/* METADATA
{
  "name": "dynamic_ui",
  "display_name": {"zh": "动态界面", "en": "Dynamic UI"},
  "description": {
    "zh": "在回答正文中嵌入原生流程图、步骤图、图表、表格和数据面板，与文字自然穿插；只有需要用户操作时才加交互控件。讲解步骤、比较数据、展示趋势或关系时，若视觉结构比文字更清楚，应主动使用，无需用户要求。简单回答用普通文字。使用前先读取 read_guide。",
    "en": "Embed native flows, step diagrams, charts, tables and metrics inside the answer, interleaved with prose; add interactive controls only when the user must act. Proactively use them when visual structure explains steps, comparisons, trends or relationships better than text. Use plain text for simple answers. Read read_guide first."
  },
  "category": "UI",
  "enabledByDefault": true,
  "tools": [
    {
      "name": "read_guide",
      "description": {"zh": "生成前必读：何时用文字/纯展示/交互、Express 语法、组件参数和排版。", "en": "Read before generation: when to use prose, display-only or interactive UI, Express syntax, components and layout."},
      "parameters": []
    },
    {
      "name": "prepare_card",
      "description": {"zh": "编译并校验一个 Express 界面块，返回短引用。把引用放在正文中它应出现的位置；失败按行列修正源码；不要抄写 JSON。", "en": "Compile and validate one Express UI block and return a short reference. Place the reference where the block belongs in the answer. Fix reported locations on failure; never copy JSON."},
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
      "description": {"zh": "直接生成已校验的演示界面块，返回短引用。自定义需求使用 prepare_card。", "en": "Prepare a tested demo block and return its reference. Use prepare_card for custom content."},
      "parameters": [{"name": "name", "type": "string", "required": true, "description": {"zh": "纯展示：flow_steps、flowchart、data_story、stats、charts、info、vector、curves；混合：mixed_branch、responsive；交互：interactive_flow、calculator、cost_calculator、form、progress、data、repeat、p2_controls、p2_lists", "en": "Display-only: flow_steps, flowchart, data_story, stats, charts, info, vector, curves; mixed: mixed_branch, responsive; interactive: interactive_flow, calculator, cost_calculator, form, progress, data, repeat, p2_controls, p2_lists"}}]
    }
  ]
}
*/

