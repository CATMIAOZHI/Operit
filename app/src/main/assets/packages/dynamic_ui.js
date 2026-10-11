/* METADATA
{
  "name": "dynamic_ui",
  "display_name": {"zh": "交互卡片", "en": "Interactive Cards"},
  "description": {
    "zh": "用原生图表、表单和交互卡片辅助讲解。比较数据、展示趋势、探索参数或解释关系时，若可视化能帮助理解，应主动使用，无需用户明确要求。简单回答用普通文字。使用前先读取 read_guide。",
    "en": "Explain with native charts, forms and interactive cards. Proactively use them when visualization helps compare data, show trends, explore parameters or explain relationships. Use plain text for simple answers. Read read_guide first."
  },
  "category": "UI",
  "enabledByDefault": true,
  "tools": [
    {
      "name": "read_guide",
      "description": {"zh": "生成前必读：Express 语法、组件参数、排版和动作指南。", "en": "Read before generation: Express syntax, component signatures, layout and actions."},
      "parameters": []
    },
    {
      "name": "prepare_card",
      "description": {"zh": "编译并校验 Express 卡片，返回展示短引用。失败按行列修正源码；成功只输出短引用，不抄写 JSON。", "en": "Compile and validate Express. Fix reported source locations on failure. On success display only the returned reference, never copy JSON."},
      "parameters": [
        {"name": "source", "type": "string", "required": true, "description": {"zh": "Express 源码，不含围栏；参数使用 name=value，语法见 read_guide", "en": "Express source without fences; use name=value arguments. See read_guide."}},
        {"name": "live", "type": "boolean", "required": false, "description": {"zh": "允许本轮更新 $/agent 数据；必须初始化该对象。", "en": "Allow current-turn updates to initialized $/agent data."}}
      ]
    },
    {
      "name": "patch_card_data",
      "description": {"zh": "原位替换本轮 live 卡片的 Agent 数据，不改界面结构或用户输入。成功后无需再次展示引用。", "en": "Replace Agent data in a current-turn live card without changing layout or user input. Do not display the reference again."},
      "parameters": [
        {"name": "reference", "type": "string", "required": true, "description": {"zh": "本轮 live 卡片引用", "en": "Current-turn live card reference."}},
        {"name": "expected_version", "type": "number", "required": true, "description": {"zh": "最近返回的版本号，初始为 0", "en": "Last returned version, initially 0."}},
        {"name": "data", "type": "object", "required": true, "description": {"zh": "完整的新 Agent 数据对象，最多 8K 字符", "en": "Complete new Agent data object, at most 8K characters."}}
      ]
    },
    {
      "name": "update_card",
      "description": {"zh": "局部修改本轮卡片的命名声明，校验后返回新版本引用；保留旧卡片及用户填写内容。", "en": "Revise named declarations of a card from this turn. Return a validated new reference, preserving the old card and its user state."},
      "parameters": [
        {"name": "reference", "type": "string", "required": true, "description": {"zh": "本轮 prepare_card 或 update_card 返回的引用", "en": "Reference returned by prepare_card or update_card in this turn."}},
        {"name": "changes", "type": "string", "required": true, "description": {"zh": "新增或替换的 Express 声明，其他声明保持不变", "en": "Express declarations to add or replace; other declarations remain unchanged."}},
        {"name": "remove", "type": "array", "required": false, "description": {"zh": "要删除的声明名称数组", "en": "Array of declaration names to remove."}}
      ]
    },
    {
      "name": "read_example",
      "description": {"zh": "直接生成已校验的演示卡片，返回展示短引用。自定义需求使用 prepare_card。", "en": "Prepare a tested demo and return its display reference. Use prepare_card for custom content."},
      "parameters": [{"name": "name", "type": "string", "required": true, "description": {"zh": "calculator、cost_calculator、charts、form、info、stats、progress、responsive、data、vector、repeat 或 curves", "en": "calculator, cost_calculator, charts, form, info, stats, progress, responsive, data, vector, repeat or curves"}}]
    }
  ]
}
*/

