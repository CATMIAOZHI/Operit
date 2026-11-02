const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const sourceDir = path.resolve(__dirname, "../../app/src/main/java/com/ai/assistance/operit/core/tools/javascript");
function script(file, name) {
    const source = fs.readFileSync(path.join(sourceDir, file), "utf8");
    const start = source.indexOf(`fun ${name}(`);
    assert.ok(start >= 0);
    const match = source.slice(start).match(/return """([\s\S]*?)"""\.trimIndent\(\)/);
    assert.ok(match);
    return match[1];
}

function runtime() {
    const failures = [];
    const context = vm.createContext({
        clearTimeout() {},
        setTimeout() { return 1; },
        NativeInterface: {
            setCallError(id, error) { failures.push([id, error]); },
            setCallResult() {},
            getEnvForCall() { return ""; },
        },
    });
    vm.runInContext(script("JsInitRuntimeScriptBuilder.kt", "buildRuntimeExposeScript"), context);
    vm.runInContext(script("JsInitRuntimeScriptBuilder.kt", "buildRuntimeCallRegistryScript"), context);
    const prelude = script("JsExecutionScriptBuilder.kt", "buildExecutionPreludeSource");
    const execution = script("JsExecutionScriptBuilder.kt", "buildExecutionRuntimeBridgeScript")
        .replaceAll("$TOOLPKG_EXECUTION_ENTRY_FUNCTION", "__testExecute")
        .replace("$preludeSource", JSON.stringify(prelude));
    vm.runInContext(execution, context);
    function start(id) {
        context.__testExecute(id, {}, "exports.wait = function() { return new Promise(function() {}); };", "wait", null, null, null);
        assert.deepEqual(failures, []);
        assert.ok(context.__operitGetCallState(id));
    }
    return { context, start };
}

test("cancelled calls remove registry and current runtime across repeated engine reuse", () => {
    const { context, start } = runtime();
    for (let i = 0; i < 200; i++) {
        const id = `call-${i}`;
        start(id);
        assert.equal(context.__operitCancelCallSession(id), true);
        assert.equal(context.__operitGetCallState(id), null);
        assert.equal(context.__operitCurrentCallId, "");
        assert.equal(context.__operit_call_runtime_ref, undefined);
        assert.equal(context.__operitCancelCallSession(id), false);
    }
    assert.equal(Object.keys(context.__operitCallRegistry).length, 0);
});

test("finishing a newer call does not restore an older cancelled runtime", () => {
    const { context, start } = runtime();
    start("older");
    start("newer");
    context.__operitCancelCallSession("older");
    assert.ok(context.__operitGetCallState("newer"));
    context.__operitCancelCallSession("newer");
    assert.equal(context.__operit_call_runtime_ref, undefined);
    assert.equal(context.__operitCurrentCallId, "");
    assert.equal(Object.keys(context.__operitCallRegistry).length, 0);
});
