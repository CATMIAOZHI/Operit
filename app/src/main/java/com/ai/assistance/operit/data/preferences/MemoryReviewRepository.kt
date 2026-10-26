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
        val description = stripInvisibleCharacters((obj.opt("description") as? String)?.trim().orEmpty())
        val body = stripSkillFrontmatter(stripInvisibleCharacters((obj.opt("body") as? String)?.trim().orEmpty()))
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
    val automatic: Boolean = false,
    val files: Map<String, String> = emptyMap(),
    val origin: String = "legacy",
    val learningGeneration: Long = 0,
    val evidence: String = "",
    val restoredFrom: String = "",
    /**
     * A new skill written by the background review: applied automatically it is installed archived
     * and stays off until the independent scorer passes it. A user's approval switches it on directly.
     */
    val holdForScore: Boolean = false,
    /** Key of the conversation excerpt the scorer reads for this change (one per change, never per name). */
    val scoreEvidence: String = ""
)

internal fun skillFileRestoreTarget(change: MemoryReviewChange, beforeChange: Boolean): Pair<String, Boolean> {
    require(change.kind == "skill_file" && change.status == "approved") { "Only approved file versions can be restored" }
    val remove = if (beforeChange) change.baseVersion == LearnedSkillRepository.version("absent")
        else change.operation == "remove"
    return (if (beforeChange) change.before else change.body) to remove
}

