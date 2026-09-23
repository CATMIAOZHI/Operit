package com.ai.assistance.operit.api.chat.library

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import org.junit.Assert.*
import org.junit.Test

class MemoryLearningSnapshotTest {
    @Test fun thinkingCanBeIncludedWithoutProtocolMetadataOrBudgetOverflow() {
        val text = "<think>先点击计算器，再输入12乘34</think>完成" +
            "<meta provider=\"gemini:thought_signature\">opaque-secret</meta>"
        val included = MemoryLearningSnapshot.digest(listOf("ai" to text), 3000, includeThinking = true)
        assertTrue(included.contains("先点击计算器"))
        assertFalse(included.contains("opaque-secret"))
        assertFalse(MemoryLearningSnapshot.digest(listOf("ai" to text), 3000, includeThinking = false)
            .contains("先点击计算器"))
        val bounded = MemoryLearningSnapshot.digest(listOf("ai" to "<think>${"思考".repeat(10000)}</think>完成"),
            1000, includeThinking = true)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 1000)
    }
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

    @Test fun batchGrowsWithTheWindowInsteadOfAFlatCeiling() {
        // The API maximum, not the window the chat manages the live conversation to.
        assertEquals(256_000, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 128f, maxContextLength = 256f)))
        // A management length the user filled higher than the API maximum still wins: the resolution
        // takes whichever usable length is larger, and only falls back to 8192 when neither can.
        assertEquals(512_000, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 512f, maxContextLength = 256f)))
        // A third of the window, so a long conversation is a few large reviews instead of a dozen
        // small ones that each re-read the catalog and cannot see their neighbours.
        assertEquals(128_000 / 3, MemoryLearningSnapshot.sourceBudget(128_000, 0))
        assertEquals(256_000 / 3, MemoryLearningSnapshot.sourceBudget(256_000, 0))
        // The ceiling still bounds a very large advertised window.
        assertEquals(96_000, MemoryLearningSnapshot.sourceBudget(2_000_000, 0))
    }

    @Test fun windowKeepsLocalProviderCapsAndTheFallback() {
        val llama = ModelConfigData("id", "n", apiProviderType = ApiProviderType.LLAMA_CPP,
            apiProviderTypeId = ApiProviderType.LLAMA_CPP.name, contextLength = 128f,
            maxContextLength = 256f, llamaContextSize = 4096)
        assertEquals(4096, MemoryLearningSnapshot.modelWindowTokens(llama))
        // A provider whose own cap leaves no room for instructions, evidence and a response is rejected
        // instead of being handed a batch it cannot hold (the pre-existing MNN behavior).
        val mnn = ModelConfigData("id", "n", apiProviderType = ApiProviderType.MNN,
            apiProviderTypeId = ApiProviderType.MNN.name, contextLength = 128f, maxContextLength = 256f)
        try {
            MemoryLearningSnapshot.modelWindowTokens(mnn)
            fail("a 2048 token local model has no room for a review batch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("4096"))
        }
        // Neither length usable -> the historical conservative default.
        assertEquals(8192, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 0f, maxContextLength = 0f)))
        // One usable length is still enough, so a filled management length alone cannot break extraction.
        assertEquals(128_000, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 128f, maxContextLength = 0f)))
        // The "maximum context mode" switch belongs to the chat window, not to a review batch: with the
        // larger field selected here the answer stays the larger field, so switching the mode cannot
        // silently shrink a batch.
        assertEquals(512_000, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 512f, maxContextLength = 256f,
                enableMaxContextMode = true)))
        // A too-small length is not a candidate, and the review then falls back instead of failing: a
        // configuration a review used to run under must keep running.
        assertEquals(8192, MemoryLearningSnapshot.modelWindowTokens(
            ModelConfigData("id", "n", contextLength = 0f, maxContextLength = 2f)))
    }

    @Test fun sourceNeverEatsTheWholeWindowOrOverflowsTheEvidenceBudget() {
        for (window in listOf(4096, 32_000, 128_000, 256_000, 2_000_000)) {
            val instructions = (window * 0.1).toInt()
            val budget = MemoryLearningSnapshot.sourceBudget(window, instructions)
            assertTrue("window=$window budget=$budget", budget <= window / 3)
            // The same budget the coordinator stops the batch on, so a batch can never start over it.
            val evidence = MemoryLearningSnapshot.evidenceBudget(window)
            assertTrue("window=$window budget=$budget", budget + instructions <= evidence)
            // Strictly less than the aggregate budget, so lookups still have room.
            assertTrue("window=$window budget=$budget", budget < evidence)
        }
        // Monotonic in the window while the ceiling is not yet reached: a bigger model window means
        // fewer, larger reviews rather than the same small slice repeated.
        assertTrue(MemoryLearningSnapshot.sourceBudget(256_000, 0) >
            MemoryLearningSnapshot.sourceBudget(128_000, 0))
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
