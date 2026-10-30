package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Forget plan / confirm for one Orbit. A plan is a read-only scan of every place the information
 * lives (memory, profile, files, skills, goals, scheduled work, task outputs, boards, approvals,
 * undelivered reports, the change record); it is kept encrypted until the user answers. Confirming rescans,
 * refuses a plan that no longer covers what is there, cleans each place through its own store,
 * verifies, and records only locators and salted hashes so the information is not learned again.
 */
internal object OrbitForget {
    const val PLAN_TTL_MS = 2 * 60 * 60_000L
    /** A plan with more places than this is too broad to show the user; narrower terms are needed. */
    const val MAX_PLACES = 200
    /** Places the app does not edit by itself; the user reviews them by hand. */
    const val USER_REVIEW = "identity"
    private val mutex = Mutex()

    data class Hit(val category: String, val locator: String, val action: String) {
        fun json(): JSONObject = JSONObject().put("category", category).put("where", locator).put("action", action)
    }

    class Matcher(terms: List<String>) {
        private val normalized = terms.map(::normalize).filter { it.length >= 2 }
        init { require(normalized.isNotEmpty()) { "Give 1-10 search terms of at least 2 characters" } }
        fun hit(text: String): Boolean = normalize(text).let { t -> normalized.any { t.contains(it) } }
        fun clean(text: String): String = text.lines().filterNot(::hit).joinToString("\n")
        fun lines(text: String): List<Int> = text.lines().mapIndexedNotNull { i, line -> if (hit(line)) i + 1 else null }
    }

    fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun planStore(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, "forgetplan")
    private fun forgottenStore(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, "forgotten")

    private suspend fun space(context: Context, orbit: OrbitProfile) = OrbitMemoryScope.ensureInitialized(context, orbit)

    /**
     * What a scan found, and the places it could not read (too large, unreadable); while any are
     * left unread, nothing can be called clean.
     */
    class Scan(val hits: List<Hit>, val unscanned: List<String>, val tooMany: Boolean = false)

    /** The kinds of place a scan looks through, for the report. */
    val SCANNED = listOf("memory", "profile", "files (people, groups, daily log, settings files, nightly reviews, " +
        "alignment state, files/)", "SOUL.md", "IDENTITY.md", "skills", "goals", "scheduled",
        "task outputs (every saved version)", "feed (with its cards), ideas and tracking", "approvals",
        "undelivered reports", "change history")

