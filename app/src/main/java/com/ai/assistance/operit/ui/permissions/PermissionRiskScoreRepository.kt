package com.ai.assistance.operit.ui.permissions

import android.content.Context
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
)

/** How one record reads: what the classifier decided, or why it never ran. */
internal enum class PermissionRiskScoreDisplay {
    RUNNING,
    LOW,
    HIGH,
    FAILED,
    SKIPPED,
}

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
    private const val MAX_RECORDS = 200
    private const val PREFERENCES_NAME = "permission_risk_scores"
    private const val RECORDS_KEY = "records_v1"

    /**
     * Distinguishes the batches of this process from an earlier process's. Step numbers restart at 1
     * for a chat whose session is rebuilt, so without this a new batch would silently take the place
     * of the record an older one left at the same step.
     */
    internal val processGeneration: String = UUID.randomUUID().toString().take(8)

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
                    runCatching { json.decodeFromString<List<PermissionRiskScoreRecord>>(stored) }
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
        val payload = runCatching { json.encodeToString(_records.value) }.getOrNull() ?: return
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
