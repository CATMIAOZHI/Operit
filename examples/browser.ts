/* METADATA
{
    "name": "browser",
    "display_name": {
        "zh": "Browser 自动化操作",
        "en": "Browser Automation"
    },
    "description": {
        "zh": "基于应用内 WebView 的浏览器工具集，提供页面快照、截图和有限的 Playwright 风格操作。",
        "en": "In-app WebView browser tools with page snapshots, screenshots and a limited Playwright-style API."
    },
    "enabledByDefault": true,
    "category": "Automatic",
    "tools": [
        {
            "name": "click",
            "description": { "zh": "按快照 ref 点击页面元素，包括同源 iframe 内的元素。", "en": "Click an element by snapshot ref, including refs inside same-origin iframes." },
            "parameters": [
                { "name": "ref", "description": { "zh": "快照中的目标元素引用；和 selector 至少提供一个。", "en": "Target element ref from the snapshot; provide ref or selector." }, "type": "string", "required": false },
                { "name": "selector", "description": { "zh": "可选，ref 不可用时的元素选择器。", "en": "Optional selector fallback when ref is not available." }, "type": "string", "required": false },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述。", "en": "Optional human-readable element description." }, "type": "string", "required": false },
                { "name": "doubleClick", "description": { "zh": "可选，是否双击。", "en": "Optional double click." }, "type": "boolean", "required": false },
                { "name": "button", "description": { "zh": "可选，left/right/middle。", "en": "Optional mouse button: left/right/middle." }, "type": "string", "required": false },
                { "name": "modifiers", "description": { "zh": "可选，修饰键数组。", "en": "Optional modifier keys array." }, "type": "array", "required": false }
            ]
        },
        {
            "name": "close",
            "description": { "zh": "关闭当前 tab。", "en": "Close the current tab." },
            "parameters": []
        },
        {
            "name": "console_messages",
            "description": { "zh": "读取控制台消息。", "en": "Read console messages." },
            "parameters": [
                { "name": "level", "description": { "zh": "可选，日志级别：error/warning/info/debug，默认 info。", "en": "Optional log level: error/warning/info/debug. Defaults to info." }, "type": "string", "required": false },
                { "name": "filename", "description": { "zh": "可选，保存输出的文件名。", "en": "Optional output file name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "drag",
            "description": { "zh": "在两个元素之间拖拽。", "en": "Drag between two elements." },
            "parameters": [
                { "name": "startElement", "description": { "zh": "源元素的人类可读描述。", "en": "Human-readable source element description." }, "type": "string", "required": true },
                { "name": "startRef", "description": { "zh": "源元素 ref。", "en": "Source element ref." }, "type": "string", "required": true },
                { "name": "endElement", "description": { "zh": "目标元素的人类可读描述。", "en": "Human-readable target element description." }, "type": "string", "required": true },
                { "name": "endRef", "description": { "zh": "目标元素 ref。", "en": "Target element ref." }, "type": "string", "required": true },
                { "name": "mode", "description": { "zh": "可选，拖拽方式：auto（默认，按元素自动选）/mouse（真实鼠标）/touch（真实手指）/html5（HTML 拖放事件）。拖不动时换一种再试。", "en": "Optional drag style: auto (default, picked from the element), mouse (real mouse), touch (real finger) or html5 (HTML drag-and-drop events). Try another if nothing moves." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "evaluate",
            "description": { "zh": "在页面或元素上执行 JavaScript 函数。", "en": "Evaluate a JavaScript function on the page or an element." },
            "parameters": [
                { "name": "function", "description": { "zh": "要执行的函数源码。", "en": "Function source to execute." }, "type": "string", "required": true },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述。", "en": "Optional human-readable element description." }, "type": "string", "required": false },
                { "name": "ref", "description": { "zh": "可选，目标元素 ref。", "en": "Optional target element ref." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "upload",
            "description": { "zh": "向当前文件选择器上传文件。", "en": "Upload files to the current file chooser." },
            "parameters": [
                { "name": "paths", "description": { "zh": "可选，绝对路径数组；不传则取消 file chooser。", "en": "Optional absolute file paths; omit to cancel the file chooser." }, "type": "array", "required": false }
            ]
        },
        {
            "name": "fill_form",
            "description": { "zh": "批量填写表单字段。", "en": "Fill multiple form fields." },
            "parameters": [
                { "name": "fields", "description": { "zh": "字段数组。", "en": "Array of form fields." }, "type": "array", "required": true }
            ]
        },
        {
            "name": "handle_dialog",
            "description": { "zh": "处理当前对话框。", "en": "Handle the current dialog." },
            "parameters": [
                { "name": "accept", "description": { "zh": "是否接受对话框。", "en": "Whether to accept the dialog." }, "type": "boolean", "required": true },
                { "name": "promptText", "description": { "zh": "可选，prompt 的输入文本。", "en": "Optional prompt text." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "hover",
            "description": { "zh": "悬停到页面元素上。", "en": "Hover over an element." },
            "parameters": [
                { "name": "ref", "description": { "zh": "目标元素 ref。", "en": "Target element ref." }, "type": "string", "required": true },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述。", "en": "Optional human-readable element description." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "goto",
            "description": { "zh": "导航到指定 URL。", "en": "Navigate to a URL." },
            "parameters": [
                { "name": "url", "description": { "zh": "目标 URL。", "en": "Target URL." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "back",
            "description": { "zh": "后退到上一页。", "en": "Go back to the previous page." },
            "parameters": []
        },
        {
            "name": "network_requests",
            "description": { "zh": "读取当前页面的网络请求。", "en": "Read network requests for the current page." },
            "parameters": [
                { "name": "includeStatic", "description": { "zh": "可选，是否包含静态资源请求，默认 false。", "en": "Optional include static resource requests. Defaults to false." }, "type": "boolean", "required": false },
                { "name": "filename", "description": { "zh": "可选，保存输出的文件名。", "en": "Optional output file name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "press_key",
            "description": { "zh": "像真实键盘一样按下按键或组合键，Enter 会真的提交表单，Tab 会真的切换焦点。", "en": "Press a key or key combination like a real keyboard: Enter really submits, Tab really moves focus." },
            "parameters": [
                { "name": "key", "description": { "zh": "按键名，例如 Enter、ArrowDown、a；组合键用 + 连接，例如 Control+A、Shift+Tab。", "en": "Key name such as Enter, ArrowDown or a; join combinations with +, such as Control+A or Shift+Tab." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "resize",
            "description": { "zh": "调整浏览器视口大小。", "en": "Resize the browser viewport." },
            "parameters": [
                { "name": "width", "description": { "zh": "宽度。", "en": "Width." }, "type": "number", "required": true },
                { "name": "height", "description": { "zh": "高度。", "en": "Height." }, "type": "number", "required": true }
            ]
        },
        {
            "name": "run_code",
            "description": { "zh": "运行有限的 Playwright 风格代码，并非完整 Playwright。截图请调用 take_screenshot，不支持 page.screenshot；注入样式请用 page.evaluate 创建 style 元素，不支持 page.addStyleTag。", "en": "Run a limited Playwright-style snippet, not full Playwright. Use take_screenshot for images; page.screenshot is unsupported. Inject styles by creating a style element with page.evaluate; page.addStyleTag is unsupported." },
            "parameters": [
                { "name": "code", "description": { "zh": "代码片段：一个接收 page 的函数，例如 async (page) => { await page.locator('#login').click(); }；或直接写函数体，可用 await 和 return。", "en": "Code snippet: a function that takes page, such as async (page) => { await page.locator('#login').click(); }, or a plain body that may use await and return." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "select_option",
            "description": { "zh": "在下拉框中选择选项。", "en": "Select options in a dropdown." },
            "parameters": [
                { "name": "ref", "description": { "zh": "目标元素 ref。", "en": "Target element ref." }, "type": "string", "required": true },
                { "name": "values", "description": { "zh": "要选择的值数组。", "en": "Values to select." }, "type": "array", "required": true },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述。", "en": "Optional human-readable element description." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "take_screenshot",
            "description": { "zh": "截取当前浏览器页面或元素，返回图片保存路径；需要查看图片时，用 read_file 读取返回的路径。type=pdf 时把整页存成 PDF（图片式，文字不可选）。", "en": "Capture the current browser page or element and return its saved image path. Use read_file on that path to view the image. With type=pdf the whole page is saved as a PDF (image-based, text not selectable)." },
            "parameters": [
                { "name": "type", "description": { "zh": "格式：png（默认）、jpeg 或 pdf；pdf 不能和 ref 同时使用。", "en": "Format: png (default), jpeg or pdf; pdf cannot be combined with ref." }, "type": "string", "required": false },
                { "name": "fullPage", "description": { "zh": "截取完整页面，不能与 ref 同时使用。", "en": "Capture the full page; cannot be combined with ref." }, "type": "boolean", "required": false },
                { "name": "ref", "description": { "zh": "可选，最新快照中的元素引用，仅截取该元素；提供 ref 时必须同时提供 element。", "en": "Optional element reference from the latest snapshot to capture only that element; requires element." }, "type": "string", "required": false },
                { "name": "element", "description": { "zh": "目标元素描述；提供 ref 时必填，截取页面时省略。", "en": "Target description, required with ref; omit for page screenshots." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "click_xy",
            "description": { "zh": "按页面截图上的像素坐标点按页面，也能长按、滑动或右键。先用 take_screenshot 截取当前页面（不带 fullPage 和 ref），再用图上的坐标。适合快照里没有 ref 的地方，比如画布、图片按钮、地图、滑块验证、跨域 iframe。", "en": "Tap the page at pixel coordinates of a page screenshot; can also long-press, swipe or right-click. Take a plain take_screenshot first (no fullPage or ref) and use coordinates from that image. Useful where the snapshot has no ref, such as canvases, image buttons, maps, slider checks or cross-origin iframes." },
            "parameters": [
                { "name": "x", "description": { "zh": "横坐标，截图左边为 0。", "en": "Horizontal pixel in the screenshot, 0 at the left edge." }, "type": "number", "required": true },
                { "name": "y", "description": { "zh": "纵坐标，截图上边为 0。", "en": "Vertical pixel in the screenshot, 0 at the top edge." }, "type": "number", "required": true },
                { "name": "doubleClick", "description": { "zh": "可选，是否连点两下，默认 false。", "en": "Optional double tap. Defaults to false." }, "type": "boolean", "required": false },
                { "name": "holdMs", "description": { "zh": "可选，按住多少毫秒再松开，用于长按（最多 10000）。", "en": "Optional milliseconds to hold before release, for a long press (max 10000)." }, "type": "number", "required": false },
                { "name": "endX", "description": { "zh": "可选，滑动终点横坐标，需与 endY 一起给。", "en": "Optional swipe end x; give together with endY." }, "type": "number", "required": false },
                { "name": "endY", "description": { "zh": "可选，滑动终点纵坐标，需与 endX 一起给。", "en": "Optional swipe end y; give together with endX." }, "type": "number", "required": false },
                { "name": "button", "description": { "zh": "可选，left（默认）/right/middle；滑动只能用 left。", "en": "Optional left (default), right or middle; swipes use left only." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "devtools",
            "description": { "zh": "向当前标签页发送一条 Chrome DevTools 协议（CDP）命令并返回结果，用来调试页面：读 DOM 和 CSS、看性能指标、取响应内容（先用 browser_network_requests 查到 requestId）、模拟设备等。传 events=true 可读取最近收到的事件（例如 WebSocket 帧、Console、Page 事件，最多 200 条）。只能用当前页面范围内的域，例如 Runtime、DOM、CSS、Network、Page、Performance、Emulation、Debugger；Target、Browser、Storage、Fetch 不可用。", "en": "Send one Chrome DevTools Protocol (CDP) command to the current tab and return its result, for debugging the page: read DOM and CSS, check performance metrics, get response bodies (find the requestId with browser_network_requests first), emulate devices and so on. Pass events=true to read recently received events (such as WebSocket frames, Console and Page events, up to 200). Only page-scoped domains work, such as Runtime, DOM, CSS, Network, Page, Performance, Emulation and Debugger; Target, Browser, Storage and Fetch are not available." },
            "parameters": [
                { "name": "method", "description": { "zh": "命令名，格式 Domain.command，例如 DOM.getDocument 或 Network.getResponseBody；只读事件时可省略。", "en": "Command name as Domain.command, for example DOM.getDocument or Network.getResponseBody; may be omitted when only reading events." }, "type": "string", "required": false },
                { "name": "events", "description": { "zh": "可选，为 true 时附带最近收到的事件；只读事件时可以不填 method。", "en": "Optional; when true, also return recently received events. method may be omitted to only read events." }, "type": "boolean", "required": false },
                { "name": "params", "description": { "zh": "可选，命令参数，JSON 对象。", "en": "Optional command parameters as a JSON object." }, "type": "object", "required": false },
                { "name": "filename", "description": { "zh": "可选，结果太长时保存到这个文件。", "en": "Optional file to save a long result to." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "downloads",
            "description": { "zh": "查看和管理本登录空间的下载：列出、查看状态、等待完成、取消或重试。点下载链接后，结果里会给出 downloadId。", "en": "View and manage downloads for this login profile: list, check status, wait until finished, cancel or retry. Clicking a download link returns its downloadId." },
            "parameters": [
                { "name": "action", "description": { "zh": "可选，list（默认）/status/wait/cancel/retry。", "en": "Optional: list (default), status, wait, cancel or retry." }, "type": "string", "required": false },
                { "name": "downloadId", "description": { "zh": "status/wait/cancel/retry 时必填。", "en": "Required for status, wait, cancel and retry." }, "type": "string", "required": false },
                { "name": "timeout", "description": { "zh": "可选，wait 最多等多少秒，1 到 120，默认 60。", "en": "Optional seconds for wait, 1 to 120, default 60." }, "type": "number", "required": false },
                { "name": "offset", "description": { "zh": "可选，list 时跳过最新的多少条，用来翻看更早的下载，默认 0。", "en": "Optional for list: skip this many newest downloads to page back to older ones. Default 0." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "clipboard",
            "description": { "zh": "在页面和手机剪贴板之间复制粘贴。paste 会先交给页面自己处理（适合编辑器和分格验证码框）。", "en": "Copy and paste between the page and the phone clipboard. paste is offered to the page first, which suits editors and split code boxes." },
            "parameters": [
                { "name": "action", "description": { "zh": "read（读页面选中内容和剪贴板）/copy（复制页面选中内容）/write（把 text 放进剪贴板）/paste（粘贴 text 或剪贴板内容到光标处）。", "en": "read (page selection and clipboard), copy (copy the page selection), write (put text on the clipboard) or paste (paste text or the clipboard at the cursor)." }, "type": "string", "required": true },
                { "name": "text", "description": { "zh": "write 必填；paste 可选，不填则粘贴剪贴板内容。", "en": "Required for write; optional for paste, which otherwise uses the clipboard." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "permissions",
            "description": { "zh": "按网站保存摄像头、麦克风、定位等权限的答案。网站再次请求时直接按保存的答案处理。", "en": "Save per-site answers for camera, microphone, location and similar requests, so the site gets the saved answer next time." },
            "parameters": [
                { "name": "action", "description": { "zh": "可选，list（默认）/allow/block/reset。", "en": "Optional: list (default), allow, block or reset." }, "type": "string", "required": false },
                { "name": "origin", "description": { "zh": "可选，网站地址，例如 https://example.com；默认当前页面。", "en": "Optional site, such as https://example.com; defaults to the current page." }, "type": "string", "required": false },
                { "name": "permission", "description": { "zh": "可选，all（默认）/camera/microphone/location/protected_media/midi。", "en": "Optional: all (default), camera, microphone, location, protected_media or midi." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "snapshot",
            "description": { "zh": "获取当前页面结构化快照，包括同源 iframe 内容。", "en": "Get a structured page snapshot, including same-origin iframe content." },
            "parameters": [
                { "name": "filename", "description": { "zh": "可选，保存快照的文件名。", "en": "Optional snapshot output file name." }, "type": "string", "required": false },
                { "name": "selector", "description": { "zh": "可选，作为快照根节点的元素选择器。", "en": "Optional root element selector for a partial snapshot." }, "type": "string", "required": false },
                { "name": "depth", "description": { "zh": "可选，限制快照树深度。", "en": "Optional snapshot tree depth limit." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "type",
            "description": { "zh": "向可编辑元素输入文本。", "en": "Type text into an editable element." },
            "parameters": [
                { "name": "ref", "description": { "zh": "目标元素 ref。", "en": "Target element ref." }, "type": "string", "required": true },
                { "name": "text", "description": { "zh": "输入文本。", "en": "Text to type." }, "type": "string", "required": true },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述。", "en": "Optional human-readable element description." }, "type": "string", "required": false },
                { "name": "submit", "description": { "zh": "可选，输入后是否提交。", "en": "Optional submit after typing." }, "type": "boolean", "required": false },
                { "name": "slowly", "description": { "zh": "可选，是否逐字输入。", "en": "Optional type slowly." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "wait_for",
            "description": { "zh": "等待文本出现、消失，等待某个元素出现或消失，或等待指定时间。会查找同源 iframe 和 Shadow DOM。", "en": "Wait for text to appear or disappear, for an element to appear or disappear, or for a duration. Searches same-origin iframes and Shadow DOM." },
            "parameters": [
                { "name": "time", "description": { "zh": "可选，等待秒数。", "en": "Optional number of seconds to wait." }, "type": "number", "required": false },
                { "name": "text", "description": { "zh": "可选，等待出现的文本。", "en": "Optional text to wait for." }, "type": "string", "required": false },
                { "name": "textGone", "description": { "zh": "可选，等待消失的文本。", "en": "Optional text to wait to disappear." }, "type": "string", "required": false },
                { "name": "selector", "description": { "zh": "可选，要等待的元素选择器。", "en": "Optional element selector to wait for." }, "type": "string", "required": false },
                { "name": "state", "description": { "zh": "可选，配合 selector：visible（默认）/hidden/attached/detached。", "en": "Optional, with selector: visible (default), hidden, attached or detached." }, "type": "string", "required": false },
                { "name": "timeout", "description": { "zh": "可选，最多等多少秒，1 到 60，默认 10。", "en": "Optional maximum seconds to wait, 1 to 60, default 10." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "tabs",
            "description": { "zh": "列出、创建、切换或关闭 tab；页面崩溃后可用 restore 重新打开。", "en": "List, create, select or close tabs; use restore to reopen a page after it crashed." },
            "parameters": [
                { "name": "action", "description": { "zh": "操作：list/create/select/close/restore。", "en": "Action: list/create/select/close/restore." }, "type": "string", "required": true },
                { "name": "index", "description": { "zh": "可选，0-based tab 索引。", "en": "Optional 0-based tab index." }, "type": "number", "required": false }
            ]
        },
    ]
}*/

