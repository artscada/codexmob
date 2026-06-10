package com.codex.android.runtime.linux

import com.codex.android.util.AndroidShellExecutor
import java.io.File

interface LinuxBackend {
    val name: String

    fun isReady(): Boolean
    suspend fun install(): Boolean
    suspend fun prepare(): Boolean

    suspend fun run(
        command: String,
        timeoutMs: Long = 120_000L,
        env: Map<String, String> = emptyMap()
    ): AndroidShellExecutor.ShellResult

    suspend fun runScript(
        scriptText: String,
        scriptName: String = "script.sh",
        timeoutMs: Long = 120_000L,
        env: Map<String, String> = emptyMap()
    ): AndroidShellExecutor.ShellResult

    suspend fun createScript(relativePath: String, content: String): Boolean
    fun createProcess(
        command: String,
        env: Map<String, String> = emptyMap()
    ): Process
    fun workspaceDir(): File
    fun rootfsDir(): File
    fun readinessDetails(): String = if (isReady()) "" else "$name backend is not ready"
}
