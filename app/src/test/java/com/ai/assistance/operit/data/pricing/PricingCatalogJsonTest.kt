package com.ai.assistance.operit.data.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PricingCatalogJsonTest {
    private fun entry(
        provider: String = "openai",
        model: String = "gpt-test",
        billingMode: String = "TOKEN",
        input: Double? = 0.0,
        cacheRead: Double? = 0.0,
        output: Double? = 0.0,
        perRequest: Double? = null,
    ) = PricingCatalogEntry(
        provider = provider,
        model = model,
        billingMode = billingMode,
        currency = "USD",
        input = input,
        cacheRead = cacheRead,
        cacheWrite = null,
        output = output,
        perRequest = perRequest,
        aliases = emptyList(),
        sourceUrl = null,
        verifiedAt = null,
    )

    private fun document(vararg entries: PricingCatalogEntry) = PricingCatalogDocument(
        schemaVersion = 1,
        revision = "test",
        generatedAt = "2026-08-24T00:00:00Z",
        entries = entries.toList(),
    )

    @Test
    fun `explicit zero is a known free token price`() {
        val defaults = PricingCatalogJson.entryToDefaults(entry())
        assertEquals(0.0, defaults.inputPricePerMillion, 0.0)
        assertEquals(true, defaults.known)
    }

    @Test
    fun `missing catalog field stays distinct from explicit zero`() {
        val defaults = PricingCatalogJson.entryToDefaults(
            entry(input = 0.0, cacheRead = null, output = null)
        )
        assertEquals(true, defaults.hasInputPrice)
        assertEquals(false, defaults.hasCachedInputPrice)
        assertEquals(false, defaults.hasOutputPrice)
        assertEquals(false, defaults.known)
    }

    @Test
    fun `duplicate provider model is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingCatalogJson.validate(document(entry(), entry(model = " GPT-TEST ")))
        }
    }

    @Test
    fun `alias cannot shadow an exact model declared later`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingCatalogJson.validate(
                document(
                    entry(model = "first").copy(aliases = listOf("second")),
                    entry(model = "second"),
                )
            )
        }
    }

    @Test
    fun `count entries require only per request and reject token fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingCatalogJson.validate(document(entry(billingMode = "COUNT", input = null, cacheRead = null, output = null, perRequest = 0.0).copy(cacheWrite = 0.0)))
        }
        val count = entry(billingMode = "COUNT", input = null, cacheRead = null, output = null, perRequest = 0.0)
        assertEquals(true, PricingCatalogJson.entryToDefaults(count).known)
    }
}
