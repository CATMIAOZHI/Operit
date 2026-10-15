package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One atomic journal owns the current persona and reviewable changes; Soul.md is its readable copy. */
internal class OrbitSoulRepository(private val directory: File, private val available: suspend () -> Boolean = { true }) {
    constructor(context: Context, orbitId: String) : this(OrbitPreferences.getInstance(context).orbitDir(orbitId), {
        OrbitPreferences.getInstance(context).current().orbits.any { it.id == orbitId && !it.deleting }
    })

    data class Version(val id: String, val body: String, val reason: String, val base: String,
        val state: String, val createdAt: Long, val restoredFrom: String = "", val source: String = "proposal")
    data class Snapshot(val current: Version, val history: List<Version>)
    companion object {
        const val MAX_CHARS = 24_000
        private val locks = ConcurrentHashMap<String, Mutex>()
        suspend fun removeDirectory(directory: File) = withContext(Dispatchers.IO) {
            locks.computeIfAbsent(directory.absolutePath) { Mutex() }.withLock { directory.deleteRecursively() }
        }
    }
    private val mutex = locks.computeIfAbsent(directory.absolutePath) { Mutex() }
    private val journal = File(directory, "soul-versions.json")
    private val document = File(directory, "Soul.md")

    private fun json(version: Version) = JSONObject().put("id", version.id).put("body", version.body)
        .put("reason", version.reason).put("base", version.base).put("state", version.state)
        .put("createdAt", version.createdAt).put("restoredFrom", version.restoredFrom).put("source", version.source)
    private fun version(value: JSONObject) = Version(value.getString("id"), value.getString("body"),
        value.getString("reason"), value.getString("base"), value.getString("state"),
        value.getLong("createdAt"), value.optString("restoredFrom"), value.optString("source", "proposal"))
    private fun atomic(file: File, content: String) {
        check(directory.mkdirs() || directory.isDirectory)
        val temp = File.createTempFile(".soul-", ".tmp", directory)
        try {
            temp.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun store(current: String, versions: List<Version>): Snapshot {
        val selected = versions.single { it.id == current }
        val pending = versions.filter { it.state == "pending" }
        val retained = (listOf(selected) + pending +
            versions.filter { it.id != current && it.state != "pending" }.sortedBy { it.createdAt }.takeLast(19)).distinctBy { it.id }
        atomic(journal, JSONObject().put("current", current)
            .put("versions", JSONArray(retained.map(::json))).toString())
        atomic(document, selected.body)
        return Snapshot(selected, retained)
    }
    private suspend fun read(): Snapshot {
        check(available()) { "This Orbit is being removed or no longer exists" }
        if (!journal.exists()) {
            check(document.isFile) { "Soul.md is missing; restore it before continuing this Orbit." }
            val initial = Version(UUID.randomUUID().toString(), document.readText().ifBlank {
                "You are the user's long-term personal companion."
            }, "", "",
                "approved", System.currentTimeMillis(), source = "import")
            return store(initial.id, listOf(initial))
        }
        val data = JSONObject(journal.readText())
        val array = data.getJSONArray("versions")
        val versions = (0 until array.length()).map { version(array.getJSONObject(it)) }
        val selected = versions.single { it.id == data.getString("current") }
        // Repair an interrupted mirror write. Only the atomic journal decides which version applies.
        if (!document.isFile || document.readText() != selected.body) atomic(document, selected.body)
        return Snapshot(selected, versions)
    }
    suspend fun seed(body: String) = withContext(Dispatchers.IO) { mutex.withLock {
        check(!journal.exists() && !document.exists()) { "An existing Orbit persona cannot be reseeded" }
        val initial = Version(UUID.randomUUID().toString(), body.ifBlank { "You are the user's long-term personal companion." },
            "", "", "approved", System.currentTimeMillis(), source = "seed")
        store(initial.id, listOf(initial))
    } }
    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) { mutex.withLock { read() } }
    suspend fun update(body: String, reason: String, expected: String): Version = withContext(Dispatchers.IO) { mutex.withLock {
        require(body.isNotBlank() && body.length <= MAX_CHARS && reason.length in 1..2000)
        val old = read()
        check(old.current.id == expected) { "Soul changed; read it again before updating" }
        val version = Version(UUID.randomUUID().toString(), body, reason, expected, "approved", System.currentTimeMillis(),
            source = "update")
        store(version.id, old.history.filter { it.state == "approved" } + version)
        version
    } }
    suspend fun restore(id: String, expected: String): Snapshot = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read()
        check(old.current.id == expected) { "Soul changed; reopen history before restoring" }
        val source = old.history.single { it.id == id && it.state == "approved" }
        val restored = Version(UUID.randomUUID().toString(), source.body, "",
            expected, "approved", System.currentTimeMillis(), source.id, source = "restore")
        store(restored.id, old.history + restored)
    } }
}
