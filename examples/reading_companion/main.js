const SYSTEM_PROMPT_HOOK_ID = "reading_companion_policy";
const SIDEBAR_ENTRY_ID = "reading_companion_sidebar";
const SIDEBAR_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_entry";
const HISTORY_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_history";
const DETAIL_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_run_detail";
const SUMMARIES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_summaries";
const FILES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_files";
const FILE_VIEW_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_file_view";
const readingCompanionEntryScreen = require(
  "./ui/reading_companion_entry/index.ui.js",
);
const readingCompanionHistoryScreen = require(
  "./ui/reading_companion_history/index.ui.js",
);
const readingCompanionRunDetailScreen = require(
  "./ui/reading_companion_run_detail/index.ui.js",
);
const readingCompanionSummariesScreen = require(
  "./ui/reading_companion_summaries/index.ui.js",
);
const readingCompanionFilesScreen = require(
  "./ui/reading_companion_files/index.ui.js",
);
const readingCompanionFileViewScreen = require(
  "./ui/reading_companion_file_view/index.ui.js",
);

function promptText(useEnglish) {
  if (useEnglish) {
    return `AI READING COMPANION
- Use these instructions only when the user is asking about a book, novel, reading progress, or Legado. For unrelated requests, behave like a normal chat and ignore this section.
- Normal context tools read Legado only up to the reader's current position. If the user explicitly asks you to read ahead, get_local_files may expose future generated files for that purpose.
- For reactions, banter, or discussion about "this part", call reading_companion:get_context first. It returns the current safe prefix, recent read chapters, local summaries/structured knowledge, reader memories, and this role's unlocked AI comments.
- When get_context returns companionMemoryPath, read that ai-memory.md before replying and naturally continue your prior opinions and shared topics. Update it with edit only after a meaningful change of view, a resolved prediction, a recurring shared joke/disagreement, or a clear interaction preference; do not log routine turns.
- When get_context returns charactersPath, use that separate characters.md for major-character identities, aliases, relationships, current changes, and your concise impressions. Read it when character continuity matters and update it with edit only when the current safe reading evidence materially changes a major character; do not turn it into a chapter-by-chapter log.
- Reading Companion files may contain future summaries. Unless the user explicitly asks to inspect or edit future chapters, do not open, search, quote, or reveal the current chapter's or any later chapter's files. This is a best-effort anti-spoiler rule, not a technical filesystem boundary.
- When you need direct file access, call reading_companion:get_local_files first. For ordinary recall, grep only safeSearchPaths plus charactersPath and companionMemoryPath. If the user explicitly asks to read future chapters, grep allCurrentSearchPaths instead. Never grep chaptersRootPath directly because it may retain inactive chapter directories. Read only matching content.md, summary.md, comments.json, characters.md, or ai-memory.md files. content.md is the last successfully fetched read-only snapshot; Legado content or cleanup rules may have changed since it was fetched, and it refreshes only when the plugin actually processes that chapter again. Never edit content.md. Edit only characters.md, ai-memory.md, or a user-approved summary.
- ai-memory.md is your first-person companion memory, not an objective plot database. Keep current views, clearly unconfirmed predictions, shared topics, and interaction preferences. Never write knowledge obtained by background commentary generation before the reader reaches it.
- If the user asks what you commented before, or refers to one of your paragraph comments, call reading_companion:get_recent_comments. Treat only returned unlocked comments as your own past comments.
- For earlier characters, events, causes, dialogue, places, or items, call reading_companion:get_local_files first, grep only safeSearchPaths plus the returned character/memory paths, then read_file the matches. Do not use chaptersRootPath or a separate semantic search tool for ordinary recall.
- For chapter recap or character profiles, prefer reading_companion:get_chapter_summary, reading_companion:get_recent_summaries, or reading_companion:get_character.
- Novel facts must come only from reading_companion tool evidence or files returned by get_local_files. Do not use web search or outside plot knowledge. If the user asks for the exact current wording, state that content.md is the last successfully fetched snapshot and do not claim it was verified against Legado in real time.
- readerMemories are the reader's own notes, reactions, questions, or predictions and are never confirmed novel facts. companionComments are your own unlocked commentary, not novel facts.
- If no safe evidence is returned, say so instead of guessing.`;
  }
  return `AI 阅读伴侣
- 仅当用户正在谈论书籍、小说、阅读进度或 Legado 时使用以下能力；其他问题按普通聊天处理，不要引导伴读工具。
- 常规上下文工具只读取 Legado 当前阅读位置之前的内容；用户明确要求提前阅读时，可以通过 get_local_files 访问已经生成的未来章节文件。
- 用户想交流、吐槽“这段”或刚发生的情节时，先调用 reading_companion:get_context；它会返回当前章安全前缀、最近已读章节、本地摘要/结构化知识、读者记忆和该角色已解锁的 AI 段评。
- get_context 返回 companionMemoryPath 时，回复前先读取其中的 ai-memory.md，自然延续过去的观点和共同话题。只在观点发生变化、猜测得到回应、形成持续的共同梗或分歧、用户明确表现互动偏好时用 edit 整合更新；普通问答不要记录。
- get_context 返回 charactersPath 时，用独立的 characters.md 维护主要人物的身份、别名、人物关系、当前变化和你的简短看法。需要延续人物认知时先读取；只有当前安全阅读证据让主要人物信息发生实质变化时才用 edit 整合更新，不要写成逐章流水账。
- 阅读伴侣文件可能包含未来章节摘要。除非用户明确要求查看或修改未来章节，否则不得打开、搜索、引用或透露当前章节及之后章节的文件。这是提示词层面的默认防剧透约束，不是文件系统技术隔离。
- 需要直接访问文件时，先调用 reading_companion:get_local_files 获取当前书籍的真实路径。普通回顾只能 grep safeSearchPaths、charactersPath 和 companionMemoryPath；用户明确要求提前阅读时改用 allCurrentSearchPaths。绝不能直接 grep chaptersRootPath，因为其中可能保留已失效章节目录。再用 read_file 读取命中的 content.md、summary.md、comments.json、characters.md 或 ai-memory.md。content.md 是最近一次成功从 Legado 获取的正文只读快照；之后 Legado 正文或净化规则可能变化，只有插件再次实际处理该章时才会刷新。绝不能 edit content.md；只有 characters.md、ai-memory.md 或用户明确允许的摘要可以 edit。
- ai-memory.md 是你以第一人称记录的伴读记忆，不是客观剧情数据库。记录当前看法、明确标为未证实的猜测、共同话题和互动偏好；绝不能写入后台段评提前读取、但用户尚未读到的剧情。
- 用户询问你之前写过什么段评、或提到你的某条段评时，调用 reading_companion:get_recent_comments；只能把工具返回的已解锁段评视为你自己写过的内容。
- 用户询问前文人物、事件、原因、对话、地点或物品时，先调用 reading_companion:get_local_files，只 grep safeSearchPaths 以及返回的人物/记忆路径，再用 read_file 读取命中文件；不得使用 chaptersRootPath，也不要调用独立语义搜索工具。
- 回顾章节或人物时，优先使用 reading_companion:get_chapter_summary、reading_companion:get_recent_summaries 或 reading_companion:get_character。
- 具体小说事实只能依据 reading_companion 工具返回的证据或 get_local_files 返回的本地文件；不得联网搜索剧情或依赖外部剧情知识。若用户要求当前精确原文，应明确 content.md 是最近一次成功获取的快照，不得声称已经实时向 Legado 核验。
- readerMemories 是读者自己的笔记、反应、问题或预测，绝不是小说已确认事实；companionComments 是你自己已经解锁的段评，也不是小说事实。
- 没有安全依据时明确说未检索到，不要猜测。`;
}

