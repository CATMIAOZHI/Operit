// Keep native-engine fixtures in sync with the actual on-demand package output.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "../..");
const source = fs.readFileSync(path.join(root, "examples/dynamic_ui.js"), "utf8");
const fixtureDir = path.join(root, "app/src/test/resources/dynamicui");
let result;
const sandbox = {exports: {}, complete: value => { result = value; }};
vm.runInNewContext(source, sandbox);
(async () => {
    for (const name of ["calculator", "cost_calculator", "charts"]) {
        await sandbox.exports.read_example({name});
        assert.equal(result.success, true);
        const document = result.message.split("```a2ui\n")[1].split("\n```")[0] + "\n";
        document.trim().split("\n").forEach(line => JSON.parse(line));
        const fixture = path.join(fixtureDir, name + ".ndjson");
        if (process.argv.includes("--update")) {
            fs.mkdirSync(fixtureDir, {recursive: true});
            fs.writeFileSync(fixture, document);
        } else assert.equal(fs.readFileSync(fixture, "utf8"), document, `${name} fixture differs`);
    }
    assert.equal(fs.readFileSync(path.join(root, "app/src/main/assets/packages/dynamic_ui.js"), "utf8"), source);
    console.log("Package examples and native fixtures match.");
})().catch(error => { console.error(error); process.exitCode = 1; });
