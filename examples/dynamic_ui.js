/* METADATA
{
  "name": "dynamic_ui",
  "display_name": {"zh": "交互卡片", "en": "Interactive Cards"},
  "description": {
    "zh": "在聊天中生成原生表单、列表、表格和交互卡片。先读取 read_guide，再输出 a2ui 代码块。",
    "en": "Create native forms, lists, tables and interactive cards in chat. Read read_guide before emitting an a2ui fence."
  },
  "category": "UI",
  "enabledByDefault": true,
  "tools": [
    {
      "name": "read_guide",
      "description": {"zh": "读取卡片协议、组件、动作和完整表单示例。生成前必须读取。", "en": "Read the card protocol, components, actions and a complete form example before generation."},
      "parameters": []
    },
    {
      "name": "read_example",
      "description": {"zh": "读取可直接显示的本地计算器或四类图表示例。", "en": "Read a ready-to-render local calculator or four chart examples."},
      "parameters": [{"name": "name", "type": "string", "required": true, "description": {"zh": "calculator（按键计算器）、cost_calculator（费用试算）或 charts", "en": "calculator (keypad), cost_calculator (cost estimate), or charts"}}]
    },
    {
      "name": "validate_card",
      "description": {"zh": "显示自定义卡片前，使用应用实际协议校验器检查完整 NDJSON，返回安全行号。不会执行卡片。", "en": "Before displaying a custom card, validate its complete NDJSON with the app protocol validator. Returns safe line diagnostics; never executes it."},
      "parameters": [{"name": "source", "type": "string", "required": true, "description": {"zh": "完整 NDJSON，不包含代码围栏", "en": "Complete NDJSON without Markdown fences"}}]
    }
  ]
}
*/

