package com.ai.assistance.operit.api.chat.library

import org.junit.Assert.*
import org.junit.Test

class MemoryLearningSnapshotTest {
    @Test fun smallestSupportedWindowReservesInstructionsBeforeSource() {
        val budget = MemoryLearningSnapshot.sourceBudget(4096, 2100)
        val source = MemoryLearningSnapshot.digest(listOf("USER" to "文".repeat(10_000)), budget)
        assertTrue(source.toByteArray(Charsets.UTF_8).size + 2100 <= 3072)
        assertEquals(0, MemoryLearningSnapshot.sourceBudget(4096, 4000))
    }
    @Test fun longMultilingualHistoryRemainsBoundedAndKeepsSummaryAndRecentEvidence() {
        val messages = listOf("SUMMARY" to "此前用户需要无障碍模式") +
            (1..200).map { "USER" to "第${it}轮🙂".repeat(500) } +
            listOf("ASSISTANT" to "最新完成内容")
        val result = MemoryLearningSnapshot.digest(messages, 6000)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 6000)
        assertTrue(result.contains("此前用户需要无障碍模式"))
        assertTrue(result.contains("最新完成内容"))
        assertTrue(result.contains("omissions"))
        assertFalse(result.contains('\uFFFD'))
    }

    @Test fun giantToolOutputCannotDisplaceAllBudgetOrExpandInput() {
        val result = MemoryLearningSnapshot.digest(
            listOf("USER" to "重要偏好", "TOOL_RESULT" to "x".repeat(200_000),
                "ASSISTANT" to "完成"), 5000
        )
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 5000)
        assertTrue(result.contains("重要偏好"))
        assertTrue(result.contains("完成"))
    }
}
