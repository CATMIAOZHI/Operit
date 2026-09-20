package com.ai.assistance.operit.core.tools

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolCallRepairLogPageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pagesAreNewestFirstWithoutOverlap() {
        val file = temporary.newFile()
        file.writeText((1..65).joinToString("\n") { """{"occurredAt":$it}""" } + "\n\n")
        val first = ToolCallRepairLogger.readPage(file, 0)
        val second = ToolCallRepairLogger.readPage(file, 1)
        val last = ToolCallRepairLogger.readPage(file, 2)
        assertEquals(65, first.total)
        assertEquals(30, first.records.size)
        assertEquals("""{"occurredAt":65}""", first.records.first())
        assertEquals("""{"occurredAt":36}""", first.records.last())
        assertEquals("""{"occurredAt":35}""", second.records.first())
        assertEquals(5, last.records.size)
        assertEquals(65, (first.records + second.records + last.records).toSet().size)
        assertTrue(ToolCallRepairLogger.readPage(file, 3).records.isEmpty())
    }

    @Test fun missingLogIsAnEmptyPage() {
        assertEquals(ToolCallRepairLogger.Page(0, emptyList()),
            ToolCallRepairLogger.readPage(java.io.File(temporary.root, "missing.jsonl"), 0))
    }
}
