package com.ai.assistance.operit.data.preferences

import org.junit.Assert.*
import org.junit.Test

class UserProfileSectionsTest {
    @Test fun `legacy document remains intact until explicitly serialized`() {
        val legacy = "# About me\n\n职业：开发者\n\n## Other\n保留原文\n"
        val sections = UserProfileSections.parse(legacy)
        assertEquals(legacy, sections.profile)
        assertEquals("", sections.preferences)
        assertTrue(sections.markdown().contains(legacy.trim()))
    }

    @Test fun `three categories round trip and targeted update preserves siblings`() {
        val original = UserProfileSections("开发者", "中文", "提交前询问")
        assertEquals(original, UserProfileSections.parse(original.markdown()))
        val changed = UserProfileSections.parse(original.markdown()).with("preferences", "简体中文")
        assertEquals("开发者", changed.profile)
        assertEquals("提交前询问", changed.interactionRules)
        assertEquals(changed, UserProfileSections.parse(changed.markdown()))
    }

    @Test fun `empty categories do not inject a headings-only document`() {
        assertEquals("", UserProfileSections().markdown())
        assertEquals(UserProfileSections(), UserProfileSections.parse(""))
        val rulesOnly = UserProfileSections(interactionRules = "先给结论")
        assertEquals(rulesOnly, UserProfileSections.parse(rulesOnly.markdown()))
    }

    @Test fun `headings inside fenced code are content`() {
        val source = UserProfileSections("```markdown\n## Preferences\n```\n\n说明", "中文")
        assertEquals(source, UserProfileSections.parse(source.markdown()))
    }

    @Test fun `ambiguous documents are not silently repartitioned`() {
        val source = "## Profile\nA\n## Preferences\nB\n## Profile\nC\n## Interaction Rules\nD"
        assertEquals(source, UserProfileSections.parse(source).profile)
    }

    @Test fun `legacy headings and unclosed fences survive category updates`() {
        listOf("# About me\n\n## Preferences\n中文", "A\n## Preferences\nB", "```text\nunfinished").forEach {
            val changed = UserProfileSections.parse(it).with("preferences", "简洁")
            val loaded = UserProfileSections.parse(changed.markdown())
            assertEquals(it, loaded.profile)
            assertEquals("简洁", loaded.preferences)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown section is rejected`() {
        UserProfileSections().with("typo", "text")
    }
}
