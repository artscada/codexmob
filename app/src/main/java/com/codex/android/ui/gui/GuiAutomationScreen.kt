package com.codex.android.ui.gui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.bridge.gui.AutomationScheduler
import com.codex.android.bridge.gui.AutomationTask
import com.codex.android.bridge.gui.GuiAutomationEngine
import com.codex.android.bridge.gui.RouteDatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuiAutomationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { RouteDatabaseHelper.getInstance(context) }
    
    var tasks by remember { mutableStateOf(listOf<AutomationTask>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isExecuting by remember { mutableStateOf(false) }

    fun refreshTasks() {
        coroutineScope.launch(Dispatchers.IO) {
            val list = db.getAllAutomationTasks()
            withContext(Dispatchers.Main) {
                tasks = list
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshTasks()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("GUI Автоматизация", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTasks() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить задачу")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            "Нет запланированных задач",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Нажмите + чтобы добавить новую задачу",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskItemCard(
                            task = task,
                            onToggle = { enabled ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    db.toggleAutomationTask(task.id, enabled)
                                    val updatedTask = task.copy(enabled = enabled)
                                    if (enabled) {
                                        AutomationScheduler.scheduleTask(context, updatedTask)
                                    } else {
                                        AutomationScheduler.cancelTask(context, task.id)
                                    }
                                    refreshTasks()
                                }
                            },
                            onDelete = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    AutomationScheduler.cancelTask(context, task.id)
                                    db.deleteAutomationTask(task.id)
                                    refreshTasks()
                                }
                            },
                            onRunNow = {
                                if (isExecuting) {
                                    Toast.makeText(context, "Другая задача уже выполняется", Toast.LENGTH_SHORT).show()
                                    return@TaskItemCard
                                }
                                isExecuting = true
                                Toast.makeText(context, "Запуск задачи: '${task.prompt}'", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val result = GuiAutomationEngine.getInstance(context).executeGuiAction(
                                        action = "do_task",
                                        path = "",
                                        content = task.prompt,
                                        timeoutMs = 180000L
                                    )
                                    withContext(Dispatchers.Main) {
                                        isExecuting = false
                                        if (result.exitCode == 0) {
                                            Toast.makeText(context, "Задача завершена успешно", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Сбой: ${result.stderr}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (isExecuting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Выполнение GUI автоматизации...", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddAutomationTaskDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { prompt, mode, timeValue ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val task = AutomationTask(
                            prompt = prompt,
                            mode = mode,
                            timeValue = timeValue,
                            enabled = true,
                            nextExecution = 0
                        )
                        val newId = db.addAutomationTask(task)
                        val inserted = db.getAutomationTask(newId.toInt())
                        if (inserted != null) {
                            AutomationScheduler.scheduleTask(context, inserted)
                        }
                        refreshTasks()
                    }
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun TaskItemCard(
    task: AutomationTask,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.prompt,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val icon = if (task.mode == "scheduled") Icons.Filled.Schedule else Icons.Filled.Casino
                        val modeLabel = if (task.mode == "scheduled") "По расписанию" else "Случайно"
                        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "$modeLabel: ${task.timeValue}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggle
                )
            }

            if (task.enabled && task.nextExecution > 0) {
                Text(
                    text = "Следующий запуск: ${java.util.Date(task.nextExecution)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onRunNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Запустить сейчас", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun AddAutomationTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (prompt: String, mode: String, timeValue: String) -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("scheduled") } // "scheduled" or "random"
    var timeValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Новая задача автоматизации", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Инструкция (Промпт)") },
                    placeholder = { Text("например, Выполни ежедневный чекин Литрес") },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Режим запуска:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == "scheduled",
                                onClick = {
                                    mode = "scheduled"
                                    if (timeValue.contains("-")) timeValue = ""
                                }
                            )
                            Text("Время", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == "random",
                                onClick = {
                                    mode = "random"
                                    if (!timeValue.contains("-")) timeValue = ""
                                }
                            )
                            Text("Случайный интервал", fontSize = 14.sp)
                        }
                    }
                }

                val label = if (mode == "scheduled") "Время запуска (ЧЧ:ММ)" else "Диапазон (ЧЧ:ММ-ЧЧ:ММ)"
                val placeholder = if (mode == "scheduled") "например, 08:00" else "например, 12:00-16:00"
                
                OutlinedTextField(
                    value = timeValue,
                    onValueChange = { timeValue = it },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (prompt.isNotBlank() && timeValue.isNotBlank()) {
                        onConfirm(prompt, mode, timeValue)
                    }
                },
                enabled = prompt.isNotBlank() && timeValue.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
