package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import java.util.Locale
import org.json.JSONObject

/** A problem the AI can fix by changing its request; its message is shown to the AI as is. */
internal class BrowserInputException(message: String) : RuntimeException(message)

internal data class BrowserKey(
    val key: String,
    val code: String,
    val keyCode: Int,
    val text: String? = null,
    val location: Int = 0
)

internal data class BrowserKeyCombo(val modifiers: List<BrowserKey>, val key: BrowserKey) {
    val label: String
        get() = (modifiers.map { it.key } + (if (key.key == " ") "Space" else key.key)).joinToString("+")

    fun has(name: String): Boolean = modifiers.any { it.key == name }

    /** Text the key types. None while Control, Alt or Meta is held, as on a real keyboard. */
    val text: String?
        get() {
            if (has("Control") || has("Alt") || has("Meta")) return null
            val base = key.text ?: return null
            return if (has("Shift") && base.length == 1 && base[0] in 'a'..'z') base.uppercase(Locale.ROOT) else base
        }

    /** True for Control/Meta + C, X or V, which go through the Android clipboard. */
    fun clipboardAction(): Char? {
        if (!(has("Control") || has("Meta")) || has("Alt") || has("Shift")) return null
        return key.key.lowercase(Locale.ROOT).singleOrNull()?.takeIf { it == 'c' || it == 'x' || it == 'v' }
    }
}

/** Key names in the form Playwright uses: "Enter", "ArrowLeft", "Control+A", "Shift+Tab", "a". */
internal object BrowserKeys {
    private val modifierNames = setOf("Shift", "Control", "Alt", "Meta")

    private val table: Map<String, BrowserKey> =
        buildMap {
            fun add(key: BrowserKey) = put(key.key.lowercase(Locale.ROOT), key)
            add(BrowserKey("Enter", "Enter", 13, "\r"))
            add(BrowserKey("Tab", "Tab", 9))
            add(BrowserKey("Escape", "Escape", 27))
            add(BrowserKey("Backspace", "Backspace", 8))
            add(BrowserKey("Delete", "Delete", 46))
            add(BrowserKey("Insert", "Insert", 45))
            add(BrowserKey("Home", "Home", 36))
            add(BrowserKey("End", "End", 35))
            add(BrowserKey("PageUp", "PageUp", 33))
            add(BrowserKey("PageDown", "PageDown", 34))
            add(BrowserKey("ArrowLeft", "ArrowLeft", 37))
            add(BrowserKey("ArrowUp", "ArrowUp", 38))
            add(BrowserKey("ArrowRight", "ArrowRight", 39))
            add(BrowserKey("ArrowDown", "ArrowDown", 40))
            add(BrowserKey("CapsLock", "CapsLock", 20))
            add(BrowserKey("ContextMenu", "ContextMenu", 93))
            add(BrowserKey("Shift", "ShiftLeft", 16, location = 1))
            add(BrowserKey("Control", "ControlLeft", 17, location = 1))
            add(BrowserKey("Alt", "AltLeft", 18, location = 1))
            add(BrowserKey("Meta", "MetaLeft", 91, location = 1))
            put(" ", BrowserKey(" ", "Space", 32, " "))
            for (n in 1..12) add(BrowserKey("F$n", "F$n", 111 + n))
            for (c in 'a'..'z') put(c.toString(), BrowserKey(c.toString(), "Key${c.uppercaseChar()}", c.uppercaseChar().code, c.toString()))
            for (d in '0'..'9') put(d.toString(), BrowserKey(d.toString(), "Digit$d", d.code, d.toString()))
            listOf(
                Triple("-", "Minus", 189), Triple("=", "Equal", 187), Triple("[", "BracketLeft", 219),
                Triple("]", "BracketRight", 221), Triple("\\", "Backslash", 220), Triple(";", "Semicolon", 186),
                Triple("'", "Quote", 222), Triple(",", "Comma", 188), Triple(".", "Period", 190),
                Triple("/", "Slash", 191), Triple("`", "Backquote", 192)
            ).forEach { (key, code, keyCode) -> put(key, BrowserKey(key, code, keyCode, key)) }
        }

