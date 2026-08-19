# 构建手册

本手册供 Agent 在本地或 CI 上构建 Operit 时参考。完整的开发者环境配置（JDK、SDK、NDK 安装）见 `docs/doc-src/dev-core/BUILDING.md`；此处只记录 Agent 构建时需要知道的分支选择、构建变体、前置步骤和验证命令。

## 分支与构建变体

| 分支 | 用途 | 主要构建变体 | 包名 |
|---|---|---|---|
| `personal/dev` | 功能集成与测试 | `debug` | `com.rainy.operitry.dev` |
| `personal/dev` | 共存测试 | `clone` | `com.rainy.operitry.clone` |
| `personal/main` | 稳定发行 | `release` | `com.rainy.operitry` |
| `personal/main` | Nightly | `nightly` | `com.rainy.operitry` |

构建变体定义在 `app/build.gradle.kts` 的 `buildTypes` 块中。`debug` 变体自动添加 `.dev` 包名后缀和 `-dev` 版本后缀，`clone` 变体添加 `.clone` 后缀。

> **注意**：`debug` 变体的 `.dev` 包名后缀、应用名 `Operit Ry Dev` 和 `PERSONAL_DEV_UPDATE_CHANNEL=true` 仅在 `personal/dev` 分支存在。在 `personal/main` 上构建 `assembleDebug` 使用稳定的 `com.rainy.operitry` 包名，**不具备**与开发版共存的能力，会覆盖已安装的 Operit Ry 稳定版（官方 Operit `com.ai.assistance.operit` 包名不同，可共存）。

## 前置步骤

### 1. 初始化子模块

```bash
git submodule update --init --recursive terminal
```

`terminal` 是 Android 构建唯一需要初始化的子模块。仓库另有一个 `tools/hotbuild/OperitNightlyRelease` 子模块，仅由 Nightly 发布流程使用，本地构建无需关注。

### 2. 准备本地依赖

完整构建需要四个手动下载的依赖包，放置位置如下：

| 压缩包 | 解压目标 |
|---|---|
| `libs.zip` | `app/libs` |
| `models.zip` | `app/src/main/assets/models` |
| `subpack.zip` | `app/src/main/assets/subpack` |
| `jniLibs.zip` | `app/src/main/jniLibs` |

这些文件不得提交到 Git。下载地址见 `docs/doc-src/dev-core/BUILDING.md` 和 `README.md`。

CI 通过 `ci/script/download_android_dependencies.sh` 和 `ci/script/prepare_android_dependencies.py` 自动下载和解压。本地构建时需手动放置。

### 3. 配置 local.properties

```properties
sdk.dir=<Android SDK 路径>
GITHUB_CLIENT_ID=
GITHUB_OAUTH_BROKER_BASE_URL=https://<个人控制的 OAuth broker>
```

GitHub OAuth 的 `client_secret` 只能配置在 broker 的服务端 secret store 中，不得进入
`local.properties`、Gradle BuildConfig 或 APK。broker 协议与安全边界见
`docs/agent/github-oauth-broker.md`。

稳定版签名需要额外配置（仅 `personal/main` release 构建）：

```properties
RELEASE_STORE_FILE=<keystore 路径>
RELEASE_STORE_PASSWORD=<密码>
RELEASE_KEY_ALIAS=<别名>
RELEASE_KEY_PASSWORD=<密码>
```

签名材料不得写入代码或日志。

### 4. Windows 长路径与 Ninja

项目原生模块继续使用 CMake 3.22.1。Android SDK 随该版本提供的 Ninja 1.10.2 不支持 Windows 超过 260 字符的路径，MNN/KleidiAI 构建可能因此出现：

```text
ninja: error: Stat(...): Filename longer than 260 characters
```

Windows 本身也必须启用 Win32 长路径。使用 PowerShell 检查：

```powershell
Get-ItemPropertyValue `
  -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' `
  -Name LongPathsEnabled
```

结果应为 `1`。系统设置只为支持长路径的应用放开限制，仍需使用 Ninja 1.12.1 或更高版本。

推荐保留 SDK Manager 管理的原版 CMake，在 SDK 管理目录之外创建本机隔离副本，例如：

```text
<Android SDK>\cmake-custom\3.22.1-ninja-1.12.1
```

操作原则：

1. 将 `<Android SDK>\cmake\3.22.1` 完整复制到隔离目录。
2. 从 Ninja 官方发布包，或包含 Ninja 1.12.1 的 Google Android SDK CMake 3.31.6 Windows 包中取得 `ninja.exe`。
3. 只替换隔离副本的 `bin\ninja.exe`，不要覆盖 `<Android SDK>\cmake\3.22.1` 中由 SDK Manager 管理的原版文件。
4. 确认隔离副本中的 CMake 仍报告 3.22.1，Ninja 报告 1.12.1 或更高版本。

```powershell
& '<隔离目录>\bin\cmake.exe' --version
& '<隔离目录>\bin\ninja.exe' --version
```

在 `local.properties` 中增加 `cmake.dir`。该文件已被 Git 忽略，不得提交；Windows 路径中的冒号和反斜杠需要转义：

```properties
sdk.dir=C\:\\AndroidSdk
cmake.dir=C\:\\AndroidSdk\\cmake-custom\\3.22.1-ninja-1.12.1
```

首次构建后不能只检查命令行中的版本输出，还要确认 AGP 生成的 `build_command_*.bat` 或 `CMakeCache.txt` 实际指向隔离工具链：

```powershell
rg -n 'CMAKE_MAKE_PROGRAM|cmake-custom' `
  app/.cxx mnn/.cxx llama/.cxx `
  app/build/intermediates/cxx mnn/build/intermediates/cxx llama/build/intermediates/cxx