const MAX_INLINE_BROWSER_TEXT_CHARS = 24000;
type BrowserTabAction = "list" | "create" | "select" | "close";
type BrowserMouseButton = "left" | "right" | "middle";
type BrowserModifierKey = "Alt" | "Control" | "ControlOrMeta" | "Meta" | "Shift";
type ToolParamValue = string | number | boolean | object;

interface FilenamePayload {
    filename?: string;
}

interface SnapshotPayload extends FilenamePayload {
    selector?: string;
    depth?: number;
}

interface ClickPayload {
    ref?: string;
    selector?: string;
    element?: string;
    button?: BrowserMouseButton;
    modifiers?: BrowserModifierKey[];
    doubleClick?: boolean;
}

interface ConsoleMessagesPayload extends FilenamePayload {
    level: string;
}

interface EvaluatePayload {
    function: string;
    ref?: string;
    element?: string;
}

interface UploadPayload {
    paths?: string[];
}

interface FillFormFieldPayload {
    name: string;
    type: string;
    value: ToolParamValue;
    ref?: string;
    selector?: string;
}

interface FillFormPayload {
    fields: FillFormFieldPayload[];
}

interface HandleDialogPayload {
    accept: boolean;
    promptText?: string;
}

interface HoverPayload {
    ref: string;
    element?: string;
}

