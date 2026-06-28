package com.codex.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.codex.android.bridge.CodexBridge
import com.codex.android.codex.CodexManager
import com.codex.android.data.chat.ChatSessionRepository
import com.codex.android.service.CodexNotifications
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import com.codex.android.security.ShellConfirmationManager
import com.codex.android.ui.about.AboutScreen
import com.codex.android.ui.diagnostics.DiagnosticsScreen
import com.codex.android.ui.environment.DevEnvironmentScreen
import com.codex.android.ui.files.FileBrowserScreen
import com.codex.android.ui.github.GitHubImportScreen
import com.codex.android.ui.mcp.CodexMCPScreen
import com.codex.android.ui.settings.CodexSettingsScreen
import com.codex.android.ui.skills.CodexSkillsScreen
import com.codex.android.ui.theme.CodexTheme
import com.codex.android.ui.theme.BottomNavBackground
import com.codex.android.ui.theme.BottomNavInactive
import com.codex.android.ui.theme.CodexPrimary
import com.codex.android.ui.workspace.WorkspaceScreen
import com.codex.android.ui.workspace.forwardStatusToWebView
import com.codex.android.ui.setup.SetupWizardScreen
import com.codex.android.data.preferences.SetupPreferences
import com.codex.android.ui.github.GitHubRepoScreen
import com.codex.android.ui.github.GitHubPRScreen
import com.codex.android.ui.github.GitHubIssueScreen
import com.codex.android.util.AndroidShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CodexActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CodexActivity"
        const val ACTION_PROMPT = "com.codex.android.action.PROMPT"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_AUTO_SEND = "auto_send"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CREATE_NEW_CHAT = "create_new_chat"
        const val EXTRA_CREATE_IF_NONE = "create_if_none"
    }

    private data class PendingExternalPrompt(
        val id: Long = System.currentTimeMillis(),
        val text: String,
        val subject: String = "",
        val autoSend: Boolean = false,
        val chatId: String? = null,
        val createNewChat: Boolean = false
    )

    val codexManager by lazy { CodexManager(this) }
    val codexBridge by lazy { CodexBridge("ws://127.0.0.1:${CodexRuntimeService.DEFAULT_WS_PORT}") }
    private val chatRepository by lazy { ChatSessionRepository.getInstance(this) }

    // Compose reactive state
    private val _currentScreen = mutableStateOf<Screen>(Screen.Workspace)
    private val _runtimeStateFlow = mutableStateOf(RuntimeState.STOPPED)
    private val _wsPortFlow = mutableIntStateOf(CodexRuntimeService.DEFAULT_WS_PORT)
    private val _isRunningFlow = mutableStateOf(false)
    private val _wsConnectedFlow = mutableStateOf(false)

    // WebView reference
    private var _webView: WebView? = null
    private var pendingExternalPrompt: PendingExternalPrompt? = null
    
    // GitHub repo state (for navigation to repo detail)
    private var _currentGitHubRepo = mutableStateOf<Pair<String, String>?>(null)
    private var _currentGitHubRepoForPRs = mutableStateOf<Pair<String, String>?>(null)
    private var _currentGitHubRepoForIssues = mutableStateOf<Pair<String, String>?>(null)

    // Selected bottom nav item
    private val _selectedNavItem = mutableIntStateOf(0)

    sealed class Screen {
        data object Workspace : Screen()
        data object Settings : Screen()
        data object Skills : Screen()
        data object MCP : Screen()
        data object GitHubImport : Screen()
        data object FileBrowser : Screen()
        data object DevEnvironment : Screen()
        data object Diagnostic : Screen()
        data object About : Screen()
        data object SetupWizard : Screen()
        data class GitHubRepo(val repoFullName: String, val localPath: String) : Screen()
        data object GitHubPRList : Screen()
        data object GitHubIssueList : Screen()
        data object GuiAutomation : Screen()
    }

    data class BottomNavItem(
        val title: String,
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector,
        val screen: Screen
    )

    private val bottomNavItems = listOf(
        BottomNavItem("Терминал", Icons.Filled.Terminal, Icons.Outlined.Terminal, Screen.Workspace),
        BottomNavItem("Файлы", Icons.Filled.Folder, Icons.Outlined.Folder, Screen.FileBrowser),
        BottomNavItem("Среда", Icons.Filled.Build, Icons.Outlined.Build, Screen.DevEnvironment),
        BottomNavItem("Настройки", Icons.Filled.Settings, Icons.Outlined.Settings, Screen.Settings),
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Log.i(TAG, "Разрешение на уведомления выдано")
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra("state") ?: return
            val port = intent.getIntExtra("wsPort", CodexRuntimeService.DEFAULT_WS_PORT)
            val running = intent.getBooleanExtra("isRunning", false)

            val runtimeState = try {
                RuntimeState.valueOf(state)
            } catch (e: Exception) {
                RuntimeState.ERROR
            }
            Log.i(TAG, "Состояние Codex: $state (порт: $port, запущен: $running)")

            // Forward state to WebView
            forwardStatusToWebView(_webView, runtimeState, port, running)

            // Connect bridge when running
            if (runtimeState == RuntimeState.RUNNING) {
                if (codexBridge.connectionState.value != CodexBridge.ConnectionState.CONNECTED) {
                    codexBridge.connect()
                }
            }

            // Update Compose state
            _runtimeStateFlow.value = runtimeState
            _wsPortFlow.intValue = port
            _isRunningFlow.value = running
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize shell executor
        AndroidShellExecutor.init(this)

        codexBridge.onConnectionChange = { state ->
            _wsConnectedFlow.value = state == CodexBridge.ConnectionState.CONNECTED
        }
        codexBridge.onError = {
            _wsConnectedFlow.value = false
        }

        // Create notification channel
        CodexNotifications.createChannels(this)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Register runtime status broadcast receiver
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        registerReceiver(statusReceiver, IntentFilter("com.codex.android.CODEX_STATUS"), receiverFlags)

        // Observe runtime state from service
        lifecycleScope.launch {
            CodexRuntimeService.state.collect { state ->
                _runtimeStateFlow.value = state
            }
        }

        handleExternalIntent(intent)

        setContent {
            CodexTheme {
                val setupPrefs = remember { SetupPreferences.getInstance(this@CodexActivity) }
                var isSetupComplete by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    isSetupComplete = setupPrefs.isSetupCompleted()
                }

                isSetupComplete?.let { completed ->
                    if (!completed) {
                        SetupWizardScreen(
                            onComplete = {
                                lifecycleScope.launch {
                                    setupPrefs.markSetupCompleted()
                                    isSetupComplete = true
                                }
                            },
                            onSkip = {
                                lifecycleScope.launch {
                                    setupPrefs.markSetupCompleted()
                                    isSetupComplete = true
                                }
                            }
                        )
                        return@CodexTheme
                    }
                }

                if (isSetupComplete == null) return@CodexTheme

                val screen by _currentScreen
                val runtimeState by _runtimeStateFlow
                val wsPort by _wsPortFlow
                val isRunning by _isRunningFlow
                val wsConnected by _wsConnectedFlow
                val selectedNav by _selectedNavItem

                var showMoreMenu by remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // Check if we should show bottom nav (not in full-screen subscreens)
                        val showBottomNav = screen in listOf(
                            Screen.Workspace,
                            Screen.FileBrowser,
                            Screen.DevEnvironment,
                            Screen.Settings
                        )

                        AnimatedVisibility(
                            visible = showBottomNav,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            NavigationBar(
                                containerColor = BottomNavBackground,
                                tonalElevation = 0.dp,
                            ) {
                                bottomNavItems.forEachIndexed { index, item ->
                                    NavigationBarItem(
                                        selected = selectedNav == index,
                                        onClick = {
                                            _selectedNavItem.intValue = index
                                            navigateTo(item.screen)
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selectedNav == index) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.title,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                item.title,
                                                fontSize = 11.sp,
                                                fontWeight = if (selectedNav == index) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = CodexPrimary,
                                            selectedTextColor = CodexPrimary,
                                            unselectedIconColor = BottomNavInactive,
                                            unselectedTextColor = BottomNavInactive,
                                            indicatorColor = CodexPrimary.copy(alpha = 0.12f)
                                        )
                                    )
                                }

                                // "More" button for overflow items
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { showMoreMenu = true },
                                    icon = {
                                        Icon(
                                            Icons.Outlined.MoreHoriz,
                                            contentDescription = "Еще",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = {
                                        Text("Еще", fontSize = 11.sp)
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        unselectedIconColor = BottomNavInactive,
                                        unselectedTextColor = BottomNavInactive,
                                    )
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                fadeIn() + slideInHorizontally { it / 4 } togetherWith
                                fadeOut() + slideOutHorizontally { -it / 4 }
                            },
                            label = "screenTransition"
                        ) { currentScreen ->
                            when (currentScreen) {
                                Screen.Workspace -> WorkspaceScreen(
                                    codexManager = codexManager,
                                    runtimeState = runtimeState,
                                    isWsConnected = wsConnected,
                                    workspacePath = codexManager.workspaceDir.absolutePath,
                                    wsPort = wsPort,
                                    codexBridge = codexBridge,
                                    retainedWebView = _webView,
                                    onWebViewReady = { webView ->
                                        _webView = webView
                                        dispatchPendingPromptIfReady()
                                    },
                                    onOpenSettings = { navigateTo(Screen.Settings) },
                                    onOpenSkills = { navigateTo(Screen.Skills) },
                                    onOpenMCP = { navigateTo(Screen.MCP) },
                                    onOpenGitHub = { navigateTo(Screen.GitHubImport) },
                                    onOpenDevEnv = { navigateTo(Screen.DevEnvironment) },
                                    onOpenDiagnostic = { navigateTo(Screen.Diagnostic) },
                                    onOpenFileBrowser = { navigateTo(Screen.FileBrowser) },
                                    onOpenAbout = { navigateTo(Screen.About) },
                                    onToggleRuntime = {
                                        if (isRunning) {
                                            CodexRuntimeService.stop(this@CodexActivity)
                                        } else {
                                            CodexRuntimeService.start(this@CodexActivity)
                                        }
                                    },
                                    onExportFile = { path ->
                                        exportWorkspaceToDownloads(path)
                                    }
                                )
                                Screen.FileBrowser -> FileBrowserScreen(
                                    workspacePath = codexManager.workspaceDir.absolutePath,
                                    onBack = { navigateTo(Screen.Workspace) }
                                )
                                Screen.DevEnvironment -> DevEnvironmentScreen(
                                    onBack = { navigateTo(Screen.Workspace) }
                                )
                                Screen.Settings -> CodexSettingsScreen(
                                    onBack = { navigateTo(Screen.Workspace) },
                                    onOpenSkills = { navigateTo(Screen.Skills) },
                                    onOpenMCP = { navigateTo(Screen.MCP) },
                                    onOpenGitHub = { navigateTo(Screen.GitHubImport) },
                                    onOpenDiagnostic = { navigateTo(Screen.Diagnostic) },
                                    onOpenAbout = { navigateTo(Screen.About) }
                                )
                                Screen.Skills -> CodexSkillsScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.MCP -> CodexMCPScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.GitHubImport -> GitHubImportScreen(
                                    workspaceDir = codexManager.workspaceDir.absolutePath,
                                    onBack = { navigateTo(Screen.Workspace) },
                                    onManageRepo = { fullName, localPath ->
                                        _currentGitHubRepo.value = Pair(fullName, localPath)
                                        navigateTo(Screen.GitHubRepo(fullName, localPath))
                                    }
                                )
                                Screen.Diagnostic -> DiagnosticsScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.About -> AboutScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.GuiAutomation -> com.codex.android.ui.gui.GuiAutomationScreen(
                                    onBack = { navigateTo(Screen.Workspace) }
                                )
                                Screen.SetupWizard -> SetupWizardScreen(
                                    onComplete = {
                                        lifecycleScope.launch {
                                            SetupPreferences.getInstance(this@CodexActivity).markSetupCompleted()
                                            navigateTo(Screen.Workspace)
                                        }
                                    },
                                    onSkip = {
                                        lifecycleScope.launch {
                                            SetupPreferences.getInstance(this@CodexActivity).markSetupCompleted()
                                            navigateTo(Screen.Workspace)
                                        }
                                    }
                                )
                                is Screen.GitHubRepo -> {
                                    val screen = currentScreen as Screen.GitHubRepo
                                    GitHubRepoScreen(
                                        repoFullName = screen.repoFullName,
                                        repoLocalPath = screen.localPath,
                                        onBack = { navigateTo(Screen.GitHubImport) },
                                        onOpenPRs = {
                                            _currentGitHubRepoForPRs.value = Pair(screen.repoFullName, screen.localPath)
                                            navigateTo(Screen.GitHubPRList)
                                        },
                                        onOpenIssues = {
                                            _currentGitHubRepoForIssues.value = Pair(screen.repoFullName, screen.localPath)
                                            navigateTo(Screen.GitHubIssueList)
                                        }
                                    )
                                }
                                Screen.GitHubPRList -> {
                                    val repoInfo = _currentGitHubRepoForPRs.value
                                    if (repoInfo != null) {
                                        GitHubPRScreen(
                                            repoFullName = repoInfo.first,
                                            onBack = {
                                                _currentGitHubRepo.value?.let { (name, path) ->
                                                    navigateTo(Screen.GitHubRepo(name, path))
                                                } ?: navigateTo(Screen.GitHubImport)
                                            }
                                        )
                                    } else {
                                        GitHubPRScreen(
                                            repoFullName = "",
                                            onBack = { navigateTo(Screen.GitHubImport) }
                                        )
                                    }
                                }
                                Screen.GitHubIssueList -> {
                                    val repoInfo = _currentGitHubRepoForIssues.value
                                    if (repoInfo != null) {
                                        GitHubIssueScreen(
                                            repoFullName = repoInfo.first,
                                            onBack = {
                                                _currentGitHubRepo.value?.let { (name, path) ->
                                                    navigateTo(Screen.GitHubRepo(name, path))
                                                } ?: navigateTo(Screen.GitHubImport)
                                            }
                                        )
                                    } else {
                                        GitHubIssueScreen(
                                            repoFullName = "",
                                            onBack = { navigateTo(Screen.GitHubImport) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // More menu dialog
                if (showMoreMenu) {
                    AlertDialog(
                        onDismissRequest = { showMoreMenu = false },
                        title = {
                            Text("Дополнительные функции", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                MoreMenuItem(
                                    icon = Icons.Default.Extension,
                                    title = "Skills",
                                    desc = "Управление плагинами Codex",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.Skills)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Memory,
                                    title = "Серверы MCP",
                                    desc = "Интеграция системных инструментов Android",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.MCP)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Code,
                                    title = "Импорт из GitHub",
                                    desc = "Импорт репозитория из GitHub",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.GitHubImport)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Folder,
                                    title = "Управление репозиторием",
                                    desc = "Git-операции, PR и Issue",
                                    onClick = {
                                        showMoreMenu = false
                                        _currentGitHubRepo.value?.let { (name, path) ->
                                            navigateTo(Screen.GitHubRepo(name, path))
                                        } ?: navigateTo(Screen.GitHubImport)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.BugReport,
                                    title = "Диагностика",
                                    desc = "Запуск диагностики устройства",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.Diagnostic)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Info,
                                    title = "О приложении",
                                    desc = "Версия и системная информация",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.About)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Schedule,
                                    title = "GUI Автоматизация",
                                    desc = "Настройка расписания и рандомизации задач",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.GuiAutomation)
                                    }
                                )
                                MoreMenuItem(
                                    icon = Icons.Default.Build,
                                    title = "Мастер настройки",
                                    desc = "Повторно запустить начальную настройку",
                                    onClick = {
                                        showMoreMenu = false
                                        navigateTo(Screen.SetupWizard)
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showMoreMenu = false }) {
                                Text("Закрыть")
                            }
                        }
                    )
                }

                // 危险 Shell 命令确认弹窗
                val pendingConfirmation by ShellConfirmationManager.pending.collectAsState()
                pendingConfirmation?.let { confirmation ->
                    AlertDialog(
                        onDismissRequest = { ShellConfirmationManager.deny() },
                        icon = {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFE65100)
                            )
                        },
                        title = {
                            Text("Подтвердить выполнение опасной команды", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "ИИ запросил выполнение потенциально опасной команды: ${confirmation.reason}。",
                                    fontSize = 14.sp
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        confirmation.command,
                                        modifier = Modifier.padding(10.dp),
                                        fontSize = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Text(
                                    "Продолжай только если уверен, что это безопасно.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { ShellConfirmationManager.approve() }) {
                                Text("Все равно выполнить", color = Color(0xFFC62828))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { ShellConfirmationManager.deny() }) {
                                Text("Отмена")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
        // Update selected nav index if it's a bottom nav item
        val navIndex = bottomNavItems.indexOfFirst { it.screen == screen }
        if (navIndex >= 0) {
            _selectedNavItem.intValue = navIndex
        }
    }

    fun setWebViewRef(webView: WebView?) {
        _webView = webView
    }

    private fun exportWorkspaceToDownloads(workspacePath: String) {
        try {
            val sourceDir = java.io.File(workspacePath)
            if (!sourceDir.exists()) {
                android.widget.Toast.makeText(this, "Рабочее пространство пусто, экспортировать нечего", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val exportDir = java.io.File(downloadsDir, "CodexWorkspace")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    sourceDir.copyRecursively(exportDir, overwrite = true)
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@CodexActivity,
                            "Экспортировано в: ${exportDir.absolutePath}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        Log.i(TAG, "Рабочее пространство экспортировано в: ${exportDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(this@CodexActivity, "Ошибка экспорта: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось экспортировать рабочее пространство", e)
            android.widget.Toast.makeText(this, "Ошибка экспорта: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        intent ?: return

        when {
            intent.action == ACTION_PROMPT -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
                if (prompt.isBlank()) return

                val subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty()
                val autoSend = intent.getBooleanExtra(EXTRA_AUTO_SEND, true)
                val requestedChatId = intent.getStringExtra(EXTRA_CHAT_ID)?.trim().orEmpty().ifBlank { null }
                val createNewChat = intent.getBooleanExtra(EXTRA_CREATE_NEW_CHAT, false)
                val createIfNone = intent.getBooleanExtra(EXTRA_CREATE_IF_NONE, true)
                val targetChatId = resolveExternalChatId(
                    requestedChatId = requestedChatId,
                    createNewChat = createNewChat,
                    createIfNone = createIfNone,
                    subject = subject,
                    prompt = prompt
                ) ?: return
                Log.i(TAG, "Получен внешний промпт: ${prompt.take(120)}")

                enqueueExternalPrompt(
                    PendingExternalPrompt(
                        text = prompt,
                        subject = subject,
                        autoSend = autoSend,
                        chatId = targetChatId,
                        createNewChat = createNewChat
                    )
                )

                android.widget.Toast.makeText(this, "Внешний запрос передан в Codex", android.widget.Toast.LENGTH_SHORT).show()
            }

            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (sharedText.isBlank()) return

                val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                Log.i(TAG, "Получен общий текст: $sharedSubject - ${sharedText.take(100)}")

                enqueueExternalPrompt(
                    PendingExternalPrompt(
                        text = sharedText,
                        subject = sharedSubject,
                        autoSend = false
                    )
                )

                android.widget.Toast.makeText(this, "Текст передан в Codex", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueExternalPrompt(prompt: PendingExternalPrompt) {
        pendingExternalPrompt = prompt
        navigateTo(Screen.Workspace)
        dispatchPendingPromptIfReady()
    }

    private fun resolveExternalChatId(
        requestedChatId: String?,
        createNewChat: Boolean,
        createIfNone: Boolean,
        subject: String,
        prompt: String
    ): String? {
        if (createNewChat) {
            val titleSeed = subject.ifBlank { prompt }
            val created = chatRepository.createChat(
                title = titleSeed.take(48).ifBlank { null },
                mode = currentChatMode()
            )
            return created.id
        }

        if (!requestedChatId.isNullOrBlank()) {
            val exists = chatRepository.listChats().any { it.id == requestedChatId }
            if (exists) {
                chatRepository.setSelectedChat(requestedChatId)
                return requestedChatId
            }

            if (!createIfNone) {
                android.widget.Toast.makeText(this, "Указанный чат не найден", android.widget.Toast.LENGTH_SHORT).show()
                return null
            }
        }

        val hasChats = chatRepository.listChats().isNotEmpty()
        if (!hasChats && !createIfNone) {
            android.widget.Toast.makeText(this, "Нет доступного чата для внешнего запроса", android.widget.Toast.LENGTH_SHORT).show()
            return null
        }

        return chatRepository.ensureInitialChat(mode = currentChatMode()).id
    }

    private fun currentChatMode(): String {
        return if (com.codex.android.codex.CodexConnectionSettings.isApiMode(this)) "api" else "local"
    }

    private fun dispatchPendingPromptIfReady() {
        pendingExternalPrompt?.let { dispatchPromptToWebView(it) }
    }

    private fun dispatchPromptToWebView(prompt: PendingExternalPrompt) {
        val webView = _webView ?: return
        val script = buildString {
            append("(function(){")
            append("window.__pendingExternalPrompt = {")
            append("id:")
            append(prompt.id)
            append(",")
            append("text:")
            append(org.json.JSONObject.quote(prompt.text))
            append(",subject:")
            append(org.json.JSONObject.quote(prompt.subject))
            append(",autoSend:")
            append(if (prompt.autoSend) "true" else "false")
            append(",chatId:")
            append(if (prompt.chatId != null) org.json.JSONObject.quote(prompt.chatId) else "null")
            append(",createNewChat:")
            append(if (prompt.createNewChat) "true" else "false")
            append("};")
            append("if (window.onSharedText) {")
            append("window.onSharedText(window.__pendingExternalPrompt.text, window.__pendingExternalPrompt.subject, window.__pendingExternalPrompt.autoSend, window.__pendingExternalPrompt.id, window.__pendingExternalPrompt.chatId, window.__pendingExternalPrompt.createNewChat);")
            append("return true;")
            append("}")
            append("return false;")
            append("})();")
        }

        webView.post {
            try {
                webView.evaluateJavascript(script) { result ->
                    if (result == "true") {
                        pendingExternalPrompt = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось передать внешний промпт в WebView", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        codexBridge.destroy()
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}


