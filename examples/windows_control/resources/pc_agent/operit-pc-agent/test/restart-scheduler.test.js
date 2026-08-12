const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const test = require("node:test");

const { createRestartScheduler } = require("../src/services/restart-service");

function nextTurn() {
  return new Promise((resolve) => setImmediate(resolve));
}

test("restart scheduler keeps the current agent alive when launcher spawn fails asynchronously", async () => {
  const shutdownSignals = [];
  const spawnCalls = [];
  let failSpawn = true;

  const scheduler = createRestartScheduler({
    projectRoot: "C:\\agent",
    scriptsDir: "C:\\agent\\scripts",
    logger: { info() {}, warn() {}, error() {} },
    shutdown(signal) {
      shutdownSignals.push(signal);
    },
    spawnProcess(executable, args, options) {
      spawnCalls.push({ executable, args, options });
      const child = new EventEmitter();
      child.pid = failSpawn ? null : 4321;
      child.unref = () => {};
      queueMicrotask(() => {
        if (failSpawn) {
          child.emit("error", new Error("spawn ENOENT"));
        } else {
          child.emit("spawn");
        }
      });
      return child;
    },
    schedule(callback) {
      queueMicrotask(callback);
      return 1;
    },
    env: {},
    delayMs: 0
  });

  const firstAttempt = scheduler("test.async_failure");
  const duplicateAttempt = scheduler("test.duplicate_waiter");
  await assert.rejects(firstAttempt, /spawn ENOENT/);
  await assert.rejects(duplicateAttempt, /spawn ENOENT/);
  await nextTurn();
  assert.deepEqual(shutdownSignals, []);
  assert.equal(spawnCalls.length, 1);

  failSpawn = false;
  assert.equal(await scheduler("test.retry"), true);
  await nextTurn();
  assert.deepEqual(shutdownSignals, ["RESTART"]);
  assert.equal(spawnCalls.length, 2);
  assert.equal(spawnCalls[1].executable, "powershell.exe");
  assert.equal(spawnCalls[1].options.detached, true);
});
