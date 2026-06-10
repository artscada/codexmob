/*
METADATA
{
    "name": "super_admin",

    "display_name": {
        "zh": "超级管理员",
        "en": "Super Admin"
    },
    "description": { "zh": "超级管理员工具集，提供 Linux runtime 与 Android root bridge 的明确分层。linux_run 运行在 Ubuntu/chroot 环境中，root_run 通过 RootBridge 直接执行 Android 系统命令。适合需要同时管理 Linux userspace 和 Android 系统底层的场景。", "en": "Super admin toolkit with a clear split between Linux runtime and Android root bridge. linux_run executes inside the Ubuntu/chroot environment, while root_run executes Android system commands through RootBridge. Useful when both Linux userspace and Android system management are required." },
    "enabledByDefault": true,
    "category": "System",
    "tools": [
        {
            "name": "linux_run",
            "description": { "zh": "在 Linux runtime 中执行命令并收集输出结果。", "en": "Execute a command in the Linux runtime and collect output." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的命令", "en": "Command to execute." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "background",
                    "description": { "zh": "是否在后台运行命令", "en": "Run command in background." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": { "zh": "可选超时（毫秒）", "en": "Optional timeout in milliseconds." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal",
            "description": { "zh": "在 Linux runtime 中执行命令并收集输出结果。当前目标运行环境是 Ubuntu/chroot，fallback 仍可保留 proot。运行时会维护会话上下文，适合 codex exec、bash、git、python、脚本生成与工作区操作。强烈建议每次显式传 timeoutMs，避免命令卡住。若未传，前台默认15秒超时；background=true 时不使用该默认超时。命令超时时不会被自动取消，不需要重新执行命令，请继续通过 terminal_getscreen 跟踪当前屏幕内容。", "en": "Execute commands in the Linux runtime and collect output. The current target runtime is Ubuntu/chroot, with proot kept as a fallback. The runtime preserves session context and is intended for codex exec, bash, git, python, script generation, and workspace operations. Strongly recommend explicitly passing timeoutMs every time to avoid hangs. If omitted, foreground mode defaults to 15s timeout; background=true does not use this default timeout. When a command times out, it is not automatically cancelled. Do not rerun the command; continue tracking the current screen via terminal_getscreen." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的命令", "en": "Command to execute." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "background",
                    "description": { "zh": "是否在后台运行命令,\"true\" 表示后台执行并立即返回,适合启动服务器等长时间运行的任务（AI 不会收到该命令的输出结果），\"false\" 或未提供则前台执行并等待并返回命令结果", "en": "Run command in background. 'true' runs in background and returns immediately (good for long-running tasks like servers; AI will not receive output). 'false' or omitted runs in foreground and returns the command result." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": { "zh": "可选超时（毫秒，最低3000ms）。强烈建议显式传入；未传时前台默认15000ms，background=true时不使用默认超时。", "en": "Optional timeout (ms, minimum 3000ms). Strongly recommended to pass explicitly; if omitted, foreground defaults to 15000ms, and background=true does not use the default timeout." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_wait",
            "description": { "zh": "等待同一终端会话中的上一条命令执行完成。适用于安装/编译等长命令在 timeout 后继续后台执行的场景。与 sleep 不同，本工具会在命令实际完成时提前返回，而不是固定睡眠。", "en": "Wait until the previous command in the same terminal session finishes. Useful when long install/build commands continue running after a timeout. Unlike sleep, this tool can return early as soon as the command actually completes." },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": { "zh": "可选目标会话ID。不传则使用默认会话 super_admin_default_session。", "en": "Optional target session ID. If omitted, uses default session super_admin_default_session." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": { "zh": "可选超时（毫秒，最低3000ms）。未传时默认300000ms（5分钟）。", "en": "Optional timeout (ms, minimum 3000ms). Defaults to 300000ms (5 minutes) if omitted." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_getscreen",
            "description": { "zh": "获取当前终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）。", "en": "Get the current visible screen content for the active terminal session (single screen only, no scrollback history)." },
            "parameters": []
        },
        {
            "name": "terminal_input",
            "description": { "zh": "向当前终端会话写入输入。input 与 control 至少传一个。常见用法：先写 input，再写 control=enter 提交；control=ctrl 且 input=c 可发送 Ctrl+C。", "en": "Write input to the active terminal session. Provide at least one of input or control. Typical usage: send input first, then control=enter to submit; use control=ctrl with input=c for Ctrl+C." },
            "parameters": [
                {
                    "name": "input",
                    "description": { "zh": "写入终端的文本", "en": "Text to write to terminal." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "control",
                    "description": { "zh": "控制键，例如 enter / tab / esc / ctrl", "en": "Control key, e.g. enter / tab / esc / ctrl." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "root_run",
            "description": { "zh": "通过 RootBridge 在 Android 主机上执行系统命令。", "en": "Execute a system command on Android through RootBridge." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的 root 命令", "en": "Root command to execute." },
                    "type": "string",
                    "required": true
                }
            ]
        },
        {
            "name": "shell",
            "description": { "zh": "兼容别名：通过 RootBridge 在 Android 主机上执行系统命令。这个工具只做单次、明确的 root 操作，并把结果返回给 Codex；适合 pm、am、getprop、iptables 等系统级命令。", "en": "Compatibility alias: execute system commands on Android through RootBridge. This tool performs one explicit root operation and returns the result back to Codex; suitable for system-level commands such as pm, am, getprop, and iptables." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的Shell命令", "en": "Shell command to execute." },
                    "type": "string",
                    "required": true
                }
            ]
        }
    ]
}*/
const superAdmin = (function () {
    const MAX_INLINE_TERMINAL_OUTPUT_CHARS = 12000;
    const DEFAULT_FOREGROUND_TIMEOUT_MS = 15000;
    const DEFAULT_WAIT_TIMEOUT_MS = 300000;
    const MIN_TIMEOUT_MS = 3000;
    async function persistTerminalOutputIfTooLong(command, result) {
        const outputStr = typeof result?.output === "string"
            ? result.output
            : String(result?.output ?? "");
        if (outputStr.length <= MAX_INLINE_TERMINAL_OUTPUT_CHARS) {
            return null;
        }
        await Tools.Files.mkdir(OPERIT_CLEAN_ON_EXIT_DIR, true);
        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        const rand = Math.floor(Math.random() * 1000000);
        const filePath = `${OPERIT_CLEAN_ON_EXIT_DIR}/terminal_output_${timestamp}_${rand}.log`;
        await Tools.Files.write(filePath, outputStr, false);
        return {
            command,
            output: "(saved_to_file)",
            exitCode: result?.exitCode,
            sessionId: result?.sessionId,
            context_preserved: true,
            output_saved_to: filePath,
            output_chars: outputStr.length,
            operit_clean_on_exit_dir: OPERIT_CLEAN_ON_EXIT_DIR,
            hint: "Output is large and saved to file. Use read_file_part or grep_code to inspect it.",
        };
    }
    /**
     * 在 Linux runtime 中执行命令并收集输出结果。
     * 当前目标环境是 Ubuntu/chroot，fallback 仍可保留 proot。
     * @param command - 要执行的命令
     * @param background - 是否后台运行（"true" 为后台执行并立即返回，适合启动服务器等长时间运行任务，AI 不会收到该命令的输出结果）
     * @param timeoutMs - 可选的超时时间（毫秒，最低 3000ms）。强烈建议显式传入；前台未传时默认 15000ms，后台模式不应用该默认值。
     */
    async function terminal(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            const background = params.background;
            const timeoutMs = params.timeoutMs;
            console.log(`执行 Linux 命令: ${command}`);
            const isBackground = background === "true";
            let timeout;
            if (!isBackground) {
                if (timeoutMs !== undefined) {
                    const parsedTimeout = parseInt(timeoutMs, 10);
                    if (!Number.isFinite(parsedTimeout) || parsedTimeout < MIN_TIMEOUT_MS) {
                        throw new Error(`timeoutMs必须是整数且不少于${MIN_TIMEOUT_MS}毫秒`);
                    }
                    timeout = parsedTimeout;
                }
                else {
                    timeout = DEFAULT_FOREGROUND_TIMEOUT_MS;
                }
            }
            if (isBackground) {
                const session = await Tools.System.terminal.create(`linux_run_background_${Date.now()}`);
                const sessionId = session.sessionId;
                // 调用系统工具执行终端命令
                (async () => {
                    try {
                        await Tools.System.terminal.exec(sessionId, command);
                    }
                    catch (error) {
                        console.error(`[terminal/background] 错误: ${error.message}`);
                        console.error(error.stack);
                    }
                })();
                return {
                    command: command,
                    background: true,
                    sessionId: sessionId,
                    started: true
                };
            }
            // 创建或获取一个默认会话
            const session = await Tools.System.terminal.create("linux_run_default_session");
            const sessionId = session.sessionId;
            // 调用系统工具执行终端命令
            const result = await Tools.System.terminal.exec(sessionId, command, timeout);
            const timedOut = result.timedOut === true;
            let timeoutScreen = null;
            if (timedOut) {
                const screenResult = await Tools.System.terminal.screen(sessionId);
                timeoutScreen = {
                    sessionId: screenResult.sessionId ?? sessionId,
                    rows: screenResult.rows,
                    cols: screenResult.cols,
                    content: screenResult.content
                };
            }
            const persistedResult = await persistTerminalOutputIfTooLong(command, result);
            if (persistedResult) {
                persistedResult.timeoutMsUsed = timeout;
                if (timeoutScreen) {
                    persistedResult.timeoutScreen = timeoutScreen;
                }
                return persistedResult;
            }
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode,
                sessionId: result.sessionId,
                timedOut: timedOut,
                timeoutMsUsed: timeout,
                timeoutScreen: timeoutScreen,
                context_preserved: true // 标记此命令保留了目录上下文
            };
        }
        catch (error) {
            console.error(`[terminal] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 等待同一终端会话中的上一条命令执行完成
     * 原理：向同会话追加一个内部 marker 命令。由于会话按序执行，marker 开始执行即代表前序命令已完成。
     * @param sessionId - 可选会话ID；不传时使用 super_admin_default_session
     * @param timeoutMs - 可选超时（毫秒，最低 3000ms）；未传默认 300000ms
     */
    async function terminal_wait(params = {}) {
        try {
            const timeoutMs = params.timeoutMs;
            let timeout = DEFAULT_WAIT_TIMEOUT_MS;
            if (timeoutMs !== undefined) {
                const parsedTimeout = parseInt(timeoutMs, 10);
                if (!Number.isFinite(parsedTimeout) || parsedTimeout < MIN_TIMEOUT_MS) {
                    throw new Error(`timeoutMs必须是整数且不少于${MIN_TIMEOUT_MS}毫秒`);
                }
                timeout = parsedTimeout;
            }
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create("super_admin_default_session");
            const sessionId = session.sessionId;
            const marker = `__OPERIT_TERMINAL_WAIT_DONE_${Date.now()}_${Math.floor(Math.random() * 1000000)}__`;
            const waitCommand = `printf '${marker}\\n'`;
            const startedAt = Date.now();
            const result = await Tools.System.terminal.exec(sessionId, waitCommand, timeout);
            const elapsedMs = Date.now() - startedAt;
            const timedOut = result?.timedOut === true;
            let timeoutScreen = null;
            if (timedOut) {
                const screenResult = await Tools.System.terminal.screen(sessionId);
                timeoutScreen = {
                    sessionId: screenResult.sessionId ?? sessionId,
                    rows: screenResult.rows,
                    cols: screenResult.cols,
                    content: screenResult.content
                };
            }
            const outputStr = typeof result?.output === "string"
                ? result.output
                : String(result?.output ?? "");
            const markerSeen = outputStr.includes(marker);
            return {
                sessionId,
                timedOut,
                timeoutMsUsed: timeout,
                elapsedMs,
                waitCompleted: !timedOut && markerSeen,
                markerSeen,
                exitCode: result?.exitCode,
                timeoutScreen,
                context_preserved: true
            };
        }
        catch (error) {
            console.error(`[terminal_wait] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 通过 RootBridge 在 Android 系统中执行单次 root 命令
     * @param command - 要执行的 root 命令
     */
    async function executeRootBridgeCommand(command) {
        return await Tools.System.shell(`${command}`);
    }
    async function root_run(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            console.log(`执行 root 命令: ${command}`);
            const result = await executeRootBridgeCommand(command);
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode
            };
        }
        catch (error) {
            console.error(`[root_run] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    async function shell(params) {
        return await root_run(params);
    }
    /**
     * 获取当前终端会话可见屏幕内容（仅一屏，不包含历史）
     */
    async function terminal_getscreen(_params = {}) {
        try {
            const session = await Tools.System.terminal.create("super_admin_default_session");
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.screen(sessionId);
            return {
                sessionId: result.sessionId ?? sessionId,
                rows: result.rows,
                cols: result.cols,
                content: result.content,
                commandRunning: result.commandRunning === true
            };
        }
        catch (error) {
            console.error(`[terminal_getscreen] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 向当前终端会话写入输入
     * @param input - 文本输入
     * @param control - 控制键
     */
    async function terminal_input(params = {}) {
        try {
            if (params.input === undefined && params.control === undefined) {
                throw new Error("input和control至少需要提供一个");
            }
            const session = await Tools.System.terminal.create("super_admin_default_session");
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.input(sessionId, {
                input: params.input,
                control: params.control
            });
            return {
                sessionId: sessionId,
                input: params.input,
                control: params.control,
                result: result?.value ?? String(result ?? "")
            };
        }
        catch (error) {
            console.error(`[terminal_input] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    return {
        linux_run: terminal,
        terminal,
        terminal_wait,
        terminal_getscreen,
        terminal_input,
        root_run,
        shell
    };
})();
// 逐个导出
exports.linux_run = superAdmin.linux_run;
exports.terminal = superAdmin.terminal;
exports.terminal_wait = superAdmin.terminal_wait;
exports.terminal_getscreen = superAdmin.terminal_getscreen;
exports.terminal_input = superAdmin.terminal_input;
exports.root_run = superAdmin.root_run;
exports.shell = superAdmin.shell;
