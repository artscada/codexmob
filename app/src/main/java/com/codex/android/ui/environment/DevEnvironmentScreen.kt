package com.codex.android.ui.environment

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.util.DevelopmentEnvironment
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.launch

/**
 * Среда разработки管理界面。
 *
 * 管理：
 * - 内置 Linux 环境Установить（proot + Ubuntu rootfs）
 * - 环境状态检测
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevEnvironmentScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devEnv = remember { DevelopmentEnvironment(context) }

    var envInfo by remember { mutableStateOf<DevelopmentEnvironment.EnvInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isInstalling by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var installProgress by remember { mutableStateOf("") }
    var currentAction by remember { mutableStateOf<String?>(null) }
    var linuxRuntimeMode by remember { mutableStateOf(CodexConnectionSettings.loadLinuxRuntimeMode(context)) }

    // 初始环境检测
    LaunchedEffect(Unit) {
        isLoading = true
        envInfo = devEnv.getEnvironmentInfo()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Среда разработки", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ===== 状态卡片 =====
            item {
                EnvironmentStatusCard(
                    envInfo = envInfo,
                    isLoading = isLoading
                )
            }

            item {
                LinuxRuntimeModeCard(
                    currentMode = linuxRuntimeMode,
                    onSelectMode = { mode ->
                        linuxRuntimeMode = mode
                        CodexConnectionSettings.saveLinuxRuntimeMode(context, mode)
                        scope.launch {
                            envInfo = devEnv.getEnvironmentInfo()
                        }
                    }
                )
            }

            // ===== 自包含 Linux Установить引导 =====
            if (envInfo?.state == DevelopmentEnvironment.EnvState.SELF_CONTAINED || envInfo?.state == DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    InstallLinuxCard(
                        isInstalling = isInstalling,
                        installLog = installLog,
                        installProgress = installProgress,
                        currentAction = currentAction,
                        onInstall = {
                            isInstalling = true
                            installLog = ""
                            currentAction = "Установка встроенного Linux"
                            scope.launch {
                                val linuxEnv = LinuxEnvironment(context)
                                val ok = linuxEnv.installRootfs(
                                    onProgress = { progress, total ->
                                        installProgress = if (total > 0) "${progress * 100 / total}%" else "${progress / 1024 / 1024}MB"
                                    },
                                    onStatus = { msg ->
                                        installLog = installLog + "\n" + msg
                                    }
                                )
                                if (ok) {
                                    installLog = if (installLog.isBlank()) {
                                        "✅ Встроенный Linux успешно установлен!"
                                    } else {
                                        installLog + "\n✅ Встроенный Linux успешно установлен!"
                                    }
                                    envInfo = devEnv.getEnvironmentInfo()
                                } else {
                                    installLog = if (installLog.isBlank()) {
                                        "❌ Установка встроенной Linux-среды не удалась."
                                    } else {
                                        installLog + "\n❌ Установка встроенной Linux-среды не удалась. Подробности смотри в строках выше."
                                    }
                                }
                                isInstalling = false
                                currentAction = null
                            }
                        }
                    )
                }
            }



            // ===== 工具列表 =====
            if (envInfo?.state == DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX) {
                item {
                    SectionTitle("Установленная среда")
                }
                item {
                    ToolStatusList(envInfo!!)
                }
            }
            item {
                SectionTitle("Инструменты разработки")
            }
            item {
                ActionCard(
                    icon = Icons.Default.Build,
                    title = "Проверить готовый образ farm-base",
                    subtitle = "Готовая Linux-среда с Node.js / Python / Git без apt",
                    buttonText = "Проверить образ",
                    enabled = !isInstalling,
                    onAction = {
                            isInstalling = true
                            installLog = ""
                            installProgress = ""
                            currentAction = "Проверка готового образа farm-base"
                            scope.launch {
                                val result = devEnv.installDevelopmentTools(
                                    onLog = { line ->
                                        installLog = if (installLog.isBlank()) line else installLog + "\n" + line
                                    }
                                )
                                envInfo = devEnv.getEnvironmentInfo()
                                if (result.success) {
                                    installLog = installLog + "\n✅ " + result.message
                                } else {
                                    installLog = installLog + "\n❌ " + result.message
                                }
                                isInstalling = false
                                currentAction = null
                            }
                        }
                    )
                }

                // 刷新环境
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                envInfo = devEnv.getEnvironmentInfo()
                                isLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить проверку среды")
                    }
                }

            // ===== Журнал установки =====
            if (installLog.isNotBlank()) {
                item {
                    SectionTitle("Журнал установки")
                }
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0A0A0F)
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            item {
                                Text(
                                    installLog,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4AF626),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // ===== 已Установить工具列表 =====
            if (envInfo != null && envInfo!!.state != DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    SectionTitle("Установленная среда")
                }

                item {
                    ToolStatusList(envInfo!!)
                }
            }

            // ===== 已Установить工具详情 =====
            if (envInfo != null && envInfo!!.state != DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    SectionTitle("Версии инструментов")
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            
                            ToolVersionRow("Node.js", envInfo?.nodeVersion ?: "-", envInfo?.hasNodeJs == true)
                            ToolVersionRow("Python", envInfo?.pythonVersion ?: "-", envInfo?.hasPython == true)
                            ToolVersionRow("Git", envInfo?.gitVersion ?: "-", envInfo?.hasGit == true)
                            ToolVersionRow("Codex CLI", "Установлен", envInfo?.hasCodex == true)
                        }
                    }
                }
            }

            // ===== 关于 =====
            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        "Среда разработки построена на встроенном proot + Ubuntu 24.04 LTS.\nДля farm-base используется готовый образ, а не установка пакетов через apt.",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LinuxRuntimeModeCard(
    currentMode: String,
    onSelectMode: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Linux runtime", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "proot остается fallback, chroot используется как целевой режим для полного Linux userspace.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelectMode("proot") },
                    modifier = Modifier.weight(1f),
                    enabled = currentMode != "proot"
                ) {
                    Text("proot")
                }
                Button(
                    onClick = { onSelectMode("chroot") },
                    modifier = Modifier.weight(1f),
                    enabled = currentMode != "chroot"
                ) {
                    Text("chroot")
                }
            }
        }
    }
}

// ===== 状态卡片 =====
@Composable
private fun EnvironmentStatusCard(
    envInfo: DevelopmentEnvironment.EnvInfo?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Проверка среды...", fontSize = 14.sp)
                }
            } else if (envInfo == null) {
                Text("Не удалось проверить среду", color = MaterialTheme.colorScheme.error)
            } else {
                // 状态图标和文字
                Row(verticalAlignment = Alignment.Top) {
                    val (icon, color, text) = when (envInfo.state) {
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX ->
                            Triple(Icons.Default.CheckCircle, Color(0xFF2ED573), "Встроенный Linux готов (Codex можно запускать)")
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED ->
                            Triple(Icons.Default.Warning, Color(0xFFFFA502), "Ограниченный режим (Codex не запускается)")
                        DevelopmentEnvironment.EnvState.ERROR ->
                            Triple(Icons.Default.Error, Color(0xFFFF4757), "Ошибка среды")
                    }
                    val description = when (envInfo.state) {
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED ->
                            "Linux-среда не найдена. Установи встроенную Linux-среду (proot + Ubuntu)."
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX ->
                            "Встроенная Linux-среда готова, Codex можно запускать через proot."
                        else -> ""
                    }
                    Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                        if (envInfo.errorMessage.isNotBlank()) {
                            Text(envInfo.errorMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}


// ===== 可Копировать命令行 =====

@Composable
private fun CopyableCommand(command: String, onCopy: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0A0A0F),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(command) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                command,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF4AF626),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Копировать",
                tint = Color(0xFF8888AA),
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopy(command) }
                    .padding(5.dp)
            )
        }
    }
}

// ===== 操作卡片 =====
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (enabled) Text(buttonText, fontSize = 13.sp)
                else CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// ===== 工具状态列表 =====
@Composable
private fun ToolStatusList(info: DevelopmentEnvironment.EnvInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ToolStatusRow("Codex", info.state != DevelopmentEnvironment.EnvState.ERROR)
            ToolStatusRow("Ubuntu", info.hasUbuntu)
            ToolStatusRow("Node.js", info.hasNodeJs)
            ToolStatusRow("Python", info.hasPython)
            ToolStatusRow("Git", info.hasGit)
            ToolStatusRow("Codex CLI", info.hasCodex)
        }
    }
}

@Composable
private fun ToolStatusRow(name: String, installed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (installed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            tint = if (installed) Color(0xFF2ED573) else Color(0xFF8888AA),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp)
    }
}

@Composable
private fun ToolVersionRow(name: String, version: String, installed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (installed) version else "Не установлен",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

// ===== 自包含 Linux Установить卡片 =====
@Composable
fun InstallLinuxCard(
    isInstalling: Boolean,
    installLog: String,
    installProgress: String,
    currentAction: String?,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Встроенный образ farm-base", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "Готовая Linux-среда: Ubuntu 24.04, Node.js, Python, Git и Codex CLI",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (currentAction != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    currentAction,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (installProgress.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    installProgress,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (installLog.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0A0F)
                ) {
                    Text(
                        installLog.trimStart(),
                        modifier = Modifier.padding(8.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4AF626),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onInstall,
                enabled = !isInstalling,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Установка...")
                } else {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Развернуть готовый образ")
                }
            }
        }
    }
}


