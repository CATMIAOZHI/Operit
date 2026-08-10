package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistration
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * ToolPkg JS provider usage 协议测试（P1-5 + 评审 P2-1）：
 * - usage 对象携带 attempt 序号（新协议）：同 attempt 流式更新部分合并，
 *   不同 attempt 分别入账；
 * - 不携带 attempt（旧协议）：整个逻辑请求的累计完整快照，后报覆盖先报，
 *   绝不猜测 attempt；
 * - 真实 hook 层测试：通过 [ToolPkgMainHookRunner] 注入假 runner，驱动
 *   sendMessage 的真实编排（intermediate channel、解码、usage 提取、chunk
 *   发射、attempt 语义），不只是 JSON parser。
 */
class ToolPkgJsAiProviderServiceTest {

    private fun service(modelName: String = ""): ToolPkgJsAiProviderService {
        val config = ModelConfigData(id = "cfg-1", name = "cfg-1", modelName = modelName)
        val registration =
            ToolPkgAiProviderRegistration(
                containerPackageName = "com.example.testpkg",
                providerId = "test-provider",
                displayName = "Test Provider",
                description = "",
                listModelsFunctionName = "",
                sendMessageFunctionName = "",
                testConnectionFunctionName = "",
                calculateInputTokensFunctionName = "",
            )
        return ToolPkgJsAiProviderService(config, registration)
    }

    @Test
    fun tokenStatisticsUseStableProviderIdWithoutChangingDisplayIdentity() {
        val service = service(modelName = "model:variant")

        assertEquals("Test Provider:model:variant", service.providerModel)
        assertEquals("test-provider" to "model:variant", resolveTokenStatIdentity(service))
    }

    /** 假 runner：按给定 intermediate/final JSON 驱动真实 hook 编排层。 */
    private fun runnerWith(
        intermediates: List<String>,
        final: String,
    ): ToolPkgMainHookRunner =
        ToolPkgMainHookRunner { _, _, _, _, _, _, _, _, onIntermediateResult ->
            intermediates.forEach { raw ->
                onIntermediateResult?.invoke(raw)
            }
            Result.success(final)
        }

    private class ReportedUsage(
        val input: Long,
        val output: Long,
        val attempt: Int,
        val completeSnapshot: Boolean,
    )

    private fun report(usage: com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) =
        ReportedUsage(
            input = usage.totalInputTokens ?: -1L,
            output = usage.outputTokens ?: -1L,
            attempt = attempt,
            completeSnapshot = usage.completeSnapshot,
        )

