package com.codex.android.bridge.gui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AutomationTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AutomationTriggerRecv"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (AutomationScheduler.ACTION_TRIGGER != intent.action) return

        val taskId = intent.getIntExtra("task_id", -1)
        if (taskId == -1) {
            Log.e(TAG, "Triggered automation with invalid task ID.")
            return
        }

        Log.i(TAG, "Triggered background execution for task ID: $taskId")
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Codex:AutomationWakeLock")
        wakeLock.acquire(10 * 60 * 1000L) // 10 mins timeout

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RouteDatabaseHelper.getInstance(context)
                val task = db.getAutomationTask(taskId)
                
                if (task != null && task.enabled) {
                    Log.i(TAG, "Running task prompt: '${task.prompt}'")
                    val result = GuiAutomationEngine.getInstance(context).executeGuiAction(
                        action = "do_task",
                        path = "",
                        content = task.prompt,
                        timeoutMs = 180_000L
                    )
                    Log.i(TAG, "Task $taskId completed with code: ${result.exitCode}")
                    
                    // Reschedule for the next run
                    AutomationScheduler.scheduleTask(context, task)
                } else {
                    Log.w(TAG, "Task $taskId is disabled or not found in database. Skipping.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing scheduled task $taskId", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }
}
