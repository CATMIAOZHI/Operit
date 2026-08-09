const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { Readable } = require("node:stream");
const test = require("node:test");

const { createApiHandler } = require("../src/handlers/api-handler");

function createResponse() {
  return {
    statusCode: null,
    headers: null,
    body: "",
    writeHead(statusCode, headers) {
      this.statusCode = statusCode;
      this.headers = headers;
    },
    end(body = "") {
      this.body += String(body);
    }
  };
}

async function callApi(handler, method, pathname, body) {
  const chunks = body === undefined ? [] : [Buffer.from(JSON.stringify(body), "utf8")];
  const req = Readable.from(chunks);
  req.method = method;
  const res = createResponse();

  const handled = await handler.handleApiRequest(req, res, new URL(`http://localhost${pathname}`));
  return {
    handled,
    statusCode: res.statusCode,
    body: res.body ? JSON.parse(res.body) : null
  };
}

function createConfigHarness(options = {}) {
  const saved = [];
  const restartReasons = [];
  const startupState = {
    issueType: "bindAddressUnavailable",
    status: "pending"
  };
  const state = {
    config: {
      bindAddress: "127.0.0.1",
      port: 58321,
      maxCommandMs: 30000,
      allowedPresets: ["safe"],
      apiToken: "current-secret"
    }
  };

  const handler = createApiHandler({
    state,
    configStore: {
      ensureApiToken(value) {
        const token = String(value || "").trim();
        return token || "generated-secret";
      },
      normalizeAllowedPresets(value) {
        return value.filter((name) => name === "safe");
      },
      saveConfig(config) {
        if (options.failSave) {
          throw new Error("simulated save failure");
        }
        saved.push(JSON.parse(JSON.stringify(config)));
      }
    },
    startupStateStore: {
      loadState() {
        return startupState;
      },
      saveState(nextState) {
        if (options.failStartupStateSave) {
          throw new Error("simulated startup state failure");
        }
        Object.assign(startupState, nextState);
      }
    },
    restartAgent(reason) {
      restartReasons.push(reason);
      if (options.restartThrows) {
        throw new Error("simulated restart failure");
      }
      return options.restartResult !== false;
    },
    processService: {
      getNetworkSnapshot() {
        return { recommendedHost: "192.168.1.20" };
      }
    },
    fileService: {},
    logger: { info() {}, warn() {}, error() {} },
    presetCommands: {
      safe: { shell: "powershell", command: "echo safe", description: "safe" }
    },
    runtimeInfo: {},
    versionInfo: { agentVersion: "test" }
  });

  return { handler, state, saved, restartReasons, startupState };
}

async function importBrowserModule(relativePath) {
  const absolutePath = path.resolve(__dirname, "..", relativePath);
  const source = fs.readFileSync(absolutePath, "utf8");
  const url = `data:text/javascript;base64,${Buffer.from(source, "utf8").toString("base64")}`;
  return import(url);
}

function createHelpers() {
  return {
    setBusy() {},
    setNotice() {},
    setJsonOutput() {},
    asErrorMessage(error) {
      return error.message;
    }
  };
}

test("config API redacts the token, rejects unauthenticated mutation, and rotates only after current-token auth", async () => {
  const { handler, state, saved } = createConfigHarness();

  const readResult = await callApi(handler, "GET", "/api/config");
  assert.equal(readResult.handled, true);
  assert.equal(readResult.statusCode, 200);
  assert.equal(readResult.body.apiTokenConfigured, true);
  assert.equal(Object.hasOwn(readResult.body, "apiToken"), false);

  const rejected = await callApi(handler, "POST", "/api/config", {
    token: "wrong-secret",
    apiToken: "attacker-secret",
    port: 60000
  });
  assert.equal(rejected.statusCode, 401);
  assert.equal(state.config.apiToken, "current-secret");
  assert.equal(state.config.port, 58321);
  assert.equal(saved.length, 0);

  const ordinaryUpdate = await callApi(handler, "POST", "/api/config", {
    token: "current-secret",
    maxCommandMs: 45000
  });
  assert.equal(ordinaryUpdate.statusCode, 200);
  assert.equal(state.config.apiToken, "current-secret");
  assert.equal(state.config.maxCommandMs, 45000);
  assert.equal(Object.hasOwn(ordinaryUpdate.body.config, "apiToken"), false);

  const rotation = await callApi(handler, "POST", "/api/config", {
    token: "current-secret",
    apiToken: " replacement-secret "
  });
  assert.equal(rotation.statusCode, 200);
  assert.equal(state.config.apiToken, "replacement-secret");
  assert.equal(Object.hasOwn(rotation.body.config, "apiToken"), false);

  const oldTokenRejected = await callApi(handler, "POST", "/api/config", {
    token: "current-secret",
    maxCommandMs: 46000
  });
  assert.equal(oldTokenRejected.statusCode, 401);

  const newTokenAccepted = await callApi(handler, "POST", "/api/config", {
    token: "replacement-secret",
    maxCommandMs: 46000
  });
  assert.equal(newTokenAccepted.statusCode, 200);
  assert.equal(state.config.maxCommandMs, 46000);
});

