/*
METADATA
{
    "name": "super_admin",

    "display_name": {
        "zh": "超级管理员",
        "en": "Super Admin"
    },
    "description": { "zh": "超级管理员工具集，提供终端命令和Shell操作的高级功能。terminal工具运行在Ubuntu环境中（已正确挂载sdcard和storage），shell工具通过Shizuku/Root直接执行Android系统命令。适合需要进行底层系统管理和命令行操作的场景。", "en": "Super admin toolkit providing advanced terminal and shell capabilities. The terminal tool runs in an Ubuntu environment (with sdcard/storage mounted). The shell tool executes Android system commands directly via Shizuku/Root. Useful for low-level system administration and CLI operations." },
    "enabledByDefault": true,
    "category": "System",
    "tools": [
        {
            "name": "terminal",
            "description": { "zh": "在Ubuntu环境中执行命令并收集输出结果，可访问Android存储空间。正常情况下复用当前对话的终端会话。建议显式传 timeoutMs；前台默认15秒，background=true 时不使用该默认超时。排队超时只取消本次调用；执行中超时先尝试中断，无法恢复则关闭故障会话，后续调用新建会话。会话重建后原工作目录和变量不再保留，命令不会自动重试。context_preserved=true 仅表示本次调用复用了已有会话且未超时，不保证跨故障保留上下文。避免使用 set -e 或 set -o errexit，以免改变会话退出行为。", "en": "Execute commands in Ubuntu with access to Android storage. Normally reuses the current chat's terminal session. Specify timeoutMs; foreground defaults to 15s, while background=true does not use this default. A queued timeout cancels only that call. A running timeout first attempts interruption; if recovery fails, the faulty session is closed and a later call creates a new one. A new session does not preserve the previous working directory or variables. Commands are not retried automatically. context_preserved=true only means this call reused an existing session without timing out; it does not guarantee context across failures. Avoid set -e or set -o errexit, which change shell exit behavior." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的 Ubuntu 命令；切换目录请在命令中使用 cd。本工具不接受 environment/path/timeout 参数，超时参数名为 timeoutMs。", "en": "Ubuntu command to execute; use cd inside the command to change directory. environment/path/timeout are unsupported; the timeout parameter is timeoutMs." },
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
            "description": { "zh": "通过向同一终端会话排入检测命令，等待前序命令完成。等待超时会取消本次检测；若仍在排队，不会中断前序命令。若检测已执行但无法中断，故障会话可能被关闭，不保证保留会话或上下文。", "en": "Wait for preceding commands by queueing a probe in the same terminal session. A timeout cancels this probe. If it is still queued, preceding commands are not interrupted. If an already running probe cannot be interrupted, the faulty session may be closed; session and context preservation are not guaranteed." },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": { "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。", "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session." },
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
            "description": { "zh": "获取目标终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。", "en": "Get the visible screen content for the target terminal session (single screen only, no scrollback history). Pass sessionId to target a session; if omitted, uses the current chat's default session." },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": { "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。", "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_input",
            "description": { "zh": "向目标终端会话写入输入。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。input 与 control 至少传一个。常见用法：先写 input，再写 control=enter 提交；control=ctrl 且 input=c 可发送 Ctrl+C。", "en": "Write input to the target terminal session. Pass sessionId to target a session; if omitted, uses the current chat's default session. Provide at least one of input or control. Typical usage: send input first, then control=enter to submit; use control=ctrl with input=c for Ctrl+C." },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": { "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。", "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session." },
                    "type": "string",
                    "required": false
                },
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
            "name": "shell",
            "description": { "zh": "通过Shizuku/Root权限直接在Android系统中执行Shell命令。运行环境：直接访问Android系统，具有系统级权限，适用于需要操作Android系统底层的场景（如pm、am等系统命令）。", "en": "Execute shell commands directly on Android with Shizuku/Root. Environment: direct Android system access with system-level privileges, suitable for low-level commands such as pm/am." },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "要执行的 Android Shell 命令。本工具仅接受 command，不接受 timeoutMs；不要与 Ubuntu terminal 的参数混用。", "en": "Android Shell command to execute. This tool accepts only command, not timeoutMs; do not reuse Ubuntu terminal parameters." },
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
    const DEFAULT_TERMINAL_SESSION_NAME = "super_admin_default_session";
    const BACKGROUND_TERMINAL_SESSION_PREFIX = "super_admin_background";
    function getCurrentChatSessionSuffix() {
        const chatId = getChatId();
        if (chatId === undefined) {
            return "";
        }
        const normalizedChatId = chatId.trim();
        if (!normalizedChatId) {
            return "";
        }
        return normalizedChatId.replace(/[^a-zA-Z0-9._-]+/g, "_");
    }
    function getDefaultTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        return chatSuffix
            ? `${DEFAULT_TERMINAL_SESSION_NAME}_${chatSuffix}`
            : DEFAULT_TERMINAL_SESSION_NAME;
    }
    function getBackgroundTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        const prefix = chatSuffix
            ? `${BACKGROUND_TERMINAL_SESSION_PREFIX}_${chatSuffix}`
            : BACKGROUND_TERMINAL_SESSION_PREFIX;
        return `${prefix}_${Date.now()}`;
    }
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
            timedOut: result?.timedOut === true,
            context_preserved: result?.timedOut !== true,
            output_saved_to: filePath,
            output_chars: outputStr.length,
            operit_clean_on_exit_dir: OPERIT_CLEAN_ON_EXIT_DIR,
            hint: "Output is large and saved to file. Use read_file_part or grep_code to inspect it.",
        };
    }
    /**
     * 在Ubuntu环境中执行终端命令并收集输出结果
     * 运行环境：完整的Ubuntu系统，已正确挂载sdcard和storage目录
     * 避免使用 set -e / set -o errexit，以免改变会话退出行为。
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
            console.log(`执行终端命令: ${command}`);
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
                const session = await Tools.System.terminal.create(getBackgroundTerminalSessionName());
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
            const session = await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            // 调用系统工具执行终端命令
            const result = await Tools.System.terminal.exec(sessionId, command, timeout);
            const timedOut = result.timedOut === true;
            const persistedResult = await persistTerminalOutputIfTooLong(command, result);
            if (persistedResult) {
                persistedResult.timeoutMsUsed = timeout;
                persistedResult.context_preserved = !session.isNewSession && !timedOut;
                return persistedResult;
            }
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode,
                sessionId: result.sessionId,
                timedOut: timedOut,
                timeoutMsUsed: timeout,
                context_preserved: !session.isNewSession && !timedOut
            };
        }
        catch (error) {
            console.error(`[terminal] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    async function bash(params) {
        return terminal(params);
    }
    /**
     * 等待同一终端会话中的上一条命令执行完成
     * 原理：向同会话追加一个内部 marker 命令。由于会话按序执行，marker 开始执行即代表前序命令已完成。
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
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
                ? { sessionId: params.sessionId, isNewSession: false }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const marker = `__OPERIT_TERMINAL_WAIT_DONE_${Date.now()}_${Math.floor(Math.random() * 1000000)}__`;
            const waitCommand = `printf '${marker}\\n'`;
            const startedAt = Date.now();
            const result = await Tools.System.terminal.exec(sessionId, waitCommand, timeout);
            const elapsedMs = Date.now() - startedAt;
            const timedOut = result?.timedOut === true;
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
                context_preserved: !session.isNewSession && !timedOut
            };
        }
        catch (error) {
            console.error(`[terminal_wait] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 通过Shizuku/Root权限在Android系统中执行Shell命令
     * 运行环境：直接访问Android系统，具有系统级权限
     * @param command - 要执行的Shell命令
     */
    async function shell(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            console.log(`执行Shell命令: ${command}`);
            // 通过Shizuku/Root权限执行shell操作
            const result = await Tools.System.shell(`${command}`);
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode
            };
        }
        catch (error) {
            console.error(`[shell] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 获取目标终端会话可见屏幕内容（仅一屏，不包含历史）
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     */
    async function terminal_getscreen(params = {}) {
        try {
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.screen(sessionId);
            return {
                sessionId: result.sessionId ?? sessionId,
                rows: result.rows,
                cols: result.cols,
                content: result.content
            };
        }
        catch (error) {
            console.error(`[terminal_getscreen] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 向目标终端会话写入输入
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     * @param input - 文本输入
     * @param control - 控制键
     */
    async function terminal_input(params = {}) {
        try {
            if (params.input === undefined && params.control === undefined) {
                throw new Error("input和control至少需要提供一个");
            }
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.input(sessionId, {
                input: params.input,
                control: params.control
            });
            return {
                sessionId,
                input: params.input,
                control: params.control,
                result
            };
        }
        catch (error) {
            console.error(`[terminal_input] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    return {
        terminal,
        bash,
        terminal_wait,
        terminal_getscreen,
        terminal_input,
        shell
    };
})();
// 逐个导出
exports.terminal = superAdmin.terminal;
exports.bash = superAdmin.bash;
exports.terminal_wait = superAdmin.terminal_wait;
exports.terminal_getscreen = superAdmin.terminal_getscreen;
exports.terminal_input = superAdmin.terminal_input;
exports.shell = superAdmin.shell;