interface DragPayload {
    startElement: string;
    startRef: string;
    endElement: string;
    endRef: string;
    mode?: "auto" | "mouse" | "touch" | "html5";
}

interface GotoPayload {
    url: string;
}

interface NetworkRequestsPayload extends FilenamePayload {
    includeStatic?: boolean;
}

interface PressKeyPayload {
    key: string;
}

interface ResizePayload {
    width: number;
    height: number;
}

interface RunCodePayload {
    code: string;
}

interface ScreenshotPayload {
    type?: string;
    fullPage?: boolean;
    ref?: string;
    element?: string;
}

interface SelectOptionPayload {
    ref: string;
    values: string[];
    element?: string;
}

interface TypePayload {
    ref: string;
    text: string;
    element?: string;
    submit?: boolean;
    slowly?: boolean;
}

interface WaitForPayload {
    time?: number;
    text?: string;
    textGone?: string;
    selector?: string;
    state?: "visible" | "hidden" | "attached" | "detached";
    timeout?: number;
}

interface ClickXyPayload {
    x: number;
    y: number;
    doubleClick?: boolean;
    holdMs?: number;
    endX?: number;
    endY?: number;
    button?: "left" | "right" | "middle";
}

interface DevtoolsPayload {
    method?: string;
    events?: boolean;
    params?: Record<string, unknown> | string;
    filename?: string;
}

