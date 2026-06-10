package com.codex.android.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 诊断报告上报器。
 *
 * 将诊断报告自动提交到 GitHub Issues，
 * 方便开发阶段追踪所有设备上的问题。
 */
class ReportUploader(private val context: Context) {

    companion object {
        private const val TAG = "ReportUploader"
        private const val GITHUB_API = "https://api.github.com"
    }

    data class UploadResult(
        val success: Boolean,
        val url: String = "",
        val message: String = ""
    )

    /**
     * 上传诊断报告到 GitHub Issues
     *
     * @param report 诊断报告
     * @param githubToken GitHub Personal Access Token
     * @param repoOwner 仓库所有者
     * @param repoName 仓库名称
     */
    suspend fun uploadToGitHubIssues(
        report: DiagnosticsRunner.DiagnosticsReport,
        githubToken: String,
        repoOwner: String = "weige369",
        repoName: String = "codex-android"
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val title = "[诊断报告] ${report.deviceInfo.device} · " +
                    "${report.passedCount}/${report.totalCount} 通过"

            val body = buildString {
                appendLine("## 🤖 自动诊断报告")
                appendLine()
                appendLine("**时间**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(report.timestamp))}")
                appendLine("**设备**: ${report.deviceInfo.device}")
                appendLine("**Android**: ${report.deviceInfo.androidVersion}")
                appendLine("**架构**: ${report.deviceInfo.arch}")
                appendLine()
                appendLine("## 测试结果")
                appendLine()
                appendLine("| 状态 | 测试项 | 详情 |")
                appendLine("|------|--------|------|")
                for (r in report.results) {
                    val icon = if (r.passed) "✅" else "❌"
                    appendLine("| $icon | ${r.name} | ${r.detail} |")
                }
                appendLine()
                appendLine("## 失败项详情")
                appendLine()
                for (r in report.results.filter { !it.passed }) {
                    appendLine("### ❌ ${r.name}")
                    appendLine("- **详情**: ${r.detail}")
                    if (r.suggestion.isNotBlank()) {
                        appendLine("- **建议**: ${r.suggestion}")
                    }
                    appendLine()
                }
                appendLine("---")
                appendLine("_由 Codex Android 诊断系统自动生成_")
            }

            // 创建 GitHub Issue
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("labels", org.json.JSONArray().apply {
                    put("diagnostic")
                    put("auto-report")
                })
            }

            val url = URL("$GITHUB_API/repos/$repoOwner/$repoName/issues")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $githubToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                val responseJson = JSONObject(responseBody)
                val issueUrl = responseJson.getString("html_url")
                UploadResult(true, issueUrl, "Issue 已创建: $issueUrl")
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
                UploadResult(false, message = "创建 Issue 失败 (HTTP $responseCode): $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传失败", e)
            UploadResult(false, message = "上传异常: ${e.message}")
        }
    }

    /**
     * 上传诊断报告为 GitHub Gist (不需要仓库权限)
     */
    suspend fun uploadToGist(
        report: DiagnosticsRunner.DiagnosticsReport,
        githubToken: String
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val content = buildReportMarkdown(report)
            val filename = "codex-diagnostic-${System.currentTimeMillis()}.md"

            val files = JSONObject().apply {
                put(filename, JSONObject().apply {
                    put("content", content)
                })
            }

            val json = JSONObject().apply {
                put("description", "Codex Android 诊断报告 - ${report.deviceInfo.device}")
                put("public", false)
                put("files", files)
            }

            val url = URL("$GITHUB_API/gists")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $githubToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                val responseJson = JSONObject(responseBody)
                val gistUrl = responseJson.getString("html_url")
                UploadResult(true, gistUrl, "Gist 已创建: $gistUrl")
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
                UploadResult(false, message = "创建 Gist 失败: $error")
            }
        } catch (e: Exception) {
            UploadResult(false, message = "上传异常: ${e.message}")
        }
    }

    private fun buildReportMarkdown(report: DiagnosticsRunner.DiagnosticsReport): String {
        return buildString {
            appendLine("# Codex Android 诊断报告")
            appendLine()
            appendLine("**设备**: ${report.deviceInfo.device}")
            appendLine("**Android**: ${report.deviceInfo.androidVersion}")
            appendLine("**架构**: ${report.deviceInfo.arch}")
            appendLine("**内存**: ${report.deviceInfo.memory}")
            appendLine("**状态**: ${report.passedCount}/${report.totalCount} 通过")
            appendLine()
            appendLine("## 结果")
            appendLine()
            appendLine("| 状态 | 测试项 | 详情 |")
            appendLine("|------|--------|------|")
            for (r in report.results) {
                val icon = if (r.passed) "✅" else "❌"
                appendLine("| $icon | ${r.name} | ${r.detail} |")
            }
        }
    }
}

