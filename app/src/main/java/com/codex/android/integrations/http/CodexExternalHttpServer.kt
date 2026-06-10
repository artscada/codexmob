package com.codex.android.integrations.http

import android.content.Context
import android.util.Log
import com.codex.android.BuildConfig
import com.codex.android.bridge.CodexApiBridge
import com.codex.android.bridge.CodexLocalExecBridge
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.data.chat.ChatSessionRepository
import com.codex.android.data.preferences.ExternalHttpApiPreferences
import com.codex.android.data.task.TaskRunRepository
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.IOException
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CodexExternalHttpServer private constructor(
    context: Context
) : NanoHTTPD(LISTEN_HOST, ExternalHttpApiPreferences.getInstance(context).getPort()) {

    companion object {
        private const val TAG = "CodexExternalHttpServer"
        private const val LISTEN_HOST = "0.0.0.0"
        private const val HEALTH_PATH = "/api/health"
        private const val CHAT_PATH = "/api/external-chat"
        private const val RUN_TASK_PATH = "/api/run-task"
        private const val RUNS_PATH = "/api/runs"
        private const val SSE_MIME_TYPE = "text/event-stream; charset=utf-8"
        private const val SSE_PIPE_BUFFER_SIZE = 64 * 1024
        private const val STREAM_EVENT_START = "start"
        private const val STREAM_EVENT_DELTA = "delta"
        private const val STREAM_EVENT_DONE = "done"
        private const val STREAM_EVENT_ERROR = "error"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var INSTANCE: CodexExternalHttpServer? = null

        fun getInstance(context: Context): CodexExternalHttpServer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CodexExternalHttpServer(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun recreate(context: Context): CodexExternalHttpServer {
            return synchronized(this) {
                INSTANCE?.stopServer()
                CodexExternalHttpServer(context.applicationContext).also {
                    INSTANCE = it
                    it.ensureStarted()
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val preferences = ExternalHttpApiPreferences.getInstance(appContext)
    private val chatRepository = ChatSessionRepository.getInstance(appContext)
    private val taskRunRepository = TaskRunRepository.getInstance(appContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val running = AtomicBoolean(false)
    private val executionLock = Any()
    private val activeTasks = ConcurrentHashMap<String, RunningTaskControl>()
    private val callbackClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    fun ensureStarted() {
        if (!preferences.isEnabled() || running.get()) return
        start(SOCKET_READ_TIMEOUT, false)
        running.set(true)
        Log.i(TAG, "External HTTP server started on ${preferences.getPort()}")
    }

    fun stopServer() {
        if (!running.get()) return
        stop()
        running.set(false)
        Log.i(TAG, "External HTTP server stopped")
    }

    fun refreshState() {
        if (preferences.isEnabled()) {
            ensureStarted()
        } else {
            stopServer()
        }
    }

    override fun useGzipWhenAccepted(response: Response): Boolean {
        val mimeType = response.mimeType?.lowercase()
        if (mimeType?.startsWith(SSE_MIME_TYPE) == true) {
            return false
        }
        return super.useGzipWhenAccepted(response)
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").withCors()
            session.uri == HEALTH_PATH && session.method == Method.GET -> handleHealth(session)
            session.uri == CHAT_PATH && session.method == Method.POST -> handleChat(session)
            session.uri == RUN_TASK_PATH && session.method == Method.POST -> handleRunTask(session)
            session.uri.startsWith("$RUNS_PATH/") && session.uri.endsWith("/cancel") && session.method == Method.POST -> handleCancelRun(session)
            session.uri == RUNS_PATH && session.method == Method.GET -> handleListRuns(session)
            session.uri.startsWith("$RUNS_PATH/") && session.method == Method.GET -> handleGetRun(session)
            else -> jsonResponse(
                Response.Status.NOT_FOUND,
                ExternalChatResult(success = false, error = "API endpoint not found")
            ).withCors()
        }
    }

    private fun handleHealth(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        return jsonResponse(
            Response.Status.OK,
            ExternalChatHealthResponse(
                enabled = preferences.isEnabled(),
                serviceRunning = running.get(),
                port = preferences.getPort(),
                versionName = BuildConfig.VERSION_NAME
            )
        ).withCors()
    }

    private fun handleChat(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        val body = readBody(session)
        if (body.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalChatResult(success = false, error = "Request body is empty")
            ).withCors()
        }

        val request = try {
            json.decodeFromString<ExternalChatHttpRequest>(body)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalChatResult(success = false, error = "Invalid JSON: ${e.message}")
            ).withCors()
        }

        return processChatRequest(request, null, ResponseShape.CHAT)
    }

    private fun handleRunTask(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        val body = readBody(session)
        if (body.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalChatResult(success = false, error = "Request body is empty")
            ).withCors()
        }

        val request = try {
            json.decodeFromString<ExternalTaskRunRequest>(body)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalChatResult(success = false, error = "Invalid JSON: ${e.message}")
            ).withCors()
        }

        val taskId = request.resolvedTaskId()
        return processChatRequest(request.toChatRequest(), taskId, ResponseShape.TASK)
    }

    private fun handleListRuns(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
        val status = session.parameters["status"]?.firstOrNull()
        return jsonResponse(
            Response.Status.OK,
            ExternalTaskRunListResponse(
                runs = taskRunRepository.listRuns(limit = limit, status = status)
            )
        ).withCors()
    }

    private fun handleGetRun(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        val taskId = session.uri.removePrefix("$RUNS_PATH/").trim().substringBefore('/')
        if (taskId.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalChatResult(success = false, error = "Missing path parameter: task_id")
            ).withCors()
        }

        val run = taskRunRepository.getRun(taskId)
            ?: return jsonResponse(
                Response.Status.NOT_FOUND,
                ExternalTaskRunResult(
                    taskId = taskId,
                    requestId = taskId,
                    success = false,
                    error = "Run not found"
                )
            ).withCors()

        return jsonResponse(Response.Status.OK, run).withCors()
    }

    private fun handleCancelRun(session: IHTTPSession): Response {
        requireBearerToken(session)?.let { return it }

        val taskId = session.uri.removePrefix("$RUNS_PATH/").substringBefore("/cancel").trim().trimEnd('/')
        if (taskId.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                ExternalTaskCancelResponse(
                    taskId = "",
                    cancelled = false,
                    status = "invalid",
                    error = "Missing path parameter: task_id"
                )
            ).withCors()
        }

        val control = activeTasks[taskId]
        if (control == null) {
            val existing = taskRunRepository.getRun(taskId)
            return if (existing != null) {
                jsonResponse(
                    Response.Status.OK,
                    ExternalTaskCancelResponse(
                        taskId = taskId,
                        cancelled = false,
                        status = existing.status,
                        error = "Task is not running"
                    )
                ).withCors()
            } else {
                jsonResponse(
                    Response.Status.NOT_FOUND,
                    ExternalTaskCancelResponse(
                        taskId = taskId,
                        cancelled = false,
                        status = "not_found",
                        error = "Run not found"
                    )
                ).withCors()
            }
        }

        runCatching { control.cancel.invoke() }
        taskRunRepository.markFinished(
            taskId = taskId,
            success = false,
            error = "Request cancelled",
            statusOverride = "cancelled"
        )
        activeTasks.remove(taskId)

        return jsonResponse(
            Response.Status.OK,
            ExternalTaskCancelResponse(
                taskId = taskId,
                cancelled = true,
                status = "cancelled"
            )
        ).withCors()
    }

    private fun processChatRequest(
        request: ExternalChatHttpRequest,
        taskId: String?,
        responseShape: ResponseShape
    ): Response {

        val requestId = request.resolvedRequestId()
        val message = request.message?.trim().orEmpty()
        val responseMode = request.normalizedResponseMode()
        if (responseMode == null) {
            return errorResponse(
                status = Response.Status.BAD_REQUEST,
                requestId = requestId,
                taskId = taskId,
                error = "Invalid parameter: response_mode must be sync/async_callback",
                responseShape = responseShape
            )
        }

        if (message.isBlank()) {
            return errorResponse(
                status = Response.Status.BAD_REQUEST,
                requestId = requestId,
                taskId = taskId,
                error = "Missing field: message",
                responseShape = responseShape
            )
        }

        if (request.stream && responseMode == ExternalChatResponseMode.ASYNC_CALLBACK) {
            return errorResponse(
                status = Response.Status.BAD_REQUEST,
                requestId = requestId,
                taskId = taskId,
                error = "Invalid parameter: stream=true is not compatible with async_callback",
                responseShape = responseShape
            )
        }

        if (request.stream) {
            if (taskId != null) {
                taskRunRepository.createAccepted(
                    taskId = taskId,
                    requestId = requestId,
                    prompt = message,
                    responseMode = responseMode.name.lowercase(),
                    stream = true,
                    callbackUrl = request.callbackUrl?.trim()?.ifBlank { null }
                )
            }
            return sseResponse(request, requestId, taskId).withCors()
        }

        val resolvedChatId = resolveTargetChatId(request, message, forceFreshChat = taskId != null)
            ?: return errorResponse(
                status = Response.Status.BAD_REQUEST,
                requestId = requestId,
                taskId = taskId,
                error = "Target chat not found and create_if_none=false",
                responseShape = responseShape
            )

        if (taskId != null) {
            taskRunRepository.createAccepted(
                taskId = taskId,
                requestId = requestId,
                prompt = message,
                responseMode = responseMode.name.lowercase(),
                stream = false,
                callbackUrl = request.callbackUrl?.trim()?.ifBlank { null }
            )
        }

        if (responseMode == ExternalChatResponseMode.ASYNC_CALLBACK) {
            val callbackUrl = request.callbackUrl?.trim().orEmpty()
            if (callbackUrl.isBlank()) {
                return errorResponse(
                    status = Response.Status.BAD_REQUEST,
                    requestId = requestId,
                    taskId = taskId,
                    error = "Invalid parameter: callback_url is required for async_callback",
                    responseShape = responseShape
                )
            }

            val callbackUri = runCatching { java.net.URI(callbackUrl) }.getOrNull()
            if (callbackUri == null || (callbackUri.scheme != "http" && callbackUri.scheme != "https")) {
                return errorResponse(
                    status = Response.Status.BAD_REQUEST,
                    requestId = requestId,
                    taskId = taskId,
                    error = "Invalid parameter: callback_url must be http/https",
                    responseShape = responseShape
                )
            }

            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                taskId?.let { taskRunRepository.markRunning(it, resolvedChatId) }
                val result = synchronized(executionLock) {
                    runBlocking {
                        executeChatRequest(resolvedChatId, message, taskId)
                    }
                }

                taskId?.let {
                    taskRunRepository.markFinished(
                        taskId = it,
                        success = result.success,
                        chatId = resolvedChatId,
                        output = result.aiResponse,
                        error = result.error,
                        statusOverride = if (result.success) "callback_pending" else "callback_pending"
                    )
                }

                val callbackResult = postCallback(
                    callbackUrl,
                    buildCallbackPayload(
                        requestId = requestId,
                        taskId = taskId,
                        chatId = resolvedChatId,
                        result = result,
                        responseShape = responseShape
                    )
                )

                taskId?.let {
                    taskRunRepository.markCallbackResult(
                        taskId = it,
                        sent = callbackResult.success,
                        error = if (callbackResult.success) null else callbackResult.error
                    )
                }
            }

            return acceptedResponse(requestId, taskId, responseShape)
        }

        val result = synchronized(executionLock) {
            taskId?.let { taskRunRepository.markRunning(it, resolvedChatId) }
            runBlocking {
                executeChatRequest(resolvedChatId, message, taskId)
            }
        }

        taskId?.let {
            taskRunRepository.markFinished(
                taskId = it,
                success = result.success,
                chatId = resolvedChatId,
                output = result.aiResponse,
                error = result.error,
                statusOverride = if (result.error == "Request cancelled") "cancelled" else null
            )
        }

        return resultResponse(
            requestId = requestId,
            taskId = taskId,
            chatId = resolvedChatId,
            result = result,
            responseShape = responseShape
        )
    }

    private fun resolveTargetChatId(
        request: ExternalChatHttpRequest,
        message: String,
        forceFreshChat: Boolean = false
    ): String? {
        if (forceFreshChat || request.createNewChat) {
            return chatRepository.createChat(title = message.take(48), mode = currentMode()).id
        }

        val requestedChatId = request.chatId?.trim().orEmpty().ifBlank { null }
        if (requestedChatId != null) {
            val exists = chatRepository.listChats().any { it.id == requestedChatId }
            if (exists) {
                chatRepository.setSelectedChat(requestedChatId)
                return requestedChatId
            }
            if (!request.createIfNone) {
                return null
            }
        }

        val hasChats = chatRepository.listChats().isNotEmpty()
        if (!hasChats && !request.createIfNone) {
            return null
        }
        return chatRepository.ensureInitialChat(mode = currentMode()).id
    }

    private suspend fun executeChatRequest(chatId: String, message: String, taskId: String? = null): BridgeExecutionResult {
        return if (CodexConnectionSettings.isApiMode(appContext)) {
            executeWithApiBridge(chatId, message, taskId)
        } else {
            val ready = ensureLocalRuntimeReady()
            if (!ready) {
                BridgeExecutionResult(
                    success = false,
                    error = "Локальный Codex не успел запуститься. Открой приложение и проверь запуск движка Codex."
                )
            } else {
            executeWithLocalBridge(chatId, message, taskId)
            }
        }
    }

    private suspend fun ensureLocalRuntimeReady(): Boolean {
        if (CodexRuntimeService.state.value == RuntimeState.RUNNING) {
            return true
        }

        CodexRuntimeService.start(appContext)

        return withTimeoutOrNull(20_000) {
            while (CodexRuntimeService.state.value != RuntimeState.RUNNING) {
                if (CodexRuntimeService.state.value == RuntimeState.ERROR) {
                    return@withTimeoutOrNull false
                }
                delay(500)
            }
            true
        } ?: false
    }

    private fun sseResponse(request: ExternalChatHttpRequest, requestId: String, taskId: String?): Response {
        val pipeInput = PipedInputStream(SSE_PIPE_BUFFER_SIZE)
        val pipeOutput = PipedOutputStream(pipeInput)
        val resolvedChatId = resolveTargetChatId(
            request,
            request.message?.trim().orEmpty(),
            forceFreshChat = taskId != null
        )

        if (resolvedChatId == null) {
            taskId?.let {
                taskRunRepository.markFinished(
                    taskId = it,
                    success = false,
                    error = "Target chat not found and create_if_none=false"
                )
            }
            pipeOutput.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writeSseEvent(
                    writer,
                    ExternalChatStreamEnvelope(
                        event = STREAM_EVENT_ERROR,
                        taskId = taskId,
                        requestId = requestId,
                        success = false,
                        error = "Target chat not found and create_if_none=false"
                    )
                )
            }
            return buildSseNanoResponse(pipeInput)
        }

        val message = request.message?.trim().orEmpty()
        if (message.isBlank()) {
            taskId?.let {
                taskRunRepository.markFinished(
                    taskId = it,
                    success = false,
                    chatId = resolvedChatId,
                    error = "Missing field: message"
                )
            }
            pipeOutput.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writeSseEvent(
                    writer,
                    ExternalChatStreamEnvelope(
                        event = STREAM_EVENT_ERROR,
                        taskId = taskId,
                        requestId = requestId,
                        chatId = resolvedChatId,
                        success = false,
                        error = "Missing field: message"
                    )
                )
            }
            return buildSseNanoResponse(pipeInput)
        }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            pipeOutput.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                try {
                    writeSseEvent(
                        writer,
                        ExternalChatStreamEnvelope(
                            event = STREAM_EVENT_START,
                            taskId = taskId,
                            requestId = requestId,
                            chatId = resolvedChatId
                        )
                    )

                    taskId?.let { taskRunRepository.markRunning(it, resolvedChatId) }

                    if (CodexConnectionSettings.isApiMode(appContext)) {
                        streamWithApiBridge(resolvedChatId, message, requestId, taskId, writer)
                    } else {
                        val ready = ensureLocalRuntimeReady()
                        if (!ready) {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_ERROR,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = resolvedChatId,
                                    success = false,
                                    error = "Локальный Codex не успел запуститься. Открой приложение и проверь запуск движка Codex."
                                )
                            )
                            taskId?.let {
                                taskRunRepository.markFinished(
                                    taskId = it,
                                    success = false,
                                    chatId = resolvedChatId,
                                    error = "Локальный Codex не успел запуститься. Открой приложение и проверь запуск движка Codex."
                                )
                            }
                        } else {
                            streamWithLocalBridge(resolvedChatId, message, requestId, taskId, writer)
                        }
                    }
                } catch (e: IOException) {
                    Log.i(TAG, "SSE client disconnected: requestId=$requestId")
                } catch (e: Exception) {
                    Log.e(TAG, "SSE stream failed: requestId=$requestId", e)
                    runCatching {
                        writeSseEvent(
                            writer,
                            ExternalChatStreamEnvelope(
                                event = STREAM_EVENT_ERROR,
                                taskId = taskId,
                                requestId = requestId,
                                chatId = resolvedChatId,
                                success = false,
                                error = e.message ?: "Unknown error"
                            )
                        )
                    }
                    taskId?.let {
                        taskRunRepository.markFinished(
                            taskId = it,
                            success = false,
                            chatId = resolvedChatId,
                            error = e.message ?: "Unknown error"
                        )
                    }
                }
            }
        }

        return buildSseNanoResponse(pipeInput)
    }

    private suspend fun executeWithApiBridge(
        chatId: String,
        message: String,
        taskId: String? = null,
        freshChat: Boolean = false
    ): BridgeExecutionResult {
        val bridge = CodexApiBridge(appContext)
        val result = CompletableDeferred<BridgeExecutionResult>()
        val buffer = StringBuilder()

        bridge.onStreamMessage = { chunk ->
            when (chunk) {
                "__STREAM_END__" -> if (!result.isCompleted) result.complete(BridgeExecutionResult(true, aiResponse = buffer.toString().trim()))
                "__STREAM_CANCELLED__" -> if (!result.isCompleted) result.complete(BridgeExecutionResult(false, error = "Request cancelled"))
                else -> buffer.append(chunk)
            }
        }
        bridge.onError = { error ->
            if (!result.isCompleted) result.complete(BridgeExecutionResult(false, error = error))
        }

        taskId?.let { registerRunningTask(it) { bridge.cancelStream() } }
        try {
            bridge.sendPrompt(chatId, message, freshChat = freshChat)
            return result.await()
        } finally {
            taskId?.let { unregisterRunningTask(it) }
            bridge.destroy()
        }
    }

    private suspend fun executeWithLocalBridge(
        chatId: String,
        message: String,
        taskId: String? = null,
        freshChat: Boolean = false
    ): BridgeExecutionResult {
        val bridge = CodexLocalExecBridge(appContext)
        val result = CompletableDeferred<BridgeExecutionResult>()
        val buffer = StringBuilder()

        bridge.onStreamMessage = { chunk ->
            when (chunk) {
                "__STREAM_END__" -> if (!result.isCompleted) result.complete(BridgeExecutionResult(true, aiResponse = buffer.toString().trim()))
                "__STREAM_CANCELLED__" -> if (!result.isCompleted) result.complete(BridgeExecutionResult(false, error = "Request cancelled"))
                else -> {
                    if (!chunk.startsWith("Локальный Codex выполняет запрос")) {
                        buffer.append(chunk)
                    }
                }
            }
        }
        bridge.onError = { error ->
            if (!result.isCompleted) result.complete(BridgeExecutionResult(false, error = error))
        }

        taskId?.let { registerRunningTask(it) { bridge.cancelStream() } }
        try {
            bridge.sendPrompt(chatId, message, freshChat = freshChat)
            return result.await()
        } finally {
            taskId?.let { unregisterRunningTask(it) }
            bridge.destroy()
        }
    }

    private suspend fun streamWithApiBridge(
        chatId: String,
        message: String,
        requestId: String,
        taskId: String?,
        writer: BufferedWriter
    ) {
        val bridge = CodexApiBridge(appContext)
        val done = CompletableDeferred<Unit>()
        val finalText = StringBuilder()

        bridge.onStreamMessage = { chunk ->
            when (chunk) {
                "__STREAM_END__" -> {
                    if (!done.isCompleted) {
                        runCatching {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_DONE,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = chatId,
                                    success = true,
                                    aiResponse = finalText.toString().trim()
                                )
                            )
                        }
                        taskId?.let {
                            taskRunRepository.markFinished(
                                taskId = it,
                                success = true,
                                chatId = chatId,
                                output = finalText.toString().trim()
                            )
                        }
                        done.complete(Unit)
                    }
                }

                "__STREAM_CANCELLED__" -> {
                    if (!done.isCompleted) {
                        runCatching {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_ERROR,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = chatId,
                                    success = false,
                                    aiResponse = finalText.toString().trim().ifBlank { null },
                                    error = "Request cancelled"
                                )
                            )
                        }
                        taskId?.let {
                            taskRunRepository.markFinished(
                                taskId = it,
                                success = false,
                                chatId = chatId,
                                output = finalText.toString().trim().ifBlank { null },
                                error = "Request cancelled",
                                statusOverride = "cancelled"
                            )
                        }
                        done.complete(Unit)
                    }
                }

                else -> {
                    finalText.append(chunk)
                    runCatching {
                        writeSseEvent(
                            writer,
                            ExternalChatStreamEnvelope(
                                event = STREAM_EVENT_DELTA,
                                taskId = taskId,
                                requestId = requestId,
                                chatId = chatId,
                                delta = chunk
                            )
                        )
                    }
                }
            }
        }
        bridge.onError = { error ->
            if (!done.isCompleted) {
                runCatching {
                    writeSseEvent(
                        writer,
                        ExternalChatStreamEnvelope(
                            event = STREAM_EVENT_ERROR,
                            taskId = taskId,
                            requestId = requestId,
                            chatId = chatId,
                            success = false,
                            aiResponse = finalText.toString().trim().ifBlank { null },
                            error = error
                        )
                    )
                }
                taskId?.let {
                    taskRunRepository.markFinished(
                        taskId = it,
                        success = false,
                        chatId = chatId,
                        output = finalText.toString().trim().ifBlank { null },
                        error = error
                    )
                }
                done.complete(Unit)
            }
        }

        taskId?.let { registerRunningTask(it) { bridge.cancelStream() } }
        try {
            bridge.sendPrompt(chatId, message)
            done.await()
        } finally {
            taskId?.let { unregisterRunningTask(it) }
            bridge.destroy()
        }
    }

    private suspend fun streamWithLocalBridge(
        chatId: String,
        message: String,
        requestId: String,
        taskId: String?,
        writer: BufferedWriter
    ) {
        val bridge = CodexLocalExecBridge(appContext)
        val done = CompletableDeferred<Unit>()
        val finalText = StringBuilder()

        bridge.onStreamMessage = { chunk ->
            when (chunk) {
                "__STREAM_END__" -> {
                    if (!done.isCompleted) {
                        runCatching {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_DONE,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = chatId,
                                    success = true,
                                    aiResponse = finalText.toString().trim()
                                )
                            )
                        }
                        taskId?.let {
                            taskRunRepository.markFinished(
                                taskId = it,
                                success = true,
                                chatId = chatId,
                                output = finalText.toString().trim()
                            )
                        }
                        done.complete(Unit)
                    }
                }

                "__STREAM_CANCELLED__" -> {
                    if (!done.isCompleted) {
                        runCatching {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_ERROR,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = chatId,
                                    success = false,
                                    aiResponse = finalText.toString().trim().ifBlank { null },
                                    error = "Request cancelled"
                                )
                            )
                        }
                        taskId?.let {
                            taskRunRepository.markFinished(
                                taskId = it,
                                success = false,
                                chatId = chatId,
                                output = finalText.toString().trim().ifBlank { null },
                                error = "Request cancelled",
                                statusOverride = "cancelled"
                            )
                        }
                        done.complete(Unit)
                    }
                }

                else -> {
                    if (!chunk.startsWith("Локальный Codex выполняет запрос")) {
                        finalText.append(chunk)
                        runCatching {
                            writeSseEvent(
                                writer,
                                ExternalChatStreamEnvelope(
                                    event = STREAM_EVENT_DELTA,
                                    taskId = taskId,
                                    requestId = requestId,
                                    chatId = chatId,
                                    delta = chunk
                                )
                            )
                        }
                    }
                }
            }
        }
        bridge.onError = { error ->
            if (!done.isCompleted) {
                runCatching {
                    writeSseEvent(
                        writer,
                        ExternalChatStreamEnvelope(
                            event = STREAM_EVENT_ERROR,
                            taskId = taskId,
                            requestId = requestId,
                            chatId = chatId,
                            success = false,
                            aiResponse = finalText.toString().trim().ifBlank { null },
                            error = error
                        )
                    )
                }
                taskId?.let {
                    taskRunRepository.markFinished(
                        taskId = it,
                        success = false,
                        chatId = chatId,
                        output = finalText.toString().trim().ifBlank { null },
                        error = error
                    )
                }
                done.complete(Unit)
            }
        }

        taskId?.let { registerRunningTask(it) { bridge.cancelStream() } }
        try {
            bridge.sendPrompt(chatId, message)
            done.await()
        } finally {
            taskId?.let { unregisterRunningTask(it) }
            bridge.destroy()
        }
    }

    private fun acceptedResponse(requestId: String, taskId: String?, responseShape: ResponseShape): Response {
        return when (responseShape) {
            ResponseShape.CHAT -> jsonResponse(
                Response.Status.ACCEPTED,
                ExternalChatAcceptedResponse(requestId = requestId)
            )

            ResponseShape.TASK -> jsonResponse(
                Response.Status.ACCEPTED,
                ExternalTaskAcceptedResponse(
                    taskId = taskId ?: requestId,
                    requestId = requestId
                )
            )
        }.withCors()
    }

    private fun errorResponse(
        status: Response.Status,
        requestId: String,
        taskId: String?,
        error: String,
        responseShape: ResponseShape
    ): Response {
        return when (responseShape) {
            ResponseShape.CHAT -> jsonResponse(
                status,
                ExternalChatResult(
                    requestId = requestId,
                    success = false,
                    error = error
                )
            )

            ResponseShape.TASK -> jsonResponse(
                status,
                ExternalTaskRunResult(
                    taskId = taskId ?: requestId,
                    requestId = requestId,
                    success = false,
                    error = error
                )
            )
        }.withCors()
    }

    private fun resultResponse(
        requestId: String,
        taskId: String?,
        chatId: String,
        result: BridgeExecutionResult,
        responseShape: ResponseShape
    ): Response {
        return when (responseShape) {
            ResponseShape.CHAT -> jsonResponse(
                Response.Status.OK,
                ExternalChatResult(
                    requestId = requestId,
                    success = result.success,
                    chatId = chatId,
                    aiResponse = result.aiResponse,
                    error = result.error
                )
            )

            ResponseShape.TASK -> jsonResponse(
                Response.Status.OK,
                ExternalTaskRunResult(
                    taskId = taskId ?: requestId,
                    requestId = requestId,
                    success = result.success,
                    chatId = chatId,
                    output = result.aiResponse,
                    error = result.error
                )
            )
        }.withCors()
    }

    private fun buildCallbackPayload(
        requestId: String,
        taskId: String?,
        chatId: String,
        result: BridgeExecutionResult,
        responseShape: ResponseShape
    ): String {
        return when (responseShape) {
            ResponseShape.CHAT -> json.encodeToString(
                ExternalChatResult(
                    requestId = requestId,
                    success = result.success,
                    chatId = chatId,
                    aiResponse = result.aiResponse,
                    error = result.error
                )
            )

            ResponseShape.TASK -> json.encodeToString(
                ExternalTaskRunResult(
                    taskId = taskId ?: requestId,
                    requestId = requestId,
                    success = result.success,
                    chatId = chatId,
                    output = result.aiResponse,
                    error = result.error
                )
            )
        }
    }

    private fun currentMode(): String {
        return if (CodexConnectionSettings.isApiMode(appContext)) "api" else "local"
    }

    private fun requireBearerToken(session: IHTTPSession): Response? {
        val expected = preferences.getBearerToken()
        val auth = session.headers["authorization"]?.trim().orEmpty()
        if (auth != "Bearer $expected") {
            return jsonResponse(
                Response.Status.UNAUTHORIZED,
                ExternalChatResult(success = false, error = "Unauthorized")
            ).apply {
                addHeader("WWW-Authenticate", "Bearer")
            }.withCors()
        }
        return null
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            val postData = files["postData"].orEmpty()
            if (postData.isBlank()) {
                ""
            } else {
                val postDataFile = File(postData)
                if (postDataFile.exists()) {
                    postDataFile.readBytes().toString(Charsets.UTF_8)
                } else {
                    postData
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalChatResult): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalChatHealthResponse): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalChatAcceptedResponse): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalTaskAcceptedResponse): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalTaskRunResult): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalTaskRunListResponse): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: ExternalTaskCancelResponse): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun jsonResponse(status: Response.Status, payload: com.codex.android.data.task.TaskRunRecord): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(payload))
    }

    private fun postCallback(callbackUrl: String, payloadJson: String): CallbackPostResult {
        try {
            val requestBody = payloadJson.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(callbackUrl)
                .post(requestBody)
                .build()

            callbackClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Async callback failed: url=$callbackUrl code=${response.code}")
                    return CallbackPostResult(false, "HTTP ${response.code}")
                }
            }
            return CallbackPostResult(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Async callback failed: url=$callbackUrl", e)
            return CallbackPostResult(false, e.message ?: "Unknown callback error")
        }
    }

    private fun buildSseNanoResponse(pipeInput: PipedInputStream): Response {
        val responseInput = object : FilterInputStream(pipeInput) {
            override fun close() {
                super.close()
                runCatching { pipeInput.close() }
            }
        }

        return newChunkedResponse(Response.Status.OK, SSE_MIME_TYPE, responseInput).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun writeSseEvent(writer: BufferedWriter, envelope: ExternalChatStreamEnvelope) {
        writer.write("event: ${envelope.event}\n")
        writer.write("data: ${json.encodeToString(envelope)}\n\n")
        writer.flush()
    }

    private fun Response.withCors(): Response {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return this
    }

    private data class BridgeExecutionResult(
        val success: Boolean,
        val aiResponse: String? = null,
        val error: String? = null
    )

    private data class CallbackPostResult(
        val success: Boolean,
        val error: String?
    )

    private data class RunningTaskControl(
        val cancel: () -> Unit
    )

    private fun registerRunningTask(taskId: String, cancel: () -> Unit) {
        activeTasks[taskId] = RunningTaskControl(cancel = cancel)
    }

    private fun unregisterRunningTask(taskId: String) {
        activeTasks.remove(taskId)
    }

    private enum class ResponseShape {
        CHAT,
        TASK
    }
}
