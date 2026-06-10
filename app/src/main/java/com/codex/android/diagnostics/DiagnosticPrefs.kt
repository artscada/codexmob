package com.codex.android.diagnostics

import android.content.Context
import android.content.SharedPreferences

/**
 * 诊断功能偏好设置。
 * 存储 GitHub Token 和上报配置。
 */
class DiagnosticPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("codex_diagnostics", Context.MODE_PRIVATE)

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value).apply()

    var repoOwner: String
        get() = prefs.getString("repo_owner", "weige369") ?: "weige369"
        set(value) = prefs.edit().putString("repo_owner", value).apply()

    var repoName: String
        get() = prefs.getString("repo_name", "codex-android") ?: "codex-android"
        set(value) = prefs.edit().putString("repo_name", value).apply()

    var autoUpload: Boolean
        get() = prefs.getBoolean("auto_upload", false)
        set(value) = prefs.edit().putBoolean("auto_upload", value).apply()

    var uploadOnCrash: Boolean
        get() = prefs.getBoolean("upload_on_crash", false)
        set(value) = prefs.edit().putBoolean("upload_on_crash", value).apply()

    var gistMode: Boolean
        get() = prefs.getBoolean("gist_mode", true)
        set(value) = prefs.edit().putBoolean("gist_mode", value).apply()

    val isConfigured: Boolean get() = githubToken.isNotBlank()
}
