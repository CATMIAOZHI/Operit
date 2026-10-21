package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.util.ChatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Orbit's own background routines, like Muse's: the Feed (a new post every 12 hours) and the Ideas
 * (new ideas every morning at 9), both written by a background subagent, the nightly alignment
 * review (Muse's dream: how working with the user went, written into the alignment synthesis every
 * chat carries), plus a morning goal alignment: a background subagent checks each active goal against
 * its steps, writes a briefing on the Goals tab and drafts a proactive item only when something needs
 * the user (Muse's research worker; the chat decides whether it is sent). An older nightly self-review is still
 * kept for Orbits that have it. They are ordinary Orbit tasks shown in the schedule list. The Feed,
 * Ideas and alignment routines can be paused or rescheduled (also from the chat) but never
 * cancelled. Each routine is made once per Orbit; a cancelled one is not brought back.
 */
internal object OrbitRoutines {
    /** The recent Orbit conversation; the run is skipped when it has not changed since the last one. */
    const val CHAT_SOURCE = "orbit:chat"
    /** The user's active goals; the run is skipped when there are none. */
    const val GOALS_SOURCE = "orbit:goals"
    /** The feed brief; the run always writes. */
    const val FEED_SOURCE = "orbit:feed"
    /** The recent Orbit conversation; the run always looks for ideas, even with nothing new said. */
    const val IDEAS_SOURCE = "orbit:ideas"
    /** Every Orbit chat since the last alignment review; the run is skipped when the user said nothing. */
    const val ALIGNMENT_SOURCE = "orbit:alignment"
    private const val SELF_REVIEW = "routine-self-review"
    private const val GOAL_BRIEFING = "routine-goal-briefing"
    private const val FEED = "routine-feed"
    private const val IDEAS = "routine-ideas"
    private const val ALIGNMENT = "routine-alignment"
    private const val SELF_REVIEW_MINUTE = 3 * 60 + 30
    private const val GOAL_BRIEFING_MINUTE = 7 * 60
    /** Late at night, when the day's talk is over. */
    private const val ALIGNMENT_MINUTE = 3 * 60 + 30
    /** How far back the first review looks, and the longest window any review reads. */
    private const val MAX_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    private const val FIRST_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    /** Room for the messages in a review's source; the run keeps 20,000 characters of it. */
    private const val WINDOW_TEXT_BUDGET = 18_000
    /** A new feed post every 12 hours by default. */
    const val FEED_INTERVAL_MINUTES = 12 * 60
    /** New ideas every morning at 9 by default. */
    const val IDEAS_MINUTE = 9 * 60
    /** Stays inside the normal background lane, so no foreground notification at night. */
    private const val TIMEOUT_MINUTES = 8

    fun isRoutineSource(source: String) =
        source in setOf(CHAT_SOURCE, GOALS_SOURCE, FEED_SOURCE, IDEAS_SOURCE, ALIGNMENT_SOURCE)

    /** The same id OrbitTaskRepository.create gives a request, so making a routine again finds the old one. */
    fun taskId(orbitId: String, request: String): String =
        UUID.nameUUIDFromBytes("$orbitId:$request".toByteArray()).toString()

    fun selfReviewId(orbitId: String) = taskId(orbitId, SELF_REVIEW)
    fun goalBriefingId(orbitId: String) = taskId(orbitId, GOAL_BRIEFING)
    fun feedId(orbitId: String) = taskId(orbitId, FEED)
    fun ideasId(orbitId: String) = taskId(orbitId, IDEAS)
    fun alignmentId(orbitId: String) = taskId(orbitId, ALIGNMENT)

    suspend fun ensureSelfReview(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, SELF_REVIEW, R.string.orbit_routine_self_review, CHAT_SOURCE, "DAILY", SELF_REVIEW_MINUTE,
            background = true) {
            if (it) SELF_REVIEW_EN else SELF_REVIEW_ZH
        }
    }

    suspend fun ensureGoalBriefing(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, GOAL_BRIEFING, R.string.orbit_routine_goal_briefing, GOALS_SOURCE, "DAILY", GOAL_BRIEFING_MINUTE,
            background = true, upgrade = ::upgradeGoalBriefing) {
            if (it) GOAL_ALIGN_EN else GOAL_ALIGN_ZH
        }
    }

    /**
     * The goal briefing used to wake the main chat each morning. It now runs in the background as the
     * goal alignment; an untouched old one gets the new title and instruction with the lane switch.
     */
    private fun upgradeGoalBriefing(context: Context, old: OrbitTaskEntity): OrbitTaskEntity {
        if (old.instruction.trim() !in setOf(GOAL_BRIEFING_ZH, GOAL_BRIEFING_EN)) return old
        val english = old.instruction.trim() == GOAL_BRIEFING_EN
        return old.copy(title = context.getString(R.string.orbit_routine_goal_briefing),
            instruction = if (english) GOAL_ALIGN_EN else GOAL_ALIGN_ZH)
    }

    /**
     * The Feed routine: a background subagent writes a post every 12 hours, the first one right
     * away. It follows the user's brief, or the default one when there is none. The daily post task
     * the Feed used before is retired so posts are not written twice.
     */
    suspend fun ensureFeed(context: Context, orbit: OrbitProfile) {
        val repository = OrbitTaskRepository(context)
        repository.dao.liveTasksTitled(orbit.id, "ASSIST", "DAILY", OrbitFeedGenerator.DAILY_TITLE)
            .filterNot { isProtected(it) }.forEach { old ->
            try {
                repository.changeState(orbit.id, old.id, "CANCELLED")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitRoutines", "Old daily feed task not retired: ${e.message}")
            }
        }
        ensure(context, orbit, FEED, R.string.orbit_routine_feed, FEED_SOURCE, "INTERVAL", 0,
            interval = FEED_INTERVAL_MINUTES, background = true) { if (it) FEED_EN else FEED_ZH }
    }

    /** The Ideas routine: a background subagent comes up with new ideas every morning at 9. */
    suspend fun ensureIdeas(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, IDEAS, R.string.orbit_routine_ideas, IDEAS_SOURCE, "DAILY", IDEAS_MINUTE,
            background = true) { if (it) IDEAS_EN else IDEAS_ZH }
    }

    /**
     * The nightly alignment review, like Muse's dream: a background subagent reads the day's chats,
     * counts corrections, keeps the repair threads and rewrites the alignment synthesis.
     */
    suspend fun ensureAlignment(context: Context, orbit: OrbitProfile) {
        // The older nightly self-review also rewrote the synthesis; this review replaces it.
        val repository = OrbitTaskRepository(context)
        repository.dao.task(selfReviewId(orbit.id))?.takeIf { it.state != "CANCELLED" }?.let { old ->
            try {
                repository.changeState(orbit.id, old.id, "CANCELLED")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitRoutines", "Old self-review not retired: ${e.message}")
            }
        }
        ensure(context, orbit, ALIGNMENT, R.string.orbit_routine_alignment, ALIGNMENT_SOURCE, "DAILY", ALIGNMENT_MINUTE,
            background = true) { if (it) ALIGNMENT_EN else ALIGNMENT_ZH }
    }

    /**
     * Runs the Feed or Ideas routine now (the "write one" and "think of some" buttons). Makes the
     * routine first if it is missing. False when it is paused or could not be made.
     */
    suspend fun runNow(context: Context, orbitId: String, feed: Boolean): Boolean {
        val orbit = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
            ?: return false
        if (feed) ensureFeed(context, orbit) else ensureIdeas(context, orbit)
        val repository = OrbitTaskRepository(context)
        val task = repository.dao.task(if (feed) feedId(orbitId) else ideasId(orbitId))?.takeIf { it.state == "ACTIVE" }
            ?: return false
        // A run already waiting is the one asked for; queuing it again would only run it twice.
        if (repository.dao.enqueueManual(task.id, UUID.randomUUID().toString(), System.currentTimeMillis())) {
            OrbitTaskScheduler.reschedule(context)
        }
        return true
    }

    /**
     * Makes the goal alignment for an Orbit that already has goals, e.g. ones made before it existed,
     * and moves an old morning briefing to the background.
     */
    suspend fun ensureGoalBriefingIfGoals(context: Context, orbit: OrbitProfile) {
        val dao = OrbitTaskRepository(context).dao
        dao.task(goalBriefingId(orbit.id))?.let { old ->
            if (old.state != "CANCELLED" && !old.useSubagent) ensureGoalBriefing(context, orbit)
            return
        }
        if (dao.goals(orbit.id).any { it.state != "CANCELLED" }) ensureGoalBriefing(context, orbit)
    }

    /** True for the tasks Orbit makes by itself; their instructions are written for the model. */
    fun isRoutine(task: OrbitTaskEntity) =
        task.id == selfReviewId(task.orbitId) || task.id == goalBriefingId(task.orbitId) || isProtected(task)

    /**
     * The Feed, Ideas and alignment routines: two tabs and every chat's alignment synthesis live on
     * them, so they can be paused and rescheduled but never cancelled, and what they do (title,
     * instruction, lane) stays as Orbit made it.
     */
    fun isProtected(task: OrbitTaskEntity) = task.id == feedId(task.orbitId) || task.id == ideasId(task.orbitId) ||
        task.id == alignmentId(task.orbitId)

    /** Why a skipped run was skipped: the goal briefing and the alignment review skip for their own reasons. */
    fun skippedReason(context: Context, task: OrbitTaskEntity): String = context.getString(
        when (task.source) {
            GOALS_SOURCE -> R.string.orbit_decision_no_active_goals
            ALIGNMENT_SOURCE -> R.string.orbit_decision_no_new_talk
            else -> R.string.orbit_watch_notifications_starting
        }
    )

    private suspend fun ensure(
        context: Context, orbit: OrbitProfile, request: String, title: Int, source: String, scheduleMode: String,
        minute: Int, interval: Int = 1440, background: Boolean,
        upgrade: (Context, OrbitTaskEntity) -> OrbitTaskEntity = { _, it -> it },
        instruction: (english: Boolean) -> String,
    ) {
        val repository = OrbitTaskRepository(context)
        // Every routine works quietly: a background subagent does it, so Orbit's chat is never woken.
        // What it drafts for the user goes through the proactive handoff, where the chat decides.
        repository.dao.task(taskId(orbit.id, request))?.let { old ->
            // Switching lanes replaces the definition, which would cancel a run in progress; it
            // waits for the run to end, checked in the same transaction as the switch.
            if (old.useSubagent != background && old.state != "CANCELLED") OrbitExecutionGate.serialized {
                repository.dao.replaceDefinitionIfIdle(upgrade(context, old).copy(useSubagent = background), old.version)
            }
            return
        }
        try {
            val english = LocaleUtils.usesEnglishPrompt(context)
            repository.create(orbit, context.getString(title), instruction(english), source, interval, "CHANGES", request,
                kind = "ASSIST", scheduleMode = scheduleMode, dailyMinute = minute, timeoutMinutes = TIMEOUT_MINUTES,
                useSubagent = background)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A full task list (20 unfinished tasks) only means the routine waits for a free slot.
            AppLogger.w("OrbitRoutines", "Routine $request not created: ${e.message}")
        }
    }

    /**
     * What a routine run reads instead of a web page. Null means the usual recent conversation.
     * An empty string means there is nothing to do, and the run is skipped.
     */
    suspend fun source(context: Context, task: OrbitTaskEntity): String? {
        if (task.kind == "ASSIST" && task.source == FEED_SOURCE) {
            return "FEED BRIEF (the user's words; it only steers what to write about):\n" +
                OrbitFeedGenerator.effectiveBrief(context, task.orbitId).take(OrbitBoardRepository.MAX_BRIEF)
        }
        if (task.kind == "ASSIST" && task.source == ALIGNMENT_SOURCE) return alignmentWindow(context, task)
        if (task.kind != "ASSIST" || task.source != GOALS_SOURCE) return null
        val dao = OrbitTaskRepository(context).dao
        val goals = dao.goals(task.orbitId).filter { it.state == "ACTIVE" }
        if (goals.isEmpty()) return ""
        val tasks = dao.tasks(task.orbitId)
        val notes = OrbitBoardRepository(context, task.orbitId, OrbitBoardRepository.Board.GOAL_NOTES).list()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return "today: ${day.format(Date())}\n\n" + goals.joinToString("\n\n") { goal ->
            val steps = tasks.filter { it.goalId == goal.id }
            val latest = notes.filter { it.extra == goal.id }.take(3)
            buildString {
                append("GOAL ${goal.id}: ${goal.title}\n")
                append("Last changed: ${day.format(Date(goal.updatedAt))}\n")
                if (goal.plan.isNotBlank()) append("Plan: ${goal.plan.take(1500)}\n")
                append("Steps: ${steps.count { it.state == "DONE" }}/${steps.size} done")
                steps.take(10).forEach { append("\n- [${it.state}] ${it.title}: ${it.lastObservation.take(200)}") }
                latest.forEach { append("\nEarlier ${it.state.lowercase()} (${day.format(Date(it.createdAt))}): ${it.title} ${it.body.take(300)}") }
            }
        }.take(20_000)
    }

    /**
     * What the alignment review reads: every chat of the Orbit since the last review (at most a
     * week), with the user turns counted by the app and message ids the review can cite. The newest
     * messages are kept when it is too long. Empty when the user said nothing, so the run is skipped.
     */
    private suspend fun alignmentWindow(context: Context, task: OrbitTaskEntity): String {
        val orbit = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == task.orbitId } ?: return ""
        val dao = OrbitTaskRepository(context).dao
        val now = System.currentTimeMillis()
        // A mark in the future (the clock was set back) is no mark: look back as on the first run.
        val since = (dao.lastCompletedRun(task.id)?.startedAt?.takeIf { it < now } ?: (now - FIRST_WINDOW_MS))
            .coerceAtLeast(now - MAX_WINDOW_MS)
        val chats = orbit.talkChatIds
        val userTurns = dao.userTurnsSince(chats, since)
        if (userTurns == 0) return ""
        val label = orbit.sideChats.associate { it.chatId to "side: ${it.title.take(40)}" } + (orbit.chatId to "main")
        val reactions = OrbitReactions.ofUser(context, orbit.id)
        val reacted = chats.associateWith { runCatching { reactions.observe(it).first() }
            .onFailure { e -> if (e is CancellationException) throw e }.getOrNull().orEmpty() }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val fetched = dao.windowSource(chats, since, WINDOW_FETCH_LIMIT)
        val lines = mutableListOf<String>()
        var used = 0
        var userShown = 0
        var userCut = false
        for (m in fetched) {
            val who = if (m.sender == "user") "user" else "orbit"
            val text = (if (m.sender == "ai") ChatUtils.removeThinkingContent(m.content) else m.content).trim()
            val emoji = reacted[m.chatId]?.get(m.timestamp)?.let { " (user reacted $it)" }.orEmpty()
            val line = "#${m.messageId} ${time.format(Date(m.timestamp))} [${label[m.chatId] ?: "main"}] $who: $text$emoji"
            if (used + line.length > WINDOW_TEXT_BUDGET) break
            lines += line
            used += line.length + 2
            if (m.sender == "user") {
                userShown++
                if (m.truncated) userCut = true
            }
        }
        // Nothing the user said fits: no rate can be counted, so this night is skipped.
        if (userShown == 0) return ""
        val partial = userShown < userTurns || userCut
        val latestDream = runCatching { OrbitFiles.of(context, orbit.id).latestDream() }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
        return buildString {
            append("ALIGNMENT REVIEW WINDOW\n")
            append("today: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))}\n")
            append("window: ${time.format(Date(since))} -> ${time.format(Date(now))} (local time)\n")
            append("user_turns (counted by the app): $userTurns\n")
            append("user_turns_shown: $userShown\n")
            append("window_complete: ${!partial}")
            if (partial) append(" (older messages or the end of long ones left out; judge only what is shown)")
            append("\nlatest_dream: ${latestDream ?: "none"}\n")
            append("Cite messages by their #id.\n\n")
            append(lines.asReversed().joinToString("\n\n"))
        }
    }

    private const val WINDOW_FETCH_LIMIT = 400

    /**
     * A chat keeps the system prompt it started with until it is summarized, so a new synthesis
     * would wait for that. After a night's review, like Muse's next day, every chat of the Orbit
     * builds its prompt again on its next reply: one fresh prompt a day. Skipped while Orbit is
     * replying, which would swap the prompt under that reply; the next summary picks it up then.
     */
    suspend fun afterAlignmentReview(context: Context, orbit: OrbitProfile) {
        try {
            if (OrbitPackages.replying(context, orbit).first()) return
            orbit.talkChatIds.forEach {
                com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository(context, it).delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OrbitRoutines", "Prompt not refreshed after the alignment review: ${e.message}")
        }
    }

    private val ALIGNMENT_ZH = """
        夜间对齐复盘（像 Muse 的 dream）。SOURCE 是上次复盘以来你和用户的全部聊天（主聊天和侧聊），开头有应用数好的 user_turns 和今天的日期。
        这次只管「你和这位用户怎么相处」，事实记忆不归这里管。全部在后台安静完成：
        1. 用 orbit_file 读 ALIGNMENT_SYNTHESIS.md、alignment/ALIGNMENT_STATE.yaml、alignment/PROGRESSION_HISTORY.yaml、alignment/REPAIR_THREADS.yaml，再 read SOURCE 里 latest_dream 那篇（是 none 就跳过）。
        2. 数摩擦：逐条看用户的话，出现摩擦或纠正的轮次算进 rupture_turns：说「不对」「不是这个意思」、要求重做同一件事、定下新规矩（「以后都……」）、表达不满、给你的消息点了 👎 之类。拿不准就先记上。correction_free_rate = 1 - rupture_turns / user_turns_shown，保留两位小数（只按你看到的轮次算）。
        3. 摩擦清单 alignment/REPAIR_THREADS.yaml：一个 YAML 列表，只存标签和证据，不写正文。每条 {id, trigger（简短英文标签，如 too_verbose）, opened（日期）, evidence（message id 列表）, clean_reviews}。新摩擦开一条；已有的摩擦这次又出现，把新的 message id 加进 evidence，clean_reviews 归零；这次有相关互动但没再出现，clean_reviews 加 1；到 3 才整条删掉，不要刚道歉就算学会了。window_complete 是 false 时，没看到的部分无法判断，这次不要给任何 clean_reviews 加 1。没有摩擦就写 []。
        4. 写 dreams/<today>.md（today 用 SOURCE 里的日期）。开头是 YAML frontmatter：
           ---
           alignment:
             schema: orbit_alignment_dream_v1
             generated_at: <现在>
             effective_date: <today>
             window_since: <窗口开始>
             window_until: <窗口结束>
             user_turns: <N>
             user_turns_shown: <SOURCE 里的数>
             rupture_turns: <数出来的>
             correction_free_rate: <算出来的>
             open_repair_threads: <摩擦清单条数>
           ---
           然后是「# Dream: <today>」和「## Dream」，用几段话写今天相处得怎么样、学到了什么、对齐总结哪一节改了、为什么。每个判断尽量带上 message id。
        5. 重写 ALIGNMENT_SYNTHESIS.md。第一行「# 对齐总结」，下面固定六节，标题照抄：
           「## 他是谁、该怎么帮他」：用户画像和配合方式的整体叙述（身份、说话风格、做决定的习惯、最近在忙什么），每晚重写。
           「## 他从你这里得到什么」：一句话，慢慢变。
           「## 红线」：硬规矩清单，只写用户拍过板的。
           「## 当前的摩擦」：你哪里容易出错、他纠正过什么、哪些事还悬着等他拍板，和摩擦清单对应。
           「## 怎样让关系更好」：一两条能做到的方向。
           「## 细节去哪找」：指向 dreams/、LESSONS.md、memory.md 等。
           除红线外用叙述体，不要堆条目。每个判断都要有聊天里的依据。它会全文放进每次聊天，整篇控制在 1500 字以内。没有新证据的节保持原样。
        6. 更新 alignment/ALIGNMENT_STATE.yaml（schema_version: 1；relationship_contract 是你对用户的承诺，user_facets、working_alliance、observed_fit、proactivity_policies 何时主动何时安静，repair_threads 只放摩擦 id）。在 alignment/PROGRESSION_HISTORY.yaml 的 observations 末尾追加一条 {observed_at, correction_free_rate, eligible_evidence（= user_turns_shown）, open_repair_threads, classification}：user_turns_shown 少于 10 时 classification 写 insufficient，也不要据此下「关系变好/变差」的结论；够了就和前几条比，写 improving、steady 或 declining，但 window_complete 是 false 时不要写 improving。只保留最近 90 条。
        7. 用户纠正过的具体做法，顺手 append 到 LESSONS.md。
        不要给用户发消息：userMessage 留空，decision 写一句这次复盘的结论。
    """.trimIndent()

    private val ALIGNMENT_EN = """
        Nightly alignment review (like Muse's dream). SOURCE holds every chat since the last review (main and side
        chats), headed by today's date and the user turns the app counted. This review is only about how to be with
        this user, not factual memory. Do it all quietly in the background:
        1. With orbit_file, read ALIGNMENT_SYNTHESIS.md and alignment/ALIGNMENT_STATE.yaml, PROGRESSION_HISTORY.yaml
           and REPAIR_THREADS.yaml, then the review named by latest_dream (skip it when none).
        2. Count as rupture_turns the user turns with friction or a correction: "that's wrong", "not what I meant",
           redoing the same thing, a new rule ("from now on..."), displeasure, a 👎 on your message. When unsure,
           count it. correction_free_rate = 1 - rupture_turns / user_turns_shown, two decimals.
        3. alignment/REPAIR_THREADS.yaml is a YAML list of labels and evidence, no prose: {id, trigger (short label
           such as too_verbose), opened (date), evidence (message ids), clean_reviews}. Open one for a new friction.
           If an open one recurs, add its message ids and reset clean_reviews to 0; if related talk happened without
           it, add 1. Remove it only at 3: an apology is not proof it was learned. When window_complete is false, add
           to no clean_reviews. With no friction, write [].
        4. Write dreams/<today>.md starting with YAML frontmatter:
           ---
           alignment:
             schema: orbit_alignment_dream_v1
             generated_at: <now>
             effective_date: <today>
             window_since: <start>
             window_until: <end>
             user_turns: <N>
             user_turns_shown: <from SOURCE>
             rupture_turns: <counted>
             correction_free_rate: <computed>
             open_repair_threads: <entries in the repair list>
           ---
           then "# Dream: <today>" and "## Dream": a few paragraphs on how it went, what you learned, which section of
           the synthesis changed and why, citing message ids.
        5. Rewrite ALIGNMENT_SYNTHESIS.md: "# Alignment synthesis", then six sections with these exact headings:
           "## Who this user is and how to act for them" (who they are, how they talk and decide, what they are busy
           with; rewritten nightly), "## User value" (one sentence; changes slowly), "## Boundaries" (hard rules the
           user settled), "## Current frictions" (where you slip, what they corrected, what waits on them; matches the
           repair list), "## How you can strengthen the relationship" (one or two actionable things), "## Where to look
           for detail" (dreams/, LESSONS.md, memory.md...). Prose except Boundaries, every judgment grounded in the
           chats, under 900 words: every chat carries it in full. Leave a section with no new evidence as it was.
        6. Update alignment/ALIGNMENT_STATE.yaml (schema_version: 1, relationship_contract, user_facets,
           working_alliance, observed_fit, proactivity_policies, repair_threads as repair ids only). Append to the
           observations in alignment/PROGRESSION_HISTORY.yaml {observed_at, correction_free_rate, eligible_evidence
           (= user_turns_shown), open_repair_threads, classification}: under 10 shown user turns write insufficient
           and draw no conclusion; otherwise compare with the last few: improving, steady or declining (never
           improving when window_complete is false). Keep the latest 90.
        7. Append specific ways of working the user corrected to LESSONS.md.
        Do not message the user: leave userMessage empty; give the review's conclusion in decision in one sentence.
    """.trimIndent()

    private val SELF_REVIEW_ZH = """
        夜间自我整理（只在有新对话时运行）。SOURCE 里是最近的对话。用它做下面几件事，都在后台安静完成：
        1. 记忆：把值得长期保留的新事实、偏好、决定和承诺整理进 memory.md 和 user.md（memory_review 工具包），新的说法替换旧的，不留互相矛盾的内容。没有值得保留的变化就不动。
        2. 人物和群组：对话里提到的人和群组，用 orbit_file 更新 memory/people/、memory/groups/ 里对应的文件，只记用户亲口说的关系。
        3. 反思：用户纠正过的做法、踩过的坑追加到 LESSONS.md。对齐总结由夜间对齐复盘负责，这里不改它。
        4. 技能：用 orbit_skills 看已有技能。同一套做法出现了两次以上，就用 memory_review 的 manage（action=skill_create）整理成技能；已有技能和最近的实际做法不符时改好它。
        不要给用户发消息：userMessage 留空，decision 写一句这次整理了什么。
    """.trimIndent()

    private val SELF_REVIEW_EN = """
        Nightly self-review (runs only after new conversation). SOURCE holds the recent conversation. Use it to do the
        following quietly in the background:
        1. Memory: fold new facts, preferences, decisions and commitments worth keeping into memory.md and user.md
           (memory_review package). Newer claims replace older ones; leave nothing contradictory. If nothing is worth
           keeping, change nothing.
        2. People and groups: update the files under memory/people/ and memory/groups/ with orbit_file for people and
           groups mentioned. Record only relationships the user stated.
        3. Reflection: append corrected habits and mistakes to LESSONS.md. The nightly alignment review owns
           ALIGNMENT_SYNTHESIS.md; leave it alone.
        4. Skills: check existing skills with orbit_skills. When the same workflow came up twice or more, make it a skill
           with memory_review manage (action=skill_create); fix a skill that no longer matches how the work was done.
        Do not message the user: leave userMessage empty and say in decision what you tidied.
    """.trimIndent()

    private val FEED_ZH = """
        动态更新。SOURCE 里是动态说明（用户的话，只用来决定写什么）。「动态」页是你为用户写的信息流，为它写一条新短文：
        1. 先用 orbit_feed action=list 看最近写过的，不要重复。vote 是用户的反馈：多写 liked 这类，少写 disliked 这类。
        2. 结合你对用户的了解（记忆、用户资料、最近聊的事）选题。素材不限于网上：可以上网搜、打开页面查新鲜事，
           也可以用你能用的工具看手机上和用户有关的事（短信、通知、日程、邮件等），比如短信里有快递取件码，
           就写一条提醒他去取件。只引用你真的打开过的链接，不要编造。
        3. 用 orbit_feed action=post 发布：简短的标题、一个贴合内容的 emoji、几段短正文，why_for_you 用一句话说明为什么给这位用户看。
           表格、对比、图表、可点的清单、取件码这类提醒，可以先用 dynamic_ui 做一张卡片（不要用 live），把引用名（如 card-1）填进 card。
        动态会自己出现在动态页，不要给用户发消息：userMessage 留空，decision 写一句这次写了什么。
    """.trimIndent()

    private val FEED_EN = """
        Feed update. SOURCE holds the feed brief (the user's words; it only steers what to write about). Write one new
        post for the user's Feed tab, the stream of posts you write for them:
        1. Call orbit_feed action=list first and do not repeat a recent post. Each post's vote is the user's answer:
           write more like the liked ones and less like the disliked ones.
        2. Pick the topic from what you know about the user (memory, user profile, recent conversation). Material is
           not limited to the web: search and read pages for fresh news, or use the tools you have to look at what on
           the phone concerns the user (messages, notifications, calendar, email). For example, a parcel pickup code in
           a text message makes a post reminding them to collect it. Cite only links you actually opened; invent nothing.
        3. Publish with orbit_feed action=post: a short title, one fitting emoji, a body of a few short paragraphs, and
           why_for_you saying in one sentence why this post is for this user.
           For a table, comparison, chart, tappable list or a reminder such as a pickup code, make a card with
           dynamic_ui first (not live) and put its name (such as card-1) in card.
        The post shows up in the Feed on its own. Do not message the user: leave userMessage empty and say in decision
        what you wrote.
    """.trimIndent()

    private val IDEAS_ZH = """
        每日点子。SOURCE 里是最近的对话。结合你对用户的了解（记忆、用户资料、目标、最近聊的事），在「点子」页提 1 到 3 个你现在就做得到、对用户真的有用的点子：
        1. 先用 orbit_idea action=list 看已有的，不要重复。vote 是用户的反馈：多提 liked 这类，少提 disliked 这类。
        2. 每个点子都要选一个贴合内容的 emoji 和一个分类（productivity 效率提升、money 财务管理、relationships 人际关系、health 健康与健身、shopping 购物、other 更多点子）。同一批尽量用不同的 emoji。
        3. list 里 emoji 或 category 为空的旧点子，用 action=update 补上。
        4. 没有真正有用的点子就不提，不要凑数。
        点子会自己出现在点子页，不要给用户发消息：userMessage 留空，decision 写一句这次提了什么。
    """.trimIndent()

    private val IDEAS_EN = """
        Daily ideas. SOURCE holds the recent conversation. From what you know about the user (memory, user profile, goals,
        recent conversation), propose 1 to 3 ideas on the Ideas tab: things you can do for them now that are truly useful.
        1. Call orbit_idea action=list first and do not repeat one. Each idea's vote is the user's answer: propose more
           like the liked ones and fewer like the disliked ones.
        2. Give every idea one fitting emoji and one category (productivity, money, relationships, health, shopping,
           other). Use different emoji within one batch where you can.
        3. Fill in the emoji or category of older ideas in the list that have none, with action=update.
        4. Propose nothing rather than something weak.
        Ideas show up on the Ideas tab on their own. Do not message the user: leave userMessage empty and say in
        decision what you proposed.
    """.trimIndent()

    private val GOAL_BRIEFING_ZH = """
        目标简报。SOURCE 里是用户进行中的目标、步骤进度和以前的简报。为每个目标写一封简短的简报：
        - 需要新信息时先查（搜网页、读页面），只引用你真的打开过的链接。
        - 写清楚最近的进展、接下来最值得做的一步，以及你能帮上的地方；和上一封比没有新东西的目标就跳过。
        - 用 orbit_goal_note 的 brief 保存（goal_id 填 SOURCE 里的编号，title 是一句话的附言，text 是简报正文）。
        简报会安静地出现在目标页，不要给用户发消息：userMessage 留空，decision 写一句写了哪些简报。
    """.trimIndent()

    private val GOAL_BRIEFING_EN = """
        Goal briefing. SOURCE holds the user's active goals, their step progress and earlier briefings. Write a short
        briefing for each goal:
        - Research first when you need fresh facts (search the web, read pages); cite only links you actually opened.
        - Say what moved recently, the most useful next step, and where you can help. Skip a goal with nothing new since
          its last briefing.
        - Save each with orbit_goal_note action=brief (goal_id from SOURCE, title a one-line note, text the briefing).
        Briefings appear quietly on the Goals tab. Do not message the user: leave userMessage empty and say in decision
        which briefings you wrote.
    """.trimIndent()

    private val GOAL_ALIGN_ZH = """
        目标对齐（后台）。SOURCE 里是今天的日期、用户进行中的目标、计划、步骤（任务）的状态和最近结果，以及以前的简报。在后台安静地对一遍：
        1. 对账：逐个目标看步骤的真实状态，找出计划和实际对不上的地方：做完了但计划里还挂着、步骤失败或卡住、在等用户、很久没动。拿不准用户最近有没有改主意，用 search_chat_history 搜他说过的话，或用 orbit_file 读 memory.md，不要猜。
        2. 需要新信息时先查（搜网页、读页面），只引用你真的打开过的链接。
        3. 简报：和上一封比有新东西的目标，用 orbit_goal_note action=brief 写一封（goal_id 填 SOURCE 里的编号，title 一句话，text 写进展、卡在哪、下一步最值得做什么、你能帮上什么）。没变化的目标跳过。
        4. 只有真需要用户本人的事（快到期的、等他拍板的、卡住只有他能解决的）才备一条主动消息：userMessage 用你平时和他说话的语气写一两句，opportunityKey 用 goal:<目标编号>:<事由>。发不发由主聊天再判断。其他情况 userMessage 留空。
        decision 写一句这次对齐的结论。
    """.trimIndent()

    private val GOAL_ALIGN_EN = """
        Goal alignment (background). SOURCE holds today's date, the user's active goals, their plans, the state and
        latest results of their steps (tasks), and earlier briefings. Check them quietly in the background:
        1. Reconcile: for each goal, compare the plan with what really happened. Point out steps done but still open in
           the plan, steps that failed or are stuck, steps waiting on the user, and goals that have not moved for a long
           time. When unsure whether the user changed their mind, search what they said with search_chat_history or
           read memory.md with orbit_file instead of guessing.
        2. Research first when you need fresh facts (search the web, read pages); cite only links you actually opened.
        3. Briefing: for a goal with something new since its last briefing, save one with orbit_goal_note action=brief
           (goal_id from SOURCE, title one line, text: what moved, where it is stuck, the most useful next step, where
           you can help). Skip goals with nothing new.
        4. Draft a proactive item only for what truly needs the user (a deadline coming up, a decision waiting on them,
           a block only they can clear): a sentence or two in your usual voice as userMessage, with opportunityKey
           goal:<goal id>:<matter>. The chat decides whether it is sent. Otherwise leave userMessage empty.
        Say the conclusion of this alignment in decision in one sentence.
    """.trimIndent()
}