const guide = String.raw`
Use native cards proactively when they help understanding, without waiting for an explicit request.
Trends: line; category comparison: bar; shares: pie; numeric relationships: scatter.
Bound controls and local calculations explore assumptions without requesting AI on each change.
Use forms for structured input. Simple answers need prose/Markdown. Use Mermaid for static diagrams.
Use actual user/tool data for factual charts; label demos and assumptions. Never invent measurements.

WORKFLOW
Read this guide, then call prepare_card(source). Display its a2ui-ref fence EXACTLY in your answer,
surrounded by a concise explanation. The app inserts the compiled card automatically.
Do not repeat the source/NDJSON, reconstruct the output or revalidate a successful result.
References belong to this active turn; never reuse them in another turn/chat.
To revise a card from THIS turn, use update_card(reference,changes,remove?).
changes contains only named declarations to replace/add; remove lists existing declarations.
The complete merged graph is checked atomically. Inline components are changed via their named parent.
Display the NEW reference; it creates a separate revision, NOT a live edit of a displayed card.
Old cards and user inputs are preserved. New revisions start from their declared initial data.
Patch errors use merged source locations. For previous turns, prepare complete new source.
valid:false is a completed inspection: fix the reported line/column and field. Do not retry identical
input or infer unsupported nesting. Validation does not prove visual quality or arithmetic intent.
read_example prepares an unmodified demo. Custom cards are freely composed, not fixed templates.

EXPRESS (Operit named-argument subset, compiled locally to AndroidX A2UI v0.9)
One assignment per line; multiline constructors allowed. No enclosing tags or Markdown fences.
root is required. ALL component/function arguments are named: Text(text="Hello").
Inline components or reference declarations (forward references allowed):
root = Column(children=[title, Text(text="Details")])
title = Heading(text="Overview")
State bindings use absolute object paths: $/form/years. Initialize with literal assignments:
$/form = {years:4,discount:false}
No overlapping initializations, array-index paths, reserved keys, uninitialized bindings, or calls
inside initial data. Strings use JSON double quotes/escapes; numbers are decimal.
No positional args, scripts, operators, imports, lifecycle commands or remote catalogs.
All declarations must be reachable from root. No cycles. Max 128 components, 32 children/container,
depth 12; source 64K characters, compiled document 128K, initial data 32K, string 8192 characters.
Event(name="...", context={...}) describes an action, not a callback.
Local calculations: multiply(a=$/form/years,b=10), formatNumber(value=...,decimals=2,grouping=true).
Functions accept numeric strings. if evaluates eagerly; division by zero is unresolved. Use
Conditional hints and Button checks, e.g. checks=[{condition:greaterThan(a=$/form/years,b=0),
message:"Enter a positive value"}]. Bind controls' value/checked/number to initialized state.
Literal controls are read-only. Calls never write state; an explicit local_action does.

DYNAMIC LISTS
Repeat(items=$/issues,key="id",child=issueRow,maxItems=20) repeats one component template.
Initialize issues as object arrays with stable, unique nonempty string IDs (not array indexes).
Template fields use item(field="title") or item(field="author/name"); functions may consume item values.
Item data is read-only. Absolute $/ bindings still refer to shared card state, not row-local form fields.
Arrays may come from arrayFilter. Default maxItems=20, maximum32; overflow is rejected, never truncated.
Repeat cannot nest. Empty arrays render no rows; use Conditional for an empty-state explanation.

LIVE TASK DATA
prepare_card(source,live=true) enables data-only updates during this active turn.
Initialize $/agent={...} as an object (maximum8K). Keep user inputs under other branches such as $/form.
After displaying the reference ONCE, patch_card_data(reference,expected_version=0,data={...}) replaces
the COMPLETE Agent object. Each success returns the next version; use it on the next patch.
Conflicts and invalid updates change nothing. The whole source is revalidated before publication.
User controls/local actions cannot write Agent data. Patches never execute actions or modify layout.
The final data is saved on this device, including while the card is off-screen. After this turn ends
the reference is no longer writable. update_card still creates a separate, non-live revision.
Live data and user input are included in app data backups; ordinary text exports carry initial data.
Cross-card sharing is not implicit: use multiple Panels in one card for shared controls and results.

LAYOUT — applies to ALL cards, not only calculators
One purpose per card: concise title, prominent result, controls, short supporting note.
Header(text=title,label=optionalSubtitle,status=optionalBadge,tone=...) starts a card.
Heading is a smaller section heading. InfoRow(label=...,text=...) aligns each attribute/value;
long values automatically stack without truncation. Divider separates header and details.
Display(text=...,label=...,align="start") gives statistics a large value on an inset grey panel.
Default alignment is start; calculators use align="end". Badge tones:
neutral, success, info, warning, error. Use colors for meaning, never as decoration.
Stack forms vertically. Table aligns tabular data; Grid suits short metrics or keypads, not prose.
Row is for short content. Avoid decorative nesting and duplicating titles in the surrounding text.
Row(wrap=true) wraps short items at the available width. gap=none|xs|sm|md|lg means 0/4/8/16/24 dp.
Row align=start|center|end is vertical; justify=start|center|end|between is horizontal.
Rows keep their content width by default; explicit justify uses the available full width.
Column align=start|center|end is horizontal. Both default to an 8 dp gap.
Panel(child=...,padding="md",radius="md",background="inset",border=false,maxWidth=600)
groups content with bounded styles. Background: transparent|surface|inset|accentMuted; radius:none|sm|md|lg.
Text textAlign=start|center|end; optional lineClamp=1..8 truncates nonessential text only.
Scroll(child=...,maxHeight=240) creates a bounded vertical region (80..480 dp). Avoid nested scrolling.
Host ALWAYS supplies a full-width white/light or neutral dark frame, clear border, rounded corners
and consistent padding, regardless of root type. Do not wrap root in Card just to get a border.
Use nested Card only to group separate related sections. Keep commentary outside the card.
Choose one primary action; quiet secondary actions. No arbitrary style/color overrides.
Grid columnCount=1..4; columnSpans needs one integer span per child, each 1..columnCount.
Without columnCount, Grid auto-fits 1..4 columns using minItemWidth (80..320 dp, default160)
and font scale. Prefer auto columns for metrics; explicit columns for keypads.
Items pack in order and incomplete rows keep empty cells. A zero key can span 2 columns.
Slider steps counts INTERMEDIATE stops: min=0,max=100,steps=99 gives integers; default continuous.
Retain decimal precision unless asked to round. Long Display values scroll rather than truncate.
Text variants: h1/h2/h3/body/caption. Icon names: info,check,settings,person,home,star,warning,
search,calendarToday,folder. Bind Expandable.expanded and Tabs/Carousel.value to retain state.
Navigation value is a named child's ID; use named declarations for navigation children.
DatePicker uses yyyy-MM-dd and expands inline. Controls do not open Activity dialogs.

DATA AND MEDIA
Chart type="line"|"bar"|"pie"|"scatter", labels=["A","B"], series=[{label:"Sales",values:[1,2]}].
Scatter: series=[{label:"Demo",points:[{x:1,y:2,label:"A"}]}].
Max 4 series/128 points, category lengths must agree. Pie: one nonnegative series.
Charts provide inspection and a data table; do not duplicate it by default.
Table: string columns and array rows, max 8 columns/32 rows.
Rows may bind to state or array expressions; every row must match columns and contain scalar cells.
Pure collection functions use items (max128) and optional field="object/nestedField", never callbacks.
arrayMap projects a required field; arrayFilter requires field,operator,value.
Filter operators: eq/ne/gt/gte/lt/lte/contains. arraySum/arrayAverage use optional field.
arrayJoin requires separator; arrayLength counts items. Missing fields or invalid numbers are unresolved;
empty sum=0, empty average is unresolved. Bound results max32K, strings8192, finite numbers <=1e12.
stringContains(text,query,ignoreCase?), stringReplace(text,search,replacement), stringTrim(text).
Dates are ISO yyyy-MM-dd, years1900..2100. dateAddDays(date,days), dateDiffDays(start,end),
formatDate(date,format="iso"|"yearMonth"|"monthDay"). All arguments are NAMED.
if supports bounded objects/arrays but is eager: both branches must be valid.
RichText runs=[{text:"...",bold:true,italic:false,underline:false,tone:"primary",size:"caption"}].
Optional run url is http(s); RichText is not HTML. Put math/Mermaid in normal Markdown.
Image/Video/Audio use user/tool-provided http(s) URLs; load only on click, never autoplay.
Link/File open web URLs, not device paths. Media can fail independently.
Map markers=[{label:"A",latitude:30,longitude:120}], route=true connects points in order:
this is a LOCATION SKETCH, not roads/navigation. Draft saves/copies locally, never sends mail.
Popover(label="Details",child=...) opens an anchored panel inside the card, at most320x240 dp,
with scrolling, a close button and outside-tap dismissal. Short cards expand to fit the panel.
Do not nest Popovers or hide essential information in them. Opening never calls AI.
Frame(child=...,minWidth=0,maxWidth=600,minHeight=0,maxHeight=300,aspectRatio=1.5)
bounds one region; aspectRatio is width/height (0.25..4). Available screen constraints take priority.
Use ratios for graphics/media, not growing prose or forms. Width0..960dp, height0..480dp;
defaults are min0,maxWidth960,maxHeight480. Content is clipped to the requested region.
Vector(label="Accessible description",width=320,height=180,shapes=[...]) draws static geometry.
Viewport integer dimensions1..1000; coordinates stay inside it. Max64 shapes,128 points/shape.
Shapes: {type:"line",x1:0,y1:0,x2:100,y2:100},
{type:"rect",x:10,y:10,width:80,height:50}, {type:"circle",cx:50,cy:50,r:20},
{type:"polyline",points:[[0,0],[100,50]]}, {type:"polygon",points:[[0,0],[100,0],[50,50]]},
{type:"text",x:10,y:10,text:"Label",size:16}. Text uses TOP-LEFT coordinates; allow space for it.
Optional tone=neutral|accent|success|info|warning|error, filled=true, stroke=0.5..12.
Curves: {type:"quadratic",x1:0,y1:80,cx:100,cy:0,x2:200,y2:80} or
{type:"cubic",x1:0,y1:80,cx1:60,cy1:0,cx2:140,cy2:160,x2:200,y2:80}.
All endpoints and controls must be within the viewport; curves are stroked, never filled.
No SVG markup, arbitrary paths, scripts, URLs or touch handlers. Use charts for data inspection.

ACTIONS
Button(child=Text(text="..."),variant="primary"|"default"|"borderless",action=Event(...)).
local_action context: {op:"set",path:"/count",value:5}, {op:"add",path:"/count",value:1},
{op:"save"}, {op:"edit"} or {op:"cancel"}. Local interactions never request AI.
ai_submit context: bound structured data; optional required:["/form/name"].
tool_action context: requested tool and args, NOT direct execution. Both preview and require
confirmation, preserve composer drafts and use normal authorization/review.
open_url context {url:"https://..."}; copy context {text:"..."}.
Label consequential actions honestly. Completed cards only; no actions on history replay.
State persists on this device by chat/message/revision/card. Changed source starts new state.
calculatorStep(state,key) is a pure reducer; initialize
{display:"0",stored:"",op:"",replace:false,error:false}; keys 0..9,".","+","-","*","/","=",
"AC","backspace","sign". Set the whole calculator state atomically with local_action.

MINIMAL COST DEMO (simulated price, no purchase)
$/form = {years:4}
root = Column(children=[
 Heading(text="Cost estimate"),
 Display(label="Demo total",text=formatNumber(value=multiply(a=$/form/years,b=12.5))),
 Slider(label="Years",min=1,max=10,steps=8,number=$/form/years)
])

CATALOG — generated from the compiler's contract; ? means optional
`;

