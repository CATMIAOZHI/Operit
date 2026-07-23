# Operit 模型列表偏差修正实施计划

## 1. 背景与当前偏差

`personal/dev` 当前提交 `976c9913` 已实现模型配置排序、模型收藏、提供商折叠和版本化备份，但手机验收前发现以下产品语义偏差：

1. `collapsed_provider_ids` 同时控制 API 提供商列表和模型配置列表，导致折叠提供商时连带折叠其全部模型配置。
2. 收藏区显示 `provider:model`，实际需要显示 `模型配置名称：模型名称`。
3. 收藏区左侧实心星号仅作装饰，不能直接取消收藏。
4. 模型配置标题行显示星号；多模型配置的标题星号不可用，单模型配置又依赖标题星号收藏，交互不一致。

本计划只修正上述偏差，并保持已经完成的配置排序、具体模型收藏标识和提供商折叠能力。

## 2. 已确认的产品决策

### 2.1 提供商折叠

- 折叠对象是 API 提供商，持久化键继续使用 `collapsed_provider_ids`。
- 提供商折叠只影响 `ApiProviderDialog` 中的提供商列表。
- 搜索提供商时继续忽略折叠分区，显示全部匹配项。
- 提供商折叠不再影响模型配置选择弹窗、经典聊天模型选择器或 Agent 聊天模型选择器。
- ToolPkg 提供商继续使用规范化后的真实 `providerTypeId`。

### 2.2 模型配置折叠

- 新增独立的模型配置折叠状态，折叠对象是稳定的 `configId`。
- 折叠入口只放在设置页 `ModelConfigScreen` 的模型配置选择弹窗中。
- 每个模型配置行末尾提供独立的折叠或取消折叠按钮。
- 被折叠配置移入底部统一的“已折叠模型配置”分区，而不是仅收起其内部模型。
- 折叠结果同时反映到：
  - 设置页模型配置选择弹窗。
  - 经典聊天模型选择器。
  - Agent 聊天模型选择器。
- 折叠只改变展示分区，不禁用配置，不改变功能模型绑定，也不影响模型请求。
- 收藏区独立置顶；模型所属配置即使已折叠，收藏模型仍直接可见和可选。

### 2.3 收藏区标签与点击行为

- 收藏身份继续使用 `configId + modelName`，不改为显示文本或模型索引。
- 收藏行只显示 `配置名称：模型名称`，不显示 API provider，不追加 provider 重名判断信息。
- 标签由完整配置名称和模型名称生成，通过单行省略处理超长文本，不在数据层截断。
- 收藏行左侧实心星号是独立按钮：点击只取消收藏，不选择模型，不关闭选择器。
- 点击星号以外的收藏行区域：选择对应配置中的具体模型，并关闭选择器。
- 经典和 Agent 样式必须保持相同行为。

### 2.4 配置行与具体模型行

- 模型配置标题行不显示收藏星号。
- 所有配置点击标题行都只切换内部模型列表的展开状态。
- 单模型配置也展开一个具体模型行，不再点击配置标题直接选中模型。
- 具体模型行承担两个独立操作：
  - 点击文本或非星号区域：选择模型并关闭选择器。
  - 点击右侧星号：切换该具体模型的收藏状态，不选择模型，不关闭选择器。
- 正常配置区和“已折叠模型配置”区使用完全相同的配置展开、模型选择和收藏规则。

## 3. 范围与非目标

### 3.1 本次修改范围

- 独立持久化模型配置折叠状态。
- 调整版本化备份，分别保存提供商折叠和配置折叠。
- 设置页配置选择弹窗增加配置折叠入口。
- 经典和 Agent 聊天模型选择器改用配置折叠状态。
- 修正收藏区标签、取消收藏按钮和具体模型星号交互。
- 更新中英文字符串、纯逻辑测试和构建验证。

### 3.2 明确不做

