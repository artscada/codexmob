package com.codex.android.ui.workspace

import android.webkit.WebView
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.codex.android.bridge.CodexBridge
import com.codex.android.bridge.CodexLocalExecBridge
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.codex.CodexManager
import com.codex.android.data.chat.ChatSessionRepository
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import com.codex.android.ui.components.AgentStatusBar
import com.codex.android.ui.theme.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.serialization.encodeToString

/**
 * Codex workspace main screen.
 * Layout: TopActionBar -> WebView (fill) -> AgentStatusBar
 * This avoids WebView consuming touch events from overlay composables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    codexManager: CodexManager,
    runtimeState: RuntimeState,
    isWsConnected: Boolean,
    workspacePath: String,
    wsPort: Int,
    codexBridge: CodexBridge?,
    retainedWebView: WebView? = null,
    onWebViewReady: ((android.webkit.WebView) -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMCP: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenDevEnv: () -> Unit = {},
    onOpenDiagnostic: () -> Unit = {},
    onOpenFileBrowser: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onToggleRuntime: () -> Unit,
    onExportFile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val connectionConfig = remember { CodexConnectionSettings.load(context) }
    val apiMode = connectionConfig.connMode == "api"
    val apiConfigured = apiMode && CodexConnectionSettings.hasApiConfiguration(connectionConfig)

    val effectiveState = if (apiConfigured) RuntimeState.RUNNING else runtimeState
    val isRunning = effectiveState == RuntimeState.RUNNING
    val isStarting = effectiveState == RuntimeState.STARTING ||
                     runtimeState == RuntimeState.DOWNLOADING ||
                     runtimeState == RuntimeState.EXTRACTING
    val hasError = effectiveState == RuntimeState.ERROR
    val effectiveConnected = if (apiConfigured) {
        true
    } else if (!apiMode && runtimeState == RuntimeState.RUNNING) {
        true
    } else {
        isWsConnected
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Top action bar
        WorkspaceTopBar(
            isRunning = isRunning,
            isStarting = isStarting,
            hasError = hasError,
            runtimeState = effectiveState,
            onToggleRuntime = onToggleRuntime,
            onOpenGitHub = onOpenGitHub,
            onOpenSkills = onOpenSkills,
            onOpenMCP = onOpenMCP,
            onOpenDevEnv = onOpenDevEnv,
            onOpenDiagnostic = onOpenDiagnostic,
            onOpenSettings = onOpenSettings,
            onOpenFileBrowser = onOpenFileBrowser,
            onOpenAbout = onOpenAbout
        )

        // Main content area with WebView
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isRunning || effectiveConnected) {
                WebViewContainer(
                    codexBridge = codexBridge,
                    wsPort = wsPort,
                    apiMode = apiMode,
                    isRunning = isRunning,
                    retainedWebView = retainedWebView,
                    onWebViewReady = onWebViewReady,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                StartPlaceholder(
                    runtimeState = effectiveState,
                    apiMode = apiMode,
                    apiConfigured = apiConfigured,
                    onToggleRuntime = onToggleRuntime,
                    onOpenSettings = onOpenSettings
                )
            }
        }

        // Bottom status bar
        AgentStatusBar(
            state = effectiveState,
            isConnected = effectiveConnected
        )
    }
}

/**
 * Placeholder shown when Codex is not running.
 */
