package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.cefrium.CefriumBrowser
import java.nio.ByteBuffer

/**
 * Shows an off-screen Cefrium tab: draws its latest frame, and hands touches, keys and the
 * keyboard's text to the engine. The browser window places and scales it like a WebView, and
 * the tab follows its size. Main thread.
 */
internal class CefriumPageView(context: Context, private val page: CefriumBrowserPage) : View(context) {
    private var frame: Bitmap? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frameBounds = Rect()

    /** The engine's own popup (a select's list) drawn over the page. */
    private var popupFrame: Bitmap? = null
    private val popupBounds = Rect()
    private var popupShown = false

    /** Frames of a page nobody sees are copied a few times a second; a refresh fetches the last one. */
    private var lastCopyAt = 0L
    private var refreshPosted = false
    private val refresh = Runnable {
        refreshPosted = false
        page.devTools.requestFrame()
    }

    private var editable = false
    private var fieldType = ""
    private var inputModeName = "DEFAULT"
    /** The field type the keyboard was last set up for; another type sets it up again. */
    private var connectedFieldType: String? = null
    /** When a finger last touched the page, so the keyboard opens for the user and not for the AI. */
    private var lastRealTouchAt = 0L
    private var quickMenu: PopupWindow? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isHapticFeedbackEnabled = false
        setBackgroundColor(Color.WHITE)
    }

    /* Frames. */

    fun onFrame(width: Int, height: Int, pixels: ByteBuffer) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (!isSeen() && now - lastCopyAt < BACKGROUND_FRAME_MS) {
            // Skipped; make sure the latest picture still arrives.
            if (!refreshPosted) {
                refreshPosted = true
                postDelayed(refresh, BACKGROUND_FRAME_MS)
            }
            return
        }
        val target =
            frame?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    frame?.recycle()
                    frame = it
                }
        pixels.rewind()
        target.copyPixelsFromBuffer(pixels)
        lastCopyAt = now
        invalidate()
    }

    fun onPopupShow(show: Boolean) {
        popupShown = show
        if (!show) {
            popupFrame?.recycle()
            popupFrame = null
        }
        invalidate()
    }

    fun onPopupBounds(x: Int, y: Int, width: Int, height: Int) {
        popupBounds.set(x, y, x + width, y + height)
    }

    fun onPopupFrame(x: Int, y: Int, width: Int, height: Int, pixels: ByteBuffer) {
        if (width <= 0 || height <= 0) return
        val target =
            popupFrame?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    popupFrame?.recycle()
                    popupFrame = it
                }
        pixels.rewind()
        target.copyPixelsFromBuffer(pixels)
        popupBounds.set(x, y, x + width, y + height)
        invalidate()
    }

    /** The page as last drawn, at the view's size; null before the first frame. */
    fun copyFrame(): Bitmap? {
        if (width <= 0 || height <= 0 || frame == null) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        // The engine follows the view's size a frame later; stretch the last frame meanwhile.
        frameBounds.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, frameBounds, framePaint)
        val popup = popupFrame
        if (popupShown && popup != null) {
            val sx = width.toFloat() / bitmap.width
            val sy = height.toFloat() / bitmap.height
            val dst = Rect(
                (popupBounds.left * sx).toInt(), (popupBounds.top * sy).toInt(),
                (popupBounds.right * sx).toInt(), (popupBounds.bottom * sy).toInt(),
            )
            canvas.drawBitmap(popup, null, dst, framePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        page.resize(w, h)
    }

    /** Shown to the user: on a visible window and not faded out like the parked background pages. */
    private fun isSeen(): Boolean {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return false
        var alpha = this.alpha
        var parentView = parent as? View
        while (parentView != null && alpha >= 0.5f) {
            alpha *= parentView.alpha
            parentView = parentView.parent as? View
        }
        return alpha >= 0.5f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        page.setDialogActivity(findActivity(rootView.context))
        page.devTools.requestFrame()
    }

    override fun onDetachedFromWindow() {
        dismissQuickMenu()
        page.setDialogActivity(null)
        super.onDetachedFromWindow()
    }

    private fun findActivity(start: Context?): Activity? {
        var context = start
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    fun release() {
        removeCallbacks(refresh)
        dismissQuickMenu()
        hideKeyboard()
        frame?.recycle()
        frame = null
        popupFrame?.recycle()
        popupFrame = null
    }

    /* Touches and keys. */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page.isClosed) return false
        // Real fingers come from a device; the AI's taps are made up in the app and have none.
        if (event.deviceId != 0) lastRealTouchAt = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!hasFocus()) requestFocus()
                dismissQuickMenu()
                send(event, TOUCH_DOWN, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> send(event, TOUCH_DOWN, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(event, TOUCH_MOVE, i)
            MotionEvent.ACTION_POINTER_UP -> send(event, TOUCH_UP, event.actionIndex)
            MotionEvent.ACTION_UP -> {
                send(event, TOUCH_UP, event.actionIndex)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) send(event, TOUCH_CANCEL, i)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun send(event: MotionEvent, action: Int, index: Int) {
        page.touch(action, event.getX(index), event.getY(index), event.getPointerId(index))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back closes the long-press menu first, as a WebView's would.
        if (keyCode == KeyEvent.KEYCODE_BACK && quickMenu != null) return true
        if (keyCode == KeyEvent.KEYCODE_BACK || page.isClosed) return super.onKeyDown(keyCode, event)
        page.key(event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && quickMenu != null) {
            dismissQuickMenu()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || page.isClosed) return super.onKeyUp(keyCode, event)
        page.key(event)
        return true
    }

    /* The keyboard. */

    fun onFocusedField(editable: Boolean, type: String) {
        fieldType = type
        if (!editable) {
            onKeyboardRequested(false, "NONE")
            return
        }
        // Another kind of field while the keyboard is up (text, then a password): the keyboard follows.
        if (this.editable && connectedFieldType != null && connectedFieldType != type) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            if (imm.isActive(this)) imm.restartInput(this)
        }
    }

    fun onKeyboardRequested(show: Boolean, modeName: String) {
        val changed = editable != show || inputModeName != modeName || connectedFieldType != fieldType
        editable = show
        inputModeName = modeName
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (!show) {
            hideKeyboard()
            return
        }
        // Only a field the user just tapped on a page they can see opens the keyboard.
        if (!isSeen() || SystemClock.uptimeMillis() - lastRealTouchAt > KEYBOARD_AFTER_TOUCH_MS) return
        if (!hasFocus()) requestFocus()
        if (changed) imm.restartInput(this)
        imm.showSoftInput(this, 0)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (imm.isActive(this)) imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            hideKeyboard()
            dismissQuickMenu()
        }
    }

    override fun onCheckIsTextEditor(): Boolean = editable

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!editable || page.isClosed) return null
        connectedFieldType = fieldType
        outAttrs.inputType =
            when {
                fieldType == "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                fieldType == "email" || inputModeName == "EMAIL" ->
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                fieldType == "url" || inputModeName == "URL" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                fieldType == "tel" || inputModeName == "TEL" -> InputType.TYPE_CLASS_PHONE
                fieldType == "number" || inputModeName == "NUMERIC" -> InputType.TYPE_CLASS_NUMBER
                inputModeName == "DECIMAL" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                fieldType == "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                if (inputModeName == "SEARCH" || fieldType == "search") EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_GO
        if (fieldType == "textarea") outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return EngineInputConnection()
    }

    /**
     * The keyboard's text goes straight to the focused field; the page keeps the text itself. The
     * engine does not report the field's text or cursor, so the keyboard sees none around the
     * cursor (as with a plain key-by-key editor) and works without word suggestions from it.
     */
    private inner class EngineInputConnection : BaseInputConnection(this@CefriumPageView, false) {
        private var composing = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text?.toString().orEmpty()
            // As Android counts it: past 0 from the text's end, else from its start.
            val cursor =
                (if (newCursorPosition > 0) composing.length + newCursorPosition - 1 else newCursorPosition)
                    .coerceIn(0, composing.length)
            page.imeComposition(composing, cursor, cursor)
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = ""
            page.imeCommit(text?.toString().orEmpty(), newCursorPosition)
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            page.selectInField(start, end)
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                composing = ""
                page.imeFinish()
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceIn(0, 64)) { tapKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceIn(0, 64)) { tapKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            page.key(event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            tapKey(KeyEvent.KEYCODE_ENTER)
            return true
        }

        private fun tapKey(code: Int) {
            val now = SystemClock.uptimeMillis()
            page.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            page.key(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

    /* Cut, copy and paste after a long press. */

    fun showQuickMenu(x: Int, y: Int, height: Int, menu: CefriumBrowser.QuickMenuCallback) {
        dismissQuickMenu()
        if (!isSeen() || windowToken == null) {
            menu.cancel()
            return
        }
        var answered = false
        val answer: (() -> Unit) -> Unit = { action ->
            if (!answered) {
                answered = true
                action()
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(240, 48, 48, 48))
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad / 2, 0, pad / 2, 0)
        }
        fun item(label: Int, enabled: Boolean, action: () -> Unit) {
            if (!enabled) return
            row.addView(TextView(context).apply {
                setText(label)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
                setOnClickListener {
                    answer(action)
                    dismissQuickMenu()
                }
            })
        }
        item(android.R.string.cut, menu.canCut()) { menu.cut() }
        item(android.R.string.copy, menu.canCopy()) { menu.copy() }
        item(android.R.string.paste, menu.canPaste()) { menu.paste() }
        if (row.childCount == 0) {
            menu.cancel()
            return
        }
        val popup = PopupWindow(row, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isOutsideTouchable = true
        popup.setOnDismissListener {
            answer { menu.cancel() }
            if (quickMenu === popup) quickMenu = null
            if (BrowserPageMenus.open === popup) BrowserPageMenus.open = null
        }
        row.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val sx = scaleX
        val sy = scaleY
        val left = location[0] + (x * sx).toInt() - row.measuredWidth / 2
        val above = location[1] + (y * sy).toInt() - row.measuredHeight - (8 * resources.displayMetrics.density).toInt()
        val top = if (above > 0) above else location[1] + ((y + height) * sy).toInt()
        quickMenu = popup
        BrowserPageMenus.open = popup
        runCatching { popup.showAtLocation(this, Gravity.NO_GRAVITY, left.coerceAtLeast(0), top.coerceAtLeast(0)) }
            .onFailure {
                quickMenu = null
                if (BrowserPageMenus.open === popup) BrowserPageMenus.open = null
                answer { menu.cancel() }
            }
    }

    private fun dismissQuickMenu() {
        quickMenu?.let { popup ->
            quickMenu = null
            runCatching { popup.dismiss() }
        }
    }

    private companion object {
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOVE = 2
        const val TOUCH_CANCEL = 3
        const val BACKGROUND_FRAME_MS = 250L
        const val KEYBOARD_AFTER_TOUCH_MS = 2_000L
    }
}
