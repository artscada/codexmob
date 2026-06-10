package com.codex.android.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 安全等级策略。
 *
 * 用户可在设置里选择 AI 工具的权限等级，控制高权限操作（任意 Shell 执行、文件系统访问）。
 * 等级存储在 SharedPreferences("codex_prefs") 的 "security_level" 键。
 *
 * 等级按能力从严到宽递增（保证每个等级的安全承诺真实可信）：
 * - SAFE  (安全)  : 禁用任意 Shell 工具，文件读写仅限应用工作目录（沙箱内）。最严格。
 * - STANDARD (标准): 禁用任意 Shell 工具，但允许全盘文件读写。默认等级。
 * - SHELL_ONLY (仅 Shell): 允许任意 Shell，但文件工具仍限制在沙箱目录。
 * - FULL  (完全)  : 无限制，AI 可执行任意 Shell、访问全盘文件。面向开发者/高级用户。
 *
 * 说明：文件沙箱约束的是“文件工具”的访问范围；一旦放开 Shell（仅 Shell / 完全），
 * AI 仍然可以通过 `cat`/`cp` 等命令绕过文件沙箱，所以这类等级下只对文件工具保留沙箱限制，
 * 不再把“沙箱保护”误说成对 Shell 也同样生效。
 */
object SecurityPolicy {

    const val PREFS_NAME = "codex_prefs"
    const val KEY_SECURITY_LEVEL = "security_level"

    const val LEVEL_FULL = "full"
    const val LEVEL_STANDARD = "standard"
    const val LEVEL_SAFE = "safe"
    const val LEVEL_SHELL_ONLY = "shell_only"

    const val DEFAULT_LEVEL = LEVEL_STANDARD

    /** 读取当前安全等级。 */
    fun currentLevel(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SECURITY_LEVEL, DEFAULT_LEVEL) ?: DEFAULT_LEVEL
    }

    /** 是否允许执行任意 Shell 命令（SHELL_ONLY / FULL 允许）。 */
    fun isShellAllowed(context: Context): Boolean {
        val level = currentLevel(context)
        return level == LEVEL_SHELL_ONLY || level == LEVEL_FULL
    }

    /** 是否允许访问全盘文件（STANDARD / FULL 允许；SAFE 与 SHELL_ONLY 限制在沙箱目录）。 */
    fun isFullFileAccessAllowed(context: Context): Boolean {
        val level = currentLevel(context)
        return level == LEVEL_STANDARD || level == LEVEL_FULL
    }

    /**
     * 沙箱根目录：应用私有外部目录（无则回退到内部 filesDir）。
     * SAFE / SHELL_ONLY 等级下，文件工具只能访问该目录子树。
     */
    fun sandboxRoot(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    /**
     * 校验文件访问是否被当前安全等级允许。
     *
     * @return 允许时返回 null；被拒绝时返回一段 JSON 错误字符串，调用方可直接返回给 AI。
     */
    fun checkFileAccess(context: Context, path: String): String? {
        if (isFullFileAccessAllowed(context)) return null

        val root = try {
            sandboxRoot(context).canonicalFile
        } catch (e: Exception) {
            sandboxRoot(context).absoluteFile
        }
        val target = try {
            File(path).canonicalFile
        } catch (e: Exception) {
            File(path).absoluteFile
        }

        val rootPath = root.path
        val targetPath = target.path
        val withinSandbox = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)

        if (!withinSandbox) {
            return "{\"success\":false,\"error\":\"当前安全等级（${currentLevel(context)}）禁止访问沙箱外路径：$path。可在设置中调整安全等级，或将文件放入工作目录：$rootPath\"}"
        }
        return null
    }

    /** Shell 被拒绝时的标准错误响应。 */
    fun shellDeniedResponse(context: Context): String {
        return "{\"success\":false,\"error\":\"当前安全等级（${currentLevel(context)}）禁止执行任意 Shell 命令。如需执行，请在设置中将安全等级调整为「仅 Shell」或「完全」。\"}"
    }

    /**
     * 危险命令规则：(正则, 中文原因)。
     *
     * 命中任意一条即视为「危险命令」，需要用户显式确认后才执行。
     * 正则在去掉多余空白并转为小写的命令字符串上匹配。
     */
    private val DANGEROUS_RULES: List<Pair<Regex, String>> = listOf(
        // rm -rf / rm -r / rm -f / rm --recursive --force（递归或强制删除）
        Regex("(^|[;&|`(]|\\s)rm\\s+(-{1,2}[a-z-]*\\s+)*-{1,2}[a-z-]*(r|f|recursive|force)")
            to "递归或强制删除文件（rm）",
        // dd —— 磁盘级写入，可能覆盖整块设备
        Regex("(^|[;&|`(]|\\s)dd\\s+")
            to "磁盘级写入（dd）可能覆盖数据",
        // mkfs / mkswap —— 格式化文件系统
        Regex("(^|[;&|`(]|\\s)(mkfs|mkswap)")
            to "格式化文件系统",
        // chmod -R / chown -R —— 递归修改权限或属主
        Regex("(^|[;&|`(]|\\s)(chmod|chown)\\s+(-{1,2}[a-z-]*\\s+)*-{1,2}[a-z-]*(r|recursive)")
            to "递归修改权限/属主",
        // 向系统关键路径重定向写入
        Regex(">\\s*/(system|dev|proc|sys|vendor|boot|etc)\\b")
            to "向系统路径写入（重定向）",
        // shred / wipe —— 数据擦除
        Regex("(^|[;&|`(]|\\s)(shred|wipe)\\b")
            to "数据擦除（shred/wipe）",
        // fork 炸弹 :(){ :|:& };:
        Regex(":\\s*\\(\\s*\\)\\s*\\{")
            to "疑似 fork 炸弹",
        // 重启 / 关机
        Regex("(^|[;&|`(]|\\s)(reboot|shutdown|halt|poweroff)\\b")
            to "重启或关机",
        // 卸载或清除应用数据
        Regex("(^|[;&|`(]|\\s)pm\\s+(uninstall|clear)\\b")
            to "卸载或清除应用数据（pm）",
        // 设备擦除 / 刷机
        Regex("(^|[;&|`(]|\\s)fastboot\\b.*(wipe|erase|format)")
            to "设备擦除（fastboot）",
    )

    /**
     * 判断命令是否危险。
     *
     * @return 危险时返回中文原因（用于确认弹窗），安全时返回 null。
     */
    fun dangerousCommandReason(command: String?): String? {
        if (command.isNullOrBlank()) return null
        val normalized = command.replace(Regex("\\s+"), " ").trim().lowercase()
        if (normalized.isEmpty()) return null
        for ((regex, reason) in DANGEROUS_RULES) {
            if (regex.containsMatchIn(normalized)) return reason
        }
        return null
    }

    /** 用户拒绝执行危险命令时的标准响应。 */
    fun shellRejectedByUserResponse(command: String, reason: String): String {
        return JSONObject().apply {
            put("success", false)
            put("error", "用户已拒绝执行该命令（$reason）。命令未运行。")
            put("command", command)
            put("reason", reason)
            put("rejected", true)
        }.toString()
    }
}
