# 上游正式版本审查与吸收手册

本手册用于在上游发布新的正式 APK 版本后，审查该正式版本相对上一审查边界的变化，选择性吸收到 `personal/dev`，并建立可恢复、可审计的检查点。

本流程只以实际发布的正式版本为批次边界，不追逐持续变化的 `upstream/main`。上游更新必须先进入 `personal/dev` 并完成验证；不得用上游分支重置或覆盖个人分支。

## 两类持久记录

- 本手册：保存长期不变的操作流程。
- `docs/agent/upstream-reviews/<version>.md`：保存单个上游正式版本的边界、候选功能簇、调用链证据、处置结论、实施状态和验证结果。

聊天记录不是审查状态的事实来源。每完成一个功能簇的调查、处置或验证，都立即更新对应版本台账；上下文压缩或换会话后先读取台账再继续。

## 1. 识别正式发布边界

先刷新远端和标签：

```powershell
git fetch origin --prune --tags
git fetch upstream --prune --tags
gh release list --repo AAswordman/Operit --limit 20 `
  --json tagName,publishedAt,isDraft,isPrerelease,name
```

候选版本必须同时满足：

1. GitHub Release 已发布，`isDraft=false` 且 `isPrerelease=false`。
2. Release 包含实际 Android 构建资产，而不只是版本号提交或 tag。
3. Release tag 能解析到固定 commit。
4. tag 中 `app/build.gradle.kts` 的 `versionName` / `versionCode` 与发布版本一致。

核对单个版本：

```powershell
gh release view <tag> --repo AAswordman/Operit `
  --json tagName,targetCommitish,publishedAt,isDraft,isPrerelease,name,url,assets
git rev-list -n 1 <tag>
git show <tag>:app/build.gradle.kts | Select-String 'versionCode|versionName'
```

`targetCommitish=main` 只说明 Release 的目标分支；审查边界使用 tag 实际解析出的 commit SHA。仅修改 `versionName` / `versionCode`、仅出现 release 字样或仅存在 CI artifact，都不能单独证明版本已经正式发布。

## 2. 确定上下界

上界是本轮正式 Release tag 的 commit。

下界按以下顺序确定：

1. 已完成过审查：读取最近一份已完成台账中的 `release_sha`。
2. 首次建立机制：使用当前 `origin/personal/dev` 与目标 Release tag 的 merge-base。

第一轮示例：

```powershell
$ReleaseTag = 'v1.12.1'
$ReleaseSha = git rev-list -n 1 $ReleaseTag
$PreviousReleaseSha = git merge-base origin/personal/dev $ReleaseSha
git log --first-parent --reverse --oneline "$PreviousReleaseSha..$ReleaseSha"
```

审查范围是 `<previous-release-sha>..<release-sha>`。`<release-sha>..upstream/main` 属于未进入该正式版本的变化，明确排除，留给后续正式版本批次。

## 3. 建立版本台账

在 `docs/agent/upstream-reviews/<version>.md` 记录：

- 上下界 tag、SHA、发布时间和 Release URL；
- APK 资产名、大小和服务端提供的 digest；
- 审查开始时的 `personal/dev` SHA；
- 范围内提交数、非 merge 提交数和一方主线批次数；
- 范围外的上游后续提交数；
- 每个功能簇的状态、结论、证据和验证；
- 计划及最终检查点名称。

版本台账顶部必须包含“恢复现场”命令和当前进度摘要，使新会话无需依赖聊天上下文即可继续。

## 4. 以功能簇为审查单位

不要按提交数量直接决定同步范围。先用一方主线日志识别 PR / 集成批次，再查看其中的非 merge 提交：

```powershell
git log --first-parent --reverse --date=short `
  --format='%h`t%ad`t%s' "<previous-release-sha>..<release-sha>"
git log --no-merges --reverse --date=short `
  --format='%h`t%ad`t%s' "origin/personal/dev..<release-sha>"
git cherry origin/personal/dev <release-sha>
```

`git cherry` 只识别 patch-id 等价，不能识别 squash、rebase、重写或由个人版更强实现覆盖的变化。最终结论必须结合真实调用链和最终代码树。