    private val aliases =
        mapOf(
            "esc" to "escape", "return" to "enter", "space" to " ", "spacebar" to " ", "del" to "delete",
            "ins" to "insert", "up" to "arrowup", "down" to "arrowdown", "left" to "arrowleft",
            "right" to "arrowright", "pgup" to "pageup", "pgdn" to "pagedown", "ctrl" to "control",
            "controlormeta" to "control", "cmd" to "meta", "command" to "meta", "win" to "meta",
            "super" to "meta", "option" to "alt"
        )

    /** CDP modifier bits. */
    fun modifierBit(name: String): Int =
        when (name) {
            "Alt" -> 1
            "Control" -> 2
            "Meta" -> 4
            "Shift" -> 8
            else -> 0
        }

    fun parse(raw: String): BrowserKeyCombo {
        val value = if (raw.isBlank()) raw else raw.trim()
        if (value.isEmpty()) throw BrowserInputException("key is required")
        val (modifierPart, keyPart) =
            when {
                value == "+" -> "" to "+"
                value.endsWith("++") -> value.dropLast(2) to "+"
                value.length > 1 && value.contains('+') -> value.substringBeforeLast('+') to value.substringAfterLast('+')
                else -> "" to value
            }
        val modifiers =
            modifierPart.split('+').filter { it.isNotBlank() }.map { name ->
                lookup(name.trim())?.takeIf { it.key in modifierNames }
                    ?: throw BrowserInputException("\"${name.trim()}\" is not a modifier key. Use Shift, Control, Alt or Meta.")
            }.distinctBy { it.key }
        val key =
            lookup(keyPart)
                ?: keyPart.takeIf { it.codePointCount(0, it.length) == 1 }?.let { BrowserKey(it, "", 0, it) }
                ?: throw BrowserInputException(
                    "Unknown key \"$keyPart\". Use a key name such as Enter, Tab, Escape, Backspace, Delete, " +
                        "ArrowLeft, Home, PageDown or F5, a single character, or a combination such as Control+A or Shift+Tab."
                )
        return BrowserKeyCombo(modifiers, key)
    }

