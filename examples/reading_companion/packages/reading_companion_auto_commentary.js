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
exports.auto_commentary_get_config = () => run("auto_commentary_get_config");
exports.auto_commentary_set_config = (params = {}) => run("auto_commentary_set_config", params);