function native() {
    if (typeof NativeInterface === "undefined" || typeof NativeInterface.prepareDynamicUi !== "function")
        throw new Error("This app version does not include the Express card compiler.");
    return NativeInterface;
}
function prepare(source, live = false) {
    const root = typeof globalThis !== "undefined" ? globalThis : this;
    const callId = String(root.__operitCurrentCallId || "");
    if (!callId) throw new Error("No active card tool invocation.");
    return {success:true, ...JSON.parse(String(native().prepareDynamicUi(callId, source, live === true)))};
}
exports.read_guide = async () => complete({success:true,message:guide + native().dynamicUiGuide()});
exports.prepare_card = async ({source, live = false}) => {
    if (typeof source !== "string" || source.length > 65536) {
        complete({success:true,valid:false,code:"invalid_source",message:"Pass Express source (maximum 65536 characters)."});
        return;
    }
    complete(prepare(source, live));
};
exports.patch_card_data = async ({reference, expected_version, data}) => {
    if (typeof reference !== "string" || reference.length > 80 || !Number.isInteger(expected_version) ||
        expected_version < 0 || !data || Array.isArray(data) || typeof data !== "object" || JSON.stringify(data).length > 8192) {
        complete({success:true,valid:false,message:"Pass a live reference, integer version and an Agent data object up to 8K characters."});
        return;
    }
    complete({success:true,...JSON.parse(String(native().patchDynamicUiData(
        String(globalThis.__operitCurrentCallId || ""), reference, expected_version, JSON.stringify(data))))});
};
exports.update_card = async ({reference, changes, remove = []}) => {
    if (typeof reference !== "string" || reference.length > 80 || typeof changes !== "string" ||
        changes.length > 65536 || !Array.isArray(remove) || remove.length > 128 ||
        remove.some(name => typeof name !== "string" || name.length > 256)) {
        complete({success:true,valid:false,code:"invalid_revision",message:"Use a current-turn reference, Express changes and an array of declaration names."});
        return;
    }
    const root = typeof globalThis !== "undefined" ? globalThis : this;
    complete({success:true,...JSON.parse(String(native().updateDynamicUi(
        String(root.__operitCurrentCallId || ""),reference,changes,JSON.stringify(remove))))});
};

