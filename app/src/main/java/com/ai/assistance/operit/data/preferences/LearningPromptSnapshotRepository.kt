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
    }

    /** Only a persisted successful summary starts a new prefix epoch. */
    suspend fun beginEpoch(summary: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            val epoch = LearnedSkillRepository.version(summary)
            if (data.optString("_epoch") != epoch) write(JSONObject().put("_epoch", epoch))
        }
    }

    class IncompatiblePrefixException : IllegalStateException()

    suspend fun bindSystem(candidate: String, revisions: Map<String, String>, protocol: String = "",
        lane: String = "main"): String =
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
                systems.put(lane, JSONObject().put("system", candidate).put("revisions", JSONObject(revisions)).put("protocol", protocol))
                write(data)
                candidate
            }
        }

    data class Status(val exists: Boolean, val needsRebuild: Boolean)

    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = read()
            val systems = data.optJSONObject("_systems") ?: return@withLock Status(false, false)
            val changed = systems.keys().asSequence().any { lane ->
                val system = systems.getJSONObject(lane)
                val saved = system.getJSONObject("revisions")
                system.optBoolean("incompatible") ||
                    revisions(context, saved.keys().asSequence().toList()).any { (key, value) -> saved.optString(key) != value }
            }
            Status(systems.length() > 0, changed)
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
