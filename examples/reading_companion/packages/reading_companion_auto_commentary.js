/* METADATA
{
  "name": "reading_companion_auto_commentary",
  "display_name": {
    "zh": "AI 自动段评",
    "en": "AI Auto Commentary"
  },
  "description": {
    "zh": "使用当前书籍所选角色卡，隔离读取后续章节（默认提前 5 章，可在 1～10 章调整）及最近最多 8 章、总计最多 4.8 万字的前情，生成少而精、按段落解锁的个性段评；Legado 将角色卡名字显示为作者。过程中会产生模型 Token 消耗。",
    "en": "Uses the selected per-book character card, the chapters ahead (default 5, adjustable from 1 to 10) and up to 8 recent chapters of private context (48,000 characters total) to pre-generate sparse in-character comments. Legado shows the card name as author. This spends model tokens."
  },
  "category": "AI Reading Companion",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "auto_commentary_status",
      "description": {
        "zh": "查看自动段评是否启用、角色卡/模型选择策略及最近一次任务。",
        "en": "Show whether auto commentary is enabled, its role/model policy, and the latest run."
      },
      "parameters": []
    },
    {
      "name": "auto_commentary_history",
      "description": {
        "zh": "查看最近的后台段评执行历史，包括状态、脱敏失败原因、实际角色卡和模型；单次详情可查看已生成段评全文与安全调用链，不返回书源地址或密钥。",
        "en": "Show recent commentary run history with status, sanitized error, actual role card, and model, without source URLs or secrets."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，1 到 50，默认 10。",
            "en": "Number of runs to return, 1 to 50; defaults to 10."
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "auto_commentary_run_detail",
      "description": {
        "zh": "查看单次段评任务的段评全文、章节/段落锚点、类型、作者和阶段/实际调用链；不返回书源地址或密钥。",
        "en": "Show one commentary run's generated text, chapter/paragraph anchors, type, author, and stage/operation trace without source URLs or secrets."
      },
      "parameters": [
        {
          "name": "runId",
          "description": {
            "zh": "历史列表中的任务 ID。",
            "en": "Run ID from the history list."
          },
          "type": "number",
          "required": true
        }
      ]
    },
    {
      "name": "auto_commentary_get_config",
      "description": {
        "zh": "查看自动段评的提前生成章数配置。",
        "en": "Show the auto commentary pre-generate distance configuration."
      },
      "parameters": []
    },
    {
      "name": "auto_commentary_set_config",
      "description": {
        "zh": "设置自动段评提前生成的章数（1～10），改动立即生效并在空闲时补足新窗口。",
        "en": "Set how many chapters ahead auto commentary pre-generates (1 to 10). The change applies right away and idle runs fill the new window."
      },
      "parameters": [
        {
          "name": "prefetchAheadChapters",
          "description": {
            "zh": "提前生成章数，1 到 10。",
            "en": "Number of chapters ahead to pre-generate, 1 to 10."
          },
          "type": "number",
          "required": true
        }
      ]
    },
    {
      "name": "queue_regenerate_next_chapter_comments",
      "description": {
        "zh": "在后台排队重新生成下一章段评并立即返回，供界面持续显示生成阶段。会产生模型调用。",
        "en": "Queue next-chapter commentary regeneration in the background and return immediately so the UI can show live stages. This invokes the model."
      },
      "parameters": []
    },
    {
      "name": "regenerate_next_chapter_comments",
      "description": {
        "zh": "手动重新生成下一章段评。会产生模型调用，完成后可在历史详情中查看段评全文与审计对话。",
        "en": "Regenerate next-chapter comments. This invokes the model; the generated text and audit chat are available in run history afterwards."
      },
      "parameters": []
    },
    {
      "name": "auto_commentary_manual_batch",
      "description": {
        "zh": "仅在用户明确操作时，为允许提前阅读窗口内的连续章节逐章生成段评。每章创建一个子代理任务，任务内部可能有多轮模型调用；返回实际目标列表和任务数，不会使用重复 force 循环。",
        "en": "Only after an explicit user action, generate commentary chapter by chapter for a selected range within the read-ahead window. Creates one subagent task per chapter, and each task may use multiple model turns; returns the target list and task count without a repeated force loop."
      },
      "parameters": [
        {
          "name": "count",
          "description": {
            "zh": "数量 1 到 10",
            "en": "Number of chapters, 1 to 10"
          },
          "type": "number",
          "required": true
        },
        {
          "name": "start_chapter_index",
          "description": {
            "zh": "从 0 开始的起始章节索引，可省略",
            "en": "Zero-based start chapter index, optional"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "end_chapter_index",
          "description": {
            "zh": "从 0 开始的结束章节索引，可省略",
            "en": "Zero-based end chapter index, optional"
          },
          "type": "number",
          "required": false
        }
      ]
    }
  ]
}
*/

function invokeNative(action, parameters = {}) {
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
    throw new Error("当前 Operit 版本不支持 AI 自动段评原生桥接");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) {
    throw new Error("无法确认 AI 自动段评工具调用身份");
  }
  return new Promise((resolve, reject) => {
    const callbackId =
      `__operit_auto_commentary_${Date.now()}_` +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      try {
        delete root[callbackId];
      } catch (_deleteError) {
        root[callbackId] = undefined;
      }
      if (isError) {
        reject(new Error(String(resultJson || "AI 自动段评操作失败")));
        return;
      }
      try {
        const response = JSON.parse(String(resultJson || "{}"));
        if (!response || response.success !== true) {
          reject(new Error((response && response.message) || "AI 自动段评操作失败"));
          return;
        }
        resolve(response.data);
      } catch (error) {
        reject(error);
      }
    };
    NativeInterface.executeReadingCompanionAsync(
      callId,
      callbackId,
      String(action || ""),
      JSON.stringify(parameters || {}),
    );
  });
}

async function run(action, parameters = {}) {
  try {
    complete({ success: true, data: await invokeNative(action, parameters) });
  } catch (error) {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
    });
  }
}

exports.auto_commentary_status = () => run("auto_commentary_status");
exports.auto_commentary_history = (params = {}) =>
  run("auto_commentary_history", { limit: params.limit });
exports.auto_commentary_run_detail = (params = {}) =>
  run("auto_commentary_run_detail", { runId: params.runId });
exports.auto_commentary_get_config = () => run("auto_commentary_get_config");
exports.auto_commentary_set_config = (params = {}) =>
  run("auto_commentary_set_config", {
    prefetchAheadChapters: params.prefetchAheadChapters,
  });
exports.queue_regenerate_next_chapter_comments = () =>
  run("queue_regenerate_next_chapter_comments");
exports.regenerate_next_chapter_comments = () =>
  run("regenerate_next_chapter_comments");
exports.auto_commentary_manual_batch = (params = {}) =>
  run("auto_commentary_manual_batch", {
    count: params.count,
    start_chapter_index: params.start_chapter_index,
    end_chapter_index: params.end_chapter_index,
  });