- 不改模型请求链路、`FunctionConfigMapping` 或角色卡绑定格式。
- 不改 `ModelConfigData` 的模型参数字段和现有 `config_list` 排序语义。
- 不在聊天选择器中增加配置折叠按钮。
- 不增加收藏排序、配置分组、搜索模型或使用频率推荐。
- 不重构经典和 Agent 两套选择器为一个跨文件大型组件。
- 不顺带修复与本次偏差无关的历史 UI 问题。

## 4. 数据模型与持久化

### 4.1 `ModelConfigBackup`

修改文件：

`Operit/app/src/main/java/com/ai/assistance/operit/data/model/ModelConfigData.kt`

在备份对象中增加可选字段：

```kotlin
@Serializable
data class ModelConfigBackup(
    val version: Int,
    val configs: List<ModelConfigData> = emptyList(),
    val favoriteModels: List<FavoriteModelRef> = emptyList(),
    val collapsedProviderIds: List<String> = emptyList(),
    val collapsedConfigIds: List<String> = emptyList(),
)
```

字段默认空列表，使现有 v1 对象在新版中仍可反序列化。

### 4.2 配置分区纯函数

现有 `partitionConfigsByCollapsed()` 根据 `apiProviderTypeId` 分区，名称没有表达这种隐含语义。将配置列表投影改为按配置 ID：

```kotlin
fun partitionConfigsByCollapsedIds(
    configSummaries: List<ModelConfigSummary>,
    collapsedConfigIds: Set<String>,
): Pair<List<ModelConfigSummary>, List<ModelConfigSummary>>
```

规则：

1. `summary.id in collapsedConfigIds` 时进入折叠配置区。
2. 其他配置进入正常区。
3. 两个分区内部都保持 `configSummaries` 的全局顺序。
4. 不对 `configId` 做 lowercase；配置 ID 按现有精确值比较。
5. 提供商折叠继续使用 `normalizeProviderId()`，两套标识不得混用。

删除旧的 provider 驱动配置分区函数，避免后续调用方再次误用。保留 `isProviderCollapsed()` 和 provider 规范化逻辑供 API 提供商列表使用。

同时提取配置折叠合并纯函数，避免把兼容语义只留在 DataStore edit 中：

```kotlin
fun mergeCollapsedConfigIds(
    localIds: Set<String>,
    backupIds: Collection<String>,
    mergedConfigIds: Collection<String>,
): Set<String>
```

该函数只执行集合并集和有效 ID 过滤，不读取 Context 或 Preferences，供 v1/v2 导入逻辑和 JVM 单元测试共同使用。

### 4.3 `ModelConfigManager`

修改文件：

`Operit/app/src/main/java/com/ai/assistance/operit/data/preferences/ModelConfigManager.kt`

新增 Preferences 键：

```text
collapsed_config_ids
```

新增只读状态：

```kotlin
val collapsedConfigIdsFlow: Flow<Set<String>>
```

读取规则：

- JSON 解码为 `List<String>` 后过滤空 ID 并转成 `Set`。
- 配置 ID 保持原值，不使用 provider 规范化函数。
- JSON 损坏时沿用现有偏好读取策略，返回空集合，不影响配置本体。

新增写入接口：

```kotlin
suspend fun toggleConfigCollapsed(configId: String)
```

写入规则：

1. 空 ID 直接忽略。
2. 在一次 `modelConfigDataStore.edit` 内读取当前集合并切换成员状态。
3. 只修改 `collapsed_config_ids`，不得修改 `collapsed_provider_ids` 或 `config_list`。

### 4.4 生命周期清理

`deleteConfig(configId)` 的同一次原子 edit 中：

- 从 `config_list` 删除配置。
- 删除对应 `config_<id>`。
- 删除所有对应 `configId` 的收藏。
- 从 `collapsed_config_ids` 删除该 ID。
- 不修改 provider 折叠状态，因为 provider 可能仍存在并被其他配置使用。

创建、重命名和修改配置不改变折叠归属。

## 5. 备份版本与兼容策略

### 5.1 版本升级

