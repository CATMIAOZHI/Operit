const SYSTEM_PROMPT_HOOK_ID = "reading_companion_policy";

function promptText(useEnglish) {
  if (useEnglish) {
    return `AI READING COMPANION
- The tool package reads Legado only up to the reader's current position.
- For reactions, banter, or discussion about "this part", call reading_companion:get_context first.
- For earlier characters, events, causes, dialogue, places, or items, call reading_companion:search.
- For chapter recap or character profiles, prefer reading_companion:get_chapter_summary, reading_companion:get_recent_summaries, or reading_companion:get_character.
- Novel facts must come only from reading_companion tool evidence. Do not use web search, outside plot knowledge, or infer events beyond the returned boundary.
- readerMemories are the reader's own notes or predictions and are not confirmed novel facts.
- If no safe evidence is returned, say so instead of guessing.`;
  }
  return `AI 阅读伴侣
- 本工具包只读取 Legado 当前阅读位置之前的内容。
- 用户想交流、吐槽“这段”或刚发生的情节时，先调用 reading_companion:get_context。
- 用户询问前文人物、事件、原因、对话、地点或物品时，调用 reading_companion:search。
- 回顾章节或人物时，优先使用 reading_companion:get_chapter_summary、reading_companion:get_recent_summaries 或 reading_companion:get_character。
- 具体小说事实只能依据 reading_companion 工具返回的证据；不得联网搜索剧情、依赖外部剧情知识或推断阅读边界之后的事件。
- readerMemories 是读者自己的笔记或预测，不是小说已确认事实。
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
  ToolPkg.registerSystemPromptComposeHook({
    id: SYSTEM_PROMPT_HOOK_ID,
    function: onSystemPromptCompose,
  });
  return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onSystemPromptCompose = onSystemPromptCompose;