const q = JSON.stringify;
function example(name) {
    if (name === "repeat") return [
        '$/issues = [{id:"login",title:"修复登录",status:"处理中"},{id:"layout",title:"调整排版",status:"待处理"}]',
        '$/query = ""',
        'root = Column(children=[Header(text="任务清单",status="演示数据"),TextField(label="筛选标题",value=$/query),rows,empty])',
        'rows = Repeat(items=arrayFilter(items=$/issues,field="title",operator="contains",value=$/query),key="id",maxItems=20,child=Panel(child=InfoRow(label=item(field="title"),text=item(field="status")),background="inset"))',
        'empty = Conditional(visible=equals(a=arrayLength(items=arrayFilter(items=$/issues,field="title",operator="contains",value=$/query)),b=0),children=[Text(text="没有匹配的任务")])',
    ].join("\n");
    if (name === "curves") return [
        'root = Column(children=[Header(text="曲线示意",status="静态图形"),Frame(maxWidth=480,maxHeight=240,aspectRatio=2,child=Vector(label="两条不同形状的曲线",width=320,height=160,shapes=[{type:"quadratic",x1:10,y1:80,cx:160,cy:0,x2:310,y2:80,tone:"accent"},{type:"cubic",x1:10,y1:100,cx1:100,cy1:20,cx2:220,cy2:160,x2:310,y2:100,tone:"success"}]))])',
    ].join("\n");
    if (name === "calculator") {
        const keys = [["AC","AC"],["backspace","⌫"],["sign","±"],["/","÷"],
            ["7","7"],["8","8"],["9","9"],["*","×"],["4","4"],["5","5"],["6","6"],["-","−"],
            ["1","1"],["2","2"],["3","3"],["+","+"],["0","0"],[".","."],["=","="]];
        return [
            '$/calculator = {display:"0",stored:"",op:"",replace:false,error:false}',
            'root = Column(children=[Header(text="本地计算器",status="本地计算"),pending,Display(label="结果",text=$/calculator/display,align="end"),error,keys,Text(text="支持小数、退格和连续运算。",variant="caption")])',
            'pending = Conditional(visible=not(value=equals(a=$/calculator/op,b="")),children=[Text(text=concat(values=[$/calculator/stored," ",$/calculator/op]))])',
            'error = Conditional(visible=$/calculator/error,children=[Text(text="无法计算，请检查除数或清除后重试。")])',
            'keys = Grid(columnCount=4,columnSpans=[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2,1,1],children=[' +
                keys.map(([key,label]) => `Button(child=Text(text=${q(label)}),variant=${q(key === "=" ? "primary" : "default")},action=Event(name="local_action",context={op:"set",path:"/calculator",value:calculatorStep(state=$/calculator,key=${q(key)})}))`).join(",") + "])",
        ].join("\n");
    }
    if (name === "cost_calculator") return String.raw`
$/form = {years:4,discount:false}
root = Column(children=[
 Header(text="费用试算",status="演示数据"),
 Display(label="预计费用",text=formatNumber(value=multiply(a=multiply(a=$/form/years,b=11.622222),b=if(condition=$/form/discount,then=0.9,else=1)))),
 Slider(label="购买年限",min=1,max=10,steps=8,number=$/form/years),
 CheckBox(label="模拟九折优惠",value=$/form/discount),
 Text(text="拖动滑块或勾选优惠即可本地重新计算，不会下单或付款。",variant="caption")
])`;
    if (name === "charts") return 'root = Column(children=[' +
        ["line","bar","pie","scatter"].map((type, i) => 'Chart(label=' + q(["折线图","柱状图","饼图","散点图"][i] + " · 演示数据") +
            ',type=' + q(type) + (type === "scatter"
            ? ',series=[{label:"演示数据",points:[{x:1,y:7},{x:2,y:12},{x:4,y:26}]}])'
            : ',labels=["A","B","C"],series=[{label:"演示数据",values:[32,56,42]}])')).join(",") + "])";
    if (name === "form") return String.raw`
$/form = {name:"",notes:""}
root = Form(label="偏好表单 · 演示",children=[
 TextField(label="称呼",value=$/form/name),
 TextArea(label="补充说明",value=$/form/notes),
 Button(child=Text(text="提交给 AI"),variant="primary",action=Event(name="ai_submit",context={form:$/form,required:["/form/name"]}))
])`;
    if (name === "info") return String.raw`
root = Card(child=Column(children=[
 Header(text="example.com",label="域名状态 · 演示数据",status="活动中",tone="success"),
 Divider(),
 InfoRow(label="到期日期",text="2028/09/29"),
 InfoRow(label="自动续费",text="已开启",tone="success")
]))`;
    if (name === "stats") return String.raw`
root = Column(children=[
 Header(text="Token 使用统计",status="演示数据"),
 Grid(children=[Display(label="总 Token",text="9.0M"),Display(label="缓存命中率",text="94.2%")]),
 Chart(label="每日 Token 用量",type="bar",labels=["周一","周二","周三","周四","周五"],
 series=[{label:"Token",values:[1200000,1800000,1500000,2100000,2400000]}])
])`;
    if (name === "progress") return String.raw`
$/tasks = {analysis:true,review:true,test:false}
root = Column(children=[
 Header(text="代码审查任务",label="演示清单，仅修改当前卡片",status=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="已完成",else="进行中"),tone=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="success",else="info")),
 Progress(label="任务进度",number=divide(a=add(a=add(a=if(condition=$/tasks/analysis,then=1,else=0),b=if(condition=$/tasks/review,then=1,else=0)),b=if(condition=$/tasks/test,then=1,else=0)),b=3)),
 CheckBox(label="分析代码",value=$/tasks/analysis),
 CheckBox(label="检查问题",value=$/tasks/review),
 CheckBox(label="运行测试",value=$/tasks/test)
])`;
    if (name === "responsive") return String.raw`
root = Column(gap="md",children=[
 Header(text="项目概览",status="演示数据"),
 Row(wrap=true,gap="sm",align="center",children=[Badge(text="Android",tone="info"),Badge(text="本地交互",tone="success"),Badge(text="响应式布局")]),
 Grid(minItemWidth=120,children=[Display(label="已完成",text="12"),Display(label="待处理",text="3"),Display(label="完成率",text="80%")]),
 Panel(background="inset",padding="md",radius="lg",child=Text(text="屏幕变窄或字体变大时，数据面板自动换行。",textAlign="center")),
 Popover(label="这些数字是什么意思？",child=Column(children=[Heading(text="示例说明"),Text(text="这些是演示数据。可以把面板替换为真实任务统计。")]))
])`;
    if (name === "data") return String.raw`
$/items = [{name:"设计",hours:8,row:["设计",8]},{name:"开发",hours:20,row:["开发",20]},{name:"测试",hours:12,row:["测试",12]}]
$/filter = {minimum:10}
$/date = "2026-10-12"
root = Column(gap="md",children=[
 Header(text="工时筛选",status="演示数据"),
 Slider(label="最低工时",min=0,max=24,steps=23,number=$/filter/minimum),
 Table(columns=["任务","工时"],rows=arrayMap(items=arrayFilter(items=$/items,field="hours",operator="gte",value=$/filter/minimum),field="row")),
 Display(label="全部任务工时",text=formatNumber(value=arraySum(items=$/items,field="hours"),decimals=0)),
 InfoRow(label="一周后",text=dateAddDays(date=$/date,days=7)),
 Text(text=arrayJoin(items=arrayMap(items=$/items,field="name"),separator=" · "),variant="caption")
])`;
    if (name === "vector") return String.raw`
root = Column(children=[
 Header(text="数据处理流程",status="示意图"),
 Vector(label="输入经过本地处理，产生结果",width=320,height=150,shapes=[
 {type:"rect",x:5,y:40,width:85,height:65,tone:"info",filled:true},
 {type:"rect",x:120,y:40,width:85,height:65,tone:"success"},
 {type:"circle",cx:275,cy:72,r:34,tone:"accent"},
 {type:"line",x1:90,y1:72,x2:120,y2:72},
 {type:"polyline",points:[[110,65],[120,72],[110,79]]},
 {type:"line",x1:205,y1:72,x2:240,y2:72},
 {type:"text",x:18,y:58,text:"输入",size:18},
 {type:"text",x:129,y:58,text:"处理",size:18},
 {type:"text",x:254,y:58,text:"结果",size:18}
 ]),
 Text(text="矢量图用于解释关系；真实统计数据请使用图表。",variant="caption")
])`;
    throw new Error("Choose calculator, cost_calculator, charts, form, info, stats, progress, responsive, data or vector.");
}
exports.read_example = async ({name}) => complete(prepare(example(name)));
