package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.pricing.ContextPriceTier
import com.ai.assistance.operit.data.pricing.PricingCatalogDocument
import com.ai.assistance.operit.data.pricing.PricingCatalogEntry
import com.ai.assistance.operit.data.pricing.PricingCatalogJson
import org.junit.Assert.*
import org.junit.Test

internal fun installStatsTestCatalog(tiers: List<ContextPriceTier> = emptyList()) {
    DefaultModelPricingCollect.installCatalog(PricingCatalogDocument(
        1, "test-defaults", "2026-09-06T00:00:00Z",
        listOf(PricingCatalogEntry("openai", "openai/gpt-4o-2024-11-20", "TOKEN", "USD",
            1.5, 1.5, null, 6.0, null, emptyList(), null, null, tiers)),
    ))
}

class ContextTierPricingTest {
    private val entry = PricingCatalogEntry(
        "vendor", "vendor/model", "TOKEN", "USD", 1.0, 0.1, 1.25, 2.0,
        null, emptyList(), null, null,
        listOf(ContextPriceTier(200_000, 2.0, 4.0, 0.2, 2.5),
            ContextPriceTier(400_000, 3.0, 6.0, 0.3, 3.75)),
    )
    private fun resolve(tokens: Long?, overrides: List<com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity> = emptyList()) =
        TokenPriceResolver.resolve("my-proxy", "model", "config", overrides, null,
            PricingCatalogJson.entryToDefaults(entry), tokens, selectContextTier = true)

    @Test fun `threshold is strict and highest matching tier prices the whole request`() {
        assertEquals(1.0, resolve(200_000).inputPricePerMillion!!, 0.0)
        assertEquals(2.0, resolve(200_001).inputPricePerMillion!!, 0.0)
        assertEquals(3.0, resolve(400_001).inputPricePerMillion!!, 0.0)
        val usage = TokenUsageInput(uncachedInputTokens = 200_001, cachedInputTokens = 0,
            cacheWriteTokens = 0, outputTokens = 100)
        assertEquals((200_001 * 2.0 + 100 * 4.0) / 1_000_000,
            TokenCostCalculator.computeCost(usage, resolve(200_001)).amount!!, 1e-12)
    }

    @Test fun `context includes cache writes once and excludes output`() {
        val usage = TokenUsageInput(uncachedInputTokens = 100_000, cachedInputTokens = 80_000,
            cacheWriteTokens = 30_000, totalInputTokens = 210_000, outputTokens = 500_000)
        assertEquals(210_000L, TokenCostCalculator.contextInputTokens(usage))
        assertEquals(210_000L, TokenCostCalculator.contextInputTokens(usage.copy(totalInputTokens = null)))
        assertEquals(180_000L, TokenCostCalculator.contextInputTokens(
            usage.copy(totalInputTokens = null, cacheWriteSeparateBilling = false)))
        assertNull(TokenCostCalculator.contextInputTokens(usage.copy(cacheWriteTokens = null)))
        assertNull(TokenCostCalculator.contextInputTokens(usage, attemptCount = 2))
    }

    @Test fun `unknown context stays unknown but explicit manual prices still win`() {
        assertFalse(resolve(null).known)
        val manual = TokenPriceResolver.normalizedOverride(
            TokenPriceResolver.SCOPE_CONFIG, "my-proxy", "model", "config",
            BillingMode.TOKEN, "USD", inputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0, outputPricePerMillion = 0.0,
        )
        assertTrue(resolve(null, listOf(manual)).known)
        assertEquals(0.0, resolve(500_000, listOf(manual)).inputPricePerMillion!!, 0.0)
        val partial = manual.copy(outputPricePerMillion = null)
        assertEquals(6.0, resolve(500_000, listOf(partial)).outputPricePerMillion!!, 0.0)
    }
}
