package com.codex.android.integrations.http

import com.codex.android.data.task.TaskRunRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

enum class ExternalChatResponseMode {
    SYNC,
    ASYNC_CALLBACK
}

@Serializable
data class ExternalChatHealthResponse(
    val status: String = "ok",
    val enabled: Boolean,
    @SerialName("service_running")
    val serviceRunning: Boolean,
    val port: Int,
    @SerialName("version_name")
    val versionName: String
)

@Serializable
data class ExternalChatHttpRequest(
    @SerialName("request_id")
    val requestId: String? = null,
    val message: String? = null,
    @SerialName("chat_id")
    val chatId: String? = null,
    @SerialName("create_new_chat")
    val createNewChat: Boolean = false,
    @SerialName("create_if_none")
    val createIfNone: Boolean = true,
    val stream: Boolean = false,
    @SerialName("response_mode")
    val responseMode: String? = null,
    @SerialName("callback_url")
    val callbackUrl: String? = null
) {
    fun resolvedRequestId(): String = requestId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    fun normalizedResponseMode(): ExternalChatResponseMode? {
        val normalized = responseMode?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "sync" }
        return when (normalized) {
            "sync" -> ExternalChatResponseMode.SYNC
            "async_callback" -> ExternalChatResponseMode.ASYNC_CALLBACK
            else -> null
        }
    }
}

@Serializable
data class ExternalTaskRunRequest(
    @SerialName("task_id")
    val taskId: String? = null,
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("prompt")
    val prompt: String? = null,
    @SerialName("chat_id")
    val chatId: String? = null,
    @SerialName("create_new_chat")
    val createNewChat: Boolean = true,
    @SerialName("create_if_none")
    val createIfNone: Boolean = true,
    @SerialName("stream")
    val stream: Boolean = false,
    @SerialName("response_mode")
    val responseMode: String? = null,
    @SerialName("callback_url")
    val callbackUrl: String? = null
) {
    fun resolvedTaskId(): String = taskId?.takeIf { it.isNotBlank() }
        ?: requestId?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()

    fun toChatRequest(): ExternalChatHttpRequest {
        val resolvedId = resolvedTaskId()
        return ExternalChatHttpRequest(
            requestId = resolvedId,
            message = prompt,
            chatId = chatId,
            createNewChat = createNewChat,
            createIfNone = createIfNone,
            stream = stream,
            responseMode = responseMode,
            callbackUrl = callbackUrl
        )
    }
}

@Serializable
data class ExternalChatAcceptedResponse(
    @SerialName("request_id")
    val requestId: String,
    val accepted: Boolean = true,
    val status: String = "accepted"
)

@Serializable
data class ExternalTaskAcceptedResponse(
    @SerialName("task_id")
    val taskId: String,
    @SerialName("request_id")
    val requestId: String,
    val accepted: Boolean = true,
    val status: String = "accepted"
)

@Serializable
data class ExternalChatResult(
    @SerialName("request_id")
    val requestId: String? = null,
    val success: Boolean,
    @SerialName("chat_id")
    val chatId: String? = null,
    @SerialName("ai_response")
    val aiResponse: String? = null,
    val error: String? = null
)

@Serializable
data class ExternalTaskRunResult(
    @SerialName("task_id")
    val taskId: String,
    @SerialName("request_id")
    val requestId: String,
    val success: Boolean,
    @SerialName("chat_id")
    val chatId: String? = null,
    val output: String? = null,
    val error: String? = null
)

@Serializable
data class ExternalTaskRunListResponse(
    val runs: List<TaskRunRecord>
)

@Serializable
data class ExternalTaskCancelResponse(
    @SerialName("task_id")
    val taskId: String,
    val cancelled: Boolean,
    val status: String,
    val error: String? = null
)

@Serializable
data class ExternalChatStreamEnvelope(
    val event: String,
    @SerialName("task_id")
    val taskId: String? = null,
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("chat_id")
    val chatId: String? = null,
    val delta: String? = null,
    val success: Boolean? = null,
    @SerialName("ai_response")
    val aiResponse: String? = null,
    val error: String? = null
)
