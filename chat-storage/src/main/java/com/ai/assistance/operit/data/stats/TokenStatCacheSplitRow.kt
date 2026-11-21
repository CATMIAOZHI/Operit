package com.ai.assistance.operit.data.stats

/**
 * 一个连接在一段时间内、缓存命中与总输入都已知的请求合计：[cachedInputTokens] 是其中命中缓存的输入，
 * [inputTokens] 是总输入（含缓存命中和缓存写入，与 Orbit 记的输入同口径）。
 */
data class TokenStatCacheSplitRow(
    val statIdentityId: String,
    val cachedInputTokens: Long,
    val inputTokens: Long,
)
