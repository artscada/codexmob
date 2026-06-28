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
    
    // GUI Automation Actions
    suspend fun click(x: Int, y: Int): AndroidShellExecutor.ShellResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): AndroidShellExecutor.ShellResult
    suspend fun inputText(text: String): AndroidShellExecutor.ShellResult
    suspend fun takeScreenshot(outputPath: String): AndroidShellExecutor.ShellResult
    suspend fun dumpWindowHierarchy(outputPath: String): AndroidShellExecutor.ShellResult
}
