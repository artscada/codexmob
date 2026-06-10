package com.codex.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.codex.CodexManager
import com.codex.android.runtime.linux.LinuxBackend
import com.codex.android.runtime.linux.LinuxRuntimeFactory
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.DevelopmentEnvironment
import java.io.File
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket

enum class RuntimeState {
    STOPPED,
    DOWNLOADING,
    EXTRACTING,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * 前台服务，管理 Codex CLI 运行时生命周期。
 *
 * 运行策略：
 * 1. 自包含 Linux (proot) → 在 proot Ubuntu 中运行
 * 2. 自包含模式 → 尝试直接运行（功能受限）
 */
class CodexRuntimeService : Service() {

    companion object {
        private const val TAG = "CodexRuntimeService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "codex_runtime"
        private const val ACTION_START = "com.codex.android.action.START_CODEX"
        private const val ACTION_STOP = "com.codex.android.action.STOP_CODEX"
        private const val ACTION_STATUS = "com.codex.android.action.CODEX_STATUS"

        const val DEFAULT_WS_PORT = 9877
        const val DEFAULT_HTTP_PORT = 19327

        private val _state = MutableStateFlow(RuntimeState.STOPPED)
        val state: StateFlow<RuntimeState> = _state.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        private var _wsPort = DEFAULT_WS_PORT
        val wsPort: Int get() = _wsPort

        private var _runningMode: String = "unknown"
        val runningMode: String get() = _runningMode

        fun start(context: Context) {
            val intent = Intent(context, CodexRuntimeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CodexRuntimeService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logsLock = Any()
    private lateinit var codexManager: CodexManager
    private lateinit var devEnv: DevelopmentEnvironment
    private lateinit var linuxBackend: LinuxBackend
    private var codexProcess: java.lang.Process? = null
    @Volatile
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        codexManager = CodexManager(this)
        devEnv = DevelopmentEnvironment(this)
        AndroidShellExecutor.init(this)
        createNotificationChannel()
        addLog("Служба Codex создана")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Codex запускается..."))
                serviceScope.launch { startCodex() }
            }
            ACTION_STOP -> {
                stopCodex()
                stopSelf()
            }
            ACTION_STATUS -> broadcastStatus()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCodex()
        serviceScope.cancel()
        _state.value = RuntimeState.STOPPED
        addLog("Служба Codex остановлена")
        super.onDestroy()
    }

    private suspend fun startCodex() {
        if (isRunning) {
            addLog("Codex уже запущен")
            return
        }

        try {
            linuxBackend = LinuxRuntimeFactory.create(this)
            _runningMode = linuxBackend.name
            addLog("Выбран Linux runtime: $_runningMode")
            if (!linuxBackend.isReady()) {
                val details = linuxBackend.readinessDetails()
                addLog(
                    if (details.isBlank()) {
                        "Linux runtime не готов, запускаю установку..."
                    } else {
                        "Linux runtime не готов: $details"
                    }
                )
            }

            // 下载/验证二进制
            _state.value = RuntimeState.STARTING
            addLog("Проверяю бинарник Codex...")

            if (!codexManager.isInstalled()) {
                _state.value = RuntimeState.DOWNLOADING
                addLog("Нужно скачать Codex CLI...")

                val success = withContext(Dispatchers.IO) {
                    codexManager.downloadWithProgress { progress, total ->
                        val pct = if (total > 0) (progress * 100 / total) else 0
                        addLog("Загрузка: $pct%")
                        updateNotification("Скачивание Codex... $pct%")
                    }
                }

                if (!success) {
                    _state.value = RuntimeState.ERROR
                    addLog("Не удалось скачать Codex")
                    updateNotification("Сбой загрузки Codex")
                    return
                }

                _state.value = RuntimeState.EXTRACTING
                addLog("Распаковываю Codex CLI...")
                updateNotification("Распаковка Codex CLI...")

                if (!codexManager.extractBinary()) {
                    _state.value = RuntimeState.ERROR
                    addLog("Не удалось распаковать Codex")
                    updateNotification("Сбой распаковки Codex")
                    return
                }

                addLog("Бинарник Codex готов: ${codexManager.codexBinary.length()} bytes")
            }

            if (!codexManager.verifyBinary()) {
                addLog("Проверка бинарника не пройдена, нужна повторная загрузка...")
                codexManager.cleanup()
                _state.value = RuntimeState.ERROR
                updateNotification("Бинарник Codex поврежден")
                return
            }

            if (!linuxBackend.isReady()) {
                _state.value = RuntimeState.EXTRACTING
                addLog("Устанавливаю Linux runtime (${linuxBackend.name})...")
                updateNotification("Установка Linux runtime...")
                if (!linuxBackend.install()) {
                    _state.value = RuntimeState.ERROR
                    addLog("Не удалось установить Linux runtime (${linuxBackend.name})")
                    updateNotification("Сбой установки Linux runtime")
                    return
                }
            }

            if (!linuxBackend.prepare()) {
                _state.value = RuntimeState.ERROR
                val details = linuxBackend.readinessDetails()
                addLog(
                    if (details.isBlank()) {
                        "Linux runtime (${linuxBackend.name}) не готов к запуску"
                    } else {
                        "Linux runtime (${linuxBackend.name}) не готов к запуску: $details"
                    }
                )
                updateNotification("Linux runtime не готов")
                return
            }

            CodexConnectionSettings.syncCodexConfig(this)
            addLog("Конфигурация Codex синхронизирована")

            codexManager.workspaceDir.mkdirs()
            _wsPort = findFreePort(DEFAULT_WS_PORT)
            addLog("WebSocket-порт: $_wsPort")

            // 启动 Codex
            _state.value = RuntimeState.STARTING
            addLog("Запускаю Codex exec-server...")
            updateNotification("Запуск Codex...")

            startCodexInLinuxBackend(linuxBackend)

            // 等待确认
            delay(3000)

            if (codexProcess?.isAlive == true) {
                _state.value = RuntimeState.RUNNING
                addLog("Codex exec-server запущен ($_runningMode)")
                updateNotification("Codex готов")
                broadcastStatus()
            } else {
                val exitCode = codexProcess?.exitValue() ?: -1
                _state.value = RuntimeState.ERROR
                addLog("Процесс Codex аварийно завершился (exit=$exitCode)")
                addLog("Сначала установи Linux-среду на вкладке окружения")
                updateNotification("Не удалось запустить Codex")
                isRunning = false
            }

        } catch (e: Exception) {
            _state.value = RuntimeState.ERROR
            addLog("Ошибка запуска Codex: ${e.message}")
            Log.e(TAG, "Не удалось запустить Codex", e)
            updateNotification("Ошибка Codex: ${e.message}")
        }
    }

    /**
     * 在 Ubuntu proot 中启动 Codex
     */
    /**
     * 直接启动（Android 原生，失败率高）
     */
    private suspend fun startDirect() {
        addLog("Пробую прямой запуск Codex...")
        // 先做可行性自检：能否直接执行该二进制
        val probe = codexManager.testDirectExecution()
        if (!probe.success) {
            addLog("Прямой запуск недоступен: ${probe.message}")
            addLog("Бинарник Codex CLI собран под Linux musl")
            if (probe.sdkInt >= 29) {
                addLog("Android ${probe.sdkInt} запрещает запуск исполняемых файлов из приватного каталога приложения (W^X)")
            }
            addLog("Установи встроенную Linux-среду на вкладке 'Среда' и повтори")
            _state.value = RuntimeState.ERROR
            updateNotification("Нужна Linux-среда")
            return
        }

        addLog("Проверка прямого запуска пройдена: ${probe.message}")
        try {
            val runtimeEnv = CodexConnectionSettings.buildRuntimeEnv(this)
            val process = ProcessBuilder(
                codexManager.codexBinary.absolutePath,
                "exec-server",
                "--listen", "ws://127.0.0.1:$_wsPort"
            ).apply {
                redirectErrorStream(true)
                environment()["CODEX_CONFIG_DIR"] = codexManager.getConfigDir().absolutePath
                environment()["HOME"] = codexManager.workspaceDir.absolutePath
                environment().putAll(runtimeEnv)
                directory(codexManager.workspaceDir)
            }.start()
            codexProcess = process
            isRunning = true
            addLog("Codex exec-server запущен напрямую")

            attachProcessLogs(process, "Codex")
        } catch (e: Exception) {
            addLog("Не удалось запустить exec-server напрямую: ${e.message}")
            _state.value = RuntimeState.ERROR
            updateNotification("Не удалось запустить Codex")
        }
    }

    private fun stopCodex() {
        addLog("Останавливаю Codex...")
        isRunning = false
        try {
            codexProcess?.destroyForcibly()
            codexProcess?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { Log.w(TAG, "停止 Codex 进程时出错", e) }
        codexProcess = null
        _state.value = RuntimeState.STOPPED
        addLog("Codex остановлен")
    }


    /**
     * 通用的 Codex 进程启动方法
     */
    private suspend fun startCodexProcess(launchCmd: String, modeName: String) {
        addLog("安装 Codex 到 $modeName 环境...")

        isRunning = true

        serviceScope.launch {
            try {
                codexProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        addLog("[Codex] $line")
                        if (line.contains("listening", ignoreCase = true) ||
                            line.contains("started", ignoreCase = true) ||
                            line.contains("ready", ignoreCase = true)) {
                            _state.value = RuntimeState.RUNNING
                            updateNotification("Codex 已就绪")
                            broadcastStatus()
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("Codex 输出流已关闭: ${e.message}")
            }
        }
    }
    /**
     * 在自包含 Linux（proot）环境中启动 Codex
     */
    private suspend fun startCodexInLinuxBackend(backend: LinuxBackend) {
        try {
            val guestFilesDir = "/data/data/$packageName/files"
            val guestWorkspaceDir = "$guestFilesDir/workspace"
            val guestHomeDir = "/root"
            val guestConfigDir = "$guestHomeDir/.codex"
            val guestCacheDir = "$guestHomeDir/.cache"
            val runtimeEnv = CodexConnectionSettings.buildRuntimeEnv(this)
            installCodexIntoRootfs(backend.rootfsDir())

            // 构建启动命令
            addLog("Запускаю Codex через ${backend.name}...")
            val launchCmd = buildChrootPathEnvBlock() +
                " && mkdir -p '$guestWorkspaceDir' '$guestConfigDir' '$guestCacheDir'" +
                " && export HOME='$guestHomeDir'" +
                " && export CODEX_HOME='$guestConfigDir'" +
                " && export CODEX_CONFIG_DIR='$guestConfigDir'" +
                " && export XDG_CONFIG_HOME='$guestHomeDir'" +
                " && export XDG_STATE_HOME='$guestHomeDir'" +
                " && export XDG_CACHE_HOME='$guestCacheDir'" +
                " && cd '$guestWorkspaceDir'" +
                " && codex exec-server --listen ws://127.0.0.1:$_wsPort"
            codexProcess = backend.createProcess(
                launchCmd,
                runtimeEnv + mapOf(
                    "HOME" to guestHomeDir,
                    "CODEX_HOME" to guestConfigDir,
                    "CODEX_CONFIG_DIR" to guestConfigDir
                )
            )
            isRunning = true

            codexProcess?.let { attachProcessLogs(it, "Codex-${backend.name}") }
        } catch (e: Exception) {
            addLog("Linux runtime (${backend.name}) 启动失败: ${e.message}")
            _state.value = RuntimeState.ERROR
            updateNotification("Codex 启动失败（${backend.name}）")
        }
    }

    private fun installCodexIntoRootfs(rootfs: File) {
        val rootfsBin = File(rootfs, "usr/local/bin")
        rootfsBin.mkdirs()
        addLog("安装 Codex 到 rootfs: ${rootfsBin.absolutePath}")
        codexManager.codexBinary.inputStream().use { input ->
            File(rootfsBin, "codex").outputStream().use { output ->
                input.copyTo(output)
            }
        }
        File(rootfsBin, "codex").setExecutable(true)
    }

    private fun buildChrootPathEnvBlock(): String {
        return buildString {
            append("export PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'")
            append(" && export HOME='/root'")
            append(" && export LANG='C.UTF-8'")
            append(" && export TERM='xterm-256color'")
        }
    }

    private fun attachProcessLogs(process: Process, prefix: String) {
        serviceScope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        handleRuntimeLogLine(prefix, line)
                    }
                }
            } catch (e: Exception) {
                addLog("$prefix stdout закрыт: ${e.message}")
            }
        }

