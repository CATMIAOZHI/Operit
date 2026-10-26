package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import kotlin.math.min
import kotlin.math.roundToInt

/** How far the user can zoom the whole page picture, beyond fitting it on screen. */
private const val MAX_USER_ZOOM = 4f

internal class WebSessionWebViewHost {
    private var container: FrameLayout? = null
    private var activeWebView: WebView? = null
    private var viewportWidthCssPx: Int? = null
    private var viewportHeightCssPx: Int? = null
    /**
     * The user's own zoom and position of the page picture. Only the view transform changes, never
     * the page's size, so the site sees the same viewport and the AI's screenshots and taps (made in
     * the WebView's own coordinates) stay the same.
     */
    private var userZoom = 1f
    private var userOffsetX = 0f
    private var userOffsetY = 0f
    private val containerLayoutChangeListener =
        View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (
                right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop
            ) {
                // The user's zoom survives the keyboard opening or a turn; only its position is re-fitted.
                applyWebViewLayout(view as FrameLayout)
            }
        }

    fun attachContainer(target: FrameLayout) {
        if (container !== target) {
            container?.removeOnLayoutChangeListener(containerLayoutChangeListener)
            target.addOnLayoutChangeListener(containerLayoutChangeListener)
        }
        container = target
        reattach()
    }

    fun detachContainer(target: FrameLayout) {
        if (container === target) {
            target.removeOnLayoutChangeListener(containerLayoutChangeListener)
            container = null
        }
    }

    fun setActiveWebView(webView: WebView?, viewportWidth: Int?, viewportHeight: Int?) {
        if (
            webView !== activeWebView ||
                viewportWidth != viewportWidthCssPx ||
                viewportHeight != viewportHeightCssPx
        ) {
            resetUserZoom()
        }
        activeWebView = webView
        viewportWidthCssPx = viewportWidth
        viewportHeightCssPx = viewportHeight
        reattach()
    }

    fun setViewportSize(width: Int?, height: Int?) {
        if (width != viewportWidthCssPx || height != viewportHeightCssPx) {
            resetUserZoom()
        }
        viewportWidthCssPx = width
        viewportHeightCssPx = height
        reattach()
    }

    fun currentWebView(): WebView? = activeWebView

    /**
     * Zooms the page picture by [zoomChange] around ([centroidX], [centroidY]) and moves it by
     * ([panX], [panY]), all in container pixels. It never shrinks below fitting the screen.
     */
    fun transformUserView(centroidX: Float, centroidY: Float, zoomChange: Float, panX: Float, panY: Float) {
        val target = container ?: return
        val webView = activeWebView ?: return
        if (webView.parent !== target || viewportWidthCssPx == null || viewportHeightCssPx == null) return
        if (!zoomChange.isFinite() || zoomChange <= 0f || !panX.isFinite() || !panY.isFinite()) return
        val fitScale = fitScale(target) ?: return
        val oldScale = fitScale * userZoom
        val newZoom = (userZoom * zoomChange).coerceIn(1f, MAX_USER_ZOOM)
        val ratio = fitScale * newZoom / oldScale
        userOffsetX = centroidX - (centroidX - webView.translationX) * ratio + panX
        userOffsetY = centroidY - (centroidY - webView.translationY) * ratio + panY
        userZoom = newZoom
        applyWebViewLayout(target)
    }

    private fun resetUserZoom() {
        userZoom = 1f
        userOffsetX = 0f
        userOffsetY = 0f
    }

    fun clear() {
        container?.removeAllViews()
        container?.removeOnLayoutChangeListener(containerLayoutChangeListener)
        container = null
        activeWebView = null
        viewportWidthCssPx = null
        viewportHeightCssPx = null
        resetUserZoom()
    }

    private fun reattach() {
        val target = container ?: return
        val webView = activeWebView

        if (webView == null) {
            target.removeAllViews()
            return
        }

        val parent = webView.parent
        if (parent is ViewGroup && parent !== target) {
            parent.removeView(webView)
        }

        if (target.childCount != 1 || target.getChildAt(0) !== webView) {
            target.removeAllViews()
            target.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        applyWebViewLayout(target)
    }

    private fun applyWebViewLayout(target: FrameLayout) {
        val webView = activeWebView ?: return
        if (webView.parent !== target) {
            return
        }
        // The page draws through its own renderer; clip it at the container, or a zoomed picture
        // spills over the title and address bars around it.
        if (target.width > 0 && target.height > 0) {
            val clip = target.clipBounds
            if (clip == null || clip.left != 0 || clip.top != 0 || clip.right != target.width || clip.bottom != target.height) {
                target.clipBounds = android.graphics.Rect(0, 0, target.width, target.height)
            }
        }

        val requestedWidth = viewportWidthCssPx
        val requestedHeight = viewportHeightCssPx
        if (requestedWidth == null || requestedHeight == null) {
            webView.pivotX = 0f
            webView.pivotY = 0f
            webView.scaleX = 1f
            webView.scaleY = 1f
            webView.translationX = 0f
            webView.translationY = 0f
            updateLayoutParams(
                webView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            return
        }

        val (layoutWidth, layoutHeight) = layoutSize(webView, requestedWidth, requestedHeight)
        updateLayoutParams(webView, layoutWidth, layoutHeight)

        val availableWidth = target.width
        val availableHeight = target.height
        if (availableWidth <= 0 || availableHeight <= 0) {
            return
        }

        val previewScale =
            min(
                availableWidth.toFloat() / layoutWidth.toFloat(),
                availableHeight.toFloat() / layoutHeight.toFloat()
            ) * userZoom
        val shownWidth = layoutWidth * previewScale
        val shownHeight = layoutHeight * previewScale
        // Smaller than the screen it stays centered; larger, it may move but never leaves a gap.
        userOffsetX =
            if (shownWidth <= availableWidth) (availableWidth - shownWidth) / 2f
            else userOffsetX.coerceIn(availableWidth - shownWidth, 0f)
        userOffsetY =
            if (shownHeight <= availableHeight) (availableHeight - shownHeight) / 2f
            else userOffsetY.coerceIn(availableHeight - shownHeight, 0f)
        webView.pivotX = 0f
        webView.pivotY = 0f
        webView.scaleX = previewScale
        webView.scaleY = previewScale
        webView.translationX = userOffsetX
        webView.translationY = userOffsetY
    }

    /**
     * WebView lays out CSS pixels as density-independent pixels. Giving it the requested CSS
     * viewport in physical pixels makes window.innerWidth/innerHeight match the tool arguments;
     * the view transform only fits that logical viewport on the phone.
     */
    private fun layoutSize(webView: WebView, cssWidth: Int, cssHeight: Int): Pair<Int, Int> {
        val density =
            webView.resources.displayMetrics.density
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f
        return (cssWidth * density).roundToInt().coerceAtLeast(1) to
            (cssHeight * density).roundToInt().coerceAtLeast(1)
    }

    /** The scale that fits the whole page picture in [target], or null before it has a size. */
    private fun fitScale(target: FrameLayout): Float? {
        val webView = activeWebView ?: return null
        val cssWidth = viewportWidthCssPx ?: return null
        val cssHeight = viewportHeightCssPx ?: return null
        if (target.width <= 0 || target.height <= 0) return null
        val (layoutWidth, layoutHeight) = layoutSize(webView, cssWidth, cssHeight)
        return min(
            target.width.toFloat() / layoutWidth.toFloat(),
            target.height.toFloat() / layoutHeight.toFloat()
        )
    }

    private fun updateLayoutParams(webView: WebView, width: Int, height: Int) {
        val current = webView.layoutParams as? FrameLayout.LayoutParams
        if (current?.width == width && current.height == height && current.gravity == Gravity.TOP) {
            return
        }
        webView.layoutParams =
            FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP
            }
    }
}
