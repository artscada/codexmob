package com.codex.android.bridge

import com.codex.android.core.CodexCoreModule
import com.codex.android.runtime.linux.LinuxBackend
import com.codex.android.runtime.root.RootBridge
import com.codex.android.util.AndroidShellExecutor

class ToolRouter(
    private val context: android.content.Context,
    private val linux: LinuxBackend,
    private val root: RootBridge
) {
    enum class ExecutionTarget {
        LINUX,
        ROOT,
        FILE,
        NETWORK,
        SYSTEM,
        GUI
    }

    enum class ToolCategory {
        LINUX,
        ROOT,
        FILE,
        NETWORK,
        SYSTEM,
        NOTIFICATION,
        GUI,
        UNKNOWN
    }

    enum class RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    data class ToolSpec(
        val id: String,
        val category: ToolCategory,
        val executionTarget: ExecutionTarget? = null,
        val action: String = "",
        val description: String,
        val params: List<String> = emptyList(),
        val riskLevel: RiskLevel = RiskLevel.LOW,
        val needsConfirmation: Boolean = false
    )

    data class ToolSuggestion(
        val toolId: String,
        val category: ToolCategory,
        val executionTarget: ExecutionTarget?,
        val confidence: Int,
        val reason: String
    )

    data class RoutingDecision(
        val toolId: String,
        val category: ToolCategory,
        val executionTarget: ExecutionTarget?,
        val confidence: Int,
        val reason: String,
        val signals: List<String>,
        val nextStep: String,
        val transitionCondition: String,
        val requiresConfirmation: Boolean
    )

    suspend fun linuxRun(command: String, timeoutMs: Long = 120_000L): AndroidShellExecutor.ShellResult {
        return linux.run(command, timeoutMs)
    }

    suspend fun linuxRunScript(
        scriptText: String,
        name: String = "script.sh",
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult {
        return linux.runScript(scriptText, name, timeoutMs)
    }

    suspend fun linuxWriteFile(path: String, content: String): Boolean {
        return linux.createScript(path, content)
    }

    suspend fun routeLinux(command: String, timeoutMs: Long = 120_000L): AndroidShellExecutor.ShellResult {
        return linuxRun(command, timeoutMs)
    }

    suspend fun routeRoot(command: String, timeoutMs: Long = 120_000L): AndroidShellExecutor.ShellResult {
        return rootRun(command, timeoutMs)
    }

    suspend fun routeFile(
        action: String,
        path: String,
        content: String? = null,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult {
        return when (action.lowercase()) {
            "read" -> root.read(path)
            "list" -> root.list(path)
            "write" -> {
                val payload = content ?: return AndroidShellExecutor.ShellResult(-1, "", "content required")
                root.write(path, payload)
            }
            else -> AndroidShellExecutor.ShellResult(-1, "", "unsupported file action: $action")
        }
    }

    suspend fun routeNetwork(
        action: String,
        payload: String? = null,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult {
        return when (action.lowercase()) {
            "iptables_dump" -> iptablesDump()
            "iptables_apply" -> {
                val rules = payload ?: return AndroidShellExecutor.ShellResult(-1, "", "rules required")
                iptablesApply(rules)
            }
            "root" -> rootRun(payload.orEmpty(), timeoutMs)
            else -> AndroidShellExecutor.ShellResult(-1, "", "unsupported network action: $action")
        }
    }

    suspend fun routeSystem(
        action: String,
        name: String? = null,
        value: String? = null,
        command: String? = null,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult {
        return when (action.lowercase()) {
            "getprop" -> {
                val propName = name ?: return AndroidShellExecutor.ShellResult(-1, "", "name required")
                systemGetProp(propName)
            }
            "setprop" -> {
                val propName = name ?: return AndroidShellExecutor.ShellResult(-1, "", "name required")
                val propValue = value ?: return AndroidShellExecutor.ShellResult(-1, "", "value required")
                systemSetProp(propName, propValue)
            }
            "pm" -> systemPm(command.orEmpty())
            "am" -> systemAm(command.orEmpty())
            "root" -> rootRun(command.orEmpty(), timeoutMs)
            else -> AndroidShellExecutor.ShellResult(-1, "", "unsupported system action: $action")
        }
    }

    suspend fun rootRun(command: String, timeoutMs: Long = 120_000L): AndroidShellExecutor.ShellResult {
        return root.run(command, timeoutMs)
    }

    suspend fun iptablesDump(): AndroidShellExecutor.ShellResult = root.iptablesDump()
    suspend fun iptablesApply(rules: String): AndroidShellExecutor.ShellResult = root.iptablesApply(rules)
    suspend fun systemGetProp(name: String): AndroidShellExecutor.ShellResult = root.getProp(name)
    suspend fun systemSetProp(name: String, value: String): AndroidShellExecutor.ShellResult = root.setProp(name, value)
    suspend fun systemPm(command: String): AndroidShellExecutor.ShellResult = root.execPm(command)
    suspend fun systemAm(command: String): AndroidShellExecutor.ShellResult = root.execAm(command)

    suspend fun routeGui(
        action: String,
        path: String = "",
        content: String? = null,
        value: String? = null,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult {
        return com.codex.android.bridge.gui.GuiAutomationEngine.getInstance(context).executeGuiAction(
            action = action,
            path = path,
            content = content ?: value,
            timeoutMs = timeoutMs
        )
    }

    suspend fun dispatch(
        target: ExecutionTarget,
        command: String = "",
        timeoutMs: Long = 120_000L,
        action: String = "",
        path: String = "",
        content: String? = null,
        value: String? = null
    ): AndroidShellExecutor.ShellResult {
        return when (target) {
            ExecutionTarget.LINUX -> routeLinux(command, timeoutMs)
            ExecutionTarget.ROOT -> routeRoot(command, timeoutMs)
            ExecutionTarget.FILE -> routeFile(action = action, path = path, content = content, timeoutMs = timeoutMs)
            ExecutionTarget.NETWORK -> routeNetwork(action = action, payload = content ?: value, timeoutMs = timeoutMs)
            ExecutionTarget.SYSTEM -> routeSystem(
                action = action,
                name = path.ifBlank { null },
                value = value,
                command = command,
                timeoutMs = timeoutMs
            )
            ExecutionTarget.GUI -> routeGui(
                action = action,
                path = path,
                content = content,
                value = value,
                timeoutMs = timeoutMs
            )
        }
    }

    fun standardTools(): List<ToolSpec> = listOf(
        ToolSpec(
            id = "linux_run",
            category = ToolCategory.LINUX,
            executionTarget = ExecutionTarget.LINUX,
            action = "run",
            description = "Run a command inside the Linux runtime",
            params = listOf("command", "timeoutMs")
        ),
        ToolSpec(
            id = "linux_run_script",
            category = ToolCategory.LINUX,
            executionTarget = ExecutionTarget.LINUX,
            action = "script",
            description = "Create and run a script inside the Linux runtime",
            params = listOf("scriptText", "name", "timeoutMs")
        ),
        ToolSpec(
            id = "linux_write_file",
            category = ToolCategory.FILE,
            executionTarget = ExecutionTarget.LINUX,
            action = "write",
            description = "Write a file in the Linux workspace or rootfs",
            params = listOf("path", "content")
        ),
        ToolSpec(
            id = "linux_read_file",
            category = ToolCategory.FILE,
            executionTarget = ExecutionTarget.LINUX,
            action = "read",
            description = "Read a file from the Linux workspace or rootfs",
            params = listOf("path")
        ),
        ToolSpec(
            id = "root_run",
            category = ToolCategory.ROOT,
            executionTarget = ExecutionTarget.ROOT,
            action = "run",
            description = "Run a command on Android host with root privileges",
            params = listOf("command", "timeoutMs"),
            riskLevel = RiskLevel.HIGH,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "root_read",
            category = ToolCategory.FILE,
            executionTarget = ExecutionTarget.ROOT,
            action = "read",
            description = "Read a file or list a directory on the Android host",
            params = listOf("path"),
            riskLevel = RiskLevel.MEDIUM,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "root_write",
            category = ToolCategory.FILE,
            executionTarget = ExecutionTarget.ROOT,
            action = "write",
            description = "Write a file on the Android host",
            params = listOf("path", "content"),
            riskLevel = RiskLevel.HIGH,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "system_getprop",
            category = ToolCategory.SYSTEM,
            executionTarget = ExecutionTarget.SYSTEM,
            action = "getprop",
            description = "Read Android system properties",
            params = listOf("name")
        ),
        ToolSpec(
            id = "system_pm",
            category = ToolCategory.SYSTEM,
            executionTarget = ExecutionTarget.SYSTEM,
            action = "pm",
            description = "Run Android package manager commands",
            params = listOf("command"),
            riskLevel = RiskLevel.MEDIUM,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "system_am",
            category = ToolCategory.SYSTEM,
            executionTarget = ExecutionTarget.SYSTEM,
            action = "am",
            description = "Run Android activity manager commands",
            params = listOf("command"),
            riskLevel = RiskLevel.MEDIUM,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "iptables_dump",
            category = ToolCategory.NETWORK,
            executionTarget = ExecutionTarget.NETWORK,
            action = "iptables_dump",
            description = "Dump firewall rules",
            riskLevel = RiskLevel.MEDIUM,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "iptables_apply",
            category = ToolCategory.NETWORK,
            executionTarget = ExecutionTarget.NETWORK,
            action = "iptables_apply",
            description = "Apply firewall rules",
            params = listOf("rules"),
            riskLevel = RiskLevel.HIGH,
            needsConfirmation = true
        ),
        ToolSpec(
            id = "notify",
            category = ToolCategory.NOTIFICATION,
            executionTarget = null,
            action = "notify",
            description = "Send a user-visible notification",
            params = listOf("title", "content")
        ),
        ToolSpec(
            id = "notifications_list",
            category = ToolCategory.NOTIFICATION,
            executionTarget = null,
            action = "list",
            description = "List recent system notifications",
            params = listOf("limit")
        ),
        ToolSpec(
            id = "notifications_analyze",
            category = ToolCategory.NOTIFICATION,
            executionTarget = null,
            action = "analyze",
            description = "Analyze the latest notification and infer a next action",
            params = listOf("limit")
        ),
        ToolSpec(
            id = "toast",
            category = ToolCategory.NOTIFICATION,
            executionTarget = null,
            action = "toast",
            description = "Show a short transient toast",
            params = listOf("message")
        )
    )

    fun suggestTool(prompt: String): ToolSuggestion? {
        val decision = analyzePrompt(prompt) ?: return null
        return ToolSuggestion(
            toolId = decision.toolId,
            category = decision.category,
            executionTarget = decision.executionTarget,
            confidence = decision.confidence,
            reason = decision.reason
        )
    }

    fun analyzePrompt(prompt: String): RoutingDecision? {
        val text = prompt.lowercase()
        CodexCoreModule.submitTask(prompt)?.let { taskPlan ->
            return RoutingDecision(
                toolId = taskPlan.recommendedToolId,
                category = ToolCategory.NOTIFICATION,
                executionTarget = null,
                confidence = taskPlan.confidence,
                reason = taskPlan.reason,
                signals = taskPlan.signals,
                nextStep = taskPlan.nextStep,
                transitionCondition = taskPlan.transitionCondition,
                requiresConfirmation = taskPlan.notificationActionable
            )
        }
        return when {
            text.contains("iptables") || text.contains("firewall") -> ToolSuggestion(
                toolId = if (text.contains("apply") || text.contains("allow") || text.contains("block") || text.contains("set")) "iptables_apply" else "iptables_dump",
                category = ToolCategory.NETWORK,
                executionTarget = ExecutionTarget.NETWORK,
                confidence = 86,
                reason = "Network/firewall keywords detected"
            ).toRoutingDecision(
                signals = listOf("iptables/firewall"),
                nextStep = "Run the network tool first, then inspect the returned firewall state before making changes.",
                transitionCondition = "If the dump is enough, stop there; if rules must change, continue with apply.",
                requiresConfirmation = text.contains("apply") || text.contains("allow") || text.contains("block") || text.contains("set")
            )
            text.contains("notify") || text.contains("уведом") || text.contains("toast") -> ToolSuggestion(
                toolId = if (text.contains("toast")) "toast" else "notify",
                category = ToolCategory.NOTIFICATION,
                executionTarget = null,
                confidence = 88,
                reason = "Notification keywords detected"
            ).toRoutingDecision(
                signals = listOf("notification", "toast"),
                nextStep = "Send the user-facing notification and report the outcome back to the conversation.",
                transitionCondition = "If the user wants a different output channel, switch to toast; otherwise keep notify.",
                requiresConfirmation = false
            )
            text.contains("notification") || text.contains("notifications") || text.contains("уведомл") || text.contains("recent notification") || text.contains("последн") -> ToolSuggestion(
                toolId = "notifications_list",
                category = ToolCategory.NOTIFICATION,
                executionTarget = null,
                confidence = 84,
                reason = "Recent notification query detected"
            ).toRoutingDecision(
                signals = listOf("notification history", "recent notifications"),
                nextStep = "List recent system notifications and use them as the factual source for what just happened on the device.",
                transitionCondition = "If the notifications do not contain the needed event, fall back to the app or system tool that generated it.",
                requiresConfirmation = false
            )
            text.contains("analyze notification") || text.contains("analyze notifications") || text.contains("разбери уведом") || text.contains("analyze the latest notification") -> ToolSuggestion(
                toolId = "notifications_analyze",
                category = ToolCategory.NOTIFICATION,
                executionTarget = null,
                confidence = 90,
                reason = "Notification analysis request detected"
            ).toRoutingDecision(
                signals = listOf("notification analysis request", "core can infer actions"),
                nextStep = "Analyze the latest notification, extract actions, and use that analysis to guide the next tool choice.",
                transitionCondition = "If the notification contains action buttons, route toward the action that matches the user's intent.",
                requiresConfirmation = false
            )
            text.contains("getprop") || text.contains("android version") || text.contains("верси") -> ToolSuggestion(
                toolId = "system_getprop",
                category = ToolCategory.SYSTEM,
                executionTarget = ExecutionTarget.SYSTEM,
                confidence = 82,
                reason = "System property query detected"
            ).toRoutingDecision(
                signals = listOf("getprop", "android version"),
                nextStep = "Read the requested property values and inspect the returned output before deciding on further system actions.",
                transitionCondition = "If the property is missing, broaden the query to another system source or report it as unavailable.",
                requiresConfirmation = false
            )
            text.contains("pm ") || text.contains("package ") || text.contains("install app") || text.contains("uninstall") -> ToolSuggestion(
                toolId = "system_pm",
                category = ToolCategory.SYSTEM,
                executionTarget = ExecutionTarget.SYSTEM,
                confidence = 80,
                reason = "Package manager keywords detected"
            ).toRoutingDecision(
                signals = listOf("pm", "package manager"),
                nextStep = "Run the package manager command and verify the package state from the result.",
                transitionCondition = "If install/uninstall fails, inspect the error and fall back to a more specific system action.",
                requiresConfirmation = true
            )
            text.contains("am ") || text.contains("open app") || text.contains("start app") || text.contains("launch") -> ToolSuggestion(
                toolId = "system_am",
                category = ToolCategory.SYSTEM,
                executionTarget = ExecutionTarget.SYSTEM,
                confidence = 78,
                reason = "Activity manager keywords detected"
            ).toRoutingDecision(
                signals = listOf("am", "launch"),
                nextStep = "Launch the requested activity and verify the app state from the returned output.",
                transitionCondition = "If the launch did not land in the expected screen, inspect the activity name or switch to a direct root/network/file tool.",
                requiresConfirmation = true
            )
            text.contains("python") || text.contains("bash") || text.contains("git") || text.contains("script") || text.contains("pip") -> ToolSuggestion(
                toolId = if (text.contains("script")) "linux_run_script" else "linux_run",
                category = ToolCategory.LINUX,
                executionTarget = ExecutionTarget.LINUX,
                confidence = 84,
                reason = "Linux runtime keywords detected"
            ).toRoutingDecision(
                signals = listOf("python", "bash", "git", "pip"),
                nextStep = "Use the Linux runtime first, because these tasks are best handled in a full userspace.",
                transitionCondition = "If the Linux runtime cannot complete the task, only then fall back to host/root operations.",
                requiresConfirmation = false
            )
            text.contains("file") || text.contains("read ") || text.contains("show ") || text.contains("list ") || text.contains("write ") || text.contains("каталог") || text.contains("файл") -> ToolSuggestion(
                toolId = when {
                    text.contains("write") || text.contains("запис") -> "linux_write_file"
                    text.contains("list") || text.contains("каталог") -> "linux_read_file"
                    else -> "linux_read_file"
                },
                category = ToolCategory.FILE,
                executionTarget = ExecutionTarget.LINUX,
                confidence = 72,
                reason = "File/workspace keywords detected"
            ).toRoutingDecision(
                signals = listOf("file", "read", "write", "list"),
                nextStep = "Handle the file in the Linux workspace first, then report the actual file state back to the model.",
                transitionCondition = "If the file lives on the Android host, switch to root file operations instead.",
                requiresConfirmation = false
            )
            text.contains("root") || text.contains("su ") || text.contains("mount") || text.contains("id") || text.contains("whoami") -> ToolSuggestion(
                toolId = "root_run",
                category = ToolCategory.ROOT,
                executionTarget = ExecutionTarget.ROOT,
                confidence = 68,
                reason = "Root/host keywords detected"
            ).toRoutingDecision(
                signals = listOf("root", "su", "mount", "id", "whoami"),
                nextStep = "Run the root action directly on the Android host and use the result as ground truth.",
                transitionCondition = "If the result shows the host is not root-ready, return to Linux/runtime analysis instead of guessing.",
                requiresConfirmation = true
            )
            else -> null
        }
    }

    private fun ToolSuggestion.toRoutingDecision(
        signals: List<String>,
        nextStep: String,
        transitionCondition: String,
        requiresConfirmation: Boolean
    ): RoutingDecision {
        return RoutingDecision(
            toolId = toolId,
            category = category,
            executionTarget = executionTarget,
            confidence = confidence,
            reason = reason,
            signals = signals,
            nextStep = nextStep,
            transitionCondition = transitionCondition,
            requiresConfirmation = requiresConfirmation
        )
    }
}
