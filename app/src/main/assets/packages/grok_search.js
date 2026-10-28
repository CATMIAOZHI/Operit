/* METADATA
{
  "name": "grok_search",
  "display_name": {"zh": "Grok 搜索", "en": "Grok Search"},
  "description": {
    "zh": "使用应用内的 Grok 账号登录搜索网页和 X（推特）实时帖子，返回摘要和真实来源链接；不需要单独填写 xAI API Key，需要 X Premium 或 SuperGrok 等订阅。任何模型都能调用。",
    "en": "Search the web and live X (Twitter) posts with the in-app Grok account login and get a summary with real source links, without an xAI API key. Needs a plan such as X Premium or SuperGrok. Any model can call it."
  },
  "category": "Search",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "web_search",
      "description": {
        "zh": "用 Grok 搜索网页和新闻，返回每个搜索词的摘要、来源标题和链接。可一次传多个搜索词并行搜索。需要先登录 Grok 账号。",
        "en": "Search the web and news with Grok and return a summary with source titles and links for each query. Several queries run in parallel. Needs the Grok account login."
      },
      "parameters": [
        {"name": "query", "type": "string", "required": false, "description": {"zh": "一个搜索词；和 queries 二选一", "en": "One query; use this or queries"}},
        {"name": "queries", "type": "array", "required": false, "description": {"zh": "多个搜索词的数组，最多 5 个，会并行搜索", "en": "Array of up to 5 queries, searched in parallel"}},
        {"name": "allowed_domains", "type": "array", "required": false, "description": {"zh": "只搜这些域名，最多 5 个，比如 [\"github.com\"]；不能和 excluded_domains 同时用", "en": "Only search these domains, up to 5, e.g. [\"github.com\"]; not together with excluded_domains"}},
        {"name": "excluded_domains", "type": "array", "required": false, "description": {"zh": "不搜这些域名，最多 5 个", "en": "Leave out these domains, up to 5"}},
        {"name": "enable_image_search", "type": "boolean", "required": false, "description": {"zh": "同时搜索相关图片，默认不搜", "en": "Also search related images; off by default"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-4.3", "en": "Optional, default grok-4.3"}}
      ]
    },
    {
      "name": "x_search",
      "description": {
        "zh": "用 Grok 搜索 X（推特）上的实时帖子，适合查热门讨论、最新动态、某人最近说了什么。返回摘要和帖子链接。需要先登录 Grok 账号。",
        "en": "Search live posts on X (Twitter) with Grok: trending talk, latest news, what someone said lately. Returns a summary and post links. Needs the Grok account login."
      },
      "parameters": [
        {"name": "query", "type": "string", "required": false, "description": {"zh": "一个搜索词；和 queries 二选一", "en": "One query; use this or queries"}},
        {"name": "queries", "type": "array", "required": false, "description": {"zh": "多个搜索词的数组，最多 5 个，会并行搜索", "en": "Array of up to 5 queries, searched in parallel"}},
        {"name": "allowed_x_handles", "type": "array", "required": false, "description": {"zh": "只看这些账号的帖子，最多 20 个，不带 @，比如 [\"xai\"]；不能和 excluded_x_handles 同时用", "en": "Only posts from these accounts, up to 20, without @, e.g. [\"xai\"]; not together with excluded_x_handles"}},
        {"name": "excluded_x_handles", "type": "array", "required": false, "description": {"zh": "不看这些账号的帖子，最多 20 个，不带 @", "en": "Leave out posts from these accounts, up to 20, without @"}},
        {"name": "from_date", "type": "string", "required": false, "description": {"zh": "开始日期，YYYY-MM-DD", "en": "Start date, YYYY-MM-DD"}},
        {"name": "to_date", "type": "string", "required": false, "description": {"zh": "结束日期，YYYY-MM-DD", "en": "End date, YYYY-MM-DD"}},
        {"name": "enable_image_understanding", "type": "boolean", "required": false, "description": {"zh": "让 Grok 也看帖子里的图片，默认不看", "en": "Let Grok also look at images in posts; off by default"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-4.3", "en": "Optional, default grok-4.3"}}
      ]
    }
  ]
}
*/

function invokeGrokSearch(mode, params) {
  const root = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof NativeInterface === "undefined" ||
      typeof NativeInterface.executeGrokSearchAsync !== "function") {
    throw new Error("当前 Operit 版本不支持 Grok 搜索");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) throw new Error("无法确认 Grok 搜索工具调用身份");
  return new Promise((resolve, reject) => {
    const callbackId = "__operit_grok_search_" + Date.now() + "_" +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      delete root[callbackId];
      if (isError) {
        reject(new Error(String(resultJson || "Grok 搜索失败")));
        return;
      }
      try {
        resolve(JSON.parse(String(resultJson || "{}")));
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeGrokSearchAsync(callId, callbackId, mode, JSON.stringify(params));
    } catch (error) {
      delete root[callbackId];
      reject(error);
    }
  });
}

/** Array parameters may arrive as a JSON string or one plain value; the host wants arrays. */
function normalizeArrays(params, names) {
  const request = Object.assign({}, params || {});
  names.forEach((name) => {
    const value = request[name];
    if (typeof value !== "string") return;
    const text = value.trim();
    if (!text) {
      delete request[name];
    } else if (text.startsWith("[")) {
      try {
        request[name] = JSON.parse(text);
      } catch (error) {
        throw new Error(name + " 要写成数组，例如 [\"a\", \"b\"]");
      }
    } else {
      request[name] = [text];
    }
  });
  ["enable_image_search", "enable_image_understanding"].forEach((name) => {
    if (typeof request[name] === "string") request[name] = request[name].trim().toLowerCase() === "true";
  });
  return request;
}

/** The results as readable text: each query's summary, then its numbered sources. */
function formatSearches(result) {
  const parts = [];
  (result.searches || []).forEach((search) => {
    const lines = ["## " + search.query];
    if (search.error) {
      lines.push("搜索失败：" + search.error);
    } else {
      if (search.summary) lines.push(search.summary);
      const sources = search.results || [];
      if (sources.length) {
        lines.push("", "来源：");
        sources.forEach((source, index) => {
          lines.push((index + 1) + ". " + (source.title ? source.title + " - " : "") + source.url);
        });
      } else {
        lines.push("", "（这次没有返回来源链接）");
      }
    }
    parts.push(lines.join("\n"));
  });
  return parts.join("\n\n");
}

async function run(mode, params, arrays) {
  try {
    const result = await invokeGrokSearch(mode, normalizeArrays(params, arrays));
    complete({
      success: !!result.success,
      message: formatSearches(result),
      data: result
    });
  } catch (error) {
    complete({
      success: false,
      message: "Grok 搜索失败：" + (error && error.message ? error.message : String(error))
    });
  }
}

exports.web_search = async (params) => {
  await run("web", params, ["queries", "allowed_domains", "excluded_domains"]);
};

exports.x_search = async (params) => {
  await run("x", params, ["queries", "allowed_x_handles", "excluded_x_handles"]);
};
