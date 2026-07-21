# AGENTS.md

本文件适用于 Operit 仓库。代码、Gradle 配置和 CI 脚本是实现事实来源；开发与贡献流程以 `docs/doc-src/dev-core/CONTRIBUTING.md` 为准。

## 项目结构

- `app/`：Android 主应用、业务逻辑、资源和 JVM 测试
- `terminal/`：公开 Git 子模块，构建前需初始化
- `dragonbones/`、`fbx/`、`llama/`、`mmd/`、`mnn/`、`quickjs/`：native/渲染/推理模块
- `web-chat/`：React/Vite Web Chat
- `examples/`、`tools/`：ToolPkg、示例和仓库工具
- `docs/`：用户与开发文档
- `ci/`、`.github/`：检查、构建和发布自动化

## 构建与验证

- Android 构建使用仓库自带的 `gradlew` / `gradlew.bat`，JDK 21 和 Android SDK 36。
- 首次构建先执行 `git submodule update --init --recursive terminal`。
- 完整 Android 构建需要 README/编译指南列出的 `models.zip`、`subpack.zip`、`jniLibs.zip` 和 `libs.zip` 内容；这些本地依赖不得提交。
- 根据改动范围主动运行最小充分验证。Kotlin 改动优先运行 `./gradlew :app:compileDebugKotlin` 和相关 `:app:testDebugUnitTest`；资源或构建输入改动再运行 lint/assemble。
- Web Chat、ToolPkg 和仓库检查命令以 `docs/doc-src/dev-core/CONTRIBUTING.md` 与 `ci/README.md` 为准。
- 构建可能触碰 ObjectBox 模型或占位文件。提交前检查 `git status` 和 diff，不提交无内容的行尾变化或无关生成物。
- 无法运行验证时，说明缺失的 SDK、依赖或环境条件，不得把“未运行”描述为“通过”。

## 修改原则

- 修改前阅读相关实现、调用方和现有测试；优先小而完整的修复。
- 持久化数据、公开接口、配置格式或已发布行为发生变化时，评估兼容性和迁移影响。未发布的内部方案无需保留无用途的旧路径。
- 不以静默降级掩盖确定错误；是否需要兼容或恢复路径应由真实用户数据、外部调用方和产品契约决定。
- 新增用户可见文字使用资源字符串并同步受支持语言；文档只在行为、接口、构建或使用方式变化时更新。
- 注释解释非显而易见的约束和原因，不记录调试流水账。
- 不修改第三方子模块来绕过主仓库问题；确需更新子模块时单独说明来源和版本。

## 安全与 Git

- API Key、令牌、Cookie、签名材料、`local.properties`、本地路径和个人信息不得进入代码、测试、日志或文档。
- 不回退或覆盖他人的未提交改动，不使用 `git reset --hard`、`git checkout --` 等破坏性命令处理工作区。
- 提交前检查状态、完整差异和近期历史，只暂存本次任务文件。
- 上游贡献以最新 `upstream/main` 为基线并目标 `main`；Operit Ry 个人发行版改动目标 `personal/main`。提交、推送和创建 PR 前确认当前任务属于哪条线。
- 除非用户明确要求，不提交、推送、关闭 PR 或执行发布操作。
