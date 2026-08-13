const path = require("path");
const { spawn } = require("child_process");

function createRestartScheduler({
  projectRoot,
  scriptsDir,
  logger,
  shutdown,
  spawnProcess = spawn,
  schedule = setTimeout,
  env = process.env,
  delayMs = 120
}) {
  let restartScheduled = false;
  let restartAttempt = null;

  return async function scheduleRestart(reason) {
    const restartReason = reason || "unknown";
    if (restartScheduled) {
      logger.warn("server.restart.duplicate_request", { reason: restartReason });
      if (restartAttempt) {
        await restartAttempt;
        return true;
      }
      return false;
    }

    restartScheduled = true;
    logger.info("server.restart.requested", { reason: restartReason });

    restartAttempt = new Promise((resolve, reject) => {
        schedule(() => {
          let child;
          try {
            const launcherScript = path.join(scriptsDir, "launch_agent.ps1");
            const psExe = env.SystemRoot
              ? path.join(env.SystemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
              : "powershell.exe";

            child = spawnProcess(
              psExe,
              ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launcherScript],
              {
                cwd: projectRoot,
                detached: true,
                stdio: "ignore",
                windowsHide: true
              }
            );

            let settled = false;
            child.once("error", (error) => {
              if (!settled) {
                settled = true;
                reject(error);
                return;
              }
              logger.error("server.restart.launcher_runtime_error", {
                reason: restartReason,
                error: error && error.message ? error.message : String(error)
              });
            });
            child.once("spawn", () => {
              if (settled) {
                return;
              }
              settled = true;
              child.unref();
              logger.info("server.restart.launcher_spawned", {
                launcherScript,
                pid: child.pid || null
              });
              resolve();
            });
          } catch (error) {
            reject(error);
          }
        }, delayMs);
      });

    try {
      await restartAttempt;
    } catch (error) {
      restartScheduled = false;
      restartAttempt = null;
      logger.error("server.restart.spawn_failed", {
        reason: restartReason,
        error: error && error.message ? error.message : String(error)
      });
      throw error;
    }

    restartAttempt = null;
    schedule(() => shutdown("RESTART"), delayMs);
    return true;
  };
}

module.exports = {
  createRestartScheduler
};
