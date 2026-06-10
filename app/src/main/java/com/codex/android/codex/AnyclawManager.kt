package com.codex.android.codex

import android.content.Context
import android.util.Log
import com.codex.android.core.CodexCoreModule
import com.codex.android.runtime.root.SuRootBridge
import com.codex.android.service.CodexNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anyclaw 工具注册表管理器。
 *
 * 吸收 anyclaw 的核心功能：
 * - 工具注册表（搜索、安装、卸载、列表）
 * - 多适配器支持（OpenAPI、CLI、脚本）
 * - MCP 工具导出
 * - Skills 生成
 *
 * 完全 Kotlin 原生实现，不依赖 Go 二进制。
 */
class AnyclawManager(private val context: Context) {

    companion object {
        private const val TAG = "AnyclawManager"
        private const val REGISTRY_DIR = "anyclaw"
        private const val PACKAGES_FILE = "packages.json"
        private const val REGISTRY_URL = "https://raw.githubusercontent.com/fastclaw-ai/anyclaw/main/registry"

        // 内置推荐工具（不需要联网即可使用）
        val BUILTIN_PACKAGES = listOf(
            AnyclawPackage(
                id = "android-shell",
                name = "Root Shell",
                description = "Выполнение команд Root Shell",
                type = "builtin",
                tools = listOf(
                    ToolDef("shell", "Выполнить произвольную команду Root Shell", mapOf("command" to "string")),
                    ToolDef("device_info", "Получить информацию об устройстве"),
                    ToolDef("battery", "Получить состояние батареи"),
                    ToolDef("network", "Получить состояние сети"),
                ),
                isBuiltin = true
            ),
            AnyclawPackage(
                id = "android-file",
                name = "Файловая система Android",
                description = "Чтение и запись файлов Android",
                type = "builtin",
                tools = listOf(
                    ToolDef("read_file", "Прочитать файл", mapOf("path" to "string")),
                    ToolDef("write_file", "Записать файл", mapOf("path" to "string", "content" to "string")),
                    ToolDef("list_dir", "Показать содержимое каталога", mapOf("path" to "string")),
                ),
                isBuiltin = true
            ),
            AnyclawPackage(
                id = "android-notification",
                name = "Уведомления и подсказки",
                description = "Отправка уведомлений и Toast",
                type = "builtin",
                tools = listOf(
                    ToolDef("notify", "Отправить уведомление", mapOf("title" to "string", "content" to "string")),
                    ToolDef("toast", "Показать Toast", mapOf("message" to "string")),
                    ToolDef("list", "Показать последние системные уведомления", mapOf("limit" to "string")),
                ),
                isBuiltin = true
            ),
            AnyclawPackage(
                id = "codex-runtime",
                name = "Среда выполнения Codex",
                description = "Управление состоянием Codex CLI",
                type = "builtin",
                tools = listOf(
                    ToolDef("start", "Запустить Codex"),
                    ToolDef("stop", "Остановить Codex"),
                    ToolDef("restart", "Перезапустить Codex"),
                    ToolDef("status", "Получить состояние"),
                    ToolDef("logs", "Получить журнал работы"),
                ),
                isBuiltin = true
            ),
            AnyclawPackage(
                id = "codex-core",
                name = "Core контекст",
                description = "Приём задач, анализ уведомлений и подготовка маршрута для диспетчера",
                type = "builtin",
                tools = listOf(
                    ToolDef("submit", "Передать задачу в Core", mapOf("prompt" to "string")),
                    ToolDef("analyze", "Проанализировать последний сигнал Core", mapOf("prompt" to "string")),
                    ToolDef("context", "Получить собранный Core-контекст", mapOf("prompt" to "string"))
                ),
                isBuiltin = true
            ),
        )
    }

    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, String> = emptyMap()
    )

    data class AnyclawPackage(
        val id: String,
        val name: String,
        val description: String,
        val type: String = "registry",  // builtin, registry, openapi, cli, script
        val tools: List<ToolDef> = emptyList(),
        val source: String = "",  // 来源 URL
        val isBuiltin: Boolean = false,
        val isInstalled: Boolean = false,
        val config: Map<String, String> = emptyMap()
    )

    enum class RegistryState {
        IDLE, LOADING, READY, ERROR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryDir = File(context.filesDir, REGISTRY_DIR)
    private val packagesFile = File(registryDir, PACKAGES_FILE)
    private val rootBridge = SuRootBridge()

    private val _state = MutableStateFlow(RegistryState.IDLE)
    val state: StateFlow<RegistryState> = _state.asStateFlow()

    private val _installedPackages = MutableStateFlow<List<AnyclawPackage>>(emptyList())
    val installedPackages: StateFlow<List<AnyclawPackage>> = _installedPackages.asStateFlow()

    private val _registryPackages = MutableStateFlow<List<AnyclawPackage>>(emptyList())
    val registryPackages: StateFlow<List<AnyclawPackage>> = _registryPackages.asStateFlow()

    private val _searchResults = MutableStateFlow<List<AnyclawPackage>>(emptyList())
    val searchResults: StateFlow<List<AnyclawPackage>> = _searchResults.asStateFlow()

    init {
        registryDir.mkdirs()
        loadInstalled()
        ensureBuiltinPackagesInstalled()
    }

    private fun ensureBuiltinPackagesInstalled() {
        val installed = _installedPackages.value.toMutableList()
        var changed = false

        for (builtin in BUILTIN_PACKAGES) {
            val builtinCopy = builtin.copy(isInstalled = true)
            val existingIndex = installed.indexOfFirst { it.id == builtin.id }
            if (existingIndex >= 0) {
                if (installed[existingIndex] != builtinCopy) {
                    installed[existingIndex] = builtinCopy
                    changed = true
                }
            } else {
                installed.add(0, builtinCopy)
                changed = true
            }
        }

        if (changed) {
            _installedPackages.value = installed
            saveInstalled()
        }
    }

    suspend fun search(query: String): List<AnyclawPackage> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AnyclawPackage>()

        // 在已安装和内置包中搜索
        for (pkg in _installedPackages.value) {
            if (pkg.name.contains(query, ignoreCase = true) ||
                pkg.description.contains(query, ignoreCase = true) ||
                pkg.tools.any { it.name.contains(query, ignoreCase = true) }
            ) {
                results.add(pkg)
            }
        }

        // 在线搜索注册表
        try {
            val onlineResults = searchRegistryOnline(query)
            for (pkg in onlineResults) {
                if (results.none { it.id == pkg.id }) {
                    results.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "在线搜索注册表失败，仅返回本地结果", e)
        }

        _searchResults.value = results
        results
    }

    /**
     * 在线搜索 anyclaw 注册表
     */
    private suspend fun searchRegistryOnline(query: String): List<AnyclawPackage> {
        return try {
            val url = URL("$REGISTRY_URL/index.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Codex-Android/1.0")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val packages = json.optJSONArray("packages") ?: JSONArray()
                val results = mutableListOf<AnyclawPackage>()

                for (i in 0 until packages.length()) {
                    val pkg = packages.getJSONObject(i)
                    val name = pkg.optString("name", "")
                    val desc = pkg.optString("description", "")
                    if (name.contains(query, ignoreCase = true) ||
                        desc.contains(query, ignoreCase = true)
                    ) {
                        val toolsArray = pkg.optJSONArray("tools") ?: JSONArray()
                        val tools = mutableListOf<ToolDef>()
                        for (j in 0 until toolsArray.length()) {
                            val t = toolsArray.getJSONObject(j)
                            tools.add(ToolDef(
                                name = t.optString("name", "tool_$j"),
                                description = t.optString("description", "")
                            ))
                        }
                        results.add(AnyclawPackage(
                            id = pkg.optString("id", name),
                            name = name,
                            description = desc,
                            type = pkg.optString("type", "registry"),
                            tools = tools,
                            source = pkg.optString("source", "")
                        ))
                    }
                }
                results
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "搜索注册表失败", e)
            emptyList()
        }
    }

    /**
     * 安装工具包
     */
    suspend fun installPackage(packageId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 如果是内置包，直接标记安装
            val builtin = BUILTIN_PACKAGES.find { it.id == packageId }
            if (builtin != null) {
                val installed = _installedPackages.value.toMutableList()
                if (installed.none { it.id == builtin.id }) {
                    installed.add(builtin.copy(isInstalled = true))
                    _installedPackages.value = installed
                    saveInstalled()
                }
                return@withContext true
            }

            // 从注册表下载安装
            val pkgUrl = URL("$REGISTRY_URL/packages/$packageId.json")
            val conn = pkgUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val toolsArray = json.optJSONArray("tools") ?: JSONArray()
                val tools = mutableListOf<ToolDef>()
                for (i in 0 until toolsArray.length()) {
                    val t = toolsArray.getJSONObject(i)
                    val params = mutableMapOf<String, String>()
                    val paramsObj = t.optJSONObject("parameters")
                    if (paramsObj != null) {
                        for (key in paramsObj.keys()) {
                            params[key] = paramsObj.optString(key, "string")
                        }
                    }
                    tools.add(ToolDef(
                        name = t.optString("name", "tool_$i"),
                        description = t.optString("description", ""),
                        parameters = params
                    ))
                }

                val pkg = AnyclawPackage(
                    id = packageId,
                    name = json.optString("name", packageId),
                    description = json.optString("description", ""),
                    type = json.optString("type", "registry"),
                    tools = tools,
                    source = json.optString("source", ""),
                    isInstalled = true
                )

                val installed = _installedPackages.value.toMutableList()
                installed.removeAll { it.id == packageId }
                installed.add(pkg.copy(isInstalled = true))
                _installedPackages.value = installed
                saveInstalled()
                Log.i(TAG, "已安装工具包: $packageId (${tools.size} 个工具)")
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "安装工具包失败: $packageId", e)
            false
        }
    }

    /**
     * 卸载工具包
     */
    fun uninstallPackage(packageId: String) {
        val installed = _installedPackages.value.toMutableList()
        // 内置包不能卸载
        installed.removeAll { it.id == packageId && !it.isBuiltin }
        _installedPackages.value = installed
        saveInstalled()
        Log.i(TAG, "已卸载工具包: $packageId")
    }

    /**
     * 获取所有工具（合并内置 + 已安装）
     */
    fun getAllTools(): List<ToolDef> {
        return _installedPackages.value.flatMap { pkg ->
            pkg.tools.map { tool ->
                tool.copy(name = "${pkg.id}_${tool.name}")
            }
        }
    }

    /**
     * 生成 SKILL.md 内容
     */
    fun generateSkillsMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# Codex Android Tools")
        sb.appendLine()
        sb.appendLine("通过 Anyclaw 注册表安装的工具包。")
        sb.appendLine()

        for (pkg in _installedPackages.value) {
            sb.appendLine("## ${pkg.name}")
            sb.appendLine()
            sb.appendLine("${pkg.description}")
            sb.appendLine()
            sb.appendLine("### 可用工具")
            sb.appendLine()
            for (tool in pkg.tools) {
                sb.appendLine("- `${tool.name}`: ${tool.description}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 获取工具 MCP 描述 JSON
     */
    fun getToolsMcpJson(): JSONArray {
        val arr = JSONArray()
        for (pkg in _installedPackages.value) {
            for (tool in pkg.tools) {
                val toolObj = JSONObject().apply {
                    put("name", "${pkg.id}_${tool.name}")
                    put("description", tool.description)
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            for ((key, type) in tool.parameters) {
                                put(key, JSONObject().apply {
                                    put("type", type)
                                })
                            }
                        })
                    })
                }
                arr.put(toolObj)
            }
        }
        return arr
    }

    /**
     * 搜索注册表（简洁版）
     */
    suspend fun refreshRegistry(): List<AnyclawPackage> = withContext(Dispatchers.IO) {
        _state.value = RegistryState.LOADING
        try {
            val url = URL("$REGISTRY_URL/index.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Codex-Android/1.0")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val packages = json.optJSONArray("packages") ?: JSONArray()
                val results = mutableListOf<AnyclawPackage>()

                for (i in 0 until packages.length()) {
                    val pkg = packages.getJSONObject(i)
                    results.add(AnyclawPackage(
                        id = pkg.optString("id", "pkg_$i"),
                        name = pkg.optString("name", "Package $i"),
                        description = pkg.optString("description", ""),
                        type = pkg.optString("type", "registry"),
                        source = pkg.optString("source", "")
                    ))
                }

                _registryPackages.value = results
                _state.value = RegistryState.READY
                return@withContext results
            }
            _state.value = RegistryState.ERROR
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "刷新注册表失败", e)
            _state.value = RegistryState.ERROR
            emptyList()
        }
    }

    /**
     * 通过 OpenAPI 规范导入工具
     */
    suspend fun importFromOpenApi(name: String, specUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(specUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val spec = JSONObject(conn.inputStream.bufferedReader().readText())
                val paths = spec.optJSONObject("paths") ?: JSONObject()
                val tools = mutableListOf<ToolDef>()

                for (pathKey in paths.keys()) {
                    val methods = paths.getJSONObject(pathKey)
                    for (methodKey in methods.keys()) {
                        val operation = methods.getJSONObject(methodKey)
                        val operationId = operation.optString("operationId", "${methodKey}_${pathKey.replace("/", "_")}")
                        val summary = operation.optString("summary", operationId)
                        val params = mutableMapOf<String, String>()

                        val parameters = operation.optJSONArray("parameters")
                        if (parameters != null) {
                            for (i in 0 until parameters.length()) {
                                val param = parameters.getJSONObject(i)
                                params[param.optString("name", "param_$i")] = param.optString("in", "string")
                            }
                        }

                        tools.add(ToolDef(
                            name = operationId,
                            description = summary,
                            parameters = params
                        ))
                    }
                }

                if (tools.isNotEmpty()) {
                    val pkg = AnyclawPackage(
                        id = "openapi_${name.lowercase().replace(" ", "_")}",
                        name = name,
                        description = "从 OpenAPI 规范导入: $specUrl",
                        type = "openapi",
                        tools = tools,
                        source = specUrl,
                        isInstalled = true
                    )
                    val installed = _installedPackages.value.toMutableList()
                    installed.add(pkg.copy(isInstalled = true))
                    _installedPackages.value = installed
                    saveInstalled()
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "导入 OpenAPI 失败", e)
            false
        }
    }

    /**
     * 手动添加 CLI 工具
     */
    fun addCliTool(name: String, command: String, description: String): Boolean {
        return try {
            val pkg = AnyclawPackage(
                id = "cli_${name.lowercase().replace(" ", "_")}",
                name = name,
                description = description,
                type = "cli",
                tools = listOf(ToolDef(name = "run", description = "运行 $name: $command", parameters = mapOf("args" to "string"))),
                config = mapOf("command" to command),
                isInstalled = true
            )
            val installed = _installedPackages.value.toMutableList()
            installed.add(pkg.copy(isInstalled = true))
            _installedPackages.value = installed
            saveInstalled()
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加 CLI 工具失败: $name", e)
            false
        }
    }

    /**
     * 执行工具（通过 MCP 桥接）
     */
    suspend fun executeTool(toolName: String, args: Map<String, String> = emptyMap()): String {
        // 解析工具名: packageId_toolName
        val parts = toolName.split("_", limit = 2)
        if (parts.size < 2) return "{\"error\":\"invalid tool name: $toolName\"}"

        val pkgId = parts[0]
        val toolNameOnly = parts[1]
        val pkg = _installedPackages.value.find { it.id == pkgId }
            ?: return "{\"error\":\"package not installed: $pkgId\"}"

        return when (pkgId) {
            "android-shell" -> executeAndroidShell(toolNameOnly, args)
            "android-file" -> executeAndroidFile(toolNameOnly, args)
            "android-notification" -> executeAndroidNotification(toolNameOnly, args)
            "codex-runtime" -> executeCodexRuntime(toolNameOnly, args)
            "codex-core" -> executeCodexCore(toolNameOnly, args)
            else -> "{\"error\":\"unknown tool: $toolName\"}"
        }
    }

    private suspend fun executeCodexCore(tool: String, args: Map<String, String>): String {
        return try {
            val prompt = args["prompt"] ?: ""
            when (tool) {
                "submit" -> {
                    val plan = CodexCoreModule.submitTask(prompt)
                        ?: return "{\"success\":false,\"error\":\"empty prompt\"}"
                    JSONObject().apply {
                        put("success", true)
                        put("taskSummary", plan.taskSummary)
                        put("recommendedToolId", plan.recommendedToolId)
                        put("confidence", plan.confidence)
                        put("reason", plan.reason)
                        put("signals", JSONArray(plan.signals))
                        put("nextStep", plan.nextStep)
                        put("transitionCondition", plan.transitionCondition)
                        put("notificationActionable", plan.notificationActionable)
                        put("notificationTopActions", JSONArray(plan.notificationTopActions))
                    }.toString(2)
                }
                "analyze" -> {
                    val analysis = CodexCoreModule.analyzeLatestNotification()
                        ?: return "{\"success\":false,\"error\":\"no notification context\"}"
                    JSONObject().apply {
                        put("success", true)
                        put("summary", analysis.summary)
                        put("recommendedToolId", analysis.recommendedToolId)
                        put("confidence", analysis.confidence)
                        put("reason", analysis.reason)
                        put("signals", JSONArray(analysis.signals))
                        put("actionable", analysis.actionable)
                        put("topActions", JSONArray(analysis.topActions))
                    }.toString(2)
                }
                "context" -> {
                    val contextText = CodexCoreModule.buildTaskContext(prompt)
                    JSONObject().apply {
                        put("success", true)
                        put("context", contextText)
                    }.toString(2)
                }
                else -> "{\"success\":false,\"error\":\"unknown tool: $tool\"}"
            }
        } catch (e: Exception) {
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private suspend fun executeAndroidShell(tool: String, args: Map<String, String>): String {
        if (tool == "shell" && !com.codex.android.security.SecurityPolicy.isShellAllowed(context)) {
            return com.codex.android.security.SecurityPolicy.shellDeniedResponse(context)
        }
        return try {
            val command = when (tool) {
                "shell" -> args["command"] ?: "echo 'no command'"
                "device_info" -> """
                    echo "Device: $(getprop ro.product.model)"
                    echo "Android: $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))"
                    echo "Arch: $(getprop ro.product.cpu.abi)"
                    echo "Manufacturer: $(getprop ro.product.manufacturer)"
                """.trimIndent()
                "battery" -> "dumpsys battery 2>/dev/null || echo 'battery info unavailable'"
                "network" -> "dumpsys connectivity 2>/dev/null | head -20 || echo 'network info unavailable'"
                else -> "echo 'unknown tool: $tool'"
            }

            // 危险命令需用户显式确认后才执行（在「完全」等级下作为中间安全网）。
            com.codex.android.security.SecurityPolicy.dangerousCommandReason(command)?.let { reason ->
                val approved = com.codex.android.security.ShellConfirmationManager.requestApproval(command, reason)
                if (!approved) {
                    return com.codex.android.security.SecurityPolicy.shellRejectedByUserResponse(command, reason)
                }
            }

            val result = rootBridge.run(command)
            JSONObject().apply {
                put("success", result.exitCode == 0)
                put("output", result.stdout.trim())
                put("stderr", result.stderr.trim())
                put("exitCode", result.exitCode)
            }.toString(2)
        } catch (e: Exception) {
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private suspend fun executeAndroidFile(tool: String, args: Map<String, String>): String {
        return try {
            when (tool) {
                "read_file" -> {
                    val path = args["path"] ?: return "{\"success\":false,\"error\":\"path required\"}"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    val content = File(path).readText()
                    JSONObject().apply {
                        put("success", true)
                        put("content", content)
                        put("path", path)
                    }.toString(2)
                }
                "write_file" -> {
                    val path = args["path"] ?: return "{\"success\":false,\"error\":\"path required\"}"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    val content = args["content"] ?: ""
                    File(path).writeText(content)
                    "{\"success\":true,\"path\":\"$path\",\"size\":${content.length}}"
                }
                "list_dir" -> {
                    val path = args["path"] ?: return "{\"success\":false,\"error\":\"path required\"}"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    val dir = File(path)
                    if (!dir.isDirectory) return "{\"success\":false,\"error\":\"not a directory: $path\"}"
                    val files = dir.listFiles()?.map { f ->
                        mapOf(
                            "name" to f.name,
                            "isDir" to f.isDirectory,
                            "size" to f.length()
                        )
                    } ?: emptyList()
                    JSONObject().apply {
                        put("success", true)
                        put("files", JSONArray(files.map { JSONObject(it) }))
                    }.toString(2)
                }
                else -> "{\"success\":false,\"error\":\"unknown tool: $tool\"}"
            }
        } catch (e: Exception) {
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private suspend fun executeAndroidNotification(tool: String, args: Map<String, String>): String {
        return try {
            when (tool) {
                "notify" -> {
                    val title = args["title"] ?: "Codex"
                    val content = args["content"] ?: ""
                    CodexNotifications.createChannels(context)
                    CodexNotifications.notifyTaskDone(context, title, content)
                    JSONObject().apply {
                        put("success", true)
                        put("channel", CodexNotifications.CHANNEL_TASK)
                        put("title", title)
                        put("content", content)
                    }.toString(2)
                }
                "toast" -> {
                    val message = args["message"] ?: "Hello"
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    JSONObject().apply {
                        put("success", true)
                        put("message", message)
                    }.toString(2)
                }
                "list" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    val notifications = CodexCoreModule.getRecentNotifications(limit)
                    JSONObject().apply {
                        put("success", true)
                        put("count", notifications.size)
                        put("notifications", JSONArray(notifications.map { entry ->
                            JSONObject().apply {
                                put("packageName", entry.packageName)
                                put("appLabel", entry.appLabel)
                                put("title", entry.title)
                                put("text", entry.text)
                                put("postTime", entry.postTime)
                                put("key", entry.key)
                                put("ongoing", entry.isOngoing)
                            }
                        }))
                    }.toString(2)
                }
                "analyze" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val analysis = CodexCoreModule.analyzeLatestNotification(limit)
                        ?: return "{\"success\":false,\"error\":\"no notifications available\"}"
                    JSONObject().apply {
                        put("success", true)
                        put("summary", analysis.summary)
                        put("recommendedToolId", analysis.recommendedToolId)
                        put("confidence", analysis.confidence)
                        put("reason", analysis.reason)
                        put("actionable", analysis.actionable)
                        put("signals", JSONArray(analysis.signals))
                        put("topActions", JSONArray(analysis.topActions))
                    }.toString(2)
                }
                else -> "{\"success\":false,\"error\":\"unknown tool: $tool\"}"
            }
        } catch (e: Exception) {
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private suspend fun executeCodexRuntime(tool: String, args: Map<String, String>): String {
        return try {
            when (tool) {
                "status" -> {
                    "{\"success\":true,\"status\":\"running\",\"version\":\"${CodexManager.CODEX_VERSION}\"}"
                }
                else -> "{\"success\":false,\"error\":\"unknown tool: $tool\"}"
            }
        } catch (e: Exception) {
            "{\"success\":false,\"error\":\"${e.message}\"}"
        }
    }

    private fun saveInstalled() {
        try {
            val arr = JSONArray()
            for (pkg in _installedPackages.value) {
                val pkgObj = JSONObject().apply {
                    put("id", pkg.id)
                    put("name", pkg.name)
                    put("description", pkg.description)
                    put("type", pkg.type)
                    put("source", pkg.source)
                    put("isBuiltin", pkg.isBuiltin)

                    val toolsArr = JSONArray()
                    for (tool in pkg.tools) {
                        toolsArr.put(JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", JSONObject(tool.parameters))
                        })
                    }
                    put("tools", toolsArr)
                    put("config", JSONObject(pkg.config))
                }
                arr.put(pkgObj)
            }
            packagesFile.writeText(JSONObject().apply {
                put("packages", arr)
            }.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "保存已安装包列表失败", e)
        }
    }

    private fun loadInstalled() {
        try {
            if (packagesFile.exists()) {
                val json = JSONObject(packagesFile.readText())
                val arr = json.optJSONArray("packages") ?: JSONArray()
                val packages = mutableListOf<AnyclawPackage>()

                for (i in 0 until arr.length()) {
                    val pkg = arr.getJSONObject(i)
                    val toolsArr = pkg.optJSONArray("tools") ?: JSONArray()
                    val tools = mutableListOf<ToolDef>()
                    for (j in 0 until toolsArr.length()) {
                        val t = toolsArr.getJSONObject(j)
                        val params = mutableMapOf<String, String>()
                        val paramsObj = t.optJSONObject("parameters")
                        if (paramsObj != null) {
                            for (key in paramsObj.keys()) {
                                params[key] = paramsObj.optString(key, "string")
                            }
                        }
                        tools.add(ToolDef(
                            name = t.optString("name", "tool_$j"),
                            description = t.optString("description", ""),
                            parameters = params
                        ))
                    }

                    val configMap = mutableMapOf<String, String>()
                    val configObj = pkg.optJSONObject("config")
                    if (configObj != null) {
                        for (key in configObj.keys()) {
                            configMap[key] = configObj.optString(key, "")
                        }
                    }

                    packages.add(AnyclawPackage(
                        id = pkg.getString("id"),
                        name = pkg.optString("name", ""),
                        description = pkg.optString("description", ""),
                        type = pkg.optString("type", "registry"),
                        tools = tools,
                        source = pkg.optString("source", ""),
                        isBuiltin = pkg.optBoolean("isBuiltin", false),
                        isInstalled = true,
                        config = configMap
                    ))
                }
                _installedPackages.value = packages
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载已安装包列表失败", e)
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