interface DownloadsPayload {
    action?: "list" | "status" | "wait" | "cancel" | "retry";
    downloadId?: string;
    timeout?: number;
    offset?: number;
}

interface ClipboardPayload {
    action: "read" | "copy" | "write" | "paste";
    text?: string;
}

interface PermissionsPayload {
    action?: "list" | "allow" | "block" | "reset";
    origin?: string;
    permission?: string;
}

interface TabsPayload {
    action: BrowserTabAction;
    index?: number;
}

const TOOL_NAMES = [
    "click",
    "close",
    "close_all",
    "console_messages",
    "drag",
    "evaluate",
    "upload",
    "fill_form",
    "handle_dialog",
    "hover",
    "goto",
    "back",
    "network_requests",
    "press_key",
    "resize",
    "run_code",
    "select_option",
    "snapshot",
    "take_screenshot",
    "click_xy",
    "devtools",
    "downloads",
    "clipboard",
    "permissions",
    "type",
    "wait_for",
    "tabs"
];

function normalizeOptionalString(value?: string): string | undefined {
    if (value === undefined) {
        return undefined;
    }
    const normalized = value.trim();
    return normalized ? normalized : undefined;
}

function buildLargeOutputFilename(prefix: string, extension: string): string {
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
    const rand = Math.floor(Math.random() * 1000000);
    return OPERIT_CLEAN_ON_EXIT_DIR + "/browser_" + prefix + "_" + timestamp + "_" + rand + "." + extension;
}

