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

    override suspend fun click(x: Int, y: Int): AndroidShellExecutor.ShellResult {
        return run("input tap $x $y")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): AndroidShellExecutor.ShellResult {
        return run("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    override suspend fun inputText(text: String): AndroidShellExecutor.ShellResult {
        // Escape characters for 'input text' command.
        // On modern Android, spaces can be replaced by %s or wrapped in quotes.
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        return run("input text '$escaped'")
    }

    override suspend fun takeScreenshot(outputPath: String): AndroidShellExecutor.ShellResult {
        return run("screencap -p ${shellQuote(outputPath)}")
    }

    override suspend fun dumpWindowHierarchy(outputPath: String): AndroidShellExecutor.ShellResult {
        return run("uiautomator dump ${shellQuote(outputPath)}")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
