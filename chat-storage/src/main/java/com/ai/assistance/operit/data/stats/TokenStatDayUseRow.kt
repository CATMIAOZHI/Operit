package com.ai.assistance.operit.data.stats

/**
 * 一个连接在一个本地日（[day] = yyyy-MM-dd，按结束时间、设备时区）内结束的请求合计，供 Orbit
 * 估算没记模型或缓存的调用：[calls] 是请求数，其中 [inputCalls] 个的总输入已知、合计 [inputTokens]
 * （与 Orbit 记输入同口径：优先 totalInputTokens，否则已知的 uncached + cached + 缓存写入）；
 * [cachedInputTokens] / [cacheKnownInputTokens] 是缓存命中与总输入都已知的请求的命中量和总输入。
 * token 合计只用来算比例，用浮点汇总，个别异常大值不会让整段查询溢出失败。
 */
data class TokenStatDayUseRow(
    val statIdentityId: String,
    val day: String,
    val calls: Long,
    val inputCalls: Long,
    val inputTokens: Double,
    val cachedInputTokens: Double,
    val cacheKnownInputTokens: Double,
)
