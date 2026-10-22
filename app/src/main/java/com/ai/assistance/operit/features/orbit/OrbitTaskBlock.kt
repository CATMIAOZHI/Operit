package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitEventEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Background tasks Android would not let Operit start. The refusal is only written down where it
 * happens (Orbit cannot be woken there either); once the user opens Operit the late tasks run, and
 * each Orbit with a late task gets a hidden note in its main chat, the way a tapback does, so it can
 * tell the user in its own words and offer the keep-alive card. Whether to bring it up at all is
 * Orbit's call from PROACTIVE_PREFERENCES.md; the app only keeps one cause from being raised twice
 * in a few days.
 */
internal object OrbitTaskBlock {
    const val MARKER = "[orbit-blocked]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")

    const val KEEP_ALIVE_OFF = "keep_alive_off"
    const val BATTERY_RESTRICTED = "battery_restricted"
    const val SYSTEM = "system"

    /** A delay this short is not worth a word: the user opened Operit right after it. */
    private const val MIN_DELAY_MS = 10 * 60_000L
    /** After an Orbit was told about one cause, the same cause is not raised again for this long. */
    private const val QUIET_AFTER_TOLD_MS = 3L * 24 * 60 * 60_000
    private const val IDLE_WAIT_MS = 10 * 60_000L
    private const val MAX_LISTED = 8

    private const val PREFS = "orbit_task_host"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_SINCE = "blocked_since"
    private const val KEY_REASON = "blocked_reason"
    private const val KEY_DETAIL = "blocked_detail"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun toldKey(orbitId: String, reason: String) = "told:$orbitId:$reason"

    data class Status(val keepAlive: Boolean, val battery: Boolean) {
        val allOn get() = keepAlive && battery
    }

    data class Episode(val since: Long, val reason: String, val detail: String)

    /** One Orbit's note, built before the late tasks run. */
    class Pending(val orbit: OrbitProfile, val notice: String, val reason: String)

    /** The title the work list shows for the turn a note started. */
    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Background keep-alive in Operit's settings, and the system's battery-optimization exemption. */
    suspend fun status(context: Context): Status {
        val app = context.applicationContext
        val keepAlive = try {
            DisplayPreferencesManager.getInstance(app).enableBackgroundKeepAlive.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        val battery = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
        return Status(keepAlive, battery)
    }

    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, false)

    /** A start was refused. Only the first refusal sets the time; the retries after it add nothing. */
    @Synchronized
    fun record(context: Context, refusal: String?) {
        val p = prefs(context)
        val edit = p.edit().putBoolean(KEY_BLOCKED, true).putString(KEY_DETAIL, refusal.orEmpty().take(200))
        if (!p.getBoolean(KEY_BLOCKED, false) || p.getLong(KEY_SINCE, 0L) == 0L) {
            edit.putLong(KEY_SINCE, System.currentTimeMillis())
        }
        edit.apply()
    }

    /** Works out why, from the settings as they are now; kept only while the refusal still stands. */
    suspend fun classify(context: Context) {
        val s = status(context)
        val reason = when {
            !s.keepAlive -> KEEP_ALIVE_OFF
            !s.battery -> BATTERY_RESTRICTED
            else -> SYSTEM
        }
        synchronized(this) {
            if (isBlocked(context)) prefs(context).edit().putString(KEY_REASON, reason).apply()
        }
    }

    /** A start worked again in the background: the delay was passing, nothing is told. */
    @Synchronized
    fun clear(context: Context) {
        if (!isBlocked(context)) return
        prefs(context).edit().remove(KEY_BLOCKED).remove(KEY_SINCE).remove(KEY_REASON).remove(KEY_DETAIL).apply()
    }

    /** Reads and clears the refusal; null when there is none. */
    @Synchronized
    fun take(context: Context): Episode? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_BLOCKED, false)) return null
        val episode = Episode(
            since = p.getLong(KEY_SINCE, 0L).takeIf { it > 0 } ?: System.currentTimeMillis(),
            reason = p.getString(KEY_REASON, null) ?: SYSTEM,
            detail = p.getString(KEY_DETAIL, null).orEmpty(),
        )
        p.edit().remove(KEY_BLOCKED).remove(KEY_SINCE).remove(KEY_REASON).remove(KEY_DETAIL).apply()
        return episode
    }

    /**
     * The note for [episode], for the running Orbit when it has a task still waiting to run. Call it
     * before the tasks run: afterwards nothing shows which ones were late. Only the active Orbit's
     * tasks run, so only it is told.
     */
    suspend fun prepare(context: Context, episode: Episode): List<Pending> {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - episode.since < MIN_DELAY_MS) return emptyList()
        val english = LocaleUtils.usesEnglishPrompt(app)
        val dao = AppDatabase.getDatabase(app).orbitTaskDao()
        val p = prefs(app)
        return listOfNotNull(OrbitPreferences.getInstance(app).current().active)
            .filter { !it.deleting && it.chatId.isNotBlank() }
            .mapNotNull { orbit ->
                val lastTold = p.getLong(toldKey(orbit.id, episode.reason), 0L)
                if (lastTold > 0 && now - lastTold in 0 until QUIET_AFTER_TOLD_MS) return@mapNotNull null
                // The same test the runner uses: a waiting event that is due (a quiet time or a busy
                // chat moves it later), or a timed task whose next run has come; and a task that wakes
                // Orbit in the chat also waits for the notification settings (quiet hours, digest time).
                val late = dao.tasks(orbit.id).filter { it.state == "ACTIVE" }.mapNotNull { task ->
                    val event = dao.pendingEvent(task.id, task.version) ?: when {
                        task.scheduleMode == "EVENT" || task.kind == "GEOFENCE" -> null
                        // What the runner would queue (see OrbitTaskDao.enqueueDue), not saved here.
                        task.nextRunAt in 1..now ->
                            OrbitEventEntity("${task.id}:${task.version}:${task.nextRunAt}", task.id, task.version, task.nextRunAt, now)
                        else -> null
                    } ?: return@mapNotNull null
                    if (event.dueAt > now) return@mapNotNull null
                    if (!task.useSubagent && dao.eventRun(event.id) == null &&
                        OrbitDelivery.wakeAllowedAt(app, task, event, now) > now) return@mapNotNull null
                    task to event.dueAt
                }.sortedBy { it.second }
                if (late.isEmpty()) return@mapNotNull null
                val preferences = try {
                    OrbitFiles.readHead(OrbitFiles.of(app, orbit.id).fileFor(OrbitFiles.PROACTIVE), 3000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                Pending(orbit, notice(english, episode, late, preferences, lastTold.takeIf { it > 0 }), episode.reason)
            }
    }

    /** Hands each note to its Orbit once the main chat is free; only the chat sees it, never a notification. */
    suspend fun deliver(context: Context, pending: List<Pending>) {
        val app = context.applicationContext
        for (item in pending) {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, item.orbit, item.orbit.chatId, item.notice, left, left,
                        onSent = {
                            prefs(app).edit().putLong(toldKey(item.orbit.id, item.reason), System.currentTimeMillis()).apply()
                        },
                        announce = false)
                    if (told != OrbitWake.Told.BUSY) break
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitTaskBlock", "Could not tell Orbit about late tasks: ${e.message}")
            }
        }
    }

    private fun line(text: String, max: Int) = text.replace(Regex("\\s+"), " ").trim().take(max)

    /** [late] pairs each task with when it was due. */
    fun notice(english: Boolean, episode: Episode, late: List<Pair<OrbitTaskEntity, Long>>, preferences: String, lastTold: Long?): String {
        val day = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val listed = late.take(MAX_LISTED).joinToString("\n") { (task, due) ->
            if (english) "- 「${line(task.title, 80)}」 (due ${day.format(Date(due))})"
            else "- 「${line(task.title, 80)}」（原定 ${day.format(Date(due))}）"
        } + if (late.size > MAX_LISTED) {
            if (english) "\n- and ${late.size - MAX_LISTED} more" else "\n- 还有 ${late.size - MAX_LISTED} 个"
        } else ""
        val detail = line(episode.detail, 200)
        val prefs = OrbitTaskRunner.fenced(preferences.trim())
        val card = com.ai.assistance.operit.core.tools.OrbitPermissionTools.NAME
        val nothing = OrbitReactionNotice.NOTHING_TO_DO
        return if (english) buildString {
            append("$MARKER Background tasks ran late\n")
            append("Some background tasks you set up could not start on time: from ${day.format(Date(episode.since))}, ")
            append("Android did not let Operit start them in the background. They are running now that the user opened Operit. ")
            append("This is not a message from the user; they typed nothing and cannot see this note.\n")
            append("Late tasks:\n$listed\n")
            append("Why: ")
            append(when (episode.reason) {
                KEEP_ALIVE_OFF -> "\"Background keep-alive\" is off in Operit's settings, so Android stops Operit from starting work in the background."
                BATTERY_RESTRICTED -> "Background keep-alive is on, but battery optimization still restricts Operit (it is not exempt), so the background start was refused."
                else -> "Background keep-alive and the battery exemption are both on, yet the system still refused, most likely the phone's own manager (auto-start or background limits)." +
                    if (detail.isNotEmpty()) " The system said: $detail" else ""
            })
            append("\n")
            if (lastTold != null) append("You already brought up the same cause on ${day.format(Date(lastTold))}.\n")
            if (prefs.isNotEmpty()) append("The user's proactive preferences (only for deciding whether to bother them):\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("What to do:\n")
            append("1. Check the preferences and this chat first. If the user said not to bring this up (tasks held back, keep-alive, battery), call $nothing and stay quiet.\n")
            append("2. Otherwise tell them briefly, in your own words, which things ran late and why; do not alarm them. ")
            append("When the cause is keep-alive or battery optimization, call $card with permission=keep_alive so they can turn it on with one tap. ")
            append("When it is the system, say they may need to allow Operit to auto-start and run in the background in the phone's manager app.\n")
            append("3. If they say not this time, leave it. If they say never mention it, write that into the \"Never tell me about\" section of PROACTIVE_PREFERENCES.md. Do not chase it or repeat it.\n")
            append("Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 后台任务被延后\n")
            append("你安排的一些后台任务没能按时开始：从 ${day.format(Date(episode.since))} 起，系统不让 Operit 在后台启动它们，")
            append("刚才用户打开 Operit 后才开始补跑。这不是用户发的消息，用户没有打字，也看不到这段话。\n")
            append("延后的任务：\n$listed\n")
            append("原因：")
            append(when (episode.reason) {
                KEEP_ALIVE_OFF -> "Operit 设置里的「后台保活」关着，系统不让 Operit 在后台启动任务。"
                BATTERY_RESTRICTED -> "「后台保活」开着，但系统的电池优化还管着 Operit（没有允许“忽略电池优化”），后台启动被拦了。"
                else -> "后台保活和忽略电池优化都开着，系统还是拦了，多半是手机管家的自启动或后台限制。" +
                    if (detail.isNotEmpty()) "系统原话：$detail" else ""
            })
            append("\n")
            if (lastTold != null) append("你在 ${day.format(Date(lastTold))} 已经跟用户提过一次同样的原因。\n")
            if (prefs.isNotEmpty()) append("用户的主动消息偏好（只用来判断要不要打扰）：\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("怎么做：\n")
            append("1. 先看偏好和聊天记录：用户说过不用提这类事（任务被拦、保活、电池），就调用 $nothing 保持安静。\n")
            append("2. 否则用你自己的话简短告诉用户哪些事晚了、为什么，别吓到他。")
            append("原因是保活或电池优化时，调用 $card（permission=keep_alive）放一张卡，让他一键打开；")
            append("原因是系统限制时，告诉他可能要在手机管家里允许 Operit 自启动和后台运行。\n")
            append("3. 用户说这次不用，就算了；说以后都别提，就把它记进 PROACTIVE_PREFERENCES.md 的「别跟我说」。不要追问，不要重复提醒。\n")
            append("不要提“隐藏消息”这些内部说法。")
        }
    }
}
