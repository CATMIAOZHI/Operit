package com.ai.assistance.operit.features.orbit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.orbitQuotaStore: DataStore<Preferences> by preferencesDataStore(name = "orbit_quota")

/**
 * Which Orbit a model call works for, and what for. [OrbitQuota] reads it where every model call
 * passes ([com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService]): a call that
 * carries it is held to Orbit's token allowance and counted on Orbit's usage page.
 */
class OrbitUsageTag(val orbitId: String, val purpose: OrbitQuota.Purpose) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OrbitUsageTag>

    fun withPurpose(purpose: OrbitQuota.Purpose) = OrbitUsageTag(orbitId, purpose)
}

/** Thrown before a model call once both of Orbit's allowances are used up: Orbit is paused. */
class OrbitQuotaPausedException(message: String) : IllegalStateException(message)

/**
 * Orbit's local token allowance, like Muse's plan bars but nothing is sold: a weekly allowance that
 * starts over every Monday at midnight, and an extra allowance that is only spent once the week's
 * is gone. The extra allowance either keeps until the user refills it, or starts over with the
 * week, as the user chooses. Every Orbit shares one allowance; the app keeps it so a busy Orbit
 * cannot spend the user's tokens without end.
 *
 * - The week's allowance used up: Orbit goes on with the extra one and is told once that week, so
 *   it can let the user know in its own words.
 * - Both used up: every Orbit model call fails before it is sent, and background tasks wait, until
 *   the week starts over or the user raises an allowance. The main app is never held.
 */
