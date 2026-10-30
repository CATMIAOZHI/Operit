package com.ai.assistance.operit.ui.features.chat.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.compositionLocalOf
import kotlin.math.roundToInt

/** Whether the chat list may take a system long screenshot now (false while something covers it). */
val LocalLongScreenshotEnabled = compositionLocalOf { true }

/**
 * Lets Xiaomi's long screenshot scroll the chat. HyperOS skips Compose views: it looks for a real
 * Android view that can scroll down, sends it a pretend swipe (DOWN, MOVEs, UP straight to
 * [dispatchTouchEvent], with no input source) and reads how far it went from the view's scrollY.
 * This empty view sits behind the chat list, answers for the list, hands each swipe step to it
 * through [onStep] and records what the list really moved with [moved]. It draws nothing, so its
 * own scrollY never moves anything on screen. Real touches land on the list above it.
 */
@SuppressLint("ViewConstructor")
internal class LongScreenshotBridgeView(context: Context) : View(context) {
    // Read on the system's binder thread, so plain flags the main thread keeps current.
    @Volatile var canForward = false
    @Volatile var canBackward = false
    /** A swipe step in pixels (positive moves the content up, to later messages); main thread. */
    var onStep: (Float) -> Unit = {}
    /** The pretend swipe started (true) or ended (false); main thread. */
    var onActive: (Boolean) -> Unit = {}
    private var lastY = 0f
    private var total = 0f
    private var active = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun canScrollVertically(direction: Int) = if (direction > 0) canForward else canBackward

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // A finger has a source; only the system's pretend swipe has none.
        if (event.source != 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                if (!active) { active = true; onActive(true) }
            }
            MotionEvent.ACTION_MOVE -> {
                val step = lastY - event.y
                lastY = event.y
                if (step != 0f) onStep(step)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) { active = false; onActive(false) }
        }
        return true
    }

    /** The list moved [px] for a step; the system measures this through scrollY. */
    fun moved(px: Float) {
        // Never reset: the system compares scrollY before and after each step.
        total += px
        super.scrollTo(0, total.roundToInt())
    }

    override fun onDetachedFromWindow() {
        if (active) { active = false; onActive(false) }
        super.onDetachedFromWindow()
    }
}