```

这套 `CMake 3.22.1 + Ninja 1.12.1` 组合已经从仓库原始长路径通过以下验证：

```powershell
.\gradlew.bat :mnn:assembleDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

CMake 仍可能输出 `CMAKE_OBJECT_PATH_MAX` 警告；只要新版 Ninja 完成构建，该警告不应被单独报告为失败。

如果无法准备 Ninja 1.12.1+、系统长路径未启用，或新版工具仍然失败，使用短盘符作为回退：

```powershell
subst R: '<Operit 仓库绝对路径>'
Set-Location R:\
.\gradlew.bat :app:lintDebug
Set-Location '<原工作目录>'
subst R: /d
```

使用前确认盘符未被占用；构建命令必须从短盘符根目录执行。不要把 `Filename longer than 260 characters` 误报为 Kotlin 或 Lint 代码失败，也不能因此跳过验证。

### 5. 构建 WebChat 和 ToolPkg

```bash
npm install
npm --prefix web-chat install
npm run build:webchat
npm run build:examples:github
python3 ./tools/example_packages/sync_example_packages.py --no-hot-reload
```

修改 `web-chat/src` 或 `examples/` 下的脚本包后，需要重新执行对应步骤再构建 APK。

## 构建命令

### 开发版（personal/dev）

```bash
./gradlew :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
# 包名：com.rainy.operitry.dev
# 版本后缀：-dev.<build>（CI 中由 OPERIT_DEV_BUILD_NUMBER 环境变量提供）
```

### 共存版（clone）

```bash
./gradlew :app:assembleClone
# 输出：app/build/outputs/apk/clone/app-clone.apk
# 包名：com.rainy.operitry.clone
```

### 稳定版（personal/main）

```bash
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
# 包名：com.rainy.operitry
# 需要 local.properties 中配置签名
```

## 最小验证命令

根据改动范围选择，不要求每次都跑完整构建：

```bash
# Kotlin 编译
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# WebChat 类型检查
npm --prefix web-chat run typecheck

# ToolPkg 构建一致性
npm run build:examples:github
git diff --exit-code -- examples/github.js
```

## CI 构建（Nightly）

Nightly 构建由 `OperitNightlyRelease` 仓库的 `personal-dev-update.yml` workflow 自动执行，每 5 分钟检查 `personal/dev` 是否有新提交。

关键配置：

- **NDK 版本**：`25.1.8937393`
- **CMake 版本**：`3.22.1`
- **JDK**：21
- **ccache**：`CCACHE_COMPILERCHECK=content`、2G 滚动缓存，加速原生编译
- **签名**：使用 Actions Secrets 中配置的 Operit Ry 发布签名，构建前和构建后双重校验证书 SHA-256

Agent 等待 Actions 时使用至少 60 秒轮询间隔：

```bash
gh run watch <run-id> --exit-status --interval 60 >/dev/null 2>&1
# Windows PowerShell: gh run watch <run-id> --exit-status --interval 60 *> $null
```

## 原生依赖

10 个原生第三方依赖已固定到具体 commit SHA，锁定清单见 `cmake/NATIVE_DEPENDENCY_LOCK.md`。CMake 配置时自动下载对应 GitHub archive，不需要手动获取。

升级依赖时：

1. 用 `git ls-remote` 解析新 SHA。
2. 更新对应 `CMakeLists.txt` 中的 SHA。
3. 更新 `cmake/NATIVE_DEPENDENCY_LOCK.md`。
4. 一次只升级一个依赖，构建验证后再升级下一个。

`OPERIT_*_GIT_REF` 命令行参数覆盖已不再生效（`operit_git_source.cmake` 使用 `CACHE ... FORCE`）。

## 常见问题

| 问题 | 解决方法 |
|---|---|
| `NDK not found` | 确认安装了 `ndk;25.1.8937393` |
| `Missing web-chat/dist` | 先执行 `npm run build:webchat` |
| `ERROR: prebuild step failed` | 先在项目根目录执行 `npm install`，确认 `pnpm` 可用 |
| Ninja `Filename longer than 260 characters` | 确认 Windows `LongPathsEnabled=1`，并按上文使用 `CMake 3.22.1 + Ninja 1.12.1+` 的隔离工具链；`subst` 仅作为回退 |
| CMake `Unable to resolve ref` | 确认 `operit_git_source.cmake` 的 SHA 检测逻辑未被改动；CMake regex 不支持 `{n}` 量词 |
| 构建产物体积异常 | 检查是否意外打包了多个 ABI；当前只支持 `arm64-v8a` |
| ObjectBox 模型文件被修改 | 提交前用 `git status` 检查，恢复无关生成物 |
