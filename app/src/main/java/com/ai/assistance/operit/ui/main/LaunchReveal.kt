package com.ai.assistance.operit.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The launch, like X's: Orbit's mark turns over the app (see [LaunchOverlay]) until the first page
 * says it is ready, then grows past the screen and the page shows through its ring.
 */
object LaunchReveal {
    /**
     * The longest the turning mark waits for the page, only so it never stays forever; a slow
     * phone keeps the mark turning rather than open on a page still filling in.
     */
    const val MAX_WAIT_MS = 10_000L
    private val readyFlow = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = readyFlow
    private val coveringFlow = MutableStateFlow(false)

    /** The launch mark still covers the app, so a page that is ready shows at once under it. */
    val covering: StateFlow<Boolean> = coveringFlow

    /** The page under the mark has drawn what it shows first. */
    fun markReady() {
        readyFlow.value = true
    }

    /** A new launch waits for its own page again. */
    fun reset() {
        readyFlow.value = false
    }

    fun setCovering(value: Boolean) {
        coveringFlow.value = value
    }
}

/** For pages shown first that have nothing to load: ready once they have drawn a frame. */
@Composable
fun MarkLaunchReadyAfterFrame() {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        LaunchReveal.markReady()
    }
}
