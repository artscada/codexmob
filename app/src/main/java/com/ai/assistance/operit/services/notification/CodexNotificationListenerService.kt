package com.ai.assistance.operit.services.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.codex.android.core.CodexCoreModule

class CodexNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        CodexCoreModule.recordNotification(sbn, resolveAppLabel(sbn.packageName))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep a recent history cache rather than deleting immediately.
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo)?.toString() ?: packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
