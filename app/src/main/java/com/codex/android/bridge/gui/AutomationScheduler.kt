package com.codex.android.bridge.gui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.Random

object AutomationScheduler {
    private const val TAG = "AutomationScheduler"
    const val ACTION_TRIGGER = "com.codex.android.action.TRIGGER_AUTOMATION"

    fun scheduleTask(context: Context, task: AutomationTask) {
        if (!task.enabled) {
            cancelTask(context, task.id)
            return
        }

        val nextExecution = calculateNextExecutionTime(task)
        if (nextExecution <= 0) {
            Log.e(TAG, "Failed to calculate next execution time for task ${task.id}")
            return
        }

        val db = RouteDatabaseHelper.getInstance(context)
        db.updateAutomationTask(task.copy(nextExecution = nextExecution))

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AutomationTriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra("task_id", task.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExecution, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExecution, pendingIntent)
                }
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextExecution, pendingIntent)
            }
            Log.i(TAG, "Scheduled task ${task.id} to run at: ${java.util.Date(nextExecution)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling exact alarm, falling back...", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextExecution, pendingIntent)
        }
    }

    fun cancelTask(context: Context, taskId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AutomationTriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled alarm for task $taskId")
        }
    }

    fun calculateNextExecutionTime(task: AutomationTask): Long {
        val now = System.currentTimeMillis()
        if (task.mode == "scheduled") {
            val parts = task.timeValue.split(":")
            if (parts.size != 2) return 0
            val hour = parts[0].toIntOrNull() ?: return 0
            val minute = parts[1].toIntOrNull() ?: return 0

            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        } else if (task.mode == "random") {
            val ranges = task.timeValue.split("-")
            if (ranges.size != 2) return 0
            val startParts = ranges[0].split(":")
            val endParts = ranges[1].split(":")
            if (startParts.size != 2 || endParts.size != 2) return 0

            val startHour = startParts[0].toIntOrNull() ?: return 0
            val startMin = startParts[1].toIntOrNull() ?: return 0
            val endHour = endParts[0].toIntOrNull() ?: return 0
            val endMin = endParts[1].toIntOrNull() ?: return 0

            val calStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, startMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val calEnd = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, endHour)
                set(Calendar.MINUTE, endMin)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (calEnd.before(calStart)) {
                calEnd.add(Calendar.DAY_OF_YEAR, 1)
            }

            if (calEnd.timeInMillis <= now) {
                calStart.add(Calendar.DAY_OF_YEAR, 1)
                calEnd.add(Calendar.DAY_OF_YEAR, 1)
            }

            val startMillis = if (calStart.timeInMillis < now) now else calStart.timeInMillis
            val endMillis = calEnd.timeInMillis

            if (endMillis <= startMillis) {
                return now + 60000
            }

            val randomRange = endMillis - startMillis
            val randomOffset = Math.abs(Random().nextLong()) % randomRange
            return startMillis + randomOffset
        }
        return 0
    }
}
