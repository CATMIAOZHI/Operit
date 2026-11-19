package com.ai.assistance.operit.data.pricing

import org.junit.Assert.*
import org.junit.Test

class OfficialPricingCatalogTest {
    @Test fun `official source excludes reseller prices and preserves context tiers`() {
        val doc = ModelsDevPricingCatalog.build(
            """{"vendor/model":{"name":"Official Model"}}""",
            """{
              "vendor":{"models":{"model":{"cost":{"input":1,"output":2,
                "tiers":[{"tier":{"type":"context","size":200000},"input":3,"output":4,"cache_read":0}]}}}},
              "reseller":{"models":{"model":{"cost":{"input":0,"output":0}}}}
            }""",
            """{"vendor":"vendor"}""",
        )
        val entry = doc.entries.single()
        assertEquals("vendor/model", entry.model)
        assertEquals(1.0, entry.input!!, 0.0)
        assertNull(entry.cacheWrite)
        assertEquals(0.0, entry.contextTiers.single().cacheRead!!, 0.0)
        val index = OfficialPricingIndex(doc.entries)
        assertNotNull(index.lookup("some-route/vendor/model"))
        assertNotNull(index.lookup("route-vendor-model"))
        assertNotNull(index.lookup("Official Model"))
        assertNull(index.lookup("model-20260906"))
    }

    @Test fun `ambiguous short names are not assigned an arbitrary official price`() {
        fun entry(owner: String) = PricingCatalogEntry(owner, "$owner/model", "TOKEN", "USD",
            1.0, 1.0, null, 2.0, null, emptyList(), null, null)
        val index = OfficialPricingIndex(listOf(entry("a"), entry("b")))
        assertNull(index.lookup("model"))
        assertNotNull(index.lookup("a/model"))
        assertNull(index.lookup("proxy/model"))
    }

    @Test fun `official alias row prices its official model and resolves by the alias`() {
        val doc = ModelsDevPricingCatalog.build(
            """{"deepseek/deepseek-v4.1-flash-1017":{"name":"DeepSeek V4.1 Flash 1017"}}""",
            """{"deepseek":{"models":{"deepseek-flash":{"cost":{"input":0.15,"output":0.6}}}}}""",
            """{"deepseek":"deepseek"}""",
        )
        assertEquals("deepseek/deepseek-v4.1-flash-1017", doc.entries.single().model)
        val index = OfficialPricingIndex(doc.entries)
        assertEquals(0.15, index.lookup("deepseek-flash")!!.inputPricePerMillion, 0.0)
        assertNotNull(index.lookup("deepseek/deepseek-flash"))
    }

    private fun entry(model: String, input: Double) = PricingCatalogEntry(model.substringBefore('/'), model,
        "TOKEN", "USD", input, input, null, input * 4, null, emptyList(), null, null)

    @Test fun `a colon tag, a vendor prefix or a missing snapshot date still find the price`() {
        val index = OfficialPricingIndex(listOf(entry("deepseek/deepseek-v4.1-flash-1017", 0.15),
            entry("deepseek/deepseek-v4-flash", 0.1)))
        assertEquals(0.15, index.lookup("deepseek-v4.1-flash:1017")!!.inputPricePerMillion, 0.0)
        assertEquals(0.15, index.lookup("deepseek-v4.1-flash")!!.inputPricePerMillion, 0.0)
        assertEquals(0.15, index.lookup("deepseek/deepseek-v4.1-flash")!!.inputPricePerMillion, 0.0)
        // A tag naming no snapshot falls back to the plain name.
        assertEquals(0.1, index.lookup("deepseek-v4-flash:latest")!!.inputPricePerMillion, 0.0)
        // Another vendor's prefix does not borrow this price.
        assertNull(index.lookup("other/deepseek-v4.1-flash"))
        assertNull(index.lookup("other/deepseek-v4.1-flash:1017"))
        assertNull(index.lookup("other/deepseek-v4-flash:latest"))
        assertEquals(0.15, index.lookup("deepseek/deepseek-v4.1-flash:1017")!!.inputPricePerMillion, 0.0)
        // A named release the catalog does not list never takes another release's price.
        assertNull(index.lookup("deepseek/deepseek-v4.1-flash:9999"))
    }

    @Test fun `a name two vendors list with different prices stays unknown`() {
        val index = OfficialPricingIndex(listOf(entry("a/model", 1.0), entry("b/model", 2.0),
            entry("a/model-0101", 3.0)))
        assertNull(index.lookup("model"))
        assertNull(index.lookup("model:latest"))
    }

    @Test fun `a latest tag finds the listed latest price first`() {
        val index = OfficialPricingIndex(listOf(entry("google/gemini-flash-latest", 0.75),
            entry("mistral/mistral-medium-0101", 1.0), entry("mistral/mistral-medium-0601", 2.0),
            entry("mistral/mistral-medium-latest", 3.0)))
        assertEquals(0.75, index.lookup("google/gemini-flash:latest")!!.inputPricePerMillion, 0.0)
        assertEquals(3.0, index.lookup("mistral/mistral-medium:latest")!!.inputPricePerMillion, 0.0)
    }

    @Test fun `snapshots with different prices are not guessed between`() {
        val index = OfficialPricingIndex(listOf(entry("v/model-0101", 1.0), entry("v/model-0601", 2.0)))
        assertNull(index.lookup("model"))
        val same = OfficialPricingIndex(listOf(entry("v/model-0101", 1.0), entry("v/model-0601", 1.0)))
        assertEquals(1.0, same.lookup("model")!!.inputPricePerMillion, 0.0)
        // Only a dated snapshot counts, never another model sharing the start of the name.
        assertNull(OfficialPricingIndex(listOf(entry("v/model-pro", 1.0))).lookup("model"))
    }

    @Test fun `free models and the free service cost nothing`() {
        val free = com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
        assertTrue(free.getDefaultPricing("OPENCODE_ZEN_FREE:space-bunny").known)
        assertEquals(0.0, free.getDefaultPricing("OPENROUTER:vendor/some-model:free").outputPricePerMillion, 0.0)
        assertTrue(free.getDefaultPricing("OPENAI_GENERIC:mimo-v2.6-flash-free").known)
        free.installCatalog(PricingCatalogDocument(1, "t", "2026-11-20T00:00:00Z",
            listOf(entry("deepseek/deepseek-v4.1-flash-1017", 0.15))))
        assertEquals(0.0, free.getDefaultPricing("OPENROUTER:deepseek/deepseek-v4.1-flash:free").inputPricePerMillion, 0.0)
        assertEquals(0.15, free.getDefaultPricing("OPENAI_GENERIC:deepseek-v4.1-flash:1017").inputPricePerMillion, 0.0)
        free.installCatalog(PricingCatalogDocument(1, "empty", "2026-11-20T00:00:00Z", emptyList()))
        assertFalse(free.getDefaultPricing("OPENAI_GENERIC:freestyle-model").known)
    }
}