async function maybePersistLargeBrowserResponse(
    result: string,
    prefix: string,
    extension: string = "md"
) {
    if (result.length <= MAX_INLINE_BROWSER_TEXT_CHARS) {
        return result;
    }
    await Tools.Files.mkdir(OPERIT_CLEAN_ON_EXIT_DIR, true);
    const filename = buildLargeOutputFilename(prefix, extension);
    await Tools.Files.write(filename, result, false);
    const normalizedPath = filename.replace(/\\/g, "/");
    return "Large browser response saved to:\n- [Browser Output](" + normalizedPath + ")";
}

async function click(params: ClickPayload) {
    const result = await Tools.Net.browserClick(params);
    return maybePersistLargeBrowserResponse(result, "click");
}

async function close() {
    const result = await Tools.Net.browserClose();
    return maybePersistLargeBrowserResponse(result, "close");
}

async function close_all() {
    const result = await Tools.Net.browserCloseAll();
    return maybePersistLargeBrowserResponse(result, "close_all");
}

async function console_messages(params: Partial<ConsoleMessagesPayload> = {}) {
    const result = await Tools.Net.browserConsoleMessages(params);
    return maybePersistLargeBrowserResponse(result, "console_messages");
}

async function drag(params: DragPayload) {
    const result = await Tools.Net.browserDrag(params);
    return maybePersistLargeBrowserResponse(result, "drag");
}