@Composable
private fun StartPlaceholder(
    runtimeState: RuntimeState,
    apiMode: Boolean,
    apiConfigured: Boolean,
    onToggleRuntime: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (apiMode && !apiConfigured) {
                Surface(
                    shape = CircleShape,
                    color = CodexPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Cloud, contentDescription = null, tint = CodexPrimary, modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Нужна настройка API",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Укажи адрес API, модель и ключ в настройках, чтобы чат заработал без локального Codex.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть настройки", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (runtimeState == RuntimeState.STOPPED) {
                // Welcome / start prompt
                Surface(
                    shape = CircleShape,
                    color = CodexPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Cx",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = CodexPrimary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Codex Android",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "ИИ-агент для программирования · нативно на Android",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onToggleRuntime,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Запустить Codex", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (runtimeState == RuntimeState.DOWNLOADING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Загрузка Codex CLI...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (runtimeState == RuntimeState.EXTRACTING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Распаковка бинарника Codex...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (runtimeState == RuntimeState.STARTING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Запуск Codex...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 显示运行时日志（下载/Запустить过程中）
            if (runtimeState == RuntimeState.DOWNLOADING || 
                runtimeState == RuntimeState.EXTRACTING || 
                runtimeState == RuntimeState.STARTING ||
                runtimeState == RuntimeState.ERROR) {
                val logs by CodexRuntimeService.logs.collectAsState()
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0A0A0F)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(logs.takeLast(30)) { logLine ->
                                Text(
                                    logLine,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4AF626),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
            
            if (runtimeState == RuntimeState.ERROR) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Не удалось запустить", fontSize = 16.sp, color = StatusError)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onToggleRuntime) {
                    Text("Повторить")
                }
            }
        }
    }
}

/**
 * WebView wrapper that handles proper initialization.
 */
@Composable
private fun WebViewContainer(
    codexBridge: CodexBridge?,
    wsPort: Int,
    apiMode: Boolean,
    isRunning: Boolean,
    retainedWebView: WebView? = null,
    onWebViewReady: ((android.webkit.WebView) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            val webView = retainedWebView ?: WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = false

                addJavascriptInterface(
                    CodexWebViewBridge(ctx, codexBridge).also { it.attachWebView(this) },
                    "CodexAndroidBridge"
                )

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(
                            "window.CODEX_API_MODE = ${if (apiMode) "true" else "false"};" +
                            "window.CODEX_WS_PORT = $wsPort;" +
                            "window.WS_PORT = $wsPort;" +
                            "window.onCodexWsPortUpdate && window.onCodexWsPortUpdate($wsPort);",
                            null
                        )
                        if (isRunning && !apiMode) {
                            view.evaluateJavascript(
                                "window.connectWebSocket && window.connectWebSocket();",
                                null
                            )
                        }
                        onWebViewReady?.invoke(view)
                    }
                }

                loadUrl("file:///android_asset/web/codex-ui.html")
            }

            (webView.parent as? ViewGroup)?.removeView(webView)
            onWebViewReady?.invoke(webView)
            webView
        },
        update = { view ->
            onWebViewReady?.invoke(view)
            view.evaluateJavascript(
                "window.CODEX_API_MODE = ${if (apiMode) "true" else "false"};" +
                "window.CODEX_WS_PORT = $wsPort;" +
                "window.WS_PORT = $wsPort;" +
                "window.onCodexWsPortUpdate && window.onCodexWsPortUpdate($wsPort);",
                null
            )
            if (isRunning && !apiMode) {
                view.evaluateJavascript(
                    "window.connectWebSocket && window.connectWebSocket();",
                    null
                )
            }
        },
        modifier = modifier
    )
}

/**
 * Top action bar with Replit-inspired design.
 * Uses proper touch targets (>=40dp) and background to ensure clickability.
 */
