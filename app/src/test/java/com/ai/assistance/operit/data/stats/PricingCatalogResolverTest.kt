package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.pricing.PricingCatalogDocument
import com.ai.assistance.operit.data.pricing.PricingCatalogEntry
import com.ai.assistance.operit.data.pricing.PricingCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingCatalogResolverTest {
    @Test
    fun `catalog exact match and explicit alias are provider scoped`() {
        val document = PricingCatalogDocument(
            schemaVersion = 1,
            revision = "resolver-test",
            generatedAt = "2026-08-24T00:00:00Z",
            entries = listOf(
                PricingCatalogEntry("provider-a", "model-a", "TOKEN", "USD", 1.0, 1.0, null, 2.0, null, listOf("alias"), null, null),
                PricingCatalogEntry("provider-b", "model-b", "TOKEN", "USD", 3.0, 3.0, null, 4.0, null, emptyList(), null, null),
            ),
        )
        DefaultModelPricingCollect.installCatalog(document)

        assertEquals(1.0, DefaultModelPricingCollect.getDefaultPricing("provider-a:model-a").inputPricePerMillion, 0.0)
        assertEquals(1.0, DefaultModelPricingCollect.getDefaultPricing("provider-a:alias").inputPricePerMillion, 0.0)
        // The same model name in another provider is not a valid fallback.
        assertFalse(DefaultModelPricingCollect.getDefaultPricing("provider-b:model-a").known)
    }

    @Test
    fun `null override fields inherit while zero overrides remain zero`() {
        val defaults = ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            pricePerRequest = 0.01,
            currency = PricingCurrency.USD,
            known = true,
        )
        val row = TokenPriceResolver.normalizedOverride(
            scope = TokenPriceResolver.SCOPE_PROVIDER_MODEL,
            provider = "provider-a",
            model = "model-a",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = "USD",
            inputPricePerMillion = 0.0,
            cachedInputPricePerMillion = null,
            outputPricePerMillion = null,
        )
        val resolved = TokenPriceResolver.resolve("provider-a", "model-a", null, listOf(row), null, defaults)
        assertEquals(0.0, resolved.inputPricePerMillion!!, 0.0)
        assertEquals(0.5, resolved.cachedInputPricePerMillion!!, 0.0)
        assertEquals(2.0, resolved.outputPricePerMillion!!, 0.0)
        assertTrue(resolved.known)
    }

    @Test
    fun `billing mode switch clears incompatible fields`() {
        val defaults = ModelPricingDefaults(BillingMode.TOKEN, 1.0, 2.0, 0.5, 0.01, PricingCurrency.USD, known = true)
        val row = TokenPriceResolver.normalizedOverride(
            TokenPriceResolver.SCOPE_PROVIDER_MODEL, "provider-a", "model-a", null,
            BillingMode.COUNT, "USD", pricePerRequest = 0.0,
        )
        val resolved = TokenPriceResolver.resolve("provider-a", "model-a", null, listOf(row), null, defaults)
        assertEquals(BillingMode.COUNT, resolved.billingMode)
        assertEquals(0.0, resolved.pricePerRequest!!, 0.0)
        assertEquals(null, resolved.inputPricePerMillion)
        assertTrue(resolved.known)
    }

    @Test
    fun `partial catalog entry cannot turn missing fields into free prices`() {
        val defaults = PricingCatalogJson.entryToDefaults(
            PricingCatalogEntry(
                provider = "provider-a",
                model = "partial",
                billingMode = "TOKEN",
                currency = "USD",
                input = 0.0,
                cacheRead = null,
                cacheWrite = null,
                output = null,
                perRequest = null,
                aliases = emptyList(),
                sourceUrl = null,
                verifiedAt = null,
            )
        )
        val outputOnly = TokenPriceResolver.normalizedOverride(
            scope = TokenPriceResolver.SCOPE_PROVIDER_MODEL,
            provider = "provider-a",
            model = "partial",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = "USD",
            outputPricePerMillion = 2.0,
        )

        val resolved = TokenPriceResolver.resolve(
            "provider-a",
            "partial",
            null,
            listOf(outputOnly),
            null,
            defaults,
        )

        assertEquals(0.0, resolved.inputPricePerMillion!!, 0.0)
        assertEquals(null, resolved.cachedInputPricePerMillion)
        assertEquals(2.0, resolved.outputPricePerMillion!!, 0.0)
        assertFalse(resolved.known)
    }
}
