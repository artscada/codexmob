package com.codex.android.runtime.linux

import android.content.Context
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.LinuxEnvironment
import java.io.File

class ProotBackend(
    private val context: Context
) : LinuxBackend {
    private val linuxEnv = LinuxEnvironment(context)

    override val name: String = "proot"

    override fun isReady(): Boolean = linuxEnv.getInfo().state == LinuxEnvironment.EngineState.READY

    override suspend fun install(): Boolean = linuxEnv.installRootfs()

    override suspend fun prepare(): Boolean = isReady()

    override suspend fun run(
        command: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): AndroidShellExecutor.ShellResult {
        if (!isReady()) {
            return AndroidShellExecutor.ShellResult(-1, "", "proot backend is not ready")
        }
        return linuxEnv.runCommand(command, timeoutMs)
    }

    override suspend fun runScript(
        scriptText: String,
        scriptName: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): AndroidShellExecutor.ShellResult {
        val scriptPath = "tmp/${sanitizeScriptName(scriptName)}"
        createScript(scriptPath, "#!/bin/bash\n$scriptText\n")
        return run("/bin/bash /$scriptPath", timeoutMs, env)
    }

    override suspend fun createScript(relativePath: String, content: String): Boolean {
        val target = File(rootfsDir(), relativePath.removePrefix("/"))
        target.parentFile?.mkdirs()
        target.writeText(content)
        target.setExecutable(true, false)
        return true
    }

    override fun createProcess(command: String, env: Map<String, String>): Process {
        return linuxEnv.createProotProcess(command, env)
    }

    override fun workspaceDir(): File = File(context.filesDir, "workspace")

    override fun rootfsDir(): File = linuxEnv.getRootfsDir()

    private fun sanitizeScriptName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
