package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Forgetting for one Orbit: find where the information is stored, show the user, and after they agree
 * remove it. Background learning reads the chat too; its prompts tell it to drop what the user asked
 * to forget, so nothing here stops or rewinds it.
 */
internal object OrbitForget {
    /** Places listed to the model at most; the summary still counts them all. */
    const val SHOWN = 100

    /** The places to list: unchecked ones first, as the model must name each; the summary counts the rest. */
    fun shown(hits: List<Hit>): List<Hit> = hits.sortedBy { if (it.category == UNCHECKED) 0 else 1 }.take(SHOWN)
    /** Places the app does not edit by itself; the user changes them by hand. */
    const val USER_REVIEW = "identity"
    /** The user's own documents in files/ (a downloaded novel, a draft): listed for the user, never changed here. */
    const val DOCUMENTS = "documents"
    /** Orbit's own text files too big to search: listed so the report does not claim they were checked. */
    const val UNCHECKED = "unchecked"
    /** A document is searched a part at a time up to this size; a bigger one is listed as unchecked. */
    private const val MAX_SEARCH_BYTES = 64L * 1024 * 1024
    private val mutex = Mutex()
    private class Plan(val id: String, val chatId: String, val createdAt: Long, val terms: List<String>)
    /** The latest plan of each Orbit; a restart drops it and Orbit plans again. */
    private val plans = ConcurrentHashMap<String, Plan>()

    /** One place: a whole file or item, or the matching lines of one file ("path#L3,7"). */
    /** [reason] says why an unchecked place was not searched: too_big, changed (while read) or unreadable. */
    data class Hit(val category: String, val locator: String, val action: String, val reason: String? = null) {
        fun json(): JSONObject = JSONObject().put("category", category).put("where", locator).put("action", action)
            .apply { reason?.let { put("reason", it) } }
    }

    class Matcher(terms: List<String>) {
        private val normalized = terms.map(::normalize).filter { it.length >= 2 }
        init { require(normalized.isNotEmpty()) { "Give 1-10 search terms of at least 2 characters" } }
        fun hit(text: String): Boolean = normalize(text).let { t -> normalized.any { t.contains(it) } }
        fun clean(text: String): String = text.lines().filterNot(::hit).joinToString("\n")
        fun lines(text: String): List<Int> = text.lines().mapIndexedNotNull { i, line -> if (hit(line)) i + 1 else null }
    }

    private val SPACES = Regex("\\s+")

    fun normalize(text: String) = text.lowercase().replace(SPACES, " ").trim()

    private suspend fun space(context: Context, orbit: OrbitProfile) = OrbitMemoryScope.ensureInitialized(context, orbit)

    /** The kinds of place a search looks through, for the report. */
    val SCANNED = listOf("memory", "profile", "files (people, groups, daily log, nightly reviews, alignment, lessons, hook scripts)",
        "the user's text documents in files/ (listed only; PDF, Word, pictures and other non-text files are not searched)",
        "SOUL.md", "IDENTITY.md", "skills Orbit learned", "goals", "scheduled", "feed and ideas")

    /** Where a file matches: the first few line numbers and how many lines in all. */
    private class Found(val first: List<Int>, val total: Int)

