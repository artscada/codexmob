package com.codex.android.runtime.root

import com.codex.android.util.AndroidShellExecutor

class SuRootBridge : RootBridge {
    override fun isAvailable(): Boolean = AndroidShellExecutor.isRootAvailable()

    override suspend fun run(command: String, timeoutMs: Long): AndroidShellExecutor.ShellResult {
        return AndroidShellExecutor.execute(
            command = command,
            timeoutMs = timeoutMs,
            permissionLevel = AndroidShellExecutor.PermissionLevel.ROOT
        )
    }

    override suspend fun read(path: String): AndroidShellExecutor.ShellResult {
        return run("if [ -d ${shellQuote(path)} ]; then ls -la ${shellQuote(path)}; else cat ${shellQuote(path)}; fi")
    }

    override suspend fun write(path: String, content: String): AndroidShellExecutor.ShellResult {
        return run("cat > ${shellQuote(path)} <<'EOF'\n$content\nEOF")
    }

    override suspend fun list(path: String): AndroidShellExecutor.ShellResult {
        return run("ls -la ${shellQuote(path)}")
    }

    override suspend fun iptablesDump(): AndroidShellExecutor.ShellResult {
        return run("iptables-save 2>/dev/null || iptables -S 2>/dev/null || iptables -L -n -v 2>/dev/null")
    }

    override suspend fun iptablesApply(rules: String): AndroidShellExecutor.ShellResult {
        return run("iptables-restore <<'EOF'\n$rules\nEOF")
    }

    override suspend fun getProp(name: String): AndroidShellExecutor.ShellResult {
        return run("getprop ${shellQuote(name)}")
    }

    override suspend fun setProp(name: String, value: String): AndroidShellExecutor.ShellResult {
        return run("setprop ${shellQuote(name)} ${shellQuote(value)}")
    }

    override suspend fun execPm(command: String): AndroidShellExecutor.ShellResult {
        return run("pm $command")
    }

    override suspend fun execAm(command: String): AndroidShellExecutor.ShellResult {
        return run("am $command")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
