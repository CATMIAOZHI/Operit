package com.ai.assistance.operit.api.chat.library

import org.junit.Assert.*
import org.junit.Test

class MemoryLearningSnapshotTest {
    @Test fun assistantReasoningIsRemovedBeforeExcerptsButUserQuotesRemain() {
        val result = MemoryLearningSnapshot.digest(listOf(
            "USER" to "引用 <think>用户示例</think>",
            "ASSISTANT" to "<think>不可靠推测".repeat(1000) + "</think>",
            "ai" to "<thinking>私有草稿</thinking>正式回复",
            "ASSISTANT" to "可见前缀<think>未闭合思考"
        ), 6000)
        assertFalse(result.contains("不可靠推测"))
        assertFalse(result.contains("私有草稿"))
        assertFalse(result.contains("未闭合思考"))
        assertTrue(result.contains("正式回复"))
        assertTrue(result.contains("用户示例"))
        assertTrue(result.contains("可见前缀"))
    }
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