const guide = String.raw`
WHAT THIS IS
Native rich content embedded INSIDE your normal answer, not a separate card app. Each reference
renders as a borderless block exactly where you place it between Markdown paragraphs, using the
chat theme. One answer may hold several blocks: text -> flow -> text -> chart/table -> text.

CHOOSE THE LIGHTEST FORM (most blocks should be display-only)
1. Prose/Markdown when words are clear. Lists, code, short tables and math stay in Markdown.
2. Display-only UI when visual structure is clearer: Flow for steps/linear processes with arrows,
   Chart for trends/comparisons, Table for records, Grid+Display for key metrics, InfoRow for
   attributes, Badge rows for status, Vector for small custom diagrams, Image with text.
   Display-only blocks need NO $/ state, NO Event, NO Button. Never initialize unused data.
3. Interactive UI only when the user must choose, enter, filter, calculate or explore. Make only
   that part interactive and keep the rest of the block static.
Use display blocks proactively when they clarify steps, trends, comparisons or relationships.
Do not add buttons, forms, toggles or "tap to..." hints that nothing needs. Keep explanations in
Markdown around blocks rather than inside them. Trends: line; categories: bar; shares: pie;
numeric relationships: scatter. Branching or many-node graphs: Mermaid in Markdown, or Vector.
Use actual user/tool data for factual charts; label demos and assumptions. Never invent measurements.

FRAMING
Blocks have no border, background or padding by default and line up with paragraphs.
Do not start every block with a Header; the surrounding text usually introduces it.
Use root=Card(child=...) only for a self-contained tool or record (calculator, form, status record)
that benefits from a visible boundary. Panel groups a sub-area. Never nest frames for decoration.

MINIMAL DISPLAY BLOCK (no state, no actions)
root = Flow(label="Three cooking stages",steps=[{title:"Prepare",caption:"2 min",icon:"list"},
 {title:"Cook separately",caption:"4 min",icon:"fire"},{title:"Combine",caption:"1 min",icon:"check",tone:"success"}])

WORKFLOW
Read this guide, then call prepare_card(source) once per block. Place each returned a2ui-ref fence
EXACTLY where that block belongs in your answer, with prose before/after as needed. The app renders
the compiled block at that position.
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
Item data is read-only. Use item_state(field="done",default=false) for independent writable row fields.
Defaults must be scalar literals; declarations of the same field must agree. State follows Repeat ID +
stable item key across reorder/filter/reopen. Hidden rows retain state within the 32K quota.
Absolute $/ bindings still refer to shared card state. Never write the internal _operit_rows branch.
local_action {op:"row_set",repeat:"rows",key:"issue-id",field:"done",value:true} edits a declared field
of a currently present row. Removing a row rejects its old events; reusing its key restores its state.
Arrays may come from arrayFilter. Default maxItems=20, maximum32; overflow is rejected, never truncated.
Repeat can nest: inner items=item(field="children"). item/item_state always refer to the nearest row.
State includes every ancestor's stable key; the whole card shares the 512 rendered-node budget.
For nested row_set, pass parents:["outerKey",...] in outermost-first order plus the named inner Repeat ID.
Empty arrays render no rows; use Conditional for an empty-state explanation.

NATIVE INTERACTION
Pressable(label="View step",child=...,selected=booleanBinding,disabled=false,action=Event(...))
makes an entire display/layout node clickable with a 48dp minimum target and accessibility semantics.
Its descendants must be noninteractive; keep inputs, links and other buttons outside the node.
For interactive flow diagrams combine Pressable + Column/Row + Vector arrows + Conditional details.
Completed is different from disabled: completed steps remain clickable; Next depends only on position.
Input controls support onChange=Event(name="local_action",context={op:"set",path:"/selection",
value:event_value(field="value")}). event_value fields: value, previous, index (or -1 when not indexed).
Only actual changed user input fires onChange, after its bound value is saved. Initialization,
restore and programmatic state changes do not fire. onChange cannot submit to AI or call tools.
Vector shapes retain literal types/array structure; coordinates, tone, stroke, filled and text may bind
to state or calculations. arrow uses x1,y1,x2,y2 and draws its own arrowhead. All geometry remains
bounded to the viewport. Invalid runtime geometry shows a local error; no arbitrary SVG/JS.

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

FLOW (static steps and linear flowcharts)
Flow(label="Accessible summary",steps=[{title:"...",caption:"...",icon:"check",tone:"success"}],
direction="auto"|"horizontal"|"vertical"). 1..8 steps; title required (<=80 chars), caption <=160.
Icons: check,info,settings,person,home,star,warning,search,calendar,folder,edit,play,flag,time,list,
fire,food,code,upload,download,lock,chat,build. Tones: neutral,accent,success,info,warning,error.
auto draws horizontal tiles with right arrows for <=4 steps when they fit, otherwise vertical tiles
with down arrows. horizontal is a preference: it still stacks vertically when tiles would not fit.
Combine several Flows in a Column/Grid for simple branches. No state or actions needed.

LAYOUT
One purpose per block. Titles are optional; prominent results, then details.
Header(text=title,label=optionalSubtitle,status=optionalBadge,tone=...) titles a self-contained block.
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
Blocks are frameless; see FRAMING. Keep commentary in Markdown outside the block.
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
DatePicker uses yyyy-MM-dd and expands inline; minDate/maxDate bound 1900..2100.
clearable=true adds Clear (writes an empty string). Changing bounds does not rewrite existing data.
TextField/TextArea error="..." shows a field error. TextArea minLines/maxLines are 1..12 (default3/8).
TextField mask=none|password|digits: password masks DISPLAY ONLY (the value remains plain local state);
digits accepts ASCII digits only, without reformatting the stored value. No arbitrary mask patterns.
Button loading=true keeps its label and shows a spinner; clicks are blocked until loading=false.
Controls do not open Activity dialogs.
Tooltip(label="What is this?",text="Explanation") opens on tap/long-press, using the same closable
in-card overlay as Popover. Stepper(label="Steps",labels=["Start","Finish"],current=$/step)
shows connected numbered steps (zero-based integer); prior steps are shown as passed, not independent
completion flags. Past steps remain clickable and onChange is local.
Formula(label="Equation",text="\\frac{1}{2}") renders native bounded math, never arbitrary TeX.
Maximum1024 chars/64 commands/12 brace levels. Allowed: frac,sqrt,sum,prod,int,lim,infty,left,right,
alpha,beta,gamma,delta,theta,lambda,mu,pi,sigma,phi,omega,Delta,Sigma,Omega,times,cdot,div,pm,
le,leq,ge,geq,ne,neq,approx,sin,cos,tan,log,ln,exp,text,mathrm,mathbf,overline,vec,hat.
No macros, environments, external files or size commands. Express quoted strings escape backslashes.

DATA AND MEDIA
Chart type="line"|"bar"|"pie"|"scatter", labels=["A","B"], series=[{label:"Sales",values:[1,2]}].
Scatter: series=[{label:"Demo",points:[{x:1,y:2,label:"A"}]}].
Max 4 series/128 points, category lengths must agree. Pie: one nonnegative series.
Charts provide inspection and a data table; do not duplicate it by default.
Table: string columns and array rows, max 8 columns/32 rows.
columnWidths=[120,180] sets one literal64..480dp width per column (default140).
maxHeight=80..480 bounds the vertically scrolling body; the header stays visible and aligns on horizontal scroll.
Rows may bind to state or array expressions; every row must match columns and contain scalar cells.
Pure collection functions use items (max128) and optional field="object/nestedField", never callbacks.
arraySort(items,field?,direction="asc"|"desc") is stable and requires same-type sortable values.
arrayFind(items,field,value,fallback) returns the first equal item or explicit fallback.
arrayUpdateByKey(items,key="id",value="a",patch={done:true}) returns a new array; keys must be unique
scalars and patch cannot change the key. No match leaves it unchanged. Write with a local_action set.
RichText runs may use strike=true, highlight=true, tabular=true along with bold/italic/underline.
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
Vector(label="Accessible description",width=320,height=180,shapes=[...]) draws data-bound geometry.
Shape types and point topology stay literal; coordinates, text and style may use bindings/functions.
Viewport integer dimensions1..1000; coordinates stay inside it. Max64 shapes,128 points/shape.
Shapes: {type:"line",x1:0,y1:0,x2:100,y2:100}, {type:"arrow",x1:0,y1:0,x2:100,y2:100},
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

MINIMAL INTERACTIVE DEMO (only because the user explores a value; simulated price)
$/form = {years:4}
root = Card(child=Column(children=[
 Display(label="Demo total",text=formatNumber(value=multiply(a=$/form/years,b=12.5))),
 Slider(label="Years",min=1,max=10,steps=8,number=$/form/years)
]))

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
    if (name === "flow_steps") return String.raw`
