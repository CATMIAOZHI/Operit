package com.ai.assistance.operit.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentProfileRepositoryTest {
    private val default =
        AgentProfile(
            id = "explore",
            name = "Explore",
            description = "Built-in description",
            mode = AgentMode.SUBAGENT,
            systemPrompt = "Default prompt",
        )

    @Test
    fun restoredSettingsCannotReplaceStableBuiltInMetadata() {
        val saved =
            default.copy(
                name = "Injected name",
                description = "Injected description",
                mode = AgentMode.PRIMARY,
                systemPrompt = "  Custom prompt  ",
                modelConfigId = "  config-a  ",
                modelIndex = 2,
                hidden = true,
                enabled = false,
            )

        val restored = mergeAgentProfileSettings(mapOf(default.id to default), listOf(saved))
            .getValue(default.id)

        assertEquals(default.id, restored.id)
        assertEquals(default.name, restored.name)
        assertEquals(default.description, restored.description)
        assertEquals(default.mode, restored.mode)
        assertEquals(default.hidden, restored.hidden)
        assertEquals(default.enabled, restored.enabled)
        assertEquals("Custom prompt", restored.systemPrompt)
        assertEquals("config-a", restored.modelConfigId)
        assertEquals(2, restored.modelIndex)
    }

    @Test
    fun blankRestoredPromptFallsBackToDefaultAndUnknownProfilesAreIgnored() {
        val blankSaved = default.copy(systemPrompt = "   ")
        val unknown = default.copy(id = "custom", systemPrompt = "Unknown")

        val restored =
            mergeAgentProfileSettings(
                defaults = mapOf(default.id to default),
                restored = listOf(blankSaved, unknown),
            )

        assertEquals(setOf(default.id), restored.keys)
        assertEquals(default, restored.getValue(default.id))
    }

    @Test
    fun inheritedModelClearsIndexAndBoundModelNormalizesIndex() {
        val inherited =
            default.withEditableSettings(
                systemPrompt = " Prompt ",
                modelConfigId = " ",
                modelIndex = 7,
            )
        assertEquals("Prompt", inherited.systemPrompt)
        assertNull(inherited.modelConfigId)
        assertNull(inherited.modelIndex)

        val bound =
            default.withEditableSettings(
                systemPrompt = "Prompt",
                modelConfigId = " config-b ",
                modelIndex = -3,
            )
        assertEquals("config-b", bound.modelConfigId)
        assertEquals(0, bound.modelIndex)
    }

    @Test
    fun editablePromptCannotBeBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            default.withEditableSettings(
                systemPrompt = " ",
                modelConfigId = null,
                modelIndex = null,
            )
        }
    }
}
