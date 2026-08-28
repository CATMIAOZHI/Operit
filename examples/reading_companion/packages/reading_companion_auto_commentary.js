/* METADATA
{
  "name": "reading_companion_auto_commentary",
  "display_name": {
    "zh": "AI 自动段评",
    "en": "AI Auto Commentary"
  },
  "description": {
    "zh": "使用当前书籍所选角色卡，隔离读取下一章及最近最多 8 章、总计最多 4.8 万字的前情，生成少而精、按段落解锁的个性段评；Legado 将角色卡名字显示为作者。过程中会产生模型 Token 消耗，未读正文和未解锁段评不会进入伴读问答。",
    "en": "Uses the selected per-book character card, the next chapter, and up to 8 recent chapters of private context (48,000 characters total) to pre-generate sparse in-character comments. Legado shows the card name as author. This spends model tokens, while unread text and locked comments stay out of ordinary chat."
  },
  "category": "AI Reading Companion",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "auto_commentary_status",
      "description": {
        "zh": "查看自动段评是否启用、角色卡/模型选择策略及最近一次任务，不返回未读段评正文。",
        "en": "Show whether auto commentary is enabled, its role/model policy, and the latest run without exposing unread comments."
      },
      "parameters": []
    },
    {
      "name": "auto_commentary_history",
      "description": {
        "zh": "查看最近的后台段评执行历史，包括状态、脱敏失败原因、实际角色卡和模型；单次详情可查看已生成段评全文与安全调用链，不返回书源地址、未读正文或密钥。",
        "en": "Show recent commentary run history with status, sanitized error, actual role card, and model, without source URLs, unread titles, text, or comments."
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
        "zh": "查看单次段评任务的段评全文、章节/段落锚点、类型、作者和阶段/实际调用链；不返回未读正文或密钥。",
        "en": "Show one commentary run's generated text, chapter/paragraph anchors, type, author, and stage/operation trace without unread text or secrets."
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
      "name": "queue_regenerate_next_chapter_comments",
      "description": {
        "zh": "在后台排队重新生成下一章段评并立即返回，供界面持续显示生成阶段。会产生模型调用，但不返回未读正文或段评。",
        "en": "Queue next-chapter commentary regeneration in the background and return immediately so the UI can show live stages. This invokes the model but returns no unread text or comments."
      },
      "parameters": []
    },
    {
      "name": "regenerate_next_chapter_comments",
      "description": {
        "zh": "手动重新生成下一章段评。会产生模型调用，但不会把未读正文或段评返回给聊天。",
        "en": "Regenerate next-chapter comments. This invokes the model but never returns unread text or comments to chat."
      },
      "parameters": []
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
exports.queue_regenerate_next_chapter_comments = () =>
  run("queue_regenerate_next_chapter_comments");
exports.regenerate_next_chapter_comments = () =>
  run("regenerate_next_chapter_comments");