    private fun runSendMessage(
        svc: ToolPkgJsAiProviderService,
        runner: ToolPkgMainHookRunner,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, Int) -> Unit)?,
    ): String = runBlocking {
        svc.mainHookRunnerOverride = runner
        val collected = StringBuilder()
        svc.sendMessage(
            context = mock(Context::class.java),
            onUsageReported = onUsageReported,
        ).collect { collected.append(it) }
        collected.toString()
    }

    /** 期望失败的 sendMessage 运行：返回已收集文本与 collect 传播出的异常。 */
    private fun runSendMessageExpectFailure(
        svc: ToolPkgJsAiProviderService,
        runner: ToolPkgMainHookRunner,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, Int) -> Unit)?,
    ): Pair<String, Throwable?> = runBlocking {
        svc.mainHookRunnerOverride = runner
        val collected = StringBuilder()
        // JVM 测试环境没有可用的 android.util.Log：stream 构建器捕获异常后
        // StreamLogger.e → AppLogger.e → Log.e 会抛 "not mocked" 掩盖原始错误，
        // 关闭日志使真实异常原样传播出来
        StreamLogger.setEnabled(false)
        val failure =
            try {
                svc.sendMessage(
                    context = mock(Context::class.java),
                    onUsageReported = onUsageReported,
                ).collect { collected.append(it) }
                null
            } catch (e: Throwable) {
                e
            } finally {
                StreamLogger.setEnabled(true)
            }
        collected.toString() to failure
    }

    @Test
    fun `usage protocol carries attempt number from usage object`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject(
                    """
                    {"usage": {"input": 100, "cachedInput": 20, "output": 50, "attempt": 2}}
                    """.trimIndent()
                )
            )
        val usage = svc.extractUsage(decoded)!!
        assertEquals(2, usage.attempt)
        assertTrue("attempt present must be tracked", usage.attemptPresent)
        assertEquals(100L, usage.input)
        assertEquals(20L, usage.cachedInput)
        assertEquals(50L, usage.output)
    }

    @Test
    fun `attemptNumber alias is supported`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject(
                    """
                    {"usage": {"input": 10, "output": 5, "attemptNumber": 5}}
                    """.trimIndent()
                )
            )
        val usage = svc.extractUsage(decoded)!!
        assertEquals(5, usage.attempt)
        assertTrue(usage.attemptPresent)
    }

    @Test
    fun `attempt defaults to 1 when provider omits the field and marks old protocol`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject("""{"usage": {"input": 10, "output": 5}}""")
            )
        val usage = svc.extractUsage(decoded)!!
        assertEquals(1, usage.attempt)
        // 评审 P2-1：缺 attempt 的旧协议上报必须被显式标记，绝不猜测 attempt
        assertFalse("attempt absent must not be guessed", usage.attemptPresent)
    }

    @Test
    fun `attempt is coerced to at least 1`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject("""{"usage": {"input": 10, "output": 5, "attempt": 0}}""")
            )
        assertEquals(1, svc.extractUsage(decoded)!!.attempt)
    }

    @Test
    fun `top-level usage without usage object is accepted`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject("""{"input": 30, "output": 9, "attempt": 3}""")
            )
        val usage = svc.extractUsage(decoded)!!
        assertEquals(3, usage.attempt)
        assertEquals(30L, usage.input)
        assertEquals(9L, usage.output)
    }

    @Test
    fun `no usage fields returns null`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(JSONObject("""{"chunk": "text"}"""))
        assertNull(svc.extractUsage(decoded))
        assertNull(svc.extractUsage(ToolPkgJsAiProviderService.ProviderHookValue.NullValue))
    }

    // ==== 评审 P2-1：账本路径全程 Long，负值拒绝为未知 ====

    @Test
    fun `usage values beyond int range are preserved as longs`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject(
                    """{"usage": {"input": 5000000000, "cachedInput": 2000000000, "output": 3000000000, "attempt": 1}}"""
                )
            )
        val usage = svc.extractUsage(decoded)!!
        assertEquals(5_000_000_000L, usage.input)
        assertEquals(2_000_000_000L, usage.cachedInput)
        assertEquals(3_000_000_000L, usage.output)
    }

    @Test
    fun `negative usage values are rejected as unknown`() {
        val svc = service()
        val decoded =
            ToolPkgJsAiProviderService.ProviderHookValue.ObjectValue(
                JSONObject("""{"usage": {"input": -5, "output": 10}}""")
            )
        val usage = svc.extractUsage(decoded)!!
        assertNull("negative input must be unknown", usage.input)
        assertEquals(10L, usage.output)
    }

    // ==== 真实 hook 层（评审 P2-1）====

    @Test
    fun `old protocol without attempt is one cumulative complete snapshot per report`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val text =
            runSendMessage(
                svc,
                runnerWith(
                    intermediates =
                        listOf(
                            """{"usage": {"input": 300, "output": 100}}""",
                        ),
                    final = """{"usage": {"input": 500, "output": 400}, "chunk": "done"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertEquals("done", text)
        // 旧协议：两次上报都是请求级累计完整快照，attempt 固定 1，绝不猜测递增
        assertEquals(2, reports.size)
        reports.forEach { r ->
            assertEquals(1, r.attempt)
            assertTrue(
                "old protocol report must be complete snapshot, was $r",
                r.completeSnapshot,
            )
        }
        assertEquals(300L, reports[0].input)
        assertEquals(500L, reports[1].input)
    }

    @Test
    fun `new protocol attempt numbers are forwarded and merged per attempt`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val text =
            runSendMessage(
                svc,
                runnerWith(
                    intermediates =
                        listOf(
                            """{"usage": {"input": 300, "output": 100, "attempt": 1}}""",
                            """{"usage": {"input": 500, "output": 400, "attempt": 2}}""",
                        ),
                    final = """{"chunk": "final"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertEquals("final", text)
        // 新协议：attempt 序号原样转发，不猜测、不覆盖
        assertEquals(2, reports.size)
        assertEquals(1, reports[0].attempt)
        assertEquals(2, reports[1].attempt)
        assertFalse("new protocol report is a partial update", reports[0].completeSnapshot)
        assertFalse(reports[1].completeSnapshot)
        assertEquals(300L, reports[0].input)
        assertEquals(500L, reports[1].input)
    }

    @Test
    fun `new protocol same attempt streaming updates stay on the same attempt`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        runSendMessage(
            svc,
            runnerWith(
                intermediates =
                    listOf(
                        """{"usage": {"input": 300, "output": 100, "attempt": 1}}""",
                        // 同 attempt 流式更新（只带 output）：不分配新 attempt
                        """{"usage": {"output": 150, "attempt": 1}}""",
                    ),
                final = """{"chunk": "ok"}""",
            ),
        ) { usage, attempt ->
            reports.add(report(usage, attempt))
        }
        assertEquals(2, reports.size)
        assertEquals(1, reports[0].attempt)
        assertEquals(1, reports[1].attempt)
        // 同 attempt 流式更新为部分快照：省略的 input 保留旧值（由上下文合并）
        assertFalse(reports[1].completeSnapshot)
    }

    @Test
    fun `final result usage is reported after intermediate usage`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        runSendMessage(
            svc,
            runnerWith(
                intermediates =
                    listOf(
                        """{"usage": {"input": 100, "output": 10, "attempt": 1}}""",
                    ),
                final = """{"usage": {"input": 120, "output": 25, "attempt": 1}}""",
            ),
        ) { usage, attempt ->
            reports.add(report(usage, attempt))
        }
        assertEquals(2, reports.size)
        // 最终结果 payload 的 usage 也必须上报（intermediate + final 都走同一通道）
        assertEquals(120L, reports[1].input)
        assertEquals(25L, reports[1].output)
        assertEquals(1, reports[1].attempt)
    }

    // ==== 评审 P1-6：新协议跨 attempt 不得继承全局 current 计数 ====

    @Test
    fun `new protocol attempt missing input does not inherit global counters`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val text =
            runSendMessage(
                svc,
                runnerWith(
                    intermediates =
                        listOf(
                            """{"usage": {"input": 100, "output": 50, "attempt": 1}}""",
                            // attempt 2 首次只上报 output：input 必须保持未知，
                            // 绝不能填入全局 currentInput（100）造成虚假累计
                            """{"usage": {"output": 10, "attempt": 2}}""",
                        ),
                    final = """{"chunk": "done"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertEquals("done", text)
        assertEquals(2, reports.size)
        assertEquals(1, reports[0].attempt)
        assertEquals(100L, reports[0].input)
        assertEquals(2, reports[1].attempt)
        assertEquals("input must be unknown for attempt 2", -1L, reports[1].input)

        // 账本聚合：attempt1 input 已知 + attempt2 input 未知 → 该分量保持未知，
        // 绝不把 100 继承为 200
        val ctx =
            com.ai.assistance.operit.data.stats.TokenStatRequestContext(
                eventId = "evt-toolpkg-attempt-gap",
                category = com.ai.assistance.operit.data.stats.TokenStatCategory.CHAT,
                configId = "cfg-1",
                provider = "TEST",
                model = "toolpkg-model",
                startedAtMs = 1000L,
            )
        reports.forEach { r ->
            ctx.onUsage(
                com.ai.assistance.operit.data.stats.ProviderUsageSnapshot(
                    uncachedInputTokens = if (r.input >= 0) r.input else null,
                    outputTokens = r.output,
                    cacheWriteSeparateBilling = false,
                    completeSnapshot = r.completeSnapshot,
                    source = "toolpkg_js",
                ),
                r.attempt,
            )
        }
        val aggregated = ctx.aggregatedUsage()!!
        assertNull("aggregated input must stay unknown, not fabricated", aggregated.uncachedInputTokens)
        assertEquals(60L, aggregated.outputTokens)
    }

    // ==== 聚焦修复：final 致命失败结果 ====

    @Test
    fun `final failure with usage reports usage once, propagates error, and emits no final text`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val (text, failure) =
            runSendMessageExpectFailure(
                svc,
                runnerWith(
                    intermediates = emptyList(),
                    final =
                        """{"usage": {"input": 80, "output": 9, "attempt": 1}, "success": false, "error": "denied"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        // 致命错误必须传播（stream collect 抛出，不吞成空结果）
        assertTrue(
            "fatal result must propagate error, failure=$failure text=<$text>",
            failure is IllegalStateException,
        )
        assertEquals("denied", failure?.message)
        // 最终失败结果里的 usage 先于致命检查被转发，且只解析/上报一次
        assertEquals(1, reports.size)
        assertEquals(80L, reports[0].input)
        assertEquals(9L, reports[0].output)
        assertEquals(1, reports[0].attempt)
        // fatal 结果不得发射最终文本
        assertEquals("", text)
    }

    @Test
    fun `final failure without usage emits no final text and propagates error`() {
        val svc = service()
        val (text, failure) =
            runSendMessageExpectFailure(
                svc,
                runnerWith(
                    intermediates = emptyList(),
                    final = """{"success": false, "error": "boom"}""",
                ),
            ) { _, _ ->
                error("usage callback must not fire without usage")
            }
        assertTrue("fatal result must propagate error, failure=$failure text=<$text>", failure is IllegalStateException)
        assertEquals("boom", failure?.message)
        assertEquals("", text)
    }

    // ==== 评审 P1-7：testConnection 的 usage 提取与 attempt 转发 ====

    private fun runTestConnection(
        svc: ToolPkgJsAiProviderService,
        runner: ToolPkgMainHookRunner,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, Int) -> Unit)?,
    ): Result<String> = runBlocking {
        svc.mainHookRunnerOverride = runner
        svc.testConnection(context = mock(Context::class.java), onUsageReported = onUsageReported)
    }

    @Test
    fun `test connection forwards intermediate and final usage like a normal request`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val result =
            runTestConnection(
                svc,
                runnerWith(
                    intermediates =
                        listOf(
                            """{"usage": {"input": 100, "output": 10, "attempt": 1}}""",
                            """{"usage": {"output": 25, "attempt": 1}}""",
                        ),
                    final = """{"usage": {"input": 120, "output": 25, "attempt": 1}, "success": true, "message": "ok"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertTrue(result.isSuccess)
        // 中间 + 最终结果都走同一 usage 提取/attempt 转发（P1-7）
        assertEquals(3, reports.size)
        assertEquals(1, reports[0].attempt)
        assertEquals(100L, reports[0].input)
        assertEquals(25L, reports[1].output)
        assertEquals(120L, reports[2].input)
        assertEquals(1, reports[2].attempt)
        assertFalse("new protocol report is a partial update", reports[2].completeSnapshot)
    }

    @Test
    fun `test connection failure still forwards usage before failing`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val result =
            runTestConnection(
                svc,
                runnerWith(
                    intermediates = listOf("""{"usage": {"input": 50, "output": 5, "attempt": 1}}"""),
                    final = """{"usage": {"input": 80, "output": 9, "attempt": 1}, "success": false, "error": "denied"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertTrue("connection must fail", result.isFailure)
        // 失败结果里的 usage 同样被转发（不丢）
        assertEquals(2, reports.size)
        assertEquals(80L, reports[1].input)
    }

    @Test
    fun `test connection forwards multiple attempts`() {
        val svc = service()
        val reports = mutableListOf<ReportedUsage>()
        val result =
            runTestConnection(
                svc,
                runnerWith(
                    intermediates =
                        listOf(
                            """{"usage": {"input": 100, "output": 10, "attempt": 1}}""",
                            """{"usage": {"input": 200, "output": 20, "attempt": 2}}""",
                        ),
                    final = """{"success": true, "message": "ok"}""",
                ),
            ) { usage, attempt ->
                reports.add(report(usage, attempt))
            }
        assertTrue(result.isSuccess)
        assertEquals(2, reports.size)
        assertEquals(1, reports[0].attempt)
        assertEquals(2, reports[1].attempt)
        assertEquals(200L, reports[1].input)
    }
}
