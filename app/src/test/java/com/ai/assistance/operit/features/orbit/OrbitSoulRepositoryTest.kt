package com.ai.assistance.operit.features.orbit

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbitSoulRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repository(name: String = "a") = OrbitSoulRepository(File(folder.root, name))

    @Test fun `persona update immediately applies and survives restart without approval`() = runBlocking {
        val directory = folder.newFolder("legacy")
        File(directory, "Soul.md").writeText("Initial persona")
        val repo = OrbitSoulRepository(directory)
        val initial = repo.snapshot()
        repo.update("New persona", "User requested a calmer tone", initial.current.id)
        val accepted = repo.snapshot()
        assertEquals("New persona", accepted.current.body)
        assertEquals("New persona", File(directory, "Soul.md").readText())
        assertEquals(accepted, OrbitSoulRepository(directory).snapshot())
    }

    @Test fun `competing edits cannot overwrite an accepted persona and restoration is a new version`() = runBlocking {
        val repo = repository()
        val initial = repo.seed("Initial")
        val first = repo.update("First", "First change", initial.current.id)
        val accepted = repo.snapshot()
        assertTrue(runCatching { repo.update("Second", "Competing change", initial.current.id) }.isFailure)
        val restored = repo.restore(initial.current.id, accepted.current.id)
        assertEquals("Initial", restored.current.body)
        assertNotEquals(initial.current.id, restored.current.id)
        assertEquals(initial.current.id, restored.current.restoredFrom)
        assertTrue(restored.history.any { it.id == first.id })
    }

    @Test fun `journal repairs mirror after interruption without affecting another Orbit`() = runBlocking {
        val a = repository()
        val b = repository("b")
        val initial = a.seed("A")
        b.seed("B")
        a.update("Updated A", "Change", initial.current.id)
        File(folder.root, "a/Soul.md").writeText("interrupted mirror")
        assertEquals("Updated A", repository().snapshot().current.body)
        assertEquals("Updated A", File(folder.root, "a/Soul.md").readText())
        assertEquals("B", b.snapshot().current.body)
    }

    @Test fun `deleted Orbit cannot recreate its identity and existing identity cannot be reseeded`() = runBlocking {
        val directory = File(folder.root, "removed")
        var available = true
        val repo = OrbitSoulRepository(directory) { available }
        repo.seed("Original")
        assertTrue(runCatching { repo.seed("Replacement role card") }.isFailure)
        available = false
        OrbitSoulRepository.removeDirectory(directory)
        assertTrue(runCatching { repo.snapshot() }.isFailure)
        assertFalse(directory.exists())
    }
}
