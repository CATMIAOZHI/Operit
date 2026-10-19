package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Forgetting closes an evidence generation before changing memory. A learner that already read the
 * old evidence cannot commit afterwards. This does not remove chat history or restrict explicit reads.
 */
class OrbitLearningBoundary(
    private val file: File,
    private val clock: () -> Long = { 0 },
    private val horizon: suspend () -> Long = { 0L }
) {
    data class Guard(val generation: Long = 0, val cutoff: Long = 0, val cutoffTimestamp: Long = 0)
    private data class State(val guard: Guard, val mutations: List<String>)
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private fun read(): State {
        if (!file.exists()) return State(Guard(), emptyList())
        val value = JSONObject(file.readText())
        val ids = value.optJSONArray("mutations") ?: JSONArray()
        return State(Guard(value.getLong("generation"), value.getLong("cutoff"), value.optLong("cutoffTimestamp")),
            (0 until ids.length()).map(ids::getString))
    }
    private fun write(state: State) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val temp = File.createTempFile(".learning-boundary-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use {
                it.write(JSONObject().put("generation", state.guard.generation)
                    .put("cutoff", state.guard.cutoff).put("mutations", JSONArray(state.mutations.takeLast(256)))
                    .put("cutoffTimestamp", state.guard.cutoffTimestamp)
                    .toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    suspend fun snapshot(): Guard = withContext(Dispatchers.IO) { mutex.withLock { read().guard } }
    suspend fun accepts(expected: Long, mutationId: String = ""): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { read().let { it.guard.generation == expected ||
            mutationId.isNotEmpty() && it.mutations.lastOrNull() == mutationId } }
    }
    suspend fun <T> learn(expected: Long, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(read().guard.generation == expected) { "Memory was corrected; discard this outdated learning result" }
            action()
        }
    }
    suspend fun <T> edit(mutationId: String, expected: Long, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(mutationId.isNotBlank())
            val state = read()
            if (mutationId != state.mutations.lastOrNull()) {
                check(state.guard.generation == expected && mutationId !in state.mutations) {
                    "Memory was corrected again; reopen it before editing"
                }
                write(State(Guard(state.guard.generation + 1, maxOf(state.guard.cutoff, horizon()),
                    maxOf(state.guard.cutoffTimestamp, clock())),
                    state.mutations + mutationId))
            }
            action()
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        suspend fun forSpace(context: Context, spaceId: String): OrbitLearningBoundary? {
            // createOrbitMemorySpace always assigns this namespace. Keep ordinary memory stores
            // independent of Orbit metadata; the prefix alone is never sufficient for ownership.
            if (!spaceId.startsWith("orbit_")) return null
            val owner = OrbitMemoryScope.privateOwner(context, spaceId) ?: return null
            val preferences = OrbitPreferences.getInstance(context)
            return OrbitLearningBoundary(File(preferences.orbitDir(owner), "learning-boundary.json"), System::currentTimeMillis) {
                // The global monotonic row ID is a conservative watermark, including old branches.
                AppDatabase.getDatabase(context).chatContentDao().memoryLearningHorizon()
            }
        }
        suspend fun guard(context: Context, spaceId: String): Guard =
            forSpace(context, spaceId)?.snapshot() ?: Guard()

        suspend fun promptRevision(context: Context, orbitId: String): String {
            val preferences = OrbitPreferences.getInstance(context)
            val orbit = preferences.current().orbits.single { it.id == orbitId }
            val generation = orbit.memorySpaceId?.let { guard(context, it).generation } ?: 0
            // "layout-v2": stable parts first, Soul/memory/user last; old snapshots rebuild once.
            return "${OrbitSoulRepository(context, orbitId).snapshot().current.id}:$generation:skills-v1:layout-v2:" +
                OrbitPromptFiles.revision(context, orbitId)
        }

        suspend fun <T> explicitEdit(context: Context, spaceId: String, action: suspend () -> T): T {
            val boundary = forSpace(context, spaceId) ?: return action()
            return boundary.edit(java.util.UUID.randomUUID().toString(), boundary.snapshot().generation, action)
        }

        suspend fun filter(context: Context, spaceId: String, chatId: String,
            messages: List<com.ai.assistance.operit.data.model.ChatMessage>): Pair<Guard?, List<com.ai.assistance.operit.data.model.ChatMessage>> {
            val boundary = forSpace(context, spaceId) ?: return null to messages
            val guard = boundary.snapshot()
            if (guard.generation == 0L) return guard to messages
            val dao = AppDatabase.getDatabase(context).chatContentDao()
            val allowed = messages.filter { it.timestamp > guard.cutoffTimestamp &&
                (dao.learningMessageId(chatId, it.timestamp) ?: 0) > guard.cutoff }
            return guard to allowed
        }
    }
}