root = Flow(label="番茄炒蛋三个阶段",steps=[{title:"准备食材",caption:"2 分钟",icon:"list"},{title:"分别炒制",caption:"4 分钟",icon:"fire"},{title:"混合出锅",caption:"1 分钟",icon:"check",tone:"success"}])`;
    if (name === "flowchart") return String.raw`
root = Column(gap="xs",children=[main,question,split,branches])
main = Flow(label="请求处理：收到请求后校验参数",direction="vertical",steps=[{title:"收到请求",icon:"download"},{title:"校验参数",icon:"search"}])
question = Row(justify="center",children=[Badge(text="参数是否有效？",tone="info")])
split = Vector(label="分成两个分支",width=320,height=36,shapes=[{type:"arrow",x1:160,y1:2,x2:80,y2:34},{type:"arrow",x1:160,y1:2,x2:240,y2:34}])
branches = Grid(columnCount=2,children=[
 Flow(label="有效分支",direction="vertical",steps=[{title:"有效",icon:"check",tone:"success"},{title:"执行业务",icon:"build"},{title:"返回结果",icon:"upload"}]),
 Flow(label="无效分支",direction="vertical",steps=[{title:"无效",icon:"warning",tone:"error"},{title:"返回错误",caption:"附带原因"}])
])`;
    if (name === "data_story") return String.raw`
