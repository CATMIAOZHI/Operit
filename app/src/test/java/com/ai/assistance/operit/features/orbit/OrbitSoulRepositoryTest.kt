package com.ai.assistance.operit.features.orbit

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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

    @Test fun `competing edits cannot overwrite the current persona and updates retain no history`() = runBlocking {
        val repo = repository()
        val initial = repo.seed("Initial")
        val first = repo.update("First", "First change", initial.current.id)
        val accepted = repo.snapshot()
        assertTrue(runCatching { repo.update("Second", "Competing change", initial.current.id) }.isFailure)
        assertEquals(first, accepted.current)
        val second = repo.update("Second", "Next change", first.id)
        val stored = JSONObject(File(folder.root, "a/soul-versions.json").readText())
        assertEquals(second.id, stored.getString("current"))
        assertEquals(1, stored.getJSONArray("versions").length())
        assertEquals("Second", stored.getJSONArray("versions").getJSONObject(0).getString("body"))
        assertEquals(second, repository().snapshot().current)
    }

    @Test fun `saving unchanged content keeps the actual modification date and still checks the version`() = runBlocking {
        val repo = repository()
        val initial = repo.seed("Unchanged")
        assertEquals(initial.current, repo.update("Unchanged", "Save", initial.current.id))
        val changed = repo.update("Updated", "Change", initial.current.id)
        assertTrue(runCatching { repo.update("Updated", "Stale editor", initial.current.id) }.isFailure)
        assertEquals(changed, repo.snapshot().current)
    }

    @Test fun `legacy history is pruned without changing current version or choosing a pending proposal`() = runBlocking {
        val directory = folder.newFolder("history")
        val repo = OrbitSoulRepository(directory)
        val initial = repo.seed("Initial")
        val journal = File(directory, "soul-versions.json")
        val old = JSONObject(journal.readText()).getJSONArray("versions").getJSONObject(0)
        val current = repo.update("Current", "Accepted change", initial.current.id)
        val data = JSONObject(journal.readText())
        val selected = data.getJSONArray("versions").getJSONObject(0)
        val pending = JSONObject(selected.toString()).put("id", "pending").put("state", "pending").put("body", "Not accepted")
        data.put("versions", JSONArray().put(old).put(selected).put(pending))
        journal.writeText(data.toString())
        File(directory, "Soul.md").writeText("interrupted mirror")

        assertEquals(current, OrbitSoulRepository(directory).snapshot().current)
        assertEquals("Current", File(directory, "Soul.md").readText())
        val retained = JSONObject(journal.readText())
        assertEquals(current.id, retained.getString("current"))
        assertEquals(1, retained.getJSONArray("versions").length())
        // A caller holding the current version before migration can still update.
        repo.update("After migration", "New change", current.id)
        assertEquals("After migration", repo.snapshot().current.body)
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
