package com.codex.android.util

import android.content.Context
import android.util.Log
import com.codex.android.codex.CodexManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 开发环境管理器。
 *
 * 使用内置 proot + Ubuntu rootfs 运行 Linux 程序。
 * 不再需要外部 Termux 安装。
 */
class DevelopmentEnvironment(val context: Context) {

    companion object {
        private const val TAG = "DevelopmentEnvironment"

        // 自包含路径（在 APP 内部）
        const val APP_BIN = "bin"
        const val APP_HOME = "home"

        // Android 系统 shell
        const val SYSTEM_SH = "/system/bin/sh"
        const val SYSTEM_BIN = "/system/bin"
    }

    /**
     * 环境状态
     */
    enum class EnvState {
        /** 自包含模式（无 proot Linux） */
        SELF_CONTAINED,
        /** 自包含 Linux（proot + rootfs）已就绪 */
        SELF_CONTAINED_LINUX,
        /** 环境出错 */
        ERROR
    }

    /**
     * 环境信息
     */
    data class EnvInfo(
        val state: EnvState = EnvState.SELF_CONTAINED,
        val hasNodeJs: Boolean = false,
        val hasPython: Boolean = false,
        val hasGit: Boolean = false,
        val hasUbuntu: Boolean = false,
        val hasCodex: Boolean = false,
        val hasProot: Boolean = false,
        val nodeVersion: String = "",
        val pythonVersion: String = "",
        val gitVersion: String = "",
        val ubuntuVersion: String = "",
        val errorMessage: String = ""
    )

    data class ToolInstallResult(
        val success: Boolean,
        val message: String = ""
    )

    private data class BundledToolState(
        val hasNodeJs: Boolean,
        val hasPython: Boolean,
        val hasGit: Boolean,
        val nodeVersion: String,
        val pythonVersion: String,
        val gitVersion: String
    )

    fun getAppBinDir(): File = File(context.filesDir, APP_BIN).also { it.mkdirs() }
    fun getAppHomeDir(): File = File(context.filesDir, APP_HOME).also { it.mkdirs() }

    /**
     * 完整环境检测。
     * 只检测内置 Linux 环境（proot + Ubuntu rootfs）。
     */
    suspend fun getEnvironmentInfo(): EnvInfo = withContext(Dispatchers.IO) {
        val appBinDir = getAppBinDir()
        val hasCodexSelf = File(appBinDir, "codex").canExecute() || CodexManager(context).isInstalled()

        val linuxEnv = LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()
        val hasSelfContainedLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY

        if (hasSelfContainedLinux) {
            val bundledTools = inspectBundledTools(linuxEnv)

            return@withContext EnvInfo(
                state = EnvState.SELF_CONTAINED_LINUX,
                hasCodex = hasCodexSelf,
                hasNodeJs = bundledTools.hasNodeJs,
                hasPython = bundledTools.hasPython,
                hasGit = bundledTools.hasGit,
                hasUbuntu = true,
                nodeVersion = bundledTools.nodeVersion,
                pythonVersion = bundledTools.pythonVersion,
                gitVersion = bundledTools.gitVersion,
                ubuntuVersion = "24.04 LTS (proot)"
            )
        }
        return@withContext EnvInfo(
            state = EnvState.SELF_CONTAINED,
            hasCodex = hasCodexSelf
        )
    }

    /**
     * 创建 proot 进程（供 AndroidShellExecutor 使用）
     */
    fun createProotProcess(command: String, env: Map<String, String> = emptyMap()): java.lang.Process {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.createProotProcess(command, env)
    }

    /**
     * 安装自包含 Linux 环境（proot + Ubuntu rootfs）
     */
    suspend fun installSelfContainedLinux(
        onProgress: ((Long, Long) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.installRootfs(onProgress, onStatus)
    }

    suspend fun installDevelopmentTools(
        onLog: ((String) -> Unit)? = null
    ): ToolInstallResult = withContext(Dispatchers.IO) {
        val linuxEnv = LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()
        if (linuxInfo.state != LinuxEnvironment.EngineState.READY) {
            return@withContext ToolInstallResult(false, "Сначала установи встроенную Linux-среду")
        }

        fun emit(line: String) {
            onLog?.invoke(line)
        }

        emit("Проверяю встроенный образ farm-base...")
        val before = inspectBundledTools(linuxEnv)
        if (before.hasNodeJs && before.hasPython && before.hasGit) {
            emit("Готовый образ уже содержит Node.js, Python и Git.")
            return@withContext ToolInstallResult(true, "Инструменты уже встроены в образ")
        }

        emit("В текущем образе не хватает инструментов.")
        emit("Переустанавливаю встроенный образ целиком вместо apt...")

        val reinstalled = linuxEnv.installRootfs(
            onStatus = { emit(it) }
        )

        if (!reinstalled) {
            return@withContext ToolInstallResult(false, "Не удалось переустановить встроенный образ")
        }

        val after = inspectBundledTools(linuxEnv)
        if (after.hasNodeJs && after.hasPython && after.hasGit) {
            emit("Готовый образ farm-base установлен полностью.")
            return@withContext ToolInstallResult(true, "Готовый образ с инструментами установлен")
        }

        return@withContext ToolInstallResult(false, "Образ переустановлен, но инструменты в нём не обнаружены")
    }

    /**
     * 检查自包含 Linux 状态
     */
    fun getSelfContainedLinuxInfo(): LinuxEnvironment.LinuxEnvInfo {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.getInfo()
    }

    private suspend fun detectVersion(linuxEnv: LinuxEnvironment, command: String): String {
        return try {
            val result = linuxEnv.runCommand(command, timeoutMs = 20_000L)
            if (result.exitCode == 0) {
                result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось определить версию для команды: $command", e)
            ""
        }
    }

    private suspend fun inspectBundledTools(linuxEnv: LinuxEnvironment): BundledToolState {
        val rootfs = linuxEnv.getRootfsDir()
        val hasNode = existingPath(rootfs, "usr/bin/node", "usr/bin/nodejs") != null
        val hasPython = existingPath(rootfs, "usr/bin/python3") != null
        val hasGit = existingPath(rootfs, "usr/bin/git") != null

        val nodeVersion = if (hasNode) {
            detectVersion(linuxEnv, "node --version || nodejs --version").ifBlank { "Встроен в образ" }
        } else ""
        val pythonVersion = if (hasPython) {
            detectVersion(linuxEnv, "python3 --version").ifBlank { "Встроен в образ" }
        } else ""
        val gitVersion = if (hasGit) {
            detectVersion(linuxEnv, "git --version").ifBlank { "Встроен в образ" }
        } else ""

        return BundledToolState(
            hasNodeJs = hasNode,
            hasPython = hasPython,
            hasGit = hasGit,
            nodeVersion = nodeVersion,
            pythonVersion = pythonVersion,
            gitVersion = gitVersion
        )
    }

    private fun existingPath(rootfs: File, vararg relativePaths: String): File? {
        return relativePaths
            .map { File(rootfs, it) }
            .firstOrNull { it.exists() && it.canExecute() }
    }

    fun getSetupGuide(): String {
        return """
╔══════════════════════════════════════╗
║      Codex Android 开发环境          ║
╚══════════════════════════════════════╝

⚡ 内置 Linux 环境（免 Termux）
• 使用 proot 引擎在 App 内运行 Ubuntu 24.04 LTS
• 无需安装任何第三方 App
• 在"环境"页面点击"一键安装 Linux 环境"即可

完成后你将获得：
• Ubuntu 24.04 LTS 环境
• 可通过 proot 运行 Codex
        """.trimIndent()
    }
}