const guide = String.raw`
Generate cards only when a form, structured comparison, or local interaction helps the user.
Use normal Markdown for ordinary answers. Cards may be mixed with Markdown.
Emit a fenced code block whose language is exactly a2ui. Each line inside is ONE COMPLETE
JSON event (NDJSON). Close the fence. Do not call tools to execute the card.
For a calculator/demo, prefer read_example and reproduce its exact document. If you customize
any document, call validate_card with its complete source before displaying it; correct failures.
Validation checks protocol and limits, not visual quality or intended arithmetic.
Prefer one independent card per fence so a malformed card cannot hide unrelated cards.
Protocol is official AndroidX A2UI v0.9, with this Operit catalog:
https://operit.ai/a2ui/catalog/v1
Always createSurface, then updateComponents, then updateDataModel. Root component ID is root.
Every code block is an independent document. Keep ALL initialization for a surface in the SAME
fence. Later fences cannot patch earlier cards. Never emit placeholder/misspelled events.
To repair a card, emit its complete corrected document. The 4-surface limit is per fence, not per chat.
The catalog URL above is an identifier, NOT a website or a link to open.
Do not use v0.8 beginRendering, v1.0 schemas, nested component objects, HTML, scripts,
unlisted functions, regex validation, modal, templates, or remote catalogs.
Use stable IDs. Bounds: 4 surfaces, 128 components/surface, 32 static children/container,
depth 12, 512 expanded nodes, 256 events, 128K source characters, 32K data characters,
8192 characters/string. Do not reference cyclic/duplicate children. Prefer small cards.
Paths are absolute object paths such as /form/instructions. No numeric array indexes.

Official Material components:
Text(text literal or {"path":"/..."}, variant optional body or h1/h2/h3 etc),
Icon(name: "info"|"check"|"settings"|"person"|"home"|"star"|"warning"|"search"|"calendarToday"|"folder"),
Use these known icon names; "dashboard" is NOT supported. Unknown icons get a local placeholder.
Row/Column/List(children:["id",...]), Card(child:"id"),
Divider, Button(child:"label-id",variant:"primary"|"default"|"borderless",action:{"event":{"name":"...","context":{...}}}),
CheckBox(label,value literal boolean or {"path":"/..."}).
Operit extensions (same official component envelope):
Heading/Badge/Code(text); Progress(label,number 0..1);
Table(columns:["A","B"],rows:[["one","two"]]) up to 8 columns, 32 rows;
Grid(children,columnCount optional literal 1..4) defaults to responsive one/two columns.
Use columnCount:4 for a keypad; narrow screens can scroll horizontally to keep buttons usable.
Form(label,children); Expandable(label,children,expanded bound boolean);
Bind Expandable.expanded to a data path to retain expansion after reopening the conversation.
TextField/TextArea(label,value bound string,inputType optional "number"); Switch(label,checked bound boolean);
Arithmetic functions accept numeric strings from TextField(inputType:"number"). You do NOT need
a slider or manual string conversion to calculate typed numbers.
RadioGroup/Select/ChoicePicker(label,options:["Low","Medium","High"],value bound string);
Slider(label,min literal number,max literal number,number bound number,steps optional integer 0..999), span <=1000;
steps counts INTERMEDIATE stops: min:0,max:100,steps:99 yields integers; default 0 is continuous.
Preserve precision: do not format continuous/decimal inputs with decimals:0 unless asked to round.
DatePicker(label,value bound ISO yyyy-MM-dd string), expands inline.
These extensions differ from the Basic Catalog; use these exact properties.
State binding: {"path":"/form/value"}. Initialize all form values with updateDataModel.
Literal field values are read-only. Form controls edit local state, never call AI on each change.
Dates and dropdowns are inline and also work without Activity dialogs.
Use one main action per form (variant "primary"); local save uses "default", cancel "borderless".
Avoid repeating the same title in the surrounding prose and inside the card. Keep form controls
in a single column. Use Grid for SHORT statistics only. The host supplies margins and theme colors.

Additional native components:
Segmented(label,options:[strings],value bound string) for compact local choices.
Tabs(label,labels:[strings],children:[IDs],value bound child ID).
Carousel(label,children:[IDs],value bound child ID) supports horizontal swipes and previous/next.
Conditional(visible bound boolean,children:[IDs]) shows children only when true.
Bind navigation value to initial child ID to retain the selected page after reopening.
RichText(runs:[{text,bold?,italic?,underline?,tone:"primary"|"muted"|"error"?,size:"title"|"caption"?,url?}]).
Chart(label,type:"line"|"bar"|"pie"|"scatter",labels:[strings],series:[{label,values:[numbers]}]).
Scatter uses series:[{label,points:[{x:number,y:number,label?:string}]}] instead.
At most 4 series / 128 points each; labels and values lengths must agree. Pie takes ONE series,
nonnegative values. Charts support point inspection and a data table. Empty/all-zero pies show no data.
Image/Video/Audio(label,url); use actual user/tool-provided http(s) sources. They load only on user click;
no autoplay, prefetch or modal. Loaded media can fail independently without breaking the form.
Link/File(label,text,url); File supports web file links, not arbitrary device paths.
Product(label,text,price,imageUrl?,url), Place(label,text,address,imageUrl?,url).
Map(label,markers:[{label,latitude,longitude}],route:boolean) shows an interactive LOCATION SKETCH
and an external map link; route connects points in order, NOT real roads or turn-by-turn navigation.
Draft(label,value bound string) is an editable draft with local save/copy. Add an ai_submit Button
if the user should send it. Never imply saving a draft sends an email or publishes anything.
For math formulas and Mermaid, use the normal Markdown outside the a2ui fence; do not nest tool
markup, Markdown fences, HTML or scripts inside a card. RichText is structured text, not HTML.

Pure local calculations use the official dynamic call binding:
{"call":"multiply","args":{"a":{"path":"/form/years"},"b":10}}
Allowed: add/subtract/multiply/divide/min/max(a,b); greaterThan/lessThan/equals(a,b);
and/or(a,b), not(value); if(condition,then,else); formatNumber(value,decimals optional 0..6,
grouping optional boolean); concat(values:[up to 16 scalars]).
Only component dynamic properties and event context/checks may use calls. Initial data and
component structure must stay literal. Maximum 6 nested calls, 128 calls per fence.
All paths must be initialized. if arguments are evaluated eagerly (not short circuit).
Division by zero/invalid numeric input yields an unresolved value, not an exception; ensure
valid inputs and use a Conditional hint for invalid forms. Calculations never write back state.
Button checks may contain {condition:bound boolean,message:"user-facing validation message"}.
calculatorStep(state,key) returns the next bounded calculator state object. Allowed keys:
0..9, ".", "+", "-", "*", "/", "=", "AC", "backspace", "sign". Initial state:
{display:"0",stored:"",op:"",replace:false,error:false}. Bind Button local_action/set value to
this function and path to the whole calculator state; the update is atomic. It is a pure built-in
reducer, NOT executable model code. Display error:true with a Conditional user-facing message.
read_example("calculator"), read_example("cost_calculator"), read_example("charts") return tested documents.

Five action names, all from Button event:
local_action context {op:"set",path:"/count",value:5} or {op:"add",path:"/count",value:1};
local_action {op:"save"} saves a read-only local result; {op:"edit"} or {op:"cancel"}.
ai_submit context contains the user's structured data via bindings. Optional required:
["/form/name"] checks nonempty fields. App previews and confirms before sending.
tool_action context describes a requested tool operation and its arguments. App previews,
confirms, and sends a normal user request; the agent must use normal tool authorization/review.
It does NOT directly invoke a tool, and sending is not proof of execution.
open_url {url:"https://..."} opens only http/https URLs on click.
copy {text:"..."} copies on click.
Never hide consequential actions behind innocuous button labels.
All actions require a complete valid card; changing/reopening history never replays actions.
AI submit is available only in an idle editable current chat, and preserves composer drafts.
Local card state persists on this device, isolated by chat/message/version/card content.
Editing/regenerating a card changes its revision and starts new state.

Complete model-parameter DEMO (does NOT change actual model configuration):
{"version":"v0.9","createSurface":{"surfaceId":"parameters","catalogId":"https://operit.ai/a2ui/catalog/v1"}}
{"version":"v0.9","updateComponents":{"surfaceId":"parameters","components":[{"id":"root","component":"Form","label":"AI 参数示例","children":["notice","effort","instructions","save","send"]},{"id":"notice","component":"Text","text":"仅演示卡片交互，不修改应用模型设置。"},{"id":"effort","component":"RadioGroup","label":"思考强度","options":["Low","Medium","High"],"value":{"path":"/form/effort"}},{"id":"instructions","component":"TextArea","label":"补充说明","value":{"path":"/form/instructions"}},{"id":"save","component":"Button","child":"saveLabel","variant":"default","action":{"event":{"name":"local_action","context":{"op":"save"}}}},{"id":"saveLabel","component":"Text","text":"保存卡片"},{"id":"send","component":"Button","child":"sendLabel","variant":"primary","action":{"event":{"name":"ai_submit","context":{"form":{"path":"/form"}}}}},{"id":"sendLabel","component":"Text","text":"提交给 AI"}]}}
{"version":"v0.9","updateDataModel":{"surfaceId":"parameters","path":"/","value":{"form":{"effort":"Medium","instructions":""}}}}
Wrap the three lines above in an a2ui fence when showing the demo. After saving, the host
keeps a readable form with Edit and Cancel changes. Never claim real settings changed.
`;

