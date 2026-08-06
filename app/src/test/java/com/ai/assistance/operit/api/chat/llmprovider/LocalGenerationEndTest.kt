package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 本地 provider 生成结束顺序契约测试（评审 P2-3）：
 * 取消必须在工具缓冲转换/emit 之前判定——取消时无工具结果 emit、
 * 已实测 usage 保留、CANCELLED（UserCancellationException）传播。
 */
class LocalGenerationEndTest {

    @Test
    fun `cancel reports usage then throws without emitting tool result`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        var toolEmitted = false
        try {
            LocalGenerationEnd.end(
                cancelled = true,
                success = false,
                inputTokens = 300,
                outputTokens = 12,
                source = ProviderUsageNormalizer.SOURCE_LLAMA,
                cancelMessage = "cancelled by user",
                onUsageReported = { usage, attempt ->
                    assertEquals(1, attempt)
                    usageReports.add(usage)
                },
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
        var toolEmitted = false
        LocalGenerationEnd.end(
            cancelled = false,
            success = true,
            inputTokens = 100,
                outputTokens = 30,
            source = ProviderUsageNormalizer.SOURCE_MNN,
            cancelMessage = "cancelled",
            onUsageReported = { usage, _ -> usageReports.add(usage) },
            emitToolResult = { toolEmitted = true },
            failWith = { fail("success path must not fail") },
        )
        assertTrue("tool result must be emitted when not cancelled", toolEmitted)
        assertEquals(1, usageReports.size)
        assertEquals(100L, usageReports[0].uncachedInputTokens)
        assertEquals(30L, usageReports[0].outputTokens)
    }

    @Test
    fun `failure emits tool result reports usage then fails`() = runBlocking {
        val usageReports = mutableListOf<ProviderUsageSnapshot>()
        var toolEmitted = false
        try {
            LocalGenerationEnd.end(
                cancelled = false,
                success = false,
                inputTokens = 200,
                outputTokens = 5,
                source = ProviderUsageNormalizer.SOURCE_LLAMA,
                cancelMessage = "cancelled",
                onUsageReported = { usage, _ -> usageReports.add(usage) },
                emitToolResult = { toolEmitted = true },
                failWith = { throw IOException("inference failed") },
            )
            fail("failure must propagate")
        } catch (e: IOException) {
            assertEquals("inference failed", e.message)
        }
        assertTrue(toolEmitted)
        // 失败前已实测 usage 必须落账
        assertEquals(1, usageReports.size)
        assertEquals(200L, usageReports[0].uncachedInputTokens)
        assertEquals(5L, usageReports[0].outputTokens)
    }
}
