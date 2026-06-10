package com.codex.android.codex

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.codex.android.util.LinuxEnvironment
import java.io.File

data class CodexConnectionConfig(
    val connMode: String,
    val apiKey: String,
    val apiUrl: String,
    val apiModel: String,
    val securityLevel: String
)

object CodexConnectionSettings {
    private const val TAG = "CodexConnectionSettings"
    const val PREFS_NAME = "codex_prefs"
    const val KEY_CONN_MODE = "conn_mode"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_URL = "api_url"
    const val KEY_API_MODEL = "api_model"
    const val KEY_SECURITY_LEVEL = "security_level"
    const val KEY_LINUX_RUNTIME_MODE = "linux_runtime_mode"

    private const val DEFAULT_CONN_MODE = "local"
    private const val DEFAULT_API_URL = "https://api.openai.com/v1"
    private const val DEFAULT_API_MODEL = "gpt-4o"
    private const val DEFAULT_SECURITY_LEVEL = "standard"
    private const val DEFAULT_LINUX_RUNTIME_MODE = "proot"

    const val CUSTOM_PROVIDER_ID = "custom_openai"
    const val CUSTOM_PROVIDER_ENV_KEY = "CODEX_OPENAI_API_KEY"

    fun load(context: Context): CodexConnectionConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CodexConnectionConfig(
            connMode = prefs.getString(KEY_CONN_MODE, DEFAULT_CONN_MODE) ?: DEFAULT_CONN_MODE,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            apiUrl = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL,
            apiModel = prefs.getString(KEY_API_MODEL, DEFAULT_API_MODEL) ?: DEFAULT_API_MODEL,
            securityLevel = prefs.getString(KEY_SECURITY_LEVEL, DEFAULT_SECURITY_LEVEL) ?: DEFAULT_SECURITY_LEVEL
        )
    }

    fun loadLinuxRuntimeMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LINUX_RUNTIME_MODE, DEFAULT_LINUX_RUNTIME_MODE)
            ?: DEFAULT_LINUX_RUNTIME_MODE
    }

    fun saveLinuxRuntimeMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LINUX_RUNTIME_MODE, mode.lowercase())
            .apply()
    }

    fun isApiMode(context: Context): Boolean = load(context).connMode == "api"

    fun hasApiConfiguration(context: Context): Boolean = hasApiConfiguration(load(context))

    fun hasApiConfiguration(config: CodexConnectionConfig): Boolean {
        return config.apiKey.isNotBlank() && config.apiUrl.isNotBlank() && config.apiModel.isNotBlank()
    }

    fun buildRuntimeEnv(context: Context): Map<String, String> {
        val config = load(context)
        if (!hasApiConfiguration(config)) return emptyMap()
        return mapOf(CUSTOM_PROVIDER_ENV_KEY to config.apiKey)
    }

    fun syncCodexConfig(context: Context): File {
        val manager = CodexManager(context)
        val file = manager.getConfigFile()
        file.parentFile?.mkdirs()
        val configText = buildCodexConfig(load(context))
        file.writeText(configText)
        mirrorConfigIntoRootfs(context, configText)
        return file
    }

    private fun mirrorConfigIntoRootfs(context: Context, configText: String) {
        try {
            val linuxEnv = LinuxEnvironment(context)
            val linuxInfo = linuxEnv.getInfo()
            if (linuxInfo.state != LinuxEnvironment.EngineState.READY) return

            val rootConfig = File(linuxInfo.rootfsPath, "root/.codex/config.toml")
            rootConfig.parentFile?.mkdirs()

            val preservedProjectsSection = if (rootConfig.exists()) {
                val existing = rootConfig.readText()
                val idx = existing.indexOf("[projects.")
                if (idx >= 0) existing.substring(idx).trim() else ""
            } else {
                ""
            }

            val merged = buildString {
                append(configText.trimEnd())
                if (preservedProjectsSection.isNotBlank()) {
                    append("\n\n")
                    append(preservedProjectsSection)
                }
                append("\n")
            }

            rootConfig.writeText(merged)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mirror config into rootfs", e)
        }
    }

    fun buildCodexConfig(config: CodexConnectionConfig): String {
        val builder = StringBuilder()
        builder.appendLine("# Codex Android Configuration")

        if (hasApiConfiguration(config)) {
            builder.appendLine("model_provider = ${tomlString(CUSTOM_PROVIDER_ID)}")
            builder.appendLine("model = ${tomlString(config.apiModel)}")
            builder.appendLine("model_reasoning_effort = \"high\"")
        } else {
            builder.appendLine("provider = \"openai\"")
            builder.appendLine("model = ${tomlString(config.apiModel.ifBlank { DEFAULT_API_MODEL })}")
        }

        builder.appendLine("approval = \"never\"")
        builder.appendLine("sandbox = \"off\"")
        builder.appendLine("skip-git-repo-check = true")
        builder.appendLine("experimental_features = true")
        builder.appendLine()

        if (hasApiConfiguration(config)) {
            builder.appendLine("[model_providers.$CUSTOM_PROVIDER_ID]")
            builder.appendLine("name = \"Custom OpenAI-Compatible API\"")
            builder.appendLine("base_url = ${tomlString(config.apiUrl.trimEnd('/'))}")
            builder.appendLine("env_key = \"$CUSTOM_PROVIDER_ENV_KEY\"")
            builder.appendLine("wire_api = \"responses\"")
            builder.appendLine()
        }

        builder.appendLine("[features]")
        builder.appendLine("codex-agent = true")
        builder.appendLine("codex-mcp = true")
        return builder.toString().trim() + "\n"
    }

    private fun tomlString(value: String): String {
        return buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}
