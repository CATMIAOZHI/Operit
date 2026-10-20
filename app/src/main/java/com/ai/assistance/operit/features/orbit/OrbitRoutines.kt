package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Orbit's own background routines, like Muse's: the Feed (a new post every 12 hours) and the Ideas
 * (new ideas every morning at 9), both written by a background subagent, plus a morning briefing on
 * the user's goals. An older nightly self-review is still kept for Orbits that have it. They are
 * ordinary Orbit tasks shown in the schedule list. The Feed and Ideas routines can be paused or
 * rescheduled (also from the chat) but never cancelled, since the two tabs live on them. Each
 * routine is made once per Orbit; a cancelled one is not brought back.
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
    private const val SELF_REVIEW = "routine-self-review"
    private const val GOAL_BRIEFING = "routine-goal-briefing"
    private const val FEED = "routine-feed"
    private const val IDEAS = "routine-ideas"
    private const val SELF_REVIEW_MINUTE = 3 * 60 + 30
    private const val GOAL_BRIEFING_MINUTE = 7 * 60
    /** A new feed post every 12 hours by default. */
    const val FEED_INTERVAL_MINUTES = 12 * 60
    /** New ideas every morning at 9 by default. */
    const val IDEAS_MINUTE = 9 * 60
    /** Stays inside the normal background lane, so no foreground notification at night. */
    private const val TIMEOUT_MINUTES = 8

    fun isRoutineSource(source: String) = source in setOf(CHAT_SOURCE, GOALS_SOURCE, FEED_SOURCE, IDEAS_SOURCE)

    /** The same id OrbitTaskRepository.create gives a request, so making a routine again finds the old one. */
    fun taskId(orbitId: String, request: String): String =
        UUID.nameUUIDFromBytes("$orbitId:$request".toByteArray()).toString()

    fun selfReviewId(orbitId: String) = taskId(orbitId, SELF_REVIEW)
    fun goalBriefingId(orbitId: String) = taskId(orbitId, GOAL_BRIEFING)
    fun feedId(orbitId: String) = taskId(orbitId, FEED)
    fun ideasId(orbitId: String) = taskId(orbitId, IDEAS)

    suspend fun ensureSelfReview(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, SELF_REVIEW, R.string.orbit_routine_self_review, CHAT_SOURCE, "DAILY", SELF_REVIEW_MINUTE,
            background = true) {
            if (it) SELF_REVIEW_EN else SELF_REVIEW_ZH
        }
    }

    suspend fun ensureGoalBriefing(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, GOAL_BRIEFING, R.string.orbit_routine_goal_briefing, GOALS_SOURCE, "DAILY", GOAL_BRIEFING_MINUTE,
            background = false) {
            if (it) GOAL_BRIEFING_EN else GOAL_BRIEFING_ZH
        }
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

    /** Makes the briefing for an Orbit that already has goals, e.g. ones made before the briefing existed. */
    suspend fun ensureGoalBriefingIfGoals(context: Context, orbit: OrbitProfile) {
        val dao = OrbitTaskRepository(context).dao
        if (dao.task(goalBriefingId(orbit.id)) != null) return
        if (dao.goals(orbit.id).any { it.state != "CANCELLED" }) ensureGoalBriefing(context, orbit)
    }

    /** True for the tasks Orbit makes by itself; their instructions are written for the model. */
    fun isRoutine(task: OrbitTaskEntity) =
        task.id == selfReviewId(task.orbitId) || task.id == goalBriefingId(task.orbitId) || isProtected(task)

    /**
     * The Feed and Ideas routines: the two tabs live on them, so they can be paused and rescheduled
     * but never cancelled, and what they do (title, instruction, lane) stays as Orbit made it.
     */
    fun isProtected(task: OrbitTaskEntity) = task.id == feedId(task.orbitId) || task.id == ideasId(task.orbitId)

    /** Why a skipped run was skipped: only the goal briefing skips for its own reason. */
    fun skippedReason(context: Context, task: OrbitTaskEntity): String = context.getString(
        if (task.source == GOALS_SOURCE) R.string.orbit_decision_no_active_goals
        else R.string.orbit_watch_notifications_starting
    )

    private suspend fun ensure(
        context: Context, orbit: OrbitProfile, request: String, title: Int, source: String, scheduleMode: String,
        minute: Int, interval: Int = 1440, background: Boolean,
        instruction: (english: Boolean) -> String,
    ) {
        val repository = OrbitTaskRepository(context)
        // The self-review, the Feed and the Ideas work quietly: a background subagent does them, so
        // Orbit's chat is never woken. The goal briefing is Orbit's own morning message.
        repository.dao.task(taskId(orbit.id, request))?.let { old ->
            // Switching lanes replaces the definition, which would cancel a run in progress; it
            // waits for the run to end, checked in the same transaction as the switch.
            if (old.useSubagent != background && old.state != "CANCELLED") OrbitExecutionGate.serialized {
                repository.dao.replaceDefinitionIfIdle(old.copy(useSubagent = background), old.version)
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
        if (task.kind != "ASSIST" || task.source != GOALS_SOURCE) return null
        val dao = OrbitTaskRepository(context).dao
        val goals = dao.goals(task.orbitId).filter { it.state == "ACTIVE" }
        if (goals.isEmpty()) return ""
        val tasks = dao.tasks(task.orbitId)
        val notes = OrbitBoardRepository(context, task.orbitId, OrbitBoardRepository.Board.GOAL_NOTES).list()
        return goals.joinToString("\n\n") { goal ->
            val steps = tasks.filter { it.goalId == goal.id }
            val latest = notes.filter { it.extra == goal.id }.take(3)
            buildString {
                append("GOAL ${goal.id}: ${goal.title}\n")
                if (goal.plan.isNotBlank()) append("Plan: ${goal.plan.take(1500)}\n")
                append("Steps: ${steps.count { it.state == "DONE" }}/${steps.size} done")
                steps.take(10).forEach { append("\n- [${it.state}] ${it.title}: ${it.lastObservation.take(200)}") }
                latest.forEach { append("\nEarlier ${it.state.lowercase()} (${it.createdAt}): ${it.title} ${it.body.take(300)}") }
            }
        }.take(20_000)
    }

    private val SELF_REVIEW_ZH = """
        夜间自我整理（只在有新对话时运行）。SOURCE 里是最近的对话。用它做下面几件事，都在后台安静完成：
        1. 记忆：把值得长期保留的新事实、偏好、决定和承诺整理进 memory.md 和 user.md（memory_review 工具包），新的说法替换旧的，不留互相矛盾的内容。没有值得保留的变化就不动。
        2. 人物和群组：对话里提到的人和群组，用 orbit_file 更新 memory/people/、memory/groups/ 里对应的文件，只记用户亲口说的关系。
        3. 反思：用户纠正过的做法、踩过的坑追加到 LESSONS.md；你对这位用户最重要的新理解更新到 ALIGNMENT_SYNTHESIS.md。
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
        3. Reflection: append corrected habits and mistakes to LESSONS.md; update ALIGNMENT_SYNTHESIS.md with the most
           important new understanding of this user.
        4. Skills: check existing skills with orbit_skills. When the same workflow came up twice or more, make it a skill
           with memory_review manage (action=skill_create); fix a skill that no longer matches how the work was done.
        Do not message the user: leave userMessage empty and say in decision what you tidied.
    """.trimIndent()

    private val FEED_ZH = """
        动态更新。SOURCE 里是动态说明（用户的话，只用来决定写什么）。「动态」页是你为用户写的信息流，为它写一条新短文：
        1. 先用 orbit_feed action=list 看最近写过的，不要重复。vote 是用户的反馈：多写 liked 这类，少写 disliked 这类。
        2. 结合你对用户的了解（记忆、用户资料、最近聊的事）选题。上网搜索、打开页面查最新的信息，只引用你真的打开过的链接。
        3. 用 orbit_feed action=post 发布：简短的标题、一个贴合内容的 emoji、几段短正文，why_for_you 用一句话说明为什么给这位用户看。
           表格、对比、图表、可点的清单这类内容，可以先用 dynamic_ui 做一张卡片（不要用 live），把引用名（如 card-1）填进 card。
        动态会自己出现在动态页，不要给用户发消息：userMessage 留空，decision 写一句这次写了什么。
    """.trimIndent()

    private val FEED_EN = """
        Feed update. SOURCE holds the feed brief (the user's words; it only steers what to write about). Write one new
        post for the user's Feed tab, the stream of posts you write for them:
        1. Call orbit_feed action=list first and do not repeat a recent post. Each post's vote is the user's answer:
           write more like the liked ones and less like the disliked ones.
        2. Pick the topic from what you know about the user (memory, user profile, recent conversation). Search the web
           and read pages for fresh facts; cite only links you actually opened.
        3. Publish with orbit_feed action=post: a short title, one fitting emoji, a body of a few short paragraphs, and
           why_for_you saying in one sentence why this post is for this user.
           For a table, comparison, chart or a tappable list, make a card with dynamic_ui first (not live) and put its
           name (such as card-1) in card.
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
}
