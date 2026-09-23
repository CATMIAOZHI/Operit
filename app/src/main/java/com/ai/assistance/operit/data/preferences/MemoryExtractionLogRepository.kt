package com.ai.assistance.operit.data.preferences

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MemoryExtractionLog(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val sourceChatId: String,
    val graph: Boolean,
    val notes: Boolean,
    val skills: Boolean,
    val status: String = "running",
    val proposals: Int = 0,
    val detail: String = "",
    val runId: String = "",
    val childChatId: String = "",
    val modelRounds: Int = 0,
    val toolCalls: Int = 0,
    /** False when no subagent ran, so the log row must not offer an audit view. */
    val reviewable: Boolean = true
)

/** Separate from approval history: bounded operational records, without conversation contents. */
class MemoryExtractionLogRepository internal constructor(root: File, profileId: String) {
    constructor(context: Context, profileId: String) : this(File(context.filesDir, "memory_extraction_logs"), profileId)
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
    private val file = File(root, MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private fun read(): List<MemoryExtractionLog> {
        if (!file.exists()) return emptyList()
        val data = JSONArray(file.readText())
        return (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            MemoryExtractionLog(obj.getString("id"), obj.getLong("startedAt"), obj.optLong("finishedAt"),
                obj.optString("sourceChatId"), obj.getBoolean("graph"), obj.getBoolean("notes"),
                obj.getBoolean("skills"), obj.getString("status"), obj.optInt("proposals"), obj.optString("detail"),
                obj.optString("runId"), obj.optString("childChatId"), obj.optInt("modelRounds"), obj.optInt("toolCalls"),
                obj.optBoolean("reviewable", true))
        }
    }
    suspend fun list(): List<MemoryExtractionLog> = withContext(Dispatchers.IO) {
        mutex.withLock { read().sortedByDescending { it.startedAt } }
    }
    suspend fun save(log: MemoryExtractionLog) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = JSONArray()
            (read().filterNot { it.id == log.id } + log).sortedByDescending { it.startedAt }.take(500).forEach {
                data.put(JSONObject().put("id", it.id).put("startedAt", it.startedAt).put("finishedAt", it.finishedAt)
                    .put("sourceChatId", it.sourceChatId).put("graph", it.graph).put("notes", it.notes)
                    .put("skills", it.skills).put("status", it.status).put("proposals", it.proposals).put("detail", it.detail)
                    .put("runId", it.runId).put("childChatId", it.childChatId)
                    .put("modelRounds", it.modelRounds).put("toolCalls", it.toolCalls)
                    .put("reviewable", it.reviewable))
            }
            file.parentFile!!.mkdirs()
            val temp = File.createTempFile(".extract-", ".tmp", file.parentFile)
            try {
                temp.outputStream().use { it.write(data.toString().toByteArray()); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            syncDirectory(file.parentFile!!)
        }
    }
    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock { Files.deleteIfExists(file.toPath()); Unit } }
}
