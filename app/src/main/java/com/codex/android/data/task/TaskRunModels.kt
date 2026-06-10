package com.codex.android.data.task

import kotlinx.serialization.Serializable

@Serializable
data class TaskRunRecord(
    val taskId: String,
    val requestId: String,
    val prompt: String,
    val responseMode: String,
    val stream: Boolean,
    val callbackUrl: String? = null,
    val status: String,
    val chatId: String? = null,
    val output: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val callbackAt: Long? = null
)

@Serializable
data class TaskRunRepositoryState(
    val runs: List<TaskRunRecord> = emptyList()
)