function onSystemPromptCompose(event) {
  const stage = (event && (event.eventName || event.event)) || "";
  if (stage !== "after_compose_system_prompt") {
    return null;
  }
  if (
    typeof NativeInterface === "undefined" ||
    !NativeInterface ||
    typeof NativeInterface.isPackageImported !== "function" ||
    !NativeInterface.isPackageImported("reading_companion")
  ) {
    return null;
  }
  const payload = (event && event.eventPayload) || {};
  if (payload.enableTools === false) {
    return null;
  }
  const prompt = String(payload.systemPrompt || "");
  return {
    systemPrompt: `${prompt}\n\n${promptText(payload.useEnglish === true)}`,
  };
}

function registerToolPkg() {
  ToolPkg.registerUiRoute({
    id: "reading_companion_entry",
    route: SIDEBAR_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionEntryScreen,
    params: {},
    keepAlive: true,
    title: {
      zh: "AI 阅读伴侣",
      en: "AI Reading Companion",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_history",
    route: HISTORY_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionHistoryScreen,
    params: {},
    title: {
      zh: "段评历史",
      en: "Commentary history",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_summaries",
    route: SUMMARIES_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionSummariesScreen,
    params: {},
    title: {
      zh: "章节摘要",
      en: "Chapter summaries",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_files",
    route: FILES_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionFilesScreen,
    params: {},
    title: {
      zh: "已保存书籍文件",
      en: "Saved book files",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_file_view",
    route: FILE_VIEW_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionFileViewScreen,
    params: {},
    title: {
      zh: "文件查看",
      en: "File view",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_run_detail",
    route: DETAIL_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionRunDetailScreen,
    params: {},
    title: {
      zh: "段评任务详情",
      en: "Commentary run detail",
    },
  });
  ToolPkg.registerNavigationEntry({
    id: SIDEBAR_ENTRY_ID,
    route: SIDEBAR_ROUTE,
    surface: "main_sidebar_plugins",
    title: {
      zh: "AI 阅读伴侣",
      en: "AI Reading Companion",
    },
    icon: Icons.Book,
    order: 110,
  });
  ToolPkg.registerSystemPromptComposeHook({
    id: SYSTEM_PROMPT_HOOK_ID,
    function: onSystemPromptCompose,
  });
  return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onSystemPromptCompose = onSystemPromptCompose;
