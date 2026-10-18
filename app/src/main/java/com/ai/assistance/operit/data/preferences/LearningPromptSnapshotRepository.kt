package com.ai.assistance.operit.data.preferences

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A persisted prefix belongs to exactly one chat. Tool reads remain fresh history entries. */
class LearningPromptSnapshotRepository internal constructor(root: File, chatId: String) {
    constructor(context: Context, chatId: String) : this(File(context.filesDir,"learning_prompt_snapshots"),chatId)
    companion object {
        private val locks = ConcurrentHashMap<String,Mutex>()
        private const val REVISION_PREFS = "system_prompt_revisions"
        fun markChanged(context: Context, key: String) {
            context.getSharedPreferences(REVISION_PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, java.util.UUID.randomUUID().toString()).apply()
        }
        fun revisions(context: Context, keys: Collection<String>): Map<String, String> {
            val prefs = context.getSharedPreferences(REVISION_PREFS, Context.MODE_PRIVATE)
            return keys.associateWith { prefs.getString(it, "").orEmpty() }
        }
    }
    private val file = File(root,LearnedSkillRepository.version(chatId)+".json")
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private fun read() = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    private fun write(data: JSONObject) {
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile(".snapshot-", ".tmp", file.parentFile)
        try {
            tmp.outputStream().use { it.write(data.toString().toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        syncDirectory(file.parentFile!!)
    }

    /** Only a persisted successful summary starts a new prefix epoch. */
    suspend fun bindMemoryScope(scope: String, identityRevision: String = "") = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            if (data.optString("_memory_scope", "global") != scope ||
                data.optString("_identity_revision") != identityRevision) {
                write(JSONObject().put("_memory_scope", scope).put("_identity_revision", identityRevision))
            }
        }
    }

    suspend fun beginEpoch(summary: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            val epoch = LearnedSkillRepository.version(summary)
            if (data.optString("_epoch") != epoch)
                write(JSONObject().put("_epoch", epoch).put("_memory_scope", data.optString("_memory_scope", "global"))
                    .put("_identity_revision", data.optString("_identity_revision")))
        }
    }

    class IncompatiblePrefixException : IllegalStateException()

    /**
     * [sections] are labelled parts of [candidate], kept beside it so a page can show what one
     * layer put into the prompt. They are saved only with a new prefix and never change it.
     */
    suspend fun bindSystem(candidate: String, revisions: Map<String, String>, protocol: String = "",
        lane: String = "main", sections: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val data = read()
                val systems = data.optJSONObject("_systems") ?: JSONObject().also { data.put("_systems", it) }
                val saved = systems.optJSONObject(lane)
                if (saved != null) {
                    if (saved.optString("protocol") != protocol) {
                        saved.put("incompatible", true)
                        write(data)
                        throw IncompatiblePrefixException()
                    }
                    if (saved.optBoolean("incompatible")) { saved.put("incompatible", false); write(data) }
                    return@withLock saved.getString("system")
                }
                systems.put(lane, JSONObject().put("system", candidate).put("revisions", JSONObject(revisions))
                    .put("protocol", protocol).put("sections", JSONObject(sections))
                    .put("createdAt", System.currentTimeMillis()))
                write(data)
                candidate
            }
        }

    /** A system prompt this chat already sent, with the sections saved beside it. */
    data class SavedSystem(val system: String, val sections: Map<String, String>, val protocol: String, val createdAt: Long)

    /**
     * The system prompt this chat uses for text chat, or null before one is built. A chat also keeps
     * prompts for other kinds of turns (voice in the floating window, a proxy card); one of those is
     * shown only when there is no text chat prompt.
     */
    suspend fun latestSystem(roleCardId: String? = null): SavedSystem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val systems = read().optJSONObject("_systems") ?: return@withLock null
            // Lane: roleCard|proxyCard|promptFunctionType|groupHint.
            fun isTextChat(lane: String) = lane.split('|').let {
                (roleCardId == null || it.getOrNull(0) == roleCardId) &&
                    it.getOrNull(1).isNullOrEmpty() && it.getOrNull(2) == "CHAT" && it.getOrNull(3) != "true"
            }
            val lanes = systems.keys().asSequence().toList()
            // With a role card given, only that card's text chat prompt counts.
            (lanes.filter(::isTextChat).ifEmpty { if (roleCardId == null) lanes else emptyList() })
                .map { systems.getJSONObject(it) }
                .maxByOrNull { it.optLong("createdAt") }
                ?.let { saved ->
                    val sections = saved.optJSONObject("sections")
                    SavedSystem(
                        system = saved.getString("system"),
                        sections = sections?.keys()?.asSequence()?.associateWith { sections.getString(it) }.orEmpty(),
                        protocol = saved.optString("protocol"),
                        createdAt = saved.optLong("createdAt"),
                    )
                }
        }
    }

    data class Status(val exists: Boolean, val needsRebuild: Boolean)

    suspend fun markChatChanged() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            if (data.has("_systems")) write(data.put("_changed", true))
        }
    }

    /** Only deterministic configuration inputs, never dynamic prompt-hook output. */
    suspend fun trackConfiguration(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            val inputs = data.optJSONObject("_configuration") ?: JSONObject().also { data.put("_configuration", it) }
            val fingerprint = LearnedSkillRepository.version(value)
            if (inputs.optString(key) == fingerprint) return@withLock
            // Legacy snapshots have no input baseline: conservatively ask once, never rebuild.
            if (data.has("_systems"))
                data.put("_changed", true)
            inputs.put(key, fingerprint)
            write(data)
        }
    }

    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            val systems = data.optJSONObject("_systems") ?: return@withLock Status(false, false)
            val changed = systems.keys().asSequence().any { lane ->
                val system = systems.getJSONObject(lane)
                val saved = system.getJSONObject("revisions")
                system.optBoolean("incompatible") || system.optBoolean("changed") ||
                    revisions(context, saved.keys().asSequence().toList()).any { (key, value) -> saved.optString(key) != value }
            }
            Status(systems.length() > 0, changed || data.optBoolean("_changed"))
        }
    }

    suspend fun getOrPut(key: String, loader: suspend () -> String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            if(data.has(key)) return@withLock data.getString(key)
            val value = loader()
            data.put(key,value)
            write(data)
            value
        }
    }
    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock { Files.deleteIfExists(file.toPath()); Unit } }
}
