package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messages drawn into a picture rather than onto the screen. Blocks that scroll inside the chat
 * (long code, copy blocks) show in full, and work still loading is counted, so the picture is
 * taken once everything is in place.
 */
class MarkdownSnapshot {
    private val work = AtomicInteger()
    private val images = IdentityHashMap<Any, Boolean>()
    @Volatile private var changedAt = System.currentTimeMillis()

    fun begin() {
        work.incrementAndGet()
        changedAt = System.currentTimeMillis()
    }

    fun end() {
        work.decrementAndGet()
        changedAt = System.currentTimeMillis()
    }

    fun imageStarted(request: Any) {
        synchronized(images) { images[request] = true }
        changedAt = System.currentTimeMillis()
    }

    fun imageEnded(request: Any) {
        synchronized(images) { images.remove(request) }
        changedAt = System.currentTimeMillis()
    }

    /** Text still being prepared or a picture still loading. */
    val busy: Boolean
        get() = work.get() > 0 || synchronized(images) { images.isNotEmpty() }

    /** Milliseconds since text or a picture last started or finished. */
    val quietFor: Long
        get() = System.currentTimeMillis() - changedAt
}

/**
 * Tallest code block in a picture, in pixels: below the 262142 pixels one Compose layout can
 * measure on any screen. Long messages are cut into parts well before a block gets this tall.
 */
internal const val SNAPSHOT_CODE_MAX_HEIGHT_PX = 200_000

/** Set while drawing messages into a picture; null on screen. */
val LocalMarkdownSnapshot = staticCompositionLocalOf<MarkdownSnapshot?> { null }

/** Counts [working] as unfinished while drawing a picture; does nothing on screen. */
@Composable
internal fun MarkdownSnapshotWork(working: Boolean) {
    val snapshot = LocalMarkdownSnapshot.current ?: return
    DisposableEffect(snapshot, working) {
        if (working) snapshot.begin()
        onDispose { if (working) snapshot.end() }
    }
}
