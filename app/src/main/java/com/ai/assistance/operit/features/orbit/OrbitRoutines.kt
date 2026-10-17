package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Orbit's own background routines, like Muse's self-improvement runs: a nightly self-review that
 * tidies memory, notes and skills after new conversation, and a morning briefing on the user's
 * goals. Both are ordinary Orbit tasks, so the user sees, pauses or cancels them in the schedule
 * list like any other. Each is made once per Orbit; a cancelled routine is not brought back.
 */
internal object OrbitRoutines {
    /** The recent Orbit conversation; the run is skipped when it has not changed since the last one. */
    const val CHAT_SOURCE = "orbit:chat"
    /** The user's active goals; the run is skipped when there are none. */
    const val GOALS_SOURCE = "orbit:goals"
    private const val SELF_REVIEW = "routine-self-review"
    private const val GOAL_BRIEFING = "routine-goal-briefing"
    private const val SELF_REVIEW_MINUTE = 3 * 60 + 30
    private const val GOAL_BRIEFING_MINUTE = 7 * 60
    /** Stays inside the normal background lane, so no foreground notification at night. */
    private const val TIMEOUT_MINUTES = 8

    fun isRoutineSource(source: String) = source == CHAT_SOURCE || source == GOALS_SOURCE

    /** The same id OrbitTaskRepository.create gives a request, so making a routine again finds the old one. */
    fun taskId(orbitId: String, request: String): String =
        UUID.nameUUIDFromBytes("$orbitId:$request".toByteArray()).toString()

    fun selfReviewId(orbitId: String) = taskId(orbitId, SELF_REVIEW)
    fun goalBriefingId(orbitId: String) = taskId(orbitId, GOAL_BRIEFING)

    suspend fun ensureSelfReview(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, SELF_REVIEW, R.string.orbit_routine_self_review, CHAT_SOURCE, SELF_REVIEW_MINUTE) {
            if (it) SELF_REVIEW_EN else SELF_REVIEW_ZH
        }
    }

    suspend fun ensureGoalBriefing(context: Context, orbit: OrbitProfile) {
        ensure(context, orbit, GOAL_BRIEFING, R.string.orbit_routine_goal_briefing, GOALS_SOURCE, GOAL_BRIEFING_MINUTE) {
            if (it) GOAL_BRIEFING_EN else GOAL_BRIEFING_ZH
        }
    }

    /** Makes the briefing for an Orbit that already has goals, e.g. ones made before the briefing existed. */
    suspend fun ensureGoalBriefingIfGoals(context: Context, orbit: OrbitProfile) {
        val dao = OrbitTaskRepository(context).dao
        if (dao.task(goalBriefingId(orbit.id)) != null) return
        if (dao.goals(orbit.id).any { it.state != "CANCELLED" }) ensureGoalBriefing(context, orbit)
    }

    /** True for the two tasks Orbit makes by itself; their instructions are written for the model. */
    fun isRoutine(task: OrbitTaskEntity) =
        task.id == selfReviewId(task.orbitId) || task.id == goalBriefingId(task.orbitId)

    /** Why a skipped run was skipped: only the goal briefing skips for its own reason. */
    fun skippedReason(context: Context, task: OrbitTaskEntity): String = context.getString(
        if (task.source == GOALS_SOURCE) R.string.orbit_decision_no_active_goals
        else R.string.orbit_watch_notifications_starting
    )

    private suspend fun ensure(
        context: Context, orbit: OrbitProfile, request: String, title: Int, source: String, minute: Int,
        instruction: (english: Boolean) -> String,
    ) {
        val repository = OrbitTaskRepository(context)
        if (repository.dao.task(taskId(orbit.id, request)) != null) return
        try {
            val english = LocaleUtils.usesEnglishPrompt(context)
            repository.create(orbit, context.getString(title), instruction(english), source, 1440, "CHANGES", request,
                kind = "ASSIST", scheduleMode = "DAILY", dailyMinute = minute, timeoutMinutes = TIMEOUT_MINUTES)
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
        5. 点子：如果对话里有你现在就能为用户做的、真的有用的事，用 orbit_idea 提一个（先 list，不要重复）。没有就不提。
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
        5. Ideas: if the conversation shows something genuinely useful you can do for the user now, propose one with
           orbit_idea (list first, no duplicates). Otherwise propose none.
        Do not message the user: leave userMessage empty and say in decision what you tidied.
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
