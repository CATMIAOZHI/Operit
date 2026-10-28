package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import org.junit.Assert.*
import org.junit.Test

class OrbitMemoryScopePolicyTest {
    @Test fun oldBranchesAndTasksInheritWithoutIncludingOtherConversations() {
        val parents = listOf(
            "task" to "branch", "branch" to "a", "a" to null,
            "b" to null, "b-task" to "b", "ordinary" to null,
        )
        assertEquals(setOf("a", "branch", "task"), orbitDescendants(listOf("a"), parents))
        assertEquals(emptySet<String>(), orbitDescendants(emptyList(), parents))
    }

    @Test fun retainedOwnershipStillCoversDescendantsAfterRootRemoval() {
        assertEquals(setOf("removed", "branch", "task"), orbitDescendants(listOf("removed"),
            listOf("branch" to "removed", "task" to "branch", "ordinary" to null)))
    }

    @Test fun malformedCycleCannotHangHistoryOrDeletion() {
        assertEquals(setOf("a", "b"), orbitDescendants(listOf("a"),
            listOf("a" to "b", "b" to "a", "unrelated" to null)))
    }

    @Test fun rememberedBranchUsesItsLiveOwnerAndDeletedOwnerIsNotReassigned() {
        val a = OrbitProfile("a", "card-a", "chat-a", "A")
        val b = OrbitProfile("b", "card-b", "chat-b", "B")
        val settings = OrbitSettings(orbits = listOf(a, b), chatOwners = mapOf("branch" to "a"))
        assertEquals(a, settings.ownerForChat("branch"))
        assertNull(settings.copy(orbits = listOf(b)).ownerForChat("branch"))
        assertEquals("a", settings.copy(orbits = listOf(b)).chatOwners["branch"])
    }
}
