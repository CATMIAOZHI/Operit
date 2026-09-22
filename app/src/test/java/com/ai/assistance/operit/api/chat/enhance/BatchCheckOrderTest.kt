package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.ui.permissions.PermissionLevel
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dispatch shape of one batch's permission checks.
 *
 * The reviewer reads a batch in the order it is handed the actions, so a batch it serves has to be
 * walked one action at a time; a level that decides each action on its own keeps the parallel walk.
 */
class BatchCheckOrderTest {
    @Test
    fun onlyABatchTheReviewerServesNeedsItsOrderKept() {
        assertTrue(batchNeedsReviewOrder(listOf(PermissionLevel.ASK, PermissionLevel.AUTO_REVIEW)))
        assertTrue(batchNeedsReviewOrder(listOf(PermissionLevel.AUTO_REVIEW)))
        assertFalse(batchNeedsReviewOrder(listOf(PermissionLevel.ALLOW, PermissionLevel.ASK)))
        assertFalse(batchNeedsReviewOrder(listOf(PermissionLevel.FORBID)))
        assertFalse(batchNeedsReviewOrder(emptyList()))
    }

    @Test
    fun orderedBatchRunsOneCheckAtATimeInTheOrderItWasGiven() =
        runBlocking {
            val trace = Collections.synchronizedList(mutableListOf<String>())
            val results =
                mapBatchInReviewOrder(listOf(0, 1, 2), ordered = true) { value ->
                    trace += "enter$value"
                    delay(5)
                    trace += "exit$value"
                    value * 10
                }

            assertEquals(listOf(0, 10, 20), results)
            assertEquals(
                listOf("enter0", "exit0", "enter1", "exit1", "enter2", "exit2"),
                trace.toList(),
            )
        }

    @Test
    fun unorderedBatchKeepsTheGivenOrderAndLetsChecksOverlap() =
        runBlocking {
            val running = AtomicInteger()
            val overlapped = CompletableDeferred<Unit>()
            val results =
                mapBatchInReviewOrder(listOf(0, 1), ordered = false) { value ->
                    if (running.incrementAndGet() > 1) overlapped.complete(Unit)
                    // Waits for the other check to start. A batch that is not ordered has to be
                    // allowed to have two of its checks in flight at once; the timeout only keeps a
                    // regression from hanging the suite.
                    withTimeoutOrNull(5_000) { overlapped.await() }
                    running.decrementAndGet()
                    value * 10
                }

            assertTrue("the two checks never overlapped", overlapped.isCompleted)
            assertEquals(listOf(0, 10), results)
        }
}
