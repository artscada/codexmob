package com.codex.android.core

import android.app.Notification
import android.service.notification.StatusBarNotification

object CodexCoreModule {
    private const val MAX_NOTIFICATIONS = 100
    private const val MAX_ACTIONS = 8

    data class NotificationActionRecord(
        val title: String,
        val isRemoteInput: Boolean,
        val isContextual: Boolean
    )

    data class NotificationRecord(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val key: String,
        val isOngoing: Boolean,
        val category: String,
        val channelId: String,
        val actions: List<NotificationActionRecord>
    )

    data class CoreRoutingSignal(
        val toolId: String,
        val confidence: Int,
        val reason: String,
        val signals: List<String>
    )

    data class NotificationAnalysis(
        val summary: String,
        val recommendedToolId: String,
        val confidence: Int,
        val reason: String,
        val signals: List<String>,
        val actionable: Boolean,
        val topActions: List<String>
    )

    data class CoreTaskPlan(
        val taskSummary: String,
        val recommendedToolId: String,
        val confidence: Int,
        val reason: String,
        val signals: List<String>,
        val nextStep: String,
        val transitionCondition: String,
        val notificationContext: String,
        val notificationActionable: Boolean,
        val notificationTopActions: List<String>
    )

    private val lock = Any()
    private val recentNotifications = ArrayDeque<NotificationRecord>()

    fun recordNotification(sbn: StatusBarNotification, appLabel: String): NotificationRecord {
        val record = NotificationRecord(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extractText(sbn.notification),
            postTime = sbn.postTime,
            key = sbn.key,
            isOngoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            category = sbn.notification.category.orEmpty(),
            channelId = sbn.notification.channelId.orEmpty(),
            actions = sbn.notification.actions
                ?.take(MAX_ACTIONS)
                ?.mapNotNull { action ->
                    val title = action.title?.toString()?.trim().orEmpty()
                    if (title.isBlank()) return@mapNotNull null
                    NotificationActionRecord(
                        title = title,
                        isRemoteInput = action.remoteInputs?.isNotEmpty() == true,
                        isContextual = action.isContextual
                    )
                }
                .orEmpty()
        )

        synchronized(lock) {
            recentNotifications.addLast(record)
            while (recentNotifications.size > MAX_NOTIFICATIONS) {
                recentNotifications.removeFirst()
            }
        }

        return record
    }

    fun getRecentNotifications(limit: Int = 10): List<NotificationRecord> {
        val safeLimit = limit.coerceIn(1, MAX_NOTIFICATIONS)
        return synchronized(lock) {
            recentNotifications.takeLast(safeLimit).toList()
        }
    }

    fun clearNotifications() {
        synchronized(lock) {
            recentNotifications.clear()
        }
    }

