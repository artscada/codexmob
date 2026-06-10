package com.codex.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.codex.android.ui.CodexActivity

/**
 * Codex 通知管理。
 *
 * 管理不同类型的通知频道：
 * - runtime: Codex 运行时状态
 * - task: 任务完成通知
 * - error: 错误警告
 */
object CodexNotifications {

    const val CHANNEL_RUNTIME = "codex_runtime"
    const val CHANNEL_TASK = "codex_task"
    const val CHANNEL_ERROR = "codex_error"

    private const val NOTIFICATION_TASK_DONE = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNTIME, "Codex 运行时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Codex AI 编码代理后台运行状态"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASK, "Codex 任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Codex 任务完成通知"
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ERROR, "Codex 错误", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Codex 运行错误和警告"
                enableVibration(true)
                enableLights(true)
            }
        )
    }

    fun notifyTaskDone(context: Context, title: String, summary: String) {
        val intent = Intent(context, CodexActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_TASK_DONE, notification)
    }

    fun notifyError(context: Context, title: String, detail: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}
