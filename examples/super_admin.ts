/*
METADATA
{
    "name": "super_admin",
    "display_name": {
        "zh": "超级管理员",
        "en": "Super Admin"
    },
    "description": {
        "zh": "超级管理员工具集：shell 通过 Shizuku/Root 直接执行 Android 系统命令，适合底层系统管理。Ubuntu 终端已内置到应用（terminal 等工具），不在本包里。",
        "en": "Super admin toolkit: shell runs Android system commands directly via Shizuku/Root, for low-level system administration. The Ubuntu terminal is built into the app (terminal and related tools) and is not part of this package."
    },
    "enabledByDefault": true,
    "category": "System",
    "tools": [
        {
            "name": "shell",
            "description": {
                "zh": "在Android执行独立Shell任务，严格使用用户当前权限（标准/Shizuku/Root）。默认等待10秒，未结束返回running和runId，用shell_wait查询、shell_cancel取消；timeoutMs默认2分钟，限制执行总时长。output是合并正常与错误输出的尾部快照，不要重复拼接。只有completed且exitCode=0才成功；failed/timed_out/cancelled表示失败/超时/取消，cancelling表示仍在清理。新消息可提前结束本次等待，任务继续。每次都是独立环境，不保留cwd/变量，无交互输入。不要用&放后台，改用yieldMs=0；结束会清理本任务进程组。主动脱离进程组的守护进程不受管理。",
                "en": "Run an isolated Android Shell task using the selected STANDARD/Shizuku/Root permission. Default wait 10s; running and runId mean poll with shell_wait or cancel with shell_cancel. timeoutMs defaults to 2 minutes and limits total execution. output is a combined stdout/stderr tail snapshot; do not concatenate snapshots. Only completed with exitCode=0 means success. failed/timed_out/cancelled indicate failure/timeout/cancellation; cancelling means cleanup is pending. New input may end a wait while execution continues. No persistent cwd/variables or interactive stdin. Use yieldMs=0 instead of &. The owned process group is cleaned up on completion; deliberately detached daemons are not managed."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的 Android Shell 命令，不是 Ubuntu 命令。",
                        "en": "Android Shell command, not an Ubuntu command."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "timeoutMs",
                    "description": { "zh": "执行总上限，1000～1800000毫秒，默认120000。超时终止任务进程组。", "en": "Execution limit, 1000..1800000 ms; default 120000. Timeout terminates the task process group." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "yieldMs",
                    "description": { "zh": "本次等待0～30000毫秒，默认10000；0立即返回任务编号。", "en": "Wait 0..30000 ms, default 10000; 0 immediately returns a task ID." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "shell_wait",
            "description": { "zh": "等待或查询shell返回的runId，仅结束本次等待，不重新执行命令、不取消任务。", "en": "Wait for or inspect a shell runId. Ending the wait neither reruns nor cancels the command." },
            "parameters": [
                { "name": "runId", "description": { "zh": "shell返回的任务编号。", "en": "Task ID returned by shell." }, "type": "string", "required": true },
                { "name": "yieldMs", "description": { "zh": "等待0～30000毫秒，默认10000。", "en": "Wait 0..30000 ms, default 10000." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "shell_cancel",
            "description": { "zh": "取消本对话指定Shell任务并清理其进程组。若仍为cancelling，用shell_wait确认结果。", "en": "Cancel this chat's Shell task and clean up its process group. If still cancelling, use shell_wait to confirm." },
            "parameters": [
                { "name": "runId", "description": { "zh": "shell返回的任务编号。", "en": "Task ID returned by shell." }, "type": "string", "required": true }
            ]
        }
    ]
}
*/

const superAdmin = (function () {
    function waitMs(value: string | undefined, fallback = 10000): number {
        if (value === undefined) return fallback;
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 30000) {
            throw new Error("yieldMs must be an integer between 0 and 30000");
        }
        return parsed;
    }

    /**
     * 通过Shizuku/Root权限在Android系统中执行Shell命令
     * 运行环境：直接访问Android系统，具有系统级权限
     * @param command - 要执行的Shell命令
     */
    async function shell(params: { command: string, timeoutMs?: string, yieldMs?: string }): Promise<any> {
        if (!params.command?.trim()) throw new Error("command is required");
        const timeoutMs = params.timeoutMs === undefined ? 120000 : Number(params.timeoutMs);
        if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 1800000) {
            throw new Error("timeoutMs must be an integer between 1000 and 1800000");
        }
        return Tools.System.shellTask.start(params.command, { timeoutMs, yieldMs: waitMs(params.yieldMs) });
    }

    async function shell_wait(params: { runId: string, yieldMs?: string }): Promise<any> {
        if (!params.runId?.trim()) throw new Error("runId is required");
        if (Object.prototype.hasOwnProperty.call(params, "command"))
            throw new Error("shell_wait does not execute commands; use shell.");
        return Tools.System.shellTask.poll(params.runId, waitMs(params.yieldMs));
    }

    async function shell_cancel(params: { runId: string }): Promise<any> {
        if (!params.runId?.trim()) throw new Error("runId is required");
        return Tools.System.shellTask.cancel(params.runId);
    }

    return {
        shell,
        shell_wait,
        shell_cancel
    };
})();

// 逐个导出
exports.shell = superAdmin.shell;
exports.shell_wait = superAdmin.shell_wait;
exports.shell_cancel = superAdmin.shell_cancel;