    private fun lookup(name: String): BrowserKey? {
        if (name == " ") return table[" "]
        val trimmed = name.trim()
        if (trimmed.length == 1 && trimmed[0] in 'A'..'Z') {
            return BrowserKey(trimmed, "Key$trimmed", trimmed[0].code, trimmed)
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        return table[aliases[lower] ?: lower]
    }
}

/** Presses a key the way a hardware keyboard does, so the browser's own default actions happen. */
internal fun cdpPressKey(connection: CdpConnection, combo: BrowserKeyCombo) {
    var mask = 0
    combo.modifiers.forEach { modifier ->
        mask = mask or BrowserKeys.modifierBit(modifier.key)
        sendCdpKey(connection, "rawKeyDown", modifier, mask, null)
    }
    val text = combo.text
    sendCdpKey(connection, if (text != null) "keyDown" else "rawKeyDown", combo.key, mask, text)
    sendCdpKey(connection, "keyUp", combo.key, mask, null)
    combo.modifiers.asReversed().forEach { modifier ->
        mask = mask and BrowserKeys.modifierBit(modifier.key).inv()
        sendCdpKey(connection, "keyUp", modifier, mask, null)
    }
}

private fun sendCdpKey(connection: CdpConnection, type: String, key: BrowserKey, mask: Int, text: String?) {
    val params =
        JSONObject()
            .put("type", type)
            .put("modifiers", mask)
            .put("key", key.key)
            .put("windowsVirtualKeyCode", key.keyCode)
            .put("location", key.location)
    if (key.code.isNotEmpty()) params.put("code", key.code)
    if (text != null) params.put("text", text).put("unmodifiedText", text)
    connection.send("Input.dispatchKeyEvent", params, 5_000L)
}

/** One real mouse event at a point in CSS pixels of the page's visible area. */
internal fun cdpMouse(
    connection: CdpConnection,
    type: String,
    x: Double,
    y: Double,
    button: String = "none",
    buttons: Int = 0,
    clickCount: Int = 0,
    deltaX: Double = 0.0,
    deltaY: Double = 0.0
) {
    val params =
        JSONObject()
            .put("type", type)
            .put("x", x)
            .put("y", y)
            .put("button", button)
            .put("buttons", buttons)
            .put("clickCount", clickCount)
    if (type == "mouseWheel") params.put("deltaX", deltaX).put("deltaY", deltaY)
    connection.send("Input.dispatchMouseEvent", params, 5_000L)
}

/**
 * Where an element is, in CSS pixels of the top page (iframe offsets included), plus the
 * visible area, so points can be turned into DevTools mouse events or Android touches.
 */
internal data class ElementGeometry(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val visualLeft: Double,
    val visualTop: Double,
    val visualWidth: Double,
    val visualHeight: Double,
    /** Pinch zoom of the visible area; desktop pages are often shown zoomed out. */
    val visualScale: Double,
    val explicitDraggable: Boolean,
    /** How far the page is scrolled, to turn view positions into page positions. */
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0
) {
    val centerX: Double get() = left + width / 2.0
    val centerY: Double get() = top + height / 2.0

    /** DevTools mouse coordinates for a page point; [scaled] applies the pinch zoom. */
    fun cdpX(x: Double, scaled: Boolean): Double = (x - visualLeft) * (if (scaled) visualScale else 1.0)

    fun cdpY(y: Double, scaled: Boolean): Double = (y - visualTop) * (if (scaled) visualScale else 1.0)

    fun isPointVisible(x: Double, y: Double): Boolean =
        x >= visualLeft && y >= visualTop && x < visualLeft + visualWidth && y < visualTop + visualHeight

    /** Android view pixels for a CSS point; pinch zoom is handled through the visual viewport. */
    fun toViewX(x: Double, viewWidth: Int): Float =
        ((x - visualLeft) * viewWidth / visualWidth.coerceAtLeast(1.0)).toFloat()

    fun toViewY(y: Double, viewHeight: Int): Float =
        ((y - visualTop) * viewHeight / visualHeight.coerceAtLeast(1.0)).toFloat()
}

/** [scroll] is "center", "nearest" or null for no scrolling. Null result: the ref is gone. */
internal fun StandardBrowserSessionTools.elementGeometry(
    webView: WebView,
    ref: String,
    scroll: String?
): ElementGeometry? {
    val scrollScript =
        when (scroll) {
            null -> ""
            else ->
                """
                try {
                    target.scrollIntoView({ block: ${JSONObject.quote(scroll)}, inline: ${JSONObject.quote(scroll)}, behavior: "instant" });
                } catch (_) {
                    try { target.scrollIntoView(); } catch (_) {}
                }
                """.trimIndent()
        }
    val script =
        """
        (function() {
            ${browserRefResolverScript()}
            const resolved = __operitResolveRef(${JSONObject.quote(ref)});
            const target = resolved ? resolved.element : null;
            if (!target) {
                return JSON.stringify({ ok: false, error: "ref_not_found" });
            }
            $scrollScript
            const rect = target.getBoundingClientRect();
            let left = rect.left;
            let top = rect.top;
            let currentWindow = resolved.window || window;
            while (currentWindow && currentWindow !== window) {
                let frameElement = null;
                try { frameElement = currentWindow.frameElement; } catch (_) {}
                if (!frameElement) break;
                const frameRect = frameElement.getBoundingClientRect();
                let style = null;
                try { style = frameElement.ownerDocument.defaultView.getComputedStyle(frameElement); } catch (_) {}
                left += frameRect.left + (style ? (parseFloat(style.borderLeftWidth) || 0) + (parseFloat(style.paddingLeft) || 0) : 0);
                top += frameRect.top + (style ? (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.paddingTop) || 0) : 0);
                try { currentWindow = frameElement.ownerDocument.defaultView; } catch (_) { break; }
            }
            const viewportWidth = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
            const viewportHeight = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
            const vv = window.visualViewport;
            return JSON.stringify({
                ok: true,
                left: left,
                top: top,
                width: rect.width,
                height: rect.height,
                viewportWidth: viewportWidth,
                viewportHeight: viewportHeight,
                visualLeft: vv ? vv.offsetLeft : 0,
                visualTop: vv ? vv.offsetTop : 0,
                visualWidth: vv ? vv.width : viewportWidth,
                visualHeight: vv ? vv.height : viewportHeight,
                visualScale: vv ? vv.scale : 1,
                scrollX: window.scrollX || window.pageXOffset || 0,
                scrollY: window.scrollY || window.pageYOffset || 0,
                draggable: !!(target.getAttribute && target.getAttribute("draggable") === "true")
            });
        })();
        """.trimIndent()
    val json = runJsonScript(webView, script, "element_geometry_error") ?: return null
    if (!json.optBoolean("ok", false)) {
        if (json.optString("error") == "ref_not_found") return null
        throw BrowserInputException("Could not locate the element: ${json.optString("error")}")
    }
    return ElementGeometry(
        left = json.optDouble("left", 0.0),
        top = json.optDouble("top", 0.0),
        width = json.optDouble("width", 0.0),
        height = json.optDouble("height", 0.0),
        viewportWidth = json.optDouble("viewportWidth", 0.0),
        viewportHeight = json.optDouble("viewportHeight", 0.0),
        visualLeft = json.optDouble("visualLeft", 0.0),
        visualTop = json.optDouble("visualTop", 0.0),
        visualWidth = json.optDouble("visualWidth", json.optDouble("viewportWidth", 1.0)),
        visualHeight = json.optDouble("visualHeight", json.optDouble("viewportHeight", 1.0)),
        visualScale = json.optDouble("visualScale", 1.0).takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
        explicitDraggable = json.optBoolean("draggable", false),
        scrollX = json.optDouble("scrollX", 0.0),
        scrollY = json.optDouble("scrollY", 0.0)
    )
}

/**
 * Moves the DevTools mouse over the element and checks the page really sees it under the mouse.
 * Tries the zoomed coordinates first, then plain ones. Returns which worked, or null for neither
 * (the element may be covered or ignore the pointer).
 */
internal fun StandardBrowserSessionTools.cdpHoverElement(
    connection: CdpConnection,
    webView: WebView,
    ref: String,
    geometry: ElementGeometry
): Boolean? {
    val options = if (kotlin.math.abs(geometry.visualScale - 1.0) < 0.01) listOf(true) else listOf(true, false)
    for (scaled in options) {
        cdpMouse(connection, "mouseMoved", geometry.cdpX(geometry.centerX, scaled), geometry.cdpY(geometry.centerY, scaled))
        Thread.sleep(60L)
        if (isElementHovered(webView, ref)) return scaled
    }
    return null
}

/** Whether the element really is under the mouse, as the page's own :hover styles see it. */
internal fun StandardBrowserSessionTools.isElementHovered(webView: WebView, ref: String): Boolean {
    val script =
        """
        (function() {
            ${browserRefResolverScript()}
            const resolved = __operitResolveRef(${JSONObject.quote(ref)});
            const target = resolved ? resolved.element : null;
            let hovered = false;
            try { hovered = !!(target && target.matches(":hover")); } catch (_) {}
            return JSON.stringify({ ok: true, hovered: hovered });
        })();
        """.trimIndent()
    return runJsonScript(webView, script, "hover_check_error")?.optBoolean("hovered", false) == true
}

/**
 * Real Android touches on the WebView: one finger goes down, moves along [points] (view pixels)
 * and lifts, with real time between moves so pages that track speed see a human-like drag.
 */
internal fun StandardBrowserSessionTools.dispatchTouchPath(
    webView: WebView,
    points: List<Pair<Float, Float>>,
    stepDelayMs: Long = 16L,
    holdBeforeMoveMs: Long = 0L
) {
    require(points.isNotEmpty()) { "A touch needs at least one point" }
    val downTime = SystemClock.uptimeMillis()
    var eventTime = downTime
    fun send(action: Int, point: Pair<Float, Float>, at: Long) {
        runOnMainSync {
            if (action == MotionEvent.ACTION_DOWN) webView.requestFocus()
            val event = MotionEvent.obtain(downTime, at, action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
    send(MotionEvent.ACTION_DOWN, points.first(), eventTime)
    if (holdBeforeMoveMs > 0L) {
        Thread.sleep(holdBeforeMoveMs)
        eventTime = SystemClock.uptimeMillis()
    }
    points.drop(1).forEach { point ->
        Thread.sleep(stepDelayMs)
        eventTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_MOVE, point, eventTime)
    }
    if (points.size > 1) Thread.sleep(stepDelayMs)
    send(MotionEvent.ACTION_UP, points.last(), SystemClock.uptimeMillis().coerceAtLeast(eventTime))
}

/** Evenly eased points from start to end, including both ends. */
internal fun easedPath(startX: Double, startY: Double, endX: Double, endY: Double, steps: Int): List<Pair<Double, Double>> =
    (0..steps).map { index ->
        val t = index.toDouble() / steps
        val eased = if (t < 0.5) 2 * t * t else 1 - Math.pow(-2 * t + 2, 2.0) / 2
        Pair(startX + (endX - startX) * eased, startY + (endY - startY) * eased)
    }

/** The text selected in the page or in the focused text box, for the clipboard bridge. */
internal fun StandardBrowserSessionTools.readPageSelection(webView: WebView): Pair<String, Boolean> {
    val script =
        """
        (function() {
            let doc = document;
            let active = doc.activeElement;
            while (active) {
                if (active.tagName === "IFRAME" || active.tagName === "FRAME") {
                    let inner = null;
                    try { inner = active.contentDocument; } catch (_) {}
                    if (!inner) break;
                    doc = inner;
                    active = inner.activeElement;
                } else if (active.shadowRoot && active.shadowRoot.activeElement) {
                    active = active.shadowRoot.activeElement;
                } else {
                    break;
                }
            }
            let text = "";
            let editable = false;
            const isField = !!active && (active.tagName === "TEXTAREA" ||
                (active.tagName === "INPUT" && !/^(button|submit|reset|checkbox|radio|file|image|range|color|hidden)$/i.test(active.type || "")));
            if (isField) {
                if (typeof active.selectionStart === "number") {
                    text = String(active.value || "").substring(active.selectionStart, active.selectionEnd);
                }
                editable = !active.readOnly && !active.disabled;
            } else {
                const view = doc.defaultView || window;
                text = String(view.getSelection ? view.getSelection() : "");
                editable = !!(active && active.isContentEditable);
            }
            return JSON.stringify({ ok: true, text: text, editable: editable });
        })();
        """.trimIndent()
    val json = runJsonScript(webView, script, "selection_read_error")
    return Pair(json?.optString("text").orEmpty(), json?.optBoolean("editable", false) == true)
}

internal fun StandardBrowserSessionTools.writeAndroidClipboard(text: String) {
    runOnMainSync {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Operit browser", text))
    }
}

/** Null when the clipboard is empty or Android does not let the app read it right now. */
internal fun StandardBrowserSessionTools.readAndroidClipboard(): String? =
    runOnMainSync {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

/**
 * Offers [text] to the page as a paste first, so pages with their own paste handling (code boxes
 * split into cells, rich editors) can take it. True means the page handled it itself.
 */
internal fun StandardBrowserSessionTools.offerPasteEvent(webView: WebView, text: String): Boolean {
    val script =
        """
        (function() {
            let doc = document;
            let target = doc.activeElement;
            while (target) {
                if (target.tagName === "IFRAME" || target.tagName === "FRAME") {
                    let inner = null;
                    try { inner = target.contentDocument; } catch (_) {}
                    if (!inner) break;
                    doc = inner;
                    target = inner.activeElement;
                } else if (target.shadowRoot && target.shadowRoot.activeElement) {
                    target = target.shadowRoot.activeElement;
                } else {
                    break;
                }
            }
            target = target || doc.body;
            if (!target) return JSON.stringify({ ok: true, handled: false });
            try {
                const view = doc.defaultView || window;
                const data = new view.DataTransfer();
                data.setData("text/plain", ${JSONObject.quote(text)});
                const event = new view.ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true, composed: true });
                const notCanceled = target.dispatchEvent(event);
                return JSON.stringify({ ok: true, handled: !notCanceled });
            } catch (_) {
                return JSON.stringify({ ok: true, handled: false });
            }
        })();
        """.trimIndent()
    return runJsonScript(webView, script, "paste_event_error")?.optBoolean("handled", false) == true
}
