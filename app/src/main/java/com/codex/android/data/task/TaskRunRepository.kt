package com.codex.android.data.task

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class TaskRunRepository private constructor(
    context: Context
) {
    companion object {
        private const val TAG = "TaskRunRepository"
        private const val MAX_RUNS = 200

        @Volatile
        private var INSTANCE: TaskRunRepository? = null

        fun getInstance(context: Context): TaskRunRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskRunRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val storageFile = File(context.filesDir, "task_runs.json")
    private val lock = Any()

    @Volatile
    private var loaded = false
    private var state = TaskRunRepositoryState()

    fun createAccepted(
        taskId: String,
        requestId: String,
        prompt: String,
        responseMode: String,
        stream: Boolean,
        callbackUrl: String?
    ): TaskRunRecord {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            val record = TaskRunRecord(
                taskId = taskId,
                requestId = requestId,
                prompt = prompt,
                responseMode = responseMode,
                stream = stream,
                callbackUrl = callbackUrl,
                status = "accepted",
                createdAt = now,
                updatedAt = now
            )
            upsertLocked(record)
            return record
        }
    }

    fun markRunning(taskId: String, chatId: String? = null): TaskRunRecord? {
        synchronized(lock) {
            ensureLoadedLocked()
            val existing = state.runs.firstOrNull { it.taskId == taskId } ?: return null
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                status = "running",
                chatId = chatId ?: existing.chatId,
                updatedAt = now,
                startedAt = existing.startedAt ?: now
            )
            upsertLocked(updated)
            return updated
        }
    }

    fun markFinished(
        taskId: String,
        success: Boolean,
        chatId: String? = null,
        output: String? = null,
        error: String? = null,
        statusOverride: String? = null
    ): TaskRunRecord? {
        synchronized(lock) {
            ensureLoadedLocked()
            val existing = state.runs.firstOrNull { it.taskId == taskId } ?: return null
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                status = statusOverride ?: if (success) "succeeded" else "failed",
                chatId = chatId ?: existing.chatId,
                output = output,
                error = error,
                updatedAt = now,
                finishedAt = now,
                startedAt = existing.startedAt ?: now
            )
            upsertLocked(updated)
            return updated
        }
    }

    fun markCallbackResult(taskId: String, sent: Boolean, error: String? = null): TaskRunRecord? {
        synchronized(lock) {
            ensureLoadedLocked()
            val existing = state.runs.firstOrNull { it.taskId == taskId } ?: return null
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                status = if (sent) "callback_sent" else "callback_failed",
                error = error ?: existing.error,
                updatedAt = now,
                callbackAt = now,
                finishedAt = existing.finishedAt ?: now
            )
            upsertLocked(updated)
            return updated
        }
    }

    fun getRun(taskId: String): TaskRunRecord? {
        synchronized(lock) {
            ensureLoadedLocked()
            return state.runs.firstOrNull { it.taskId == taskId }
        }
    }

    fun listRuns(limit: Int = 50, status: String? = null): List<TaskRunRecord> {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalizedStatus = status?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            return state.runs
                .asSequence()
                .filter { normalizedStatus == null || it.status.lowercase() == normalizedStatus }
                .sortedByDescending { it.createdAt }
                .take(limit.coerceIn(1, MAX_RUNS))
                .toList()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return

        state = try {
            if (storageFile.exists()) {
                json.decodeFromString<TaskRunRepositoryState>(storageFile.readText())
            } else {
                TaskRunRepositoryState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load task runs", e)
            TaskRunRepositoryState()
        }
        loaded = true
    }

    private fun upsertLocked(record: TaskRunRecord) {
        val updated = state.runs.filterNot { it.taskId == record.taskId } + record
        state = state.copy(
            runs = updated
                .sortedByDescending { it.createdAt }
                .take(MAX_RUNS)
        )
        saveLocked()
    }

    private fun saveLocked() {
        try {
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save task runs", e)
        }
    }
}