class MemoryReviewRepository internal constructor(
    private val root: File, val profileId: String,
    private val autoApprovalEnabled: () -> Boolean = { false },
    private val staged: MutableList<MemoryReviewChange>? = null,
    private val boundary: suspend () -> com.ai.assistance.operit.features.orbit.OrbitLearningBoundary? = { null },
    private val learningGeneration: Long? = null,
    private val evidence: String = "",
    /** Set for an Orbit, whose record lives in its own memory folder: where older versions kept it. */
    private val legacyRoot: File? = null,
    /** True when a text holds something the user had this Orbit forget; such changes are refused. */
    private val forgotten: suspend (String) -> Boolean = { false },
) {
    constructor(context: Context, profileId: String, staged: MutableList<MemoryReviewChange>? = null,
        learningGeneration: Long? = null, evidence: String = "") :
        this(MemorySearchSettingsPreferences.orbitMemoryDir(context.filesDir, profileId)
            ?: File(context.filesDir, "memory_reviews"), profileId,
        { MemorySearchSettingsPreferences(context, profileId).shouldAutoApproveChanges() }, staged,
        { com.ai.assistance.operit.features.orbit.OrbitLearningBoundary.forSpace(context, profileId) },
        learningGeneration, evidence,
        File(context.filesDir, "memory_reviews").takeIf { MemorySearchSettingsPreferences.isOrbitSpace(profileId) },
        { text ->
            MemorySearchSettingsPreferences.isOrbitSpace(profileId) &&
                com.ai.assistance.operit.features.orbit.OrbitForget.orbitForSpace(context, profileId)
                    ?.let { com.ai.assistance.operit.features.orbit.OrbitForget.containsForgotten(context, it, text) } == true
        })
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val skillInstallMutex = Mutex()
        /** How many undecided proposals may wait for review. */
        internal const val PENDING_LIMIT = 30
        /** A sectioned memory.md add stores its section as `add:<section>` in [MemoryReviewChange.operation]. */
        internal const val NOTES_ADD_PREFIX = "add:"
        /** One of an Orbit's own note files (a person, a circle, the lessons book); the title is its path. */
        const val ORBIT_FILE = "orbit_file"
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
    private val legacyFile = File(legacyRoot ?: root, "${hash(profileId)}.json")
    private val file = if (legacyRoot != null) File(root, "changes.json") else legacyFile
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private fun read(): List<MemoryReviewChange> {
        MemorySearchSettingsPreferences.moveLegacyFile(legacyFile, file)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            MemoryReviewChange(obj.getString("id"), obj.getString("kind"), obj.getString("title"),
                obj.getString("body"), obj.optString("description"), obj.optString("before"),
                obj.optString("baseVersion"), obj.optString("addition"), obj.optString("sourceChatId"),
                obj.getLong("createdAt"), obj.getString("status"), obj.optLong("reviewedAt"),
                obj.optString("reviewer"), obj.optString("reason"), obj.optString("audits"),
                obj.optString("path","SKILL.md"),obj.optString("operation","write"),obj.optBoolean("automatic"),
                readFiles(obj), obj.optString("origin", "legacy"), obj.optLong("learningGeneration"),
                obj.optString("evidence"), obj.optString("restoredFrom"),
                holdForScore = obj.optBoolean("holdForScore"), scoreEvidence = obj.optString("scoreEvidence"))
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
    /** Call under the review mutex. Obsolete proposals must not occupy the pending quota. */
    private suspend fun readCurrent(): List<MemoryReviewChange> {
        val items = read()
        val guard = boundary() ?: return items
        val refreshed = items.map {
            if (it.status in setOf("pending", "applying") && !guard.accepts(it.learningGeneration,
                    if (it.origin == "foreground") it.id else ""))
                it.copy(status = "superseded", reviewedAt = System.currentTimeMillis(),
                    reason = "Memory was corrected after this proposal was created") else it
        }
        if (refreshed != items) write(refreshed)
        return refreshed
    }
    fun toJson(d: MemoryReviewChange): JSONObject = JSONObject().put("id", d.id).put("kind", d.kind)
        .put("title", d.title).put("body", d.body).put("description", d.description).put("before", d.before)
        .put("baseVersion", d.baseVersion).put("addition", d.addition).put("sourceChatId", d.sourceChatId)
        .put("createdAt", d.createdAt).put("status", d.status).put("reviewedAt", d.reviewedAt)
        .put("reviewer", d.reviewer).put("reason", d.reason).put("audits", d.audits)
        .put("path",d.path).put("operation",d.operation).put("automatic",d.automatic)
        .put("files", JSONObject(d.files)).put("origin", d.origin)
        .put("learningGeneration", d.learningGeneration).put("evidence", d.evidence).put("restoredFrom", d.restoredFrom)
        .put("holdForScore", d.holdForScore)
        .put("scoreEvidence", d.scoreEvidence)

    /**
     * What a learning run is told about its own proposal. The full before/after snapshots stay on
     * disk for the review UI, but returning them to the model is pure waste: one rewrite of a nearly
     * full document is about twelve thousand characters, several of which exhaust a batch's evidence
     * budget and discard everything it had already staged. A target is read back with memory_read or
     * skill_read when the text itself is needed.
     */
    fun toModelJson(d: MemoryReviewChange): JSONObject = JSONObject().put("id", d.id).put("kind", d.kind)
        .put("title", d.title).put("description", d.description).put("status", d.status)
        .put("path", d.path).put("operation", d.operation).put("characters", d.body.length)
        .apply { if (d.files.isNotEmpty()) put("files", JSONArray(d.files.keys.toList())) }

    private fun readFiles(obj: JSONObject): Map<String, String> =
        obj.optJSONObject("files")?.let { files ->
            files.keys().asSequence().associateWith { files.getString(it) }
        }.orEmpty()

    suspend fun list(): List<MemoryReviewChange> = withContext(Dispatchers.IO) {
        mutex.withLock { readCurrent().sortedByDescending { maxOf(it.createdAt, it.reviewedAt) } }
    }

    private fun texts(d: MemoryReviewChange) = listOf(d.title, d.body, d.description, d.before, d.addition,
        d.reason, d.audits, d.evidence) + d.files.values

    /** Forget flow, read only: the records whose stored text contains a line [matches] finds. */
    internal suspend fun idsMatching(matches: (String) -> Boolean): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read().filter { d -> texts(d).any { t -> t.lines().any(matches) } }.map { it.id } }
    }

    /**
     * Forget flow: strips every line [matches] finds from the stored snapshots, and stops undecided
     * proposals that carry it so they cannot write it back. Returns the ids changed.
     */
    internal suspend fun forgetLines(matches: (String) -> Boolean): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            fun clean(text: String) = if (text.lines().none(matches)) text else text.lines().filterNot(matches).joinToString("\n")
            val changed = mutableListOf<String>()
            val next = read().map { d ->
                if (texts(d).none { t -> t.lines().any(matches) }) return@map d
                changed += d.id
                val undecided = d.status in setOf("pending", "applying")
                d.copy(title = clean(d.title).ifBlank { "-" }, body = clean(d.body), description = clean(d.description),
                    before = clean(d.before), addition = clean(d.addition), reason = if (undecided)
                        "Removed because the user asked Orbit to forget it" else clean(d.reason),
                    audits = clean(d.audits), evidence = clean(d.evidence), files = d.files.mapValues { clean(it.value) },
                    status = if (undecided) "superseded" else d.status,
                    reviewedAt = if (undecided) System.currentTimeMillis() else d.reviewedAt)
            }
            if (changed.isNotEmpty()) write(next)
            changed
        }
    }
    /** Only AI proposal call sites use this; manual edits and old pending items are not swept. */
    suspend fun applyAutomaticDecision(context: Context, change: MemoryReviewChange): MemoryReviewChange {
        if (staged != null) return change.copy(status = "staged")
        if (!MemorySearchSettingsPreferences(context, profileId).shouldAutoApproveChanges()) return change
        return decide(context, change.id, true, "automatic", "Auto-approval enabled for this memory space")
    }

    /** What a change would add (its addition, or the new text's lines that were not there) touches the forgotten list. */
    private suspend fun mentionsForgotten(change: MemoryReviewChange): Boolean {
        val added = change.addition.ifBlank {
            val old = change.before.lines().toSet()
            (change.body.lines().filterNot { it in old } + change.files.values).joinToString("\n")
        }
        return forgotten(added) || forgotten(change.title) || forgotten(change.description)
    }
    suspend fun propose(candidate: MemoryReviewChange, onCreated: () -> Unit = {}): MemoryReviewChange = withContext(Dispatchers.IO) {
        require(!mentionsForgotten(candidate)) {
            "This change contains information the user asked Orbit to forget; leave that information out"
        }
        val change = candidate.copy(origin = if (learningGeneration != null) "learning" else "foreground",
            learningGeneration = learningGeneration ?: boundary()?.snapshot()?.generation ?: 0,
            evidence = evidence)
        staged?.let { pending ->
            if (change.kind == "skill_delete") {
                // Delete supersedes earlier edits of every file in the directory. Applying those
                // first would invalidate the deletion's directory version.
                pending.removeAll { it.title==change.title && it.kind=="skill_file" }
            }
            // A batch is revisable: the last change to a target replaces the earlier one, and the first
            // proposal's baseline is kept so the applied change still matches the unchanged target on disk.
            val index = pending.indexOfFirst { sameTarget(it, change) }
            if (index < 0) {
                // The export stage checks the store's undecided total, so this must count the same
                // rows: a batch that fills its own slots on top of an existing backlog would be
                // rejected only after the model had already done all of its work.
                val undecided = mutex.withLock { readCurrent().count { it.status in setOf("pending", "applying") } }
                require(undecided + pending.size < PENDING_LIMIT || autoApprovalEnabled()) {
                    "Pending review limit reached; the user must review the existing proposals before more can be added"
                }
                return@withContext change.copy(id = java.util.UUID.randomUUID().toString()).also { pending.add(it) }
            }
            val previous = pending[index]
            check(previous.kind == change.kind) {
                if (previous.kind == "skill_delete")
                    "This batch already stages a deletion of ${change.title}. Revise an installed skill with " +
                        "skill_write on its SKILL.md instead of deleting and recreating it, or finish this " +
                        "batch and create the skill in a later review."
                else "This batch already staged a ${previous.kind} change for ${change.title}; resubmit it as " +
                    "${previous.kind} instead of ${change.kind}."
            }
            // A lessons append carries only its lines, so a later one on the same page joins them; it
            // is still applied as one append, never as a rewrite of the whole book.
            if (change.kind == ORBIT_FILE && change.addition.isNotEmpty() && previous.addition.isNotEmpty()) {
                val joined = previous.addition.trimEnd() + "\n" + change.addition.trim()
                return@withContext change.copy(id = previous.id, before = previous.before, body = joined,
                    baseVersion = previous.baseVersion, addition = joined, createdAt = previous.createdAt)
                    .also { pending[index] = it }
            }
            // The replacement carries the whole target text, so an accumulated notes addition must not
            // be appended on top of it.
            return@withContext change.copy(id = previous.id, before = previous.before,
                baseVersion = previous.baseVersion, addition = "", createdAt = previous.createdAt)
                .also { pending[index] = it }
        }
        mutex.withLock {
            val items = readCurrent().toMutableList()
            // A row stored before the write points stripped invisible characters can describe the same
            // edit with a different body, so both sides are normalized before they are compared;
            // otherwise the same change is staged twice instead of matching its own earlier proposal.
            val body = stripInvisibleCharacters(change.body)
            val addition = stripInvisibleCharacters(change.addition)
            items.find { it.status in setOf("pending", "applying") &&
                it.kind == change.kind && it.title == change.title &&
                stripInvisibleCharacters(it.body) == body &&
                it.description == change.description && it.baseVersion == change.baseVersion &&
                stripInvisibleCharacters(it.addition) == addition &&
                it.path==change.path && it.operation==change.operation &&
                it.automatic==change.automatic && it.files==change.files &&
                it.origin==change.origin && it.learningGeneration==change.learningGeneration &&
                it.restoredFrom==change.restoredFrom }?.let { return@withLock it }
            // Old pending items must not block newly enabled automatic saving.
            require(items.count { it.status in setOf("pending", "applying") } < PENDING_LIMIT || autoApprovalEnabled()) {
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
            val items = readCurrent().toMutableList()
            val ids = items.mapTo(mutableSetOf()) { it.id }
            val generation = boundary()?.snapshot()?.generation ?: 0
            val additions = changes.filter { ids.add(it.id) }.map {
                if (it.learningGeneration != generation) it.copy(status = "superseded",
                    reviewedAt = System.currentTimeMillis(),
                    reason = "Memory was corrected after this evidence was read") else it
            }
            require(items.count { it.status in setOf("pending","applying") } +
                additions.count { it.status in setOf("pending","applying") } <= PENDING_LIMIT ||
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
        automatic=obj.optBoolean("automatic"), files=readFiles(obj),
        origin=obj.optString("origin","legacy"), learningGeneration=obj.optLong("learningGeneration"),
        evidence=obj.optString("evidence"), restoredFrom=obj.optString("restoredFrom"),
        holdForScore=obj.optBoolean("holdForScore"), scoreEvidence=obj.optString("scoreEvidence")
    )

    suspend fun proposeSkill(draft: SkillDraft, onCreated: () -> Unit = {},
        files: Map<String, String> = emptyMap()): MemoryReviewChange = propose(MemoryReviewChange(
        id = hash("skill:${draft.name}:${draft.description}:${draft.body}"),
        kind = "skill", title = draft.name, description = draft.description, body = draft.body,
        sourceChatId = draft.sourceChatId, files = files
    ), onCreated)

    suspend fun proposeSkillFile(name: String, path: String, before: LearnedSkillRepository.Snapshot,
        body: String, remove: Boolean = false, automatic: Boolean = false, sourceChatId: String = "",
        onCreated: () -> Unit = {}): MemoryReviewChange = propose(MemoryReviewChange(
            id = "",kind="skill_file",title=name,body=body,before=before.text,baseVersion=before.version,
            path=path,operation=if(remove) "remove" else "write",automatic=automatic,sourceChatId=sourceChatId
        ),onCreated)

    /** Restore a reviewed file through a new proposal; never replay an old target version blindly. */
    suspend fun proposeSkillFileRestore(context: Context, id: String, beforeChange: Boolean): MemoryReviewChange {
        val change = list().single { it.id == id }
        val target = skillFileRestoreTarget(change, beforeChange)
        val current = LearnedSkillRepository(context, profileId).read(change.title, change.path)
        return propose(MemoryReviewChange(id = "", kind = "skill_file", title = change.title,
            path = change.path, body = target.first, before = current.text, baseVersion = current.version,
            operation = if (target.second) "remove" else "write",
            restoredFrom = "$id:${if (beforeChange) "before" else "after"}"))
    }

    suspend fun proposeUser(before: String, after: String, sourceChatId: String = "",
        onCreated: () -> Unit = {}): MemoryReviewChange {
        // The stored document is stripped at its write point, so the proposal must carry the same
        // bytes: otherwise a retry after an interrupted apply compares two different strings and
        // either appends twice or fails its version check forever.
        val text = stripInvisibleCharacters(after)
        require(text.length <= UserProfileDocumentRepository.MAX_CONTENT_CHARS) { "user.md exceeds the character limit" }
        return propose(MemoryReviewChange(id="", kind="user",
            title="user.md",body=text,before=before,baseVersion=LearnedSkillRepository.version(before),
            sourceChatId=sourceChatId),onCreated)
    }
    suspend fun proposeSkillDeletion(name: String, before: LearnedSkillRepository.Snapshot, sourceChatId: String="",
        onCreated: () -> Unit = {}) = propose(MemoryReviewChange(id="",kind="skill_delete",title=name,body="",
        before=before.text,baseVersion=before.version,sourceChatId=sourceChatId,operation="delete"),onCreated)

    suspend fun proposeNotes(
        before: MemoryNotesRepository.Snapshot, after: String, addition: String = "", sourceChatId: String = "",
        onCreated: () -> Unit = {}, section: String = ""
    ): MemoryReviewChange {
        // Same reason as user.md: the proposal holds the text that will actually be stored, so an
        // interrupted apply can be retried and is recognised as already done.
        val text = stripInvisibleCharacters(after)
        val added = stripInvisibleCharacters(addition)
        return propose(MemoryReviewChange(
            id = hash("notes:${before.version}:$text"), kind = "notes", title = "memory.md", body = text,
            before = before.markdown, baseVersion = before.version, addition = added, sourceChatId = sourceChatId,
            operation = if (added.isNotEmpty() && section.isNotBlank()) NOTES_ADD_PREFIX + section else "write"
        ), onCreated)
    }

    /**
     * A change to one of an Orbit's note files. [addition] is one line appended on apply (the
     * lessons book), which survives a concurrent edit; otherwise [after] replaces [before] only if
     * the file still reads [before].
     */
    suspend fun proposeOrbitFile(path: String, before: String, after: String, addition: String = "",
        sourceChatId: String = "", onCreated: () -> Unit = {}): MemoryReviewChange {
        val text = stripInvisibleCharacters(after)
        return propose(MemoryReviewChange(id = "", kind = ORBIT_FILE, title = path, body = text, before = before,
            baseVersion = LearnedSkillRepository.version(before), addition = stripInvisibleCharacters(addition),
            sourceChatId = sourceChatId), onCreated)
    }

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
            // A lessons append holds only its lines; an edit of it stays an append of the edited lines.
            val append = previous.kind == ORBIT_FILE && previous.addition.isNotEmpty()
            if (append) require(body.isNotBlank()) { "A lesson cannot be empty; reject it instead" }
            val revised = previous.copy(id = java.util.UUID.randomUUID().toString(),
                body = body, description = description,
                addition = if (append) stripInvisibleCharacters(body).trim() else "", audits = "",
                createdAt = System.currentTimeMillis(), reason = "", origin = "foreground",
                learningGeneration = boundary()?.snapshot()?.generation ?: 0)
            items[index] = previous.copy(status = "superseded", reviewer = "user",
                reviewedAt = System.currentTimeMillis(), reason = "Edited in memory library")
            items.add(revised)
            write(items)
            revised
        }
    }

    /** Settles an undecided change without applying it, saying why; a decided one is left as it is. */
    internal suspend fun holdBack(id: String, reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = read().toMutableList()
            val index = items.indexOfFirst { it.id == id }
            if (index < 0 || items[index].status != "pending") return@withLock
            items[index] = items[index].copy(status = "superseded", reviewer = "automatic",
                reviewedAt = System.currentTimeMillis(), reason = reason.take(2000))
            write(items)
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
            check(!approve || change.restoredFrom.isBlank() || reviewer == "user") {
                "A restored file version requires the user's approval"
            }
            if (change.status == "superseded") return@withLock change
            // The user may have had Orbit forget something after this was proposed (a staged batch,
            // an import replay): it is checked again right before it would be written.
            if (approve && change.status in setOf("pending", "applying") && mentionsForgotten(change)) {
                val dropped = change.copy(status = "superseded", reviewedAt = System.currentTimeMillis(),
                    reason = "Removed because the user asked Orbit to forget it")
                items[index] = dropped
                write(items)
                return@withLock dropped
            }
            val governance = boundary()
            if (approve && change.status in setOf("pending", "applying") &&
                governance != null && !governance.accepts(change.learningGeneration,
                    if (change.origin == "foreground") change.id else "")) {
                val outdated = change.copy(status = "superseded", reviewedAt = System.currentTimeMillis(),
                    reason = "Memory was corrected after this proposal was created")
                items[index] = outdated
                write(items)
                return@withLock outdated
            }
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
                    SkillManager.forMemorySpace(context, profileId).getAvailableSkills()[change.title]?.description else null
                try {
                    suspend fun applyTarget() {
                    if (change.kind == "notes") {
                        val notes = MemoryNotesRepository(context, profileId)
                        // A sectioned add names its section in the operation; an older one has none and
                        // lands in the unsorted section rather than failing forever.
                        if (change.addition.isNotEmpty()) notes.mutate("add", change.addition,
                            section = change.operation.removePrefix(NOTES_ADD_PREFIX)
                                .takeIf { change.operation.startsWith(NOTES_ADD_PREFIX) }
                                ?: if (notes.sectioned) MemoryNoteSections.UNSORTED else "")
                        else if (notes.load().markdown != change.body) notes.save(change.body, change.baseVersion)
                        // The learner's own writes invalidate a frozen prefix too: memory.md is part of
                        // the prefix, so the library must offer the refresh instead of silently serving
                        // the older text. Skill catalog changes are marked the same way below.
                        LearningPromptSnapshotRepository.markChanged(context, "notes-content:$profileId")
                    } else if(change.kind=="user") {
                        UserProfileDocumentRepository.forMemorySpace(context, profileId).saveIfUnchanged(change.body,change.before)
                    } else if (change.kind == ORBIT_FILE) {
                        val owner = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.privateOwner(context, profileId)
                            ?: error("This memory space has no Orbit files")
                        val files = com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, owner)
                        if (change.addition.isNotEmpty()) files.appendOnce(change.title, change.addition)
                        else files.writeIfUnchanged(change.title, change.before, change.body)
                    } else if(change.kind=="skill_delete") {
                        LearnedSkillRepository(context, profileId).delete(change.title,change.baseVersion)
                    } else if (change.kind=="skill_file") {
                        LearnedSkillRepository(context, profileId).apply(profileId,change.title,change.path,change.body,
                            change.baseVersion,change.operation=="remove",change.automatic)
                    } else skillInstallMutex.withLock {
                        if (change.status == "pending") {
                            check(SkillManager.forMemorySpace(context, profileId).getAvailableSkills()[change.title] == null) {
                                context.getString(com.ai.assistance.operit.R.string.skill_draft_duplicate)
                            }
                        }
                        val held = change.holdForScore && reviewer != "user"
                        // Switched off before it exists, so no crash can leave a held skill shown to the AI.
                        if (held) check(com.ai.assistance.operit.data.skill.SkillRepository.forMemorySpace(context, profileId)
                            .skillVisibilityPreferences.setSkillVisibleToAi(change.title, false, durable = true)) {
                            "Could not switch the new skill off"
                        }
                        installSkill(context, change)
                        skillInstalled = true
                        LearnedSkillRepository(context, profileId).register(change.title,profileId)
                        if (held) LearnedSkillRepository(context, profileId).holdForScore(change.title, change.scoreEvidence)
                    }
                    // Any catalog change invalidates a frozen prefix, including one the background
                    // learner made: without this the new skill is invisible to existing chats.
                    if (change.kind in setOf("skill", "skill_delete") ||
                        (change.kind == "skill_file" &&
                            previousSkillDescription != SkillManager.forMemorySpace(context, profileId).getAvailableSkills()[change.title]?.description))
                        LearningPromptSnapshotRepository.markChanged(context,
                            if (com.ai.assistance.operit.features.orbit.OrbitMemoryScope.privateOwner(context, profileId) == null)
                                "settings" else "skills:$profileId")
                    }
                    val correction = change.origin == "foreground" && (change.restoredFrom.isNotBlank() || change.kind == "skill_delete" ||
                        change.before.isNotBlank() && change.before != change.body && change.addition.isBlank())
                    when {
                        governance == null -> applyTarget()
                        correction -> governance.edit(change.id, change.learningGeneration) { applyTarget() }
                        else -> governance.learn(change.learningGeneration) { applyTarget() }
                    }
                } catch (e: MemoryNotesRepository.NotesException) {
                    // A rejected CAS/capacity check made no write; it is safe to return to pending.
                    items[index] = change.copy(status = "pending", reason = e.reason.name)
                    write(items)
                    throw e
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (governance != null && !governance.accepts(change.learningGeneration,
                            if (change.origin == "foreground") change.id else "")) {
                        val outdated = change.copy(status = "superseded", reviewedAt = System.currentTimeMillis(),
                            reason = "Memory changed while this proposal was being applied")
                        items[index] = outdated
                        write(items)
                        return@withLock outdated
                    }
                    if (!skillInstalled && change.kind in setOf("skill", "skill_file", "skill_delete", "user", "notes", ORBIT_FILE)) {
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
    suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(legacyFile.toPath())
            // An Orbit's memory folder goes once it is empty; File.delete leaves a folder with files in it.
            if (legacyRoot != null) file.parentFile?.delete()
            Unit
        }
    }

    private suspend fun installSkill(context: Context, draft: MemoryReviewChange) {
        val validated = parseSkillDrafts(JSONArray().put(JSONObject().put("name", draft.title)
            .put("description", draft.description).put("body", draft.body)), draft.sourceChatId)
        require(validated.size == 1) { context.getString(com.ai.assistance.operit.R.string.skill_draft_invalid) }
        // Write the validated draft, not the raw body, so the file always has exactly one header.
        val skill = validated.single()
        val zip = File.createTempFile("learned-skill-", ".zip", context.cacheDir)
        try {
            // JSON quoted strings are valid YAML scalars; names are restricted path-safe slugs.
            val markdown = "---\nname: ${skill.name}\ndescription: ${JSONObject.quote(skill.description)}\n---\n\n${skill.body}\n"
            val manager = SkillManager.forMemorySpace(context, profileId)
            // Retry after installation but before the decision journal was finalized.
            val files = draft.files.mapValues { (_, text) -> stripInvisibleCharacters(text) }
            validateDraftFiles(files)
            val installed = manager.getAvailableSkills()[draft.title]?.directory
            if (manager.readSkillContent(draft.title) == markdown && installed != null &&
                files.all { (path, text) -> File(installed,path).let { it.isFile && it.readText()==text } }) return
            ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(ZipEntry("SKILL.md"))
                stream.write(markdown.toByteArray())
                stream.closeEntry()
                files.forEach { (path, text) ->
                    stream.putNextEntry(ZipEntry(path))
                    stream.write(text.toByteArray())
                    stream.closeEntry()
                }
            }
            val result = manager.importSkillFromZipDetailed(zip, null, notifyPrefix = false)
            check(result.installedDir != null) { result.message }
        } finally { zip.delete() }
    }
}

