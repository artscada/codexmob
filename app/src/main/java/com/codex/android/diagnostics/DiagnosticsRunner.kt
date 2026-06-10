package com.codex.android.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.codex.android.bridge.CodexBridge
import com.codex.android.codex.CodexManager
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.DevelopmentEnvironment
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APP 全方位自测系统。
 *
 * 自动检测所有模块的健康状态，定位问题根因。
 * 测试项覆盖：UI/WebView、桥接通信、运行时环境、
 * 开发环境、文件系统、网络、权限等。
 */
class DiagnosticsRunner(private val context: Context) {

    companion object {
        private const val TAG = "DiagnosticsRunner"

        /**
         * 把已捕获的异常压缩成一行可读摘要（类型 + message），
         * 用于在界面上直接展示失败根因，完整堆栈仍写入 logcat。
         */
        fun exceptionSummary(e: Throwable): String {
            val type = e.javaClass.simpleName.ifBlank { e.javaClass.name }
            val msg = e.message?.trim()?.takeIf { it.isNotEmpty() }
            return if (msg != null) "$type: ${msg.take(200)}" else type
        }
    }

    /** 单条测试结果 */
    data class TestResult(
        val name: String,            // 测试名称
        val passed: Boolean,         // 是否通过
        val detail: String,          // 结果详情
        val severity: Severity = if (passed) Severity.INFO else Severity.ERROR,
        val suggestion: String = ""  // 修复建议
    )

    enum class Severity { INFO, WARNING, ERROR }

    /** 完整的诊断报告 */
    data class DiagnosticsReport(
        val timestamp: Long = System.currentTimeMillis(),
        val deviceInfo: DeviceInfo = DeviceInfo(),
        val results: List<TestResult> = emptyList()
    ) {
        val passedCount: Int get() = results.count { it.passed }
        val failedCount: Int get() = results.count { !it.passed }
        val totalCount: Int get() = results.size
        val isAllPassed: Boolean get() = failedCount == 0
    }

    data class DeviceInfo(
        val androidVersion: String = "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
        val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        val arch: String = Build.SUPPORTED_ABIS.joinToString(", "),
        val memory: String = "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB / ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB",
        val diskSpace: String = "${android.os.Environment.getExternalStorageDirectory().totalSpace / 1024 / 1024}MB доступно"
    )

    /**
     * 运行全部诊断测试
     */
    suspend fun runAll(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()

        // 1. 设备基础信息
        results.addAll(runDeviceTests())

        // 2. 权限测试
        results.addAll(runPermissionTests())

        // 3. 网络测试
        results.addAll(runNetworkTests())

        // 4. WebView 与桥接测试
        results.addAll(runBridgeTests())

        // 5. 文件系统测试
        results.addAll(runFileSystemTests())

        // 6. Codex 二进制测试
        results.addAll(runCodexBinaryTests())

        // 7. 开发环境测试
        results.addAll(runDevEnvironmentTests())

        // 8. Shell 执行测试
        results.addAll(runShellTests())

        DiagnosticsReport(
            deviceInfo = DeviceInfo(),
            results = results
        )
    }

    /**
     * 运行指定分类的测试
     */
    suspend fun runCategory(category: String): List<TestResult> = withContext(Dispatchers.IO) {
        when (category) {
            "device" -> runDeviceTests()
            "permissions" -> runPermissionTests()
            "network" -> runNetworkTests()
            "bridge" -> runBridgeTests()
            "filesystem" -> runFileSystemTests()
            "codex" -> runCodexBinaryTests()
            "environment" -> runDevEnvironmentTests()
            "shell" -> runShellTests()
            else -> emptyList()
        }
    }

    // ====== 1. 设备基础测试 ======
    private fun runDeviceTests(): List<TestResult> {
        return listOf(
            TestResult(
                "Android 版本", true,
                "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
            ),
            TestResult(
                "设备型号", true,
                "${Build.MANUFACTURER} ${Build.MODEL}"
            ),
            TestResult(
                "CPU 架构", true,
                Build.SUPPORTED_ABIS.joinToString(", ")
            ),
            TestResult(
                "内存配置", true,
                "堆内存: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB"
            ),
            TestResult(
                "是否模拟器", Build.FINGERPRINT.contains("generic") ||
                        Build.FINGERPRINT.contains("emulator"),
                if (Build.FINGERPRINT.contains("generic")) "Похоже на эмулятор" else "Физическое устройство",
                severity = if (Build.FINGERPRINT.contains("generic")) Severity.WARNING else Severity.INFO
            )
        )
    }

    // ====== 2. 权限测试 ======
    private fun runPermissionTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 检查必要权限
        val permissions = mapOf(
            "INTERNET" to android.Manifest.permission.INTERNET,
            "FOREGROUND_SERVICE" to android.Manifest.permission.FOREGROUND_SERVICE,
            "POST_NOTIFICATIONS" to android.Manifest.permission.POST_NOTIFICATIONS,
        )

