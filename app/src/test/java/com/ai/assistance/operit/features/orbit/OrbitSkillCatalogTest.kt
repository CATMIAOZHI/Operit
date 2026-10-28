package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitSkillCatalogTest {
    @Test fun largeCatalogCanBeSearchedAndPagedWithoutLoadingBodies() {
        val catalog = (1..105).associate { "skill-%03d".format(it) to "网页监控 task $it" }
        val preview = OrbitSkillCatalog.page(catalog, limit = 12)
        assertEquals(12, preview.entries.size)
        assertEquals(12, preview.nextOffset)
        assertEquals("skill-105", OrbitSkillCatalog.page(catalog, "网页 105").entries.single().name)
        val last = OrbitSkillCatalog.page(catalog, offset = 100)
        assertEquals(5, last.entries.size)
        assertNull(last.nextOffset)
        assertTrue(OrbitSkillCatalog.page(catalog, "absent").entries.isEmpty())
    }
    @Test fun metadataAndParametersAreBounded() {
        assertEquals(240, OrbitSkillCatalog.page(mapOf("x" to "a".repeat(1000))).entries.single().description.length)
        assertTrue(OrbitSkillCatalog.page(mapOf("x" to "a"), offset = Int.MAX_VALUE).entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { OrbitSkillCatalog.page(emptyMap(), limit = 21) }
        assertThrows(IllegalArgumentException::class.java) { OrbitSkillCatalog.page(emptyMap(), offset = -1) }
    }
}
