package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptPageMenuCommand
import kotlinx.serialization.Serializable

internal enum class BrowserBackTarget {
    NONE,
    PAGE_MENU,
    TEXT_SELECTION,
    DIALOG,
    EXTERNAL_OPEN,
    SHEET,
    URL_EDIT,
}

/**
 * Drives predictive back for the browser's own overlays (sheets, page dialogs, URL editing) from
 * [WebSessionBrowserActivity]'s single back callback. Compose only reads [target], [progress] and
 * [exit] inside graphics layers, so swiping never recomposes the lists inside a sheet.
 */
@Stable
internal class BrowserBackMotion {
    var target by mutableStateOf(BrowserBackTarget.NONE)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var exit by mutableFloatStateOf(0f)
        private set
    /** True while the committed exit animation is playing; only flips at start/end, never mid-drag. */
    var committing by mutableStateOf(false)
        private set

    private var targetKey: Any? = null
    private var startProgress = 0f
    private var animator: ValueAnimator? = null
    private var cancelling = false
    private var pendingCommit: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resetAfterCommit = Runnable {
        if (pendingCommit == null && committing) clearState()
    }
    private val postFrameReset = Choreographer.FrameCallback {
        if (pendingCommit == null && committing) mainHandler.post(resetAfterCommit)
    }

    val isCommitting: Boolean get() = pendingCommit != null || committing

    fun isExiting(layer: BrowserBackTarget): Boolean = committing && target == layer

    fun activeTargetKey(): Any? = targetKey

    fun start(active: BrowserBackTarget, key: Any?) {
        if (isCommitting) return
        if (active == BrowserBackTarget.NONE ||
            active == BrowserBackTarget.PAGE_MENU ||
            active == BrowserBackTarget.TEXT_SELECTION
        ) return
        cancelAnimator()
        cancelling = false
        mainHandler.removeCallbacks(resetAfterCommit)
        Choreographer.getInstance().removeFrameCallback(postFrameReset)
        if (target == active && targetKey == key) {
            // Continue smoothly if a second swipe begins before the previous spring-back ends.
            startProgress = progress
        } else {
            target = active
            targetKey = key
            progress = 0f
            exit = 0f
            startProgress = 0f
        }
    }

    fun move(rawProgress: Float, active: BrowserBackTarget, key: Any?) {
        if (isCommitting || cancelling || target == BrowserBackTarget.NONE) return
        if (active != target || key != targetKey) {
            cancel()
            return
        }
        val p = rawProgress.coerceIn(0f, 1f)
        progress = startProgress + (1f - startProgress) * p
    }

    fun cancel() {
        if (isCommitting || cancelling || target == BrowserBackTarget.NONE) return
        if (progress <= 0f) {
            reset()
            return
        }
        cancelAnimator()
        cancelling = true
        val from = progress
        val anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    cancelling = false
                    if (!isCommitting) clearState()
                }
            })
        }
        animator = anim
        anim.start()
    }

    fun commit(active: BrowserBackTarget, key: Any?, dismiss: () -> Unit) {
        if (active == BrowserBackTarget.NONE ||
            active == BrowserBackTarget.PAGE_MENU ||
            active == BrowserBackTarget.TEXT_SELECTION
        ) {
            dismiss()
            return
        }
        if (isCommitting) {
            // Same layer already animating out: ignore duplicate presses so the window below stays put.
            if (target == active && targetKey == key) return
            finishPendingOrReset()
        }
        cancelAnimator()
        cancelling = false
        mainHandler.removeCallbacks(resetAfterCommit)
        Choreographer.getInstance().removeFrameCallback(postFrameReset)
        if (target != active || targetKey != key) {
            target = active
            targetKey = key
            progress = 0f
            startProgress = 0f
        }
        exit = 0f
        pendingCommit = dismiss
        committing = true
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { exit = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    val action = pendingCommit
                    pendingCommit = null
                    // Keep exit at 1f and committing=true until the next composition removes the layer
                    // (via syncActive), preventing both a 1-frame flash and late taps; fall back via post.
                    exit = 1f
                    action?.invoke()
                    Choreographer.getInstance().postFrameCallback(postFrameReset)
                }
            })
        }
        animator = anim
        anim.start()
    }

    /**
     * Keeps motion aligned when the topmost overlay changes. If a committed close was interrupted by
     * a higher overlay appearing while the closing layer is still open ([previousStillOpen]), finish
     * the committed close immediately so it is not left behind; otherwise drop stale motion.
     */
    fun syncActive(active: BrowserBackTarget, key: Any?, previousStillOpen: Boolean) {
        if (target != BrowserBackTarget.NONE && (active != target || key != targetKey)) {
            if (pendingCommit != null && previousStillOpen) completeCommitAndHoldHidden() else reset()
        }
    }

    /** Finishes an in-flight commit immediately while holding exit=1f until Compose removes the layer. */
    private fun completeCommitAndHoldHidden() {
        cancelAnimator()
        val action = pendingCommit
        pendingCommit = null
        exit = 1f
        action?.invoke()
        Choreographer.getInstance().postFrameCallback(postFrameReset)
    }

    /** Runs any in-flight dismiss immediately before the window leaves or the host tears down. */
    fun finishPendingOrReset() {
        cancelAnimator()
        val action = pendingCommit
        pendingCommit = null
        committing = false
        action?.invoke()
        clearState()
    }

    fun reset() {
        cancelAnimator()
        pendingCommit = null
        committing = false
        clearState()
    }

    private fun cancelAnimator() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun clearState() {
        mainHandler.removeCallbacks(resetAfterCommit)
        Choreographer.getInstance().removeFrameCallback(postFrameReset)
        cancelling = false
        committing = false
        target = BrowserBackTarget.NONE
        targetKey = null
        progress = 0f
        exit = 0f
        startProgress = 0f
    }
}

