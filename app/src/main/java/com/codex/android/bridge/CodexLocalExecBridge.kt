package com.codex.android.bridge

import android.content.Context
import android.util.Log
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.codex.CodexManager
import com.codex.android.core.CodexCoreModule
import com.codex.android.runtime.linux.LinuxRuntimeFactory
import com.codex.android.runtime.root.SuRootBridge
import com.codex.android.data.chat.ChatSessionRepository
import com.codex.android.security.SecurityPolicy
import com.codex.android.security.ShellConfirmationManager
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CodexLocalExecBridge(
    private val context: Context
) {
    companion object {
        private const val TAG = "CodexLocalExecBridge"
        private const val EXEC_TIMEOUT_MINUTES = 10L
        private const val MAX_HISTORY_MESSAGES = 16
        private const val MAX_HISTORY_CHARS = 12000
    }

    private data class DirectCommandPlan(
        val command: String,
        val permissionLevel: AndroidShellExecutor.PermissionLevel,
        val description: String,
        val target: ToolRouter.ExecutionTarget = ToolRouter.ExecutionTarget.ROOT,
        val routeAction: String = "",
        val routePath: String = "",
        val routeValue: String = ""
    )

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        RUNNING,
        ERROR
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatRepository = ChatSessionRepository.getInstance(context)
    private val rootBridge = SuRootBridge()
    private val toolRouter = ToolRouter(context, LinuxRuntimeFactory.create(context), rootBridge)
    private var currentJob: Job? = null
    @Volatile
    private var currentProcess: Process? = null

    var onStreamMessage: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnectionChange: ((ConnectionState) -> Unit)? = null

    fun isReady(): Boolean = CodexRuntimeService.state.value == RuntimeState.RUNNING

    fun sendPrompt(prompt: String) {
        val chatId = chatRepository.ensureInitialChat(mode = "local").id
        sendPrompt(chatId, prompt)
    }

    fun sendPrompt(chatId: String, prompt: String, freshChat: Boolean = false) {
        if (currentJob?.isActive == true) {
            onError?.invoke("Локальный Codex уже обрабатывает предыдущий запрос")
            return
        }

        val effectiveChatId = if (freshChat) {
            chatRepository.createChat(title = prompt.take(48), mode = "local").id
        } else {
            chatRepository.setSelectedChat(chatId)
            chatId
        }

        chatRepository.appendMessage(effectiveChatId, "user", prompt)

        currentJob = scope.launch {
            runPrompt(effectiveChatId, prompt)
        }
    }

    fun cancelStream() {
        currentJob?.cancel()
        currentProcess?.destroy()
        currentProcess?.destroyForcibly()
        currentProcess = null
        currentJob = null
        onStreamMessage?.invoke("__STREAM_CANCELLED__")
        onConnectionChange?.invoke(if (isReady()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
    }

    fun clearHistory() {
        chatRepository.getSelectedChatId()?.let { clearHistory(it) }
    }

    fun clearHistory(chatId: String) {
        chatRepository.clearChat(chatId)
    }

    fun destroy() {
        cancelStream()
        scope.cancel()
    }

    private suspend fun runPrompt(chatId: String, prompt: String) {
        var hostOutputFile: File? = null
        var hostDebugFile: File? = null

        try {
            if (!isReady()) {
                onError?.invoke("Локальный Codex ещё не запущен")
                onConnectionChange?.invoke(ConnectionState.DISCONNECTED)
                return
            }

            val config = CodexConnectionSettings.load(context)
            if (!CodexConnectionSettings.hasApiConfiguration(config)) {
                onError?.invoke("Не заполнены адрес API, модель или ключ для локального Codex")
                onConnectionChange?.invoke(ConnectionState.ERROR)
                return
            }

            val linuxEnv = LinuxEnvironment(context)
            val linuxInfo = linuxEnv.getInfo()
            if (linuxInfo.state != LinuxEnvironment.EngineState.READY) {
                onError?.invoke("Встроенная Linux-среда не готова: ${linuxInfo.errorMessage}")
                onConnectionChange?.invoke(ConnectionState.ERROR)
                return
            }

            CodexConnectionSettings.syncCodexConfig(context)
            ensureCodexInstalledInRootfs(linuxInfo.rootfsPath)

            val routingDecision = toolRouter.analyzePrompt(prompt)
            val routingHint = routingDecision?.let {
                ToolRouter.ToolSuggestion(
                    toolId = it.toolId,
                    category = it.category,
                    executionTarget = it.executionTarget,
                    confidence = it.confidence,
                    reason = it.reason
                )
            }
            if (routingDecision != null) {
                addRoutingHintLog(routingDecision)
            }

            handleDirectCommand(prompt)?.let { directResult ->
                chatRepository.appendMessage(chatId, "assistant", directResult)
                onStreamMessage?.invoke(directResult)
                onStreamMessage?.invoke("__STREAM_END__")
                onConnectionChange?.invoke(ConnectionState.CONNECTED)
                return
            }

            val coreContext = CodexCoreModule.buildTaskContext(prompt)
            val effectivePrompt = buildConversationPrompt(chatId, prompt, routingHint, routingDecision, coreContext)

            onConnectionChange?.invoke(ConnectionState.RUNNING)

            val packageName = context.packageName
            val guestFilesDir = "/data/data/$packageName/files"
            val guestWorkspaceDir = "$guestFilesDir/workspace"
            val guestHomeDir = "/root"
            val guestConfigDir = "$guestHomeDir/.codex"
            val guestCacheDir = "$guestHomeDir/.cache"
            val outputName = "codex-last-message-${System.currentTimeMillis()}.txt"
            val debugName = "codex-exec-debug-${System.currentTimeMillis()}.log"
            val guestOutputPath = "/tmp/$outputName"
            val guestDebugPath = "/tmp/$debugName"
            hostOutputFile = File(linuxEnv.getRootfsDir(), "tmp/$outputName")
            hostDebugFile = File(linuxEnv.getRootfsDir(), "tmp/$debugName")
            hostOutputFile.parentFile?.mkdirs()
            if (hostOutputFile.exists()) hostOutputFile.delete()
            if (hostDebugFile.exists()) hostDebugFile.delete()

            val command = buildString {
                append("mkdir -p ")
                append(shellQuote(guestWorkspaceDir))
                append(" ")
                append(shellQuote(guestHomeDir))
                append(" ")
                append(shellQuote(guestCacheDir))
                append(" && export HOME=")
                append(shellQuote(guestHomeDir))
                append(" && export CODEX_HOME=")
                append(shellQuote(guestConfigDir))
                append(" && export CODEX_CONFIG_DIR=")
                append(shellQuote(guestConfigDir))
                append(" && export XDG_CONFIG_HOME=")
                append(shellQuote(guestHomeDir))
                append(" && export XDG_STATE_HOME=")
                append(shellQuote(guestHomeDir))
                append(" && export XDG_CACHE_HOME=")
                append(shellQuote(guestCacheDir))
                append(" && export TMPDIR='/tmp'")
                append(" && cd ")
                append(shellQuote(guestWorkspaceDir))
                append(" && codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox --color never -o ")
                append(shellQuote(guestOutputPath))
                append(' ')
                append(shellQuote(effectivePrompt))
                append(" > ")
                append(shellQuote(guestDebugPath))
                append(" 2>&1")
            }

            val runtimeEnv = CodexConnectionSettings.buildRuntimeEnv(context) + mapOf(
                "HOME" to guestHomeDir,
                "CODEX_HOME" to guestConfigDir,
                "CODEX_CONFIG_DIR" to guestConfigDir,
                "XDG_CONFIG_HOME" to guestHomeDir,
                "XDG_STATE_HOME" to guestHomeDir,
                "XDG_CACHE_HOME" to guestCacheDir,
            )

            Log.i(TAG, "Starting local exec")
            Log.i(TAG, "guestWorkspaceDir=$guestWorkspaceDir")
            Log.i(TAG, "guestHomeDir=$guestHomeDir")
            Log.i(TAG, "guestConfigDir=$guestConfigDir")
            Log.i(TAG, "guestOutputPath=$guestOutputPath")
            Log.i(TAG, "guestDebugPath=$guestDebugPath")
            Log.i(TAG, "command=$command")

            val process = linuxEnv.createProotProcess(command, runtimeEnv)
            currentProcess = process
            process.outputStream.close()

            val stdoutDeferred = scope.async { process.inputStream.bufferedReader().use { it.readText() } }
            val stderrDeferred = scope.async { process.errorStream.bufferedReader().use { it.readText() } }

            val finished = process.waitFor(EXEC_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val debugTail = readTail(hostDebugFile)

            if (!finished) {
                process.destroyForcibly()
                val timeoutMessage = buildString {
                    append("Локальный Codex превысил лимит ожидания")
                    if (debugTail.isNotBlank()) {
                        append("\n\nПоследние строки журнала:\n")
                        append(debugTail)
                    }
                }
                Log.e(TAG, timeoutMessage)
                onError?.invoke(timeoutMessage.take(4000))
                onConnectionChange?.invoke(ConnectionState.ERROR)
                return
            }

            if (!scope.isActive) return

            val answer = when {
                hostOutputFile.exists() -> hostOutputFile.readText().trim()
                stdout.isNotBlank() -> sanitizeCliOutput(stdout)
                else -> ""
            }

            if (process.exitValue() == 0 && answer.isNotBlank()) {
                Log.i(TAG, "Local exec completed successfully")
                chatRepository.appendMessage(chatId, "assistant", answer)
                onStreamMessage?.invoke(answer)
                onStreamMessage?.invoke("__STREAM_END__")
                onConnectionChange?.invoke(ConnectionState.CONNECTED)
            } else {
                val details = buildString {
                    if (debugTail.isNotBlank()) append(debugTail)
                    val cleanErr = sanitizeCliOutput(stderr)
                    val cleanOut = sanitizeCliOutput(stdout)
                    if (cleanErr.isNotBlank()) {
                        if (isNotBlank()) append("\n")
                        append(cleanErr)
                    }
                    if (cleanOut.isNotBlank()) {
                        if (isNotBlank()) append("\n")
                        append(cleanOut)
                    }
                }.trim().take(4000)

                val baseMessage = if (details.isNotBlank()) details else "Локальный Codex завершился без итогового ответа"
                Log.e(TAG, "Local exec failed: $baseMessage")
                chatRepository.appendMessage(chatId, "assistant", baseMessage)
                onError?.invoke(baseMessage)
                onConnectionChange?.invoke(ConnectionState.ERROR)
            }
        } catch (_: CancellationException) {
            Log.i(TAG, "Local exec cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Local exec failed", e)
            chatRepository.appendMessage(chatId, "assistant", "Ошибка локального Codex: ${e.message}")
            onError?.invoke("Ошибка локального Codex: ${e.message}")
            onConnectionChange?.invoke(ConnectionState.ERROR)
        } finally {
            currentProcess = null
            currentJob = null
            hostOutputFile?.takeIf { it.exists() }?.delete()
            hostDebugFile?.takeIf { it.exists() }?.delete()
            if (CodexRuntimeService.state.value == RuntimeState.RUNNING) {
                onConnectionChange?.invoke(ConnectionState.CONNECTED)
            }
        }
    }

    private fun readTail(file: File?, maxLines: Int = 80): String {
        if (file == null || !file.exists()) return ""

        return try {
            file.readLines()
                .takeLast(maxLines)
                .joinToString("\n")
                .trim()
                .let(::sanitizeCliOutput)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read debug tail from ${file.absolutePath}", e)
            ""
        }
    }

    private fun ensureCodexInstalledInRootfs(rootfsPath: String) {
        val manager = CodexManager(context)
        val source = manager.codexBinary
        val target = File(rootfsPath, "/usr/local/bin/codex")
        if (target.exists() && target.length() == source.length()) {
            return
        }

        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setExecutable(true, false)
    }

    private fun sanitizeCliOutput(text: String): String {
        if (text.isBlank()) return ""

        return text
            .replace(Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]"), "")
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                line.isBlank() ||
                    line.startsWith("WARNING: proceeding") ||
                    line.startsWith("Reading additional input from stdin")
            }
            .joinToString("\n")
            .trim()
    }

    private fun buildConversationPrompt(
        chatId: String,
        latestPrompt: String,
        routingHint: ToolRouter.ToolSuggestion? = null,
        routingDecision: ToolRouter.RoutingDecision? = null,
        coreContext: String? = null
    ): String {
        val snapshot = chatRepository.listMessages(chatId)
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_HISTORY_MESSAGES)

        if (snapshot.size <= 1) return latestPrompt

        val trimmedSnapshot = trimHistory(snapshot)

        val historyText = trimmedSnapshot.joinToString("\n\n") { turn ->
            val speaker = if (turn.role == "assistant") "Assistant" else "User"
            "$speaker: ${turn.content}"
        }

        return buildString {
            if (routingHint != null) {
                appendLine("Routing hint:")
                appendLine("Tool: ${routingHint.toolId}")
                appendLine("Category: ${routingHint.category}")
                appendLine("Execution target: ${routingHint.executionTarget ?: "none"}")
                appendLine("Reason: ${routingHint.reason}")
                appendLine()
            }
            if (routingDecision != null) {
                appendLine("Routing analysis:")
                appendLine("Signals: ${routingDecision.signals.joinToString(", ")}")
                appendLine("Next step: ${routingDecision.nextStep}")
                appendLine("Transition condition: ${routingDecision.transitionCondition}")
                appendLine("Confirmation required: ${routingDecision.requiresConfirmation}")
                appendLine()
            }
            if (!coreContext.isNullOrBlank()) {
                appendLine(coreContext)
                appendLine()
            }
            appendLine("Continue the same ongoing conversation.")
            appendLine("Use the previous context and do not restart the dialog from scratch.")
            appendLine("If the latest user message is short, such as 'yes', 'continue', or 'show', interpret it using the prior messages.")
            appendLine()
            appendLine("Conversation history:")
            appendLine(historyText)
            appendLine()
            appendLine("Answer the latest user message naturally and keep the current topic.")
        }.trim()
    }

    private fun trimHistory(history: List<com.codex.android.data.chat.ChatMessageEntity>): List<com.codex.android.data.chat.ChatMessageEntity> {
        val buffer = history.toMutableList()
        while (buffer.size > MAX_HISTORY_MESSAGES) {
            buffer.removeAt(0)
        }

        while (buffer.sumOf { it.content.length } > MAX_HISTORY_CHARS && buffer.size > 1) {
            buffer.removeAt(0)
        }

        return buffer
    }

    private suspend fun handleDirectCommand(prompt: String): String? {
        val plan = detectDirectCommand(prompt) ?: return null

        if (plan.permissionLevel == AndroidShellExecutor.PermissionLevel.ROOT ||
            plan.permissionLevel == AndroidShellExecutor.PermissionLevel.NORMAL) {
            if (!SecurityPolicy.isShellAllowed(context)) {
                return SecurityPolicy.shellDeniedResponse(context)
            }

            SecurityPolicy.dangerousCommandReason(plan.command)?.let { reason ->
                val approved = ShellConfirmationManager.requestApproval(plan.command, reason)
                if (!approved) {
                    return SecurityPolicy.shellRejectedByUserResponse(plan.command, reason)
                }
            }
        }

        val result = toolRouter.dispatch(
            target = plan.target,
            command = plan.command,
            action = plan.routeAction,
            path = plan.routePath,
            content = if (plan.routeValue.isBlank()) null else plan.routeValue,
            value = if (plan.routeValue.isBlank()) null else plan.routeValue
        )

        return formatDirectCommandResult(plan, result)
    }

    private fun detectDirectCommand(prompt: String): DirectCommandPlan? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()

        extractAndroidShellCommand(trimmed, lower)?.let { command ->
            return DirectCommandPlan(
                command = command,
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Explicit Root Shell command",
                target = ToolRouter.ExecutionTarget.ROOT
            )
        }

        if (lower.contains("проверь root") || lower.contains("check root")) {
            return DirectCommandPlan(
                command = "id && whoami && uname -a && getenforce 2>/dev/null && getprop ro.build.version.release 2>/dev/null && getprop ro.product.model 2>/dev/null",
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Проверка root и базовой системной информации",
                target = ToolRouter.ExecutionTarget.SYSTEM,
                routeAction = "root"
            )
        }

        if (lower.contains("selinux")) {
            return DirectCommandPlan(
                command = "getenforce 2>/dev/null || cat /sys/fs/selinux/enforce 2>/dev/null || echo SELinux status unavailable",
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Проверка состояния SELinux",
                target = ToolRouter.ExecutionTarget.ROOT
            )
        }

        if (lower.startsWith("su -c ")) {
            val innerCommand = trimmed.removePrefix("su -c ").trim().trim('"')
            if (innerCommand.isNotBlank()) {
                return DirectCommandPlan(
                    command = innerCommand,
                    permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                    description = "Выполнение root shell команды",
                    target = ToolRouter.ExecutionTarget.ROOT
                )
            }
        }

        Regex("""^(?:shell|root)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.let { match ->
            val command = match.groupValues[1].trim()
            if (command.isNotBlank()) {
                return DirectCommandPlan(
                    command = command,
                    permissionLevel = if (lower.startsWith("root:")) AndroidShellExecutor.PermissionLevel.ROOT else AndroidShellExecutor.PermissionLevel.NORMAL,
                    description = "Выполнение shell команды",
                    target = if (lower.startsWith("root:")) ToolRouter.ExecutionTarget.ROOT else ToolRouter.ExecutionTarget.LINUX
                )
            }
        }

        Regex("""^(?:выполни(?:\s+команду)?|run(?:\s+command)?)\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.let { match ->
            val command = match.groupValues[1].trim()
            if (command.isNotBlank()) {
                return DirectCommandPlan(
                    command = command,
                    permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                    description = "Выполнение команды по явному запросу",
                    target = ToolRouter.ExecutionTarget.ROOT
                )
            }
        }

        Regex("""^(?:покажи|show|read)\s+(/\S+)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.let { match ->
            val path = match.groupValues[1].trim()
            return DirectCommandPlan(
                command = "if [ -d ${shellQuote(path)} ]; then ls -la ${shellQuote(path)}; else cat ${shellQuote(path)}; fi",
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Чтение файла или просмотр каталога",
                target = ToolRouter.ExecutionTarget.FILE,
                routeAction = "read",
                routePath = path
            )
        }

        Regex("""^(?:список|list)\s+(/\S+)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.let { match ->
            val path = match.groupValues[1].trim()
            return DirectCommandPlan(
                command = "ls -la ${shellQuote(path)}",
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Просмотр содержимого каталога",
                target = ToolRouter.ExecutionTarget.FILE,
                routeAction = "list",
                routePath = path
            )
        }

        if (lower.startsWith("getprop ")) {
            val name = trimmed.removePrefix("getprop").trim()
            return DirectCommandPlan(
                command = trimmed,
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Чтение системных свойств Android",
                target = ToolRouter.ExecutionTarget.SYSTEM,
                routeAction = "getprop",
                routePath = name
            )
        }

        if (lower.startsWith("pm ")) {
            val command = trimmed.removePrefix("pm").trim()
            return DirectCommandPlan(
                command = command,
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Выполнение package manager команды",
                target = ToolRouter.ExecutionTarget.SYSTEM,
                routeAction = "pm",
                routeValue = command
            )
        }

        if (lower.startsWith("am ")) {
            val command = trimmed.removePrefix("am").trim()
            return DirectCommandPlan(
                command = command,
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Выполнение activity manager команды",
                target = ToolRouter.ExecutionTarget.SYSTEM,
                routeAction = "am",
                routeValue = command
            )
        }

        val obviousShellPrefixes = listOf("ls ", "cat ", "pm ", "am ", "id", "whoami", "uname ", "pwd", "ps ")
        if (obviousShellPrefixes.any { lower == it.trim() || lower.startsWith(it) }) {
            return DirectCommandPlan(
                command = trimmed,
                permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT,
                description = "Выполнение shell команды",
                target = ToolRouter.ExecutionTarget.ROOT
            )
        }

        return null
    }

    private fun extractAndroidShellCommand(trimmed: String, lower: String): String? {
        val shellIntentMarkers = listOf(
            "android shell",
            "root shell",
            "root-shell",
            "adb shell",
            "shell:",
            "shell command",
            "shell commands",
            "android-shell",
            "adb-shell"
        )

        val matchedMarker = shellIntentMarkers.firstOrNull { lower.contains(it) } ?: return null
        val markerIndex = lower.indexOf(matchedMarker)
        var tail = trimmed.substring(markerIndex + matchedMarker.length).trimStart()
        if (tail.isBlank()) return null

        if (tail.startsWith(":") || tail.startsWith("-")) {
            tail = tail.substring(1).trimStart()
            return tail.takeIf { it.isNotBlank() }
        }

        val tailLower = tail.lowercase()
        val obviousShellPrefixes = listOf(
            "getprop ",
            "pm ",
            "am ",
            "ls ",
            "cat ",
            "id",
            "whoami",
            "uname ",
            "pwd",
            "ps ",
            "ip ",
            "ifconfig",
            "mount",
            "settings ",
            "wm ",
            "screencap",
            "input ",
            "logcat",
            "dumpsys",
            "cmd ",
            "/system/bin/",
            "/storage/",
            "/sdcard"
        )

        if (tailLower.contains("service.d") && tailLower.endsWith(".sh")) {
            val fileName = Regex("""([A-Za-z0-9._-]+\.sh)""", RegexOption.IGNORE_CASE).find(tail)?.groupValues?.get(1)
            if (fileName != null) {
                return "cat /data/adb/service.d/$fileName"
            }
        }

        if (tail.contains(" && ") || tail.contains(" | ") || tail.contains("; ") ||
            obviousShellPrefixes.any { tailLower == it.trim() || tailLower.startsWith(it) }
        ) {
            return tail.trim().takeIf { it.isNotBlank() }
        }

        val commandIndex = obviousShellPrefixes
            .mapNotNull { marker ->
                val idx = tailLower.indexOf(marker)
                if (idx >= 0) idx else null
            }
            .minOrNull() ?: return null

        return tail.substring(commandIndex).trim().takeIf { it.isNotBlank() }
    }

    private fun formatDirectCommandResult(
        plan: DirectCommandPlan,
        result: AndroidShellExecutor.ShellResult
    ): String {
        val mode = when (plan.permissionLevel) {
            AndroidShellExecutor.PermissionLevel.ROOT -> "root"
            AndroidShellExecutor.PermissionLevel.NORMAL -> "shell"
            AndroidShellExecutor.PermissionLevel.SHIZUKU -> "shizuku"
            AndroidShellExecutor.PermissionLevel.UBUNTU_PROOT -> "proot"
        }

        val stdout = sanitizeCliOutput(result.stdout)
        val stderr = sanitizeCliOutput(result.stderr)

        return buildString {
            appendLine("Прямое выполнение $mode: ${plan.description}")
            appendLine("Команда: ${plan.command}")
            appendLine("Код выхода: ${result.exitCode}${if (result.isTimedOut) " (таймаут)" else ""}")
            if (stdout.isNotBlank()) {
                appendLine()
                appendLine("Вывод:")
                appendLine(stdout)
            }
            if (stderr.isNotBlank()) {
                appendLine()
                appendLine("Ошибки:")
                appendLine(stderr)
            }
            if (stdout.isBlank() && stderr.isBlank()) {
                appendLine()
                append("Команда завершилась без вывода.")
            }
        }.trim()
    }

    private fun addRoutingHintLog(hint: ToolRouter.RoutingDecision) {
        Log.i(
            TAG,
            "Routing hint: tool=${hint.toolId}, category=${hint.category}, target=${hint.executionTarget ?: "none"}, confidence=${hint.confidence}%, nextStep=${hint.nextStep}, transition=${hint.transitionCondition}, confirmation=${hint.requiresConfirmation}"
        )
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
