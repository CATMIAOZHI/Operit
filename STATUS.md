# 状态记录 — Operit 贡献工作

> 最后更新：2026-07-08（debug 版实测回传）

## 背景

用户（水晴）作为 fork 贡献者参与上游 AAswordman/Operit 项目，主要工作围绕两个方向：修复 issue #685（原生 function calling 时 AI 文本中的 XML 工具标签被误执行），以及搭建 CI 编译环境验证修复代码。

---

## Issue #685 修复 — `data-origin` 来源标记方案

### 问题描述

开启 `enableToolCall`（原生 function calling）时，AI 在普通文本回复中输出的 XML 工具标签仍被 `ToolExecutionManager.extractToolInvocations()` 正则匹配并当作真实工具调用执行，而非作为普通文本展示。

### 修复方案（协作者 @luojiaping 建议）

Provider 将原生 tool call 转成 XML 时增加 `data-origin="native_tool_call"` 标记；`extractToolInvocations` 和 `parseXmlToolCalls` 增加 `onlyNative` 参数，在 `enableToolCall=true` 时只提取/转换带标记的 XML；历史重建时对 `ASSISTANT` turn 只转换带标记的 XML。`TOOL_CALL` turn 和 `enableToolCall=false` 行为不变。

### 修改的文件（12 个代码文件 + 2 个 CI 文件）

| 文件 | 改动 |
|------|------|
| `ChatMarkupRegex.kt` | 新增 `NATIVE_TOOL_CALL_ORIGIN` 常量、`isNativeToolCallOrigin()` 方法 |
| `ToolExecutionManager.kt` | `extractToolInvocations` 增加 `onlyNative` 参数，过滤不带标记的 XML |
| `EnhancedAIService.kt` | `processStreamCompletion` 接收 `useToolCallApi`，传给 `extractToolInvocations`；`finally` 块用 `firstRoundEnableToolCall` 替代作用域外的 `modelSnapshot` |
| `StructuredToolCallBridge.kt` | `convertToolCallsToXml` 加 `data-origin` 标记；`parseXmlToolCalls` 增加 `onlyNative` 参数；`convertToolCallPayloadToXml` 调用链传递标记 |
| `OpenAIProvider.kt` | `convertToolCallsToXml` 加 `data-origin` 标记；`parseXmlToolCalls` 增加 `onlyNative` 参数 |
| `ClaudeProvider.kt` | `parseXmlToolCalls` 增加 `onlyNative` 参数；流式 tool call XML 加 `data-origin` 标记 |
| `GeminiProvider.kt` | `parseXmlToolCalls` 增加 `onlyNative` 参数；流式 tool call XML 加 `data-origin` 标记 |
| `MistralProvider.kt` | `parseXmlToolCalls` override 去掉默认参数值 |
| `LinuxFileSystemTools.kt` | 修复上游笔误 `grepCodeWithRipgrep` → `grepCodeWithNativeRipgrep`（issue #688） |
| `app/build.gradle.kts` | Debug 构建添加 `.debug` 包名后缀（`applicationIdSuffix`）|
| `.github/workflows/build-debug.yml` | CI workflow 文件 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper 配置 |

### 编译验证

| Run # | 结果 | 说明 |
|-------|------|------|
| #12 | ❌ | 首次提交，3 个 Kotlin 编译错误 |
| #13 | ❌ | 对抗审查后发现 4 个遗漏，重新提交后仍有编译错误 |
| #14 | ✅ | 修复全部编译错误，编译成功，产出 debug APK |

CI Run 14 链接：https://github.com/CATMIAOZHI/Operit/actions/runs/28909018188

### 编译错误修复记录

| 错误 | 原因 | 修复 |
|------|------|------|
| `EnhancedAIService.kt:1283` — `Unresolved reference 'modelSnapshot'` | `modelSnapshot` 定义在 `withContext(Dispatchers.IO)` 块内，`finally` 中不可见 | 用外层变量 `firstRoundEnableToolCall` 替代 |
| `MistralProvider.kt` — override 方法不能有默认参数值 | Kotlin 规则：override 方法不允许默认参数 | 去掉 `= false` |
| `LinuxFileSystemTools.kt:1176` — `Unresolved reference 'grepCodeWithRipgrep'` | 上游 main 分支笔误（issue #688） | 修复为 `grepCodeWithNativeRipgrep` |

### Debug 版实测结果（2026-07-08）

在 debug APK（包名 `com.ai.assistance.operit.debug`）上实测：

- **执行层**：✅ 已修复。`enableToolCall=true` 时，AI 文本中不带 `data-origin` 标记的 XML 工具标签不会被 `extractToolInvocations` 提取执行。
- **渲染层**：⚠️ 不带 `data-origin` 标记的 XML 工具标签仍被 UI 渲染为工具调用卡片样式，但不会被执行。
  - 分析后决定**不改渲染层**，原因：
    1. 核心问题（误执行）已解决
    2. 渲染层无法区分「旧版 `enableToolCall=false` 的真实工具调用」和「AI 文本中的 XML 示例」——两者都不带 `data-origin`
    3. 历史消息未存储 `enableToolCall` 配置，无法按配置区分渲染
    4. 任何基于 `data-origin` 的渲染过滤都会误伤历史数据中的真实工具调用
  - 结论：卡片样式不执行 ≠ 误执行，视觉问题不影响功能，零回归风险

