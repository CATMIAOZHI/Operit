# 聊天交互卡片

## 使用

启用「交互卡片」工具包。AI 先调用 `dynamic_ui.read_guide`，再在普通 Markdown
之间输出 `a2ui` 代码围栏。指南含可直接运行的「AI 参数示例」：默认 Medium，
补充说明可编辑，保存后保留可读表单，可再次编辑或取消修改。
这个示例只保存卡片，不修改真实模型配置。
`read_example("calculator")` 返回按键式四则计算器；
`read_example("cost_calculator")` 返回本地费用试算；`read_example("charts")`
返回折线、柱状、饼图、散点四类演示数据。每个围栏必须自带完整初始化；
后续围栏不能更新前一个围栏的 surface。

卡片原文随聊天消息保存，本地字段值以原子文件保存在应用私有 `dynamic_ui/`。
按聊天、消息时间戳、回答版本、围栏位置、源内容和 surface ID 隔离。
重开恢复字段；编辑或重新生成卡片后使用新版本。删除聊天会清理其卡片状态。
此轮未将侧边状态加入聊天导出/跨设备同步，导入聊天会使用协议中的初始值。
Expandable 可将 `expanded` 绑定数据路径，跨页面恢复展开状态。

输入不请求模型。只有明确点击 `ai_submit` / `tool_action` 并确认，才生成
当前聊天的一条用户消息；聊天忙碌、切换聊天、聊天只读或消息已被编辑时拒绝提交。
输入框的文本、附件和回复草稿不被卡片消耗。`tool_action` 是正常工具操作请求，
不会直接运行工具，也不绕过自动审核和权限检查。

## 官方集成

固定 AndroidX A2UI alpha01，使用其 parser、processor、数据绑定和 Compose surface。
协议固定 **v0.9**；自有目录 ID 为 `https://operit.ai/a2ui/catalog/v1`。
这是标识符，不是网络加载地址。没有第三方 React、WebView 或脚本执行路径。

- `DynamicUiProtocol`：围栏事件校验、静态图和资源边界。
- `DynamicUiCatalog`：官方基础组件，以及原生 Form/Table/Grid 等目录扩展。
- `DynamicUiCard`：按序增量入队、恢复屏障、动作预览、提交和局部错误。
- `DynamicUiStateStore`：原子写入、合并待写状态和聊天删除清理。
- `CanvasMarkdownNodeRenderer`：仅识别明确的 `a2ui` 语言围栏。
- `ChatViewModel.submitDynamicUi`：再次核对会话、消息和版本，复用原聊天发送链路。

P0 支持 Card、Text、Heading、Badge、Icon、Row、Column、Grid、Divider、
Button、Progress、List、Table、Code。
P1 支持 TextField、TextArea、CheckBox、RadioGroup、Select、Slider、Switch、
DatePicker、Form、Expandable。Select 和 DatePicker 在卡片内展开，不创建 Activity
弹窗；悬浮窗不能自行提交到某个猜测的会话。

扩展目录支持：

| 能力 | 实现和边界 |
| --- | --- |
| 排版与导航 | 响应式 Grid、分段选择、Tabs、可滑动 Carousel、Conditional 条件显示 |
| 富文本 | RichText 的结构化字重、斜体、下划线、主题色、字号及安全链接；不是 HTML |
| 图表 | 原生 Canvas 折线、柱状、饼图、散点；点击查看数值、展开数据表；最多 4 系列、每系列 128 点 |
| 本地计算 | 官方动态函数绑定，支持四则运算、比较、布尔、条件值、格式化、字符串组合；不请求模型 |
| 媒体 | 图片、音频、视频，用户点击后才加载；不自动播放；离开可见区域或应用后台暂停播放器 |
| 数据卡片 | 商品、地点、网页、文件链接；数据由用户/工具提供，不保证实时；文件入口仅 http(s) |
| 地图 | 地点坐标示意、按顺序连线、外部地图入口；不含道路底图、路线规划或导航 |
| 草稿 | Draft 本地编辑、保存、复制；需要发送给 AI 时另放明确提交按钮，不会发送邮件或发布文章 |

公式、Mermaid、Markdown 表格继续使用既有普通消息渲染；不在 A2UI 内嵌套
Markdown 围栏或工具标记。不引入任意脚本执行、自由 HTML、设备权限或隐式请求。
以上是 Operit 自有目录能力，不声称使用了 ChatGPT 私有 UI SDK。

按键计算器仍由 Form/Grid/Button 等普通组件组合，不新增固定 Calculator 界面组件。
`calculatorStep(state,key)` 是有界纯函数；按键通过已有 `local_action/set` 原子替换
该计算器状态。支持小数、退格、清除、正负切换、连续运算、除零提示和错误后重输。
结果保留 12 位有效数字，绝对值不超过 1e12，普通十进制最多 24 位小数；
超范围显式提示错误。模型生成的科学计数法状态不会进入巨量字符串格式化。

`Grid.columnCount` 可显式指定 1～4 列，窄屏保留触摸宽度并允许横滚；
`Slider.steps` 是中间停靠点数量，0 表示连续，例如 0～100 整数用 99。
TextField 的数字字符串可以直接交给算术函数，不必为了计算改成滑块。

动作限定 `local_action`、`ai_submit`、`tool_action`、`open_url`、`copy`。
本地操作只允许 set/add/save/edit/cancel；不支持任意代码、网络函数、正则、递归模板、
远端目录或隐式操作。open_url 限 http/https，不能含 URL 凭据。
未完成事件不进入官方 parser；完整事件可显示，围栏结束且恢复完成后启用交互。
重放历史不会执行动作。独立 surface 顺序消费；尾部保留标记用于确认恢复已完成。