    /**
     * The lines of [file] that [matcher] finds, read a part at a time in its own encoding; null when it
     * is too big to search. A file that changes while it is read throws [OrbitTextFiles.Changed]. Only
     * the first few line numbers are kept.
     */
    private suspend fun searchLarge(file: java.io.File, matcher: Matcher): Found? {
        if (!file.isFile) throw java.io.FileNotFoundException(file.name)
        if (file.length() > MAX_SEARCH_BYTES) return null
        val stamp = OrbitTextFiles.Stamp.of(file)
        val charset = OrbitTextFiles.charsetOf(file)
        val first = mutableListOf<Int>()
        var total = 0
        var offset = 0L
        var line = 0
        // The start of a line too long for one part, kept to the next; a match in it is remembered.
        var carry = ""
        var carryHit = false
        fun count(text: String, hitBefore: Boolean) {
            line++
            if (hitBefore || matcher.hit(text)) {
                total++
                if (first.size < 10) first += line
            }
        }
        do {
            kotlinx.coroutines.yield()
            val part = OrbitTextFiles.part(file, offset, 512 * 1024, charset, stamp, strict = true)
            val text = carry + part.text
            val ended = !part.more || text.endsWith('\n')
            // Only a line break the part ends with is dropped (with its CR); a lone CR stays a break.
            val lines = (if (text.endsWith('\n')) text.removeSuffix("\n").removeSuffix("\r") else text).lines()
            // A cut between CR and LF: the CR waits with the rest of its line, so CRLF stays one break.
            val splitCrlf = !ended && text.endsWith('\r')
            val open = if (splitCrlf) text.dropLast(1).lines() else lines
            val whole = if (ended) lines else open.dropLast(1)
            whole.forEachIndexed { i, t -> count(t, i == 0 && carryHit) }
            if (whole.isNotEmpty()) carryHit = false
            if (ended) {
                carry = ""
            } else {
                carry = open.last() + if (splitCrlf) "\r" else ""
                // A line longer than any term needs only its end kept to find a match across the cut.
                if (carry.length > CARRY_CHARS) {
                    carryHit = carryHit || matcher.hit(carry)
                    // Kept as the matcher sees it (spaces folded), with one space if the line ended in
                    // spaces, so a term split by the cut and by runs of spaces still matches.
                    val tail = normalize(carry).takeLast(MAX_TERM_CHARS * 2)
                    carry = when {
                        // The CR before a cut LF still ends the line once the LF comes.
                        splitCrlf -> tail + '\r'
                        carry.last().isWhitespace() -> "$tail "
                        else -> tail
                    }
                }
            }
            offset = part.next
        } while (part.more)
        return Found(first, total)
    }

    /**
     * Of files/, the hook scripts tasks registered that are still exactly what was registered: Orbit's
     * own. A script replaced since then (its task refuses to run it) is the user's file.
     */
    private fun ownHooks(files: OrbitFiles, sources: List<String>): Set<String> =
        sources.mapNotNull { OrbitWatchSources.hook(it) }.filter { hook ->
            runCatching {
                val file = files.fileFor(hook.path)
                file.isFile && file.length() <= OrbitFiles.MAX_TEXT * 4L &&
                    OrbitTextFiles.readStrictUtf8(file)?.let(OrbitWatchSources::hash) == hook.hash
            }.getOrDefault(false)
        }.map { it.path }.toSet()

    private const val CARRY_CHARS = 64 * 1024
    private const val MAX_TERM_CHARS = 200

