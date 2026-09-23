package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.preferences.MemoryReviewChange
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.repository.RecallEvidenceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LearningCursor(
    val messageId: Long = 0, val byteOffset: Long = 0,
    val revision: String = "", val thinking: Boolean = false
) {
    fun json() = JSONObject().put("id",messageId).put("offset",byteOffset)
        .put("revision",revision).put("thinking",thinking)
    companion object {
        fun parse(obj: JSONObject?) = if (obj == null) LearningCursor() else LearningCursor(
            obj.getLong("id"),obj.getLong("offset"),obj.getString("revision"),obj.getBoolean("thinking"))
    }
}

/**
 * The completed source range and the entire change set share one atomic journal write.
 * A crash during export replays the same proposal IDs, never generates new approvals.
 */
internal class MemoryLearningJournal(private val context: Context, val profile: String, val chat: String) {
    private val root = File(context.filesDir, "memory_learning_progress")
    private val file = File(root, java.util.UUID.nameUUIDFromBytes("$profile\u0000$chat".toByteArray()).toString()+".json")
    private val reviews = MemoryReviewRepository(context,profile)
    private var state = if (file.exists()) JSONObject(file.readText()) else
        JSONObject().put("profile",profile).put("chat",chat).put("horizon",0)
    fun cursor(path: String) = LearningCursor.parse(state.optJSONObject(path))
    fun pending(path: String) = state.optBoolean("pending_$path")
    fun horizon() = state.optLong("horizon")
    fun rewind(messageId: Long) {
        listOf("notes","skills").forEach {
            val old=cursor(it)
            if (old.messageId>messageId || old.messageId==messageId && old.byteOffset>0)
                state.put(it,LearningCursor(messageId).json())
        }
        save()
    }
    private fun save() {
        root.mkdirs()
        val temp = File.createTempFile(".learning-", ".tmp", root)
        try {
            temp.outputStream().use { it.write(state.toString().toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        com.ai.assistance.operit.data.preferences.syncDirectory(file.parentFile!!)
    }
    fun enqueue(notes: Boolean, skills: Boolean, horizon: Long, restart: Boolean = false) {
        state.put("horizon",maxOf(horizon,horizon()))
        if (restart) {
            if (notes) state.put("notes",LearningCursor().json())
            if (skills) state.put("skills",LearningCursor().json())
        }
        if (notes) state.put("pending_notes",true)
        if (skills) state.put("pending_skills",true)
        save()
    }
    fun complete(paths: List<String>, next: LearningCursor, more: Boolean, changes: List<MemoryReviewChange>) {
        check(!state.has("export")) { "Previous batch must be exported first" }
        paths.forEach { state.put(it,next.json()).put("pending_$it",more) }
        state.put("export",JSONArray().apply { changes.forEach { put(reviews.toJson(it)) } })
        save()
    }
    /**
     * Stops retrying a range that can never be reviewed, keeping each cursor where it is so the next
     * normal trigger covers the same range again once the cause is gone.
     */
    fun abandon(paths: List<String>) {
        paths.forEach { state.put("pending_$it",false) }
        save()
    }
    suspend fun export(): List<String> {
        val array = state.optJSONArray("export") ?: return emptyList()
        val changes = (0 until array.length()).map { reviews.fromJson(array.getJSONObject(it)) }
        reviews.importCompletedBatch(changes)
        val failures = mutableListOf<String>()
        for (change in changes) {
            currentCoroutineContext().ensureActive()
            try {
                // A rejected or manually revised proposal is already settled and must stay so.
                val stored = reviews.list().first { it.id == change.id }
                if (stored.status in setOf("pending","applying")) reviews.applyAutomaticDecision(context,stored)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failures += learningFailureDetail("apply ${change.id}",e) }
        }
        // Failed writes remain visible in the review UI; evidence has still been reviewed.
        state.remove("export")
        save()
        return failures
    }
    companion object {
        fun deleteSpace(context: Context, profile: String) {
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.forEach { if (JSONObject(it.readText()).getString("profile")==profile) it.delete() }
        }
        /**
         * Drops the progress of a conversation that no longer exists, in every memory space. The
         * deleted conversation can never be reviewed again, so keeping the file would only make the
         * coordinator retry it on each launch.
         */
        suspend fun deleteChat(context: Context, chat: String) = withContext(Dispatchers.IO) {
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.forEach {
                    runCatching { if (JSONObject(it.readText()).optString("chat")==chat) it.delete() }
                }
        }
        fun pending(context: Context): List<Pair<String,String>> =
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.mapNotNull {
                    runCatching {
                        val obj=JSONObject(it.readText())
                        if (obj.optBoolean("pending_notes") || obj.optBoolean("pending_skills") || obj.has("export"))
                            obj.getString("profile") to obj.getString("chat") else null
                    }.getOrNull()
                }.orEmpty()
    }
}

internal class MemoryLearningSource(
    private val context: Context, private val dao: ChatContentDao, private val chat: String,
    private val horizon: Long, private val thinking: Boolean
) : java.io.Closeable {
    data class Batch(val text: String, val next: LearningCursor, val more: Boolean)
    private data class Prepared(val file: File, val revision: String)
    private val files = mutableMapOf<Long,Prepared>()

    suspend fun next(cursor: LearningCursor, budget: Int): Batch {
        require(budget >= 256)
        var position = cursor
        val output = StringBuilder()
        var remaining = budget
        while (remaining >= 200) {
            currentCoroutineContext().ensureActive()
            val row = dao.nextLearningSource(chat,position.messageId,horizon) ?: break
            val prepared = files[row.messageId] ?: run {
                val file = File.createTempFile("learning-source-", ".txt", context.cacheDir)
                try {
                    val page = file.bufferedWriter(Charsets.UTF_8).use { writer ->
                        RecallEvidenceReader.read({ dao.readLearningMessageBytes(row.messageId,it) },
                            row.sender=="ai",thinking,limit=1,onEvidence={ writer.write(it) })
                    } ?: error("Source message disappeared during review")
                    Prepared(file,page.revision).also { files[row.messageId]=it }
                } catch (e: Exception) { file.delete(); throw e }
            }
            var offset = if (position.messageId==row.messageId && position.revision==prepared.revision &&
                position.thinking==thinking) position.byteOffset else 0
            require(offset in 0..prepared.file.length()) { "Invalid learning source cursor" }
            val header = "\n[message_id=${row.messageId}; role=${row.sender}; source_byte_offset=$offset]\n"
            val headerBytes = header.toByteArray().size
            val bytes = ByteArray(minOf((remaining-headerBytes-1).coerceAtLeast(0).toLong(),
                prepared.file.length()-offset).toInt())
            RandomAccessFile(prepared.file,"r").use { it.seek(offset); it.readFully(bytes) }
            var size=bytes.size
            if (offset+size < prepared.file.length() && size>0) {
                var head=size-1
                while (head>0 && (bytes[head].toInt() and 0xc0)==0x80) head--
                val lead=bytes[head].toInt() and 0xff
                val width=when { lead>=0xf0 -> 4; lead>=0xe0 -> 3; lead>=0xc0 -> 2; else -> 1 }
                if (head+width>size) size=head
            }
            if (size>0) {
                output.append(header).append(String(bytes,0,size,Charsets.UTF_8)).append('\n')
                remaining-=headerBytes+size+1
            }
            offset+=size
            position = if (offset==prepared.file.length()) LearningCursor(row.messageId+1,thinking=thinking)
                else LearningCursor(row.messageId,offset,prepared.revision,thinking)
            if (offset<prepared.file.length()) break
        }
        return Batch(output.toString(),position,dao.nextLearningSource(chat,position.messageId,horizon)!=null)
    }
    override fun close() { files.values.forEach { it.file.delete() } }
}
