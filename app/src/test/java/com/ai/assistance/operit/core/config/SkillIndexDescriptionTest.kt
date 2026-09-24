package com.ai.assistance.operit.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillIndexDescriptionTest {
    @Test fun `short descriptions are kept as written`() {
        assertEquals("Search arXiv papers by keyword.", skillIndexDescription("Search arXiv papers by keyword."))
        assertEquals("Trimmed.", skillIndexDescription("  Trimmed.  "))
    }

    @Test fun `long descriptions are truncated to the index limit`() {
        val long = "A comprehensive skill that lets the agent search arXiv for academic papers using keywords and authors."
        val indexed = skillIndexDescription(long)
        assertEquals(SKILL_INDEX_DESCRIPTION_LIMIT, indexed.length)
        assertTrue(indexed.endsWith("..."))
        assertEquals(long.take(SKILL_INDEX_DESCRIPTION_LIMIT - 3), indexed.removeSuffix("..."))
    }

    @Test fun `a description exactly at the limit is left intact`() {
        val exact = "x".repeat(SKILL_INDEX_DESCRIPTION_LIMIT)
        assertEquals(exact, skillIndexDescription(exact))
    }
}
