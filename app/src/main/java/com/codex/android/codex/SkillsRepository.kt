package com.codex.android.codex

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Codex Skills/Plugin 数据仓库。
 *
 * 参照 opencode plugin 系统和 Claude Code skills 设计：
 * - 本地文件系统扫描 (codex_skills/)
 * - GitHub 市场源
 * - 插件生命周期管理（安装/卸载/脚手架）
 * - SKILL.md 元数据解析
 */
class SkillsRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillsRepository"
        private const val PREFS_NAME = "codex_skills"
        private const val KEY_INSTALLED = "installed_skills"
        private const val KEY_MARKETS = "skill_markets"
        private const val SKILLS_DIR = "codex_skills"
        private const val SKILL_MANIFEST = "SKILL.md"
    }

    data class SkillInfo(
        val name: String,
        val title: String,
        val description: String,
        val version: String,
        val market: String,
        val installed: Boolean = false,
        val category: String = "general",
        val author: String = "",
        val permissions: List<String> = emptyList(),
        val icon: String = "",
        val localPath: String = "",
        val isBuiltin: Boolean = false
    )

    data class SkillMarket(
        val id: String,
        val name: String,
        val description: String,
        val url: String = "",
        val type: String = "builtin"
    )

    data class SkillManifest(
        val name: String,
        val title: String,
        val description: String,
        val version: String,
        val category: String = "general",
        val author: String = "",
        val permissions: List<String> = emptyList(),
        val icon: String = ""
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val skillsBaseDir = File(context.filesDir, SKILLS_DIR)

    init {
        skillsBaseDir.mkdirs()
    }

    fun getInstalledSkills(): List<SkillInfo> {
        val fromPrefs = getInstalledFromPrefs()
        val fromLocal = scanLocalSkills()
        val result = fromPrefs.toMutableList()
        for (local in fromLocal) {
            val idx = result.indexOfFirst { it.name == local.name }
            if (idx >= 0) {
                result[idx] = result[idx].copy(installed = true, localPath = local.localPath)
            } else {
                result.add(local)
            }
        }
        return result.map { if (it.localPath.isEmpty()) it.copy(installed = false) else it }
    }

    suspend fun getAvailableSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        val installedNames = getInstalledNames()
        val local = scanLocalSkills()
        val fromMarkets = fetchMarketSkills()
        (local + fromMarkets).distinctBy { it.name }
            .map { it.copy(installed = it.name in installedNames || it.localPath.isNotEmpty()) }
    }

    private fun scanLocalSkills(): List<SkillInfo> {
        if (!skillsBaseDir.exists()) return emptyList()
        return skillsBaseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val manifest = parseManifest(dir)
            if (manifest != null) {
                SkillInfo(
                    name = manifest.name, title = manifest.title,
                    description = manifest.description, version = manifest.version,
                    market = "local", installed = true, category = manifest.category,
                    author = manifest.author, permissions = manifest.permissions,
                    localPath = dir.absolutePath, isBuiltin = false
                )
            } else {
                SkillInfo(
                    name = dir.name,
                    title = dir.name.replace("-", " ").replaceFirstChar { it.uppercase() },
                    description = "本地安装的插件", version = "0.0.1",
                    market = "local", installed = true, localPath = dir.absolutePath
                )
            }
        } ?: emptyList()
    }

    private fun parseManifest(dir: File): SkillManifest? {
        val manifestFile = File(dir, SKILL_MANIFEST)
        if (!manifestFile.exists()) return null
        return try {
            val text = manifestFile.readText()
            parseMarkdownManifest(text)
        } catch (e: Exception) {
            Log.w(TAG, "解析 SKILL.md 失败: ${dir.name}", e)
            null
        }
    }

    private fun parseMarkdownManifest(text: String): SkillManifest {
        val name = extractField(text, "name") ?: "unknown"
        val title = extractField(text, "title") ?: name
        val description = extractField(text, "description") ?: ""
        val version = extractField(text, "version") ?: "0.0.1"
        val category = extractField(text, "category") ?: "general"
        val author = extractField(text, "author") ?: ""
        return SkillManifest(name, title, description, version, category, author)
    }

    private fun extractField(text: String, key: String): String? {
        val regex = Regex("""^#+\s*\**$key\**:?\s*(.+)$""", RegexOption.MULTILINE)
        return regex.find(text)?.groupValues?.get(1)?.trim()?.removePrefix("- ")
    }

    private suspend fun fetchMarketSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        val markets = getRegisteredMarkets()
        markets.flatMap { market ->
            when (market.type) {
                "github" -> fetchGitHubMarket(market)
                else -> emptyList()
            }
        }
    }

    private fun fetchGitHubMarket(market: SkillMarket): List<SkillInfo> {
        return try {
            val url = URL("${market.url}/contents/skills")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val json = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until json.length()).map { i ->
                val item = json.getJSONObject(i)
                SkillInfo(
                    name = item.optString("name"),
                    title = item.optString("name").replace("-", " ").replaceFirstChar { it.uppercase() },
                    description = "来自 ${market.name} 的插件",
                    version = "1.0.0", market = market.id, category = "market"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取市场 ${market.id} 失败", e)
            emptyList()
        }
    }

    fun getMarkets(): List<SkillMarket> = getRegisteredMarkets()

    private fun getRegisteredMarkets(): List<SkillMarket> {
        val json = prefs.getString(KEY_MARKETS, null)
        val custom = if (json != null) {
            try {
                JSONArray(json).let { arr ->
                    (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        SkillMarket(obj.getString("id"), obj.getString("name"),
                            obj.optString("description"), obj.optString("url"), "github")
                    }
                }
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        return listOf(
            SkillMarket("built-in", "内置", "Codex 内置 Skills", type = "builtin"),
            SkillMarket("android-local", "本地安装", "从本地文件系统发现的插件", type = "local"),
        ) + custom
    }

    suspend fun installSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val installed = getInstalledNames().toMutableList()
            if (name !in installed) {
                installed.add(name)
                saveInstalledNames(installed)
                Log.i(TAG, "Skill 已安装: $name")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装 Skill 失败: $name", e)
            false
        }
    }

    suspend fun removeSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val installed = getInstalledNames().toMutableList()
            installed.remove(name)
            saveInstalledNames(installed)
            val localDir = File(skillsBaseDir, name)
            if (localDir.exists()) localDir.deleteRecursively()
            Log.i(TAG, "Skill 已卸载: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "卸载 Skill 失败: $name", e)
            false
        }
    }

    suspend fun addMarket(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val markets = getMarketsJson().toMutableList()
            val marketId = "custom_${markets.size}"
            markets.add(JSONObject().apply { put("id", marketId); put("url", url) })
            saveMarkets(markets)
            Log.i(TAG, "市场已添加: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加市场失败: $url", e)
            false
        }
    }

    /**
     * 生成本地 Skill 脚手架 (类似 claude plugin init)
     */
    suspend fun scaffoldSkill(
        name: String, title: String, description: String, category: String = "general"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(skillsBaseDir, name)
            if (dir.exists()) { Log.w(TAG, "Skill 目录已存在: $name"); return@withContext false }
            dir.mkdirs()
            File(dir, "SKILL.md").writeText("""# $name

**Title:** $title
**Description:** $description
**Version:** 0.0.1
**Category:** $category
**Author:** 

## Overview

Describe what this skill does.

## Usage

Explain how to use this skill.
""")
            val installed = getInstalledNames().toMutableList()
            if (name !in installed) { installed.add(name); saveInstalledNames(installed) }
            Log.i(TAG, "Skill 脚手架已创建: $dir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建 Skill 失败", e)
            false
        }
    }

    private fun getInstalledNames(): List<String> {
        val json = prefs.getString(KEY_INSTALLED, "[]") ?: "[]"
        return try { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }
        catch (e: Exception) { emptyList() }
    }

    private fun getInstalledFromPrefs(): List<SkillInfo> {
        return getInstalledNames().map { name ->
            SkillInfo(name = name, title = name, description = "", version = "0.0.1", market = "built-in", installed = true, isBuiltin = true)
        }
    }

    private fun saveInstalledNames(names: List<String>) {
        prefs.edit().putString(KEY_INSTALLED, JSONArray(names).toString()).apply()
    }

    private fun getMarketsJson(): MutableList<JSONObject> {
        val json = prefs.getString(KEY_MARKETS, "[]") ?: "[]"
        return try { JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } }
        catch (e: Exception) { mutableListOf() }
    }

    private fun saveMarkets(markets: List<JSONObject>) {
        prefs.edit().putString(KEY_MARKETS, JSONArray(markets).toString()).apply()
    }
}
