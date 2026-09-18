package com.ai.assistance.operit.ui.permissions

import android.content.Context
import com.ai.assistance.operit.data.stats.TokenCostCalculator
import com.ai.assistance.operit.util.AppLogger
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * How the stored pre-classification history is written and read. It is named rather than inlined so
 * the test that pins compatibility with rows an older version wrote reads them the way the app does
 * instead of through a configuration of its own.
 */
internal val permissionRiskScoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * One tool batch as the fast level's pre-classification saw it: what the lightweight classifier was
 * asked to judge, what it answered, or why it could not be asked at all. A batch that was never
 * going to be scored - a level that does not score, or a batch with nothing a reviewer would judge -
 * leaves no record, because nothing happened that the user could act on.
 *
 * [step] advances once per dispatched batch, which is the window a verdict stays usable in, so the
 * record says where in that window the verdict landed. The classifier never denies anything: a high
 * or failed verdict only sends the calls to the blocking reviewer, and [answeredCalls] is how many
 * reviewed calls this batch's verdict answered instead of running that reviewer.
 */
@Serializable
internal data class PermissionRiskScoreRecord(
    val id: String,
    val parentChatId: String,
    val step: Int,
    val startedAt: Long,
    val completedAt: Long? = null,
    val verdict: PermissionRiskVerdict? = null,
    val skip: PermissionRiskScoringSkip? = null,
    /** The classifier call failed, and the batch failed closed onto the blocking reviewer. */
    val failedClosed: Boolean = false,
    /** This batch threw the stored verdict away, so nothing older can answer a later call. */
    val discardedStoredScore: Boolean = false,
    val scorableActions: Int = 0,
    val totalActions: Int = 0,
    /** A batch action the user's own settings refuse, which no reviewer ever judges. */
    val refusedBySettings: Boolean = false,
    val answeredCalls: Int = 0,
    /** The tools the batch dispatched, bounded, so the row says what was judged. */
    val toolNames: List<String> = emptyList(),
    /**
     * What the classifier's own call cost, as the provider reported it, or null when it reported
     * nothing. The classifier makes one call with no conversation behind it, so these are the numbers
     * the usage statistics page counts under its category, and the row can say what a batch actually
     * spent instead of leaving it to be inferred from the prompt's size.
     */
    val usage: PermissionRiskScoreUsage? = null,
    /**
     * The earlier review decisions this batch's classifier input carried: how many, how many
     * characters they took, and a fingerprint of the rendered block.
     *
     * The classifier's request is a single call rather than a conversation, so there is no session to
     * read the prompt back from. The block is rebuilt from the review events instead, and these three
     * are what makes the batch's evidence checkable: the count says whether decisions were carried at
     * all, and a rebuild that ends with the same fingerprint proves it is the same block.
     *
     * The fingerprint is a claim about the events the batch was shown, not a permanent one. The
     * review history is a bounded window whose stored form drops each event's rationale and action
     * arguments, and the block itself drops its oldest fragments once its budget is reached, so a
     * later rebuild can legitimately differ even though nothing about this batch changed.
     */
    val priorReviewCount: Int = 0,
    val priorReviewChars: Int = 0,
    val priorReviewHash: String = "",
)

/** How many tool names one record keeps: a batch can hold many calls, and the row shows them all. */
internal const val MAX_RECORDED_BATCH_TOOLS = 6

/** A tool name comes from the package that registered it, so one long name cannot grow the store. */
internal const val MAX_RECORDED_TOOL_NAME_CHARS = 48

/**
 * How much of a block's fingerprint the row shows. The full value is long enough to make the row
 * unreadable, and a prefix is enough to compare two rows or a row against a rebuild by eye.
 */
internal const val PRIOR_REVIEW_FINGERPRINT_CHARS = 8

/**
 * The tools a batch asked the classifier about, in the order they were dispatched. The list is
 * distinct and bounded, and a batch that dispatched the same tool twice is named once.
 */
internal fun permissionRiskBatchToolNames(actions: List<PermissionRiskAction>): List<String> =
    actions
        .map { action -> action.canonical.toolName.trim().take(MAX_RECORDED_TOOL_NAME_CHARS) }
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_RECORDED_BATCH_TOOLS)

/** How one record reads: what the classifier decided, or why it never ran. */
internal enum class PermissionRiskScoreDisplay {
    RUNNING,
    LOW,
    HIGH,
    FAILED,
    SKIPPED,
}

