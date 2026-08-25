/* METADATA
{
  "name": "reading_companion",
  "display_name": {
    "zh": "阅读伴侣工具",
    "en": "Reading Companion Tools"
  },
  "description": {
    "zh": "在 Legado 已读边界内陪聊、回顾和建立本地小说知识索引。读者记忆与小说事实严格分开。",
    "en": "Spoiler-safe Legado conversation, recall, and local novel knowledge. Reader memories stay separate from novel facts."
  },
  "category": "AI Reading Companion",
  "enabledByDefault": true,
  "tools": [
    {
      "name": "list_books",
      "description": {
        "zh": "列出 Legado 书架，并显示当前采用自动最近阅读还是手动选书。",
        "en": "List the Legado bookshelf and the current automatic or manual selection mode."
      },
      "parameters": []
    },
    {
      "name": "select_book",
      "description": {
        "zh": "按完整书名或 book_id 手动选择伴读书籍；automatic=true 恢复自动跟随最近阅读。",
        "en": "Select a book by full name or book_id; automatic=true restores recent-book tracking."
      },
      "parameters": [
        {
          "name": "book",
          "description": { "zh": "书名或 book_id；自动模式可省略", "en": "Book name or book_id; omit for automatic mode" },
          "type": "string",
          "required": false
        },
        {
          "name": "automatic",
          "description": { "zh": "是否恢复自动跟随最近阅读", "en": "Restore automatic recent-book tracking" },
          "type": "boolean",
          "required": false,
          "default": false
        }
      ]
    },
    {
      "name": "get_current_book",
      "description": {
        "zh": "获取当前伴读书籍、章节和安全阅读位置。",
        "en": "Get the current companion book, chapter, and safe reading position."
      },
      "parameters": []
    },
    {
      "name": "get_context",
      "description": {
        "zh": "获取当前阅读位置之前的安全正文，以及该角色已经写过且已解锁的当前章段评。用户想交流、吐槽“这段”或刚发生的剧情时使用。",
        "en": "Get safe text before the current reading position plus this character's unlocked comments in the current chapter."
      },
      "parameters": [
        {
          "name": "max_characters",
          "description": { "zh": "返回 400 到 6000 字，默认 2600", "en": "Return 400 to 6000 characters, default 2600" },
          "type": "number",
          "required": false,
          "default": 2600
        }
      ]
    },
    {
      "name": "get_recent_comments",
      "description": {
        "zh": "读取当前角色自己已经写过、且用户已阅读解锁的近期段评。用户询问“你刚才怎么评价”“你之前写过什么段评”时使用。",
        "en": "Read this character's own recent comments that the user has already unlocked by reading."
      },
      "parameters": [
        {
          "name": "limit",
          "description": { "zh": "返回 1 到 50 条，默认 20", "en": "Return 1 to 50 comments; defaults to 20" },
          "type": "number",
          "required": false,
          "default": 20
        }
      ]
    },
    {
      "name": "search",
      "description": {
        "zh": "三级检索已读范围：人物/事件结构化知识、章节摘要、正文片段；同时单独返回匹配的读者记忆。",
        "en": "Three-level search over structured knowledge, chapter summaries, and read text, with reader memories returned separately."
      },
      "parameters": [
        {
          "name": "query",
          "description": { "zh": "要回顾的问题或描述", "en": "Recall question or description" },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "get_chapter_summary",
      "description": {
        "zh": "获取当前或已读章节摘要和人物、事件、地点、物品、关系变化、伏笔候选等结构化知识；缺失时可用当前模型生成。",
        "en": "Get structured knowledge for the current or a read chapter, optionally generating it with the current model."
      },
      "parameters": [
        {
          "name": "chapter_index",
          "description": { "zh": "从 0 开始的章节索引，省略表示当前章", "en": "Zero-based chapter index; omit for current chapter" },
          "type": "number",
          "required": false
        },
        {
          "name": "generate_if_missing",
          "description": { "zh": "缺失时是否生成，默认 true", "en": "Generate when missing, default true" },
          "type": "boolean",
          "required": false,
          "default": true
        }
      ]
    },
    {
      "name": "get_character",
      "description": {
        "zh": "从已读章节结构化索引中查询人物、别称和可确认事实，并附章节依据。",
        "en": "Find a character, aliases, and confirmed facts in the read structured index with chapter evidence."
      },
      "parameters": [
        {
          "name": "name",
          "description": { "zh": "人物名或称呼", "en": "Character name or alias" },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "get_recent_summaries",
      "description": {
        "zh": "获取最近若干个已读章节的摘要，用于快速回顾前文。",
        "en": "Get recent read-chapter summaries for a quick recap."
      },
      "parameters": [
        {
          "name": "count",
          "description": { "zh": "数量 1 到 10，默认 5", "en": "Count from 1 to 10, default 5" },
          "type": "number",
          "required": false,
          "default": 5
        }
      ]
    },
    {
      "name": "refresh_progress",
      "description": {
        "zh": "刷新 Legado 进度，增量索引新读正文，并为已读完章节生成结构化摘要；剩余工作在后台继续。",
        "en": "Refresh Legado progress, incrementally index new text, and build structured summaries in the background."
      },
      "parameters": [
        {
          "name": "max_chapters",
          "description": { "zh": "本次最多索引章节数，默认 3", "en": "Maximum chapters indexed now, default 3" },
          "type": "number",
          "required": false,
          "default": 3
        },
        {
          "name": "max_summaries",
          "description": { "zh": "本次最多生成摘要数，默认 1", "en": "Maximum summaries generated now, default 1" },
          "type": "number",
          "required": false,
          "default": 1
        }
      ]
    },
    {
      "name": "add_memory",
      "description": {
        "zh": "保存读者自己的笔记、情绪、猜测或待验证问题。它与小说事实索引分开，不能作为已确认剧情。",
        "en": "Save a reader note, reaction, prediction, or question separately from confirmed novel facts."
      },
      "parameters": [
        {
          "name": "type",
          "description": { "zh": "note、reaction、prediction 或 question", "en": "note, reaction, prediction, or question" },
          "type": "string",
          "required": false,
          "default": "note"
        },
        {
          "name": "content",
          "description": { "zh": "记忆内容", "en": "Memory content" },
          "type": "string",
          "required": true
        },
        {
          "name": "chapter_index",
          "description": { "zh": "绑定的已读章节索引，省略表示当前章", "en": "Read chapter index; omit for current chapter" },
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

exports.list_books = (params) => run("list_books", params);
exports.select_book = (params) => run("select_book", params);
exports.get_current_book = (params) => run("get_current_book", params);
exports.get_context = (params) => run("get_context", params);
exports.get_recent_comments = (params) => run("get_recent_comments", params);
exports.search = (params) => run("search", params);
exports.get_chapter_summary = (params) => run("get_chapter_summary", params);
exports.get_character = (params) => run("get_character", params);
exports.get_recent_summaries = (params) => run("get_recent_summaries", params);
exports.refresh_progress = (params) => run("refresh_progress", params);
exports.add_memory = (params) => run("add_memory", params);