每个功能簇使用以下状态之一：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 尚未调查 |
| `INVESTIGATING` | 正在追踪实现和调用链 |
| `ADOPT` | 行为应吸收，预计可直接或小幅适配落地 |
| `ADAPT` | 目标有价值，但必须按个人版架构重新实现 |
| `EQUIVALENT` | `personal/dev` 已有等价或更强实现，不重复引入 |
| `DEFER` | 有价值，但本轮因依赖、风险或产品选择延后 |
| `SKIP` | 明确不属于个人版或没有同步价值 |
| `BLOCKED` | 缺少必要证据或外部条件，暂时无法下结论 |

`ADOPT` / `ADAPT` 表示审查结论，不表示已经实施；台账另行记录实施与验证状态。

## 5. 先证明运行路径，再决定保护和测试

对任何用户行为、持久化、兼容性、安全或回归相关变化，先在当前 `personal/dev` 证明：

1. 用户或系统入口；
2. ViewModel / service / repository / provider 等真实调用链；
3. 数据写入、外部请求、进程或文件边界；
4. 当前行为与上游变化的可观察差异；
5. 个人版已有实现、测试和产品约束。

运行路径未证明前，不新增 guardrail、迁移、兼容分支或回归测试。纯文档、资源或构建元数据也要先确认实际消费方，但可按其风险缩短调查。

每个功能簇在台账中至少写明：

- 上游意图与提交；
- 当前 dev 的入口和关键符号；
- 最终树差异；
- 个人版影响；
- 处置结论及理由；
- 若实施，需要的最小充分验证。

## 6. 分批实施

完成审查并确认处置后，从最新 `origin/personal/dev` 建立隔离 worktree 和独立分支。按功能簇分别实施，避免一次性 merge 整个 `upstream/main`。

- 低耦合且个人版代码未分叉：可 cherry-pick 后复核最终 diff。
- 与个人版重叠：按已证明的当前调用链手工适配。
- 上游身份、发布、市场、CI 或服务路由：默认逐项判断，不覆盖个人版配置。
- 已等价：记录对应个人版提交或符号，不制造空改动。
- 跳过：记录明确理由，下一版本默认不重复审查相同已决策内容，除非上游行为再次变化。

不回退未提交改动，不混入无关文件，不修改 `main` 只读镜像。提交、推送、PR 和标签发布仍分别需要用户明确授权。

## 7. 验证

按功能簇执行最小充分验证：

- 纯 Kotlin / Compose 变化优先编译相关模块；
- 有现成聚焦测试时运行对应测试；
- 数据、外部发布、权限、生命周期等变化按已证明路径设计验证；
- 原生依赖、完整 APK 和高风险聚合变化使用 Dev Quality / Nightly；
- 用户可见或真机相关变化在可共存的 Dev APK 中实际验证。

不能运行的验证要在台账标记为未运行及原因，不得描述为通过。

## 8. 完成条件与检查点

只有同时满足以下条件才结束本轮：

1. 范围内所有功能簇不再是 `PENDING` / `INVESTIGATING`。
2. 所有 `ADOPT` / `ADAPT` 项已实施，或经用户确认改为 `DEFER`。
3. 必要验证已完成并记录。
4. 台账记录最终 `personal/dev` SHA 和剩余风险。
5. 工作区和远端状态已复核。

选择性吸收不意味着 dev 包含上游 Release commit，因此检查点使用“已审查”语义：

```text
upstream-review/<release-tag>-<release-short-sha>
```

例如：

```text
upstream-review/v1.12.1-4faa5cd2a
```

annotated tag 指向包含本轮全部已采纳变化和最终台账的 dev 提交。标签说明至少记录：上游 Release tag/SHA、最终 dev SHA、台账路径和验证摘要。标签不得移动、覆盖或复用。

下一轮的下界是本台账记录的上游 `release_sha`，不是检查点 tag 所在的 dev commit；两条历史在选择性吸收流程中没有完整包含关系。

## 9. 恢复与复核

换会话或上下文压缩后：

1. 读取本手册；
2. 读取最新 `docs/agent/upstream-reviews/*.md`；
3. 核对台账中的上下界 SHA 仍能解析且 tag 未移动；
4. 核对当前分支、工作区、远端和台账记录的 dev 基线；
5. 从第一项 `PENDING` / `INVESTIGATING` 继续。

若 Release tag 被上游移动、资产被替换或 digest 改变，立即停止实施，将台账标记为 `BLOCKED`，重新确认可信边界。
