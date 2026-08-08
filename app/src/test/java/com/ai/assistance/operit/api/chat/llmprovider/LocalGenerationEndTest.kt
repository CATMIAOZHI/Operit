package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 本地 provider 生成结束顺序契约测试（评审 P2-3）：
 * 取消必须在工具缓冲转换/emit 之前判定——取消时无工具结果 emit、
 * 已实测 usage 保留、CANCELLED（UserCancellationException）传播；
 * 失败路径同样绝不 emit 工具缓冲（先上报 usage 再 failWith）。
 */
class LocalGenerationEndTest {

    @Test
    fun `cancel reports usage then throws without emitting tool result`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_LLAMA) { usage, attempt ->
            assertEquals(1, attempt)
            usageReports.add(usage)
        }
        var toolEmitted = false
        try {
            LocalGenerationEnd.end(
                cancelled = true,
                success = false,
                inputTokens = 300,
                outputTokens = 12,
                usageReporter = reporter,
                cancelMessage = "cancelled by user",
                emitToolResult = { toolEmitted = true },
                failWith = { fail("cancel path must not reach failWith") },
            )
            fail("cancellation must propagate")
        } catch (e: UserCancellationException) {
            assertEquals("cancelled by user", e.message)
        }
        // 取消时绝不 emit 不完整的工具 XML
        assertFalse("tool buffer must not be emitted on cancel", toolEmitted)
        // 已实测 usage 先上报
        assertEquals(1, usageReports.size)
        assertEquals(300L, usageReports[0].uncachedInputTokens)
        assertEquals(12L, usageReports[0].outputTokens)
    }

    @Test
    fun `success emits tool result and reports usage without throwing`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_MNN) { usage, _ -> usageReports.add(usage) }
        var toolEmitted = false
        LocalGenerationEnd.end(
            cancelled = false,
            success = true,
            inputTokens = 100,
                outputTokens = 30,
            usageReporter = reporter,
            cancelMessage = "cancelled",
            emitToolResult = { toolEmitted = true },
            failWith = { fail("success path must not fail") },
        )
        assertTrue("tool result must be emitted when not cancelled", toolEmitted)
        assertEquals(1, usageReports.size)
        assertEquals(100L, usageReports[0].uncachedInputTokens)
        assertEquals(30L, usageReports[0].outputTokens)
    }

    @Test
    fun `failure reports usage then fails without emitting tool result`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_LLAMA) { usage, _ -> usageReports.add(usage) }
        var toolEmitted = false
        try {
            LocalGenerationEnd.end(
                cancelled = false,
                success = false,
                inputTokens = 200,
                outputTokens = 5,
                usageReporter = reporter,
                cancelMessage = "cancelled",
                emitToolResult = { toolEmitted = true },
                failWith = { throw IOException("inference failed") },
            )
            fail("failure must propagate")
        } catch (e: IOException) {
            assertEquals("inference failed", e.message)
        }
        // 失败路径绝不转换/emit 不完整的工具 XML
        assertFalse("tool buffer must not be emitted on failure", toolEmitted)
        // 失败前已实测 usage 必须落账
        assertEquals(1, usageReports.size)
        assertEquals(200L, usageReports[0].uncachedInputTokens)
        assertEquals(5L, usageReports[0].outputTokens)
    }

    @Test
    fun `failure never emits tool result even if failWith returns normally`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_MNN) { usage, _ -> usageReports.add(usage) }
        var toolEmitted = false
        LocalGenerationEnd.end(
            cancelled = false,
            success = false,
            inputTokens = 80,
            outputTokens = 2,
            usageReporter = reporter,
            cancelMessage = "cancelled",
            emitToolResult = { toolEmitted = true },
            failWith = {},
        )
        // failWith 正常返回（未抛异常）时，失败路径也必须就此结束，绝不落入 emit
        assertFalse("tool buffer must never be emitted on failure", toolEmitted)
        assertEquals(1, usageReports.size)
        assertEquals(80L, usageReports[0].uncachedInputTokens)
    }

    @Test
    fun `coroutine cancellation reports latest usage once and still propagates`() = runBlocking {
        val reports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_LLAMA) { usage, _ -> reports += usage }
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            reporter.runReportingFinally({ 42 }, { 7 }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, reports.size)
        assertEquals(42L, reports.single().uncachedInputTokens)
        assertEquals(7L, reports.single().outputTokens)
        reporter.report(99, 99)
        assertEquals("reporter must be once-only", 1, reports.size)
    }

    @Test
    fun `native exception reports usage once without running success payload`() = runBlocking {
        val reports = mutableListOf<ProviderUsageSnapshot>()
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_MNN) { usage, _ -> reports += usage }
        var toolEmitted = false
        try {
            reporter.runReportingFinally({ 15 }, { 4 }) {
                throw IOException("native failure")
            }
            toolEmitted = true
        } catch (e: IOException) {
            assertEquals("native failure", e.message)
        }

        assertFalse(toolEmitted)
        assertEquals(1, reports.size)
        assertEquals(15L, reports.single().uncachedInputTokens)
        assertEquals(4L, reports.single().outputTokens)
    }

    @Test
    fun `usage callback failure cannot mask cancellation`() = runBlocking {
        val reporter = LocalUsageReporter(ProviderUsageNormalizer.SOURCE_LLAMA) { _, _ ->
            throw IOException("ledger unavailable")
        }
        try {
            LocalGenerationEnd.end(
                cancelled = true,
                success = false,
                usageReporter = reporter,
                inputTokens = 10,
                outputTokens = 2,
                cancelMessage = "cancelled",
                emitToolResult = { fail("cancel must not emit") },
                failWith = { fail("cancel must not use failure payload") },
            )
            fail("cancellation must propagate")
        } catch (e: UserCancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
