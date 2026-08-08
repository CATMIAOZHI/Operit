package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图表纯逻辑测试（P1-8 无障碍模型 + P2 折线分段）：
 * 只测可抽出的纯函数，不触碰 Compose UI / 仪器测试。
 */
class TokenStatsChartsTest {

    // ==== 无障碍上一/下一桶（P1-8，边界禁用） ====

    @Test
    fun `previous and next bucket indices respect bounds`() {
        // 上一桶
        assertEquals(0, previousBucketIndex(1, 3))
        assertEquals(1, previousBucketIndex(2, 3))
        assertNull(previousBucketIndex(0, 3)) // 已在最前 → 禁用
        assertNull(previousBucketIndex(1, 1)) // 单桶 → 禁用
        assertNull(previousBucketIndex(0, 0)) // 无桶 → 禁用

        // 下一桶
        assertEquals(1, nextBucketIndex(0, 3))
        assertEquals(2, nextBucketIndex(1, 3))
        assertNull(nextBucketIndex(2, 3)) // 已在最后 → 禁用
        assertNull(nextBucketIndex(0, 1)) // 单桶 → 禁用
        assertNull(nextBucketIndex(0, 0)) // 无桶 → 禁用
    }

    @Test
    fun `chart accessibility description reads summary and rows`() {
        // 无明细行：只读摘要
        assertEquals(
            "费用趋势，14:00，第 2 / 3 桶，合计 ¥1.0000",
            chartAccessibilityDescription("费用趋势，14:00，第 2 / 3 桶，合计 ¥1.0000", emptyList()),
        )
        // 有明细行：摘要 + 行
        assertEquals(
            "费用趋势，14:00，第 2 / 3 桶，合计 ¥1.0000：输出 ¥0.6000，输入 ¥0.4000",
            chartAccessibilityDescription(
                "费用趋势，14:00，第 2 / 3 桶，合计 ¥1.0000",
                listOf("输出 ¥0.6000", "输入 ¥0.4000"),
            ),
        )
    }

    // ==== 折线分段（P2：连接相邻点，null 断段） ====

    @Test
    fun `line segments connect adjacent points instead of segment start`() {
        val p0 = Offset(0f, 0f)
        val p1 = Offset(10f, 10f)
        val p2 = Offset(20f, 5f)
        // 回归：旧实现会得到 [p0->p1, p0->p2]，正确实现是 [p0->p1, p1->p2]
        assertEquals(listOf(p0 to p1, p1 to p2), lineSegments(listOf(p0, p1, p2)))
    }

    @Test
    fun `line segments break at null points`() {
        val p0 = Offset(0f, 0f)
        val p1 = Offset(10f, 10f)
        val p2 = Offset(20f, 5f)
        assertEquals(listOf(p1 to p2), lineSegments(listOf(p0, null, p1, p2)))
        // 全空 → 无线段
        assertEquals(emptyList<Pair<Offset, Offset>>(), lineSegments(listOf(null, null)))
    }

    @Test
    fun `lifetime token sum includes migrated baseline without overflow`() {
        val legacyTotal = saturatedTokenSum(15_530_991L, 13_717_376L, 320_485L)

        assertEquals(29_568_852L, legacyTotal)
        assertEquals(Long.MAX_VALUE, saturatedTokenSum(Long.MAX_VALUE, 1L))
        assertEquals(12L, includeLegacyValue(5L, 7L, true))
        assertEquals(5L, includeLegacyValue(5L, 7L, false))
    }

    @Test
    fun `token trend uses Rainytoken palette`() {
        assertEquals(Color(0xFFFFD1DC), RainyTokenCacheRead)
        assertEquals(Color(0xFFFF85A2), RainyTokenUncachedInput)
        assertEquals(Color(0xFFE91E63), RainyTokenOutput)
    }
}