- 将 `BACKUP_VERSION` 从 `1` 提升到 `2`。
- v2 导出同时包含 `collapsedProviderIds` 和 `collapsedConfigIds`。
- 如果保留 `version = 1`，旧客户端因 `ignoreUnknownKeys = true` 会静默忽略新增的配置折叠字段并造成恢复不完整。升级到 v2 后，旧客户端现有的 `version > BACKUP_VERSION` 检查会明确拒绝导入；这是用显式不兼容替代静默丢失状态。

### 5.2 导出

`exportAllConfigs()` 从同一个 Preferences 快照读取：

1. 按 `config_list` 顺序导出的配置。
2. 有效收藏列表及顺序。
3. 规范化的 provider 折叠 ID。
4. 当前配置集合中仍存在的折叠 config ID。

导出前不需要额外写回清理；仅在输出投影中过滤不存在的 config ID，避免导出幽灵折叠项。

### 5.3 v1、v2 与旧数组导入

- 旧版根数组 `List<ModelConfigData>`：保持现有行为，只导入配置，不修改收藏、provider 折叠或配置折叠。
- v1 对象：恢复配置、收藏和 provider 折叠；`collapsedConfigIds` 因字段缺失按空列表处理，不清空本地配置折叠状态。
- v2 对象：恢复并合并全部四类状态。
- `version <= 0` 或 `version > BACKUP_VERSION`：导入失败且不得产生部分写入。

### 5.4 配置折叠合并

版本化对象继续采用非破坏性合并：

1. 先完成备份配置与本地配置合并，得到 `mergedIds`。
2. v1 缺少配置折叠字段时，唯一规则为 `localCollapsedConfigIds.filter { it in mergedIds }`：保留本地有效项，只移除合并后已不存在的幽灵 ID，不追加备份项，也不清空有效状态。
3. v2 的唯一规则为 `(localCollapsedConfigIds + backup.collapsedConfigIds).filter { it in mergedIds }`。
4. 两个版本都通过 `mergeCollapsedConfigIds()` 实现，以版本判断决定传入空备份集合还是 v2 备份集合。
5. provider 折叠继续独立按规范化 ID 取并集。
6. 全部结果在现有单次 DataStore edit 中提交。

## 6. 设置页模型配置选择弹窗

修改文件：

`Operit/app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/ModelConfigScreen.kt`

### 6.1 状态接入

- 将页面收集的 `collapsedProviderIdsFlow` 替换为 `collapsedConfigIdsFlow`。
- `ConfigSelectDialog` 参数改为 `collapsedConfigIds` 和 `onToggleConfigCollapsed`。
- 使用 `partitionConfigsByCollapsedIds()` 生成正常配置和折叠配置。
- 标题从“已折叠提供商”改为“已折叠模型配置”。

### 6.2 配置行结构

每个正常配置行：

1. 左侧保留拖动手柄。
2. 中间保留配置名称、provider 和模型摘要。
3. 当前配置保留选中标记，位于折叠按钮左侧。
4. 最右侧增加独立“折叠配置”按钮。
5. 点击折叠按钮不得触发配置选择或关闭 Dialog。

每个已折叠配置行：

1. 不显示拖动手柄，沿用当前折叠区不可拖动规则。
2. 保留配置选择能力。
3. 行末增加独立“取消折叠配置”按钮。
4. 点击后配置立即回到正常区。

按钮图标沿用 API provider 行现有的 `+/-` 视觉语言，避免引入另一套折叠表达。

### 6.3 排序约束

- 正常区继续允许拖动排序。
- 折叠区不允许拖动。
- 拖动计算基于 `collapsedConfigIds`，不再受 provider 状态影响。
- 持久化的 `config_list` 仍包含所有正常和折叠配置。
- 当前拖动回调从可见 `flatItems` 读取折叠配置；折叠分区收起时该集合为空。`normalizeConfigOrder()` 虽会把缺失 ID 补回，但会将折叠配置追加到 `config_list` 末尾并破坏其全局相对顺序。
- 修复点明确位于 `ConfigSelectDialog` 的拖动回调：`collapsedIds` 必须从完整的 `collapsedConfigs` 分区读取，不能从只包含可见行的 `flatItems` 读取。生成的新顺序必须包含所有配置，并保持未参与拖动的折叠配置相对顺序。
- 本次不扩大范围重做实时拖动动画；只确保改用配置折叠后排序数据正确。

