package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingCompanionLoopGuardTest {

    private val traces = mutableListOf<Triple<String, String, String?>>()

    private fun newGuard(runId: Long = 1L): ReadingCompanionLoopGuard =
        ReadingCompanionLoopGuard(runId = runId) { operation, status, metadata ->
            traces += Triple(operation, status, metadata)
        }

    @Test
    fun `second identical call does not trigger and third identical call triggers loop_detected`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=1"))
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=1"))
        assertTrue(
            "前两次相同调用不应触发 loop_detected",
            traces.none { it.second == "loop_detected" },
        )
        assertEquals(ReadingCompanionCallVerdict.LOOP_DETECTED, guard.recordCall("t", "a=1"))
        assertTrue(traces.any { it.second == "loop_detected" })
        assertTrue(traces.first { it.second == "loop_detected" }.third?.contains("\"tool\":\"t\"") == true)
    }

    @Test
    fun `a different call breaks the consecutive streak`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=1"))
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=2"))
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=1"))
        assertEquals(ReadingCompanionCallVerdict.OK, guard.recordCall("t", "a=1"))
        // 只有重新连续 3 次才触发（5 次调用：1,2,1,1,1）
        assertEquals(ReadingCompanionCallVerdict.LOOP_DETECTED, guard.recordCall("t", "a=1"))
    }

    @Test
    fun `normalization makes parameter order irrelevant and keeps the same fingerprint`() {
        val toolA =
            AITool(
                name = "reading_commentary_search",
                parameters = listOf(ToolParameter("query", "主角"), ToolParameter("x", "1")),
            )
        val toolB =
            AITool(
                name = "reading_commentary_search",
                parameters = listOf(ToolParameter("x", "1"), ToolParameter("query", "主角")),
            )
        assertEquals(normalizeReadingCompanionToolCall(toolA), normalizeReadingCompanionToolCall(toolB))
        assertFalse(
            normalizeReadingCompanionToolCall(toolA) ==
                normalizeReadingCompanionToolCall(
                    toolB.copy(
                        parameters = listOf(ToolParameter("query", "主角2"), ToolParameter("x", "1")),
                    ),
                ),
        )
    }

    @Test
    fun `same call and result three times without evidence triggers no_progress`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r"))
        assertEquals(ReadingCompanionProgressVerdict.NO_PROGRESS, guard.recordResult("t", "a=1", "r"))
        assertTrue(traces.any { it.second == "no_progress" })
    }

    @Test
    fun `interleaved same call-result pairs still count as a stalled cycle`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("a", "x=1", "r1"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("b", "y=2", "r2"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("a", "x=1", "r1"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("b", "y=2", "r2"))
        // a 的组合第 3 次出现，且中间没有新增证据
        assertEquals(ReadingCompanionProgressVerdict.NO_PROGRESS, guard.recordResult("a", "x=1", "r1"))
    }

    @Test
    fun `new evidence resets the stalled-pair counting`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r", evidenceAdvanced = true))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r"))
        // 证据重置后需要再重复 3 次才算 no_progress
        assertEquals(ReadingCompanionProgressVerdict.NO_PROGRESS, guard.recordResult("t", "a=1", "r"))
    }

    @Test
    fun `a changed result fingerprint never counts as a repeated pair`() {
        val guard = newGuard()
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r1"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r2"))
        assertEquals(ReadingCompanionProgressVerdict.OK, guard.recordResult("t", "a=1", "r3"))
        assertTrue(traces.none { it.second == "no_progress" })
    }
}
