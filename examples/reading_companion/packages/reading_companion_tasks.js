/* METADATA
{
  "name": "reading_companion_tasks",
  "display_name": {
    "zh": "阅读伴侣生成任务：启动摘要或段评、查询进度、取消。",
    "en": "Reading generation tasks: start summaries/commentary, inspect progress, cancel."
  },
  "description": {
    "zh": "阅读伴侣生成任务：启动摘要或段评、查询进度、取消。",
    "en": "Reading generation tasks: start summaries/commentary, inspect progress, cancel."
  },
  "enabledByDefault": true,
  "category": "AI Reading Companion",
  "tools": [
    {
      "name": "start_task",
      "description": {
        "zh": "用户明确要求生成时使用。立即返回 task_id；任务固定书籍，在后台逐章执行。进程退出则中断，不自动重放。",
        "en": "Use only for explicit generation requests. Returns task_id immediately, binds the book and runs chapters asynchronously. Process exit interrupts without automatic replay."
      },
      "parameters": [
        {
          "name": "kind",
          "description": {
            "zh": "summary（摘要）或 commentary（段评）",
            "en": "summary or commentary"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "book_id",
          "description": {
            "zh": "目标书籍 ID；省略时固定当前选书",
            "en": "Target book ID; omit to bind current selection"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "mode",
          "description": {
            "zh": "fill_missing（补缺，默认）或 regenerate（段评重生成，必须提供起止章节，最多 10 章）",
            "en": "fill_missing (default) or regenerate (commentary only; requires both range endpoints, at most 10 chapters)"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "count",
          "description": {
            "zh": "补缺数量：摘要 1～999，段评 1～10；regenerate 按起止范围生成，此值不缩小范围",
            "en": "Fill count: summaries 1-999, commentary 1-10. regenerate uses the full explicit range instead of this count"
          },
          "type": "number",
          "required": true
        },
        {
          "name": "start_chapter",
          "description": {
            "zh": "起始章节号，从 1 开始",
            "en": "One-based first chapter"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "end_chapter",
          "description": {
            "zh": "结束章节号，从 1 开始",
            "en": "One-based last chapter"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "request_id",
          "description": {
            "zh": "可选重试标识；同一次请求重试时保持不变",
            "en": "Optional retry key; reuse for retries of the same request"
          },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "get_task",
      "description": {
        "zh": "查询任务状态和生成结果摘要。",
        "en": "Read task status and result summary."
      },
      "parameters": [
        {
          "name": "task_id",
          "description": {
            "zh": "任务 ID",
            "en": "Task ID"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "cancel_task",
      "description": {
        "zh": "取消任务；不会删除已完成的章节结果。",
        "en": "Cancel a task without deleting completed chapter results."
      },
      "parameters": [
        {
          "name": "task_id",
          "description": {
            "zh": "任务 ID",
            "en": "Task ID"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "list_tasks",
      "description": {
        "zh": "列出最近生成任务，以便离开界面后恢复进度查看。",
        "en": "List recent generation tasks to recover status after leaving the screen."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "最多返回任务数，默认 20",
            "en": "Maximum tasks, default 20"
          },
          "type": "number",
          "required": false
        }
      ]
    }
  ]
}
*/


function invokeNative(action, params) {
  const root =
    typeof globalThis !== "undefined"
      ? globalThis
      : typeof window !== "undefined"
        ? window
        : this;
  if (
    typeof NativeInterface === "undefined" ||
    !NativeInterface ||
    typeof NativeInterface.executeReadingCompanionAsync !== "function"
  ) {
    throw new Error("当前 Operit 版本不支持 AI 阅读伴侣原生桥接");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) {
    throw new Error("无法确认阅读伴侣工具调用身份");
  }
  return new Promise((resolve, reject) => {
    const callbackId =
      `__operit_reading_companion_${Date.now()}_` +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      try {
        delete root[callbackId];
      } catch (_deleteError) {
        root[callbackId] = undefined;
      }
      if (isError) {
        reject(new Error(String(resultJson || "阅读伴侣操作失败")));
        return;
      }
      try {
        const response = JSON.parse(String(resultJson || "{}"));
        if (!response || response.success !== true) {
          reject(
            new Error(
              (response && response.message) || "阅读伴侣操作失败",
            ),
          );
          return;
        }
        resolve(response.data);
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeReadingCompanionAsync(
        callId,
        callbackId,
        String(action || ""),
        JSON.stringify(params || {}),
      );
    } catch (error) {
      try {
        delete root[callbackId];
      } catch (_deleteError) {
        root[callbackId] = undefined;
      }
      reject(error);
    }
  });
}

async function run(action, params) {
  try {
    complete({
      success: true,
      data: await invokeNative(action, params),
    });
  } catch (error) {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
    });
  }
}

exports.start_task = (params = {}) => run("start_task", params);
exports.get_task = (params = {}) => run("get_task", params);
exports.cancel_task = (params = {}) => run("cancel_task", params);

exports.list_tasks = (params = {}) => run("list_tasks", params);