test("startup bind recovery is also a protected config mutation", async () => {
  const { handler, state, saved, restartReasons, startupState } = createConfigHarness();

  const rejected = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "wrong-secret"
  });
  assert.equal(rejected.statusCode, 401);
  assert.equal(state.config.bindAddress, "127.0.0.1");
  assert.equal(saved.length, 0);
  assert.equal(restartReasons.length, 0);

  const accepted = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "current-secret"
  });
  assert.equal(accepted.statusCode, 200);
  assert.equal(state.config.bindAddress, "192.168.1.20");
  assert.equal(saved.length, 1);
  assert.deepEqual(restartReasons, ["api.startup.apply_recommended_bind"]);
  assert.equal(startupState.status, "applied_restarting");
  assert.equal(accepted.body.configApplied, true);
  assert.equal(accepted.body.restartScheduled, true);
  assert.equal(accepted.body.restartAlreadyScheduled, false);
  assert.equal(accepted.body.manualRestartRequired, false);
  assert.equal(accepted.body.startupStateUpdated, true);
  assert.equal(Object.hasOwn(accepted.body.config, "apiToken"), false);
});

test("startup metadata failure does not misreport an already committed config update", async () => {
  const { handler, state, saved, restartReasons } = createConfigHarness({ failStartupStateSave: true });

  const result = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "current-secret"
  });
  assert.equal(result.statusCode, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.configApplied, true);
  assert.equal(result.body.startupStateUpdated, false);
  assert.equal(result.body.restartScheduled, true);
  assert.equal(state.config.bindAddress, "192.168.1.20");
  assert.equal(saved.at(-1).bindAddress, "192.168.1.20");
  assert.deepEqual(restartReasons, ["api.startup.apply_recommended_bind"]);
});

test("an already queued restart is reported as pending success after the config commit", async () => {
  const { handler, state, restartReasons } = createConfigHarness({ restartResult: false });

  const result = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "current-secret"
  });
  assert.equal(result.statusCode, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.configApplied, true);
  assert.equal(result.body.restartScheduled, false);
  assert.equal(result.body.restartAlreadyScheduled, true);
  assert.equal(result.body.manualRestartRequired, false);
  assert.equal(state.config.bindAddress, "192.168.1.20");
  assert.deepEqual(restartReasons, ["api.startup.apply_recommended_bind"]);
});

test("restart scheduling failure reports that config applied and manual restart is required", async () => {
  const { handler, state } = createConfigHarness({ restartThrows: true });

  const result = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "current-secret"
  });
  assert.equal(result.statusCode, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.configApplied, true);
  assert.equal(result.body.restartScheduled, false);
  assert.equal(result.body.restartAlreadyScheduled, false);
  assert.equal(result.body.manualRestartRequired, true);
  assert.equal(result.body.restartError, "simulated restart failure");
  assert.equal(state.config.bindAddress, "192.168.1.20");
});

test("failed persistence does not apply config changes to the running state", async () => {
  const { handler, state, restartReasons } = createConfigHarness({ failSave: true });

  const configResult = await callApi(handler, "POST", "/api/config", {
    token: "current-secret",
    apiToken: "replacement-secret",
    port: 60000
  });
  assert.equal(configResult.statusCode, 400);
  assert.equal(state.config.apiToken, "current-secret");
  assert.equal(state.config.port, 58321);

  const startupResult = await callApi(handler, "POST", "/api/startup/apply_recommended_bind", {
    token: "current-secret"
  });
  assert.equal(startupResult.statusCode, 400);
  assert.equal(state.config.bindAddress, "127.0.0.1");
  assert.equal(restartReasons.length, 0);
});

test("browser API client sends the current token for startup recovery", async () => {
  const requests = [];
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    requests.push({ url, options });
    return {
      ok: true,
      status: 200,
      async text() {
        return JSON.stringify({ ok: true });
      }
    };
  };

  try {
    const { api } = await importBrowserModule("public/scripts/services/api.js");
    await api.applyRecommendedBind("current-secret");
  } finally {
    global.fetch = originalFetch;
  }

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "/api/startup/apply_recommended_bind");
  assert.equal(requests[0].options.method, "POST");
  assert.deepEqual(JSON.parse(requests[0].options.body), { token: "current-secret" });
});

