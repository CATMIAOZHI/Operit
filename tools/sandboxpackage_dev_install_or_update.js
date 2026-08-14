const SandboxPackageDevInstallerState = {
  logs: []
};

const SandboxPackageDevInstaller = (function () {
  const ENVIRONMENT = "android";
  const SKILL_NAME = "SandboxPackage_DEV";
  const SKILL_ROOT = `/sdcard/Download/Operit/skills/${SKILL_NAME}`;
  const REFERENCES_DIR = `${SKILL_ROOT}/references`;
  const TYPES_DIR = `${SKILL_ROOT}/types`;
  const SCRIPTS_DIR = `${SKILL_ROOT}/scripts`;
  const EXAMPLES_DIR = `${SKILL_ROOT}/examples`;
  const EXAMPLE_PACKAGES_DIR = `${EXAMPLES_DIR}/packages`;
  const CDN_BASE = "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/main";
  const MAX_DOWNLOAD_CONCURRENCY = 8;
  const TYPE_FILES = [
    "android.d.ts",
    "chat.d.ts",
    "compose-dsl.d.ts",
    "compose-dsl.material3.generated.d.ts",
    "core.d.ts",
    "cryptojs.d.ts",
    "ffmpeg.d.ts",
    "files.d.ts",
    "index.d.ts",
    "java-bridge.d.ts",
    "jimp.d.ts",
    "memory.d.ts",
    "network.d.ts",
    "okhttp.d.ts",
    "pako.d.ts",
    "quickjs-runtime.d.ts",
    "results.d.ts",
    "software_settings.d.ts",
    "system.d.ts",
    "tasker.d.ts",
    "tool-types.d.ts",
    "toolpkg.d.ts",
    "ui.d.ts",
    "workflow.d.ts"
  ];
  const DOWNLOADS = [
    {
      url: `${CDN_BASE}/docs/SCRIPT_DEV_SKILL.md`,
      destination: `${SKILL_ROOT}/SKILL.md`
    },
    {
      url: `${CDN_BASE}/docs/SCRIPT_DEV_GUIDE.md`,
      destination: `${REFERENCES_DIR}/SCRIPT_DEV_GUIDE.md`
    },
    {
      url: `${CDN_BASE}/docs/TOOLPKG_FORMAT_GUIDE.md`,
      destination: `${REFERENCES_DIR}/TOOLPKG_FORMAT_GUIDE.md`
    }
  ]
    .concat(
      TYPE_FILES.map((fileName) => ({
        url: `${CDN_BASE}/examples/types/${fileName}`,
        destination: `${TYPES_DIR}/${fileName}`
      }))
    );

  function logStep(message) {
    SandboxPackageDevInstallerState.logs.push(message);
    console.log(message);
  }

  async function makeDirectory(path) {
    return await Tools.Files.mkdir(path, true, ENVIRONMENT);
  }

  async function downloadFileAsync(url, destination) {
    return await Tools.Files.download(url, destination, ENVIRONMENT);
  }

  async function downloadAllFiles() {
    let nextIndex = 0;

    async function worker() {
      while (nextIndex < DOWNLOADS.length) {
        const item = DOWNLOADS[nextIndex];
        nextIndex += 1;
        logStep(`Downloading -> ${item.destination}`);
        await downloadFileAsync(item.url, item.destination);
      }
    }

    const workerCount = Math.min(MAX_DOWNLOAD_CONCURRENCY, DOWNLOADS.length);
    const workers = [];
    for (let index = 0; index < workerCount; index += 1) {
      workers.push(worker());
    }
    await Promise.all(workers);
  }

  function requireBundledPackageAssetApi() {
    if (
      typeof NativeInterface !== "object" ||
      typeof NativeInterface.listSandboxPackageDevPackageAssets !== "function" ||
      typeof NativeInterface.readSandboxPackageDevPackageAssetBase64 !== "function"
    ) {
      throw new Error(
        "This Operit build does not provide the read-only SandboxPackage_DEV asset API"
      );
    }
  }

  async function syncBundledPackageExamples() {
    requireBundledPackageAssetApi();
    const result = JSON.parse(NativeInterface.listSandboxPackageDevPackageAssets());
    if (!result || result.success !== true || !Array.isArray(result.files)) {
      throw new Error(
        String(result && result.message ? result.message : "Failed to list bundled package examples")
      );
    }
    for (const fileName of result.files) {
      const readResult = JSON.parse(
        NativeInterface.readSandboxPackageDevPackageAssetBase64(fileName)
      );
      if (!readResult || readResult.success !== true || typeof readResult.base64 !== "string") {
        throw new Error(
          String(
            readResult && readResult.message
              ? readResult.message
              : `Failed to read bundled package example: ${fileName}`
          )
        );
      }
      const destination = `${EXAMPLE_PACKAGES_DIR}/${fileName}`;
      logStep(`Writing built-in package example -> ${destination}`);
      await Tools.Files.writeBinary(destination, readResult.base64, ENVIRONMENT);
    }
    return result.files;
  }

  async function run() {
    logStep(`Preparing skill root -> ${SKILL_ROOT}`);
    await makeDirectory("/sdcard/Download/Operit/skills");
    await makeDirectory(SKILL_ROOT);
    await makeDirectory(REFERENCES_DIR);
    await makeDirectory(TYPES_DIR);
    await makeDirectory(SCRIPTS_DIR);
    await makeDirectory(EXAMPLES_DIR);
    await makeDirectory(EXAMPLE_PACKAGES_DIR);
    await downloadAllFiles();

    const copiedExampleFiles = await syncBundledPackageExamples();
    logStep(`Built-in package examples synced -> ${copiedExampleFiles.length} files`);

    return {
      success: true,
      message: `${SKILL_NAME} installed or updated successfully.`,
      data: {
        skill_name: SKILL_NAME,
        skill_root: SKILL_ROOT,
        references_dir: REFERENCES_DIR,
        types_dir: TYPES_DIR,
        scripts_dir: SCRIPTS_DIR,
        examples_dir: EXAMPLES_DIR,
        examples_packages_dir: EXAMPLE_PACKAGES_DIR,
        downloaded_count: DOWNLOADS.length,
        type_count: TYPE_FILES.length,
        builtin_example_count: copiedExampleFiles.length,
        builtin_example_files: copiedExampleFiles,
        logs: SandboxPackageDevInstallerState.logs
      }
    };
  }

  return {
    run
  };
})();

SandboxPackageDevInstaller.run()
  .then((result) => {
    complete(result);
  })
  .catch((error) => {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
      data: {
        skill_name: "SandboxPackage_DEV",
        logs: SandboxPackageDevInstallerState.logs
      }
    });
  });
