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
        val body = stripSkillFrontmatter((obj.opt("body") as? String)?.trim().orEmpty())
        if (!Regex("[a-z][a-z0-9-]{2,63}").matches(name) ||
            description.length !in 1..LearnedSkillRepository.MAX_SKILL_DESCRIPTION_CHARS ||
            description.contains('\n') ||
            body.length !in LearnedSkillRepository.MIN_SKILL_BODY_CHARS..LearnedSkillRepository.MAX_SKILL_BODY_CHARS
        ) return@mapNotNull null
        SkillDraft(name, name, description, body, chatId, System.currentTimeMillis())
    }.take(1)
}

/**
 * A created skill is submitted as its body only, and the frontmatter is composed from the already
 * validated name and description. A draft that repeats the header anyway would otherwise install a
 * doubled one, so drop a leading YAML block here — the single place every creation path goes through.
 */
internal fun stripSkillFrontmatter(body: String): String {
    val normalized = body.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return body
    val end = normalized.indexOf("\n---", 4)
    if (end <= 0) return body
    return normalized.substring(end + 4).trimStart('\n')
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

class MemoryReviewRepository internal constructor(
    private val root: File, val profileId: String,
    private val autoApprovalEnabled: () -> Boolean = { false },
    private val staged: MutableList<MemoryReviewChange>? = null
) {
    constructor(context: Context, profileId: String, staged: MutableList<MemoryReviewChange>? = null) :
        this(File(context.filesDir, "memory_reviews"), profileId,
        { MemorySearchSettingsPreferences(context, profileId).shouldAutoApproveChanges() }, staged)
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val skillInstallMutex = Mutex()
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        /** A skill's directory entry and its files are one target, so a batch cannot stage both. */
        internal fun sameTarget(previous: MemoryReviewChange, incoming: MemoryReviewChange): Boolean =
            previous.title == incoming.title && previous.path == incoming.path &&
                (previous.kind == incoming.kind || isSkillKind(previous.kind) && isSkillKind(incoming.kind))
        private fun isSkillKind(kind: String) = kind == "skill" || kind == "skill_file" || kind == "skill_delete"
        /** Full before/after snapshots make history expensive, so only finished items are ever dropped. */
        internal const val HISTORY_LIMIT = 200
        /**
         * Approval history keeps one full snapshot pair per change, so it must not grow without bound.
         * Unfinished items are never removed: they are still waiting for a decision.
         */
        internal fun pruneHistory(items: List<MemoryReviewChange>): List<MemoryReviewChange> {
            val unfinished = items.filter { it.status in setOf("pending","applying") }
            val finished = items.filterNot { it.status in setOf("pending","applying") }
                .sortedByDescending { maxOf(it.createdAt,it.reviewedAt) }
            return unfinished + finished.take(HISTORY_LIMIT)
        }
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
        val kept = pruneHistory(items)
        root.mkdirs()
        val json = JSONArray().apply { kept.forEach { d ->
            put(toJson(d))
        } }
        val temp = File.createTempFile(".draft-", ".tmp", root)
        try {
            temp.outputStream().use { out -> out.write(json.toString().toByteArray()); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        syncDirectory(file.parentFile!!)
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
    /** Only AI proposal call sites use this; manual edits and old pending items are not swept. */
    suspend fun applyAutomaticDecision(context: Context, change: MemoryReviewChange): MemoryReviewChange {
        if (staged != null) return change.copy(status = "staged")
        if (!MemorySearchSettingsPreferences(context, profileId).shouldAutoApproveChanges()) return change
        return decide(context, change.id, true, "automatic", "Auto-approval enabled for this memory space")
    }
    suspend fun propose(change: MemoryReviewChange, onCreated: () -> Unit = {}): MemoryReviewChange = withContext(Dispatchers.IO) {
        staged?.let { pending ->
            // A batch is revisable: the last change to a target replaces the earlier one, and the first
            // proposal's baseline is kept so the applied change still matches the unchanged target on disk.
            val index = pending.indexOfFirst { sameTarget(it, change) }
            if (index < 0) {
                return@withContext change.copy(id = java.util.UUID.randomUUID().toString()).also { pending.add(it) }
            }
            val previous = pending[index]
            check(previous.kind == change.kind) {
                "This batch already staged a ${previous.kind} change for ${change.title}; resubmit it as " +
                    "${previous.kind} instead of ${change.kind}."
            }
            // The replacement carries the whole target text, so an accumulated notes addition must not
            // be appended on top of it.
            return@withContext change.copy(id = previous.id, before = previous.before,
                baseVersion = previous.baseVersion, addition = "", createdAt = previous.createdAt)
                .also { pending[index] = it }
        }
        mutex.withLock {
            val items = read().toMutableList()
            items.find { it.status in setOf("pending", "applying") &&
                it.kind == change.kind && it.title == change.title && it.body == change.body &&
                it.description == change.description && it.baseVersion == change.baseVersion &&
                it.addition == change.addition && it.path==change.path && it.operation==change.operation &&
                it.automatic==change.automatic }?.let { return@withLock it }
            // Old pending items must not block newly enabled automatic saving.
            require(items.count { it.status in setOf("pending", "applying") } < 30 || autoApprovalEnabled()) {
                "Pending review limit reached"
            }
            val created = change.copy(id = java.util.UUID.randomUUID().toString())
            items.add(created)
            write(items)
            onCreated()
            created
        }
    }

    /** Replay of an atomically completed learning batch preserves IDs, including decided items. */
    internal suspend fun importCompletedBatch(changes: List<MemoryReviewChange>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = read().toMutableList()
            val ids = items.mapTo(mutableSetOf()) { it.id }
            val additions = changes.filter { ids.add(it.id) }
            require(items.count { it.status in setOf("pending","applying") } + additions.size <= 30 ||
                autoApprovalEnabled()) { "Pending review limit reached; review existing proposals before continuing" }
            items.addAll(additions)
            write(items)
        }
    }

    internal fun fromJson(obj: JSONObject) = MemoryReviewChange(
        id=obj.getString("id"), kind=obj.getString("kind"), title=obj.getString("title"),
        body=obj.getString("body"), description=obj.optString("description"), before=obj.optString("before"),
        baseVersion=obj.optString("baseVersion"), addition=obj.optString("addition"),
        sourceChatId=obj.optString("sourceChatId"), createdAt=obj.getLong("createdAt"),
        path=obj.optString("path","SKILL.md"), operation=obj.optString("operation","write"),
        automatic=obj.optBoolean("automatic")
    )

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
        onCreated: () -> Unit = {}): MemoryReviewChange {
        require(after.length <= UserProfileDocumentRepository.MAX_CONTENT_CHARS) { "user.md exceeds the character limit" }
        return propose(MemoryReviewChange(id="", kind="user",
            title="user.md",body=after,before=before,baseVersion=LearnedSkillRepository.version(before),
            sourceChatId=sourceChatId),onCreated)
    }
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
            // Same limits the write paths enforce, so a manual edit cannot smuggle past them.
            require(body.length <= when(previous.kind) {
                "skill_file" -> LearnedSkillRepository.MAX_SKILL_FILE_CHARS
                "user" -> UserProfileDocumentRepository.MAX_CONTENT_CHARS
                else -> LearnedSkillRepository.MAX_SKILL_BODY_CHARS
            })
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
                val previousSkillDescription = if (change.kind == "skill_file")
                    SkillManager.getInstance(context).getAvailableSkills()[change.title]?.description else null
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
                    // Any catalog change invalidates a frozen prefix, including one the background
                    // learner made: without this the new skill is invisible to existing chats.
                    if (change.kind in setOf("skill", "skill_delete") ||
                        (change.kind == "skill_file" &&
                            previousSkillDescription != SkillManager.getInstance(context).getAvailableSkills()[change.title]?.description))
                        LearningPromptSnapshotRepository.markChanged(context, "settings")
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
        // Write the validated draft, not the raw body, so the file always has exactly one header.
        val skill = validated.single()
        val zip = File.createTempFile("learned-skill-", ".zip", context.cacheDir)
        try {
            // JSON quoted strings are valid YAML scalars; names are restricted path-safe slugs.
            val markdown = "---\nname: ${skill.name}\ndescription: ${JSONObject.quote(skill.description)}\n---\n\n${skill.body}\n"
            val manager = SkillManager.getInstance(context)
            // Retry after installation but before the decision journal was finalized.
            if (manager.readSkillContent(draft.title) == markdown) return
            ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(ZipEntry("SKILL.md"))
                stream.write(markdown.toByteArray())
                stream.closeEntry()
            }
            val result = manager.importSkillFromZipDetailed(zip, null, notifyPrefix = false)
            check(result.installedDir != null) { result.message }
        } finally { zip.delete() }
    }
}