## 7. 经典聊天模型选择器

修改文件：

`Operit/app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/style/input/classic/ClassicChatSettingsBar.kt`

### 7.1 状态参数

- 页面收集 `collapsedConfigIdsFlow`。
- `ModelSelectorItem` 参数由 `collapsedProviderIds` 改为 `collapsedConfigIds`。
- 使用 `partitionConfigsByCollapsedIds()`。
- 折叠分区标题显示“已折叠模型配置 (%d)”。

### 7.2 收藏行

每项结构改为一个 `Row`，避免父级整行 clickable 包裹星号：

1. 左侧 `IconButton` 显示实心星号。
2. 星号 `onClick` 调用 `onToggleFavorite(fav.configId, fav.modelName)`。
3. 星号 content description 使用“取消收藏模型”。
4. 右侧文本区域使用单独 `Modifier.clickable` 选择收藏模型。
5. 标签使用资源格式 `%1$s：%2$s`，参数为 `favConfig.name` 和 `fav.modelName`。
6. 收藏引用已由 `resolveValidFavorites` 过滤；仍保留索引解析失败时不选择的安全分支。
7. AutoGLM 检查继续只发生在选择路径，取消收藏不触发警告。

### 7.3 配置和模型行

- 删除正常配置标题行中的 `uniqueModel`、`isFav` 和星号按钮。
- 配置标题点击统一切换 `expandedConfigId`，不再根据模型数量直接选择。
- 展开内容对单模型和多模型使用同一循环：`modelList.forEachIndexed`。
- 单模型配置展开后显示一条具体模型行及星号。
- 每个具体模型行将选择区域和星号按钮分开，避免星号点击触发模型选择。
- 折叠配置区不能保留简化版实现；必须具备与正常区相同的具体模型星号和选择行为。
- 正常区和折叠配置区的单模型标题点击都只切换展开，不得直接选择；AutoGLM 检查只在具体模型行的选择路径触发。
- 在 Classic 文件内部提取一个配置项 Composable，供正常区和折叠配置区复用展开、模型选择、收藏和 AutoGLM 逻辑；该复用不跨 Classic/Agent 文件，避免扩大为大型 UI 重构。
- 具体模型行星号根据状态提供“收藏模型”或“取消收藏模型”的 content description。

## 8. Agent 聊天模型选择器

修改文件：

`Operit/app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/style/input/agent/AgentChatInputSection.kt`

按经典样式的同一规则修改：

- 收集并传递 `collapsedConfigIds`。
- 配置分区按 config ID，而不是 provider ID。
- 收藏标签使用配置名称和模型名称。
- 收藏星号可独立取消收藏。
- 配置标题行不显示星号，只控制展开。
- 单模型配置也展开具体模型行。
- 正常区和折叠配置区的具体模型行都支持选择与收藏。
- 折叠配置区的单模型标题点击也只切换展开，不得直接选择；AutoGLM 检查只在具体模型行选择时触发。
- 在 Agent 文件内部提取一个配置项 Composable，供正常区和折叠配置区复用，避免两份近似实现再次产生行为偏差。
- 具体模型行星号根据状态提供“收藏模型”或“取消收藏模型”的 content description。
- 保留 Agent 现有容器颜色、尺寸和 AutoGLM 提示方式，不强行与 Classic 合并视觉组件。

## 9. 字符串资源

只修改：

- `Operit/app/src/main/res/values/strings.xml`
- `Operit/app/src/main/res/values-en/strings.xml`

新增或调整以下语义：