最大 128K 源字符、256 事件、4 surface、每 surface 128 组件、
32 个静态子节点、12 层组件、512 个展开实例；表格 8 列/32 行，
数据状态 32K 字符，单字段 8192 字符。超过限制显示错误，不请求 AI 自动修复。
状态写入失败明确提示，表单过大阻止保存/提交。

纯函数白名单：add/subtract/multiply/divide/min/max、equals/greaterThan/lessThan、
and/or/not、if、formatNumber、concat、calculatorStep。最多 6 层嵌套、128 次调用声明，
只在动态属性/检查/动作上下文使用；初始数据及组件结构不含函数。数值必须有限且
绝对值不超过 1e12，除零/无效数字得到未解析值，修正输入后可恢复。if 参数是急切求值。
表单检查使用 Button.checks；不会执行正则。所有函数无 I/O、无工具访问、无状态写入。
动态卡片保存后禁用字段修改，但仍可切换浏览区、查看图表、复制或打开链接。
提交预览按字段标签展示实际发送的值，确认后仍走正常聊天、工具审核流程。

未开放任意画布代码、文件上传、内部文件 URI、内嵌网页和模态窗口。
新增能力通过目录组件和安全边界扩展，不把任意 JSON 当界面。
包指南按需读取，不在系统前缀塞入完整 schema。

## 真机反馈修复

2026-10-11 用户测试发现 `Icon(name:"dashboard")` 报错时宿主隐藏整张卡片；
另一次生成含错拼的 `updataDataModelPlaceholder`，且把初始化分散在两个围栏。
后者是无效协议，不应把缺失的初始化猜成有效卡片。

- 非内置图标局部占位，不使无关字段失效；其他组件错误保留可显示内容，
  只锁定出错 surface 的动作。
- 协议错误保留行号供展开诊断，运行日志只记录阶段/标识/错误类型，不记录表单内容。
- 指南明确单围栏完整初始化、已知图标名称和组件属性。
- 去掉重复卡片标题/边框和大号源码按钮；表单单列、选项整行可点、
  按钮有主次、保存后不显示原始 JSON；源码入口缩为小图标。
- 图表按实际柱形位置命中，坐标间距按屏幕密度计算；无效数据有局部反馈。

第二次真机计算器反馈：首次生成的 NDJSON 第 2 行括号不配对；重写后计算正常，
但模型把连续滑块的小数结果格式化为整数，造成 `3.219 + 3.814 → 7` 的误解。
不是响应式引擎失效，也没有达到函数嵌套深度上限。
新增 `validate_card(source)`，经 NativeInterface 调用与渲染入口相同的协议校验器，
只返回安全错误码/真实行号，不执行卡片。自定义文档显示前必须调用校验，
示例优先直接复用；每张独立卡片推荐独立围栏，减少坏文档的连带影响。
检查成功只表示协议入口通过，不保证视觉布局或业务计算正确。

本地状态动作在宿主消费事件时重新解析原始绑定，随后无挂起地写回，避免排队按键
基于同一旧快照计算；发送给 AI/工具请求继续使用点击时快照，确认预览不变。

## 工具链与验证范围

AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.20 / compileSdk 37.1，
Compose BOM alpha 2026.09.00（UI 1.13.0-alpha03、Material3 1.5.0-alpha28）。
targetSdk、minSdk、版本、签名均不改变。官方 AAR 最低 AGP 9.1，
但 AGP9.1 文档测试上限是 API37.0，本地构建仍会保留该警告。

升级对既有下拉菜单、思考滑块、过渡动画及本地 LazyLayout 预取做了 API 迁移，
不能把改动范围描述为「仅影响新卡片」。工具链编译通过与真机体验通过是两回事。
主仓库 CI 已同步 SDK/Build Tools/NDK；外部 Nightly 发布仓库仍需在发布前同步，
本任务不修改公开子模块、不推送。

调研基线：AndroidX A2UI、a2ui-project/a2ui、uny/a2ui-compose、
JAICHANGPARK/droidkaigi2026-a2ui、Vercel json-render。依赖采用官方 AndroidX；
没有复制其他项目的渲染引擎。实际发布源码与 Maven metadata 优先于示例旧 README。

2026-10-11 初版验证：工具链单独编译通过，接入后编译、8 项定向 JVM 测试及
`assembleDebug` 通过。官方引擎测试使用发布版 parser/processor，JVM 中仅将
Android JsonReader 替换为 Gson token reader。APK 内指南与源码一致；
包名仍为 `com.rainy.operitry.dev`，versionCode 100205、minSdk 26、targetSdk 34。
初版独立只读代码审计结论 CLEAN，之后按用户要求覆盖安装并取得上述真机反馈。
扩展版编译、14 项定向 JVM 测试通过；代码安全与 UIUX 独立只读复审均为 CLEAN。
测试包括官方引擎计算器 41.84 → 37.66 → 94.14 的状态重算且不产生动作、
四类图表的官方引擎回放、协议及资源边界、无效数字恢复、提交预览标签/数值。
`node examples/tests/dynamic_ui_examples.cjs` 核对包输出、JVM 示例和打包资产一致。
这些是协议/逻辑验证，不是截图和触摸测试。扩展版已于 2026-10-11 17:26 覆盖安装，
保留应用数据，之后收到上述计算器反馈。
后续计算器修复：编译及 19 项定向测试通过，包含官方 action 队列连续输入
`12 × 34 = 408`、`0.1 + 0.2 = 0.3`、除零恢复、极端指数防护及真机坏 JSON。
本批独立只读审计 CLEAN；这批修复尚未再次安装真机。
未执行完整 Lint；
Compose/A2UI 使用 alpha 依赖，发布前仍需检查既有输入栏、下拉菜单和长聊天滚动体验。