root = Column(gap="md",children=[
 Grid(minItemWidth=100,children=[Display(label="本周总量",text="9.0M"),Display(label="缓存命中",text="94%"),Display(label="较上周",text="+12%")]),
 Chart(label="每日用量（百万 Token，演示数据）",type="bar",labels=["周一","周二","周三","周四","周五"],series=[{label:"用量",values:[1.2,1.8,1.5,2.1,2.4]}]),
 Table(columns=["模型","占比","平均耗时"],rows=[["主对话","72%","14 秒"],["子代理","21%","38 秒"],["预测","7%","1.2 秒"]])
])`;
    if (name === "mixed_branch") return String.raw`
$/release = {plan:"稳妥"}
root = Column(gap="md",children=[
 Flow(label="发布流程",steps=[{title:"开发",icon:"code"},{title:"审计",icon:"search"},{title:"发布",icon:"upload",tone:"success"}]),
 Segmented(label="选择发布方式",options=["快速","稳妥"],value=$/release/plan),
 Conditional(visible=equals(a=$/release/plan,b="快速"),children=[InfoRow(label="快速发布",text="跳过真机验证，适合文案修改")]),
 Conditional(visible=equals(a=$/release/plan,b="稳妥"),children=[InfoRow(label="稳妥发布",text="真机验证通过后再合并，适合功能改动",tone="success")])
])`;
    if (name === "interactive_flow") return String.raw`
