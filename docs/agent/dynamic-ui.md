# 聊天交互卡片

## 使用

工具包描述和 `read_guide` 会引导 AI 主动选择有助于理解的可视化，无需用户明确要求：
趋势用折线图，数量对比用柱状图，占比用饼图，数值关系用散点图；
参数试算用本地控件和计算，收集信息用表单。简单回答仍用文字或 Markdown 表格。
事实图表必须使用用户或工具提供的数据，演示数据与假设需明确标注；不为配图编造数据。
这是给模型的使用指引，不会强制每条回答生成卡片。

所有卡片共用中性视觉样式：浅色模式白底、深色模式深灰底、淡边框和主题强调色，
不改变聊天背景。指南同时要求自定义表单、对比、图表和参数试算组织好标题、重点、
操作和辅助说明，而不是仅套用固定示例。
`Display(text,label?)` 提供突出结果区；长结果可横向查看，不省略数字。
`Grid` 内按钮铺满各自单元格，未满行不会重新均分。显式 `columnCount` 时可传
`columnSpans` 整数数组，与 `children` 一一对应，每项为 `1..columnCount`；
跨列不足时另起一行。计算器的零键使用两列，费用试算也复用 `Display`。
启用「交互卡片」工具包。AI 先调用 `dynamic_ui.read_guide`，再调用
`prepare_card(source)` 编译 Express 源码。成功后只需在回答中放返回的 `a2ui-ref`
短围栏。应用在模型文本进入显示、历史和持久化之前，将引用展开为完整 `a2ui`。
不再让模型二次抄写 JSON；工具不回传整份编译结果。`valid:false` 保留行、列和原因。
新编写入口只有 Express，移除 `prepare_card(components,data)` 和 `validate_card`。

`read_example("calculator"|"cost_calculator"|"charts"|"form")` 同样编译后返回短引用。
它们分别是按键计算器、费用试算、四类图表和表单演示，不修改真实应用配置。
每张卡片独立初始化，不能跨卡片更新状态。

## Express 编写与交付

参考 [A2UI Express 规范](https://a2ui.org/specification/proposals/express/a2ui_express/)及
[`Express.g4`](https://github.com/a2ui-project/a2ui/blob/ce03d005c0575ff6708da583d9e7658a34df2d5d/specification/inference_formats/express/Express.g4)，
参考版本 `ce03d005c0575ff6708da583d9e7658a34df2d5d`。
这是 Operit 的 **Kotlin 受限子集实现**，不是完整官方 Express SDK，也不宣称通过其全套 conformance。
不引入 TypeScript/ANTLR 运行时。

```text
$/form = {years:4}
root = Column(children=[
  Heading(text="费用试算"),
  Display(label="演示金额",text=formatNumber(value=multiply(a=$/form/years,b=12.5))),
  Slider(label="年数",min=1,max=10,steps=8,number=$/form/years)
])
```

仅命名参数；一行一项赋值，可多行构造。组件内联或用变量引用，编译器生成 ID；
使用 `root` 入口。状态仅绝对对象路径、字面量初始化；纯函数使用现有允许列表，
动作以 `Event(name=...,context={...})` 声明。不支持位置参数、标签、脚本、import、
生命周期命令、远端目录、模板或跨卡片更新。组件说明和签名来自同一作者目录。
解析前限制 64K 字符，解析中限制深度、节点、数组和字段数量，编译后再过原生协议校验。

注册只能通过当前 `dynamic_ui` 工具调用的真实执行会话；引用归属稳定的 run ID，
不受 steering 引起的消息计时 scope 切换影响。仅模型正文中的独立完整引用围栏可展开，
思考、工具内容、普通代码示例不展开；SAVEPOINT/ROLLBACK 同时恢复解码状态。
未闭合引用保持代码，丢失或跨轮引用显示明确提示。单轮最多 16 张/512K 编译文本，
临时全局存储最多 4M 字符；单次采样展开最多 512K，重复引用也计入。
整轮结束清理临时内容。最终聊天记录为自包含卡片，不需要临时存储进行导出和恢复。
这节省二次生成成本，不代表后续历史中的卡片源码不占输入 token。

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

P0 支持 Card、Text、Heading、Display、Badge、Icon、Row、Column、Grid、Divider、
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
当时增加了原生校验工具；现已由 Express 编译入口统一校验并交付，
不再公开手写 NDJSON 的生成流程。检查仍不保证视觉布局或业务计算意图正确。

第三次真机反馈：模型反复抄写示例，22 次校验有 15 次失败，多数是在
`updateDataModel` 的嵌套初始值末尾漏掉闭合括号。嵌套对象本身受支持。
原先 `success:false` 会让工具执行器只保留 message/data，丢失 line/code。
校验现在将文档不合法作为 `success:true, valid:false` 的完整检查结果返回；
包含安全错误码、行号、可用时的列号和原因，文字消息也包含位置。
解析器原始片段不会进入诊断；工具不可用仍属于执行失败。

这次重构将生成流程统一为 Express 编译与短引用交付，消除校验后再次抄写的环节。
渲染入口继续独立校验；未引入自动补括号或静默放宽协议。

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
