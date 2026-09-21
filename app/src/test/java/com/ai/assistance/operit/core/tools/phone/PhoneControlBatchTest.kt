package com.ai.assistance.operit.core.tools.phone

import com.ai.assistance.operit.core.tools.SimplifiedUINode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class PhoneControlBatchTest {
    private fun button(text: String, bounds: String = "[0,0][100,100]") =
        SimplifiedUINode("Button", text, null, "app:id/key", bounds, true, emptyList())

    @Test fun calculatorSequenceRunsInOneBatch() = runBlocking {
        val actions = PhoneControlBatch.actions(mapOf("actions" to
            """[{"action":"tap","element_index":1},{"action":"tap","element_index":2},
                {"action":"tap","element_index":3},{"action":"tap","element_index":4},
                {"action":"tap","element_index":5},{"action":"tap","element_index":6}]"""))
        val received = mutableListOf<String>()
        val result = runPhoneBatch(actions.size, before = {}, perform = {
            received.add(actions[it].getValue("element_index")); null
        })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), received)
        assertEquals(PhoneBatchProgress(6, 6, 6), result)
    }

    @Test fun visualBatchDoesNotRequireAccessibilityIndexes() {
        val actions = PhoneControlBatch.actions(mapOf("actions" to
            """[{"action":"tap","x":120,"y":300},{"action":"wait","duration_ms":500},
                {"action":"tap","x":220,"y":400}]"""))
        assertEquals(3, actions.size)
        assertTrue(actions.none { "element_index" in it })
        assertEquals("wait", actions[1]["action"])
    }

    @Test fun failedInputStopsWithoutRepeatingOrSendingTheRemainder() = runBlocking {
        val sent = mutableListOf<Int>()
        val progress = runPhoneBatch(6, before = {}, perform = {
            sent.add(it); if (it == 2) "gesture failed" else null
        })
        assertEquals(listOf(0, 1, 2), sent)
        assertEquals(PhoneBatchProgress(2, 3, 6, "gesture failed"), progress)
    }

    @Test fun changedWindowStopsBeforeTheNextInput() = runBlocking {
        val sent = mutableListOf<Int>()
        val progress = runPhoneBatch(3, before = {
            check(it != 1) { "window changed" }
        }, perform = { sent.add(it); null })
        assertEquals(listOf(0), sent)
        assertEquals(PhoneBatchProgress(1, 1, 3, "window changed"), progress)
    }

    @Test fun stopCancellationIsNotConvertedIntoAResumableFailure() = runBlocking {
        var sent = 0
        try {
            runPhoneBatch(3, before = {
                if (it == 1) throw CancellationException("user stopped")
            }, perform = { sent++; null })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, sent)
    }

    @Test fun internalTimeoutKeepsAttemptedInputCountAndStopsTheBatch() = runBlocking {
        var sent = 0
        val progress = runPhoneBatch(3, before = {}, perform = {
            sent++
            delay(5_000)
            null
        }, timeoutMs = 100)
        assertEquals(1, sent)
        assertEquals(0, progress.completed)
        assertEquals(1, progress.attempted)
        assertTrue(progress.error.orEmpty().contains("timed out"))
    }

    @Test fun changingCalculatorDisplayDoesNotInvalidateAnotherButton() {
        val target = PhoneControlBatch.elements(button("1")).single()
        val current = PhoneControlBatch.elements(button("1")).toMutableList()
        current.addAll(PhoneControlBatch.elements(button("408", "[0,100][200,150]").copy(isClickable = false)))
        PhoneControlBatch.verifyTarget(target, current)
        assertEquals(50, target.x)
        assertEquals(50, target.y)
    }

    @Test(expected = IllegalStateException::class)
    fun movedTargetCannotBeClickedUsingItsOldIndex() {
        val target = PhoneControlBatch.elements(button("1")).single()
        PhoneControlBatch.verifyTarget(target, PhoneControlBatch.elements(button("1", "[100,0][200,100]")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedBatchBeforeAnyInput() {
        PhoneControlBatch.actions(mapOf("actions" to
            (1..21).joinToString(",", "[", "]") { """{"action":"back"}""" }))
    }

    @Test fun stableButtonCanBeRelocatedAfterLaunchAnimation() {
        val target = PhoneControlBatch.elements(button("1")).single()
        val moved = PhoneControlBatch.elements(button("1", "[100,200][200,300]"))
        assertEquals(150, PhoneControlBatch.resolveTarget(target, moved).x)
        assertEquals(250, PhoneControlBatch.resolveTarget(target, moved).y)
    }

    @Test(expected = IllegalStateException::class)
    fun repeatedListIdsCannotBeRelocatedAmbiguously() {
        val target = PhoneControlBatch.elements(button("Buy")).single()
        PhoneControlBatch.resolveTarget(target, listOf(target, target.copy(index = 2, top = 200)))
    }

    @Test(expected = IllegalStateException::class)
    fun changedProductCannotBeRelocatedByIdAlone() {
        val target = PhoneControlBatch.elements(button("Buy A")).single()
        PhoneControlBatch.resolveTarget(target, PhoneControlBatch.elements(button("Buy B")))
    }
}
