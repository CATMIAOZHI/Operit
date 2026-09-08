package com.ai.assistance.operit.data.stats

data class TokenActivityEventRow(
    val eventId: String = "",
    val startedAtMs: Long,
    val uncachedInputTokens: Long?,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long?,
    val totalInputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val reasoningIncludedInOutput: Boolean?,
    /** null = 旧行未声明，按保守默认 true（独立计费）处理。 */
    val cacheWriteSeparateBilling: Boolean? = null,
)
