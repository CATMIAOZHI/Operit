/* METADATA
{
  "name": "deepseek_search",
  "display_name": {"zh": "DeepSeek 搜索", "en": "DeepSeek Search"},
  "description": {
    "zh": "使用 DeepSeek 的网页搜索与网页抓取 API。需在本包环境变量中设置 DEEPSEEK_API_KEY，可与 DeepSeek 模型使用同一个 Key。",
    "en": "Search the web and read pages through DeepSeek's API. Set DEEPSEEK_API_KEY in this package; it can be the same key as your DeepSeek model."
  },
  "category": "Search",
  "enabledByDefault": false,
  "env": [
    {
      "name": "DEEPSEEK_API_KEY",
      "description": {"zh": "DeepSeek API Key；可与 DeepSeek 模型使用同一个 Key", "en": "DeepSeek API key; you can use the same key as the DeepSeek model"},
      "required": true
    }
  ],
  "tools": [
    {
      "name": "search",
      "description": {
        "zh": "搜索网页，按相关度从高到低返回标题、链接、日期和摘要。支持 site:、-site:、\"精确短语\"、lang:、after:、before: 写法。",
        "en": "Search the web; results come best first with title, URL, date and summary. Supports site:, -site:, \"exact phrase\", lang:, after: and before:."
      },
      "parameters": [
        {"name": "query", "type": "string", "required": true, "description": {"zh": "搜索词，最多 128 个字", "en": "Search query, at most 128 characters"}},
        {"name": "max_results", "type": "number", "required": false, "default": 8, "description": {"zh": "返回 1 到 20 条，默认 8 条", "en": "Return 1 to 20 results; defaults to 8"}},
        {"name": "question", "type": "string", "required": false, "description": {"zh": "用户原本的问题（最多 128 个字），帮助判断哪些结果更相关", "en": "The user's original question (at most 128 characters), to rank results better"}},
        {"name": "full_text", "type": "boolean", "required": false, "default": false, "description": {"zh": "是否同时返回网页正文，默认否；只需要读一两页时用 fetch_page 更省", "en": "Also return page text; defaults to false. To read one or two pages, fetch_page costs less"}}
      ]
    },
    {
      "name": "fetch_page",
      "description": {"zh": "通过 DeepSeek 读取指定网页，返回标题和 Markdown 正文。", "en": "Read a web page through DeepSeek; returns its title and Markdown text."},
      "parameters": [
        {"name": "url", "type": "string", "required": true, "description": {"zh": "要读取的网页 URL（http 或 https）", "en": "Page URL to read (http or https)"}}
      ]
    }
  ]
}
*/

const client = OkHttp.newClient();
const MAX_TEXT = 24000;
const MAX_FULL_TEXT = 6000;
const GOOD_SCORE = 1.5;

function apiKey() {
  const key = String(getEnv("DEEPSEEK_API_KEY") || "").trim();
  if (!key) throw new Error("请先在 DeepSeek 搜索包中设置 DEEPSEEK_API_KEY");
  return key;
}

async function request(endpoint, body) {
  const key = apiKey();
  const response = await client.newRequest()
    .url("https://api.deepseek.com/v1/tools/" + endpoint)
    .method("POST")
    .headers({
      "Authorization": "Bearer " + key,
      "Content-Type": "application/json",
      "Accept": "application/json"
    })
    .body(JSON.stringify(body), "json")
    .build()
    .execute();
  if (!response.isSuccessful()) {
    let detail = "";
    try {
      const error = JSON.parse(response.content || "{}");
      detail = String((error.error && error.error.message) || error.message || "");
    } catch (_) {
      detail = "";
    }
    detail = detail.split(key).join("***").slice(0, 200);
    const status = response.statusCode;
    const hint = status === 401 ? "，API Key 无效"
      : status === 402 ? "，账户余额不足，充值后再试"
      : status === 403 ? "，这个网址被安全策略拒绝，换一个网址"
      : status === 404 ? "，读不到这个网页的正文，换一个网址"
      : status === 429 ? "，请求太频繁，稍后再试"
      : status === 408 || status >= 500 ? "，服务暂时出错，稍后再试"
      : "";
    throw new Error("DeepSeek 请求失败（HTTP " + response.statusCode + hint + "）" + (detail ? "：" + detail : ""));
  }
  return JSON.parse(response.content);
}

function text(value, limit) {
  return String(value == null ? "" : value).slice(0, limit);
}

function score(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

exports.search = async (params) => {
  try {
    const query = String(params && params.query || "").trim();
    if (!query) throw new Error("请提供搜索词");
    if (query.length > 128) throw new Error("搜索词最多 128 个字，请缩短");
    const count = (params && params.max_results) == null ? 8 : Number(params.max_results);
    if (!Number.isInteger(count) || count < 1 || count > 20) {
      throw new Error("max_results 必须是 1 到 20 的整数");
    }
    const fullText = params && (params.full_text === true || params.full_text === "true");
    const body = { query, count, need_content: !!fullText };
    const question = String(params && params.question || "").trim();
    if (question) body.ori_question = question.slice(0, 128);
    const payload = await request("search", body);
    if (!Array.isArray(payload.results)) throw new Error("DeepSeek 返回的搜索结果格式无效");
    const results = payload.results
      .map((item) => ({
        title: text(item.title, 300),
        url: text(item.url, 2048),
        site: text(item.site_name, 100),
        date: text(item.date, 40),
        score: Math.round(score(item.rerank_score) * 100) / 100,
        summary: text(item.summary || item.snippet, 3000),
        content: fullText ? text(item.content, MAX_FULL_TEXT) : ""
      }))
      .sort((a, b) => b.score - a.score)
      .slice(0, count);
    const good = results.filter((item) => item.score >= GOOD_SCORE).length;
    const message = results.length
      ? "共 " + results.length + " 条，按相关度排序（分数 0 到 3，" + GOOD_SCORE + " 以上较可靠，" + good + " 条达到）。\n\n" +
        results.map((item, index) =>
          (index + 1) + ". " + item.title + "（" + item.score + "）\n" + item.url +
          (item.site || item.date ? "\n" + [item.site, item.date].filter(Boolean).join(" · ") : "") +
          "\n" + item.summary +
          (item.content ? "\n\n正文：\n" + item.content : "")
        ).join("\n\n")
      : "没有找到搜索结果，可以换个说法或去掉限制条件再搜";
    complete({ success: true, message, data: { results } });
  } catch (error) {
    complete({ success: false, message: "DeepSeek 搜索失败：" + (error.message || String(error)) });
  }
};

exports.fetch_page = async (params) => {
  try {
    const url = String(params && params.url || "").trim();
    if (!url) throw new Error("请提供网页 URL");
    if (!/^https?:\/\//i.test(url)) throw new Error("URL 必须以 http:// 或 https:// 开头");
    if (url.length > 8192) throw new Error("URL 太长（最多 8192 个字符）");
    const payload = await request("fetch", { url });
    const full = String(payload.content == null ? "" : payload.content);
    const page = {
      title: text(payload.title, 300),
      content: full.slice(0, MAX_TEXT),
      truncated: full.length > MAX_TEXT
    };
    complete({
      success: true,
      message: page.title + "\n" + url + "\n\n" + (page.content || "（这个网页没有可读的正文）") +
        (page.truncated ? "\n\n…正文太长，只返回了前 " + MAX_TEXT + " 个字（共 " + full.length + " 个字）。" : ""),
      data: page
    });
  } catch (error) {
    complete({ success: false, message: "DeepSeek 网页读取失败：" + (error.message || String(error)) });
  }
};
