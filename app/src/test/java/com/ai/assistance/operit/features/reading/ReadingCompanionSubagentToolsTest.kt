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
        val summaries = mutableMapOf<String, String>()
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

        override fun hasPersistedSummary(bookId: String, sourceId: String): Boolean =
            sourceId in summaries

        override suspend fun readPersistedSummary(
            bookId: String,
            sourceId: String,
            chapterIndex: Int,
        ): String? =
            summaries[sourceId]

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
        summaryOnly: Boolean = false,
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
                chapters = (-2..7).map { index ->
                    ReaderChapter("book-A", "source-$index", index, "第${index + 1}章")
                },
                previousContext = (-1..2).map { index ->
                    AutoCommentContextChapter(
                        sourceId = "source-$index",
                        chapterIndex = index,
                        chapterTitle = "第${index + 1}章",
                        content = "前文$index",
                        excerptFromEnd = false,
                    )
                },
                backend = backend,
                loopGuard = ReadingCompanionLoopGuard(runId = 7L),
                summaryOnly = summaryOnly,
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

    private fun stageSummary(chatId: String, summary: String): ToolResult =
        withCaller(chatId) {
            ReadingCompanionSubagentTools.execute(
                tool(
                    ReadingCompanionSubagentTools.TOOL_SUBMIT_SUMMARY,
                    "summary" to summary,
                ),
            )
        }

    @Test
    fun `missing callerChatId is rejected`() {
        val result = ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS))
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("callerChatId"))
    }

    @Test
    fun `forged callerChatId without a registered session is rejected`() {
        val result =
            withCaller("not-a-real-child") {
                ReadingCompanionSubagentTools.execute(
                    tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS),
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
                            ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS,
                            "bookId" to "book-FORGED",
                            "chapterIndex" to "999",
                        ),
                    )
                }
            assertTrue(result.success)
            val text = result.result.toString()
            assertTrue("应返回会话的书籍名而非伪造值", text.contains("\"bookName\":\"Book A\""))
            assertTrue("应返回会话窗口中的目标章节", text.contains("\"chapterNumber\":4"))
            assertFalse(text.contains("book-FORGED"))
            val targetRef = JSONObject(text).getString("targetChapterRef")
            val read =
                withCaller("child-1") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_READ_CHAPTER, "chapterRef" to targetRef),
                    )
                }
            assertTrue(read.success)
            assertTrue(read.result.toString().contains("第一段"))
            assertEquals(4, backend.heartbeats.get())
            assertEquals(2, backend.toolInvocations.get())
            assertTrue(backend.traces.contains("subagent_tool:completed"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-1")
        }
    }

    @Test
    fun `read chapter accepts target and four preceding chapters only`() {
        val backend = FakeBackend()
        registerSession("child-window", backend)
        try {
            val listed = withCaller("child-window") {
                ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS))
            }
            val chapters = JSONObject(listed.result.toString()).getJSONArray("chapters")
            for (i in 0 until chapters.length()) {
                val ref = chapters.getJSONObject(i).getString("chapterRef")
                val read = withCaller("child-window") {
                    ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_READ_CHAPTER, "chapterRef" to ref))
                }
                assertTrue(read.success)
            }
            val outside = ReadingCompanionFileStore.chapterRef("book-A", "source-4")
            val rejected = withCaller("child-window") {
                ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_READ_CHAPTER, "chapterRef" to outside))
            }
            assertFalse(rejected.success)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-window")
        }
    }

    @Test
    fun `chapter summaries read only persisted summaries before raw window`() {
        val backend = FakeBackend()
        backend.summaries["source--2"] = "旧章摘要"
        backend.summaries["source-3"] = "目标不应返回"
        registerSession("child-summaries", backend)
        try {
            val result = withCaller("child-summaries") {
                ReadingCompanionSubagentTools.execute(tool(ReadingCompanionSubagentTools.TOOL_GET_CHAPTER_SUMMARIES))
            }
            val summaries = JSONObject(result.result.toString()).getJSONArray("summaries")
            assertEquals(1, summaries.length())
            assertEquals("旧章摘要", summaries.getJSONObject(0).getString("summary"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-summaries")
        }
    }

    @Test
    fun `empty submit result stores summary and succeeds`() {
        val backend = FakeBackend()
        val session = registerSession("child-2", backend)
        try {
            val summaryResult = stageSummary("child-2", "无评论目标章摘要")
            assertTrue(summaryResult.success)
            assertFalse(summaryResult.interruptTurn)
            val result =
                withCaller("child-2") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to "[]",
                        ),
                    )
                }
            assertTrue(result.success)
            assertTrue(result.interruptTurn)
            val payload = JSONObject(result.result.toString())
            assertEquals(0, payload.getInt("acceptedCount"))
            assertTrue(session.candidateDrafts.isEmpty())
            assertEquals("无评论目标章摘要", session.candidateSummary)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-2")
        }
    }

    @Test
    fun `valid submit candidate with wrapped object stores drafts and finalizes`() {
        val backend = FakeBackend()
        val session = registerSession("child-3", backend)
        try {
            assertTrue(stageSummary("child-3", "目标章摘要").success)
            val result =
                withCaller("child-3") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """{"comments":[{"anchorId":"p0001","text":"写得真好","kind":"reaction","evidenceIds":["p0001"]}]}""",
                        ),
                    )
                }
            assertTrue(result.success)
            assertTrue("成功的 submit 必须终止本轮", result.interruptTurn)
            val payload = JSONObject(result.result.toString())
            assertEquals(AutoCommentSupport.REPORT_SUBMITTED, payload.getString("code"))
            assertEquals(1, payload.getInt("acceptedCount"))
            assertEquals(1, session.candidateDrafts.size)
            assertEquals(1, session.candidateDrafts.single().paragraphIndex)
            assertEquals("写得真好", session.candidateDrafts.single().text)
            assertTrue("submit 成功后会话必须置位 finalized", session.submissionFinalized)
            assertEquals(0, backend.replaceAttempts.get())
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-3")
        }
    }

    @Test
    fun `valid submit candidate with bare array is accepted`() {
        val backend = FakeBackend()
        val session = registerSession("child-bare", backend)
        try {
            assertTrue(stageSummary("child-bare", "目标章摘要").success)
            val result =
                withCaller("child-bare") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """[{"anchorId":"p0002","text":"这段很有味道","kind":"echo","evidenceIds":["p0002"]}]""",
                        ),
                    )
                }
            assertTrue(result.success)
            assertTrue(result.interruptTurn)
            assertEquals(1, session.candidateDrafts.size)
            assertEquals(2, session.candidateDrafts.single().paragraphIndex)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-bare")
        }
    }

    @Test
    fun `invalid json shape returns diagnostic without touching candidates`() {
        val backend = FakeBackend()
        val session = registerSession("child-shape", backend)
        try {
            assertTrue(stageSummary("child-shape", "目标章摘要").success)
            val result =
                withCaller("child-shape") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to """not-json-at-all""",
                        ),
                    )
                }
            assertFalse(result.success)
            assertFalse(result.interruptTurn)
            val diagnostic = JSONObject(result.error.orEmpty())
            assertEquals(AutoCommentSupport.REPORT_INVALID_JSON_SHAPE, diagnostic.getString("code"))
            assertTrue(diagnostic.getString("expectedFormat").contains("["))
            assertTrue(session.candidateDrafts.isEmpty())
            assertFalse(session.submissionFinalized)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-shape")
        }
    }

    @Test
    fun `all candidates rejected returns reasons without leaking content`() {
        val backend = FakeBackend()
        val session = registerSession("child-reject", backend)
        try {
            assertTrue(stageSummary("child-reject", "目标章摘要").success)
            val result =
                withCaller("child-reject") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """[{"anchorId":"p9999","text":"越界锚点","kind":"reaction","evidenceIds":["p9999"]}]""",
                        ),
                    )
                }
            assertFalse(result.success)
            val diagnostic = JSONObject(result.error.orEmpty())
            assertEquals(
                AutoCommentSupport.REPORT_ALL_CANDIDATES_REJECTED,
                diagnostic.getString("code"),
            )
            assertEquals(1, diagnostic.getInt("rejectedCount"))
            val reasons = diagnostic.getJSONObject("reasons")
            assertEquals(1, reasons.getInt(AutoCommentSupport.REASON_INVALID_ANCHOR))
            val rejections = diagnostic.getJSONArray("rejections")
            assertEquals(1, rejections.length())
            assertEquals(1, rejections.getJSONObject(0).getInt("candidateNumber"))
            // 诊断不得携带候选正文。
            assertFalse(diagnostic.toString().contains("越界锚点"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-reject")
        }
    }

    @Test
    fun `finalized session rejects subsequent tool calls`() {
        val backend = FakeBackend()
        val session = registerSession("child-final", backend)
        try {
            assertTrue(stageSummary("child-final", "目标章摘要").success)
            val first =
                withCaller("child-final") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """[{"anchorId":"p0001","text":"好","kind":"reaction","evidenceIds":["p0001"]}]""",
                        ),
                    )
                }
            assertTrue(first.success)
            assertTrue(session.submissionFinalized)
            val second =
                withCaller("child-final") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS,
                        ),
                    )
                }
            assertFalse("finalized 后任何工具调用必须被拒绝", second.success)
            assertTrue(second.error.orEmpty().contains("不再接受"))
            assertEquals(1, session.candidateDrafts.size)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-final")
        }
    }

    @Test
    fun `failed submit does not finalize and allows a corrected resubmit`() {
        val backend = FakeBackend()
        val session = registerSession("child-retry", backend)
        try {
            assertTrue(stageSummary("child-retry", "目标章摘要").success)
            val first =
                withCaller("child-retry") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """[{"anchorId":"p9999","text":"坏锚点","kind":"reaction"}]""",
                        ),
                    )
                }
            assertFalse(first.success)
            assertFalse(first.interruptTurn)
            assertFalse(session.submissionFinalized)
            val second =
                withCaller("child-retry") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to
                                """[{"anchorId":"p0001","text":"修正后","kind":"reaction","evidenceIds":["p0001"]}]""",
                        ),
                    )
                }
            assertTrue("失败后修正重提必须成功", second.success)
            assertTrue(second.interruptTurn)
            assertEquals(1, session.candidateDrafts.size)
            assertEquals("修正后", session.candidateDrafts.single().text)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-retry")
        }
    }

    @Test
    fun `summary submission with empty array finalizes without comments`() {
        val backend = FakeBackend()
        val session = registerSession("child-4", backend)
        try {
            assertTrue(stageSummary("child-4", "本章均为过渡情节").success)
            val result =
                withCaller("child-4") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to "[]",
                        ),
                    )
                }
            assertTrue(result.success)
            assertTrue(result.interruptTurn)
            assertEquals("本章均为过渡情节", session.candidateSummary)
            assertTrue(session.candidateDrafts.isEmpty())
            assertEquals(0, backend.replaceAttempts.get())
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-4")
        }
    }

    @Test
    fun `summary-only session finalizes on submit_summary without claim or comments`() {
        val backend = FakeBackend().apply { heartbeatResult = false }
        val session = registerSession("child-summary-only", backend, summaryOnly = true)
        try {
            val result = stageSummary("child-summary-only", "只保存客观摘要")
            assertTrue(result.success)
            assertTrue("summary-only submit_summary 是终止工具", result.interruptTurn)
            assertEquals("summary_submitted", JSONObject(result.result.toString()).getString("code"))
            assertTrue(session.submissionFinalized)
            assertTrue(session.candidateDrafts.isEmpty())
            assertEquals(0, backend.heartbeats.get())
            val comments =
                withCaller("child-summary-only") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to "[]",
                        ),
                    )
                }
            assertFalse(comments.success)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-summary-only")
        }
    }

    @Test
    fun `comments before summary are rejected without finalizing`() {
        val session = registerSession("child-summary-required", FakeBackend())
        try {
            val result =
                withCaller("child-summary-required") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to "[]",
                        ),
                    )
                }
            assertFalse(result.success)
            assertFalse(result.interruptTurn)
            assertEquals("summary_required", JSONObject(result.error.orEmpty()).getString("code"))
            assertFalse(session.submissionFinalized)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-summary-required")
        }
    }

    @Test
    fun `list chapters returns stable refs and target window`() {
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
                        tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS),
                    )
                }
            assertTrue(result.success)
            val text = result.result.toString()
            val payload = JSONObject(text)
            assertEquals(5, payload.getInt("chapterCount"))
            assertEquals(
                ReadingCompanionFileStore.chapterRef("book-A", "source-3"),
                payload.getString("targetChapterRef"),
            )
            assertTrue(payload.getJSONArray("chapters").getJSONObject(4).getBoolean("isTarget"))
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-6")
        }
    }

    @Test
    fun `submit of up to six comments keeps them all`() {
        val backend = FakeBackend()
        // 不再有任何按字数推导的上限：提交 6 条应全部保留，仅超出全局 6 条才会截断。
        val session =
            registerSession(
                "child-more",
                backend,
                targetContent = "第1段\n第2段\n第3段\n第4段\n第5段\n第6段",
            )
        try {
            assertTrue(stageSummary("child-more", "目标章摘要").success)
            val items =
                (1..6).joinToString(",") { index ->
                    """{"anchorId":"p%04d","text":"评论%d","kind":"reaction","evidenceIds":["p%04d"]}"""
                        .format(index, index, index)
                }
            val result =
                withCaller("child-more") {
                    ReadingCompanionSubagentTools.execute(
                        tool(
                            ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                            "comments" to "[$items]",
                        ),
                    )
                }
            assertTrue(result.success)
            assertEquals(6, session.candidateDrafts.size)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-more")
        }
    }

    @Test
    fun `submit over six comments truncates to six by anchor order`() {
        val backend = FakeBackend()
        val session = registerSession(
            "child-over", backend,
            targetContent = (1..7).joinToString("\n") { "第${it}段" },
        )
        try {
            assertTrue(stageSummary("child-over", "目标章摘要").success)
            val items = (1..7).joinToString(",") {
                """{"anchorId":"p%04d","text":"评论%d","kind":"reaction","evidenceIds":["p%04d"]}"""
                    .format(it, it, it)
            }
            val result = withCaller("child-over") {
                ReadingCompanionSubagentTools.execute(
                    tool(
                        ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS,
                        "comments" to "[$items]",
                    ),
                )
            }
            assertTrue(result.success)
            assertEquals(6, session.candidateDrafts.size)
            assertEquals(1, session.candidateDrafts.first().paragraphIndex)
            assertEquals(6, session.candidateDrafts.last().paragraphIndex)
        } finally {
            ReadingCompanionSubagentSessionRegistry.unregister("child-over")
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
                        tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS),
                    )
                }
            assertFalse(first.success)
            assertTrue(first.error.orEmpty().contains("claim_lost"))
            assertEquals("claim_lost", session.stoppedReason)
            val second =
                withCaller("child-5") {
                    ReadingCompanionSubagentTools.execute(
                        tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS),
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
        assertTrue(ReadingCompanionSubagentTools.TERMINAL_TOOL_NAMES.isEmpty())
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
            val tool = tool(ReadingCompanionSubagentTools.TOOL_LIST_CHAPTERS)
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
