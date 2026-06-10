package com.codex.android.runtime.linux

import android.content.Context
import com.codex.android.runtime.root.RootBridge
import com.codex.android.util.AndroidShellExecutor
import java.io.File

class ChrootMountManager(
    private val context: Context,
    private val rootBridge: RootBridge,
    private val rootfs: File
) {
    @Volatile
    private var lastErrorMessage: String = ""

    fun isReady(): Boolean {
        return rootBridge.isAvailable() && rootfs.isDirectory() && hasShell()
    }

    fun readinessDetails(): String {
        if (!rootBridge.isAvailable()) {
            return "root bridge is unavailable: no root access"
        }
        if (!rootfs.exists()) {
            return "rootfs not found: ${rootfs.absolutePath}"
        }
        if (!rootfs.isDirectory) {
            return "rootfs path is not a directory: ${rootfs.absolutePath}"
        }
        if (!hasShell()) {
            return "rootfs is missing /bin/bash or /bin/sh: ${rootfs.absolutePath}"
        }
        return ""
    }

    suspend fun mountAll(): Boolean {
        if (!rootBridge.isAvailable()) {
            lastErrorMessage = "root bridge is unavailable: no root access"
            return false
        }
        if (!rootfs.exists() || !rootfs.isDirectory) {
            lastErrorMessage = "rootfs not found: ${rootfs.absolutePath}"
            return false
        }
        if (!hasShell()) {
            lastErrorMessage = "rootfs is missing /bin/bash or /bin/sh: ${rootfs.absolutePath}"
            return false
        }

        val packageData = "/data/data/${context.packageName}"
        val commands = listOf(
            "mkdir -p '${rootfs.absolutePath}/dev' '${rootfs.absolutePath}/proc' '${rootfs.absolutePath}/sys' '${rootfs.absolutePath}/tmp' '${rootfs.absolutePath}/root' '${rootfs.absolutePath}/home' '${rootfs.absolutePath}/workspace' '${rootfs.absolutePath}/storage'",
            "mountpoint -q '${rootfs.absolutePath}/dev' || mount --bind /dev '${rootfs.absolutePath}/dev'",
            "mountpoint -q '${rootfs.absolutePath}/proc' || mount -t proc proc '${rootfs.absolutePath}/proc'",
            "mountpoint -q '${rootfs.absolutePath}/sys' || mount --bind /sys '${rootfs.absolutePath}/sys'",
            "mountpoint -q '${rootfs.absolutePath}/storage' || mount --bind /storage '${rootfs.absolutePath}/storage'",
            "mkdir -p '${rootfs.absolutePath}$packageData'",
            "mountpoint -q '${rootfs.absolutePath}$packageData' || mount --bind '$packageData' '${rootfs.absolutePath}$packageData'"
        )

        for (cmd in commands) {
            val result = rootBridge.run(cmd)
            if (result.exitCode != 0) {
                lastErrorMessage = buildString {
                    append("failed to mount chroot path")
                    append(": ")
                    append(cmd)
                    if (result.stderr.isNotBlank()) {
                        append(" | stderr: ")
                        append(result.stderr.trim())
                    }
                    if (result.stdout.isNotBlank()) {
                        append(" | stdout: ")
                        append(result.stdout.trim())
                    }
                }
                return false
            }
        }
        lastErrorMessage = ""
        return true
    }

    suspend fun execInChroot(
        command: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): AndroidShellExecutor.ShellResult {
        val mergedEnv = buildMap {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            putAll(env)
        }
        val exports = mergedEnv.entries.joinToString("; ") { (k, v) ->
            "export $k=${shellQuote(v)}"
        }
        val chrootCmd = buildString {
            if (exports.isNotBlank()) {
                append(exports)
                append("; ")
            }
            append("chroot ")
            append(shellQuote(rootfs.absolutePath))
            append(" /bin/bash -lc ")
            append(shellQuote(command))
        }
        return rootBridge.run(chrootCmd, timeoutMs)
    }

    fun lastError(): String = lastErrorMessage

    private fun hasShell(): Boolean {
        return File(rootfs, "bin/bash").canExecute() || File(rootfs, "bin/sh").canExecute()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
