package com.ai.assistance.operit.ui.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Orbit's launch mark over the app while it loads: the same picture as the system launch screen,
 * whose ring turns until the first page is ready, then grows past the screen while the page shows
 * through it. The ring turns on the render thread, so loading never stutters it; the opening only
 * moves the whole layer, so each of its frames costs almost nothing.
 */
internal class LaunchOverlay private constructor(
    private val activity: Activity,
    private val decor: ViewGroup,
    private val layer: FrameLayout,
    private val ring: ImageView,
    private val inner: List<View>,
    private val diagonal: Float,
    private val holeRadius: Float,
    private val content: View?,
    private val contentAccessibility: Int,
) {
    private var opening: ValueAnimator? = null

    /**
     * Starts the ring turning; first called once the system's still copy of the mark is gone. That
     * puts back the system bar colors from before the app drew, so they are cleared again.
     */
    fun spin() {
        clearBars()
        decor.post { clearBars() }
        (ring.drawable as? Animatable)?.takeIf { !it.isRunning }?.start()
    }

    /** The system bars stay clear while the mark covers the app (see OperitTheme). */
    private fun clearBars() {
        if (opening != null || layer.parent == null) return
        clearSystemBars(activity)
    }

    /** Grows the mark past the screen, then takes it away. */
    fun open(onDone: () -> Unit) {
        if (opening != null) return
        spin()
        val maxScale = diagonal / 2f / holeRadius * 1.1f
        opening = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val dip = easeOut((t / DIP_PART).coerceAtMost(1f))
                val grow = if (t > DIP_PART) easeIn((t - DIP_PART) / (1f - DIP_PART)) else 0f
                val scale = if (t <= DIP_PART) 1f - (1f - DIP_SCALE) * dip
                    else DIP_SCALE * (maxScale / DIP_SCALE).pow(grow)
                layer.scaleX = scale
                layer.scaleY = scale
                layer.rotation = DIP_TURN * dip + (GROW_TURN - DIP_TURN) * grow
                // The middle of the mark goes first, so the page shows through the ring.
                val innerAlpha = if (t <= DIP_PART) 1f else (1f - (t - DIP_PART) / 0.14f).coerceIn(0f, 1f)
                inner.forEach { it.alpha = innerAlpha }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    remove()
                    onDone()
                }
            })
            start()
        }
    }

    /** Takes the mark away at once. */
    fun remove() {
        (ring.drawable as? Animatable)?.stop()
        decor.removeView(layer)
        content?.importantForAccessibility = contentAccessibility
    }

    companion object {
        private const val OPEN_MS = 820L
        private const val DIP_PART = 0.22f
        private const val DIP_SCALE = 0.86f
        private const val DIP_TURN = -12f
        private const val GROW_TURN = 120f

        /** Rainy's pink, as in res/drawable/orbit_launch_logo.xml. */
        private const val MARK_COLOR = 0xFFFF6B8E.toInt()

        /** The mark in the 288dp launch icon: ring radius 40dp, its line 7.2dp wide. */
        private const val RING_RADIUS_DP = 40f
        private const val LINE_DP = 7.2f
        private const val CORE_RADIUS_DP = 12f

        @SuppressLint("ClickableViewAccessibility")
        fun attach(activity: Activity): LaunchOverlay {
            val decor = activity.window.decorView as ViewGroup
            val density = activity.resources.displayMetrics.density
            val (width, height) = windowSize(activity)
            val diagonal = hypot(width.toFloat(), height.toFloat())
            val holeRadius = (RING_RADIUS_DP - LINE_DP / 2f) * density
            val background = ContextCompat.getColor(activity, R.color.orbit_launch_background)
            // Larger than the screen, so it still covers it while dipping and turning.
            val side = (diagonal * 1.2f).toInt()
            val layer = FrameLayout(activity).apply {
                clipChildren = false
                // Nothing under the mark reacts to touches until it is gone.
                setOnTouchListener { _, _ -> true }
                // Screen readers hear that the app is loading, not the page still covered.
                contentDescription = activity.getString(R.string.loading)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            val content = activity.findViewById<View>(android.R.id.content)
            val contentAccessibility = content?.importantForAccessibility ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            fun centered(size: Int) = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            layer.addView(HoleView(activity, background, holeRadius), centered(side))
            val cover = CircleView(activity, background, holeRadius + density, stroke = 0f)
            val core = CircleView(activity, MARK_COLOR, CORE_RADIUS_DP * density, stroke = LINE_DP * density)
            val iconSize = (288 * density).toInt()
            layer.addView(cover, centered(iconSize))
            layer.addView(core, centered(iconSize))
            val ring = ImageView(activity).apply {
                setImageResource(R.drawable.orbit_launch_ring_spin)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            layer.addView(ring, centered(iconSize))
            decor.addView(layer, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            clearSystemBars(activity)
            return LaunchOverlay(activity, decor, layer, ring, listOf(cover, core), diagonal, holeRadius,
                content, contentAccessibility)
        }

        /** Clear system bars over the mark, so it fills the whole screen like the launch screen. */
        private fun clearSystemBars(activity: Activity) {
            val window = activity.window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val night = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }

        private fun windowSize(activity: Activity): Pair<Int, Int> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.maximumWindowMetrics.bounds
                return bounds.width() to bounds.height()
            }
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        }

        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x)

        private fun easeIn(x: Float) = x.pow(1.3f)
    }

    /** The launch background with a round hole the size of the inside of the ring. */
    private class HoleView(context: Context, color: Int, private val hole: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            // One wide circle line from the hole to past the corners: a plain circle, so drawing it
            // larger costs nothing.
            val reach = hypot(width / 2f, height / 2f) + 2f
            val band = max(0f, reach - hole)
            paint.strokeWidth = band
            canvas.drawCircle(width / 2f, height / 2f, hole + band / 2f, paint)
        }
    }

    /** A circle in the middle: filled when [stroke] is 0, otherwise a line of that width. */
    private class CircleView(context: Context, color: Int, private val radius: Float, stroke: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (stroke > 0f) {
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        }
    }
}