@Composable
private fun WorkspaceTopBar(
    isRunning: Boolean,
    isStarting: Boolean,
    hasError: Boolean,
    runtimeState: RuntimeState,
    onToggleRuntime: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMCP: () -> Unit,
    onOpenDevEnv: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFileBrowser: (() -> Unit)?,
    onOpenAbout: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo + Title
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CodexPrimary.copy(alpha = 0.15f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Cx",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CodexPrimary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Codex",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Status dot
            if (isRunning) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusOnline)
                )
            } else if (isStarting) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusWarning)
                )
            } else if (hasError) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusError)
                )
            }

            Spacer(Modifier.weight(1f))

            // Action buttons (always visible, but some only active when running)
            // GitHub button
            IconButton(
                onClick = onOpenGitHub
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = "GitHub",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // MCP button
            IconButton(
                onClick = onOpenMCP
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = "MCP",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Start/Stop button
            IconButton(
                onClick = onToggleRuntime
            ) {
                Icon(
                    if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isRunning) "Остановить" else "Запустить",
                    tint = if (isRunning) StatusOnline else CodexPrimary
                )
            }

            // More menu
            Box {
                IconButton(
                    onClick = { showMenu = true }
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Еще"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(CodexSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Файловый браузер", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenFileBrowser?.invoke() },
                        leadingIcon = { Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Серверы MCP", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenMCP() },
                        leadingIcon = { Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Skills-плагины", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenSkills() },
                        leadingIcon = { Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Среда разработки", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenDevEnv() },
                        leadingIcon = { Icon(Icons.Outlined.Build, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Диагностика", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenDiagnostic() },
                        leadingIcon = { Icon(Icons.Outlined.BugReport, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenSettings() },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("О приложении", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenAbout?.invoke() },
                        leadingIcon = { Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }
    }
}

/**
 * WebView JavaScript bridge interface.
 */
class CodexWebViewBridge(
    private val context: android.content.Context,
    private val codexBridge: CodexBridge?
) {
    companion object {
        private const val TAG = "CodexWebViewBridge"
    }

    private val apiBridge = com.codex.android.bridge.CodexApiBridge(context)
    private val localExecBridge = CodexLocalExecBridge(context)
    private val chatRepository = ChatSessionRepository.getInstance(context)
    private val chatJson = kotlinx.serialization.json.Json {
        encodeDefaults = true
    }
    private var webView: WebView? = null

    fun attachWebView(webView: WebView) {
        this.webView = webView
        apiBridge.onStreamMessage = { chunk ->
            emitJs("window.onCodexStreamMessage(${org.json.JSONObject.quote(chunk)});")
        }
        apiBridge.onError = { error ->
            emitJs("window.onCodexApiError(${org.json.JSONObject.quote(error)});")
        }
        apiBridge.onConnectionChange = { state ->
            emitJs("window.onCodexBridgeState(${org.json.JSONObject.quote(state.name)});")
        }
        localExecBridge.onStreamMessage = { chunk ->
            emitJs("window.onCodexStreamMessage(${org.json.JSONObject.quote(chunk)});")
        }
        localExecBridge.onError = { error ->
            emitJs("window.onCodexApiError(${org.json.JSONObject.quote(error)});")
        }
        localExecBridge.onConnectionChange = { state ->
            emitJs("window.onCodexBridgeState(${org.json.JSONObject.quote(state.name)});")
        }
    }

    private fun isApiModeInternal(): Boolean = CodexConnectionSettings.isApiMode(context)
    private fun isNativeLocalModeInternal(): Boolean = !isApiModeInternal()

    private fun emitJs(script: String) {
        webView?.post {
            try {
                webView?.evaluateJavascript(script, null)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "JS callback failed", e)
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun isCodexReady(): Boolean {
        return if (isApiModeInternal()) {
            apiBridge.isConfigured()
        } else if (isNativeLocalModeInternal()) {
            localExecBridge.isReady()
        } else {
            codexBridge?.connectionState?.value == CodexBridge.ConnectionState.CONNECTED
        }
    }

    @android.webkit.JavascriptInterface
    fun isApiMode(): Boolean = isApiModeInternal()

    @android.webkit.JavascriptInterface
    fun isNativeLocalMode(): Boolean = isNativeLocalModeInternal()

    @android.webkit.JavascriptInterface
    fun isConfigured(): Boolean = apiBridge.isConfigured()

    @android.webkit.JavascriptInterface
    fun getChatBootstrapState(): String {
        val mode = when {
            isApiModeInternal() -> "api"
            isNativeLocalModeInternal() -> "local"
            else -> "remote"
        }
        return chatJson.encodeToString(chatRepository.getBootstrapState(mode = mode))
    }

    @android.webkit.JavascriptInterface
    fun listChats(): String {
        return chatJson.encodeToString(chatRepository.listChats())
    }

    @android.webkit.JavascriptInterface
    fun listMessages(chatId: String): String {
        return chatJson.encodeToString(chatRepository.listMessages(chatId))
    }

    @android.webkit.JavascriptInterface
    fun createChat(title: String?): String {
        val mode = when {
            isApiModeInternal() -> "api"
            isNativeLocalModeInternal() -> "local"
            else -> "remote"
        }
        return chatJson.encodeToString(chatRepository.createChat(title = title, mode = mode))
    }

    @android.webkit.JavascriptInterface
    fun selectChat(chatId: String): Boolean = chatRepository.setSelectedChat(chatId)

    @android.webkit.JavascriptInterface
    fun deleteChat(chatId: String): String {
        return chatRepository.deleteChat(chatId).orEmpty()
    }

    @android.webkit.JavascriptInterface
    fun renameChat(chatId: String, title: String): Boolean = chatRepository.renameChat(chatId, title)

    @android.webkit.JavascriptInterface
    fun getSelectedChatId(): String {
        return chatRepository.getSelectedChatId()
            ?: chatRepository.ensureInitialChat(
                mode = if (isApiModeInternal()) "api" else if (isNativeLocalModeInternal()) "local" else "remote"
            ).id
    }

    @android.webkit.JavascriptInterface
    fun getConnectionState(): String {
        return if (isApiModeInternal()) {
            apiBridge.connectionState.value.name
        } else if (isNativeLocalModeInternal()) {
            if (localExecBridge.isReady()) CodexLocalExecBridge.ConnectionState.CONNECTED.name
            else CodexLocalExecBridge.ConnectionState.DISCONNECTED.name
        } else {
            codexBridge?.connectionState?.value?.name ?: "DISCONNECTED"
        }
    }

    @android.webkit.JavascriptInterface
    fun postMessage(jsonMessage: String) {
        android.util.Log.d(TAG, "JS -> Bridge: $jsonMessage")
        try {
            val msg = org.json.JSONObject(jsonMessage)
            val chatId = msg.optString("chatId").takeIf { it.isNotBlank() }
                ?: getSelectedChatId()
            if (isApiModeInternal()) {
                when (msg.optString("type")) {
                    "prompt" -> apiBridge.sendPrompt(chatId, msg.getString("data"))
                    "cancel" -> apiBridge.cancelStream()
                    "clear" -> apiBridge.clearHistory(chatId)
                }
            } else if (isNativeLocalModeInternal()) {
                when (msg.optString("type")) {
                    "prompt" -> localExecBridge.sendPrompt(chatId, msg.getString("data"))
                    "cancel" -> localExecBridge.cancelStream()
                    "clear" -> localExecBridge.clearHistory(chatId)
                }
            } else {
                codexBridge?.let { bridge ->
                    when (msg.optString("type")) {
                        "prompt" -> bridge.sendPrompt(msg.getString("data"))
                        "disconnect" -> bridge.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "JS message parse error", e)
        }
    }
}

/**
 * Forward runtime status to WebView JS.
 */
fun forwardStatusToWebView(
    webView: WebView?,
    state: RuntimeState,
    wsPort: Int,
    isRunning: Boolean
) {
    val wv = webView ?: return
    wv.post {
        try {
            wv.evaluateJavascript(
                "window.onCodexStatusUpdate && window.onCodexStatusUpdate('${state.name}', $wsPort, $isRunning);",
                null
            )
            if (state == RuntimeState.RUNNING) {
                wv.evaluateJavascript(
                    "window.connectWebSocket && window.connectWebSocket();",
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("forwardStatusToWebView", "JS call failed", e)
        }
    }
}


