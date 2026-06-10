package com.codex.android.policy

object ToolPolicy {
    fun needsRoot(command: String): Boolean {
        val c = command.lowercase()
        return listOf(
            "iptables",
            "ip rule",
            "setprop",
            "getprop",
            "pm ",
            "am ",
            "mount",
            "umount",
            "sysctl",
            "getenforce",
            "setenforce"
        ).any { c.contains(it) }
    }

    fun allowLinuxScript(command: String): Boolean {
        return command.length < 50_000
    }
}