| Key 建议 | 中文 | English |
| --- | --- | --- |
| `collapsed_model_configs` | 已折叠模型配置 | Collapsed model configs |
| `collapsed_model_configs_count` | 已折叠模型配置 (%d) | Collapsed model configs (%d) |
| `collapse_model_config` | 折叠模型配置 | Collapse model config |
| `uncollapse_model_config` | 取消折叠模型配置 | Uncollapse model config |
| `add_model_to_favorites` | 收藏模型 | Add model to favorites |
| `remove_model_from_favorites` | 取消收藏模型 | Remove model from favorites |
| `favorite_model_label` | `%1$s：%2$s` | `%1$s: %2$s` |

继续保留 provider 专用字符串，API provider Dialog 不改变语义。不修改其他语言资源。

## 10. 测试计划

### 10.1 纯函数测试

修改：

`Operit/app/src/test/java/com/ai/assistance/operit/data/preferences/ModelListPreferencesTest.kt`

将旧的 provider 驱动配置分区测试替换为：

1. 按一个具体 config ID 折叠时，只移动该配置；相同 provider 的其他配置保持正常。
2. 多个不同 provider 的配置可独立折叠。
3. 正常区与折叠区各自保持源顺序。
4. 空折叠集合返回全部正常配置。
5. 不存在的折叠 config ID 不影响列表。
6. config ID 使用精确匹配，不进行大小写归一化。

保留 provider 规范化和 `isProviderCollapsed` 测试，证明 provider 折叠逻辑仍独立存在。

### 10.2 Manager 与备份逻辑检查

使用 `mergeCollapsedConfigIds()` 纯函数覆盖折叠 ID 合并语义，不为本次小改动引入完整 Android DataStore 仪器测试框架。JUnit 至少覆盖：

1. 本地与 v2 备份配置折叠取并集。
2. 不存在于合并配置列表中的 ID 被过滤。
3. v1 缺失字段时保留本地有效配置折叠。

以下写入隔离属性由 manager 代码审查、完整 diff 和手工验收确认，不将其伪装成纯函数单测：

4. `deleteConfig` 的同一次 edit 清理对应配置折叠 ID。
5. `toggleProviderCollapsed` 与 `toggleConfigCollapsed` 只写各自 Preferences 键。

### 10.3 编译与单元测试