async function evaluate(params: EvaluatePayload) {
    const result = await Tools.Net.browserEvaluate(params);
    return maybePersistLargeBrowserResponse(result, "evaluate");
}

async function upload(params: UploadPayload = {}) {
    const result = await Tools.Net.browserFileUpload(params);
    return maybePersistLargeBrowserResponse(result, "upload");
}

function normalizeFormFields(fields: FillFormFieldPayload[]): FillFormFieldPayload[] {
    if (fields.length === 0) {
        throw new Error("fields must be a non-empty array");
    }
    return fields.map((field, index) => {
        const normalized: FillFormFieldPayload = {
            name: field.name.trim(),
            type: field.type.trim(),
            value: field.value as ToolParamValue
        };
        if (!normalized.name) {
            throw new Error("fields[" + index + "].name is required");
        }
        if (!normalized.type) {
            throw new Error("fields[" + index + "].type is required");
        }
        const ref = normalizeOptionalString(field.ref);
        const selector = normalizeOptionalString(field.selector);
        if (!ref && !selector) {
            throw new Error("fields[" + index + "] requires ref or selector");
        }
        if (ref) {
            normalized.ref = ref;
        }
        if (selector) {
            normalized.selector = selector;
        }
        return normalized;
    });
}

async function fill_form(params: FillFormPayload) {
    const result = await Tools.Net.browserFillForm({
        fields: normalizeFormFields(params.fields)
    });
    return maybePersistLargeBrowserResponse(result, "fill_form");
}

