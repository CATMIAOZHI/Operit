# dev → main 晋升操作手册

本手册供 Agent 在用户确认功能测试通过后，将 `personal/dev` 上的通用功能改动晋升到 `personal/main` 稳定分支。

## 前提

1. 功能已在 `personal/dev` 上构建并通过实际测试（可共存 debug APK）。
2. 用户已明确同意晋升。
3. `personal/dev` 工作区干净且与远端同步。

## 晋升检查点

每次晋升完成后，将最新 `personal/main` 回合并到 `personal/dev`，并在合并提交上创建不可复用的 annotated tag：

```bash
promotion-checkpoint/main-<YYYYMMDD>-<main-short-sha>
```

该标签同时表示：

- 标签所在提交已经包含当时的 `personal/main`；
- 标签之前的 dev 通用改动已经完成晋升审查；
- 下一次晋升默认只需要审查该标签之后的 dev 改动。

开始下一轮晋升时先定位最近检查点：

```bash
git fetch origin personal/main personal/dev --tags
CHECKPOINT=$(git describe \
  --tags \
  --match 'promotion-checkpoint/*' \
  --abbrev=0 \
  origin/personal/dev)

git log --oneline "$CHECKPOINT"..origin/personal/dev
git diff --stat "$CHECKPOINT"..origin/personal/dev
git diff "$CHECKPOINT"..origin/personal/dev
```

检查点只缩小审查范围，不替代以下检查：

- 确认标签可从当前 `personal/dev` 到达；
- 确认标签说明中记录的 main SHA 与上一轮实际晋升结果一致；
- 继续排除检查点之后新增或修改的开发版专属文件；
- 如果 `personal/main` 在检查点之后又有其他改动，先将最新 main 回合并到 dev 并建立新检查点。

检查点标签不得移动、覆盖或复用；每轮晋升创建一个新标签，以保留可审计历史。

## 步骤

### 1. 确认状态

```bash
git checkout personal/dev
git pull origin personal/dev
git log --oneline -20
```

逐条审视最近的提交，标记哪些是通用功能、哪些是开发版专属改动。

### 2. 更新 personal/main

```bash
git checkout personal/main
git pull origin personal/main
```

### 3. 创建晋升分支

```bash
git checkout -b promote/<feature-name>   # 从 personal/main 创建
```

### 4. 挑选通用功能提交

从 `personal/dev` 中 cherry-pick 只包含通用功能的提交：

```bash
git cherry-pick <commit-sha>
# 或多个连续提交
git cherry-pick <oldest-sha>^..<newest-sha>
```

如果某次提交混入了开发版专属改动，需要手动修正后再继续。

### 5. 排除开发版专属文件

以下文件和改动**不得**出现在晋升 PR 中：

| 类型 | 文件 |
|---|---|
| 开发包名 | `app/build.gradle.kts` 中的 `applicationIdSuffix = ".dev"`、`versionNameSuffix`、`resValue("string", "app_name", "Operit Ry Dev")`、`buildConfigField("boolean", "PERSONAL_DEV_UPDATE_CHANNEL", "true")` |
| DEV 图标 | `app/src/debug/res/` 下的 `ic_launcher_dev_badge.xml`、`ic_launcher_simple_foreground_dev.xml` 等 |
| 开发版热更新 | `app/src/main/java/.../data/updates/UpdateManager.kt` 和 `PatchUpdateInstaller.kt` 中的 `personal-dev` channel 逻辑 |
| 差分更新工具 | `tools/hotbuild/build_patch.py`、`tools/hotbuild/publish_dev_update.py` |
| Nightly 子模块 | `tools/hotbuild/OperitNightlyRelease` 子模块指针 |
| ccache/CI 专属 | Nightly workflow 中的 ccache 配置 |

如果 cherry-pick 时这些文件被带入，使用不提交的 cherry-pick + 精确恢复：

```bash
git cherry-pick -n <commit-sha>
git restore --source=HEAD --staged --worktree -- <dev-only-file>
git commit
```

若 cherry-pick 已经提交完成，可从父提交恢复并 amend：

```bash
git restore --source=HEAD^ --staged --worktree -- <dev-only-file>
git commit --amend --no-edit
```

### 6. 验证

```bash
# === 拒绝所有 personal/dev 身份配置（无论是否在 debug 块中）===

# 以下任何匹配均应视为晋升失败，对应提交需重新 cherry-pick 并排除：
#   applicationIdSuffix = ".dev"
#   versionNameSuffix = "-dev"（或含 -dev 的任何变体）
#   resValue("string", "app_name", "Operit Ry Dev")
#   buildConfigField("boolean", "PERSONAL_DEV_UPDATE_CHANNEL", "true")
#   app/src/debug/res/ 下 *_dev_* 图标文件

grep -En 'applicationIdSuffix\s*=\s*"\.dev"|versionNameSuffix\s*=\s*".*-dev|app_name.*Operit Ry Dev|PERSONAL_DEV_UPDATE_CHANNEL.*true' \
  app/build.gradle.kts && echo "FAIL: dev 身份配置残留" && exit 1 || true

test -f app/src/debug/res/drawable/ic_launcher_dev_badge.xml \
  && echo "FAIL: DEV 图标残留" && exit 1 || true

# 注意：clone 配置（applicationIdSuffix = ".clone"、Operit Ry Clone）可以在
# 稳定分支中保留；上述检查只针对 dev 专用身份。

# 编译检查
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# 完整 release 构建与 APK 验证交给 GitHub Actions（PR Check / Nightly）
```

