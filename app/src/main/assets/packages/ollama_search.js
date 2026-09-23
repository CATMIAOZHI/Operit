/* METADATA
{
  "name": "ollama_search",
  "display_name": {"zh": "Ollama 搜索", "en": "Ollama Search"},
  "description": {
    "zh": "使用 Ollama 的云端网页搜索与网页读取 API。需在本包环境变量中设置 OLLAMA_API_KEY。",
    "en": "Search the web and read pages through Ollama's cloud API. Set OLLAMA_API_KEY in this package."
  },
  "category": "Search",
  "enabledByDefault": false,
  "env": [
    {
      "name": "OLLAMA_API_KEY",
      "description": {"zh": "Ollama API Key；可与 Ollama Cloud 提供商使用同一个 Key", "en": "Ollama API key; you can use the same key as the Ollama Cloud provider"},
      "required": true
    }
  ],
  "tools": [
    {
      "name": "search",
      "description": {"zh": "搜索网页，返回标题、链接和内容摘要。", "en": "Search the web and return titles, URLs, and content snippets."},
      "parameters": [
        {"name": "query", "type": "string", "required": true, "description": {"zh": "搜索词", "en": "Search query"}},
        {"name": "max_results", "type": "number", "required": false, "default": 5, "description": {"zh": "返回 1 到 10 条，默认 5 条", "en": "Return 1 to 10 results; defaults to 5"}}
      ]
    },
    {
      "name": "fetch_page",
      "description": {"zh": "通过 Ollama 读取指定网页的正文和链接。", "en": "Read a web page's text and links through Ollama."},
      "parameters": [
        {"name": "url", "type": "string", "required": true, "description": {"zh": "要读取的网页 URL", "en": "Page URL to read"}}
      ]
    }
  ]
}
*/

const client = OkHttp.newClient();
const MAX_TEXT = 24000;

async function request(endpoint, body) {
  const apiKey = String(getEnv("OLLAMA_API_KEY") || "").trim();
  if (!apiKey) throw new Error("请先在 Ollama 搜索包中设置 OLLAMA_API_KEY");
  const response = await client.newRequest()
    .url("https://ollama.com/api/" + endpoint)
    .method("POST")
    .headers({
      "Authorization": "Bearer " + apiKey,
      "Content-Type": "application/json",
      "Accept": "application/json"
    })
    .body(JSON.stringify(body), "json")
    .build()
    .execute();
  if (!response.isSuccessful()) {
    throw new Error("Ollama 请求失败（HTTP " + response.statusCode + "）");
  }
  return JSON.parse(response.content);
}

function text(value, limit) {
  return String(value == null ? "" : value).slice(0, limit);
}

exports.search = async (params) => {
  try {
    const query = String(params && params.query || "").trim();
    if (!query) throw new Error("请提供搜索词");
    const count = (params && params.max_results) == null ? 5 : Number(params.max_results);
    if (!Number.isInteger(count) || count < 1 || count > 10) {
      throw new Error("max_results 必须是 1 到 10 的整数");
    }
    const payload = await request("web_search", { query, max_results: count });
    if (!Array.isArray(payload.results)) throw new Error("Ollama 返回的搜索结果格式无效");
    const results = payload.results.slice(0, count).map((item) => ({
      title: text(item.title, 300),
      url: text(item.url, 2048),
      content: text(item.content, 3000)
    }));
    const message = results.length
      ? results.map((item, index) =>
          (index + 1) + ". " + item.title + "\n" + item.url + "\n" + item.content
        ).join("\n\n")
      : "没有找到搜索结果";
    complete({ success: true, message, data: { results } });
  } catch (error) {
    complete({ success: false, message: "Ollama 搜索失败：" + (error.message || String(error)) });
  }
};

exports.fetch_page = async (params) => {
  try {
    const url = String(params && params.url || "").trim();
    if (!url) throw new Error("请提供网页 URL");
    const payload = await request("web_fetch", { url });
    const page = {
      title: text(payload.title, 300),
      content: text(payload.content, MAX_TEXT),
      links: Array.isArray(payload.links) ? payload.links.slice(0, 30).map((link) => text(link, 2048)) : []
    };
    complete({
      success: true,
      message: page.title + "\n" + url + "\n\n" + page.content +
        (page.links.length ? "\n\n链接：\n" + page.links.join("\n") : ""),
      data: page
    });
  } catch (error) {
    complete({ success: false, message: "Ollama 网页读取失败：" + (error.message || String(error)) });
  }
};