    /**
     * Read-only: every place [matcher] finds the information. Without [documents] the user's documents
     * are not searched, for a step that only cleans what Orbit keeps.
     */
    suspend fun scan(context: Context, orbit: OrbitProfile, matcher: Matcher, documents: Boolean = true): List<Hit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<Hit>()
        // One place per file, whatever the number of matching lines, so a common word cannot fill memory.
        fun found(category: String, path: String, found: Found, action: String) {
            if (found.total == 0) return
            val shown = found.first.joinToString(",") + if (found.total > found.first.size) ",+${found.total - found.first.size}" else ""
            hits += Hit(category, "$path#L$shown", action)
        }
        fun lines(category: String, path: String, text: String, action: String = "edit") {
            val all = matcher.lines(text)
            found(category, path, Found(all.take(10), all.size), action)
        }
        val spaceId = space(context, orbit)
        lines("memory", "memory.md", MemoryNotesRepository(context, spaceId).load().markdown)
        lines("profile", "user.md", UserProfileDocumentRepository.forMemorySpace(context, spaceId).load())
        val files = OrbitFiles.of(context, orbit.id)
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val hooks = ownHooks(files, dao.tasks(orbit.id).map { it.source })
        // INDEX.md is rebuilt by the app from the person and group files, so it follows their cleanup.
        files.list(includeReview = true).filterNot { OrbitFiles.isIndex(it.path) }.forEach { entry ->
            // The name without its extension, so a term like "md" cannot match every file.
            val name = entry.path.substringAfterLast('/').let { it.substringBeforeLast('.', it) }
            // The user's documents are theirs: where they match is listed, and they decide what to change.
            if (files.isUserDocument(entry.path, hooks)) {
                if (!documents) return@forEach
                val named = matcher.hit(name)
                if (!OrbitTextFiles.isText(entry.path)) {
                    if (named) hits += Hit(DOCUMENTS, entry.path, "user_review")
                    return@forEach
                }
                // Any size and encoding, a part at a time; one that could not be searched says why.
                var reason: String? = null
                val matched = try {
                    searchLarge(files.fileFor(entry.path), matcher).also { if (it == null) reason = "too_big" }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: OrbitTextFiles.Changed) { reason = "changed"; null
                } catch (e: Exception) { reason = "unreadable"; null }
                // One entry per document: its matching lines, or the whole file when only its name matches.
                when {
                    matched != null && matched.total > 0 -> found(DOCUMENTS, entry.path, matched, "user_review")
                    named -> hits += Hit(DOCUMENTS, entry.path, "user_review")
                }
                if (matched == null) hits += Hit(UNCHECKED, entry.path, "user_review", reason)
                return@forEach
            }
            val deletable = entry.path !in OrbitFiles.SINGLE && entry.path !in OrbitFiles.ALIGNMENT_DATA
            if (deletable && matcher.hit(name)) {
                hits += Hit("files", entry.path, "delete")
            } else if (OrbitFiles.isText(entry.path) && entry.size >= 0) {
                // A notebook not written yet (size -1) is only its template: nothing of the user's is in it.
                // Orbit's own files are cleaned line by line; one too big to read here is not skipped silently.
                val text = if (entry.size <= OrbitFiles.MAX_TEXT * 4L) runCatching { files.read(entry.path) }.getOrNull() else null
                if (text != null) lines("files", entry.path, text)
                else hits += Hit(UNCHECKED, entry.path, "user_review", if (entry.size > OrbitFiles.MAX_TEXT * 4L) "too_big" else "unreadable")
            }
        }
        // Soul and identity are the Orbit's own voice: listed for the user, never edited by the app.
        runCatching { OrbitSoulRepository(context, orbit.id).snapshot().current.body }.getOrNull()
            ?.let { lines(USER_REVIEW, "SOUL.md", it, "user_review") }
        runCatching { OrbitPromptFiles.identity(context, orbit.id) }.getOrNull()
            ?.let { lines(USER_REVIEW, "IDENTITY.md", it, "user_review") }
        // The installed skills are listed once for the whole scan; each file is then read straight from disk.
        runCatching {
            val installed = com.ai.assistance.operit.core.tools.skill.SkillManager.forMemorySpace(context, spaceId).getAvailableSkills()
            LearnedSkillRepository(context, spaceId).owned(spaceId, installed).forEach { name ->
                val root = installed[name]?.directory?.canonicalFile ?: return@forEach
                root.walkTopDown().maxDepth(5).filter { it.isFile && it.length() <= 256_000 &&
                    it.canonicalFile.toPath().startsWith(root.toPath()) }.take(100).forEach { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    // Only files the skill store can edit, so confirm can clean every one it lists.
                    if (runCatching { LearnedSkillRepository.validatePath(path) }.isFailure) return@forEach
                    runCatching { file.readText() }.getOrNull()?.let { lines("skills", "skills/$name/$path", it) }
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        dao.goals(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.plan) }.forEach {
            hits += Hit("goals", "goal:${it.id}", if (matcher.hit(it.title)) "stop" else "edit")
        }
        // A WATCH task whose script is cleaned or deleted here could not run it any more: it stops too.
        val touchedFiles = hits.filter { it.category == "files" }.map { it.locator.substringBefore("#L") }.toSet()
        dao.tasks(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.instruction) || matcher.hit(it.lastObservation) ||
            OrbitWatchSources.hook(it.source)?.path in touchedFiles }
            .forEach { hits += Hit("scheduled", "task:${it.id}", "stop") }
        OrbitBoardRepository.Board.values().forEach { board ->
            val repository = OrbitBoardRepository(context, orbit.id, board)
            repository.listAll().filter { item ->
                val fields = listOf(item.title, item.body, item.extra, item.rationale) + item.notes.map { it.text } +
                    item.parts.flatMap { listOf(it.title, it.detail) } + item.sources
                fields.any(matcher::hit) || item.card && repository.readCard(item.id)?.let(matcher::hit) == true
            }.forEach { hits += Hit("created", "${board.name.lowercase()}:${it.id}", "delete") }
        }
        hits
    }