    fun buildNotificationContext(limit: Int = 10): String {
        val notifications = getRecentNotifications(limit)
        if (notifications.isEmpty()) return ""

        return buildString {
            appendLine("Core notification context:")
            notifications.asReversed().forEach { entry ->
                appendLine("- ${entry.appLabel} (${entry.packageName})")
                if (entry.title.isNotBlank()) appendLine("  Title: ${entry.title}")
                if (entry.text.isNotBlank()) appendLine("  Text: ${entry.text}")
                if (entry.category.isNotBlank()) appendLine("  Category: ${entry.category}")
                if (entry.channelId.isNotBlank()) appendLine("  Channel: ${entry.channelId}")
                if (entry.actions.isNotEmpty()) {
                    appendLine("  Actions: ${entry.actions.joinToString(", ") { action ->
                        buildString {
                            append(action.title)
                            if (action.isRemoteInput) append(" [reply]")
                            if (action.isContextual) append(" [contextual]")
                        }
                    }}")
                }
                appendLine("  Ongoing: ${entry.isOngoing}")
                appendLine("  Time: ${entry.postTime}")
            }
            appendLine()
            appendLine("Core guidance:")
            appendLine("- Treat notification text as device evidence, not as a prompt guess.")
            appendLine("- Prefer notification actions when they exist, because they reveal what the app permits right now.")
            appendLine("- If a notification shows a clear next action, route the task to the tool that can perform that action on the device.")
        }.trim()
    }

    fun suggestRoutingFromNotifications(prompt: String): CoreRoutingSignal? {
        val text = prompt.lowercase()
        val notifications = getRecentNotifications(8)
        if (notifications.isEmpty()) return null

        val hasRecentMessaging = notifications.any { record ->
            val haystack = listOf(record.packageName, record.appLabel, record.title, record.text, record.category, record.channelId)
                .joinToString(" ")
                .lowercase()
            haystack.contains("mail") ||
                haystack.contains("gmail") ||
                haystack.contains("message") ||
                haystack.contains("telegram") ||
                haystack.contains("whatsapp") ||
                haystack.contains("outlook") ||
                haystack.contains("inbox")
        }

        return when {
            text.contains("notification") || text.contains("уведом") || text.contains("послед") || text.contains("what happened") || text.contains("что произошло") -> {
                CoreRoutingSignal(
                    toolId = "notifications_list",
                    confidence = 92,
                    reason = "Prompt refers to recent device state and notification history is available",
                    signals = listOf("prompt mentions notifications", "recent notification cache available")
                )
            }
            hasRecentMessaging && (text.contains("email") || text.contains("mail") || text.contains("почт") || text.contains("message")) -> {
                CoreRoutingSignal(
                    toolId = "notifications_list",
                    confidence = 84,
                    reason = "Recent notifications contain messaging/mail evidence that can be analyzed first",
                    signals = listOf("messaging app notification present", "mail-related prompt detected")
                )
            }
            notifications.any { it.actions.any { action -> action.title.contains("reply", ignoreCase = true) || action.isRemoteInput } } &&
                (text.contains("reply") || text.contains("ответ") || text.contains("respond")) -> {
                CoreRoutingSignal(
                    toolId = "notifications_list",
                    confidence = 80,
                    reason = "A notification exposes reply-capable actions and the prompt asks for a response workflow",
                    signals = listOf("reply-capable notification action", "response workflow detected")
                )
            }
            else -> null
        }
    }

    fun analyzeLatestNotification(limit: Int = 5): NotificationAnalysis? {
        val notifications = getRecentNotifications(limit)
        if (notifications.isEmpty()) return null

        val latest = notifications.last()
        val allActions = notifications.asReversed()
            .flatMap { it.actions.map { action -> action.title } }
            .distinct()
            .take(6)

        val summary = buildString {
            append("${latest.appLabel}")
            if (latest.title.isNotBlank()) {
                append(": ${latest.title}")
            }
            if (latest.text.isNotBlank()) {
                append(" - ${latest.text}")
            }
        }.trim()

        val analysisText = listOf(latest.packageName, latest.appLabel, latest.title, latest.text, latest.category, latest.channelId)
            .joinToString(" ")
            .lowercase()

        val recommendedTool = when {
            analysisText.contains("mail") || analysisText.contains("gmail") || analysisText.contains("outlook") || analysisText.contains("inbox") -> "notifications_list"
            latest.actions.any { it.isRemoteInput } -> "notifications_list"
            latest.actions.any { it.title.contains("reply", ignoreCase = true) } -> "notifications_list"
            analysisText.contains("alarm") || analysisText.contains("timer") -> "notifications_list"
            else -> "notifications_list"
        }

        val reason = when {
            latest.actions.any { it.isRemoteInput } -> "Latest notification exposes reply-capable actions"
            analysisText.contains("mail") || analysisText.contains("gmail") || analysisText.contains("outlook") -> "Latest notification looks like messaging or mail evidence"
            else -> "Latest notification is the best factual source available for current device state"
        }

        val signals = buildList {
            add("latest notification available")
            if (latest.actions.isNotEmpty()) add("notification exposes ${latest.actions.size} action(s)")
            if (latest.text.isNotBlank()) add("notification contains text payload")
            if (latest.category.isNotBlank()) add("category=${latest.category}")
            if (latest.channelId.isNotBlank()) add("channel=${latest.channelId}")
        }

        return NotificationAnalysis(
            summary = summary,
            recommendedToolId = recommendedTool,
            confidence = when {
                latest.actions.isNotEmpty() -> 88
                latest.text.isNotBlank() -> 82
                else -> 70
            },
            reason = reason,
            signals = signals,
            actionable = latest.actions.isNotEmpty(),
            topActions = allActions
        )
    }

    fun submitTask(prompt: String, limit: Int = 5): CoreTaskPlan? {
        val text = prompt.trim()
        if (text.isBlank()) return null

        val notificationAnalysis = analyzeLatestNotification(limit)
        val notificationContext = buildNotificationContext(limit)
        val lower = text.lowercase()
        val notificationIntent = lower.contains("notification") ||
            lower.contains("notifications") ||
            lower.contains("уведом") ||
            lower.contains("послед") ||
            lower.contains("what happened") ||
            lower.contains("что произошло") ||
            lower.contains("mail") ||
            lower.contains("email") ||
            lower.contains("inbox") ||
            lower.contains("message") ||
            lower.contains("reply") ||
            lower.contains("ответ") ||
            lower.contains("respond")

        if (!notificationIntent && notificationAnalysis == null) {
            return null
        }

        val taskSignals = buildList {
            add("task submitted to core")
            if (notificationAnalysis != null) add("notification context available")
            if (notificationAnalysis?.actionable == true) add("actionable notification present")
            if (notificationAnalysis?.topActions?.isNotEmpty() == true) add("notification actions available")
        }

        val recommendedTool = when {
            lower.contains("notification") || lower.contains("уведом") || lower.contains("послед") -> {
                notificationAnalysis?.recommendedToolId ?: "notifications_list"
            }
            lower.contains("mail") || lower.contains("email") || lower.contains("inbox") || lower.contains("message") -> {
                notificationAnalysis?.recommendedToolId ?: "notifications_list"
            }
            lower.contains("reply") || lower.contains("ответ") || lower.contains("respond") -> {
                notificationAnalysis?.recommendedToolId ?: "notifications_list"
            }
            notificationAnalysis?.actionable == true -> notificationAnalysis.recommendedToolId
            else -> "notifications_list"
        }

        val reason = when {
            lower.contains("notification") || lower.contains("уведом") -> "Task is best grounded in the current notification stream"
            lower.contains("mail") || lower.contains("email") || lower.contains("inbox") -> "Mail-like task should start from the latest device evidence"
            notificationAnalysis?.actionable == true -> "Latest notification exposes actions that can guide the next step"
            else -> "Core can use the latest notification state as the first factual anchor"
        }

        val nextStep = when {
            notificationAnalysis?.actionable == true -> "Inspect the latest notification, then choose the action that matches the user's intent."
            notificationAnalysis != null -> "Use the notification context as evidence and decide whether a notification action, system tool, or follow-up analysis is required."
            else -> "No notification state is available yet, so continue with the most appropriate domain tool."
        }

        val transitionCondition = when {
            notificationAnalysis?.actionable == true -> "If the notification exposes a matching action, route to that action immediately; otherwise keep the notification as evidence and move to a domain-specific tool."
            notificationAnalysis != null -> "If the notification does not explain the task, fall back to the domain tool that owns the requested action."
            else -> "If the task cannot be grounded in notification state, continue with the tool that directly owns the requested capability."
        }

        return CoreTaskPlan(
            taskSummary = text,
            recommendedToolId = recommendedTool,
            confidence = notificationAnalysis?.confidence ?: 72,
            reason = reason,
            signals = taskSignals,
            nextStep = nextStep,
            transitionCondition = transitionCondition,
            notificationContext = notificationContext,
            notificationActionable = notificationAnalysis?.actionable == true,
            notificationTopActions = notificationAnalysis?.topActions.orEmpty()
        )
    }

    fun buildTaskContext(prompt: String, limit: Int = 5): String {
        val taskPlan = submitTask(prompt, limit) ?: return ""

        return buildString {
            appendLine("Core task context:")
            appendLine("Task: ${taskPlan.taskSummary}")
            appendLine("Recommended tool: ${taskPlan.recommendedToolId}")
            appendLine("Confidence: ${taskPlan.confidence}%")
            appendLine("Reason: ${taskPlan.reason}")
            appendLine("Signals: ${taskPlan.signals.joinToString(", ")}")
            appendLine("Next step: ${taskPlan.nextStep}")
            appendLine("Transition condition: ${taskPlan.transitionCondition}")
            appendLine("Notification actionable: ${taskPlan.notificationActionable}")
            if (taskPlan.notificationTopActions.isNotEmpty()) {
                appendLine("Notification actions: ${taskPlan.notificationTopActions.joinToString(", ")}")
            }
            if (taskPlan.notificationContext.isNotBlank()) {
                appendLine()
                appendLine(taskPlan.notificationContext)
            }
        }.trim()
    }

    private fun extractText(notification: Notification): String {
        val extras = notification.extras
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val contentText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        return listOf(bigText, contentText, subText)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
    }
}