$/flow = {step:0,flavor:"原味"}
$/steps = [
 {id:"step0",order:0,title:"开始",detail:"点击任意步骤查看说明。准备好后，勾选完成并前往下一步。"},
 {id:"step1",order:1,title:"准备食材",detail:"准备番茄、鸡蛋、葱、食用油和适量盐。清洗番茄并切块。"},
 {id:"step2",order:2,title:"打散鸡蛋",detail:"鸡蛋打入碗中，加少量盐搅匀。生熟食材分开放置。"},
 {id:"step3",order:3,title:"炒鸡蛋",detail:"锅热后加油，倒入蛋液，轻推至凝固后盛出备用。"},
 {id:"step4",order:4,title:"炒番茄",detail:"锅中补少量油，放入番茄炒软，按需加一点水。"},
 {id:"step5",order:5,title:"口味选择",detail:"选择甜口或原味。可以随时返回更改，不影响其他步骤记录。"},
 {id:"step6",order:6,title:"混合出锅",detail:"倒回鸡蛋轻轻翻匀，按口味调盐，确认鸡蛋熟透后出锅。"},
 {id:"step7",order:7,title:"结束",detail:"完成！已经完成的步骤仍可点开查看，也可以返回上一步。"}
]
root = Column(children=[Header(text="番茄炒蛋",label="点步骤查看说明，勾选记录进度",status="本地交互"),steps])
steps = Repeat(items=$/steps,key="id",maxItems=8,child=stepRow)
stepRow = Column(gap="xs",children=[node,details,connector])
node = Pressable(label=concat(values=[item(field="title"),if(condition=item_state(field="done",default=false),then="，已完成",else="，未完成")]),selected=equals(a=$/flow/step,b=item(field="order")),child=Header(text=item(field="title"),status=if(condition=item_state(field="done",default=false),then="已完成",else="待完成"),tone=if(condition=item_state(field="done",default=false),then="success",else="neutral")),action=Event(name="local_action",context={op:"set",path:"/flow/step",value:item(field="order")}))
details = Conditional(visible=equals(a=$/flow/step,b=item(field="order")),children=[Text(text=item(field="detail")),branch,CheckBox(label="本步骤已完成",value=item_state(field="done",default=false)),navigation])
branch = Conditional(visible=equals(a=item(field="order"),b=5),children=[Frame(maxWidth=120,child=Vector(label="判断节点：选择口味",width=120,height=64,shapes=[{type:"polygon",points:[[60,2],[118,32],[60,62],[2,32]],tone:"info",filled:true}])),Segmented(label="选择口味",options=["甜口","原味"],value=$/flow/flavor),Text(text=if(condition=equals(a=$/flow/flavor,b="甜口"),then="甜口分支：加入少量糖，先少放，再尝味调整。",else="原味分支：不加糖，保留番茄的酸甜。"))])
connector = Conditional(visible=lessThan(a=item(field="order"),b=7),children=[Vector(label="前往下一步骤",width=320,height=28,shapes=[{type:"arrow",x1:160,y1:2,x2:160,y2:26,tone:if(condition=item_state(field="done",default=false),then="success",else="neutral")}])])
navigation = Row(wrap=true,children=[Button(child=Text(text="上一步"),checks=[{condition:greaterThan(a=$/flow/step,b=0),message:"当前是第一步"}],action=Event(name="local_action",context={op:"add",path:"/flow/step",value:-1})),Button(child=Text(text="下一步"),variant="primary",checks=[{condition:lessThan(a=$/flow/step,b=7),message:"已到最后一步"}],action=Event(name="local_action",context={op:"add",path:"/flow/step",value:1}))])
`;
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
            'root = Card(child=Column(children=[Header(text="本地计算器",status="本地计算"),pending,Display(label="结果",text=$/calculator/display,align="end"),error,keys,Text(text="支持小数、退格和连续运算。",variant="caption")]))',
            'pending = Conditional(visible=not(value=equals(a=$/calculator/op,b="")),children=[Text(text=concat(values=[$/calculator/stored," ",$/calculator/op]))])',
            'error = Conditional(visible=$/calculator/error,children=[Text(text="无法计算，请检查除数或清除后重试。")])',
            'keys = Grid(columnCount=4,columnSpans=[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2,1,1],children=[' +
                keys.map(([key,label]) => `Button(child=Text(text=${q(label)}),variant=${q(key === "=" ? "primary" : "default")},action=Event(name="local_action",context={op:"set",path:"/calculator",value:calculatorStep(state=$/calculator,key=${q(key)})}))`).join(",") + "])",
        ].join("\n");
    }
    if (name === "cost_calculator") return String.raw`
$/form = {years:4,discount:false}
root = Card(child=Column(children=[
 Header(text="费用试算",status="演示数据"),
 Display(label="预计费用",text=formatNumber(value=multiply(a=multiply(a=$/form/years,b=11.622222),b=if(condition=$/form/discount,then=0.9,else=1)))),
 Slider(label="购买年限",min=1,max=10,steps=8,number=$/form/years),
 CheckBox(label="模拟九折优惠",value=$/form/discount),
 Text(text="拖动滑块或勾选优惠即可本地重新计算，不会下单或付款。",variant="caption")
]))`;
    if (name === "charts") return 'root = Column(children=[' +
        ["line","bar","pie","scatter"].map((type, i) => 'Chart(label=' + q(["折线图","柱状图","饼图","散点图"][i] + " · 演示数据") +
            ',type=' + q(type) + (type === "scatter"
            ? ',series=[{label:"演示数据",points:[{x:1,y:7},{x:2,y:12},{x:4,y:26}]}])'
            : ',labels=["A","B","C"],series=[{label:"演示数据",values:[32,56,42]}])')).join(",") + "])";
    if (name === "form") return String.raw`
