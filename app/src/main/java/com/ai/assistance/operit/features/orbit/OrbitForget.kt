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
    /** Places the app does not edit by itself; the user changes them by hand. */
    const val USER_REVIEW = "identity"
    private val mutex = Mutex()
    private class Plan(val id: String, val chatId: String, val createdAt: Long, val terms: List<String>)
    /** The latest plan of each Orbit; a restart drops it and Orbit plans again. */
    private val plans = ConcurrentHashMap<String, Plan>()

    /** One place: a whole file or item, or the matching lines of one file ("path#L3,7"). */
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

    private suspend fun space(context: Context, orbit: OrbitProfile) = OrbitMemoryScope.ensureInitialized(context, orbit)

    /** The kinds of place a search looks through, for the report. */
    val SCANNED = listOf("memory", "profile", "files (people, groups, daily log, nightly reviews, alignment, files/)",
        "SOUL.md", "IDENTITY.md", "skills Orbit learned", "goals", "scheduled", "feed and ideas")

    /** Read-only: every place [matcher] finds the information. */
    suspend fun scan(context: Context, orbit: OrbitProfile, matcher: Matcher): List<Hit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<Hit>()
        // One place per file, whatever the number of matching lines, so a common word cannot fill memory.
        fun lines(category: String, path: String, text: String, action: String = "edit") {
            val found = matcher.lines(text)
            if (found.isEmpty()) return
            val shown = found.take(10).joinToString(",") + if (found.size > 10) ",+${found.size - 10}" else ""
            hits += Hit(category, "$path#L$shown", action)
        }
        val spaceId = space(context, orbit)
        lines("memory", "memory.md", MemoryNotesRepository(context, spaceId).load().markdown)
        lines("profile", "user.md", UserProfileDocumentRepository.forMemorySpace(context, spaceId).load())
        val files = OrbitFiles.of(context, orbit.id)
        // INDEX.md is rebuilt by the app from the person and group files, so it follows their cleanup.
        files.list(includeReview = true).filterNot { OrbitFiles.isIndex(it.path) }.forEach { entry ->
            val deletable = entry.path !in OrbitFiles.SINGLE && entry.path !in OrbitFiles.ALIGNMENT_DATA
            // The name without its extension, so a term like "md" cannot match every file.
            val name = entry.path.substringAfterLast('/').let { it.substringBeforeLast('.', it) }
            if (deletable && matcher.hit(name)) {
                hits += Hit("files", entry.path, "delete")
            } else if (OrbitFiles.isText(entry.path) && entry.size <= OrbitFiles.MAX_TEXT) {
                runCatching { files.read(entry.path) }.getOrNull()?.let { lines("files", entry.path, it) }
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
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        dao.goals(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.plan) }.forEach {
            hits += Hit("goals", "goal:${it.id}", if (matcher.hit(it.title)) "stop" else "edit")
        }
        dao.tasks(orbit.id).filter { matcher.hit(it.title) || matcher.hit(it.instruction) || matcher.hit(it.lastObservation) }
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
            val current = scan(context, orbit, matcher)
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
                    if (hits.any { it.action == "delete" }) files.delete(path)
                    else files.write(path, matcher.clean(files.read(path)))
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
            plans.remove(orbit.id)
            // A new memory generation rebuilds the prompts of open chats, so none keeps the old memory.
            step("prompt refresh") { OrbitLearningBoundary.explicitEdit(context, spaceId) {}; 0 }
            Outcome(cleaned, scan(context, orbit, matcher), failed)
        }
}
