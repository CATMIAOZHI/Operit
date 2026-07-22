# 提交检查清单

本手册供 Agent 在 `git commit` 前逐项确认，避免常见错误。

## 通用检查（每次提交）

### 1. 只暂存本次任务文件

```bash
git status
git diff --cached --stat
```

确认暂存的文件全部属于本次任务。常见不该提交的文件：

- `local.properties`
- `models.zip`、`subpack.zip`、`jniLibs.zip`、`libs.zip`
- `.cxx/`、`build/`、`.gradle/` 构建产物
- `app/src/main/jniLibs/` 下通过脚本生成的 `.so`
- `examples/github.js`（除非是 `npm run build:examples:github` 的有意输出）

### 2. 排除凭据和隐私

确认暂存内容不包含：

- API Key、Token、Cookie
- 签名材料（keystore、密码）
- 个人路径（如 `C:\Users\...`）
- `local.properties` 中的 `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` 真实值

### 3. 检查行尾

```bash
git diff --cached --check
```

不要提交无内容的行尾变化（CRLF → LF 或反之）。如果文件被 git 自动转换了行尾但内容未变，恢复它：

```bash
git checkout -- <file>
```

### 4. 检查 ObjectBox 和生成物

构建可能触碰 ObjectBox 模型文件或占位文件（如 `.keep`）。确认这些没有被无意义修改。

## 按改动类型的额外检查

### Kotlin / Java 改动

- 运行 `./gradlew :app:compileDebugKotlin`
- 相关模块运行 `./gradlew :app:testDebugUnitTest`
- 新增用户可见文字时：使用资源字符串（`values/` 简体中文 + `values-en/` 英语），不硬编码
- 改动持久化数据格式时：评估迁移和兼容性

### 资源 / 构建输入改动

- 运行 `./gradlew :app:lintDebug`
- 改动图标或应用身份时：同步检查 `app/src/debug/res/` 下的 DEV 资源
- 改动 `app/build.gradle.kts` 时：确认 `PERSONAL_DEV_UPDATE_CHANNEL` 在 `debug` 为 `true`、在 `release` 为 `false`

### CMake / 原生依赖改动

- 改动 `cmake/operit_git_source.cmake` 时：注意对 ccache 命中率的影响
- 升级原生依赖 SHA 时：更新 `cmake/NATIVE_DEPENDENCY_LOCK.md`，一次只升一个依赖
- 不要通过 `OPERIT_*_GIT_REF` 命令行参数覆盖（已不再生效）

### Web Chat / ToolPkg 改动

- Web Chat：`npm --prefix web-chat run typecheck`、`npm run build:webchat`
- ToolPkg：`npm run build:examples:github`，确认 `git diff --exit-code -- examples/github.js`

### CI / workflow 改动

- 等待 Actions 时使用至少 60 秒轮询间隔
- 不写入签名密钥或 Token 值到 workflow 文件

## 分支相关

### personal/dev

- 直接 push 即可，不需要 PR（但禁止删除和强推）
- 可以包含开发版专属配置（包名 `.dev`、DEV 图标、Nightly 子模块等）

### personal/main

- 必须通过 PR 和必需检查
- 不得包含开发版专属配置
- 晋升流程见 [dev → main 晋升操作手册](./dev-to-main-promotion.md)

### 上游 main

- 以 `upstream/main` 为基线，目标分支为 `main`
- 不得混入 Operit Ry 的品牌、服务路由或发布配置
- PR 流程以 `docs/doc-src/dev-core/CONTRIBUTING.md` 为准

## 提交信息格式

建议使用 Conventional Commits：

```
feat(tools): add package description
fix(chat): preserve scroll position
ci: add ccache to nightly build
docs: update native dependency docs
```

## 最后确认

```bash
git log --oneline -5          # 确认提交历史
git diff --cached              # 最终审视完整差异
```

确认无误后再推送。除非用户明确要求，不推送、关闭 PR 或执行发布操作。