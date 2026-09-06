from __future__ import annotations

import json
import re
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PLUGIN = REPO_ROOT / "examples" / "reading_companion"


class ReadingCompanionDiscoveryTest(unittest.TestCase):
    def test_ui_polling_preserves_terminal_task_envelope_and_result(self) -> None:
        subprocess.run(
            [
                "node", "-e",
                """
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const sandbox = { exports: {}, setTimeout: callback => callback() };
vm.runInNewContext(fs.readFileSync(process.argv[1], "utf8"), sandbox);
const task = { task_id: "task-1", status: "completed",
  result: { status: "completed", completedCount: 2, targetChapterIndices: [0, 1] } };
const wrap = value => ({ success: true, data: JSON.stringify({ success: true, data: value }) });
const history = require(process.argv[2]);
assert.equal(history.unwrapToolResult(wrap(task)).task_id, "task-1");
let calls = [];
const ctx = {
  usePackage: async () => {},
  callTool: async (name, params) => {
    calls.push([name, params]);
    return wrap(name.endsWith("start_task")
      ? { task_id: "task-1", status: "queued" } : task);
  },
};
(async () => {
  const result = await sandbox.runGenerationTask(ctx, { kind: "summary", count: 2 });
  assert.equal(result.completedCount, 2);
  assert.equal(calls.length, 2);
  assert.equal(calls[1][1].task_id, "task-1");
  ctx.callTool = async () => wrap({ ...task, status: "cancelled",
    result: { completedCount: 1, status: "stopped" } });
  assert.equal((await sandbox.runGenerationTask(ctx, {})).status, "stopped");
  ctx.callTool = async () => wrap({ task_id: "task-1", status: "interrupted" });
  await assert.rejects(sandbox.runGenerationTask(ctx, {}), /interrupted/);
})().catch(error => { console.error(error); process.exitCode = 1; });
""",
                str(PLUGIN / "ui/reading_companion_entry/index.ui.js"),
                str(PLUGIN / "ui/history_shared.js"),
            ],
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
