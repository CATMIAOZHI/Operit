# AGENTS.md

本文件适用于 Operit 仓库。代码、Gradle 配置和 CI 脚本是实现事实来源；开发与贡献流程以 `docs/doc-src/dev-core/CONTRIBUTING.md` 为准。

## 项目结构

- `app/`：Android 主应用、业务逻辑、资源和 JVM 测试
- `terminal/`：公开 Git 子模块，构建前需初始化
- `dragonbones/`、`fbx/`、`llama/`、`mmd/`、`mnn/`、`quickjs/`：native/渲染/推理模块
- `cmake/`：共享 CMake 工具和原生依赖锁定清单（`operit_git_source.cmake`、`NATIVE_DEPENDENCY_LOCK.md`）
- `web-chat/`：React/Vite Web Chat
- `examples/`、`tools/`：ToolPkg、示例和仓库工具
- `docs/`：用户与开发文档；Agent 操作手册位于 `docs/agent/`（构建手册、晋升流程、提交检查清单）
- `ci/`、`.github/`：检查、构建和发布自动化

## 构建与验证

- Android 构建使用仓库自带的 `gradlew` / `gradlew.bat`，JDK 21 和 Android SDK 36。构建手册见 `docs/agent/build-guide.md`。
- 首次构建先执行 `git submodule update --init --recursive terminal`。
- 完整 Android 构建需要 README/编译指南列出的 `models.zip`、`subpack.zip`、`jniLibs.zip` 和 `libs.zip` 内容；这些本地依赖不得提交。
- 原生第三方依赖（MNN、llama.cpp、ncnn、sherpa-ncnn、WAMR、QuickJS、Saba、Bullet3、ufbx、KleidiAI）已固定到具体 commit SHA，锁定清单见 `cmake/NATIVE_DEPENDENCY_LOCK.md`；升级时更新该清单和对应 `CMakeLists.txt`，不要通过 `OPERIT_*_GIT_REF` 命令行参数覆盖（已不再生效）。
- 根据改动范围主动运行最小充分验证。Kotlin 改动优先运行 `./gradlew :app:compileDebugKotlin` 和相关 `:app:testDebugUnitTest`；资源或构建输入改动再运行 lint/assemble。
- Windows 本地执行 `lintDebug` 或其他会构建原生模块的任务时，SDK 随 CMake 3.22.1 提供的 Ninja 1.10.2 会在 MNN/KleidiAI 中间路径超过 260 字符时报告 `Filename longer than 260 characters`。已验证的首选方案是保留 CMake 3.22.1、使用支持 Windows 长路径的 Ninja 1.12.1+：将 CMake 复制到 SDK 管理目录之外的本机隔离目录，替换副本中的 Ninja，并通过被 Git 忽略的 `local.properties` 的 `cmake.dir` 指向副本；不要直接覆盖 SDK Manager 管理的原版工具。构建后必须从 `build_command_*.bat` 或 `CMakeCache.txt` 核对 Gradle 实际调用了新版 Ninja。若系统未启用 Win32 长路径、无法准备新版 Ninja，或新版工具仍失败，再用 `subst` 将仓库根目录映射为短盘符，并从该盘符根目录重新运行 Gradle。CMake 的 `CMAKE_OBJECT_PATH_MAX` 警告本身不等于构建失败。完整步骤见 `docs/agent/build-guide.md`。
- 完整 APK 构建、release 构建和原生依赖升级后的验证优先使用 GitHub Actions（Nightly workflow 或手动触发），不要在本地执行完整 `assembleRelease`。本地构建仅用于快速迭代测试（`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleDebug` 等），验证通过后再推送让 CI 做完整构建。
- 原生依赖 SHA 升级必须先推送 `personal/dev`，由 Nightly 构建验证 ccache 命中率和编译成功后，再考虑合并到 `personal/main`。
- Web Chat、ToolPkg 和仓库检查命令以 `docs/doc-src/dev-core/CONTRIBUTING.md` 与 `ci/README.md` 为准。
- 等待 GitHub Actions workflow 时使用至少 60 秒的轮询间隔并默认静默输出（PowerShell 示例：`gh run watch <run-id> --exit-status --interval 60 *> $null`）；结束后用一次 `gh run view` 查询最终结果，仅在诊断失败时读取详细 job 日志。
- 构建可能触碰 ObjectBox 模型或占位文件。提交前检查 `git status` 和 diff，不提交无内容的行尾变化或无关生成物。
- 无法运行验证时，说明缺失的 SDK、依赖或环境条件，不得把“未运行”描述为“通过”。

## 分支与发布