### 对抗审查

对代码修改进行了从第一性原理出发的对抗审查，发现并修复了 4 个遗漏：
1. `MistralProvider.kt` override 方法默认参数值（编译错误）
2. `convertToolCallPayloadToXml` 调用链未传递 `data-origin` 标记（逻辑遗漏）
3. `ClaudeProvider` 流式 tool call 未加 `data-origin` 标记（逻辑遗漏）
4. `GeminiProvider` 流式 tool call 未加 `data-origin` 标记（逻辑遗漏）

### 编译错误根因分析（第一性原理）

1. **变量作用域**：`modelSnapshot` 定义在 `withContext` 内部块，`finally` 引用时不可见——每个新引用的变量需确认定义处和引用处在同一个 `{}` 内
2. **Kotlin override 规则**：override 方法不能有默认参数值——需熟记语言特定约束
3. **基线完整性**：分支从 main 创建，main 本身有笔误 bug（#688）——确认出发点本身能编译

---

## 分支信息

| 分支 | 仓库 | 用途 |
|------|------|------|
| `main` | fork (CATMIAOZHI/Operit) | 已同步到上游最新 |
| `fix/issue-685-native-toolcall-origin` | fork | Issue #685 修复分支，含完整修复代码 + CI workflow |
| `ci/build-debug` | fork | CI 编译调试分支 |
| `fix/grep-ripgrep-typo` | fork | 笔误修复分支（PR #690 已提交上游） |

---

## 待办

- [x] Issue #685 方案设计（协作者 @luojiaping 建议）
- [x] 代码实现（12 个文件）
- [x] 对抗审查与遗漏修复
- [x] CI 编译通过（Run #14）
- [x] Debug APK 实测验证
- [x] 渲染层分析（决定不改）
- [ ] 向上游提交 PR（待水晴确认后操作）
- [ ] 等待 issue #688 回复
- [x] PR #690 已提交上游（grepCodeWithRipgrep 笔误修复）

---

## Git 签名

- 本地 Git 配置使用 SSH 签名（`gpg.format = ssh`，密钥 `id_ed25519`）。
- 提交前需加载私钥：`eval $(ssh-agent -s) && ssh-add /root/.ssh/id_ed25519`。
- Debug 版终端环境无 git/curl，需通过 GitHub API 操作。

---

## CI 编译环境

### 依赖包

4 个 zip 已上传到 GitHub Release `deps-v1`：

| 文件 | 大小 | 解压位置 | 内容 |
|------|------|----------|------|
| `libs.zip` | 15 MB | `app/libs/` | FFmpegKit 等 aar/jar |
| `jniLibs.zip` | 1.3 MB | `app/src/main/jniLibs/` | 原生 .so 库 |
| `models.zip` | 126 MB | `app/src/main/assets/models/` | 模型资源 |
| `subpack.zip` | 34 MB | `app/src/main/assets/subpack/` | 附加资源包 |

### Workflow 配置

`.github/workflows/build-debug.yml`：
- Java 21（Temurin）
- 手动触发（`workflow_dispatch`）
- 依赖缓存（`actions/cache/restore` + `actions/cache/save`）
- Gradle wrapper URL 改为官方源
- 构建命令：`./gradlew :app:assembleDebug -x lint --build-cache`
- Debug APK 包名后缀 `.debug`（`applicationIdSuffix = ".debug"`）

### 编译历程

| Run # | 结果 | 失败原因 | 修复 |
|-------|------|----------|------|
| #5 | ❌ | NDK 27.2 zip 包损坏 | 去掉 NDK 显式安装 |
| #6 | ❌ | 代码变量作用域错误 + fork 落后上游 46 commit | 修复变量 + 同步 fork |
| #7 | ❌ | FFmpegKit 依赖缺失 | 上传依赖到 Release + workflow 自动下载 |
| #8 | 🚫 取消 | Gradle 下载卡在阿里云镜像 | 改为官方源 |
| #9 | ❌ | `grepCodeWithRipgrep` 函数名笔误 | 改为 `grepCodeWithNativeRipgrep` |
| #10 | ✅ | — | `ci/build-debug` 分支编译通过 |
| #11 | ✅ | — | `fix/grep-ripgrep-typo` 分支编译通过 |
| #12 | ❌ | 3 个 Kotlin 编译错误 | 修复变量作用域 + override 规则 + 上游笔误 |
| #13 | ❌ | 对抗审查后发现 4 个遗漏，编译仍有错误 | 修复后重新提交 |
| #14 | ✅ | — | 全部编译通过，产出 debug APK |