$/form = {name:"",notes:""}
root = Card(child=Form(label="偏好表单 · 演示",children=[
 TextField(label="称呼",value=$/form/name),
 TextArea(label="补充说明",value=$/form/notes),
 Button(child=Text(text="提交给 AI"),variant="primary",action=Event(name="ai_submit",context={form:$/form,required:["/form/name"]}))
]))`;
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
root = Card(child=Column(children=[
 Header(text="代码审查任务",label="演示清单，仅修改当前卡片",status=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="已完成",else="进行中"),tone=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="success",else="info")),
 Progress(label="任务进度",number=divide(a=add(a=add(a=if(condition=$/tasks/analysis,then=1,else=0),b=if(condition=$/tasks/review,then=1,else=0)),b=if(condition=$/tasks/test,then=1,else=0)),b=3)),
 CheckBox(label="分析代码",value=$/tasks/analysis),
 CheckBox(label="检查问题",value=$/tasks/review),
 CheckBox(label="运行测试",value=$/tasks/test)
]))`;
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
    if (name === "p2_controls") return String.raw`
$/form={date:"2026-10-12",notes:"",code:"",busy:false,step:0,formula:"\\frac{1}{2}"}
root=Column(children=[
 Header(text="原生控件演示",label="所有输入与切换均在本地"),
 Stepper(label="准备步骤",labels=["填写","检查","完成"],current=$/form/step),
 DatePicker(label="日期",value=$/form/date,minDate="2026-10-01",maxDate="2026-10-31",clearable=true),
 TextField(label="数字编号",value=$/form/code,mask="digits",error=if(condition=equals(a=$/form/code,b=""),then="请输入数字编号",else="")),
 TextArea(label="备注",value=$/form/notes,minLines=2,maxLines=5),
 Switch(label="预览按钮忙碌状态",checked=$/form/busy),
 Button(child=Text(text="保存"),loading=$/form/busy,action=Event(name="local_action",context={op:"save"})),
 Tooltip(label="为什么按钮不能点？",text="忙碌时会显示进度圈并禁止点击。关闭上方开关后即可使用。"),
 RichText(runs=[{text:"旧值 ",strike:true},{text:"重要信息 ",highlight:true},{text:"1234.56",tabular:true}]),
 TextField(label="公式源码",value=$/form/formula),
 Formula(label="公式预览",text=$/form/formula),
 Table(columns=["项目","数量"],rows=[["甲",12],["乙",24],["丙",36],["丁",48],["戊",60],["己",72]],columnWidths=[160,100],maxHeight=160)
])`;
    if (name === "p2_lists") return String.raw`
$/groups=[{id:"a",title:"厨房",rank:2,children:[{id:"one",title:"准备食材"},{id:"two",title:"整理台面"}]},{id:"b",title:"客厅",rank:1,children:[{id:"one",title:"整理书架"}]}]
root=Column(children=[
 Header(text="分组任务",label="相同子任务 ID 在不同分组中独立保存"),
 Row(wrap=true,children=[
  Button(child=Text(text="按顺序排列"),action=Event(name="local_action",context={op:"set",path:"/groups",value:arraySort(items=$/groups,field="rank")})),
  Button(child=Text(text="重命名厨房"),action=Event(name="local_action",context={op:"set",path:"/groups",value:arrayUpdateByKey(items=$/groups,key="id",value="a",patch={title:"厨房与餐厅"})}))
 ]),
 groups
])
groups=Repeat(items=$/groups,key="id",child=Panel(child=Column(children=[
 Heading(text=item(field="title")),
 tasks
]),background="inset"))
tasks=Repeat(items=item(field="children"),key="id",child=CheckBox(label=item(field="title"),value=item_state(field="done",default=false)))
`;
    throw new Error("Choose flow_steps, flowchart, data_story, mixed_branch, stats, charts, info, vector, curves, responsive, interactive_flow, calculator, cost_calculator, form, progress, data, repeat, p2_controls or p2_lists.");
}
exports.read_example = async ({name}) => complete(prepare(example(name)));