test("accepted token rotation refreshes every in-memory consumer and the rendered mobile payload", async () => {
  const { applyAcceptedApiToken } = await importBrowserModule("public/scripts/services/api-token-session.js");
  const state = { configAuthToken: "old-secret" };
  const refs = Object.fromEntries(
    [
      "settingsCurrentApiTokenInput",
      "wizardCurrentApiTokenInput",
      "commandTokenInput",
      "manageTokenInput",
      "mobileTokenInput"
    ].map((name) => [name, { value: "old-secret" }])
  );
  let mobileRenderCount = 0;

  const applied = applyAcceptedApiToken({
    token: " replacement-secret ",
    state,
    refs,
    onMobileTokenUpdated() {
      mobileRenderCount += 1;
    }
  });

  assert.equal(applied, true);
  assert.equal(state.configAuthToken, "replacement-secret");
  for (const ref of Object.values(refs)) {
    assert.equal(ref.value, "replacement-secret");
  }
  assert.equal(mobileRenderCount, 1);
});

test("settings controller keeps current-token auth separate from optional token rotation", async () => {
  const { createSettingsController } = await importBrowserModule("public/scripts/features/settings-page.js");
  const payloads = [];
  const acceptedTokens = [];
  const refs = {
    settingsBindAddressInput: { value: "192.168.1.5" },
    settingsPortInput: { value: "58321" },
    settingsMaxCommandInput: { value: "30000" },
    settingsCurrentApiTokenInput: { value: "current-secret" },
    settingsNewApiTokenInput: { value: "" },
    settingsPresetChecksWrap: { querySelectorAll: () => [] }
  };
  const state = { configAuthToken: "" };
  const controller = createSettingsController({
    api: {
      async updateConfig(payload) {
        payloads.push(payload);
        return { ok: true, restartRequired: false, config: { apiTokenConfigured: true } };
      }
    },
    refs,
    state,
    t: (key) => key,
    W: {},
    render() {},
    helpers: createHelpers(),
    callbacks: {
      onApiTokenAccepted(token) {
        acceptedTokens.push(token);
        state.configAuthToken = token;
      },
      async onConfigSaved() {}
    }
  });

  await controller.saveConfigFromSettings({ preventDefault() {} });
  assert.equal(payloads[0].token, "current-secret");
  assert.equal(Object.hasOwn(payloads[0], "apiToken"), false);
  assert.equal(acceptedTokens[0], "current-secret");

  refs.settingsNewApiTokenInput.value = "replacement-secret";
  await controller.saveConfigFromSettings({ preventDefault() {} });
  assert.equal(payloads[1].token, "current-secret");
  assert.equal(payloads[1].apiToken, "replacement-secret");
  assert.equal(acceptedTokens[1], "replacement-secret");
  assert.equal(refs.settingsNewApiTokenInput.value, "");
});

test("wizard authenticates Save and Next and one-click fill reuses only the accepted in-memory token", async () => {
  const { createWizardController } = await importBrowserModule("public/scripts/features/wizard-page.js");
  const payloads = [];
  const state = {
    configAuthToken: "",
    config: { bindAddress: "192.168.1.5", port: 58321, maxCommandMs: 30000 },
    health: { network: { preferredLan: "192.168.1.5" } },
    wizardStep: 0,
    wizardAdvancedVisible: false,
    wizardBindAutoApplied: true
  };
  const classList = { toggle() {} };
  const refs = {
    wizardBindAddressInput: { value: "192.168.1.5" },
    wizardPortInput: { value: "58321" },
    wizardMaxCommandInput: { value: "30000" },
    wizardCurrentApiTokenInput: { value: "current-secret" },
    wizardNewApiTokenInput: { value: "" },
    wizardStep1Panel: { hidden: false },
    wizardStep2Panel: { hidden: true },
    wizardStep1Button: { classList },
    wizardStep2Button: { classList },
    wizardAdvancedPanel: { hidden: true },
    wizardToggleAdvancedButton: { textContent: "" },
    mobileBaseUrlInput: { value: "" },
    mobileTokenInput: { value: "" },
    mobileDefaultShellInput: { value: "" },
    mobileTimeoutMsInput: { value: "" },
    wizardMobileJsonOutput: { textContent: "" }
  };
  const controller = createWizardController({
    api: {
      async updateConfig(payload) {
        payloads.push(payload);
        return { ok: true, restartRequired: false, config: { apiTokenConfigured: true } };
      }
    },
    refs,
    state,
    t: (key) => key,
    helpers: createHelpers(),
    callbacks: {
      async reloadConfigAndHealth() {},
      onApiTokenAccepted(token) {
        state.configAuthToken = token;
        refs.wizardCurrentApiTokenInput.value = token;
      }
    }
  });

  await controller.handleWizardStep1SaveNext();
  assert.equal(payloads.length, 1);
  assert.equal(payloads[0].token, "current-secret");
  assert.equal(Object.hasOwn(payloads[0], "apiToken"), false);
  assert.equal(state.configAuthToken, "current-secret");

  await controller.handleWizardOneClickFill();
  assert.equal(payloads.length, 1, "one-click fill must not call the config mutation API");
  assert.equal(refs.mobileTokenInput.value, "current-secret");
  assert.match(refs.wizardMobileJsonOutput.textContent, /"WINDOWS_AGENT_TOKEN": "current-secret"/);
});
