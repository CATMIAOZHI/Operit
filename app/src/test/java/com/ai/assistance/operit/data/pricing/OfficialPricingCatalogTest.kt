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
}
