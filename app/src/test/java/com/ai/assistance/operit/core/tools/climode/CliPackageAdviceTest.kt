package com.ai.assistance.operit.core.tools.climode

import com.ai.assistance.operit.core.tools.LocalizedText
import com.ai.assistance.operit.core.tools.PackageTool
import com.ai.assistance.operit.core.tools.ToolPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliPackageAdviceTest {
    private val advice = "Read companion memory before discussing this book."

    private fun catalog(): List<HiddenToolCatalogEntry> {
        val entries = linkedMapOf<String, HiddenToolCatalogEntry>()
        val toolPackage =
            ToolPackage(
                name = "reading_companion",
                description = LocalizedText.of("Reading companion"),
                tools =
                    listOf(
                        PackageTool("usage_advice", LocalizedText.of(advice), emptyList(), "", true),
                        PackageTool("get_context", LocalizedText.of("Reading context"), emptyList(), ""),
                        PackageTool("get_local_files", LocalizedText.of("Reading files"), emptyList(), ""),
                    ),
            )
        CliToolModeSupport.addPackageToolEntries(
            entries = entries,
            prefix = toolPackage.name,
            toolPackage = toolPackage,
            descriptionResolver = { it.description.resolve("en") },
            paramHintResolver = { it.name },
            sourceKind = HiddenToolSourceKind.PACKAGE,
            keywordTag = "package",
        )
        return entries.values.toList()
    }

    @Test
    fun searchReturnsPackageAdviceOnceWithExecutableProxyTargets() {
        val catalog = catalog()
        assertEquals(
            setOf("reading_companion:get_context", "reading_companion:get_local_files"),
            catalog.map { it.targetToolName }.toSet(),
        )
        val hits = CliToolModeSupport.searchHiddenToolCatalog(catalog, "reading_companion", 8)
        assertEquals(2, hits.size)
        val result = CliToolModeSupport.formatSearchResults("reading_companion", hits, true)
        assertEquals(1, Regex(Regex.escape(advice)).findAll(result).count())
        assertTrue(result.contains("Target: `reading_companion:get_context`"))
        assertFalse(result.contains("reading_companion:usage_advice"))
    }

    @Test
    fun unmatchedPackagesDoNotExposeTheirAdvice() {
        val unrelated =
            HiddenToolCatalogEntry(
                targetToolName = "weather:forecast",
                displayName = "weather:forecast",
                description = "Weather forecast",
                parameterHints = emptyList(),
                sourceKind = HiddenToolSourceKind.PACKAGE,
            )
        val hits =
            CliToolModeSupport.searchHiddenToolCatalog(catalog() + unrelated, "forecast", 8)
        assertEquals(listOf(unrelated), hits)
        assertFalse(CliToolModeSupport.formatSearchResults("forecast", hits, true).contains(advice))
    }
}