/**
 * What the classifier's own call cost, as the provider reported it.
 *
 * Every component is nullable for the reason the usage ledger gives: a component the provider did not
 * report is unknown, not zero, and a provider that reported nothing at all leaves the whole value
 * null.
 *
 * The cache read and the output come straight from the reports the ledger counted, through the same
 * merging and aggregation, so those two say what the usage statistics page says. [inputTokens] is a
 * derivation rather than one of the ledger's own figures: the ledger bills the split parts, and falls
 * back to a stated total only when the split is unknown and both input prices are equal; its canonical
 * and context input totals also need the cache-write count that Anthropic prices separately. The row
 * prices nothing, so it reports what the provider gave instead of the figure the ledger can bill.
 */
@Serializable
internal data class PermissionRiskScoreUsage(
    val uncachedInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val totalInputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/**
 * The call's input as the row states it: the total the provider gave, or the two parts it split the
 * input into. Without a split it falls back to the uncached part alone, which is what a provider that
 * reports no cache field is saying the input was, and it refuses to call the cached part an input,
 * because a cache read is only a share of one.
 *
 * This is not one of the ledger's own figures: its billed input is the two parts summed and never a
 * stated total, and it is unknown when the cache read is; its canonical and context input totals also
 * need the cache-write count that Anthropic prices separately. The row prices nothing, so it states
 * what it was given instead of withholding it.
 */
internal fun PermissionRiskScoreUsage.inputTokens(): Long? =
    totalInputTokens
        ?: if (uncachedInputTokens != null && cachedInputTokens != null) {
            TokenCostCalculator.saturatedAdd(uncachedInputTokens, cachedInputTokens)
        } else {
            uncachedInputTokens
        }

/**
 * The share of this call's input the provider served from its own cache, or null when it did not
 * report enough to say. Zero is a real answer - the provider stated that nothing was read from the
 * cache - and is what a batch with no cache hit reads as.
 */
internal fun PermissionRiskScoreUsage.cacheReadPercent(): Int? {
    val input = inputTokens() ?: return null
    val cached = cachedInputTokens ?: return null
    if (input <= 0L) return null
    return ((cached * 100L) / input).toInt().coerceIn(0, 100)
}

/**
 * One log line for a batch's call. A component the provider did not report reads as a dash rather
 * than a zero, so a reader of the log cannot mistake an unknown for a measurement.
 */
internal fun PermissionRiskScoreUsage?.summaryForLog(): String =
    this?.let { usage ->
        "in=${usage.inputTokens() ?: UNREPORTED_TOKEN_COUNT}" +
            ",cached=${usage.cachedInputTokens ?: UNREPORTED_TOKEN_COUNT}" +
            ",out=${usage.outputTokens ?: UNREPORTED_TOKEN_COUNT}"
    } ?: "unreported"

/** How a component the provider did not report is written in a log line. */
private const val UNREPORTED_TOKEN_COUNT = "-"

internal fun PermissionRiskScoreRecord.display(): PermissionRiskScoreDisplay =
    when {
        skip != null -> PermissionRiskScoreDisplay.SKIPPED
        failedClosed -> PermissionRiskScoreDisplay.FAILED
        verdict == PermissionRiskVerdict.LOW -> PermissionRiskScoreDisplay.LOW
        verdict == PermissionRiskVerdict.HIGH -> PermissionRiskScoreDisplay.HIGH
        else -> PermissionRiskScoreDisplay.RUNNING
    }

/** What the records in one chat add up to, for the summary above the list. */
internal data class PermissionRiskScoreSummary(
    val total: Int,
    /** Batches the classifier was actually asked to score, whether or not it answered in time. */
    val scored: Int,
    val low: Int,
    val high: Int,
    val failed: Int,
    val skipped: Int,
    val running: Int,
    val answeredCalls: Int,
)

internal fun permissionRiskScoreSummary(
    records: List<PermissionRiskScoreRecord>
): PermissionRiskScoreSummary {
    var low = 0
    var high = 0
    var failed = 0
    var skipped = 0
    var running = 0
    var answered = 0
    records.forEach { record ->
        when (record.display()) {
            PermissionRiskScoreDisplay.LOW -> low += 1
            PermissionRiskScoreDisplay.HIGH -> high += 1
            PermissionRiskScoreDisplay.FAILED -> failed += 1
            PermissionRiskScoreDisplay.SKIPPED -> skipped += 1
            PermissionRiskScoreDisplay.RUNNING -> running += 1
        }
        answered += record.answeredCalls
    }
    return PermissionRiskScoreSummary(
        total = records.size,
        scored = low + high + failed + running,
        low = low,
        high = high,
        failed = failed,
        skipped = skipped,
        running = running,
        answeredCalls = answered,
    )
}

/**
 * The pre-classification history of the fast level, newest last and bounded, kept beside the review
 * events for the same reason: the automatic review answers a call from a record that is not a
 * subagent run, so without this the user has nothing to look at when a call was allowed without a
 * reviewer turn.
 */
internal object PermissionRiskScoreRepository {
    private const val TAG = "PermissionRiskScores"

    /**
     * How many records the store keeps, across every chat in the app rather than per chat, which is
     * why the page says when the oldest ones started being dropped. One record is one dispatched
     * tool batch, so this covers far more than the tool calls a single conversation dispatches, and
     * the payload stays small enough to rewrite on every batch.
     */
    internal const val MAX_RECORDS = 500

    private const val PREFERENCES_NAME = "permission_risk_scores"
    private const val RECORDS_KEY = "records_v1"

    /**
     * Distinguishes the batches of this process from an earlier process's. Step numbers restart at 1
     * for a chat whose session is rebuilt, so without this a new batch would silently take the place
     * of the record an older one left at the same step.
     */
    internal val processGeneration: String = UUID.randomUUID().toString().take(8)

    private val lock = Any()
    @Volatile private var applicationContext: Context? = null
    @Volatile private var storedRecordsLoaded = false
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<PermissionRiskScoreRecord>>(emptyList())
    val records: StateFlow<List<PermissionRiskScoreRecord>> = _records.asStateFlow()

    /** Registers the stored history and reads it off the calling thread, like the review events. */
    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(lock) {
            if (applicationContext != null) return
            applicationContext = context.applicationContext
        }
        loadScope.launch { ensureStoredRecordsLoaded() }
    }

    private fun ensureStoredRecordsLoaded() {
        if (storedRecordsLoaded) return
        synchronized(lock) {
            if (storedRecordsLoaded) return
            loadStoredRecordsLocked()
        }
    }

    private fun loadStoredRecordsLocked() {
        val appContext = applicationContext ?: return
        try {
            val stored =
                runCatching {
                        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .getString(RECORDS_KEY, null)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "Failed to read stored permission risk scores", error)
                    }
                    .getOrNull()
            if (!stored.isNullOrBlank()) {
                val decoded =
                    runCatching {
                            permissionRiskScoreJson
                                .decodeFromString<List<PermissionRiskScoreRecord>>(stored)
                        }
                        .onFailure { error ->
                            AppLogger.e(
                                TAG,
                                "Stored permission risk scores are invalid; preserving raw data",
                                error,
                            )
                        }
                        .getOrNull()
                if (decoded != null) {
                    // A classification that was still running when the process ended never finished,
                    // so it is reported as the failure it became rather than left in progress.
                    val storedRecords =
                        decoded
                            .map { record ->
                                if (record.display() == PermissionRiskScoreDisplay.RUNNING) {
                                    record.copy(
                                        completedAt = System.currentTimeMillis(),
                                        failedClosed = true,
                                    )
                                } else {
                                    record
                                }
                            }
                            .takeLast(MAX_RECORDS)
                    val pending =
                        _records.value.filterNot { record ->
                            storedRecords.any { it.id == record.id }
                        }
                    _records.value = (storedRecords + pending).takeLast(MAX_RECORDS)
                    persistLocked()
                }
            }
        } finally {
            storedRecordsLoaded = true
        }
    }

    private fun persistLocked() {
        val appContext = applicationContext ?: return
        val payload =
            runCatching { permissionRiskScoreJson.encodeToString(_records.value) }.getOrNull()
                ?: return
        runCatching {
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(RECORDS_KEY, payload)
                    .apply()
            }
            .onFailure { error -> AppLogger.e(TAG, "Failed to store permission risk scores", error) }
    }

    fun publish(record: PermissionRiskScoreRecord) {
        ensureStoredRecordsLoaded()
        synchronized(lock) {
            val retained = _records.value.filterNot { existing -> existing.id == record.id }
            _records.value = (retained + record).takeLast(MAX_RECORDS)
            persistLocked()
        }
    }

    /**
     * Records that one reviewed call was answered by the verdict of [step] instead of the blocking
     * reviewer, which is the only thing the fast level saves the user.
     *
     * The row is matched by identity, not by chat and step alone: a score can only ever be consumed
     * by the process that took it, so an earlier process's row at the same step is a different
     * batch and must not be credited with this call.
     */
    fun noteAnsweredCall(parentChatId: String, step: Int) {
        ensureStoredRecordsLoaded()
        synchronized(lock) {
            val targetId = recordId(parentChatId, step)
            if (_records.value.none { record -> record.id == targetId }) return
            _records.value =
                _records.value.map { record ->
                    if (record.id == targetId) {
                        record.copy(answeredCalls = record.answeredCalls + 1)
                    } else {
                        record
                    }
                }
            persistLocked()
        }
    }

    /**
     * The row one batch owns. The chat and the step identify the batch, and the process generation
     * keeps an earlier process's batch at the same step from being mistaken for it.
     */
    internal fun recordId(parentChatId: String, step: Int): String =
        "$parentChatId#$step@$processGeneration"
}
