package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SkillDraft(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val sourceChatId: String,
    val createdAt: Long,
    val status: String = "pending"
)

/** Accept only a small procedural draft. No model-produced files or scripts are executed. */
internal fun parseSkillDrafts(value: Any?, chatId: String): List<SkillDraft> {
    val array = value as? JSONArray ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.opt(i) as? JSONObject ?: return@mapNotNull null
        val name = (obj.opt("name") as? String)?.trim().orEmpty()
        val description = (obj.opt("description") as? String)?.trim().orEmpty()
        val body = (obj.opt("body") as? String)?.trim().orEmpty()
        if (!Regex("[a-z][a-z0-9-]{2,63}").matches(name) ||
            description.length !in 1..240 || description.contains('\n') ||
            body.length !in 50..6000) return@mapNotNull null
        SkillDraft(name, name, description, body, chatId, System.currentTimeMillis())
    }.take(1)
}

data class MemoryReviewChange(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val description: String = "",
    val before: String = "",
    val baseVersion: String = "",
    val addition: String = "",
    val sourceChatId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val reviewedAt: Long = 0,
    val reviewer: String = "",
    val reason: String = "",
    val audits: String = "",
    val path: String = "SKILL.md",
    val operation: String = "write",
    val automatic: Boolean = false
)

class MemoryReviewRepository internal constructor(private val root: File, val profileId: String) {
    constructor(context: Context, profileId: String) : this(File(context.filesDir, "memory_reviews"), profileId)
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val skillInstallMutex = Mutex()
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private val file = File(root, "${hash(profileId)}.json")
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private fun read(): List<MemoryReviewChange> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            MemoryReviewChange(obj.getString("id"), obj.getString("kind"), obj.getString("title"),
                obj.getString("body"), obj.optString("description"), obj.optString("before"),
                obj.optString("baseVersion"), obj.optString("addition"), obj.optString("sourceChatId"),
                obj.getLong("createdAt"), obj.getString("status"), obj.optLong("reviewedAt"),
                obj.optString("reviewer"), obj.optString("reason"), obj.optString("audits"),
                obj.optString("path","SKILL.md"),obj.optString("operation","write"),obj.optBoolean("automatic"))
        }
    }
    private fun write(items: List<MemoryReviewChange>) {
        root.mkdirs()
        val json = JSONArray().apply { items.forEach { d ->
            put(toJson(d))
        } }
        val temp = File.createTempFile(".draft-", ".tmp", root)
        try {
            temp.outputStream().use { out -> out.write(json.toString().toByteArray()); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    fun toJson(d: MemoryReviewChange): JSONObject = JSONObject().put("id", d.id).put("kind", d.kind)
        .put("title", d.title).put("body", d.body).put("description", d.description).put("before", d.before)
        .put("baseVersion", d.baseVersion).put("addition", d.addition).put("sourceChatId", d.sourceChatId)
        .put("createdAt", d.createdAt).put("status", d.status).put("reviewedAt", d.reviewedAt)
        .put("reviewer", d.reviewer).put("reason", d.reason).put("audits", d.audits)
        .put("path",d.path).put("operation",d.operation).put("automatic",d.automatic)

    suspend fun list(): List<MemoryReviewChange> = withContext(Dispatchers.IO) {
        mutex.withLock { read().sortedByDescending { maxOf(it.createdAt, it.reviewedAt) } }
    }
    suspend fun propose(change: MemoryReviewChange, onCreated: () -> Unit = {}): MemoryReviewChange = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = read().toMutableList()
            items.find { it.status in setOf("pending", "applying") &&
                it.kind == change.kind && it.title == change.title && it.body == change.body &&
                it.description == change.description && it.baseVersion == change.baseVersion &&
                it.addition == change.addition && it.path==change.path && it.operation==change.operation &&
                it.automatic==change.automatic }?.let { return@withLock it }
            require(items.count { it.status in setOf("pending", "applying") } < 30) { "Pending review limit reached" }
            val created = change.copy(id = java.util.UUID.randomUUID().toString())
            items.add(created)
            write(items)
            onCreated()
            created
        }
    }

    suspend fun proposeSkill(draft: SkillDraft, onCreated: () -> Unit = {}): MemoryReviewChange = propose(MemoryReviewChange(
        id = hash("skill:${draft.name}:${draft.description}:${draft.body}"),
        kind = "skill", title = draft.name, description = draft.description, body = draft.body,
        sourceChatId = draft.sourceChatId
    ), onCreated)

    suspend fun proposeSkillFile(name: String, path: String, before: LearnedSkillRepository.Snapshot,
        body: String, remove: Boolean = false, automatic: Boolean = false, sourceChatId: String = "",
        onCreated: () -> Unit = {}): MemoryReviewChange = propose(MemoryReviewChange(
            id = "",kind="skill_file",title=name,body=body,before=before.text,baseVersion=before.version,
            path=path,operation=if(remove) "remove" else "write",automatic=automatic,sourceChatId=sourceChatId
        ),onCreated)

    suspend fun proposeUser(before: String, after: String, sourceChatId: String = "",
        onCreated: () -> Unit = {}) = propose(MemoryReviewChange(id="", kind="user",
        title="user.md",body=after,before=before,baseVersion=LearnedSkillRepository.version(before),
        sourceChatId=sourceChatId),onCreated)
    suspend fun proposeSkillDeletion(name: String, before: LearnedSkillRepository.Snapshot, sourceChatId: String="",
        onCreated: () -> Unit = {}) = propose(MemoryReviewChange(id="",kind="skill_delete",title=name,body="",
        before=before.text,baseVersion=before.version,sourceChatId=sourceChatId,operation="delete"),onCreated)

    suspend fun proposeNotes(
        before: MemoryNotesRepository.Snapshot, after: String, addition: String = "", sourceChatId: String = "",
        onCreated: () -> Unit = {}
    ): MemoryReviewChange = propose(MemoryReviewChange(
        id = hash("notes:${before.version}:$after"), kind = "notes", title = "memory.md", body = after,
        before = before.markdown, baseVersion = before.version, addition = addition, sourceChatId = sourceChatId
    ), onCreated)

    suspend fun audit(id: String, note: String, reviewer: String): MemoryReviewChange = withContext(Dispatchers.IO) {
        require(note.isNotBlank() && note.length <= 2000)
        mutex.withLock {
            val items = read().toMutableList()
            val index = items.indexOfFirst { it.id == id }
            require(index >= 0) { "Change not found" }
            val previous = items[index]
            val updated = previous.copy(audits = previous.audits +
                "\n${System.currentTimeMillis()} [$reviewer]\n$note\n")
            require(updated.audits.length <= 40_000) { "Audit history limit reached" }
            items[index] = updated
            write(items)
            updated
        }
    }

    /** Editing produces a new identity so previous audits cannot approve different content. */
    suspend fun revise(id: String, body: String, description: String): MemoryReviewChange = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = read().toMutableList()
            val index = items.indexOfFirst { it.id == id }
            require(index >= 0 && items[index].status == "pending")
            val previous = items[index]
            require(previous.kind!="skill_delete") { "Reject and submit a new deletion request instead of editing it" }
            require(body.length <= when(previous.kind) { "skill_file" -> 24_000; "user" -> 12_000; else -> 6000 })
            if (previous.kind == "skill") require(parseSkillDrafts(JSONArray().put(
                JSONObject().put("name", previous.title).put("description", description).put("body", body)
            ), previous.sourceChatId).isNotEmpty())
            if (previous.body == body && previous.description == description) return@withLock previous
            val revised = previous.copy(id = java.util.UUID.randomUUID().toString(),
                body = body, description = description, addition = "", audits = "",
                createdAt = System.currentTimeMillis(), reason = "")
            items[index] = previous.copy(status = "superseded", reviewer = "user",
                reviewedAt = System.currentTimeMillis(), reason = "Edited in memory library")
            items.add(revised)
            write(items)
            revised
        }
    }

    /** Journal 'applying' before touching the target so an interrupted approval can be retried. */
    suspend fun decide(
        context: Context, id: String, approve: Boolean, reviewer: String, reason: String
    ): MemoryReviewChange = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(reason.length <= 2000)
            val items = read().toMutableList()
            val index = items.indexOfFirst { it.id == id }
            require(index >= 0) { "Change not found" }
            val change = items[index]
            if (change.status !in setOf("pending", "applying")) {
                check(change.status == if (approve) "approved" else "rejected") { "Change was already decided differently" }
                return@withLock change
            }
            require(approve || change.status != "applying") { "Interrupted approval must be completed before rejection" }
            if (approve) {
                items[index] = change.copy(status = "applying", reviewer = reviewer, reason = reason)
                write(items)
                var skillInstalled = false
                try {
                    if (change.kind == "notes") {
                        val notes = MemoryNotesRepository(context, profileId)
                        if (change.addition.isNotEmpty()) notes.mutate("add", change.addition)
                        else if (notes.load().markdown != change.body) notes.save(change.body, change.baseVersion)
                        if (reviewer=="user") LearningPromptSnapshotRepository.markChanged(context, "notes-content:$profileId")
                    } else if(change.kind=="user") {
                        UserProfileDocumentRepository.getInstance(context).saveIfUnchanged(change.body,change.before)
                    } else if(change.kind=="skill_delete") {
                        LearnedSkillRepository(context).delete(change.title,change.baseVersion)
                    } else if (change.kind=="skill_file") {
                        LearnedSkillRepository(context).apply(profileId,change.title,change.path,change.body,
                            change.baseVersion,change.operation=="remove",change.automatic)
                    } else skillInstallMutex.withLock {
                        if (change.status == "pending") {
                            check(SkillManager.getInstance(context).getAvailableSkills()[change.title] == null) {
                                context.getString(com.ai.assistance.operit.R.string.skill_draft_duplicate)
                            }
                        }
                        installSkill(context, change)
                        skillInstalled = true
                        LearnedSkillRepository(context).register(change.title,profileId)
                    }
                } catch (e: MemoryNotesRepository.NotesException) {
                    // A rejected CAS/capacity check made no write; it is safe to return to pending.
                    items[index] = change.copy(status = "pending", reason = e.reason.name)
                    write(items)
                    throw e
                } catch (e: Exception) {
                    if (!skillInstalled && change.kind in setOf("skill", "skill_file", "skill_delete", "user")) {
                        // Import returned no success: keep the proposal editable/rejectable.
                        items[index] = change.copy(status = "pending", reason = e.javaClass.simpleName)
                        write(items)
                    }
                    throw e
                }
            }
            val decided = change.copy(
                status = if (approve) "approved" else "rejected", reviewedAt = System.currentTimeMillis(),
                reviewer = reviewer, reason = reason.take(2000)
            )
            items[index] = decided
            write(items)
            decided
        }
    }
    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock { Files.deleteIfExists(file.toPath()); Unit } }

    private fun installSkill(context: Context, draft: MemoryReviewChange) {
        val validated = parseSkillDrafts(JSONArray().put(JSONObject().put("name", draft.title)
            .put("description", draft.description).put("body", draft.body)), draft.sourceChatId)
        require(validated.size == 1) { context.getString(com.ai.assistance.operit.R.string.skill_draft_invalid) }
        val zip = File.createTempFile("learned-skill-", ".zip", context.cacheDir)
        try {
            // JSON quoted strings are valid YAML scalars; names are restricted path-safe slugs.
            val markdown = "---\nname: ${draft.title}\ndescription: ${JSONObject.quote(draft.description)}\n---\n\n${draft.body}\n"
            val manager = SkillManager.getInstance(context)
            // Retry after installation but before the decision journal was finalized.
            if (manager.readSkillContent(draft.title) == markdown) return
            ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(ZipEntry("SKILL.md"))
                stream.write(markdown.toByteArray())
                stream.closeEntry()
            }
            val result = manager.importSkillFromZipDetailed(zip, null)
            check(result.installedDir != null) { result.message }
        } finally { zip.delete() }
    }
}
