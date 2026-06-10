package com.codex.android.ui.settings

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.codex.android.bridge.CodexApiBridge
import com.codex.android.codex.CodexConnectionSettings
import com.codex.android.codex.CodexManager
import com.codex.android.data.preferences.ExternalHttpApiPreferences
import com.codex.android.integrations.http.CodexExternalHttpServer
import com.codex.android.service.CodexRuntimeService
import com.codex.android.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Codex settings screen.
 * Extended with navigation to Skills, MCP, GitHub, Diagnostics, and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexSettingsScreen(
    onBack: () -> Unit = {},
    onOpenSkills: (() -> Unit)? = null,
    onOpenMCP: (() -> Unit)? = null,
    onOpenGitHub: (() -> Unit)? = null,
    onOpenDiagnostic: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val codexManager = remember { CodexManager(context) }
    val apiBridge = remember { CodexApiBridge(context) }
    val httpApiPreferences = remember { ExternalHttpApiPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Connection settings
    var connMode by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("conn_mode", "local")
    ) }
    var apiKey by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
    ) }
    var apiUrl by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    ) }
    var apiModel by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_model", "gpt-4o") ?: "gpt-4o"
    ) }
    var showApiKey by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var applyStatus by remember { mutableStateOf<String?>(null) }
    var httpApiEnabled by remember { mutableStateOf(httpApiPreferences.isEnabled()) }
    var httpApiPort by remember { mutableStateOf(httpApiPreferences.getPort().toString()) }
    var httpApiToken by remember { mutableStateOf(httpApiPreferences.getBearerToken()) }
    var httpApiStatus by remember { mutableStateOf<String?>(null) }
    var callbackExampleUrl by remember { mutableStateOf("http://192.168.28.230:18080/callback/") }

    // Security level
    var securityLevel by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("security_level", "standard") ?: "standard"
    ) }

    // Binary status
    val isInstalled = codexManager.isInstalled()
    val binarySize = if (isInstalled) {
        "%.1f MB".format(codexManager.codexBinary.length() / (1024.0 * 1024.0))
    } else "Не установлен"
    val httpApiPortValue = httpApiPort.toIntOrNull() ?: httpApiPreferences.getPort()
    val httpApiBaseUrl = "http://IP_ТЕЛЕФОНА:$httpApiPortValue"
    val healthExample = "curl -H \"Authorization: Bearer $httpApiToken\" \"$httpApiBaseUrl/api/health\""
    val syncTaskExample = """
        |${'$'}h = @{ Authorization = 'Bearer $httpApiToken' }
        |${'$'}payload = @{
        |  task_id = 'task-sync-001'
        |  prompt = 'Ответь по-русски: телефон готов к работе?'
        |  create_new_chat = ${'$'}true
        |  response_mode = 'sync'
        |} | ConvertTo-Json -Compress
        |Invoke-RestMethod -Headers ${'$'}h -Method POST -Uri '$httpApiBaseUrl/api/run-task' -ContentType 'application/json; charset=utf-8' -Body ${'$'}payload
    """.trimMargin()
    val streamTaskExample = """
        |curl -N -H "Authorization: Bearer $httpApiToken" -H "Content-Type: application/json; charset=utf-8" \
        |  -d '{"task_id":"task-stream-001","prompt":"Ответь по-русски коротко: поток работает.","stream":true}' \
        |  "$httpApiBaseUrl/api/run-task"
    """.trimMargin()
    val asyncTaskExample = """
        |${'$'}h = @{ Authorization = 'Bearer $httpApiToken' }
        |${'$'}payload = @{
        |  task_id = 'task-callback-001'
        |  prompt = 'Ответь по-русски коротко: callback работает.'
        |  response_mode = 'async_callback'
        |  callback_url = '$callbackExampleUrl'
        |} | ConvertTo-Json -Compress
        |Invoke-RestMethod -Headers ${'$'}h -Method POST -Uri '$httpApiBaseUrl/api/run-task' -ContentType 'application/json; charset=utf-8' -Body ${'$'}payload
    """.trimMargin()
    val runsListExample = "curl -H \"Authorization: Bearer $httpApiToken\" \"$httpApiBaseUrl/api/runs?limit=20\""
    val runStatusExample = "curl -H \"Authorization: Bearer $httpApiToken\" \"$httpApiBaseUrl/api/runs/task-sync-001\""
    val runsRunningExample = "curl -H \"Authorization: Bearer $httpApiToken\" \"$httpApiBaseUrl/api/runs?status=running&limit=20\""
    val cancelRunExample = "curl -X POST -H \"Authorization: Bearer $httpApiToken\" \"$httpApiBaseUrl/api/runs/task-cancel-001/cancel\""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ===== Connection Mode =====
            item {
                SectionHeader("Способ подключения")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ConnModeOption(
                            title = "Локальный Codex CLI (рекомендуется)",
                            subtitle = "Использовать встроенный локальный движок Codex на устройстве",
                            icon = Icons.Default.Terminal,
                            selected = connMode == "local",
                            onClick = { connMode = "local"; saveConnMode(context, "local") }
                        )
                        Spacer(Modifier.height(4.dp))
                        ConnModeOption(
                            title = "OpenAI-совместимый API",
                            subtitle = "Подключение к облаку через API key",
                            icon = Icons.Default.Cloud,
                            selected = connMode == "api",
                            onClick = { connMode = "api"; saveConnMode(context, "api") }
                        )
                        Spacer(Modifier.height(4.dp))
                        ConnModeOption(
                            title = "Пользовательский WebSocket",
                            subtitle = "Ручное подключение к серверу выполнения Codex",
                            icon = Icons.Default.Link,
                            selected = connMode == "ws",
                            onClick = { connMode = "ws"; saveConnMode(context, "ws") }
                        )
                    }
                }
            }

            // ===== Provider / API settings =====
            if (connMode != "ws") {
                item {
                    SectionHeader(if (connMode == "local") "Провайдер модели для локального Codex" else "Настройки внешнего API")
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (connMode == "local") {
                                    "Эти параметры будут записаны в конфиг локального Codex CLI на телефоне. Для запуска понадобится совместимый Responses API."
                                } else {
                                    "Эти параметры используются напрямую, без запуска локального Codex CLI."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = apiUrl,
                                onValueChange = {
                                    apiUrl = it
                                    savePref(context, "api_url", it)
                                    applyStatus = null
                                    testStatus = null
                                },
                                label = { Text("Адрес API") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiModel,
                                onValueChange = {
                                    apiModel = it
                                    savePref(context, "api_model", it)
                                    applyStatus = null
                                    testStatus = null
                                },
                                label = { Text("Модель") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    savePref(context, "api_key", it)
                                    applyStatus = null
                                    testStatus = null
                                },
                                label = { Text("API-ключ") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (showApiKey) "Скрыть" else "Показать"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )

                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        isTesting = true
                                        testStatus = "Проверка подключения..."
                                        scope.launch {
                                            val (ok, message) = apiBridge.testConfiguration()
                                            isTesting = false
                                            testStatus = if (ok) "✅ $message" else "❌ $message"
                                        }
                                    },
                                    enabled = !isTesting,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isTesting) "Проверка..." else "Проверить")
                                }

                                if (connMode == "local") {
                                    Button(
                                        onClick = {
                                            val file = CodexConnectionSettings.syncCodexConfig(context)
                                            applyStatus = "✅ Конфиг Codex обновлён: ${file.absolutePath}"
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary)
                                    ) {
                                        Text("Применить")
                                    }
                                }
                            }

                            testStatus?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    it,
                                    fontSize = 12.sp,
                                    color = if (it.startsWith("✅")) StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }

                            applyStatus?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    it,
                                    fontSize = 12.sp,
                                    color = StatusOnline,
                                    lineHeight = 18.sp
                                )
                            }

                            if (connMode == "local") {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Для локального Codex нужен шлюз с поддержкой Responses API. Эти значения подхватятся при следующем запуске Codex на телефоне.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("Авторизация")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Для провайдера модели удобного OAuth-входа в этой сборке пока нет. Сейчас поддержан ручной ввод адреса API, модели и ключа. OAuth в приложении реализован только для GitHub-функций.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                SectionHeader("HTTP API по локальной сети")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Внешний доступ к чату и командам",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Открывает локальные методы /api/health и /api/external-chat для управления этим телефоном по сети.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }
                            Switch(
                                checked = httpApiEnabled,
                                onCheckedChange = { enabled ->
                                    httpApiEnabled = enabled
                                    httpApiPreferences.setEnabled(enabled)
                                    CodexExternalHttpServer.getInstance(context).refreshState()
                                    httpApiStatus = if (enabled) {
                                        "HTTP API включён на порту ${httpApiPreferences.getPort()}"
                                    } else {
                                        "HTTP API выключен"
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = httpApiPort,
                            onValueChange = {
                                httpApiPort = it.filter { ch -> ch.isDigit() }.take(5)
                                httpApiStatus = null
                            },
                            label = { Text("Порт") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = httpApiToken,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Bearer-токен") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3
                        )

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Codex HTTP API token", httpApiToken))
                                    Toast.makeText(context, "Токен скопирован", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Скопировать токен")
                            }

                            OutlinedButton(
                                onClick = {
                                    httpApiToken = httpApiPreferences.resetBearerToken()
                                    httpApiStatus = "Токен обновлён"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Сбросить токен")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val parsedPort = httpApiPort.toIntOrNull()
                                if (parsedPort == null) {
                                    httpApiStatus = "Укажи корректный порт от 1024 до 65535"
                                    return@Button
                                }

                                httpApiPreferences.setPort(parsedPort)
                                httpApiPort = httpApiPreferences.getPort().toString()
                                if (httpApiEnabled) {
                                    CodexExternalHttpServer.recreate(context)
                                    httpApiStatus = "HTTP API перезапущен на порту ${httpApiPreferences.getPort()}"
                                } else {
                                    httpApiStatus = "Порт сохранён. Сервер запустится после включения HTTP API"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary)
                        ) {
                            Text("Применить настройки HTTP API")
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Для внешнего клиента используй адрес вида $httpApiBaseUrl. Для фермы теперь доступны и /api/external-chat, и более удобный /api/run-task.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = callbackExampleUrl,
                            onValueChange = { callbackExampleUrl = it },
                            label = { Text("Адрес callback на ПК") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )

                        Spacer(Modifier.height(12.dp))
                        HttpApiExampleCard(
                            title = "Проверка health",
                            code = healthExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Задача sync через /api/run-task",
                            code = syncTaskExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Задача SSE через /api/run-task",
                            code = streamTaskExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Задача async_callback через /api/run-task",
                            code = asyncTaskExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Список запусков /api/runs",
                            code = runsListExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Статус одной задачи /api/runs/{task_id}",
                            code = runStatusExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Только running задачи /api/runs?status=running",
                            code = runsRunningExample
                        )

                        Spacer(Modifier.height(8.dp))
                        HttpApiExampleCard(
                            title = "Отмена задачи /api/runs/{task_id}/cancel",
                            code = cancelRunExample
                        )

                        httpApiStatus?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = if (it.startsWith("HTTP API") || it.startsWith("Токен") || it.startsWith("Порт сохранён")) {
                                    StatusOnline
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // ===== Security Level =====
            item {
                SectionHeader("Уровень безопасности")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Определяет, что ИИ может делать: shell-команды и доступ к файлам.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                        ConnModeOption(
                            title = "Безопасный",
                            subtitle = "Shell отключен, чтение и запись файлов только в рабочем каталоге",
                            icon = Icons.Default.Shield,
                            selected = securityLevel == "safe",
                            onClick = { securityLevel = "safe"; savePref(context, "security_level", "safe") }
                        )
                        ConnModeOption(
                            title = "Стандартный (рекомендуется)",
                            subtitle = "Shell отключен, но доступ к файлам по всей системе разрешен",
                            icon = Icons.Default.Security,
                            selected = securityLevel == "standard",
                            onClick = { securityLevel = "standard"; savePref(context, "security_level", "standard") }
                        )
                        ConnModeOption(
                            title = "Только shell",
                            subtitle = "Shell включен, а файловые инструменты остаются в рабочем каталоге",
                            icon = Icons.Default.Terminal,
                            selected = securityLevel == "shell_only",
                            onClick = { securityLevel = "shell_only"; savePref(context, "security_level", "shell_only") }
                        )
                        ConnModeOption(
                            title = "Полный",
                            subtitle = "Без ограничений: любые shell-команды и полный доступ к файлам",
                            icon = Icons.Default.LockOpen,
                            selected = securityLevel == "full",
                            onClick = { securityLevel = "full"; savePref(context, "security_level", "full") }
                        )
                    }
                }
            }

            // ===== Codex Binary =====
            item {
                SectionHeader("Движок Codex CLI")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                    Text("Состояние", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (isInstalled) "Установлен ($binarySize)" else "Не установлен",
                                        fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                            if (!isInstalled) {
                                Button(
                                    onClick = { CodexRuntimeService.start(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary)
                                ) {
                                    Text("Установить")
                                }
                            }
                        }
                        if (isInstalled) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = StatusOnline,
                                trackColor = StatusOnline.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }

            // ===== Проверка версии и обновление =====
            if (isInstalled) {
                item {
                    SectionHeader("Версия и обновление")
                }
                item {
                    CodexUpdateCard(codexManager = codexManager, context = context)
                }
            }

            // ===== Feature Navigation =====
            // ===== Ручной импорт бинарника =====
            item {
                SectionHeader("Ручной импорт")
            }
            item {
                ManualImportCard(
                    codexManager = codexManager,
                    context = context
                )
            }

            item {
                SectionHeader("Возможности")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (onOpenSkills != null) {
                            SettingsNavItem(
                                Icons.Default.Extension,
                                "Навыки Codex",
                                "Установка и управление навыками Codex",
                                onClick = onOpenSkills
                            )
                        }
                        if (onOpenMCP != null) {
                            SettingsNavItem(
                                Icons.Default.Memory,
                                "Серверы MCP",
                                "Интеграция системных инструментов Android",
                                onClick = onOpenMCP
                            )
                        }
                        if (onOpenGitHub != null) {
                            SettingsNavItem(
                                Icons.Default.Code,
                                "Импорт из GitHub",
                                "Импорт репозиториев из GitHub",
                                onClick = onOpenGitHub
                            )
                        }
                        SettingsNavItem(
                            Icons.Default.BugReport,
                            "Диагностика",
                            "Запустить диагностические тесты устройства",
                            onClick = { onOpenDiagnostic?.invoke() }
                        )
                        SettingsNavItem(
                            Icons.Default.Info,
                            "О приложении",
                            "Версия и системная информация",
                            onClick = { onOpenAbout?.invoke() }
                        )
                    }
                }
            }

            // ===== Info =====
            item {
                SectionHeader("О приложении")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow("Версия Codex CLI", CodexManager.CODEX_VERSION)
                        SettingsRow("Движок агента", "Codex CLI (OpenAI)")
                        SettingsRow("Способ интеграции", "WebSocket JSON-RPC")
                        SettingsRow("Версия приложения", "1.11.0+7")
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun ConnModeOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}

@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HttpApiExampleCard(
    title: String,
    code: String
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, code))
                    Toast.makeText(context, "Пример скопирован", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Скопировать пример")
            }
        }
    }
}

