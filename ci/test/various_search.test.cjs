const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const root = path.resolve(__dirname, '../..');
const code = fs.readFileSync(path.join(root, 'app/src/main/assets/packages/various_search.js'), 'utf8');
function fixture(links) {
  const calls = [];
  const context = {
    exports: {}, console,
    Tools: { Net: { visit: async params => {
      calls.push(params);
      assert.ok(params.url, 'Search must not eagerly visit result pages');
      return { visitKey: 'cached-search', links, content: 'Useful search summary' };
    } } }
  };
  vm.runInNewContext(code, context);
  return { tools: context.exports, calls };
}
test('combined default preserves summaries without visiting results', async () => {
  const { tools, calls } = fixture(['a', 'b', 'c'].map(host =>
    ({ text: `Result ${host}`, url: `https://${host}.example/article` })));
  const results = await tools.combined_search({ query: 'test', platforms: 'bing,baidu' });
  assert.equal(calls.length, 2);
  assert.ok(results.every(result => result.content.includes('Useful search summary')));
  assert.ok(results.every(result => !result.content.includes('https://a.example')));
});
test('one result is retained with original cached index and summary', async () => {
  const { tools, calls } = fixture([
    { text: 'Home', url: 'https://cn.bing.com/' },
    { text: 'Useful result', url: 'https://example.org/article' }
  ]);
  const result = await tools.search_bing({ query: 'test', includeLinks: true });
  assert.match(result.content, /\[2\] Useful result - https:\/\/example.org\/article/);
  assert.match(result.content, /Useful search summary/);
  assert.equal(calls.length, 1);
});
test('same-host redirect wrappers remain available for on-demand visits', async () => {
  const { tools, calls } = fixture([1, 2, 3, 4].map(id =>
    ({ text: `Result ${id}`, url: `https://www.baidu.com/link?url=${id}` })));
  const result = await tools.search_baidu({ query: 'test', includeLinks: true });
  for (const id of [1, 2, 3, 4]) assert.ok(result.content.includes(`[${id}] Result ${id}`));
  assert.match(result.content, /Useful search summary/);
  assert.equal(calls.length, 1);
});
