package com.ai.assistance.operit.ui.features.tokenstats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingDraftMergeTest {
    @Test
    fun `blank cached override inherits cached base instead of overridden input`() {
        val base = draft(input = 1.0, cached = 0.5, output = 2.0)
        val overlay = draft(input = 3.0, cached = null, output = null)

        val merged = mergePricingDraft(base, overlay)

        assertEquals(3.0, merged.inputPricePerMillion!!, 0.0)
        assertEquals(0.5, merged.cachedInputPricePerMillion!!, 0.0)
        assertEquals(2.0, merged.outputPricePerMillion!!, 0.0)
    }

    @Test
    fun `automatic reference is hidden after switching to another billing mode`() {
        val tokenDefaults = ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            pricePerRequest = 0.0,
            currency = PricingCurrency.USD,
            known = true,
        )

        assertEquals(true, tokenDefaults.hasAutomaticReferenceFor(BillingMode.TOKEN))
        assertEquals(false, tokenDefaults.hasAutomaticReferenceFor(BillingMode.COUNT))
    }

    private fun draft(
        input: Double?,
        cached: Double?,
        output: Double?,
    ) = TokenStatsPriceOverrideDraft(
        scope = PriceOverrideScope.PROVIDER_MODEL,
        provider = "provider-a",
        model = "model-a",
        configId = null,
        billingMode = BillingMode.TOKEN,
        currency = PricingCurrency.USD,
        inputPricePerMillion = input,
        cachedInputPricePerMillion = cached,
        outputPricePerMillion = output,
    )
}
