/* METADATA
{
  "name": "codex_search",
  "display_name": {"zh": "Codex 搜索", "en": "Codex Search"},
  "description": {
    "zh": "使用应用内的 ChatGPT（Codex）登录联网搜索，直接返回搜索结果（标题、链接、片段）；不需要单独填写 API Key。任何模型都能调用。",
    "en": "Search the web with the in-app ChatGPT (Codex) login and get the search results themselves (titles, links, snippets), without an API key. Any model can call it."
  },
  "category": "Search",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "web_search",
      "description": {
        "zh": "联网搜索。每个搜索词返回搜索服务给的结果文字和结果列表（标题、链接、片段），由你自己判断和总结。直接搜索接口不可用时会自动改由 ChatGPT 搜索并写总结，结果开头会注明。可一次传多个搜索词并行搜索。需要先登录 ChatGPT。",
        "en": "Search the web. Each query returns the search service's own text of the results and a result list (title, link, snippet) for you to judge and sum up. If the direct search endpoint is unavailable it falls back to ChatGPT searching and summarizing, noted at the top of that result. Several queries run in parallel. Needs the ChatGPT login."
      },
      "parameters": [
        {"name": "query", "type": "string", "required": false, "description": {"zh": "一个搜索词；和 queries 二选一", "en": "One query; use this or queries"}},
        {"name": "queries", "type": "array", "required": false, "description": {"zh": "多个搜索词的数组，最多 5 个，会并行搜索", "en": "Array of up to 5 queries, searched in parallel"}},
        {"name": "search_context_size", "type": "string", "required": false, "description": {"zh": "搜索读取的内容量：low、medium（默认）、high。越高越全，也越慢、越耗额度；直接搜索时 low 按 medium 处理", "en": "How much the search reads: low, medium (default) or high. Higher is fuller but slower and uses more quota; direct search treats low as medium"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 gpt-6-luna；也可填其他 gpt 开头的 Codex 模型", "en": "Optional, default gpt-6-luna; other Codex models starting with gpt also work"}}
      ]
    }
  ]
}
*/

function invokeCodexSearch(params) {
  const root = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof NativeInterface === "undefined" ||
      typeof NativeInterface.executeCodexSearchAsync !== "function") {
    throw new Error("当前 Operit 版本不支持 Codex 搜索");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) throw new Error("无法确认 Codex 搜索工具调用身份");
  return new Promise((resolve, reject) => {
    const callbackId = "__operit_codex_search_" + Date.now() + "_" +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      delete root[callbackId];
      if (isError) {
        reject(new Error(String(resultJson || "Codex 搜索失败")));
        return;
      }
      try {
        resolve(JSON.parse(String(resultJson || "{}")));
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeCodexSearchAsync(callId, callbackId, JSON.stringify(params));
    } catch (error) {
      delete root[callbackId];
      reject(error);
    }
  });
}

/** queries may arrive as a JSON string or a single text; the host wants an array. */
function normalizeQueries(params) {
  const request = Object.assign({}, params || {});
  const queries = request.queries;
  if (typeof queries === "string") {
    const text = queries.trim();
    if (!text) {
      delete request.queries;
    } else if (text.startsWith("[")) {
      try {
        request.queries = JSON.parse(text);
      } catch (error) {
        throw new Error("queries 要写成搜索词数组，例如 [\"今天的新闻\", \"天气\"]");
      }
    } else {
      request.queries = [text];
    }
  }
  return request;
}

/** The results as readable text: each query's text, its numbered sources (with snippets) and any note. */
function formatSearches(result) {
  const MAX_LISTED = 15;
  const parts = [];
  (result.searches || []).forEach((search) => {
    const lines = ["## " + search.query];
    if (search.error) {
      lines.push("搜索失败：" + search.error);
    } else {
      if (search.note) lines.push("（" + search.note + "）");
      if (search.summary) lines.push(search.summary);
      const sources = search.results || [];
      if (sources.length) {
        lines.push("", "来源：");
        sources.slice(0, MAX_LISTED).forEach((source, index) => {
          lines.push((index + 1) + ". " + (source.title ? source.title + " - " : "") + source.url);
          if (source.snippet) lines.push("   " + source.snippet);
        });
        if (sources.length > MAX_LISTED) lines.push("……另有 " + (sources.length - MAX_LISTED) + " 个来源未列出");
      } else {
        lines.push("", "（这次没有返回来源链接）");
      }
    }
    parts.push(lines.join("\n"));
  });
  return parts.join("\n\n");
}

exports.web_search = async (params) => {
  try {
    const result = await invokeCodexSearch(normalizeQueries(params));
    complete({
      success: !!result.success,
      message: formatSearches(result),
      data: { provider: result.provider, model: result.model, searched_at: result.searched_at, success: !!result.success }
    });
  } catch (error) {
    complete({
      success: false,
      message: "Codex 搜索失败：" + (error && error.message ? error.message : String(error))
    });
  }
};