/**
 * Invisible and bidirectional control characters never carry meaning in stored text, and they are
 * how injected instructions hide inside distilled material. Skills and memory documents are both
 * loaded into later sessions, so the characters are dropped at every write instead of only being
 * called out in the prompt.
 */
internal fun stripInvisibleCharacters(text: String): String = text.filterNot { character ->
    val code = character.code
    code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2064 ||
        code in 0x2066..0x2069 || code == 0xFEFF || code in 0xE0000..0xE007F ||
        // The Arabic letter mark and the other invisible joiners are the bidi tricks of
        // "Trojan Source" payloads, and none of them carries meaning on their own.
        code == 0x061C || code == 0x00AD || code == 0x034F || code == 0x180E ||
        code == 0x7F || (code < 0x20 && character != '\n' && character != '\t')
}

/** A bounded, reviewable package; paths obey the same policy as installed skill files. */
internal fun validateDraftFiles(files: Map<String, String>) {
    require(files.size <= 20) { "A new skill may contain at most 20 companion files" }
    require(files.values.sumOf { it.length.toLong() } <= 120_000) {
        "New skill companion files must total at most 120000 characters"
    }
    files.forEach { (path, text) ->
        LearnedSkillRepository.validatePath(path)
        require(path != "SKILL.md") { "Edit the main skill content separately" }
        require(files.keys.none { it != path && path.startsWith("$it/") }) {
            "$path has a parent that is already a file"
        }
        require(text.length <= LearnedSkillRepository.MAX_SKILL_FILE_CHARS) {
            "$path exceeds ${LearnedSkillRepository.MAX_SKILL_FILE_CHARS} characters"
        }
    }
}
