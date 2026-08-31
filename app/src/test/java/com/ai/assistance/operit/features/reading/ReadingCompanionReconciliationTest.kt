package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 跨库弱关联对账编排测试（纯 JVM）。
 *
 * 生产 [ReadingCompanionStore.reconcileWithChatGraph] 直接调用这里的
 * [runReadingCompanionReconcile]（同一实现，非测试副本），因此这些断言就是
 * 生产对账路径的行为契约：
 * - 缺一侧补链（主库有 run/child 时补 child_chat_id）；
 * - 目标不存在则 interrupt 仍活跃一侧（孤儿 run / 缺失 child 聊天）并放行 claim 释放；
 * - 已完成 run 不因缺失目标而被再次打断（transcript 保留，无删除调用）。
 */
class ReadingCompanionReconciliationTest {

    private fun generatedRun(
        runId: Long,
        subagentRunId: String? = "s-$runId",
        childChatId: String? = "c-$runId",
    ) =
        ReadingCompanionRunLinkRef(
            runId = runId,
            status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING,
            parentChatId = "p-$runId",
            subagentRunId = subagentRunId,
            childChatId = childChatId,
        )

    @Test
    fun `missing main run interrupts still-generating reading run and releases claim`() =
        runBlocking {
        var interrupted = 0
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = listOf(generatedRun(runId = 1)),
                activeMainOwners = emptyList(),
                lookupSubagentRun = { null },
                chatExists = { true },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { runId, error ->
                    assertEquals(1L, runId)
                    assertEquals("orphaned_subagent_run", error)
                    interrupted++
                    true
                },
                readingRunExists = { true },
                interruptSubagentRun = { false },
            )
        assertEquals(1, interrupted)
        assertEquals(1, outcome.interruptedReadingRuns)
        assertEquals(0, outcome.relinkedRuns)
        assertEquals(0, outcome.interruptedSubagentRuns)
        }

    @Test
    fun `missing child chat id is relinked from main run`() =
        runBlocking {
        var relinkedChild: String? = null
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns =
                    listOf(
                        generatedRun(runId = 2, subagentRunId = "s-2", childChatId = null)
                    ),
                activeMainOwners = emptyList(),
                lookupSubagentRun = {
                    ReadingCompanionSubagentRunLink(
                        subagentRunId = it,
                        parentChatId = "main-parent",
                        childChatId = "main-child",
                    )
                },
                chatExists = { true },
                relinkChildChat = { runId, parentChatId, childChatId ->
                    assertEquals(2L, runId)
                    // 阅读侧已有父聊天时保留原值，不覆盖（补链只补 child）。
                    assertEquals("p-2", parentChatId)
                    relinkedChild = childChatId
                },
                interruptReadingRun = { _, _ -> error("must not interrupt") },
                readingRunExists = { true },
                interruptSubagentRun = { false },
            )
        assertEquals("main-child", relinkedChild)
        assertEquals(1, outcome.relinkedRuns)
        assertEquals(0, outcome.interruptedReadingRuns)
        }

    @Test
    fun `missing child chat target interrupts still-generating reading run`() =
        runBlocking {
        var interrupted = 0
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = listOf(generatedRun(runId = 3, childChatId = "gone-child")),
                activeMainOwners = emptyList(),
                lookupSubagentRun = {
                    ReadingCompanionSubagentRunLink(
                        subagentRunId = it,
                        parentChatId = "p-3",
                        childChatId = "gone-child",
                    )
                },
                chatExists = { it != "gone-child" },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { runId, error ->
                    assertEquals(3L, runId)
                    assertEquals("missing_child_chat", error)
                    interrupted++
                    true
                },
                readingRunExists = { true },
                interruptSubagentRun = { false },
            )
        assertEquals(1, interrupted)
        assertEquals(1, outcome.interruptedReadingRuns)
        }

    @Test
    fun `completed run missing main run is not interrupted and transcript stays`() =
        runBlocking {
        val completed =
            ReadingCompanionRunLinkRef(
                runId = 4,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATED,
                parentChatId = "p-4",
                subagentRunId = "s-4",
                childChatId = "c-4",
            )
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = listOf(completed),
                activeMainOwners = emptyList(),
                lookupSubagentRun = { null },
                chatExists = { true },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { _, _ -> error("completed run must not be interrupted") },
                readingRunExists = { true },
                interruptSubagentRun = { false },
            )
        assertEquals(0, outcome.interruptedReadingRuns)
        // 只 interrupt/补链；没有任何删除 transcript 的路径（无 delete 回调可调用）。
        }

    @Test
    fun `reverse reconciliation interrupts active main run when reading run is missing`() =
        runBlocking {
        var interruptedSubagent: String? = null
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = emptyList(),
                activeMainOwners =
                    listOf(
                        ReadingCompanionMainOwnerRef(
                            subagentRunId = "s-missing",
                            readingRunId = 99L,
                        )
                    ),
                lookupSubagentRun = { null },
                chatExists = { true },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { _, _ -> error("must not interrupt reading side") },
                readingRunExists = { it != 99L },
                interruptSubagentRun = { id ->
                    interruptedSubagent = id
                    true
                },
            )
        assertEquals("s-missing", interruptedSubagent)
        assertEquals(1, outcome.interruptedSubagentRuns)
        assertEquals(0, outcome.interruptedReadingRuns)
        }

    @Test
    fun `reverse reconciliation skips main run when reading run still exists`() =
        runBlocking {
        var interruptedSubagent = 0
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = emptyList(),
                activeMainOwners =
                    listOf(
                        ReadingCompanionMainOwnerRef(
                            subagentRunId = "s-ok",
                            readingRunId = 100L,
                        )
                    ),
                lookupSubagentRun = { null },
                chatExists = { true },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { _, _ -> error("must not interrupt") },
                readingRunExists = { true },
                interruptSubagentRun = {
                    interruptedSubagent++
                    true
                },
            )
        assertEquals(0, interruptedSubagent)
        assertEquals(0, outcome.interruptedSubagentRuns)
        }

    @Test
    fun `interrupt callback refusal is not counted`() =
        runBlocking {
        val outcome =
            runReadingCompanionReconcile(
                linkedRuns = listOf(generatedRun(runId = 5)),
                activeMainOwners = emptyList(),
                lookupSubagentRun = { null },
                chatExists = { true },
                relinkChildChat = { _, _, _ -> error("must not relink") },
                interruptReadingRun = { _, _ -> false },
                readingRunExists = { true },
                interruptSubagentRun = { false },
            )
        assertEquals(0, outcome.interruptedReadingRuns)
        assertTrue(outcome.interruptedSubagentRuns == 0)
        assertFalse(outcome.interruptedReadingRuns > 0)
        }
}