    /** Read-only: every place [matcher] finds the information. */
    suspend fun scan(context: Context, orbit: OrbitProfile, matcher: Matcher): Scan = withContext(Dispatchers.IO) {
        // Stops collecting at what a plan can show, so a common word cannot fill memory.
        var tooMany = false
        val hits = object : ArrayList<Hit>() {
            override fun add(element: Hit): Boolean {
                if (size >= MAX_PLACES) { tooMany = true; return false }
                return super.add(element)
            }
        }
        val unscanned = mutableListOf<String>()
        val spaceId = space(context, orbit)
        MemoryNotesRepository(context, spaceId).load().markdown.let { text ->
            matcher.lines(text).forEach { hits += Hit("memory", "memory.md#L$it", "edit") }
        }
        UserProfileDocumentRepository.forMemorySpace(context, spaceId).load().let { text ->
            matcher.lines(text).forEach { hits += Hit("profile", "user.md#L$it", "edit") }
        }
        val files = OrbitFiles.of(context, orbit.id)
        // INDEX.md is rebuilt by the app from the person and group files, so it follows their cleanup.
        // Nightly reviews and alignment state hold what the user said too, so they are scanned.
        files.list(includeReview = true).filterNot { OrbitFiles.isIndex(it.path) }.forEach { entry ->
            val deletable = !OrbitFiles.isIndex(entry.path) && entry.path !in OrbitFiles.SINGLE &&
                entry.path !in OrbitFiles.ALIGNMENT_DATA
            // The name without its extension, so a term like "md" or "json" cannot match every file.
            val name = entry.path.substringAfterLast('/').let { it.substringBeforeLast('.', it) }
            if (deletable && matcher.hit(name)) {
                hits += Hit("files", entry.path, "delete")
            } else if (OrbitFiles.isText(entry.path)) {
                val text = if (entry.size <= OrbitFiles.MAX_TEXT) runCatching { files.read(entry.path) }.getOrNull() else null
                if (text == null) unscanned += entry.path
                else matcher.lines(text).forEach { hits += Hit("files", "${entry.path}#L$it", "edit") }
            }
        }
        // Soul and identity are the Orbit's own voice; they are listed for the user, never auto-edited.
        // PROMPT.md is the app's built-in handbook and holds nothing about the user.
        val soul = runCatching { OrbitSoulRepository(context, orbit.id).snapshot().current.body }.getOrNull()
        if (soul == null) unscanned += "SOUL.md"
        soul?.let { matcher.lines(it) }?.forEach { hits += Hit(USER_REVIEW, "SOUL.md#L$it", "user_review") }
        val identity = runCatching { OrbitPromptFiles.identity(context, orbit.id) }.getOrNull()
        if (identity == null) unscanned += "IDENTITY.md"
        identity?.let { matcher.lines(it) }?.forEach { hits += Hit(USER_REVIEW, "IDENTITY.md#L$it", "user_review") }
        val skills = LearnedSkillRepository(context, spaceId)
        val owned = runCatching { skills.owned(spaceId) }.getOrNull()
        if (owned == null) unscanned += "skills"
        owned.orEmpty().forEach { name ->
            val paths = runCatching { skills.files(name) }.getOrNull()
            if (paths == null) unscanned += "skills/$name"
            paths.orEmpty().forEach { path ->
                val text = runCatching { skills.read(name, path).text }.getOrNull()
                if (text == null) unscanned += "skills/$name/$path"
                else matcher.lines(text).forEach { hits += Hit("skills", "skills/$name/$path#L$it", "edit") }
            }
        }
        // Skills Orbit did not learn (ones the user added, wrote or changed) are only listed: the user
        // edits them. Files of a preinstalled skill still exactly as installed hold nothing about the user.
        if (owned != null) {
            val installed = runCatching {
                com.ai.assistance.operit.core.tools.skill.SkillManager.forMemorySpace(context, spaceId).getAvailableSkills()
            }.getOrNull()
            if (installed == null) unscanned += "skills"
            val pristine = runCatching { OrbitBundledSkills.installedHashes(context, orbit.id) }.getOrDefault(emptyMap())
            installed.orEmpty().filterKeys { it !in owned }.forEach { (name, skill) ->
                val root = skill.directory.canonicalFile
                val files = root.walkTopDown().filter { it.isFile }.take(101).toList()
                if (files.size > 100) unscanned += "skills/$name (more than 100 files)"
                files.take(100).forEach { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    val bytes = if (file.length() <= 256_000) runCatching { file.readBytes() }.getOrNull() else null
                    when {
                        bytes == null -> unscanned += "skills/$name/$path"
                        pristine[name]?.get(path) == OrbitBundledSkills.hashOf(bytes) -> Unit
                        else -> matcher.lines(bytes.toString(Charsets.UTF_8))
                            .forEach { hits += Hit(USER_REVIEW, "skills/$name/$path#L$it", "user_review") }
                    }
                }
            }
        }
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        dao.goals(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.plan) }.forEach {
            hits += Hit("goals", "goal:${it.id}", if (matcher.hit(it.title)) "stop" else "edit")
        }
        dao.tasks(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.instruction) || matcher.hit(it.lastObservation) }
            .forEach { hits += Hit("scheduled", "task:${it.id}", "stop") }
        // Every saved version of what tasks made, older ones included; a version cannot be edited, only
        // removed. Task outputs are always saved as text, whatever their file name.
        val root = OrbitPreferences.getInstance(context).orbitDir(orbit.id).canonicalFile
        var offset = 0
        while (true) {
            val page = dao.artifacts(orbit.id, 100, offset)
            page.forEach { version ->
                val file = File(root, version.relativePath).canonicalFile
                val found = matcher.hit(version.name.substringBeforeLast('.', version.name)) || run {
                    val text = if (file.toPath().startsWith(root.toPath()) && file.isFile && file.length() <= 800_000)
                        runCatching { file.readText() }.getOrNull() else null
                    if (text == null && file.exists()) unscanned += "artifact:${version.id}"
                    text != null && matcher.hit(text)
                }
                if (found) hits += Hit("outputs", "artifact:${version.id}", "delete")
            }
            if (page.size < 100) break
            offset += page.size
        }
        OrbitBoardRepository.Board.values().forEach { board ->
            val repository = OrbitBoardRepository(context, orbit.id, board)
            // Expired ideas too: they are kept to teach the user's taste.
            repository.listAll().filter { item ->
                val fields = listOf(item.title, item.body, item.extra, item.rationale) + item.notes.map { it.text } +
                    item.parts.flatMap { listOf(it.title, it.detail) } + item.sources
                fields.any(matcher::hit) || item.card && run {
                    val card = repository.readCard(item.id)
                    if (card == null) unscanned += "${board.name.lowercase()}:${item.id}:card"
                    card != null && matcher.hit(card)
                }
            }.forEach { hits += Hit("created", "${board.name.lowercase()}:${it.id}", "delete") }
        }
        OrbitApprovals.matching(context, orbit.id, matcher::hit).forEach { hits += Hit("approvals", "approval:$it", "edit") }
        dao.pendingDeliveries(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.body) }
            .forEach { hits += Hit("reports", "delivery:${it.id}", "stop") }
        MemoryReviewRepository(context, spaceId).idsMatching(matcher::hit)
            .forEach { hits += Hit("history", "review:$it", "edit") }
        Scan(hits, unscanned, tooMany)
    }

    /** Saves a new plan, replacing any older one, and returns its id with the places found. */
    suspend fun plan(context: Context, orbit: OrbitProfile, chatId: String, terms: List<String>): Planned =
        mutex.withLock {
            // A new plan always retires the old one, even if this one fails, so an outdated scope is never confirmed.
            withContext(Dispatchers.IO) { planStore(context, orbit.id).delete() }
            require(terms.size in 1..10 && terms.all { it.trim().length in 2..200 }) { "Give 1-10 search terms of 2-200 characters" }
            val scan = scan(context, orbit, Matcher(terms))
            val hits = scan.hits
            require(!scan.tooMany && hits.size <= MAX_PLACES) {
                "The terms match more than $MAX_PLACES places, too many to show the user. Use more specific terms and plan again."
            }
            val id = UUID.randomUUID().toString().take(8)
            withContext(Dispatchers.IO) {
                planStore(context, orbit.id).write(JSONObject().put("id", id).put("chatId", chatId)
                    .put("createdAt", System.currentTimeMillis()).put("terms", JSONArray(terms.map { it.trim() }))
                    .put("hits", JSONArray(hits.map { it.json() })).toString())
            }
            Planned(id, hits, scan.unscanned)
        }

    class Planned(val id: String, val hits: List<Hit>, val unscanned: List<String>)

    suspend fun cancel(context: Context, orbitId: String) = mutex.withLock { withContext(Dispatchers.IO) { planStore(context, orbitId).delete() } }

    data class Outcome(val cleaned: Map<String, Int>, val remaining: List<Hit>, val failed: List<String>, val unscanned: List<String>)

    /**
     * Carries out plan [planId]. [lastUserMessageAt] is the newest user message in the chat that made
     * the plan; the user must have answered after the plan was shown.
     */
    suspend fun confirm(context: Context, orbit: OrbitProfile, chatId: String, planId: String, lastUserMessageAt: Long): Outcome =
        mutex.withLock {
            val stored = withContext(Dispatchers.IO) { planStore(context, orbit.id).read() }
                ?.let(::JSONObject) ?: error("There is no forget plan; make one with plan first")
            check(stored.getString("id") == planId) { "That is not the latest plan; use the newest plan_id or make a new plan" }
            check(stored.getString("chatId") == chatId) { "The plan was made in another chat" }
            val createdAt = stored.getLong("createdAt")
            check(System.currentTimeMillis() - createdAt <= PLAN_TTL_MS) { "The plan is too old; make a new plan and show it to the user again" }
            check(lastUserMessageAt > createdAt) { "The user has not answered since the plan was made; show the plan and ask for confirmation first" }
            val terms = stored.getJSONArray("terms").let { a -> (0 until a.length()).map { a.getString(it) } }
            val planned = stored.getJSONArray("hits").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("where") } }.toSet()
            val matcher = Matcher(terms)
            val first = scan(context, orbit, matcher)
            check(!first.tooMany) { "The terms now match more than $MAX_PLACES places; make a narrower plan and ask again" }
            val current = first.hits
            val fresh = current.filter { it.locator.substringBefore("#L") !in planned.map { p -> p.substringBefore("#L") } }
            check(fresh.isEmpty()) {
                "The information now also lives in places the plan did not cover; make a revised plan and ask again"
            }
            val cleaned = mutableMapOf<String, Int>()
            val failed = mutableListOf<String>()
            suspend fun step(category: String, block: suspend () -> Int) {
                try { block().takeIf { it > 0 }?.let { cleaned[category] = (cleaned[category] ?: 0) + it } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { failed += category }
            }
            val dao = AppDatabase.getDatabase(context).orbitTaskDao()
            val spaceId = space(context, orbit)
            // A correction of memory: a background review that read the old messages is now outdated,
            // so nothing it found can be saved, and every chat rebuilds its memory prefix.
            step("learning") {
                try {
                    OrbitLearningBoundary.explicitEdit(context, spaceId) { }
                } catch (e: IllegalStateException) {
                    // Another correction landed between the read and the write; one more try settles it.
                    OrbitLearningBoundary.explicitEdit(context, spaceId) { }
                }
                0
            }
            // Future work first, so nothing writes the information back while it is being removed.
            step("scheduled") {
                val tasks = OrbitTaskRepository(context)
                current.filter { it.category == "scheduled" }.sumOf { hit ->
                    val id = hit.locator.removePrefix("task:")
                    tasks.changeState(orbit.id, id, "CANCELLED")
                    dao.task(id)?.let { t ->
                        dao.updateTask(t.copy(title = matcher.clean(t.title).ifBlank { "-" }, instruction = matcher.clean(t.instruction),
                            lastObservation = matcher.clean(t.lastObservation), version = t.version + 1, updatedAt = System.currentTimeMillis()))
                    }
                    1L
                }.toInt()
            }
            step("reports") {
                val ids = current.filter { it.category == "reports" }.map { it.locator.removePrefix("delivery:") }.toSet()
                dao.pendingDeliveries(orbit.id).filter { it.id in ids }.onEach { dao.updateDelivery(it.copy(state = "CANCELLED")) }.size
            }
            step("history") { MemoryReviewRepository(context, spaceId).forgetLines(matcher::hit).size }
            step("memory") {
                val notes = MemoryNotesRepository(context, spaceId)
                val snapshot = notes.load()
                val next = matcher.clean(snapshot.markdown)
                if (next == snapshot.markdown) 0 else { notes.save(next, snapshot.version); matcher.lines(snapshot.markdown).size }
            }
            step("profile") {
                val profile = UserProfileDocumentRepository.forMemorySpace(context, spaceId)
                val text = profile.load()
                val next = matcher.clean(text)
                if (next == text) 0 else { profile.saveIfUnchanged(next, text); matcher.lines(text).size }
            }
            step("files") {
                val files = OrbitFiles.of(context, orbit.id)
                current.filter { it.category == "files" }.groupBy { it.locator.substringBefore("#L") }.entries.sumOf { (path, hits) ->
                    if (hits.any { it.action == "delete" }) {
                        files.delete(path)
                    } else {
                        val text = files.read(path)
                        files.write(path, matcher.clean(text))
                    }
                    1L
                }.toInt()
            }
            step("skills") {
                val skills = LearnedSkillRepository(context, spaceId)
                current.filter { it.category == "skills" }.map { it.locator.substringBefore("#L").removePrefix("skills/") }.distinct()
                    .sumOf { ref ->
                        val name = ref.substringBefore('/')
                        val path = ref.substringAfter('/')
                        val snapshot = skills.read(name, path)
                        skills.apply(spaceId, name, path, matcher.clean(snapshot.text), snapshot.version)
                        1L
                    }.toInt()
            }
            step("goals") {
                val goals = OrbitGoalRepository(context)
                current.filter { it.category == "goals" }.sumOf { hit ->
                    dao.goal(hit.locator.removePrefix("goal:"))?.let { goal ->
                        val titleHit = matcher.hit(goal.title)
                        goals.update(goal, if (titleHit) "-" else goal.title, matcher.clean(goal.plan),
                            if (titleHit) "CANCELLED" else goal.state)
                    }
                    1L
                }.toInt()
            }
            step("created") {
                current.filter { it.category == "created" }.sumOf { hit ->
                    val board = OrbitBoardRepository.Board.valueOf(hit.locator.substringBefore(':').uppercase())
                    OrbitBoardRepository(context, orbit.id, board).delete(hit.locator.substringAfter(':'))
                    1L
                }.toInt()
            }
            step("outputs") {
                val root = OrbitPreferences.getInstance(context).orbitDir(orbit.id).canonicalFile
                current.filter { it.category == "outputs" }.sumOf { hit ->
                    val id = hit.locator.removePrefix("artifact:")
                    dao.artifact(id)?.takeIf { it.orbitId == orbit.id }?.let { version ->
                        val file = File(root, version.relativePath).canonicalFile
                        if (file.toPath().startsWith(root.toPath()) && file.exists()) check(file.delete()) { "Could not delete ${version.name}" }
                        dao.deleteArtifact(id)
                    }
                    1L
                }.toInt()
            }
            step("approvals") {
                val ids = current.filter { it.category == "approvals" }.map { it.locator.removePrefix("approval:") }.toSet()
                if (ids.isEmpty()) 0 else OrbitApprovals.forget(context, orbit.id, ids, matcher::clean, matcher::hit)
            }
            val after = scan(context, orbit, matcher)
            val remaining = after.hits
            val unread = after.unscanned + if (after.tooMany) listOf("more matches than could be listed") else emptyList()
            // Only a clean run joins the forgotten list; otherwise the user could not fix what is left
            // because every write mentioning it would be refused. Cleanup has already happened either way.
            if (failed.isEmpty() && unread.isEmpty() && remaining.none { it.category != USER_REVIEW }) {
                // Without the list, later writes are not checked against it; that is not "all clear".
                step("forgotten_list") { remember(context, orbit.id, planId, terms, current.map { it.locator }); 0 }
            }
            withContext(Dispatchers.IO) { planStore(context, orbit.id).delete() }
            Outcome(cleaned, remaining, failed, unread)
        }

    /** The forgotten list: plan ids, locators and salted hashes of the search terms, never the text. */
    private suspend fun remember(context: Context, orbitId: String, planId: String, terms: List<String>, locators: List<String>) =
        withContext(Dispatchers.IO) {
            val store = forgottenStore(context, orbitId)
            val data = store.read()?.let(::JSONObject) ?: JSONObject().put("salt", ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }).put("terms", JSONArray()).put("records", JSONArray())
            val salt = data.getString("salt")
            val known = data.getJSONArray("terms")
            val have = (0 until known.length()).map { known.getJSONObject(it).getString("hash") }.toMutableSet()
            terms.map(::normalize).filter { it.length >= 2 }.forEach { term ->
                val hash = sha(salt + term)
                if (have.add(hash)) known.put(JSONObject().put("length", term.length).put("hash", hash))
            }
            data.getJSONArray("records").put(JSONObject().put("plan", planId).put("at", System.currentTimeMillis())
                .put("locators", JSONArray(locators.take(500))))
            store.write(data.toString())
        }

    private fun sha(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private val HEX = "0123456789abcdef".toCharArray()
    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** True when [text] contains something the user had this Orbit forget. */
    suspend fun containsForgotten(context: Context, orbitId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        val data = runCatching { forgottenStore(context, orbitId).read() }.getOrNull()?.let(::JSONObject) ?: return@withContext false
        val salt = data.getString("salt")
        val terms = data.getJSONArray("terms")
        val byLength = (0 until terms.length()).map { terms.getJSONObject(it) }
            .groupBy({ it.getInt("length") }, { it.getString("hash") }).mapValues { it.value.toSet() }
        val t = normalize(text)
        // One digest for the whole scan (a long file has many windows); the salt goes in first as
        // in [sha], so the hashes match the stored ones.
        val digest = MessageDigest.getInstance("SHA-256")
        val saltBytes = salt.toByteArray()
        byLength.any { (length, hashes) ->
            (0..t.length - length).any { start ->
                if (start and 0x3ff == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                digest.update(saltBytes)
                digest.update(t.substring(start, start + length).toByteArray())
                hex(digest.digest()) in hashes
            }
        }
    }

    /** The Orbit that owns memory space [spaceId], if any. */
    suspend fun orbitForSpace(context: Context, spaceId: String): String? =
        OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.memorySpaceId == spaceId }?.id
}
