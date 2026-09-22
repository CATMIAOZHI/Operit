package com.ai.assistance.operit.pet

import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Temporary visibility only: never changes the user's pet preferences or starts a service. */
object PetAutomationVisibility {
    private var users = 0
    private val suppressed = MutableStateFlow(false)
    val hidden = suppressed.asStateFlow()

    suspend fun <T> whileHidden(block: suspend () -> T): T {
        var acquired = false
        try {
            withContext(Dispatchers.Main.immediate) {
                users++
                acquired = true
                suppressed.value = true
                PetCompanionService.refreshAutomationVisibility()
            }
            awaitHiddenFrames()
            return block()
        } finally {
            if (acquired) withContext(NonCancellable + Dispatchers.Main.immediate) {
                users = (users - 1).coerceAtLeast(0)
                suppressed.value = users > 0
                PetCompanionService.refreshAutomationVisibility()
            }
        }
    }

    /** Let Compose and WindowManager commit the hidden state before reading screen pixels. */
    suspend fun awaitHiddenFrames() = withContext(Dispatchers.Main.immediate) {
        repeat(2) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }
    }
}