按顺序执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.ai.assistance.operit.data.preferences.ModelListPreferencesTest"
.\gradlew.bat :app:compileDebugKotlin
```

若资源或 Compose 检查出现可疑问题，再运行：

```powershell
.\gradlew.bat :app:lintDebug
```

不在本地运行完整 release 构建。

## 11. 手工验收清单

### 11.1 两套折叠状态独立

- 在 API provider Dialog 折叠一个被多个配置使用的提供商。
- 该 provider 进入“已折叠提供商”分区。
- 对应模型配置在设置页和聊天选择器中不发生移动。
- 在模型配置选择弹窗折叠其中一个配置。
- 只有该配置进入“已折叠模型配置”分区，同 provider 的其他配置保持原位。
- provider Dialog 不受配置折叠影响。
- 分别取消两种折叠，确认互不修改对方状态。
- 重启应用后两套状态分别保持。

### 11.2 配置折叠入口

- 正常配置行可点击折叠按钮，且不会误选配置或关闭弹窗。
- 已折叠配置仍可被选中。
- 已折叠配置可点击取消折叠按钮恢复正常区。
- 折叠分区默认收起，展开状态不要求跨页面持久化。
- 当前正在使用的配置即使折叠，聊天顶部当前模型摘要仍正常显示。

### 11.3 收藏区

- 标签准确显示 `配置名称：模型名称`，不出现 provider ID。
- 点击收藏行文本可选择正确的 config ID 和模型索引。
- 点击左侧星号只取消收藏，不改变当前模型，不关闭选择器。
- 取消后收藏项立即从 Classic 和 Agent 的收藏区消失。
- 所属配置已折叠时，收藏项仍在顶部显示。
- AutoGLM 收藏项点击选择时仍显示警告；点击星号取消收藏不显示警告。

### 11.4 配置和具体模型星号

- 单模型配置标题行没有星号，点击后展开唯一模型。
- 唯一模型行可收藏、取消收藏和选择。
- 多模型配置标题行没有星号，点击后展开全部具体模型。
- 每个具体模型的星号独立工作，不触发模型选择。
- 正常配置区和已折叠配置区行为一致。
- 空模型列表配置展开后不崩溃、不出现无效收藏按钮。

### 11.5 排序与备份

- 折叠部分配置后拖动正常区，所有配置 ID 均保留且顺序正确。
- 折叠区收起时拖动，隐藏配置不能被追加到全局顺序末尾，其原有相对顺序必须保持。
- v2 导出同时包含 provider 和 config 折叠字段。
- 导入 v1 对象后保留本地配置折叠状态。
- 导入 v2 对象后恢复两套独立折叠状态。
- 导入旧数组不修改收藏和任何折叠状态。

## 12. 风险与控制

### 12.1 持久化兼容

新增 Preferences 键不会迁移或覆盖现有 `collapsed_provider_ids`。备份字段使用默认值保证新版可读取 v1；版本提升到 v2，使旧客户端对含新字段的备份显式拒绝，而不是在仍标记 v1 时静默忽略配置折叠状态。

### 12.2 隐藏配置在排序时被重排

当前扁平 UI 列表在折叠分区收起时不包含折叠配置。若排序结果仅从可见 `flatItems` 构建，`normalizeConfigOrder()` 会补回这些 ID，但会把它们追加到末尾，破坏全局相对顺序。拖动回调必须直接从完整 `collapsedConfigs` 分区构建折叠部分，并继续交给 `normalizeConfigOrder()` 做防御性校验。

### 12.3 Compose 嵌套点击

收藏星号、模型星号、配置折叠按钮、配置选择和模型选择必须使用独立点击区域。避免在整行父容器 clickable 内依赖事件冒泡行为，优先让 IconButton 与文本点击区域成为同级元素。

### 12.4 Classic 与 Agent 行为漂移

两套 UI 不做大型重构，但应按同一验收矩阵逐项检查。任何一侧的正常区与折叠区都不能保留缺少星号的简化模型行。

### 12.5 单模型交互变化

单模型配置从“一次点击直接选择”改为“点击配置展开，再点击具体模型选择”。这是为了满足星号只属于具体模型的已确认设计，不再保留标题行快捷选择路径。

## 13. 推荐实施顺序

1. 在 `ModelConfigData.kt` 增加 `collapsedConfigIds`，实现按 config ID 分区和必要的纯合并函数。
2. 在 `ModelConfigManager.kt` 增加键、Flow、切换接口、删除清理和备份 v2 支持。
3. 更新 `ModelListPreferencesTest.kt`，先验证配置分区与兼容合并规则。
4. 修改 `ModelConfigScreen.kt`，切换到配置折叠状态并增加折叠按钮。
5. 修改 Classic 收藏区、配置展开和具体模型行。
6. 修改 Agent 对应路径，保持与 Classic 行为一致。
7. 补齐中英文资源和 content description。
8. 运行定向单元测试和 Kotlin 编译。
9. 审查完整 diff，重点检查两套折叠键没有混用、隐藏配置没有在排序中被移到末尾、星号点击不触发选择。
10. 经用户明确要求后再提交并推送 `personal/dev` 触发 Nightly；本计划本身不自动提交或推送。

## 14. 完成标准

满足以下全部条件才视为修正完成：

- provider 折叠只影响 provider Dialog。
- config 折叠按单个 config ID 工作，并反映到设置页、Classic 和 Agent。
- 收藏区显示 `配置名称：模型名称`。
- 收藏区星号可以独立取消收藏。
- 配置标题行不显示星号。
- 单模型和多模型都只在具体模型行提供收藏星号。
- 正常区与已折叠配置区的模型选择和收藏行为一致。
- 删除配置不留下收藏或配置折叠幽灵记录。
- v1、v2 和旧数组备份行为符合兼容规则。
- 定向测试和 `:app:compileDebugKotlin` 通过。
- 未修改模型请求链路、功能模型绑定或配置参数格式。
