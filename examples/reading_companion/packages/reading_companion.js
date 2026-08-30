/* METADATA
{
  "name": "reading_companion",
  "display_name": {
    "zh": "阅读伴侣工具",
    "en": "Reading Companion Tools"
  },
  "description": {
    "zh": "在 Legado 已读边界内陪聊、回顾和建立本地小说知识索引。读者记忆与小说事实严格分开。",
    "en": "Legado conversation, recall, and local novel knowledge. Reader memories stay separate from novel facts."
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
        "zh": "获取严格已读边界内的对话上下文：当前章安全正文、最近最多 8 章前文、已有摘要/结构化知识、读者记忆，以及该角色已解锁的 AI 段评。用户想交流、吐槽“这段”或刚发生的剧情时使用。",
        "en": "Get spoiler-safe conversation context: current-chapter text, up to 8 recent read chapters, existing summaries/structured knowledge, reader memories, and this character's unlocked AI comments."
      },
      "parameters": [
        {
          "name": "max_characters",
          "description": { "zh": "完整工具结果的序列化字符预算，8000 到 96000，默认 16000；超出时硬裁剪", "en": "Serialized complete-result budget, 8000 to 96000; defaults to 16000 with hard trimming for overflow" },
          "type": "number",
          "required": false,
          "default": 16000
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
      "name": "get_chapter_summary",
      "description": {
        "zh": "读取当前或已读章节已有的摘要和人物、事件、地点、物品、关系变化、伏笔候选等结构化知识；缺失时不会自动调用模型。",
        "en": "Read existing structured knowledge for the current or a read chapter; missing summaries never trigger a model call automatically."
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
          "description": { "zh": "兼容旧调用参数；普通读取不会生成摘要，默认 false", "en": "Legacy compatibility parameter; ordinary reads never generate summaries, default false" },
          "type": "boolean",
          "required": false,
          "default": false
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
      "name": "list_summary_files",
      "description": {
        "zh": "列出当前书籍已经持久化的全部章节摘要及其可编辑 Markdown 路径。可能包含当前进度之后的章节；仅在用户明确要求查看、审计或修改摘要时调用。",
        "en": "List every persisted chapter summary and editable Markdown path for the current book. May include unread chapters; call only when the user explicitly asks to inspect or edit summaries."
      },
      "parameters": []
    },
    {
      "name": "get_local_files",
      "description": {
        "zh": "返回普通回顾专用的 safeSearchPaths、用户明确要求提前阅读时使用的 allCurrentSearchPaths，以及 book.md、characters.md 和当前角色 ai-memory.md 路径。禁止直接 grep chaptersRootPath，因为它可能保留已失效目录。content.md 是最近一次成功从 Legado 获取的正文只读快照；之后正文或净化规则可能变化，只有插件再次实际处理该章时才刷新，禁止 edit。",
        "en": "Return safeSearchPaths for ordinary recall, allCurrentSearchPaths for explicit read-ahead requests, and paths for book.md, characters.md, and this role's ai-memory.md. Never grep chaptersRootPath directly because it may retain inactive directories. content.md is the last successfully fetched read-only chapter snapshot; Legado content or cleanup rules may change afterward, and it refreshes only when the plugin actually processes that chapter again. Never edit it."
      },
      "parameters": []
    },
    {
      "name": "refresh_progress",
      "description": {
        "zh": "刷新 Legado 进度并增量索引新读正文；摘要只会在用户明确发起手动批量操作时生成。",
        "en": "Refresh Legado progress and incrementally index newly read text; summaries are generated only by an explicit manual batch."
      },
      "parameters": [
        {
          "name": "max_chapters",
          "description": { "zh": "本次最多索引章节数，默认 3", "en": "Maximum chapters indexed now, default 3" },
          "type": "number",
          "required": false,
          "default": 3
        }
      ]
    },
    {
      "name": "manual_batch_summaries",
      "description": {
        "zh": "仅在用户明确点击手动操作后，为指定已读章节逐章生成摘要。每章创建一个子代理任务，任务内部可能有多轮模型调用；返回目标范围和任务数。不会由普通伴读或后台触发。",
        "en": "Only after an explicit user action, generate summaries chapter by chapter for selected read chapters. Creates one subagent task per chapter, and each task may use multiple model turns; returns the target range and task count. Never triggered by ordinary context or background refresh."
      },
      "parameters": [
        {
          "name": "count",
          "description": { "zh": "数量 1 到 10", "en": "Number of chapters, 1 to 10" },
          "type": "number",
          "required": true
        },
        {
          "name": "start_chapter_index",
          "description": { "zh": "从 0 开始的起始章节索引，可省略", "en": "Zero-based start chapter index, optional" },
          "type": "number",
          "required": false
        },
        {
          "name": "end_chapter_index",
          "description": { "zh": "从 0 开始的结束章节索引，可省略", "en": "Zero-based end chapter index, optional" },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "auto_commentary_manual_batch",
      "description": {
        "zh": "仅在用户明确操作时，为允许提前阅读窗口内的连续章节逐章生成段评。每章创建一个子代理任务，任务内部可能有多轮模型调用；返回实际目标列表和任务数，不会开启或依赖后台自动段评。",
        "en": "Only after an explicit user action, generate commentary chapter by chapter for a selected range within the read-ahead window. Creates one subagent task per chapter, and each task may use multiple model turns; returns the target list and task count without enabling or relying on background auto commentary."
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
    },
    {
      "name": "list_persisted_files",
      "description": {
        "zh": "分页列出当前书籍已保存的 book.md、characters.md、ai-memory.md 与各章 content/summary/comments/meta 文件，供界面浏览；不会导出整本书。",
        "en": "List persisted book.md, characters.md, ai-memory.md, and chapter content/summary/comments/meta files with pagination for the in-app browser; never exports the whole book at once."
      },
      "parameters": [
        {
          "name": "offset",
          "description": { "zh": "分页偏移，默认 0", "en": "Page offset, default 0" },
          "type": "number",
          "required": false,
          "default": 0
        },
        {
          "name": "limit",
          "description": { "zh": "每页数量，1 到 100，默认 50", "en": "Page size from 1 to 100, default 50" },
          "type": "number",
          "required": false,
          "default": 50
        }
      ]
    },
    {
      "name": "read_persisted_file",
      "description": {
        "zh": "读取文件浏览器选中的白名单文件；路径必须来自 list_persisted_files。content.md 始终只读。",
        "en": "Read an allowlisted file selected by the file browser; the path must come from list_persisted_files. content.md is always read-only."
      },
      "parameters": [
        {
          "name": "path",
          "description": { "zh": "list_persisted_files 返回的文件路径", "en": "File path returned by list_persisted_files" },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "auto_commentary_history",
      "description": {
        "zh": "查看自动段评任务历史；单次详情会显示已生成段评全文、段落锚点、类型、作者和安全调用链，并可打开对应的完整审计对话。",
        "en": "Show auto-commentary history; run details include generated comment text, paragraph anchors, type, author, and a safe operation trace, and can open the full audit chat."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，1 到 50，默认 10。",
            "en": "Number of runs, 1 to 50; defaults to 10."
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "auto_commentary_run_detail",
      "description": {
        "zh": "查看单次自动段评任务的段评全文、章节/段落锚点、类型、作者和阶段/实际调用链，并可打开该任务的完整审计对话。",
        "en": "Show one auto-commentary run's generated text, chapter/paragraph anchors, type, author, and stage/operation trace, and open its full audit chat."
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
      "name": "request_next_chapter_comments",
      "description": {
        "zh": "立即以本书角色卡的口吻，用当前对话直接生成下一章段落级 AI 段评（子任务执行，完成后返回状态与数量）。会产生模型消耗；完成后可在历史详情查看或打开本次审计对话。",
        "en": "Immediately generate next-chapter paragraph-level AI comments in this conversation (runs as a subtask and returns status and count when done). Spends model tokens; open the run's audit chat afterwards for the full transcript."
      },
      "parameters": []
    },
    {
      "name": "list_audit_chats",
      "description": {
        "zh": "按书列出段评审计隐藏聊天（隐藏根与每次任务的子代理对话摘要：chatId、标题、runId、状态），供界面打开对应审计对话。",
        "en": "List commentary audit chats per book (hidden root and per-run subagent chat summaries: chatId, title, runId, status) so the UI can open them."
      },
      "parameters": [
        {
          "name": "bookId",
          "description": {
            "zh": "只返回指定书籍的审计聊天；省略返回全部书籍",
            "en": "Return audit chats only for this book; omit for all books"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "limit",
          "description": {
            "zh": "最多返回最近多少条任务审计聊天（1-50，默认 10）",
            "en": "Maximum number of recent run audit chats to return (1-50, default 10)"
          },
          "type": "number",
          "required": false
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
exports.get_chapter_summary = (params) => run("get_chapter_summary", params);
exports.get_character = (params) => run("get_character", params);
exports.get_recent_summaries = (params) => run("get_recent_summaries", params);
exports.list_summary_files = (params) => run("list_summary_files", params);
exports.get_local_files = (params) => run("get_local_files", params);
exports.refresh_progress = (params) => run("refresh_progress", params);
exports.manual_batch_summaries = (params) =>
  run("manual_batch_summaries", {
    count: params && params.count,
    start_chapter_index: params && params.start_chapter_index,
    end_chapter_index: params && params.end_chapter_index,
  });
exports.auto_commentary_manual_batch = (params = {}) =>
  run("auto_commentary_manual_batch", {
    count: params.count,
    start_chapter_index: params.start_chapter_index,
    end_chapter_index: params.end_chapter_index,
  });
exports.list_persisted_files = (params = {}) =>
  run("list_persisted_files", {
    offset: params.offset,
    limit: params.limit,
  });
exports.read_persisted_file = (params = {}) =>
  run("read_persisted_file", { path: params.path });
exports.add_memory = (params) => run("add_memory", params);
exports.auto_commentary_history = (params = {}) =>
  run("auto_commentary_history", { limit: params.limit });
exports.auto_commentary_run_detail = (params = {}) =>
  run("auto_commentary_run_detail", { runId: params.runId });
exports.request_next_chapter_comments = (params = {}) =>
  run("request_next_chapter_comments", {});
exports.list_audit_chats = (params = {}) =>
  run("list_audit_chats", {
    bookId: params.bookId,
    limit: params.limit,
  });