exports.read_guide = async () => complete({ success: true, message: guide });

function document(id, components, value) {
    return [
        {version: "v0.9", createSurface: {surfaceId: id, catalogId: "https://operit.ai/a2ui/catalog/v1"}},
        {version: "v0.9", updateComponents: {surfaceId: id, components}},
        {version: "v0.9", updateDataModel: {surfaceId: id, path: "/", value}}
    ].map(JSON.stringify).join("\n");
}
const path = (path) => ({path});
const call = (call, args) => ({call, args});
function example(name) {
    if (name === "calculator") {
        const keys = [["AC","AC"],["backspace","⌫"],["sign","±"],["/","÷"],
            ["7","7"],["8","8"],["9","9"],["*","×"],["4","4"],["5","5"],["6","6"],["-","−"],
            ["1","1"],["2","2"],["3","3"],["+","+"],["0","0"],[".","."],["=","="]];
        return document("keypad", [
            {id:"root",component:"Form",label:"本地计算器",children:["pending","screen","error","keys","hint"]},
            {id:"pending",component:"Conditional",visible:call("not",{value:call("equals",{a:path("/calculator/op"),b:""})}),children:["pendingText"]},
            {id:"pendingText",component:"Text",text:call("concat",{values:[path("/calculator/stored")," ",path("/calculator/op")]})},
            {id:"screen",component:"Heading",text:path("/calculator/display")},
            {id:"error",component:"Conditional",visible:path("/calculator/error"),children:["errorText"]},
            {id:"errorText",component:"Text",text:"无法计算：除零或数值超出计算范围。按数字或 AC 重新开始。"},
            {id:"keys",component:"Grid",columnCount:4,children:keys.map((_,i)=>"key"+i)},
            ...keys.flatMap(([key,label],i)=>[
                {id:"key"+i,component:"Button",child:"label"+i,variant:key==="="?"primary":"default",
                    action:{event:{name:"local_action",context:{op:"set",path:"/calculator",
                        value:call("calculatorStep",{state:path("/calculator"),key})}}}},
                {id:"label"+i,component:"Text",text:label}
            ]),
            {id:"hint",component:"Text",text:"本地四则运算，支持小数、退格和连续运算。结果保留 12 位有效数字，不请求 AI。"}
        ], {calculator:{display:"0",stored:"",op:"",replace:false,error:false}});
    }
    if (name === "cost_calculator") return document("calculator", [
        {id: "root", component: "Form", label: "费用试算", children: ["intro", "years", "discount", "total", "hint"]},
        {id: "intro", component: "Text", text: "演示数据，每年 10.46 元。所有计算均在本地完成。"},
        {id: "years", component: "Slider", label: "购买年限", min: 1, max: 10, number: path("/form/years")},
        {id: "discount", component: "CheckBox", label: "模拟九折优惠", value: path("/form/discount")},
        {id: "total", component: "Heading", text: call("concat", {values: ["¥ ", call("formatNumber", {
            value: call("multiply", {a: call("multiply", {a: path("/form/years"), b: 10.46}),
                b: call("if", {condition: path("/form/discount"), then: 0.9, else: 1})}), decimals: 2
        })]})},
        {id: "hint", component: "Conditional", visible: path("/form/discount"), children: ["hintText"]},
        {id: "hintText", component: "Text", text: "已按九折试算，不会下单或付款。"}
    ], {form: {years: 4, discount: false}});
    if (name === "charts") return ["line", "bar", "pie", "scatter"].map((type, index) =>
        document("chart_" + type, [
            {id: "root", component: "Chart", label: ["折线图", "柱状图", "饼图", "散点图"][index],
                type, labels: type === "scatter" ? [] : ["A", "B", "C"],
                series: type === "scatter" ? [{label: "演示数据", points: [{x: 1, y: 7}, {x: 2, y: 12}, {x: 4, y: 26}]}]
                    : [{label: "演示数据", values: [32, 56, 42]}]}
        ], {})
    ).join("\n");
    throw new Error("Choose calculator, cost_calculator or charts.");
}
exports.read_example = async ({name}) => complete({success: true, message: "Demo data only. Render this complete a2ui fence:\n```a2ui\n" + example(name) + "\n```"});

exports.validate_card = async ({source}) => {
    if (typeof source !== "string" || source.length > 131072) {
        complete({success:false,message:"Source must be NDJSON, at most 131072 characters."});
        return;
    }
    if (typeof NativeInterface === "undefined" || typeof NativeInterface.validateDynamicUi !== "function") {
        complete({success:false,message:"This app version does not include the native card validator. Update Operit."});
        return;
    }
    const result = JSON.parse(String(NativeInterface.validateDynamicUi(source)));
    complete({success:result.valid === true, ...result});
};