- `origin` 指向个人 fork，默认分支为 `personal/main`；`upstream` 指向官方仓库，默认分支为 `main`。
- 除非用户明确要求同步上游、分析上游或向上游贡献，否则所有规划、开发、审查和验证只以个人版当前目标分支为基线，不主动拉取、对比、合并或兼容 `upstream/main`。
- `personal/main` 更新后由 `.github/workflows/sync-main-mirror.yml` 单向快进到 `main`，供仅支持 `main` 的安全工具读取。镜像使用仅授权本仓库 **Contents: read and write** 与 **Workflows: read and write** 的 `MAIN_MIRROR_TOKEN`，因为内置 `GITHUB_TOKEN` 不能推进包含 workflow 变更的提交。`main` 是只读兼容镜像，不得直接提交、合并 PR、强推或用作上游贡献分支；同步失败时先排查分叉，不得覆盖历史。
- 上游贡献以最新 `upstream/main` 为基线，使用独立的 `contrib/<topic>` 分支，并显式目标官方 `main`；不得混入 Operit Ry 的品牌、服务路由或发布配置。
- `personal/main` 是 Operit Ry 稳定发行分支，受规则保护；改动必须通过 PR 和必需检查，稳定 APK 与 `v*` Release tag 只从该分支发布。
- `personal/dev` 是所有新功能的集成与测试分支。新功能必须先进入该分支，构建并实际测试可共存的 debug APK；测试通过后，经用户同意再以只包含通用功能提交的 PR 晋升到 `personal/main`。晋升操作手册见 `docs/agent/dev-to-main-promotion.md`。不得绕过开发版验证直接向稳定分支加入新功能，也不得把开发版专属配置带入晋升 PR。
- 上游更新先整合进 `personal/dev` 构建并测试，确认不破坏现有功能后再合并到 `personal/main`；不要用上游分支重置或覆盖任一个人分支。
- `personal/dev` 的 `debug` 变体使用包名 `com.rainy.operitry.dev`、应用名 `Operit Ry Dev` 和 `-dev` 版本后缀，可与官方 Operit 及稳定版同时安装；`app/src/debug/res/` 维护 DEV 角标图标和指向开发包名的快捷方式，修改应用身份时必须同步核对这些资源。
- `OperitNightlyRelease` 仓库在 `personal/dev` 有新提交时被 push 驱动的 `repository_dispatch` 即时触发，不再轮询；每小时第 7 分钟的 `cron` 只作漏触发兜底（GitHub Actions 的 `schedule` 是 best-effort，`*/5` 实测会被丢弃到约每小时一次）。触发由本仓库 `.github/workflows/trigger-nightly-build.yml` 完成，仅 `personal/dev` 的 push 自动触发，`personal/main` 需手动 `workflow_dispatch`（dev 与 main 走不同的 `event_type`）；调用 API 用的 `NIGHTLY_DISPATCH_TOKEN` 是只授予 `OperitNightlyRelease` 的 **Contents: write** fine-grained PAT（`repository_dispatch` 端点需要 Contents 而非 Actions 权限），存在 Operit 仓库 Secrets 中。发布使用递增的 `-dev.<build>` 版本和自身 `GITHUB_TOKEN` 发布 `personal-dev` 差分更新；发布链中的 APK 必须保持 `com.rainy.operitry.dev` 包名及与现有开发版相同的固定签名，首次或补丁链不匹配时保留完整 debug APK 回退。签名 Secret 只配置在 Actions 中，不得写入代码或日志。Nightly 构建使用 ccache（`CCACHE_COMPILERCHECK=content`、2G 滚动缓存）加速原生编译，修改 `cmake/operit_git_source.cmake` 或原生依赖 SHA 时需注意对缓存命中率的影响；ccache 相关环境变量（`CCACHE_*`、`CMAKE_C/CXX_COMPILER_LAUNCHER`）配置在 `build-and-publish` job 级别。Nightly 工作流仅含 `build-and-publish`（checkout、原生编译、APK 构建、验签、发布），质量检查已移至 Operit 主仓库的 `.github/workflows/dev-quality.yml`，在 `personal/dev` push 时独立运行，不阻塞 Nightly 发布。

## 修改原则

- 修改前阅读相关实现、调用方和现有测试；优先小而完整的修复。
- 持久化数据、公开接口、配置格式或已发布行为发生变化时，评估兼容性和迁移影响。未发布的内部方案无需保留无用途的旧路径。
- 不以静默降级掩盖确定错误；是否需要兼容或恢复路径应由真实用户数据、外部调用方和产品契约决定。
- 新增用户可见文字使用资源字符串并提供简体中文；`values/`（默认简体中文）和 `values-en/`（英语）为唯一需要维护的文案来源，不新增或同步其他语言。例外：同步上游官方 i18n PR 中已发布的非默认语言翻译时，允许带入并在 PR 说明中标注上游 PR 编号。`MissingTranslation` 检查已在 `personal/main` 的 `app/build.gradle.kts` 中禁用，其他语言的现有翻译由社区自行维护，不作为本仓库门槛。文档只在行为、接口、构建或使用方式变化时更新。
- 注释解释非显而易见的约束和原因，不记录调试流水账。
- 不修改第三方子模块来绕过主仓库问题；确需更新子模块时单独说明来源和版本。

## 安全与 Git

- API Key、令牌、Cookie、签名材料、`local.properties`、本地路径和个人信息不得进入代码、测试、日志或文档。
- 不回退或覆盖他人的未提交改动，不使用 `git reset --hard`、`git checkout --` 等破坏性命令处理工作区。
- 提交前检查状态、完整差异和近期历史，只暂存本次任务文件。提交前检查清单见 `docs/agent/commit-checklist.md`。
- 除非用户明确要求，不提交、推送、关闭 PR 或执行发布操作。
