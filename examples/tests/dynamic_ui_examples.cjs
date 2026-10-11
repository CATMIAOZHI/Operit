const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "../..");
const source = fs.readFileSync(path.join(root, "examples/dynamic_ui.js"), "utf8");
let result, checked;
let report = {valid:true,reference:"demo",message:"```a2ui-ref\ndemo\n```"};
const sandbox = {
    exports: {}, __operitCurrentCallId:"host-call", complete:value => { result = value; },
    NativeInterface: {
        dynamicUiGuide:() => "NATIVE SIGNATURES",
        prepareDynamicUi:(callId, source) => {
            assert.equal(callId, "host-call");
            checked = source;
            return JSON.stringify(report);
        },
    },
};
vm.runInNewContext(source, sandbox);
(async () => {
    await sandbox.exports.read_guide();
    assert.ok(result.message.endsWith("NATIVE SIGNATURES"));
    for (const name of ["calculator","cost_calculator","charts","form","info","stats","progress"]) {
        await sandbox.exports.read_example({name});
        assert.equal(result.success, true);
        assert.equal(result.valid, true);
        assert.ok(result.message.includes("a2ui-ref"));
        assert.ok(!result.message.includes("root ="), "Do not ask the model to copy source again");
        const file = path.join(root, "app/src/test/resources/dynamicui", name + ".express");
        if (process.argv.includes("--update")) fs.writeFileSync(file, checked);
        else assert.equal(fs.readFileSync(file, "utf8"), checked);
    }
    await sandbox.exports.prepare_card({source:'root = Text(text="hello")'});
    assert.equal(checked, 'root = Text(text="hello")');
    report = {valid:false,code:"invalid_express",line:3,column:5,message:"Expected ')'."};
    await sandbox.exports.prepare_card({source:'root = Text('});
    assert.equal(result.success, true);
    assert.equal(result.valid, false);
    assert.equal(result.line, 3);
    assert.equal(result.column, 5);
    assert.equal(result.code, "invalid_express");
    await sandbox.exports.prepare_card({source:42});
    assert.equal(result.code, "invalid_source");
    assert.equal(sandbox.exports.validate_card, undefined);
    assert.equal(fs.readFileSync(path.join(root, "app/src/main/assets/packages/dynamic_ui.js"),"utf8"), source);
    console.log("Express package fixtures and diagnostic/reference transport passed.");
})().catch(e => { console.error(e); process.exitCode = 1; });
