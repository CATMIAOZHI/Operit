package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingCompanionSubagentToolsTest {

    private class FakeBackend : ReadingCompanionSubagentBackend {
        val heartbeats = AtomicInteger(0)
        val toolInvocations = AtomicInteger(0)
        val replaceAttempts = AtomicInteger(0)
        val traces = mutableListOf<String>()
        val searchQueries = mutableListOf<String>()
        @Volatile var heartbeatResult = true

        override fun heartbeatClaimIfOwned(
            bookId: String,
            chapterIndex: Int,
            runId: Long,
        ): Boolean {
            heartbeats.incrementAndGet()
            return heartbeatResult
        }

        override fun recordAutoCommentRunTrace(
            runId: Long,
            operation: String,
            status: String,
            startedAt: Long,
            finishedAt: Long?,
            metadataJson: String?,
        ) {
            traces += "$operation:$status"
        }

        override fun incrementRunToolInvocation(runId: Long): Boolean {
            toolInvocations.incrementAndGet()
            return true
        }

        override fun incrementRunModelRound(runId: Long): Boolean = true

        override suspend fun search(query: String): JSONObject =
            JSONObject().apply {
                searchQueries += query
                put("hits", 1)
                put("query", query)
            }
    }

    private fun registerSession(
        childChatId: String,
        backend: ReadingCompanionSubagentBackend,
        targetContent: String = "第一段\n第二段\n第三段",
        rolePrompt: String = "",
    ): ReadingCompanionRunSession {
        val session =
            ReadingCompanionRunSession(
                runId = 7L,
                bookId = "book-A",
                bookName = "Book A",
                chapterIndex = 3,
                chapterTitle = "测试章",
                contentHash = "hash-1",
                roleCardId = "role-1",
                roleCardName = "Rainy",
                rolePrompt = rolePrompt,
                targetContent = targetContent,
                previousContext = emptyList(),
                backend = backend,
                loopGuard = ReadingCompanionLoopGuard(runId = 7L),
            )
        ReadingCompanionSubagentSessionRegistry.register(childChatId, session)
        return session
    }

    private fun withCaller(chatId: String, block: () -> ToolResult): ToolResult {
        val runtime =
            ToolExecutionManager.ToolRuntimeContext(
                callerChatId = chatId,
                callerName = "audit",
                isSubagent = true,
                parentModelConfigId = "mc-1",
                parentModelIndex = 2,
            )
        return runBlocking {
            withContext(ToolExecutionManager.toolRuntimeContextElement(runtime)) {
                block()
            }
        }
    }

    @Test
    fun `missing callerChatId is rejected`() {
        val result = ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_GET_TARGET_CHAPTER))
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("callerChatId"))
    }

    @Test
    fun `forged callerChatId without a registered session is rejected`() {
        val result =
            withCaller("not-a-real-child") {
                ReadingCompanionSubagentTools.execute(
                    tool(ReadingCompanionSubagentTools.TOOL_GET_TARGET_CHAPTER),
                )
            }
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("未找到阅读伴读执行会话"))
    }

    @Test
    fun `fake bookId and chapterIndex are ignored and session target wins`() {
        val backend = FakeBackend()
        val session = registerSession("child-1", backend)
        try {
            val result =
                withCaller("child-1") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_GET_TARGET_CHAPTER,
                            "bookId" to "book-FORGED",
                            "chapterIndex" to "999",
                        ),
                    )
                }
            assertTrue(result.success)
            val text = result.result.toString()
            assertTrue("应返回会话的书籍名而非伪造值", text.contains("\"bookName\":\"Book A\""))
            assertTrue("应返回会话的章节号 4（索引 3+1）而非 1000", text.contains("\"chapterNumber\":4"))
            assertFalse(text.contains("book-FORGED"))
            assertTrue(text.contains("第一段"))
            assertEquals(2, backend.heartbeats.get())
            assertEquals(1, backend.toolInvocations.get())
            assertTrue(backend.traces.contains("subagent_tool:completed"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-1")
        }
    }

    @Test
    fun `empty submit candidate is rejected and never touches replace`() {
        val backend = FakeBackend()
        val session = registerSession("child-2", backend)
        try {
            val result =
                withCaller("child-2") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_CANDIDATE,
                            "comments" to """{"comments":[]}""",
                        ),
                    )
                }
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("不会覆盖任何已有段评"))
            assertTrue(session.candidateDrafts.isEmpty())
            assertEquals(0, backend.replaceAttempts.get())
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-2")
        }
    }

    @Test
    fun `valid submit candidate stores drafts in the session only`() {
        val backend = FakeBackend()
        val session = registerSession("child-3", backend)
        try {
            val result =
                withCaller("child-3") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_CANDIDATE,
                            "comments" to
                                """{"comments":[{"anchorId":"p0001","text":"写得真好","kind":"reaction","evidenceIds":["p0001"]}]}""",
                        ),
                    )
                }
            assertTrue(result.success)
            assertEquals(1, session.candidateDrafts.size)
            assertEquals(1, session.candidateDrafts.single().paragraphIndex)
            assertEquals("写得真好", session.candidateDrafts.single().text)
            assertEquals(0, backend.replaceAttempts.get())
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-3")
        }
    }

    @Test
    fun `abstain marks abstained with empty candidates so caller emits no_valid_comments`() {
        val backend = FakeBackend()
        val session = registerSession("child-4", backend)
        try {
            val result =
                withCaller("child-4") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_ABSTAIN,
                            "reason" to "本章均为过渡情节",
                        ),
                    )
                }
            assertTrue(result.success)
            assertTrue(session.abstained)
            assertTrue(session.candidateDrafts.isEmpty())
            assertEquals(0, backend.replaceAttempts.get())
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-4")
        }
    }

    @Test
    fun `get_constraints returns the full persona text not just the name`() {
        val backend = FakeBackend()
        val personaText = "【设定】雨夜侦探，冷静毒舌，喜欢短句与吐槽。"
        registerSession(
            "child-6",
            backend,
            rolePrompt = personaText,
        )
        try {
            val result =
                withCaller("child-6") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_GET_CONSTRAINTS),
                    )
                }
            assertTrue(result.success)
            val text = result.result.toString()
            assertTrue(text.contains("\"roleCardName\":\"Rainy\""))
            assertTrue(
                "get_constraints 必须携带完整人设而非仅角色名",
                text.contains(personaText),
            )
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-6")
        }
    }

    @Test
    fun `claim loss stops the run and subsequent calls short-circuit`() {
        val backend = FakeBackend()
        val session = registerSession("child-5", backend)
        backend.heartbeatResult = false
        try {
            val first =
                withCaller("child-5") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_GET_CONSTRAINTS),
                    )
                }
            assertFalse(first.success)
            assertTrue(first.error.orEmpty().contains("claim_lost"))
            assertEquals("claim_lost", session.stoppedReason)
            val second =
                withCaller("child-5") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_GET_CONSTRAINTS),
                    )
                }
            assertFalse(second.success)
            assertTrue(second.error.orEmpty().contains("生成已停止"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-5")
        }
    }

    @Test
    fun `search executes through the session backend and records the query`() {
        val backend = FakeBackend()
        registerSession("child-6", backend)
        try {
            val result =
                withCaller("child-6") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_SEARCH, "query" to "主角"),
                    )
                }
            assertTrue(result.success)
            assertTrue(result.result.toString().contains("\"hits\":1"))
            assertEquals(listOf("主角"), backend.searchQueries)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-6")
        }
    }

    @Test
    fun `isolated prompts expose exactly the six tools and nothing else`() {
        val promptNames = ReadingCompanionSubagentTools.prompts().map { it.name }.toSet()
        assertEquals(ReadingCompanionSubagentTools.TOOL_NAMES, promptNames)
        assertEquals(6, promptNames.size)
        assertFalse("第七个工具名不得进入隔离面", "reading_commentary_evil_tool" in promptNames)
        assertEquals(
            setOf(
                ReadingCompanionSubagentTools.TOOL_SUBMIT_CANDIDATE,
                ReadingCompanionSubagentTools.TOOL_ABSTAIN,
            ),
            ReadingCompanionSubagentTools.TERMINAL_TOOL_NAMES,
        )
    }

    @Test
    fun `three identical calls hit loop_detected without any approval dialog`() {
        registerSession("child-7", FakeBackend())
        try {
            // 调用层的连续相同调用由 ToolExecutionManager 的阅读护栏在 executeInvocations 内
            // 拦截（recordCall 第 3 次抛 ReadingCompanionLoopException）。这里验证护栏状态机：
            val guard =
                ReadingCompanionSubagentSessionRegistry
                    .sessionForChildChat("child-7")
                    ?.loopGuard
                    ?: error("session missing")
            val tool = tool(ReadingCompanionSubagentTools.TOOL_GET_CONSTRAINTS)
            assertEquals(
                ReadingCompanionCallVerdict.OK,
                guard.recordCall(tool.name, normalizeReadingCompanionToolCall(tool)),
            )
            assertEquals(
                ReadingCompanionCallVerdict.OK,
                guard.recordCall(tool.name, normalizeReadingCompanionToolCall(tool)),
            )
            assertEquals(
                ReadingCompanionCallVerdict.LOOP_DETECTED,
                guard.recordCall(tool.name, normalizeReadingCompanionToolCall(tool)),
            )
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-7")
        }
    }

    private fun tool(name: String, vararg parameters: Pair<String, String>): AITool =
        AITool(name = name, parameters = parameters.map { ToolParameter(it.first, it.second) })
}