        for ((name, perm) in permissions) {
            var checkError: Exception? = null
            val granted = try {
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.w(TAG, "权限检查失败: $name", e)
                checkError = e
                false
            }
            results.add(TestResult(
                "权限: $name", granted,
                when {
                    granted -> "Разрешение выдано"
                    checkError != null -> "Ошибка проверки: ${exceptionSummary(checkError)}"
                    else -> "Разрешение не выдано"
                },
                severity = if (granted) Severity.INFO else Severity.WARNING,
                suggestion = when {
                    granted -> ""
                    checkError != null -> "Во время проверки возникла ошибка: ${exceptionSummary(checkError)}"
                    else -> "Выдай разрешение $name"
                }
            ))
        }

        // Shizuku 检测
        var shizukuError: Exception? = null
        val hasShizuku = try {
            Class.forName("moe.shizuku.api.ShizukuApi")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku API 类存在性检查失败", e)
            shizukuError = e
            false
        }
        results.add(TestResult(
            "Shizuku API", hasShizuku,
            if (hasShizuku) "Доступен" else "Не установлен",
            severity = Severity.INFO,
            suggestion = if (!hasShizuku && shizukuError != null) "Ошибка проверки класса: ${exceptionSummary(shizukuError)}" else ""
        ))

        return results
    }

    // ====== 3. 网络测试 ======
    private fun runNetworkTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 网络连接检测
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        results.add(TestResult(
            "网络连接", hasInternet,
            if (hasInternet) "Подключено" else "Нет подключения",
            severity = if (hasInternet) Severity.INFO else Severity.ERROR,
            suggestion = if (!hasInternet) "Подключи устройство к сети" else ""
        ))

        // GitHub API 可达性
        var githubError: Exception? = null
        val githubReachable = try {
            val url = java.net.URL("https://api.github.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API 可达性探测失败", e)
            githubError = e
            false
        }
        results.add(TestResult(
            "GitHub API 可达", githubReachable,
            when {
                githubReachable -> "Доступен"
                githubError != null -> "Ошибка подключения: ${exceptionSummary(githubError)}"
                else -> "Ошибка подключения (HTTP не 200)"
            },
            severity = if (githubReachable) Severity.INFO else Severity.ERROR,
            suggestion = if (!githubReachable) "Проверь сеть или межсетевой экран" else ""
        ))

        // OpenAI API 可达性
        var openaiError: Exception? = null
        val openaiReachable = try {
            val url = java.net.URL("https://api.openai.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.responseCode in 200..499
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI API 可达性探测失败", e)
            openaiError = e
            false
        }
        results.add(TestResult(
            "OpenAI API 可达", openaiReachable,
            when {
                openaiReachable -> "Доступен"
                openaiError != null -> "Ошибка подключения: ${exceptionSummary(openaiError)}"
                else -> "Ошибка подключения"
            },
            severity = Severity.INFO
        ))

        // 本地端口检查 (Codex WebSocket)
        val wsPortOpen = try {
            val s = java.net.ServerSocket(9877)
            s.close()
            false // 端口可用说明 Codex 没在运行
        } catch (e: Exception) {
            true // 端口被占用说明已在运行
        }
        results.add(TestResult(
            "Codex WebSocket 端口 (9877)", true,
            if (wsPortOpen) "Codex уже работает" else "Порт свободен",
            severity = Severity.INFO
        ))

        return results
    }

    // ====== 4. WebView & 桥接测试 ======
    private fun runBridgeTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // WebView 版本
        val wvVersion = try {
            WebView.getCurrentWebViewPackage()?.versionName ?: "未知"
        } catch (e: Exception) {
            Log.w(TAG, "WebView 版本检测失败", e)
            "未知"
        }
        results.add(TestResult(
            "WebView 版本", true, wvVersion
        ))

        // WebView 可用性
        try {
            // 使用 CountDownLatch 在主线程创建 WebView
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOf<Exception?>(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val wv = android.webkit.WebView(context)
                    wv.destroy()
                } catch (e: Exception) {
                    holder[0] = e
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val error = holder[0]
            results.add(TestResult("Создание WebView", error == null,
                if (error == null) "Работает" else "Ошибка: ${error.message}",
                severity = if (error == null) Severity.INFO else Severity.ERROR
            ))
        } catch (e: Exception) {
            results.add(TestResult("Создание WebView", false, "Ошибка: ${e.message}", Severity.ERROR))
        }

        // HTML 资源检查
        var htmlError: Exception? = null
        val htmlExists = File(context.filesDir.parentFile?.parentFile, "app/src/main/assets/web/codex-ui.html")
            .exists() || try {
            context.assets.open("web/codex-ui.html").use { true }
        } catch (e: Exception) {
            Log.w(TAG, "Codex UI HTML 资源检查失败", e)
            htmlError = e
            false
        }
        results.add(TestResult(
            "Ресурс Codex UI HTML", htmlExists,
            if (htmlExists) "Файл найден" else "Файл отсутствует",
            severity = if (htmlExists) Severity.INFO else Severity.ERROR,
            suggestion = when {
                htmlExists -> ""
                htmlError != null -> "Не удалось прочитать assets/web/codex-ui.html: ${exceptionSummary(htmlError)}"
                else -> "Файл assets/web/codex-ui.html отсутствует"
            }
        ))

        // Bridge 对象可用性
        var bridgeError: Exception? = null
        val bridgeAvailable = try {
            Class.forName("com.codex.android.bridge.CodexBridge")
            true
        } catch (e: Exception) {
            Log.w(TAG, "CodexBridge 类存在性检查失败", e)
            bridgeError = e
            false
        }
        results.add(TestResult(
            "Класс CodexBridge", bridgeAvailable,
            if (bridgeAvailable) "Доступен" else "Отсутствует",
            severity = if (bridgeAvailable) Severity.INFO else Severity.ERROR,
            suggestion = if (!bridgeAvailable && bridgeError != null) "Ошибка загрузки класса: ${exceptionSummary(bridgeError)}" else ""
        ))

        return results
    }

    // ====== 5. 文件系统测试 ======
    private fun runFileSystemTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        val codexDir = File(context.filesDir, "codex")
        val workspaceDir = File(context.filesDir, "workspace")
        val configDir = File(context.filesDir, ".codex")

        // 目录创建
        codexDir.mkdirs(); workspaceDir.mkdirs(); configDir.mkdirs()
        val dirsOk = codexDir.exists() && workspaceDir.exists() && configDir.exists()
        results.add(TestResult(
            "文件目录访问", dirsOk,
            "codex: ${codexDir.exists()} | workspace: ${workspaceDir.exists()} | .codex: ${configDir.exists()}",
            severity = if (dirsOk) Severity.INFO else Severity.ERROR
        ))

        // 写入测试
        var writeError: Exception? = null
        val writeOk = try {
            val testFile = File(codexDir, ".write_test")
            testFile.writeText("ok")
            val readBack = testFile.readText()
            testFile.delete()
            readBack == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "文件写入测试失败", e)
            writeError = e
            false
        }
        results.add(TestResult(
            "文件写入权限", writeOk,
            when {
                writeOk -> "Запись работает"
                writeError != null -> "Ошибка записи: ${exceptionSummary(writeError)}"
                else -> "Ошибка записи: содержимое не совпадает после чтения"
            },
            severity = if (writeOk) Severity.INFO else Severity.ERROR,
            suggestion = if (!writeOk && writeError != null) "Ошибка записи в файл: ${exceptionSummary(writeError)}" else ""
        ))

        // 可用空间
        try {
            val dirPath = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val dir = java.io.File(dirPath)
            val free = dir.freeSpace / 1024 / 1024
            val enough = free > 200
            results.add(TestResult(
                "Свободное место", enough,
                "${free}MB доступно (желательно >200MB)",
                severity = if (enough) Severity.INFO else Severity.WARNING,
                suggestion = if (!enough) "Свободного места мало, очисти память" else ""
            ))
        } catch (e: Exception) {
            results.add(TestResult("Проверка свободного места", false, "Ошибка проверки: ${e.message}"))
        }

        return results
    }

    // ====== 6. Codex 二进制测试 ======
    private fun runCodexBinaryTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()
        val codexManager = CodexManager(context)
        val binary = codexManager.codexBinary

        // 二进制是否存在
        val exists = binary.exists()
        results.add(TestResult(
            "Codex 二进制文件", exists,
            if (exists) "${binary.length() / 1024 / 1024}MB" else "Не загружен",
            severity = if (exists) Severity.INFO else Severity.WARNING,
            suggestion = if (!exists) "Нажми запуск Codex для автоматической загрузки" else ""
        ))

        // 二进制完整性校验
        if (exists) {
            val valid = codexManager.verifyBinary()
            results.add(TestResult(
                "Codex 二进制完整性", valid,
                if (valid) "Проверка ELF-заголовка пройдена" else "Проверка не пройдена, файл повреждён",
                severity = if (valid) Severity.INFO else Severity.ERROR,
                suggestion = if (!valid) "Удалить и скачать заново: codexManager.cleanup()" else ""
            ))
        }

        // CPU 架构支持
        val arch = CodexManager.detectArch()
        results.add(TestResult(
            "CPU 架构支持", arch.supported,
            if (arch.supported) "${arch.abi} (поддерживается)" else "${arch.abi} (нет официальной сборки)",
            severity = if (arch.supported) Severity.INFO else Severity.ERROR,
            suggestion = if (!arch.supported) "Для Codex есть только сборки arm64-v8a и x86_64, текущее устройство не поддерживается" else ""
        ))

        // 已安装版本
        if (exists) {
            val installed = codexManager.getInstalledVersion()
            results.add(TestResult(
                "Codex 版本", true,
                installed ?: "Неизвестно (ручной импорт или старая установка)",
                severity = Severity.INFO,
                suggestion = if (installed == null) "Перекачай в настройках, чтобы записать номер версии" else ""
            ))
        }

        // 直接运行自检（无 Termux 可行性验证）
        if (exists) {
            val probe = codexManager.testDirectExecution()
            results.add(TestResult(
                "Codex 直接运行自检", probe.success,
                probe.message,
                severity = if (probe.success) Severity.INFO else Severity.WARNING,
                suggestion = if (!probe.success)
                    "Для Android ${probe.sdkInt} это нормально: прямой запуск ограничен, используй встроенную Linux-среду" else ""
            ))
        }

        // 下载源可达性（逐个检测每个镜像并分别列出状态）
        var anyMirrorReachable = false
        for (mirrorUrl in CodexManager.getAllDownloadUrls()) {
            val host = try { java.net.URL(mirrorUrl).host } catch (_: Exception) { mirrorUrl }
            var reachable = false
            var statusDetail: String
            try {
                val url = java.net.URL(mirrorUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "Codex-Android/1.0")
                val code = conn.responseCode
                reachable = code in 200..399
                statusDetail = if (reachable) "Доступно (HTTP $code)" else "Недоступно (HTTP $code)"
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "下载镜像可达性探测失败: $host", e)
                statusDetail = "Ошибка подключения (${e.javaClass.simpleName})"
            }
            if (reachable) anyMirrorReachable = true
            results.add(TestResult(
                "Зеркало загрузки: $host", reachable,
                statusDetail,
                severity = if (reachable) Severity.INFO else Severity.WARNING
            ))
        }
        results.add(TestResult(
            "Сводка по источникам Codex", anyMirrorReachable,
            if (anyMirrorReachable) "Хотя бы одно зеркало доступно" else "Все зеркала недоступны",
            severity = if (anyMirrorReachable) Severity.INFO else Severity.WARNING,
            suggestion = if (!anyMirrorReachable) "Проверь сеть или импортируй бинарник Codex вручную через настройки" else ""
        ))

        return results
    }

    // ====== 7. 开发环境测试 ======
    private suspend fun runDevEnvironmentTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()
        val devEnv = DevelopmentEnvironment(context)
        val linuxEnv = LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()

        // 自包含 Linux（proot）检测
        val hasSelfContainedLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY
        results.add(TestResult(
            "Встроенный Linux (proot)", hasSelfContainedLinux,
            when (linuxInfo.state) {
                LinuxEnvironment.EngineState.READY -> "Готово (proot + Ubuntu rootfs)"
                LinuxEnvironment.EngineState.NOT_INSTALLED -> "proot готов, но rootfs не установлен"
                LinuxEnvironment.EngineState.UNAVAILABLE -> "Движок proot недоступен: ${linuxInfo.errorMessage}"
                LinuxEnvironment.EngineState.ERROR -> "Ошибка: ${linuxInfo.errorMessage}"
                else -> "Неизвестное состояние"
            },
            severity = if (hasSelfContainedLinux) Severity.INFO else Severity.WARNING,
            suggestion = if (!hasSelfContainedLinux && linuxInfo.state == LinuxEnvironment.EngineState.NOT_INSTALLED)
                "Открой страницу среды разработки и нажми «Установить Linux-среду»" else ""
        ))

        return results
    }

    // ====== 8. Shell 执行测试 ======
    private suspend fun runShellTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 基本 shell 执行
        try {
            val result = AndroidShellExecutor.execute("echo 'hello'", permissionLevel = AndroidShellExecutor.PermissionLevel.NORMAL)
            results.add(TestResult(
                "Shell 执行 (NORMAL)", result.exitCode == 0,
                "exit=${result.exitCode}: ${result.stdout.take(100)}",
                severity = if (result.exitCode == 0) Severity.INFO else Severity.ERROR
            ))
        } catch (e: Exception) {
            results.add(TestResult("Shell 执行 (NORMAL)", false, "异常: ${e.message}", Severity.ERROR))
        }


        // Root 可用性
        val hasRoot = AndroidShellExecutor.isRootAvailable()
        results.add(TestResult(
            "Root 权限", hasRoot,
            if (hasRoot) "Доступен" else "Недоступен",
            severity = Severity.INFO
        ))

        return results
    }
}