### 7. 推送并创建 PR

```bash
git push origin promote/<feature-name>
gh pr create --base personal/main --head promote/<feature-name> \
  --title "<type>: <description>" \
  --body "从 personal/dev 晋升已通过测试的功能。"
```

### 8. 等待 CI 并合并

```bash
gh run watch <run-id> --exit-status --interval 60 >/dev/null 2>&1
```

CI 通过且用户确认后，合并 PR（建议 squash 或 rebase）。合并后 `sync-main-mirror.yml` 会将同一 commit 自动快进到只读 `main` 镜像；也可将 `personal/main` 合并回 `personal/dev` 保持开发线同步。

### 9. 回合并 main 并建立新检查点

晋升 PR 合并后，将稳定分支回合并到开发分支。由于晋升 PR 可能经过 squash，出现等价代码冲突时，通用代码以通过审查的 `personal/main` 为准，同时保留 dev 专属身份、Nightly 和热更新配置。

如果 dev 仍有未包含在本轮晋升 PR 中的通用改动，合并前必须按从旧到新的顺序列出对应提交。每个提交只能属于一个待晋升功能，且不得混入 dev 专属配置；混合提交必须先通过 revert 和分块重提拆成单一职责的替代提交，不能继续建立检查点。

检查点提交本身只允许包含“最新 main 通用代码 + dev 专属设施”。因此先按逆序暂时 revert 待晋升提交，再回合并 main；标签创建后，按原顺序逐提交 cherry-pick。这样即使通用改动和 dev 专属配置位于同一文件，也只移动待晋升提交实际修改的 hunk，并保留独立功能的提交边界。

```bash
git fetch origin personal/main personal/dev --tags
git checkout personal/dev
git pull --ff-only origin personal/dev

# 仅在仍有待晋升通用改动时执行本段。
# 提交按从旧到新排列，并逐个检查不含 dev 专属配置或其他功能。
PENDING_COMMITS=(<oldest-commit> ... <newest-commit>)
for commit in "${PENDING_COMMITS[@]}"; do
  git show --stat --patch "$commit"
done

# 逆序移除待晋升功能，使检查点树不包含它们。
for ((i=${#PENDING_COMMITS[@]}-1; i>=0; i--)); do
  git revert --no-commit "${PENDING_COMMITS[$i]}"
done
git diff --name-only --diff-filter=U  # 必须无输出
git commit -m "chore(dev): suspend pending features for checkpoint"

# 必须停在提交前；不得省略 --no-commit。
git merge --no-ff --no-commit origin/personal/main

# 解决冲突：通用代码采用 main，dev 专属设施继续保留。
git diff --name-only --diff-filter=U  # 必须无输出
git commit -m "chore(dev): sync personal/main checkpoint"
```

在合并提交上创建 annotated tag；若暂时移除了待晋升提交，随后按原顺序逐个重放：

```bash
MAIN_SHA=$(git rev-parse origin/personal/main)
MAIN_SHORT=$(git rev-parse --short=8 origin/personal/main)
CHECKPOINT="promotion-checkpoint/main-$(date +%Y%m%d)-$MAIN_SHORT"

git tag -a "$CHECKPOINT" \
  -m "Promotion checkpoint: personal/main $MAIN_SHA"

for commit in "${PENDING_COMMITS[@]}"; do
  git cherry-pick "$commit"
done
```

若 cherry-pick 发生冲突，必须保留 main 已审查修复和 dev 专属配置，只重放该提交所属功能的 hunk，验证后执行 `git cherry-pick --continue`。若没有待晋升通用改动，应省略 `PENDING_COMMITS`、revert 和 cherry-pick 段。确认 dev 专属配置仍然存在后，验证最终 dev 工作树：

```bash
git merge-base --is-ancestor origin/personal/main personal/dev
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

最后原子推送 dev 与检查点标签，避免只发布其中一个：

```bash
git push --atomic origin personal/dev "$CHECKPOINT"
```

确认远端标签指向本次回合并提交，并且标签说明记录了准确的 main SHA；`git diff "$CHECKPOINT"..origin/personal/dev` 应只显示标签之后尚未晋升的改动。原子推送失败时不得把该轮视为已建立检查点，应排查分支并发更新或远端规则后整体重试。

## 注意事项

- **不要直接 merge `personal/dev` 到 `personal/main`**：dev 分支包含大量开发版专属提交，直接 merge 会全部带入。
- **不要省略测试**：晋升 PR 必须通过 CI 必需检查，`personal/main` 受 Ruleset 保护。
- **上游更新方向相反**：上游更新走 `upstream/main → personal/dev`（先测试）→ `personal/main`，晋升走 `personal/dev → personal/main`，两条路径都经过 dev 验证，不要混用。
- **一次只晋升一个功能**：多个功能应分开 PR，便于回滚和审查。
