package com.codex.android.runtime.linux

import android.content.Context
import com.codex.android.runtime.root.RootBridge
import com.codex.android.util.AndroidShellExecutor
import java.io.File

class ChrootBackend(
    private val context: Context,
    private val rootBridge: RootBridge,
    private val installer: ChrootInstaller = ChrootInstaller(context)
) : LinuxBackend {
    private val mountManager = ChrootMountManager(context, rootBridge, installer.rootfsDir())

    override val name: String = "chroot"

    override fun isReady(): Boolean = mountManager.isReady()

    override suspend fun install(): Boolean = installer.install()

    override suspend fun prepare(): Boolean = mountManager.mountAll()

    override suspend fun run(
        command: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): AndroidShellExecutor.ShellResult {
        if (!isReady()) {
            val details = readinessDetails()
            return AndroidShellExecutor.ShellResult(-1, "", if (details.isBlank()) "chroot backend is not ready" else details)
        }
        return mountManager.execInChroot(command, timeoutMs, env)
    }

    override suspend fun runScript(
        scriptText: String,
        scriptName: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): AndroidShellExecutor.ShellResult {
        val safeName = sanitizeScriptName(scriptName)
        val scriptPath = "/tmp/$safeName"
        createScript(scriptPath, "#!/bin/bash\n$scriptText\n")
        return run("/bin/bash $scriptPath", timeoutMs, env)
    }

    override suspend fun createScript(relativePath: String, content: String): Boolean {
        val target = File(rootfsDir(), relativePath.removePrefix("/"))
        target.parentFile?.mkdirs()
        target.writeText(content)
        target.setExecutable(true, false)
        return true
    }

    override fun createProcess(command: String, env: Map<String, String>): Process {
        val chrootCommand = buildChrootShellCommand(command, env)
        return ProcessBuilder("su", "-c", chrootCommand)
            .redirectErrorStream(false)
            .start()
    }

    override fun workspaceDir(): File = File(context.filesDir, "workspace")

    override fun rootfsDir(): File = installer.rootfsDir()

    override fun readinessDetails(): String {
        val details = mountManager.readinessDetails()
        if (details.isNotBlank()) return details
        val lastError = mountManager.lastError()
        if (lastError.isNotBlank()) return lastError
        return if (isReady()) "" else "chroot backend is not ready"
    }

    private fun sanitizeScriptName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun buildChrootShellCommand(command: String, env: Map<String, String>): String {
        val mergedEnv = buildMap {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            putAll(env)
        }
        val exports = mergedEnv.entries.joinToString("; ") { (k, v) -> "export $k=${shellQuote(v)}" }
        val chrootCommand = "chroot ${shellQuote(rootfsDir().absolutePath)} /bin/bash -lc ${shellQuote(command)}"
        return if (exports.isBlank()) {
            chrootCommand
        } else {
            "$exports; exec $chrootCommand"
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
