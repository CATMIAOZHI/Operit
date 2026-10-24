package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.resolveModelIndex
import org.junit.Assert.*
import org.junit.Test

class OrbitModelsTest {
    private fun task() = OrbitTaskEntity("task", "orbit", "chat", "Task", "Do it", "ASSIST",
        "", 60, 0, "CHANGES", "ACTIVE", 1, 0, "", "", 0, 0)

    @Test fun defaultsFollowExecutionLaneButAlignmentAndExplicitChoiceWin() {
        assertEquals(OrbitModels.MAIN, OrbitModels.taskRole(task()))
        assertEquals(OrbitModels.ASSISTANT, OrbitModels.taskRole(task().copy(useSubagent = true)))
        val alignment = task().copy(useSubagent = true, source = OrbitRoutines.ALIGNMENT_SOURCE)
        assertEquals(OrbitModels.MAIN, OrbitModels.taskRole(alignment))
        assertEquals(OrbitModels.ASSISTANT, OrbitModels.taskRole(alignment.copy(modelRole = OrbitModels.ASSISTANT)))
        assertEquals(OrbitModels.MAIN, OrbitModels.taskRole(task().copy(useSubagent = true, modelRole = OrbitModels.MAIN)))
    }

    @Test fun simpleModeIgnoresButPreservesOverridesAndAdvancedFallsBackPerPurpose() {
        val main = OrbitModelRef("account", "main")
        val helper = OrbitModelRef("account", "helper")
        val review = OrbitModelRef("other", "review")
        val simple = OrbitModelSettings(main = main, assistant = helper,
            overrides = mapOf(FunctionType.PERMISSION_REVIEWER.name to review))
        assertEquals(main, simple.selection(FunctionType.CHAT))
        assertEquals(helper, simple.selection(FunctionType.PERMISSION_REVIEWER))
        val advanced = simple.copy(advanced = true)
        assertEquals(review, advanced.selection(FunctionType.PERMISSION_REVIEWER))
        assertEquals(helper, advanced.selection(FunctionType.MEMORY))
        assertEquals(review, advanced.copy(advanced = false).overrides[FunctionType.PERMISSION_REVIEWER.name])
    }

    @Test fun absentAssistantDoesNotSilentlyUseMain() {
        val settings = OrbitModelSettings(main = OrbitModelRef("account", "expensive"))
        assertNull(settings.selection(FunctionType.MEMORY))
        assertNotNull(settings.selection(FunctionType.CHAT))
    }

    @Test fun pluginDefaultRemainsDistinctFromADeletedNamedModel() {
        val default = OrbitModelRef("plugin", "")
        assertEquals(0, default.resolveIndex("", pluginProvider = true))
        assertEquals(-1, default.resolveIndex("", pluginProvider = false))
        assertEquals(-1, default.resolveIndex("new-model", pluginProvider = true))
        assertEquals(-1, OrbitModelRef("plugin", "removed").resolveIndex("", pluginProvider = true))
        // The general function resolver uses null for unnamed plugin models.
        val mapping = default.mapping(default.resolveIndex("", pluginProvider = true))
        assertNull(mapping.modelName)
        assertEquals(0, mapping.resolveModelIndex("").modelIndex)
    }

    @Test fun deletedTaggedAndTombstonedOwnersNeverFallBackToMainApp() {
        val settings = OrbitSettings(chatOwners = mapOf("old-task" to "deleted"))
        listOf(
            Triple("deleted", "main-app-chat", null),
            Triple(null, "old-task", null),
            Triple(null, null, "old-task"),
        ).forEach { (tag, chat, caller) ->
            val error = assertThrows(IllegalStateException::class.java) {
                OrbitModels.resolveOwnerId(settings, tag, chat, caller, "Companion unavailable")
            }
            assertEquals("Companion unavailable", error.message)
        }
        assertNull(OrbitModels.resolveOwnerId(settings, null, "main-app-chat", null, "Unavailable"))
    }

    @Test fun deletingOwnerIsBlockedButPausedOwnerStillWorks() {
        val orbit = OrbitProfile("orbit", "card", "chat", "Name")
        val paused = OrbitSettings(orbits = listOf(orbit))
        assertEquals("orbit", OrbitModels.resolveOwnerId(paused, null, "chat", null, "Unavailable"))
        assertThrows(IllegalStateException::class.java) {
            OrbitModels.resolveOwnerId(paused.copy(orbits = listOf(orbit.copy(deleting = true))),
                "orbit", "chat", null, "Unavailable")
        }
    }
}
