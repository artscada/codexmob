package com.codex.android.runtime.root

import com.codex.android.util.AndroidShellExecutor

interface RootBridge {
    fun isAvailable(): Boolean

    suspend fun run(
        command: String,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult

    suspend fun read(path: String): AndroidShellExecutor.ShellResult
    suspend fun write(path: String, content: String): AndroidShellExecutor.ShellResult
    suspend fun list(path: String): AndroidShellExecutor.ShellResult
    suspend fun iptablesDump(): AndroidShellExecutor.ShellResult
    suspend fun iptablesApply(rules: String): AndroidShellExecutor.ShellResult
    suspend fun getProp(name: String): AndroidShellExecutor.ShellResult
    suspend fun setProp(name: String, value: String): AndroidShellExecutor.ShellResult
    suspend fun execPm(command: String): AndroidShellExecutor.ShellResult
    suspend fun execAm(command: String): AndroidShellExecutor.ShellResult
}