object OrbitQuota {
    const val DEFAULT_WEEKLY = 1_000_000_000L
    const val MIN_WEEKLY = 100_000_000L
    const val DEFAULT_EXTRA = 1_000_000_000L
    /** Far above any real use; only keeps a typo from becoming a number that overflows. */
    const val MAX_LIMIT = 1_000_000_000_000L
    /** How far back the usage page can look. */
    const val KEEP_DAYS = 90
    /** How far back the day totals for the activity heatmap go: a year, as the main app's shows. */
    const val HEATMAP_DAYS = 366
    const val MARKER = "[orbit-quota]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")

    /** What a call was for, as the usage page groups it. */
    enum class Purpose {
        CHAT, SUBAGENT, TASK, PROACTIVE, ALIGNMENT, FEED, IDEAS, GOALS, SELF_REVIEW, MEMORY, REVIEW,
        SUMMARY, TITLE, OTHER;

        companion object {
            fun of(name: String): Purpose = entries.firstOrNull { it.name == name } ?: OTHER
        }
    }

    data class State(
        val weeklyLimit: Long = DEFAULT_WEEKLY,
        val extraLimit: Long = DEFAULT_EXTRA,
        /** Monday 00:00 (local time) of the week [weeklyUsed] belongs to. */
        val weekStart: Long = 0L,
        val weeklyUsed: Long = 0L,
        val extraUsed: Long = 0L,
        /** The week Orbit was last told its weekly allowance ran out. */
        val warnedWeek: Long = 0L,
        /** The extra allowance starts over with the week, instead of keeping until refilled. */
        val extraWeekly: Boolean = false,
    ) {
        /**
         * Monday 00:00 of this allowance's week in the current time zone. [weekStart] was taken in
         * the zone of its time; read in another zone it can fall on Sunday evening, so the week is
         * found from a moment safely inside it.
         */
        val localWeekStart: Long get() = weekStartOf(weekStart + 2 * 86_400_000L)
        /** When the week starts over: the next Monday 00:00 in the current time zone, as [at] resets. */
        val weekEnd: Long get() = nextWeekStart(localWeekStart)
        val weeklyLeft: Long get() = (weeklyLimit - weeklyUsed).coerceAtLeast(0)
        val extraLeft: Long get() = (extraLimit - extraUsed).coerceAtLeast(0)
        val weeklyExhausted: Boolean get() = weeklyUsed >= weeklyLimit
        val paused: Boolean get() = weeklyExhausted && extraUsed >= extraLimit

        /**
         * The same state seen at [now]: a new week starts its allowance over (and the extra one, if
         * it follows the week). Only a real step to a later week counts: moving to another time zone
         * shifts "Monday 00:00" by some hours, and must not start the week over.
         */
        fun at(now: Long): State {
            val start = weekStartOf(now)
            val newWeek = weekStart == 0L || start - weekStart >= NEW_WEEK_GAP_MS
            return if (!newWeek) this
            else copy(weekStart = start, weeklyUsed = 0L, extraUsed = if (extraWeekly) 0L else extraUsed)
        }
    }

    /**
     * Two week starts this far apart are different weeks; closer ones are the same week in another
     * zone. Zones differ by at most 26 hours, so the same week moves by less than this, and the next
     * week, at least 7 days less 26 hours on, is always further.
     */
    private const val NEW_WEEK_GAP_MS = 3 * 86_400_000L

    /** One day's use by one Orbit for one purpose. */
    data class Usage(
        val day: String,
        val orbitId: String,
        val purpose: Purpose,
        val input: Long,
        val output: Long,
        val calls: Long,
    ) {
        val tokens: Long get() = input + output
    }

    /** One day's calls by one Orbit, by the hour of the day they were made in. */
    data class Hours(val day: String, val orbitId: String, val calls: List<Long>)

    /**
     * Everything the usage page shows: the use by day and purpose, the calls by hour, and the
     * tokens of every Orbit by day for the last year ([totals], day to tokens).
     */
    data class UsageData(val rows: List<Usage>, val hours: List<Hours>, val totals: Map<String, Long> = emptyMap())

    private val WEEKLY_LIMIT = longPreferencesKey("weekly_limit")
    private val EXTRA_LIMIT = longPreferencesKey("extra_limit")
    private val EXTRA_WEEKLY = booleanPreferencesKey("extra_weekly")
    private val WEEK_START = longPreferencesKey("week_start")
    private val WEEKLY_USED = longPreferencesKey("weekly_used")
    private val EXTRA_USED = longPreferencesKey("extra_used")
    private val WARNED_WEEK = longPreferencesKey("warned_week")
    private val DAYS = stringPreferencesKey("days")
    private val HOURS = stringPreferencesKey("hours")
    /** Day to [tokens, calls] of every Orbit, kept a year for the heatmap; [DAYS] only keeps 90 days. */
    private val TOTALS = stringPreferencesKey("totals")
    /** The Orbit told its week's allowance ran out, so the same one hears when it starts over. */
    private val WARNED_ORBIT = stringPreferencesKey("warned_orbit")
    /** The warned week whose start-over Orbit has been told about. */
    private val RESET_TOLD = longPreferencesKey("reset_told_week")
    private const val RESET_WORK = "orbit_quota_reset_notice"
    /** How long the start-over note waits for a quiet chat in one go; a worker may run 10 minutes at most. */
    private const val RESET_IDLE_WAIT_MS = 8 * 60_000L
    private const val RESET_RETRY_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warning = AtomicBoolean(false)
    /** How long the week's note waits for a quiet chat before a later call tries again. */
    private const val WARN_IDLE_WAIT_MS = 2 * 60 * 60_000L
    private const val TAG = "OrbitQuota"

    private fun read(p: Preferences) = State(
        // A limit saved under an older, lower floor reads as today's floor.
        weeklyLimit = (p[WEEKLY_LIMIT] ?: DEFAULT_WEEKLY).coerceIn(MIN_WEEKLY, MAX_LIMIT),
        extraLimit = p[EXTRA_LIMIT] ?: DEFAULT_EXTRA,
        weekStart = p[WEEK_START] ?: 0L,
        weeklyUsed = p[WEEKLY_USED] ?: 0L,
        extraUsed = p[EXTRA_USED] ?: 0L,
        warnedWeek = p[WARNED_WEEK] ?: 0L,
        extraWeekly = p[EXTRA_WEEKLY] ?: false,
    )

    /** The last state read, so a page can open with real numbers instead of a placeholder. */
    @Volatile
    var lastState: State? = null
        private set

    /** The allowances as they change; cheap, without the usage by day. */
    fun stateFlow(context: Context): Flow<State> =
        context.applicationContext.orbitQuotaStore.data
            .map { p -> read(p).at(System.currentTimeMillis()).also { lastState = it } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** The usage by day and by hour, read off the main thread and only when it changed. */
    fun usageFlow(context: Context): Flow<UsageData> =
        context.applicationContext.orbitQuotaStore.data
            .map { p -> Triple(p[DAYS], p[HOURS], p[TOTALS]) }
            .distinctUntilChanged()
            .map { (days, hours, totals) -> UsageData(parseDays(days), parseHours(hours), parseTotals(totals)) }
            .flowOn(Dispatchers.Default)

    suspend fun state(context: Context): State =
        read(context.applicationContext.orbitQuotaStore.data.first()).at(System.currentTimeMillis())
            .also { lastState = it }

    suspend fun paused(context: Context): Boolean = state(context).paused

    /** Throws [OrbitQuotaPausedException] when Orbit may not spend another token. */
    suspend fun checkAllowed(context: Context, tag: OrbitUsageTag) {
        val state = state(context)
        if (!state.paused) return
        val app = context.applicationContext
        val name = OrbitPreferences.getInstance(app).current().orbits.firstOrNull { it.id == tag.orbitId }
            ?.name?.takeIf { it.isNotBlank() } ?: app.getString(R.string.nav_orbit)
        throw OrbitQuotaPausedException(app.getString(R.string.orbit_quota_paused_error, name, name,
            resetText(app, state.weekEnd)))
    }

    /**
     * Saves both allowances and how the extra one resets. The use so far is first settled under the
     * old choice, so switching to "starts over with the week" never wipes extra spent in a past week
     * that was already counted as kept.
     */
    /** A null value keeps what is stored, read inside the same edit so parallel changes never undo each other. */
    suspend fun setLimits(context: Context, weekly: Long? = null, extra: Long? = null, extraWeekly: Boolean? = null) {
        val now = System.currentTimeMillis()
        context.applicationContext.orbitQuotaStore.edit { p ->
            val stored = read(p)
            val s = stored.at(now)
            p[WEEK_START] = s.weekStart
            p[WEEKLY_USED] = s.weeklyUsed
            p[EXTRA_USED] = s.extraUsed
            p[WEEKLY_LIMIT] = (weekly ?: stored.weeklyLimit).coerceIn(MIN_WEEKLY, MAX_LIMIT)
            p[EXTRA_LIMIT] = (extra ?: stored.extraLimit).coerceIn(0L, MAX_LIMIT)
            p[EXTRA_WEEKLY] = extraWeekly ?: stored.extraWeekly
        }
    }

    /** Fills the extra allowance again: what was spent from it no longer counts. */
    suspend fun refillExtra(context: Context) {
        context.applicationContext.orbitQuotaStore.edit { p -> p[EXTRA_USED] = 0L }
    }

    /**
     * Counts one finished model call. The week's allowance is spent first and the rest comes out of
     * the extra one, so the extra allowance only moves once the week's is gone.
     */
    suspend fun record(context: Context, tag: OrbitUsageTag, input: Long, output: Long) {
        val app = context.applicationContext
        val inTokens = input.coerceAtLeast(0)
        val outTokens = output.coerceAtLeast(0)
        val total = inTokens + outTokens
        val now = System.currentTimeMillis()
        val day = dayKey(now)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val oldest = dayKey(now - KEEP_DAYS * 86_400_000L)
        var after = State()
        app.orbitQuotaStore.edit { p ->
            val s = read(p).at(now)
            val toWeekly = minOf(total, s.weeklyLeft)
            p[WEEK_START] = s.weekStart
            p[WEEKLY_USED] = s.weeklyUsed + toWeekly
            p[EXTRA_USED] = s.extraUsed + (total - toWeekly)
            // A day leaving the 90-day detail keeps its whole total in the year's heatmap; this
            // also carries over days spent before the year's totals were kept.
            val dropped = HashMap<String, LongArray>()
            p[DAYS] = addDay(p[DAYS], day, tag, inTokens, outTokens, oldest) { d, tokens, calls ->
                dropped.getOrPut(d) { LongArray(2) }.let { it[0] += tokens; it[1] += calls }
            }
            p[HOURS] = addHour(p[HOURS], day, tag.orbitId, hour, oldest)
            p[TOTALS] = addTotal(p[TOTALS], day, total, dayKey(now - HEATMAP_DAYS * 86_400_000L), dropped)
            after = read(p)
        }
        if (after.weeklyExhausted && after.warnedWeek != after.weekStart && !after.paused) warn(app, tag.orbitId)
    }

    /**
     * Tells Orbit once a week that the week's allowance is gone, in its main chat once that is quiet,
     * as a hidden note; it decides from the user's preferences and habits whether and when to say so.
     */
    private fun warn(app: Context, orbitId: String) {
        if (!warning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val orbit = OrbitPreferences.getInstance(app).current().orbits
                    .firstOrNull { it.id == orbitId && !it.deleting && it.chatId.isNotBlank() } ?: return@launch
                val deadline = android.os.SystemClock.elapsedRealtime() + WARN_IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    if (!stillDue(state(app))) break
                    // The wait for a quiet chat can be long: whether to tell, and what, is decided
                    // again once the chat is held, from the allowance as it is then.
                    var sending = State()
                    val told = OrbitWake.tell(app, orbit, orbit.chatId, "", left, left,
                        ready = { state(app).also { sending = it }.let(::stillDue) },
                        onSent = {
                            app.orbitQuotaStore.edit { p ->
                                p[WARNED_WEEK] = sending.weekStart
                                p[WARNED_ORBIT] = orbitId
                            }
                            // Once the week starts over, Orbit hears that too; if this is cut
                            // short, [restoreResetNotice] sets it up again at the next start.
                            val queued = scheduleResetNotice(app, sending.weekEnd + 60_000L)
                            withContext(Dispatchers.IO) {
                                try {
                                    queued.result.get()
                                } catch (e: java.util.concurrent.ExecutionException) {
                                    AppLogger.w(TAG, "Could not set the allowance reset note: ${e.message}")
                                }
                            }
                        },
                        announce = false,
                        noticeNow = { notice(app, orbitId, sending) })
                    if (told != OrbitWake.Told.BUSY) break
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not tell Orbit its weekly allowance ran out: ${e.message}")
            } finally {
                warning.set(false)
            }
        }
    }

    /** The week's note is still to be told: the week's allowance is gone, the extra one is not, and Orbit has not heard. */
    private fun stillDue(state: State) = state.weeklyExhausted && !state.paused && state.warnedWeek != state.weekStart

    /** Sets the one wake-up, at [at], that tells Orbit its week's allowance started over. */
    fun scheduleResetNotice(app: Context, at: Long, keepPending: Boolean = false): androidx.work.Operation =
        WorkManager.getInstance(app).enqueueUniqueWork(RESET_WORK,
            if (keepPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OrbitQuotaResetWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build())

    /** At app start: a week whose reset Orbit still has to hear about gets its wake-up back if it was lost. */
    suspend fun restoreResetNotice(context: Context) {
        val app = context.applicationContext
        val p = app.orbitQuotaStore.data.first()
        val warned = p[WARNED_WEEK] ?: 0L
        if (warned == 0L || p[RESET_TOLD] == warned) return
        scheduleResetNotice(app, read(p).copy(weekStart = warned).weekEnd + 60_000L, keepPending = true)
    }

    /**
     * After a week in which Orbit was told its allowance ran out, tells it (as a hidden note, once
     * the chat is quiet) that the week started over, so it stops holding back. Returns when to try
     * again, or null when there is nothing (more) to tell.
     */
    internal suspend fun tellReset(app: Context): Long? {
        val p = app.orbitQuotaStore.data.first()
        val warned = p[WARNED_WEEK] ?: 0L
        if (warned == 0L || p[RESET_TOLD] == warned) return null
        val now = System.currentTimeMillis()
        // When the warned week ends in today's time zone, the same moment [State.at] starts over.
        val resetAt = read(p).copy(weekStart = warned).weekEnd
        if (now < resetAt) return resetAt - now + 60_000L
        val orbitId = p[WARNED_ORBIT].orEmpty()
        val orbit = OrbitPreferences.getInstance(app).current().orbits
            .firstOrNull { it.id == orbitId && !it.deleting && it.chatId.isNotBlank() }
        // A week on, or with that Orbit gone, it is no longer news: nothing is said.
        if (orbit == null || now >= nextWeekStart(resetAt)) {
            app.orbitQuotaStore.edit { it[RESET_TOLD] = warned }
            return null
        }
        var sending = State()
        var notYet: Long? = null
        val told = OrbitWake.tell(app, orbit, orbit.chatId, "", RESET_IDLE_WAIT_MS, RESET_IDLE_WAIT_MS,
            ready = {
                // The wait for a quiet chat can cross into another week or outlive the Orbit:
                // everything is checked again once the chat is held.
                val q = app.orbitQuotaStore.data.first()
                val at = System.currentTimeMillis()
                val due = q[WARNED_WEEK] == warned && q[RESET_TOLD] != warned
                val resetAgain = read(q).copy(weekStart = warned).weekEnd
                if (due && at < resetAgain) {
                    // A time zone change moved the reset back into the future: wait for it.
                    notYet = resetAgain - at + 60_000L
                    return@tell false
                }
                sending = read(q).at(at)
                val stillThere = OrbitPreferences.getInstance(app).current().orbits
                    .any { it.id == orbit.id && !it.deleting && it.chatId == orbit.chatId }
                val news = stillThere && at < nextWeekStart(resetAgain) && !sending.weeklyExhausted
                if (due && !news) app.orbitQuotaStore.edit { if (it[WARNED_WEEK] == warned) it[RESET_TOLD] = warned }
                due && news
            },
            onSent = { app.orbitQuotaStore.edit { it[RESET_TOLD] = warned } },
            announce = false,
            noticeNow = { resetNotice(app, orbit.id, sending) })
        return notYet ?: if (told == OrbitWake.Told.BUSY) RESET_RETRY_MS else null
    }

    private fun resetNotice(app: Context, orbitId: String, state: State): String {
        val english = LocaleUtils.usesEnglishPrompt(app)
        val preferences = try {
            OrbitFiles.readHead(OrbitFiles.of(app, orbitId).fileFor(OrbitFiles.PROACTIVE), 3000)
        } catch (e: Exception) {
            ""
        }
        val prefs = OrbitTaskRunner.fenced(preferences.trim())
        val nothing = OrbitReactionNotice.NOTHING_TO_DO
        val weekly = amount(state.weeklyLimit, english)
        val extra = amount(state.extraLeft, english)
        return if (english) buildString {
            append("$MARKER Weekly token allowance reset\n")
            append("A new week has started and the weekly token allowance ($weekly) is full again; $extra of the extra allowance is left. ")
            append("Last week you were told it ran out and to spend with care; that no longer applies. ")
            append("This is not a message from the user; they typed nothing and cannot see this note.\n")
            if (prefs.isNotEmpty()) append("The user's proactive preferences (only for deciding whether to bother them):\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("What to do:\n")
            append("1. Check the preferences and this chat first. If the user said not to bring up allowances or usage, call $nothing and stay quiet.\n")
            append("2. If you told the user the allowance ran out or you were paused, you may let them know briefly, in your own words, that you are back to normal, at a time that suits their habits; if you never mentioned it, or now is a bad time, call $nothing.\n")
            append("3. Work you put off to save tokens can go ahead again.\n")
            append("Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 本周额度已重置\n")
            append("新的一周开始了，每周 token 额度（$weekly）已经补满，额外额度还剩 $extra。")
            append("上周提醒过你额度用完、要省着用，现在不用了。")
            append("这不是用户发的消息，用户没有打字，也看不到这段话。\n")
            if (prefs.isNotEmpty()) append("用户的主动消息偏好（只用来判断要不要打扰）：\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("怎么做：\n")
            append("1. 先看偏好和聊天记录：用户说过不用提额度、用量这类事，就调用 $nothing 保持安静。\n")
            append("2. 如果你跟用户说过额度用完或暂停了，可以按用户的作息挑合适的时候，用你自己的话简短告诉用户已经恢复；没提过、或现在不合适，就调用 $nothing。\n")
            append("3. 之前为了省 token 推迟的事，可以接着做了。\n")
            append("不要提“隐藏消息”这些内部说法。")
        }
    }

    /** The title the work list shows for the turn the note started. */
    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun notice(app: Context, orbitId: String, state: State): String {
        val english = LocaleUtils.usesEnglishPrompt(app)
        val reset = SimpleDateFormat(if (english) "EEE MMM d, HH:mm" else "M月d日 HH:mm",
            if (english) Locale.US else Locale.CHINA).format(Date(state.weekEnd))
        val preferences = try {
            OrbitFiles.readHead(OrbitFiles.of(app, orbitId).fileFor(OrbitFiles.PROACTIVE), 3000)
        } catch (e: Exception) {
            ""
        }
        val prefs = OrbitTaskRunner.fenced(preferences.trim())
        val nothing = OrbitReactionNotice.NOTHING_TO_DO
        val weekly = amount(state.weeklyLimit, english)
        val extra = amount(state.extraLeft, english)
        return if (english) buildString {
            append("$MARKER Weekly token allowance used up\n")
            append("This week's token allowance ($weekly) is used up. From now on you are spending the extra allowance; $extra of it is left. ")
            append("When that is gone too you are paused until the week starts over ($reset) or the user raises an allowance at the top of your settings. ")
            append("This is not a message from the user; they typed nothing and cannot see this note.\n")
            if (prefs.isNotEmpty()) append("The user's proactive preferences (only for deciding whether to bother them):\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("What to do:\n")
            append("1. Check the preferences and this chat first. If the user said not to bring up allowances or usage, call $nothing and stay quiet.\n")
            append("2. Otherwise decide from their habits and the time whether to say it now; if now is a bad time, call $nothing. ")
            append("When you do say it, keep it short and calm, in your own words: the week's allowance is gone, how much extra is left, when it starts over, and that they can change it at the top of your settings.\n")
            append("3. Do not bring it up again this week. Spend tokens with care until then: skip work that can wait.\n")
            append("Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 本周额度用完了\n")
            append("这周的 token 额度（$weekly）已经用完，从现在起用的是额外额度，还剩 $extra。")
            append("额外额度也用完的话你会暂停，直到 $reset 每周额度重置，或用户在你的设置顶部调高额度。")
            append("这不是用户发的消息，用户没有打字，也看不到这段话。\n")
            if (prefs.isNotEmpty()) append("用户的主动消息偏好（只用来判断要不要打扰）：\nBEGIN PREFERENCES\n$prefs\nEND PREFERENCES\n")
            append("怎么做：\n")
            append("1. 先看偏好和聊天记录：用户说过不用提额度、用量这类事，就调用 $nothing 保持安静。\n")
            append("2. 否则按用户的作息和现在的时间判断要不要现在说；现在不合适就调用 $nothing。")
            append("要说的话，用你自己的话简短、平和地讲清楚：本周额度用完了、额外额度还剩多少、什么时候重置、可以在你的设置顶部调整额度。\n")
            append("3. 这周不要再提。在那之前省着用 token：能晚点做的事先放一放。\n")
            append("不要提“隐藏消息”这些内部说法。")
        }
    }

    /**
     * The tag for a model call in [chatId], which [orbit] owns (as [OrbitMemoryScope.owner] found).
     */
    suspend fun tagFor(context: Context, orbit: OrbitProfile, chatId: String?, functionType: FunctionType?,
        isSubTask: Boolean): OrbitUsageTag {
        val id = chatId?.takeIf { it.isNotBlank() }
        val purpose = when (functionType) {
            FunctionType.PERMISSION_REVIEWER -> Purpose.REVIEW
            FunctionType.MEMORY -> Purpose.MEMORY
            FunctionType.SUMMARY -> Purpose.SUMMARY
            FunctionType.TITLE_GENERATION -> Purpose.TITLE
            else -> id?.let { taskPurpose(context, it) } ?: if (isSubTask) Purpose.SUBAGENT else Purpose.CHAT
        }
        return OrbitUsageTag(orbit.id, purpose)
    }

    /**
     * The tag for model calls a tool makes on its own (a phone-control subagent, reading an image or
     * a recording), taken from the chat that called the tool: such a call runs outside the turn's
     * coroutine, yet is still that Orbit's work. Read on the thread that runs the tool. Empty for a
     * main-app chat.
     */
    suspend fun toolCallContext(context: Context): CoroutineContext {
        currentCoroutineContext()[OrbitUsageTag]?.let { return it }
        val chatId = com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.currentToolRuntimeContext()
            ?.callerChatId?.takeIf { it.isNotBlank() } ?: return EmptyCoroutineContext
        val orbit = try {
            OrbitMemoryScope.owner(context, chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return EmptyCoroutineContext
        val side = chatId == orbit.chatId || orbit.sideChats.any { it.chatId == chatId }
        return tagFor(context, orbit, chatId, null, isSubTask = !side)
    }

    /** The tag for a call made for [chatId] outside a turn (a summary or a title). */
    suspend fun tagForChat(context: Context, chatId: String?, purpose: Purpose): OrbitUsageTag? {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return null
        val orbit = OrbitPreferences.getInstance(context).current().ownerForChat(id) ?: return null
        return OrbitUsageTag(orbit.id, purpose)
    }

    /** The background task a chat (or a subagent under it) runs for, named by what the task is. */
    private suspend fun taskPurpose(context: Context, chatId: String): Purpose? {
        val db = AppDatabase.getDatabase(context)
        val tasks = db.orbitTaskDao()
        var cursor: String? = chatId
        var depth = 0
        while (cursor != null && depth++ < 12) {
            tasks.binding(cursor)?.let { binding ->
                val task = tasks.run(binding.runId)?.let { tasks.task(it.taskId) } ?: return Purpose.TASK
                return when (task.source) {
                    OrbitRoutines.PROACTIVE_SOURCE -> Purpose.PROACTIVE
                    OrbitRoutines.ALIGNMENT_SOURCE -> Purpose.ALIGNMENT
                    OrbitRoutines.FEED_SOURCE -> Purpose.FEED
                    OrbitRoutines.IDEAS_SOURCE -> Purpose.IDEAS
                    OrbitRoutines.GOALS_SOURCE -> Purpose.GOALS
                    OrbitRoutines.CHAT_SOURCE -> Purpose.SELF_REVIEW
                    else -> Purpose.TASK
                }
            }
            cursor = db.chatDao().getChatById(cursor)?.parentChatId
        }
        return null
    }

    // ---- usage by day ----

    private fun addDay(
        raw: String?, day: String, tag: OrbitUsageTag, input: Long, output: Long, oldest: String,
        onDrop: (day: String, tokens: Long, calls: Long) -> Unit = { _, _, _ -> },
    ): String {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        days.keys().asSequence().toList().filter { it < oldest }.forEach { d ->
            val byOrbit = days.optJSONObject(d)
            if (byOrbit != null) for (orbitId in byOrbit.keys()) {
                val byPurpose = byOrbit.optJSONObject(orbitId) ?: continue
                for (purpose in byPurpose.keys()) {
                    val v = byPurpose.optJSONArray(purpose) ?: continue
                    onDrop(d, v.optLong(0) + v.optLong(1), v.optLong(2))
                }
            }
            days.remove(d)
        }
        val byOrbit = days.optJSONObject(day) ?: JSONObject().also { days.put(day, it) }
        val byPurpose = byOrbit.optJSONObject(tag.orbitId) ?: JSONObject().also { byOrbit.put(tag.orbitId, it) }
        val old = byPurpose.optJSONArray(tag.purpose.name)
        byPurpose.put(tag.purpose.name, JSONArray()
            .put((old?.optLong(0) ?: 0L) + input)
            .put((old?.optLong(1) ?: 0L) + output)
            .put((old?.optLong(2) ?: 0L) + 1))
        return days.toString()
    }

    private fun parseDays(raw: String?): List<Usage> {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Usage>()
        for (day in days.keys()) {
            val byOrbit = days.optJSONObject(day) ?: continue
            for (orbitId in byOrbit.keys()) {
                val byPurpose = byOrbit.optJSONObject(orbitId) ?: continue
                for (purpose in byPurpose.keys()) {
                    val v = byPurpose.optJSONArray(purpose) ?: continue
                    out += Usage(day, orbitId, Purpose.of(purpose), v.optLong(0), v.optLong(1), v.optLong(2))
                }
            }
        }
        return out
    }

    private fun addHour(raw: String?, day: String, orbitId: String, hour: Int, oldest: String): String {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }
        val byOrbit = days.optJSONObject(day) ?: JSONObject().also { days.put(day, it) }
        val old = byOrbit.optJSONArray(orbitId)
        val counts = JSONArray()
        for (h in 0 until 24) counts.put((old?.optLong(h) ?: 0L) + if (h == hour) 1 else 0)
        byOrbit.put(orbitId, counts)
        return days.toString()
    }

    private fun parseHours(raw: String?): List<Hours> {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Hours>()
        for (day in days.keys()) {
            val byOrbit = days.optJSONObject(day) ?: continue
            for (orbitId in byOrbit.keys()) {
                val v = byOrbit.optJSONArray(orbitId) ?: continue
                out += Hours(day, orbitId, List(24) { v.optLong(it) })
            }
        }
        return out
    }

    private fun addTotal(raw: String?, day: String, tokens: Long, oldest: String, dropped: Map<String, LongArray>): String {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }
        // The detail of a day was complete; the year's total of it may have started late.
        dropped.forEach { (d, sum) ->
            if (d < oldest) return@forEach
            val old = days.optJSONArray(d)
            days.put(d, JSONArray().put(maxOf(old?.optLong(0) ?: 0L, sum[0])).put(maxOf(old?.optLong(1) ?: 0L, sum[1])))
        }
        val old = days.optJSONArray(day)
        days.put(day, JSONArray().put((old?.optLong(0) ?: 0L) + tokens).put((old?.optLong(1) ?: 0L) + 1))
        return days.toString()
    }

    private fun parseTotals(raw: String?): Map<String, Long> {
        val days = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return days.keys().asSequence().associateWith { days.optJSONArray(it)?.optLong(0) ?: 0L }
    }

    fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    /** Monday 00:00 in local time of the week [now] is in. */
    fun weekStartOf(now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        // SUNDAY is 1 and MONDAY 2: how many days back Monday was.
        c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        return c.timeInMillis
    }

    fun nextWeekStart(weekStart: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = weekStart
        c.add(Calendar.DAY_OF_MONTH, 7)
        return c.timeInMillis
    }

    /**
     * Whether amounts and dates are written the English way (K/M/B) or the Chinese way (万/亿; every
     * other language falls back to the Chinese strings). Decided by the strings themselves, apart from
     * the unit amounts are typed in, which is a million in both.
     */
    private fun english(context: Context) = context.getString(R.string.orbit_quota_number_style) == "en"

    /** How many tokens one typed unit is: 1000000 (百万 / M). */
    fun unitSize(context: Context): Long =
        context.getString(R.string.orbit_quota_unit_size).toLongOrNull()?.takeIf { it > 0 } ?: 1_000_000L

    /** "10月27日 00:00"-style time the week starts over, for the user. */
    fun resetText(context: Context, at: Long): String {
        val english = english(context)
        return SimpleDateFormat(if (english) "MMM d, HH:mm" else "M月d日 HH:mm",
            if (english) Locale.US else Locale.CHINA).format(Date(at))
    }

    /** A token count the way people say it: 1亿, 5000万, 3.2万 / 100M, 1.5B. */
    fun amount(value: Long, english: Boolean): String {
        fun trim(v: Double) = if (v >= 100 || v == Math.floor(v)) String.format(Locale.US, "%.0f", v)
            else String.format(Locale.US, "%.1f", v).removeSuffix(".0")
        return if (english) when {
            value >= 1_000_000_000L -> trim(value / 1_000_000_000.0) + "B"
            value >= 1_000_000L -> trim(value / 1_000_000.0) + "M"
            value >= 1_000L -> trim(value / 1_000.0) + "K"
            else -> value.toString()
        } else when {
            value >= 100_000_000L -> trim(value / 100_000_000.0) + "亿"
            value >= 10_000L -> trim(value / 10_000.0) + "万"
            else -> value.toString()
        }
    }

    fun amount(context: Context, value: Long): String = amount(value, english(context))
}

/** Wakes at the start of a new week to tell Orbit its allowance is full again; see [OrbitQuota.tellReset]. */
class OrbitQuotaResetWorker(context: Context, parameters: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val again = try {
            OrbitQuota.tellReset(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OrbitQuota", "Could not tell Orbit its weekly allowance reset: ${e.message}")
            15 * 60_000L
        }
        if (again != null) OrbitQuota.scheduleResetNotice(applicationContext, System.currentTimeMillis() + again)
        return Result.success()
    }
}
