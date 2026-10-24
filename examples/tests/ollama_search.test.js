const assert = require("node:assert/strict");
const test = require("node:test");

const requests = [];
let apiKey = "test-key";
let result;

global.getEnv = () => apiKey;
global.complete = (value) => { result = value; };
global.OkHttp = {
  newClient: () => ({
    newRequest: () => {
      const request = {};
      return {
        url(value) { request.url = value; return this; },
        method(value) { request.method = value; return this; },
        headers(value) { request.headers = value; return this; },
        body(value) { request.body = JSON.parse(value); return this; },
        build() { return this; },
        async execute() {
          requests.push(request);
          return {
            isSuccessful: () => true,
            content: request.url.endsWith("web_search")
              ? JSON.stringify({ results: [{ title: "Example", url: "https://example.com", content: "Found" }] })
              : JSON.stringify({ title: "Example", content: "Page text", links: ["https://example.com/next"] })
          };
        }
      };
    }
  })
};

const ollamaSearch = require("../ollama_search.js");

test("web search sends authenticated Ollama request and preserves source URLs", async () => {
  await ollamaSearch.search({ query: "operit", max_results: 3 });

  assert.equal(requests[0].url, "https://ollama.com/api/web_search");
  assert.equal(requests[0].method, "POST");
  assert.equal(requests[0].headers.Authorization, "Bearer test-key");
  assert.deepEqual(requests[0].body, { query: "operit", max_results: 3 });
  assert.equal(result.success, true);
  assert.equal(result.data.results[0].url, "https://example.com");
});

test("page fetch uses Ollama web_fetch", async () => {
  await ollamaSearch.fetch_page({ url: "https://example.com" });

  assert.equal(requests[1].url, "https://ollama.com/api/web_fetch");
  assert.deepEqual(requests[1].body, { url: "https://example.com" });
  assert.equal(result.data.content, "Page text");
});

test("missing key fails before sending a request", async () => {
  apiKey = "";
  await ollamaSearch.search({ query: "operit" });

  assert.equal(result.success, false);
  assert.equal(requests.length, 2);
});
