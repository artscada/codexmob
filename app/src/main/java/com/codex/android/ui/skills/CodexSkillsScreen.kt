package com.codex.android.ui.skills

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.SkillsRepository
import kotlinx.coroutines.launch

/**
 * Codex Skills 管理界面。
 * 管理 Codex 插件（Skills）的浏览、安装、卸载。
 * 通过 WebSocket 与 Codex exec-server 通信。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexSkillsScreen(
    onBack: () -> Unit = {},
    onInstall: ((String) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
    onAddMarket: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SkillsRepository(context) }
    var showAddMarketDialog by remember { mutableStateOf(false) }
    var marketUrl by remember { mutableStateOf("") }
    var installedSkills by remember { mutableStateOf(repo.getInstalledSkills()) }
    var availableSkills by remember { mutableStateOf(repo.getInstalledSkills()) }
    LaunchedEffect(Unit) {
        availableSkills = repo.getAvailableSkills()
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Навыки Codex", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMarketDialog = true }) {
                        Icon(Icons.Default.Add, "Добавить каталог")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab 切换
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Установлено (${installedSkills.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Доступно (${availableSkills.size})") }
                )
            }

            when (selectedTab) {
                0 -> InstalledSkillsTab(
                    skills = installedSkills.map { it.toSkillItem() },
                    onRemove = { name ->
                        scope.launch {
                            repo.removeSkill(name)
                            installedSkills = repo.getInstalledSkills()
                            availableSkills = repo.getAvailableSkills()
                            onRemove?.invoke(name)
                        }
                    }
                )
                1 -> AvailableSkillsTab(
                    skills = availableSkills.map { it.toSkillItem() },
                    onInstall = { skill ->
                        scope.launch {
                            repo.installSkill(skill.name)
                            installedSkills = repo.getInstalledSkills()
                            availableSkills = repo.getAvailableSkills()
                            onInstall?.invoke(skill.name)
                        }
                    }
                )
            }
        }
    }

    // 添加市场对话框
    if (showAddMarketDialog) {
        AlertDialog(
            onDismissRequest = { showAddMarketDialog = false },
            title = { Text("Добавить каталог навыков") },
            text = {
                Column {
                    Text("Укажи URL Git-репозитория или локальный путь:", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = marketUrl,
                        onValueChange = { marketUrl = it },
                        placeholder = { Text("https://github.com/user/repo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (marketUrl.isNotBlank()) {
                        scope.launch {
                            repo.addMarket(marketUrl)
                            availableSkills = repo.getAvailableSkills()
                            onAddMarket?.invoke()
                        }
                        showAddMarketDialog = false
                        marketUrl = ""
                    }
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMarketDialog = false }) { Text("Отмена") }
            }
        )
    }
}

// ===== 已安装 Skills 标签页 =====

@Composable
private fun InstalledSkillsTab(
    skills: List<SkillItem>,
    onRemove: (String) -> Unit
) {
    if (skills.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text("Пока не установлен ни один навык", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Открой вкладку «Доступно», чтобы посмотреть и установить навыки",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(skills) { skill ->
                SkillCard(
                    skill = skill,
                    action = {
                        TextButton(
                            onClick = { onRemove(skill.name) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Удалить") }
                    }
                )
            }
        }
    }
}

// ===== 可用 Skills 标签页 =====

@Composable
private fun AvailableSkillsTab(
    skills: List<SkillItem>,
    onInstall: (SkillItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                "Просматривай и устанавливай навыки Codex из подключённых каталогов",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        items(skills) { skill ->
            SkillCard(
                skill = skill,
                action = {
                    if (skill.installed) {
                        Text("Установлено", color = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(
                            onClick = { onInstall(skill) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) { Text("Установить", fontSize = 13.sp) }
                    }
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Что такое навыки", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Навыки — это расширения Codex, которые добавляют новые функции и инструменты.\n" +
                        "Через кнопку + справа вверху можно подключить свой каталог навыков.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

// ===== Skill 卡片 =====

@Composable
private fun SkillCard(
    skill: SkillItem,
    action: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.installed)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Skill 图标
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        skill.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    skill.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (skill.version.isNotBlank()) {
                    Text(
                        "v${skill.version}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

// ===== 数据模型 =====

data class SkillItem(
    val name: String,
    val description: String,
    val version: String = "",
    val installed: Boolean = false,
    val icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Extension
)

/** 将 SkillsRepository.SkillInfo 转换为 SkillItem */
private fun SkillsRepository.SkillInfo.toSkillItem(): SkillItem = SkillItem(
    name = name,
    description = description,
    version = version,
    installed = installed
)
