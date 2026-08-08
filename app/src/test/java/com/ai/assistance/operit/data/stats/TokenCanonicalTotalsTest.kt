package com.ai.assistance.operit.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * canonical 总 Token 推导（聚合器/活动/UI 共用同一纯 helper）：
 * - 权威 totalInputTokens 已知则用它（拆分未知也可表达输入量）；
 * - fallback 按 cacheWriteSeparateBilling 决定输入口径：OpenAI 非独立计费不重复
 *   cacheWrite，Anthropic 独立计费不漏 cacheWrite；
 * - reasoning 按 reasoningIncludedInOutput 决定是否补加（已包含/未声明不加）；
 * - 未知组件保持 unknown（返回 null），绝不把 null 当作 0；
 * - 饱和加法，Long 溢出钳制不回绕。
 */
class TokenCanonicalTotalsTest {

    private fun total(
        totalInput: Long? = null,
        uncached: Long? = null,
        cached: Long? = null,
        cacheWrite: Long? = null,
        separate: Boolean? = null,
        output: Long? = null,
        reasoning: Long? = null,
        reasoningIncluded: Boolean? = null,
    ): Long? =
        canonicalTotalTokens(
            totalInputTokens = totalInput,
            uncachedInputTokens = uncached,
            cachedInputTokens = cached,
            cacheWriteTokens = cacheWrite,
            cacheWriteSeparateBilling = separate,
            outputTokens = output,
            reasoningTokens = reasoning,
            reasoningIncludedInOutput = reasoningIncluded,
        )

    @Test
    fun `authoritative total input wins even when split is unknown`() {
        // OpenAI 兼容端点缺 prompt_tokens_details：拆分未知但总输入权威已知
        assertEquals(1500L, total(totalInput = 1000L, uncached = null, cached = null, output = 500L))
        // Gemini：cachedContentTokenCount 缺失同理
        assertEquals(1200L, total(totalInput = 700L, cached = null, output = 500L))
    }

    @Test
    fun `non separate billing never double counts cache write`() {
        // OpenAI：无 totalInput 时输入 = uncached + cached，cacheWrite 已含在输入内
        assertEquals(
            1000L,
            total(uncached = 500L, cached = 100L, cacheWrite = 50L, separate = false, output = 400L),
        )
        // 有权威 totalInput 时同样不再追加 cacheWrite
        assertEquals(
            1000L,
            total(totalInput = 600L, uncached = 500L, cached = 100L, cacheWrite = 50L, separate = false, output = 400L),
        )
        // cacheWrite 未知也不阻碍（非独立计费概念下该分量不影响总量）
        assertEquals(
            1000L,
            total(uncached = 500L, cached = 100L, cacheWrite = null, separate = false, output = 400L),
        )
    }

    @Test
    fun `separate billing counts cache write exactly once`() {
        // Anthropic：无 totalInput 时输入 = uncached + cached + cacheWrite
        assertEquals(
            1050L,
            total(uncached = 500L, cached = 100L, cacheWrite = 50L, separate = true, output = 400L),
        )
        // 权威 totalInput（= 三分量之和）直接使用，不得再加 cacheWrite（只计一次）
        assertEquals(
            1050L,
            total(totalInput = 650L, uncached = 500L, cached = 100L, cacheWrite = 50L, separate = true, output = 400L),
        )
        // 旧行未声明独立计费 → 保守默认 true，cacheWrite 计入（与费用重估同一边界）
        assertEquals(
            1050L,
            total(uncached = 500L, cached = 100L, cacheWrite = 50L, separate = null, output = 400L),
        )
    }

    @Test
    fun `reasoning added only when excluded from output`() {
        assertEquals(1000L, total(totalInput = 600L, output = 400L, reasoning = 50L, reasoningIncluded = true))
        assertEquals(1050L, total(totalInput = 600L, output = 400L, reasoning = 50L, reasoningIncluded = false))
        // null = 未声明 → 按“已包含”处理，避免重复收费
        assertEquals(1000L, total(totalInput = 600L, output = 400L, reasoning = 50L, reasoningIncluded = null))
    }

    @Test
    fun `unknown required component keeps total unknown`() {
        // fallback 输入拆分缺失 → 整体 unknown（不把 null 当 0）
        assertNull(total(uncached = 100L, cached = null, separate = false, output = 50L))
        // 独立计费下 cacheWrite 缺失 → unknown
        assertNull(total(uncached = 100L, cached = 20L, cacheWrite = null, separate = true, output = 50L))
        // 独立推理但 reasoning 未知 → 输出 unknown → 整体 unknown
        assertNull(total(totalInput = 100L, output = 50L, reasoning = null, reasoningIncluded = false))
        // 输出未知 → 整体 unknown
        assertNull(total(totalInput = 100L, output = null))
    }

    @Test
    fun `saturated addition never wraps negative`() {
        val saturated =
            total(
                totalInput = Long.MAX_VALUE,
                uncached = Long.MAX_VALUE,
                cached = Long.MAX_VALUE,
                output = Long.MAX_VALUE,
            )
        assertEquals(Long.MAX_VALUE, saturated)
    }
}