    /** Finds the places and keeps the plan, replacing any older one. */
    suspend fun plan(context: Context, orbit: OrbitProfile, chatId: String, terms: List<String>): Planned =
        mutex.withLock {
            plans.remove(orbit.id)
            require(terms.size in 1..10 && terms.all { it.trim().length in 2..200 }) { "Give 1-10 search terms of 2-200 characters" }
            val clean = terms.map { it.trim() }
            val hits = scan(context, orbit, Matcher(clean))
            val id = UUID.randomUUID().toString().take(8)
            plans[orbit.id] = Plan(id, chatId, System.currentTimeMillis(), clean)
            Planned(id, hits)
        }

    class Planned(val id: String, val hits: List<Hit>)

    suspend fun cancel(orbitId: String) = mutex.withLock { plans.remove(orbitId); Unit }

    data class Outcome(val cleaned: Map<String, Int>, val remaining: List<Hit>, val failed: List<String>)

    /**
     * Carries out plan [planId]. [lastUserMessageAt] is the newest user message in the chat that made
     * the plan; the user must have answered after the plan was shown.
     */
    suspend fun confirm(context: Context, orbit: OrbitProfile, chatId: String, planId: String, lastUserMessageAt: Long): Outcome =
        mutex.withLock {
            val plan = plans[orbit.id] ?: error("There is no forget plan; make one with plan first")
            check(plan.id == planId) { "That is not the latest plan; use the newest plan_id or make a new plan" }
            check(plan.chatId == chatId) { "The plan was made in another chat" }
            check(lastUserMessageAt > plan.createdAt) { "The user has not answered since the plan was made; show the plan and ask for confirmation first" }
            val matcher = Matcher(plan.terms)
            // Only what Orbit keeps is cleaned, so the user's documents are not searched again here.
            val current = scan(context, orbit, matcher, documents = false)
            val cleaned = mutableMapOf<String, Int>()
            val failed = mutableListOf<String>()
            suspend fun step(category: String, block: suspend () -> Int) {
                try { block().takeIf { it > 0 }?.let { cleaned[category] = (cleaned[category] ?: 0) + it } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { failed += category }
            }
            val dao = AppDatabase.getDatabase(context).orbitTaskDao()
            val spaceId = space(context, orbit)
            // Future work first, so nothing writes the information back while it is being removed.
            step("scheduled") {
                val tasks = OrbitTaskRepository(context)
                var missed = false
                val count = current.filter { it.category == "scheduled" }.sumOf { hit ->
                    val id = hit.locator.removePrefix("task:")
                    // Changed meanwhile: it stays as it is, and is not counted as cleaned.
                    if (!tasks.changeState(orbit.id, id, "CANCELLED")) {
                        missed = true
                        return@sumOf 0L
                    }
                    dao.task(id)?.let { t ->
                        dao.updateTask(t.copy(title = matcher.clean(t.title).ifBlank { "-" }, instruction = matcher.clean(t.instruction),
                            lastObservation = matcher.clean(t.lastObservation), version = t.version + 1, updatedAt = System.currentTimeMillis()))
                    }
                    1L
                }.toInt()
                if (missed) failed += "scheduled"
                count
            }
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
                // Each file is checked again under the lock it is changed with ([OrbitFiles.cleanOwned]).
                val hooks = dao.tasks(orbit.id).mapNotNull { OrbitWatchSources.hook(it.source) }.associate { it.path to it.hash }
                // A script still run by a task that could not be stopped above stays as it is.
                val running = dao.tasks(orbit.id).filter { it.state != "CANCELLED" }
                    .mapNotNull { OrbitWatchSources.hook(it.source)?.path }.toSet()
                var kept = false
                val count = current.filter { it.category == "files" }.groupBy { it.locator.substringBefore("#L") }.entries.sumOf { (path, hits) ->
                    // Never the user's documents, even when a link leads one of Orbit's paths into files/.
                    // One file that cannot be cleaned stays and is reported; the rest are still cleaned.
                    val done = path !in running && try {
                        files.cleanOwned(path, hooks, hits.any { it.action == "delete" }, matcher::clean)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
                    if (!done) {
                        kept = true
                        return@sumOf 0L
                    }
                    1L
                }.toInt()
                if (kept) failed += "files"
                count
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
            plans.remove(orbit.id)
            // A new memory generation stops a learner that already read the old memory from saving it.
            // Open chats keep their prompt until the daily refresh or their next summary.
            step("learning guard") { OrbitLearningBoundary.explicitEdit(context, spaceId) {}; 0 }
            Outcome(cleaned, scan(context, orbit, matcher), failed)
        }
}
