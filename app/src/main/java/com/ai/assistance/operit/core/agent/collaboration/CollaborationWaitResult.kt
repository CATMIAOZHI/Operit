package com.ai.assistance.operit.core.agent.collaboration

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class CollaborationWaitOutcome { MAILBOX, STEERED, TIMED_OUT }

/** Storage arrival is not readiness: the next model boundary drains the execution inbox. */
internal suspend fun awaitCollaborationInput(
    timeoutMs: Long,
    deliverToCurrentTurn: suspend () -> Unit,
    pendingInput: () -> CollaborationWaitOutcome?,
): CollaborationWaitOutcome = withTimeoutOrNull(timeoutMs) {
    while (true) {
        pendingInput()?.let { return@withTimeoutOrNull it }
        deliverToCurrentTurn()
        pendingInput()?.let { return@withTimeoutOrNull it }
        delay(100)
    }
    @Suppress("UNREACHABLE_CODE")
    CollaborationWaitOutcome.TIMED_OUT
} ?: CollaborationWaitOutcome.TIMED_OUT

data class CollaborationWaitResult(val outcome: CollaborationWaitOutcome, val requestedTimeoutMs: Long, val minimumTimeoutMs: Long = 10_000) {
    val timedOut: Boolean get() = outcome == CollaborationWaitOutcome.TIMED_OUT
    val message: String get() {
        val result = when (outcome) {
            CollaborationWaitOutcome.MAILBOX -> "Wait completed."
            CollaborationWaitOutcome.STEERED -> "Wait interrupted by new input."
            CollaborationWaitOutcome.TIMED_OUT -> "Wait timed out."
        }
        return if (requestedTimeoutMs < minimumTimeoutMs) {
            "$result\n\nRequested timeout of ${requestedTimeoutMs}ms was clamped to the minimum of ${minimumTimeoutMs}ms."
        } else result
    }
}