@Composable
private fun CodexUpdateCard(
    codexManager: CodexManager,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var installedVersion by remember { mutableStateOf(codexManager.getInstalledVersion()) }
    var checking by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var upgrading by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }

    val updateAvailable = latestVersion?.let { codexManager.isUpdateAvailable(it) } == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Установленная версия", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        installedVersion?.let { "v$it" } ?: "Неизвестно (ручной импорт)",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
                if (!upgrading) {
                    OutlinedButton(
                        onClick = {
                            checking = true
                            statusMsg = "Проверка..."
                            scope.launch {
                                val latest = codexManager.checkLatestVersion()
                                checking = false
                                if (latest == null) {
                                    statusMsg = "Не удалось проверить обновление: сеть недоступна или ограничена"
                                } else {
                                    latestVersion = latest
                                    statusMsg = if (codexManager.isUpdateAvailable(latest))
                                        "Найдена новая версия v$latest" else "Уже установлена последняя версия (v$latest)"
                                }
                            }
                        },
                        enabled = !checking
                    ) {
                        Text(if (checking) "Проверка..." else "Проверить обновления")
                    }
                }
            }

            statusMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (updateAvailable && !upgrading) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val target = latestVersion ?: return@Button
                        upgrading = true
                        progressPct = 0
                        statusMsg = "Обновление до v$target..."
                        scope.launch {
                            val ok = codexManager.upgradeTo(target) { progress, total ->
                                progressPct = if (total > 0) (progress * 100 / total).toInt() else 0
                            }
                            upgrading = false
                            if (ok) {
                                statusMsg = "Обновление успешно, установлена v$target"
                                installedVersion = codexManager.getInstalledVersion()
                                latestVersion = null
                                Toast.makeText(context, "Codex обновлен до v$target", Toast.LENGTH_LONG).show()
                            } else {
                                statusMsg = "Обновление не удалось, прежняя версия сохранена"
                                Toast.makeText(context, "Обновление не удалось. Проверь сеть и попробуй снова.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary)
                ) {
                    Text("Обновить до v${latestVersion}")
                }
            }

            if (upgrading) {
                Spacer(Modifier.height(12.dp))
                Text("Обновление... $progressPct%", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progressPct / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = CodexPrimary
                )
            }
        }
    }
}