internal enum class WebSessionBrowserSheetRoute {
    NONE,
    TABS,
    MENU,
    DOWNLOADS,
    HISTORY,
    BOOKMARKS,
    USERSCRIPTS
}

@Immutable
internal data class WebSessionBrowserTab(
    val sessionId: String,
    val title: String,
    val url: String,
    val isActive: Boolean,
    val hasSslError: Boolean,
    /** The Orbit whose own logins this tab uses; null for the main app's tabs. */
    val ownerLabel: String? = null
)

@Immutable
internal data class WebSessionSessionHistoryItem(
    val index: Int,
    val title: String,
    val url: String,
    val isCurrent: Boolean
)

@Immutable
internal data class WebSessionPendingDialogState(
    val type: String,
    val message: String,
    val defaultValue: String? = null,
    val url: String? = null,
    val id: Long = 0L,
)

@Immutable
internal data class WebSessionBrowserState(
    val activeSessionId: String? = null,
    val pageTitle: String = "",
    val currentUrl: String = "about:blank",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val hasSslError: Boolean = false,
    val isDesktopMode: Boolean = true,
    val activeDownloadCount: Int = 0,
    val hasFailedDownloads: Boolean = false,
    val failedDownloadCount: Int = 0,
    val latestCompletedDownloadName: String? = null,
    val overallDownloadProgress: Float? = null,
    val pendingDialog: WebSessionPendingDialogState? = null,
    val tabs: List<WebSessionBrowserTab> = emptyList(),
    val sessionHistory: List<WebSessionSessionHistoryItem> = emptyList(),
    val userscriptMenuCommands: List<UserscriptPageMenuCommand> = emptyList()
)

internal enum class BrowserDownloadFilter {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

@Immutable
internal data class BrowserDownloadItem(
    val id: String,
    val fileName: String,
    val status: String,
    val type: String,
    val progress: Float?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val destinationPath: String,
    val errorMessage: String?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canDelete: Boolean,
    val canDeleteFile: Boolean,
    val canOpenFile: Boolean,
    val canOpenLocation: Boolean
)

@Immutable
internal data class BrowserDownloadUiState(
    val tasks: List<BrowserDownloadItem> = emptyList(),
    val storageUnavailable: Boolean = false,
    val selectedFilter: BrowserDownloadFilter = BrowserDownloadFilter.IN_PROGRESS
)

@Immutable
internal data class ExternalOpenPromptState(
    val requestId: String,
    val title: String,
    val target: String
)

/**
 * Orbit's watch screen over a tab an Orbit is working in. Until [controlling] the user only looks;
 * [waiting] is the moment between asking for control and the AI's current action ending.
 */
@Immutable
internal data class BrowserWatchUi(
    val host: String,
    val orbitName: String,
    val controlling: Boolean,
    val waiting: Boolean,
    /** Orbit stopped working here; the page stays for the user to look at or take over. */
    val finished: Boolean,
    val onClose: () -> Unit,
    val onTakeOver: () -> Unit,
    val onHandBack: () -> Unit,
    val onStop: () -> Unit,
)

@Immutable
internal data class WebSessionBrowserHostState(
    val browserState: WebSessionBrowserState = WebSessionBrowserState(),
    val sheetRoute: WebSessionBrowserSheetRoute = WebSessionBrowserSheetRoute.NONE,
    val isEditingUrl: Boolean = false,
    val urlDraft: String = WebSessionBrowserState().currentUrl,
    val externalOpenPrompt: ExternalOpenPromptState? = null,
    val downloadUiState: BrowserDownloadUiState = BrowserDownloadUiState(),
    val viewportWidthPx: Int? = null,
    val viewportHeightPx: Int? = null,
    val chromeHeightPx: Int = 0,
    val browserAreaWidthPx: Int = 0,
    val browserAreaHeightPx: Int = 0
)

@Serializable
internal data class WebSessionBookmark(
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
internal data class WebSessionHistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)
