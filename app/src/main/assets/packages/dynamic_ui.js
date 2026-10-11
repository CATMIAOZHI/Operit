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
      "parameters": [{"name": "source", "type": "string", "required": true, "description": {"zh": "Express 源码，不含围栏；参数使用 name=value，语法见 read_guide", "en": "Express source without fences; use name=value arguments. See read_guide."}}]
    },
    {
      "name": "read_example",
      "description": {"zh": "直接生成已校验的演示卡片，返回展示短引用。自定义需求使用 prepare_card。", "en": "Prepare a tested demo and return its display reference. Use prepare_card for custom content."},
      "parameters": [{"name": "name", "type": "string", "required": true, "description": {"zh": "calculator、cost_calculator、charts 或 form", "en": "calculator, cost_calculator, charts or form"}}]
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
To revise, prepare complete corrected source and use the NEW reference.
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
No positional args, scripts, operators, imports, templates, lifecycle commands or remote catalogs.
All declarations must be reachable from root. No cycles. Max 128 components, 32 children/container,
depth 12; source 64K characters, compiled document 128K, initial data 32K, string 8192 characters.
Event(name="...", context={...}) describes an action, not a callback.
Local calculations: multiply(a=$/form/years,b=10), formatNumber(value=...,decimals=2,grouping=true).
Functions accept numeric strings. if evaluates eagerly; division by zero is unresolved. Use
Conditional hints and Button checks, e.g. checks=[{condition:greaterThan(a=$/form/years,b=0),
message:"Enter a positive value"}]. Bind controls' value/checked/number to initialized state.
Literal controls are read-only. Calls never write state; an explicit local_action does.

LAYOUT — applies to ALL cards, not only calculators
One purpose per card: concise title, prominent result, controls, short supporting note.
Heading for sections; Display for large results and units; Badge for short status.
Stack forms vertically. Table aligns attributes; Grid suits short metrics or keypads, not prose.
Row is for short content. Avoid decorative nesting and duplicating titles in the surrounding text.
Host supplies white/light or neutral dark surfaces, soft borders, spacing and theme accents.
Choose one primary action; quiet secondary actions. No arbitrary style/color overrides.
Grid columnCount=1..4; columnSpans needs one integer span per child, each 1..columnCount.
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
RichText runs=[{text:"...",bold:true,italic:false,underline:false,tone:"primary",size:"caption"}].
Optional run url is http(s); RichText is not HTML. Put math/Mermaid in normal Markdown.
Image/Video/Audio use user/tool-provided http(s) URLs; load only on click, never autoplay.
Link/File open web URLs, not device paths. Media can fail independently.
Map markers=[{label:"A",latitude:30,longitude:120}], route=true connects points in order:
this is a LOCATION SKETCH, not roads/navigation. Draft saves/copies locally, never sends mail.

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
function prepare(source) {
    const root = typeof globalThis !== "undefined" ? globalThis : this;
    const callId = String(root.__operitCurrentCallId || "");
    if (!callId) throw new Error("No active card tool invocation.");
    return {success:true, ...JSON.parse(String(native().prepareDynamicUi(callId, source)))};
}
exports.read_guide = async () => complete({success:true,message:guide + native().dynamicUiGuide()});
exports.prepare_card = async ({source}) => {
    if (typeof source !== "string" || source.length > 65536) {
        complete({success:true,valid:false,code:"invalid_source",message:"Pass Express source (maximum 65536 characters)."});
        return;
    }
    complete(prepare(source));
};

const q = JSON.stringify;
function example(name) {
    if (name === "calculator") {
        const keys = [["AC","AC"],["backspace","⌫"],["sign","±"],["/","÷"],
            ["7","7"],["8","8"],["9","9"],["*","×"],["4","4"],["5","5"],["6","6"],["-","−"],
            ["1","1"],["2","2"],["3","3"],["+","+"],["0","0"],[".","."],["=","="]];
        return [
            '$/calculator = {display:"0",stored:"",op:"",replace:false,error:false}',
            'root = Column(children=[Heading(text="本地计算器"),pending,Display(label="本地计算",text=$/calculator/display),error,keys,Text(text="支持小数、退格和连续运算。",variant="caption")])',
            'pending = Conditional(visible=not(value=equals(a=$/calculator/op,b="")),children=[Text(text=concat(values=[$/calculator/stored," ",$/calculator/op]))])',
            'error = Conditional(visible=$/calculator/error,children=[Text(text="无法计算，请检查除数或清除后重试。")])',
            'keys = Grid(columnCount=4,columnSpans=[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2,1,1],children=[' +
                keys.map(([key,label]) => `Button(child=Text(text=${q(label)}),variant=${q(key === "=" ? "primary" : "default")},action=Event(name="local_action",context={op:"set",path:"/calculator",value:calculatorStep(state=$/calculator,key=${q(key)})}))`).join(",") + "])",
        ].join("\n");
    }
    if (name === "cost_calculator") return String.raw`
$/form = {years:4,discount:false}
root = Column(children=[
 Heading(text="费用试算 · 演示数据"),
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
    throw new Error("Choose calculator, cost_calculator, charts or form.");
}
exports.read_example = async ({name}) => complete(prepare(example(name)));
