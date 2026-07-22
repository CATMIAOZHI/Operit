# dev → main 晋升操作手册

本手册供 Agent 在用户确认功能测试通过后，将 `personal/dev` 上的通用功能改动晋升到 `personal/main` 稳定分支。

## 前提

1. 功能已在 `personal/dev` 上构建并通过实际测试（可共存 debug APK）。
2. 用户已明确同意晋升。
3. `personal/dev` 工作区干净且与远端同步。

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
# 确认 applicationId 不含 .dev
grep -n "applicationIdSuffix" app/build.gradle.kts  # 应只在 debug 块中

# 确认 PERSONAL_DEV_UPDATE_CHANNEL 为 false
grep -n "PERSONAL_DEV_UPDATE_CHANNEL" app/build.gradle.kts

# 编译检查
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# 完整构建（如有条件）
./gradlew :app:assembleRelease
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

CI 通过且用户确认后，合并 PR（建议 squash 或 rebase）。合并后可将 `personal/main` 合并回 `personal/dev` 保持同步。

## 注意事项

- **不要直接 merge `personal/dev` 到 `personal/main`**：dev 分支包含大量开发版专属提交，直接 merge 会全部带入。
- **不要省略测试**：晋升 PR 必须通过 CI 必需检查，`personal/main` 受 Ruleset 保护。
- **上游更新方向相反**：上游更新走 `upstream/main → personal/dev`（先测试）→ `personal/main`，晋升走 `personal/dev → personal/main`，两条路径都经过 dev 验证，不要混用。
- **一次只晋升一个功能**：多个功能应分开 PR，便于回滚和审查。