        serviceScope.launch {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        handleRuntimeLogLine("$prefix stderr", line)
                    }
                }
            } catch (e: Exception) {
                addLog("$prefix stderr закрыт: ${e.message}")
            }
        }
    }

    private fun handleRuntimeLogLine(prefix: String, line: String) {
        addLog("[$prefix] $line")
        if (line.contains("listening", ignoreCase = true) ||
            line.contains("started", ignoreCase = true) ||
            line.contains("ready", ignoreCase = true)) {
            _state.value = RuntimeState.RUNNING
            updateNotification("Codex 已就绪")
            broadcastStatus()
        }
    }

    private fun broadcastStatus() {
        val intent = Intent("com.codex.android.CODEX_STATUS").apply {
            putExtra("state", _state.value.name)
            putExtra("wsPort", _wsPort)
            putExtra("isRunning", isRunning)
            putExtra("runningMode", _runningMode)
        }
        sendBroadcast(intent)
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < startPort + 100) {
            try { ServerSocket(port).use { it.close(); return port } } catch (e: Exception) { port++ }
        }
        return startPort
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Codex 运行时", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Codex AI 编码代理后台服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 停止按钮
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CodexRuntimeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codex AI")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(logsLock) {
            val newLogs = _logs.value + "[$timestamp] $message"
            _logs.value = if (newLogs.size > 500) newLogs.takeLast(200) else newLogs
        }
        Log.d(TAG, message)
    }
}
