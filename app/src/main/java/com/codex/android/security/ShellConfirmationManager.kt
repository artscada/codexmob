package com.codex.android.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 危险 Shell 命令的「执行前确认」协调器（应用级单例）。
 *
 * AI 可达的 Shell 执行路径在跑命令前调用 [requestApproval]，该调用会挂起协程，
 * 同时把待确认的命令通过 [pending] 暴露给 UI（Compose）。UI 弹出对话框，
 * 用户点击「执行 / 取消」后调用 [approve] / [deny] 唤醒协程。
 *
 * 之所以是单例 object：AnyclawManager 与 CodexMCPBridge 各自持有独立实例，
 * 而 UI 只有一个宿主，需要一个共享的渠道把确认请求路由到界面。
 *
 * 若超时（默认 3 分钟）内无人响应（例如界面未在前台），默认按「拒绝」处理，
 * 避免命令永久挂起。
 */
object ShellConfirmationManager {

    private const val DEFAULT_TIMEOUT_MS = 180_000L

    data class PendingConfirmation(
        val command: String,
        val reason: String,
        internal val deferred: CompletableDeferred<Boolean>
    )

    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    /**
     * 请求用户确认执行某条危险命令，挂起直到用户响应或超时。
     *
     * @return true 表示用户批准执行，false 表示拒绝（或超时）。
     */
    suspend fun requestApproval(
        command: String,
        reason: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        // 若已有未决确认（罕见的并发场景），先将其按拒绝处理，避免被覆盖后永久挂起。
        _pending.value?.deferred?.complete(false)

        val deferred = CompletableDeferred<Boolean>()
        val request = PendingConfirmation(command, reason, deferred)
        _pending.value = request
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            // 仅当当前未决项仍是本次请求时清除，避免误清后续请求。
            if (_pending.value === request) {
                _pending.value = null
            }
        }
    }

    /** 用户批准执行当前待确认命令。 */
    fun approve() {
        _pending.value?.deferred?.complete(true)
    }

    /** 用户拒绝执行当前待确认命令。 */
    fun deny() {
        _pending.value?.deferred?.complete(false)
    }
}