async function handle_dialog(params: HandleDialogPayload) {
    const result = await Tools.Net.browserHandleDialog(params);
    return maybePersistLargeBrowserResponse(result, "handle_dialog");
}

async function hover(params: HoverPayload) {
    const result = await Tools.Net.browserHover(params);
    return maybePersistLargeBrowserResponse(result, "hover");
}

async function goto(params: GotoPayload) {
    const result = await Tools.Net.browserNavigate(params);
    return maybePersistLargeBrowserResponse(result, "goto");
}

async function back() {
    const result = await Tools.Net.browserNavigateBack();
    return maybePersistLargeBrowserResponse(result, "back");
}

async function network_requests(params: NetworkRequestsPayload = {}) {
    const result = await Tools.Net.browserNetworkRequests(params);
    return maybePersistLargeBrowserResponse(result, "network_requests");
}

async function press_key(params: PressKeyPayload) {
    const result = await Tools.Net.browserPressKey(params);
    return maybePersistLargeBrowserResponse(result, "press_key");
}

async function resize(params: ResizePayload) {
    const result = await Tools.Net.browserResize(params);
    return maybePersistLargeBrowserResponse(result, "resize");
}

async function run_code(params: RunCodePayload) {
    const result = await Tools.Net.browserRunCode(params);
    return maybePersistLargeBrowserResponse(result, "run_code");
}

async function select_option(params: SelectOptionPayload) {
    const result = await Tools.Net.browserSelectOption(params);
    return maybePersistLargeBrowserResponse(result, "select_option");
}

async function snapshot(params: SnapshotPayload = {}) {
    const result = await Tools.Net.browserSnapshot(params);
    return maybePersistLargeBrowserResponse(result, "snapshot");
}

async function take_screenshot(params: ScreenshotPayload = {}) {
    return Tools.Net.browserTakeScreenshot(params);
}

async function click_xy(params: ClickXyPayload) {
    const result = await Tools.Net.browserMouseClickXy(params);
    return maybePersistLargeBrowserResponse(result, "click_xy");
}

async function devtools(params: DevtoolsPayload) {
    const result = await Tools.Net.browserDevtools(params);
    return maybePersistLargeBrowserResponse(result, "devtools");
}

async function downloads(params: DownloadsPayload = {}) {
    const result = await Tools.Net.browserDownloads(params);
    return maybePersistLargeBrowserResponse(result, "downloads");
}

async function clipboard(params: ClipboardPayload) {
    const result = await Tools.Net.browserClipboard(params);
    return maybePersistLargeBrowserResponse(result, "clipboard");
}

async function permissions(params: PermissionsPayload = {}) {
    const result = await Tools.Net.browserPermissions(params);
    return maybePersistLargeBrowserResponse(result, "permissions");
}

async function type(params: TypePayload) {
    const result = await Tools.Net.browserType(params);
    return maybePersistLargeBrowserResponse(result, "type");
}

async function wait_for(params: WaitForPayload = {}) {
    const result = await Tools.Net.browserWaitFor(params);
    return maybePersistLargeBrowserResponse(result, "wait_for");
}

async function tabs(params: TabsPayload) {
    const result = await Tools.Net.browserTabs(params);
    return maybePersistLargeBrowserResponse(result, "tabs");
}

async function browserMain() {
    return "Browser package ready: " + TOOL_NAMES.join(", ");
}

exports.click = click;
exports.close = close;
exports.close_all = close_all;
exports.console_messages = console_messages;
exports.drag = drag;
exports.evaluate = evaluate;
exports.upload = upload;
exports.fill_form = fill_form;
exports.handle_dialog = handle_dialog;
exports.hover = hover;
exports.goto = goto;
exports.back = back;
exports.network_requests = network_requests;
exports.press_key = press_key;
exports.resize = resize;
exports.run_code = run_code;
exports.select_option = select_option;
exports.snapshot = snapshot;
exports.take_screenshot = take_screenshot;
exports.click_xy = click_xy;
exports.devtools = devtools;
exports.downloads = downloads;
exports.clipboard = clipboard;
exports.permissions = permissions;
exports.type = type;
exports.wait_for = wait_for;
exports.tabs = tabs;
exports.main = browserMain;