@Composable
private fun ManualImportCard(
    codexManager: CodexManager,
    context: Context
) {
    var isImporting by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importOk by remember { mutableStateOf(false) }
    var helpExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            isImporting = true
            importStatus = "Импорт..."
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = java.io.File(context.cacheDir, "codex-import")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val result = codexManager.importBinaryChecked(tempFile)
                tempFile.delete()
                isImporting = false
                importOk = result.success
                importStatus = result.message
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                isImporting = false
                importOk = false
                importStatus = "Ошибка импорта: ${e.message}"
                Toast.makeText(context, "Не удалось импортировать: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Если автоматическая загрузка не сработала, можно вручную импортировать бинарник Codex.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 折叠的导入说明
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { helpExpanded = !helpExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.HelpOutline,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Инструкция по импорту (формат файла / путь / проверка)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (helpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (helpExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        appendLine("• Тип файла: исполняемый файл codex для Linux aarch64 (формат ELF)")
                        appendLine("• Не выбирай архив .tar.gz напрямую. Сначала распакуй его и возьми файл codex.")
                        appendLine("• Размер файла обычно больше 10 МБ. Слишком маленький файл значит неполную загрузку.")
                        appendLine("• При импорте проверяется заголовок ELF, при ошибке будет показана конкретная причина.")
                        append("• После импорта файл будет сохранен в filesDir/codex/codex и автоматически получит права на выполнение.")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Выбрать файл")
                }

                Button(
                    onClick = {
                        isImporting = true
                        importStatus = "Загрузка..."
                        CodexRuntimeService.start(context)
                        isImporting = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Повторить загрузку")
                }
            }

            if (importStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    importStatus!!,
                    fontSize = 12.sp,
                    color = when {
                        isImporting -> MaterialTheme.colorScheme.onSurfaceVariant
                        importOk -> StatusOnline
                        else -> MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun saveConnMode(context: Context, mode: String) {
    context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        .edit().putString("conn_mode", mode).apply()
}

private fun savePref(context: Context, key: String, value: String) {
    context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        .edit().putString(key, value).apply()
}


