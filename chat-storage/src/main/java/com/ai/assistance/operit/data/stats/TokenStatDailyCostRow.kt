package com.ai.assistance.operit.data.stats

/**
 * One local day's priced calls in one price currency: what they cost, and their tokens counted
 * the way Orbit counts them (all input, cache included, plus output with reasoning billed apart),
 * both added up as floating point so large sums cannot overflow.
 */
data class TokenStatDailyCostRow(
    val day: String,
    val currency: String,
    val cost: Double,
    val tokens: Double,
)
