from __future__ import annotations

import json
import re
import subprocess
import sqlite3
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PLUGIN = REPO_ROOT / "examples" / "reading_companion"


class ReadingCompanionDiscoveryTest(unittest.TestCase):
    def test_task_schema_upgrades_old_runs_and_archive_remains_queryable(self) -> None:
        directory = REPO_ROOT / "app/src/main/java/com/ai/assistance/operit/features/reading"
        repository = (directory / "ReadingTaskRepository.kt").read_text(encoding="utf-8")
        store = (directory / "ReadingCompanionStore.kt").read_text(encoding="utf-8")
        schema = repository[repository.index("fun createTables"):]
        schema += store[store.index("private fun createTaskTables"):store.index("private fun createTaskTables") + 900]
        with sqlite3.connect(":memory:") as db:
            db.execute("""CREATE TABLE auto_comment_runs (
                id INTEGER PRIMARY KEY, chapter_index INTEGER, chapter_title TEXT,
                status TEXT, stage TEXT, subagent_run_id TEXT, child_chat_id TEXT,
                actual_input_tokens INTEGER, actual_output_tokens INTEGER,
                started_at INTEGER, finished_at INTEGER)""")
            db.execute("INSERT INTO auto_comment_runs(id, chapter_index, status, started_at) VALUES(1, 2, 'generated', 1)")
            db.execute("ALTER TABLE auto_comment_runs ADD COLUMN task_id TEXT")
            statements = re.findall(r'db.execSQL\((?:"""(.*?)"""|"([^"\n]*)")\)', schema, re.S)
            self.assertGreaterEqual(len(statements), 5)
            for triple, plain in statements:
                db.execute(triple or plain)
            self.assertEqual((2, "generated"), db.execute("SELECT chapter_index, status FROM auto_comment_runs WHERE id=1").fetchone())
            db.execute("UPDATE auto_comment_runs SET task_id='task-1' WHERE id=1")
            archive = re.search(r'db.execSQL\("""(INSERT OR REPLACE INTO reading_task_attempts.*?)"""', store, re.S)[1]
            db.execute(archive.replace("$selection", "id = 1"))
            db.execute("DELETE FROM auto_comment_runs WHERE id=1")
            query = re.search(r'db.rawQuery\("""(.*?FROM reading_task_attempts.*?)"""', repository, re.S)[1]
            attempts = db.execute(query, ("task-1", "task-1")).fetchall()
            self.assertEqual(1, len(attempts))
            self.assertEqual(2, attempts[0][1])
            self.assertEqual(1, attempts[0][-1])

    def test_ui_polling_preserves_terminal_task_envelope_and_result(self) -> None:
        subprocess.run(
            [
                "node", "-e",
                """
const assert = require("node:assert/strict");
const client = require(process.argv[1]);
const task = { task_id: "task-1", status: "completed",
  result: { status: "completed", completedCount: 2, targetChapterIndices: [0, 1] } };
const wrap = value => ({ success: true, data: JSON.stringify({ success: true, data: value }) });
let calls = [];
const ctx = { callTool: async (name, params) => {
  calls.push([name, params]); return wrap(name.endsWith("start_task") ? { task_id: "task-1", status: "queued" } : task);
} };
(async () => {
  assert.equal((await client.tasks.start(ctx, { kind: "summary", count: 2 })).status, "queued");
  assert.equal((await client.tasks.get(ctx, "task-1")).result.completedCount, 2);
  assert.equal(calls.length, 2);
  assert.equal(calls[1][1].task_id, "task-1");
  ctx.callTool = async () => { calls.push("failure"); return { success: false, error: "business failure" }; };
  await assert.rejects(client.tasks.start(ctx, {}), /business failure/);
  assert.equal(calls.length, 3, "business failure must not retry through another package");
  let release, updates = 0;
  ctx.callTool = () => new Promise(resolve => { release = resolve; });
  const pending = client.watch(ctx, "test", () => updates++);
  await new Promise(resolve => setImmediate(resolve));
  client.pause("test"); release({ tasks: [task] }); await pending;
  assert.equal(updates, 0, "paused page must not receive late results");
  await client.watch(ctx, "test", () => updates++);
  assert.equal(updates, 0);
})().catch(error => { console.error(error); process.exitCode = 1; });
""",
                str(PLUGIN / "ui/reading_client.js"),
            ],
            check=True, capture_output=True, text=True, timeout=30,
        )

    def test_resuming_page_keeps_drafts_and_binds_controls_to_loaded_book(self) -> None:
        subprocess.run(
            ["node", "-e", r"""
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const sandbox = { exports: {}, getLang: () => "en", setTimeout, require: require("node:module").createRequire(process.argv[1]) };
vm.runInNewContext(fs.readFileSync(process.argv[1], "utf8"), sandbox);
const states = new Map();
let bookId = "A", failBook = false, releasePrefs, holdPrefs = false;
let calls = [];
const modifier = new Proxy({}, { get: () => () => modifier });
const ctx = {
  Modifier: modifier,
  MaterialTheme: { colorScheme: {} },
  UI: new Proxy({}, { get: (_, type) => (props, children) => ({ type, props, children }) }),
  useState: (key, initial) => {
    if (!states.has(key)) states.set(key, initial);
    return [states.get(key), value => states.set(key, value)];
  },
  isPackageImported: async name => name === "reading_companion",
  getReadingCompanionCommentaryCharacter: async id => ({ name: id }),
  callTool: async (name, params) => {
    calls.push([name, params]);
    if (name.endsWith(":get_current_book")) {
      if (failBook) throw new Error("reader unavailable");
      return { bookId, bookName: bookId, currentChapterNumber: 10 };
    }
    if (name.endsWith(":summary_batch_prefs")) {
      assert.equal(params.book_id, bookId);
      if (holdPrefs) await new Promise(resolve => { releasePrefs = resolve; });
      return { startChapter: 1, endChapter: 5, budget: 20 };
    }
    return {};
  },
};
const render = () => sandbox.exports.default(ctx);
const flatten = node => !node ? [] : Array.isArray(node)
  ? node.flatMap(flatten) : [node, ...flatten(node.children)];
(async () => {
  await render().props.onLoad();
  const nodes = flatten(render());
  assert.ok(nodes.some(node => String(node.props && node.props.text).includes("Official Legado and other unadapted builds are not supported")));
  const download = nodes.find(node => node.type === "OutlinedButton" &&
    flatten(node.children).some(child => child.props && child.props.text === "Download our Legado build"));
  assert.ok(download);
  await download.props.onClick();
  assert.ok(calls.some(([name, params]) => name === "execute_intent" &&
    params.action === "android.intent.action.VIEW" && params.uri === "https://github.com/CATMIAOZHI/legado/releases/latest"));
  const selectPage = label => flatten(render()).find(node => node.type === "Tab" &&
    flatten(node.children).some(child => child.props && child.props.text === label)).props.onClick();
  assert.equal(flatten(render()).filter(node => node.type === "TextField").length, 0);
  selectPage("Generate");
  assert.ok(flatten(render()).some(node => node.type === "TextField"));
  states.set("batchSummaryStart", "3");
  states.set("batchSummaryEnd", "9");
  selectPage("Settings");
  assert.ok(!flatten(render()).some(node => String(node.props && node.props.onClick).includes("runManualBatch")));
  selectPage("Generate");
  assert.equal(states.get("batchSummaryEnd"), "9");
  await render().props.onResume();
  assert.equal(states.get("batchSummaryStart"), "3");
  assert.equal(states.get("batchSummaryEnd"), "9");
  bookId = "B"; holdPrefs = true;
  const pending = render().props.onResume();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(states.get("readingState").bookId, "A");
  assert.equal(states.get("loading"), false);
  const generation = flatten(render()).filter(node =>
    String(node.props && node.props.onClick).includes("runManualBatch"));
  assert.ok(generation.length > 0);
  assert.ok(generation.every(node => node.props.enabled === false));
  releasePrefs(); await pending; holdPrefs = false;
  assert.equal(states.get("readingState").bookId, "B");
  assert.equal(states.get("batchSummaryStart"), "1");
  assert.equal(states.get("batchSummaryEnd"), "5");
  failBook = true; calls = [];
  await render().props.onResume();
  assert.equal(states.get("readingState").bookId, "B");
  assert.equal(states.get("batchSummaryEnd"), "5");
  assert.ok(!calls.some(([name]) => name.endsWith(":summary_batch_prefs")));
  assert.match(states.get("error"), /reader unavailable/);
  assert.equal(states.get("refreshing"), false);
  render().props.onPause();
})().catch(error => { console.error(error); process.exitCode = 1; });
""", str(PLUGIN / "ui/reading_companion_entry/index.ui.js")],
            check=True, capture_output=True, text=True, timeout=30,
        )

    def test_subpackages_expose_disjoint_executable_contracts(self) -> None:
        names = set()
        for path in (PLUGIN / "packages").glob("*.js"):
            source = path.read_text(encoding="utf-8")
            metadata = json.loads(re.search(r"/\* METADATA\s*(.*?)\*/", source, re.S)[1])
            tools = [tool for tool in metadata["tools"] if not tool.get("advice")]
            declared = {tool["name"] for tool in tools}
            self.assertEqual(declared, set(re.findall(r"exports\.(\w+)\s*=", source)))
            self.assertFalse(names & declared, f"Duplicate tools in {path.name}")
            names.update(declared)
            if metadata["name"] == "reading_companion":
                self.assertNotIn("manual_batch_summaries", declared)
                self.assertNotIn("list_audit_chats", declared)
            if metadata["name"] == "reading_companion_tasks":
                self.assertEqual({"start_task", "get_task", "cancel_task", "list_tasks"}, declared)
                params = {p["name"] for tool in tools for p in tool["parameters"]}
                self.assertNotIn("batch_id", params)
                self.assertNotIn("start_chapter_index", params)

    def test_enabled_plugin_registers_ui_without_eager_prompt_injection(self) -> None:
        # Execute the real bootstrap with an imported package, which previously registered
        # a hook that appended the full policy to every ordinary system prompt.
        subprocess.run(
            [
                "node",
                "-e",
                """
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const calls = { routes: [], navigation: [], hooks: [] };
const sandbox = {
  exports: {},
  require: () => ({}),
  Icons: { Book: "book" },
  NativeInterface: { isPackageImported: () => true },
  ToolPkg: {
    registerUiRoute: value => calls.routes.push(value),
    registerNavigationEntry: value => calls.navigation.push(value),
    registerSystemPromptComposeHook: value => calls.hooks.push(value),
  },
};
vm.runInNewContext(fs.readFileSync(process.argv[1], "utf8"), sandbox);
assert.equal(sandbox.exports.registerToolPkg(), true);
assert.equal(calls.routes.length, 6);
assert.equal(calls.navigation[0].surface, "main_sidebar_plugins");
assert.equal(calls.hooks.length, 0);
""",
                str(PLUGIN / "main.js"),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def test_full_guidance_is_advice_alongside_loadable_tool_interfaces(self) -> None:
        package_file = PLUGIN / "packages" / "reading_companion.js"
        source = package_file.read_text(encoding="utf-8")
        metadata = json.loads(re.search(r"/\* METADATA\s*(.*?)\*/", source, re.S)[1])
        advice = [tool for tool in metadata["tools"] if tool.get("advice")]
        self.assertEqual(1, len(advice))
        self.assertEqual([], advice[0]["parameters"])
        for language in ("zh", "en"):
            guidance = advice[0]["description"][language]
            # These details belong to use_package's advice response, not discovery.
            self.assertIn("reading_companion:get_context", guidance)
            self.assertIn("safeSearchPaths", guidance)
            self.assertIn("ai-memory.md", guidance)
            self.assertNotIn("safeSearchPaths", metadata["description"][language])
        executable_names = [
            tool["name"] for tool in metadata["tools"] if not tool.get("advice")
        ]
        subprocess.run(
            [
                "node",
                "-e",
                """
const assert = require("node:assert/strict");
const tools = require(process.argv[1]);
for (const name of JSON.parse(process.argv[2])) {
  assert.equal(typeof tools[name], "function", name);
}
assert.equal(tools.usage_advice, undefined);
""",
                str(package_file),
                json.dumps(executable_names),
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )


if __name__ == "__main__":
    unittest.main()
