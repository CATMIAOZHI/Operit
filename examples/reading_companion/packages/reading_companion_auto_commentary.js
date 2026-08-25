/* METADATA
{
  "name": "reading_companion_auto_commentary",
  "display_name": {
    "zh": "AI 自动段评",
    "en": "AI Auto Commentary"
  },
  "description": {
    "zh": "隔离预读下一章并预生成按段落解锁的真人式短评。未读正文和未解锁段评不会进入普通问答工具。",
    "en": "Privately pre-generates reader-like comments for exactly one next chapter. Unread text and locked comments never enter ordinary chat tools."
  },
  "category": "AI Reading Companion",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "auto_commentary_status",
      "description": {
        "zh": "查看自动段评是否启用及当前生成策略，不返回未读段评正文。",
        "en": "Show whether auto commentary is enabled and its generation policy without exposing unread comments."
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

function invokeNative(action) {
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
      "{}",
    );
  });
}

async function run(action) {
  try {
    complete({ success: true, data: await invokeNative(action) });
  } catch (error) {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
    });
  }
}

exports.auto_commentary_status = () => run("auto_commentary_status");
exports.regenerate_next_chapter_comments = () =>
  run("regenerate_next_chapter_comments");
