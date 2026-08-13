package com.ai.assistance.operit.core.workflow

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import androidx.work.*
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

internal fun specificTimeInitialDelay(
    targetTime: Long,
    currentTime: Long,
    allowPastTarget: Boolean,
): Long? {
    val delay = targetTime - currentTime
    return when {
        delay >= 0L -> delay
        allowPastTarget -> 0L
        else -> null
    }
}

internal fun isActiveWorkflowScheduleState(state: WorkInfo.State): Boolean =
    state == WorkInfo.State.ENQUEUED ||
        state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.BLOCKED

internal fun intervalReplacementInitialDelayMinutes(
    intervalMinutes: Long,
    delayFirstRun: Boolean,
): Long? = intervalMinutes.takeIf { delayFirstRun }

internal fun deferredCronInitialDelayMillis(
    calculatedDelay: Long,
    runImmediately: Boolean,
): Long = if (runImmediately) 0L else calculatedDelay

internal fun shouldRetireCompletedPreFingerprintOneTime(
    workflow: Workflow,
    workInfos: Collection<WorkflowScheduleWorkInfo>,
    targetIsDue: Boolean,
    excludedWorkRequestIds: Set<UUID> = emptySet(),
): Boolean {
    if (workflow.scheduleFingerprintGeneration != null) return false
    if (!targetIsDue) return false
    val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
        .firstOrNull { it.triggerType == "schedule" }
        ?: return false
    if (triggerNode.triggerConfig[WorkflowScheduler.CONFIG_SCHEDULE_TYPE] !=
        WorkflowScheduler.SCHEDULE_TYPE_SPECIFIC_TIME
    ) return false
    return workInfos.any { info ->
        info.id !in excludedWorkRequestIds &&
        (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED) &&
            info.tags.none { tag ->
                tag.startsWith(WorkflowScheduler.SCHEDULE_FINGERPRINT_TAG_PREFIX)
            }
    }
}

internal data class WorkflowScheduleWorkInfo(
    val state: WorkInfo.State,
    val tags: Set<String>,
    val id: UUID = UUID(0L, 0L),
)

internal fun isActiveMatchingWorkflowSchedule(
    state: WorkInfo.State,
    tags: Set<String>,
    expectedFingerprintTag: String,
): Boolean =
    isActiveWorkflowScheduleState(state) &&
        expectedFingerprintTag in tags

internal fun isActivePreFingerprintWorkflowSchedule(
    state: WorkInfo.State,
    tags: Set<String>,
): Boolean =
    isActiveWorkflowScheduleState(state) &&
        tags.none { tag -> tag.startsWith(WorkflowScheduler.SCHEDULE_FINGERPRINT_TAG_PREFIX) }

/**
 * WorkflowScheduler manages scheduling workflows using WorkManager
 * 
 * Supports three scheduling types:
 * - interval: Fixed interval execution (e.g., every 15 minutes)
 * - specific_time: One-time execution at a specific time
 * - cron: Cron-based scheduling (simplified implementation)
 */
class WorkflowScheduler(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowScheduler"
        private const val WORK_NAME_PREFIX = "workflow_"
        
        // Schedule types
        const val SCHEDULE_TYPE_INTERVAL = "interval"
        const val SCHEDULE_TYPE_SPECIFIC_TIME = "specific_time"
        const val SCHEDULE_TYPE_CRON = "cron"
        
        // Config keys
        const val CONFIG_SCHEDULE_TYPE = "schedule_type"
        const val CONFIG_INTERVAL_MS = "interval_ms"
        const val CONFIG_SPECIFIC_TIME = "specific_time"
        const val CONFIG_CRON_EXPRESSION = "cron_expression"
        const val CONFIG_REPEAT = "repeat"
        const val CONFIG_END_TIME = "end_time"
        const val CONFIG_ENABLED = "enabled"
        
        // WorkManager data keys
        const val KEY_TRIGGER_NODE_ID = "trigger_node_id"
        const val KEY_SCHEDULE_FINGERPRINT = "schedule_fingerprint"
        internal const val SCHEDULE_FINGERPRINT_TAG_PREFIX = "workflow_schedule_fingerprint:"
        const val PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION = -1
        const val CLAIMED_SCHEDULE_FINGERPRINT_GENERATION = 0
        const val CURRENT_SCHEDULE_FINGERPRINT_GENERATION = 1
        const val REJECTED_SCHEDULE_FINGERPRINT_GENERATION = 2

        internal fun scheduleFingerprint(workflowId: String, triggerNode: TriggerNode): String {
            val canonicalConfig = triggerNode.triggerConfig.toSortedMap().entries.joinToString("\u0000") {
                (key, value) -> "${key.length}:$key${value.length}:$value"
            }
            val payload = "$workflowId\u0000${triggerNode.id}\u0000${triggerNode.triggerType}\u0000$canonicalConfig"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        internal fun scheduleFingerprintTag(fingerprint: String): String =
            SCHEDULE_FINGERPRINT_TAG_PREFIX + fingerprint
    }

    private val workManager: WorkManager by lazy { 
        WorkManager.getInstance(context.applicationContext)
    }
    private val gateRetryStore by lazy { WorkflowGateRetryStore(context.applicationContext) }

    /**
     * Schedule a workflow based on its trigger configuration
     */
    fun scheduleWorkflow(
        workflow: Workflow,
        allowDuePreFingerprintOneTime: Boolean = false,
        delayFirstIntervalRun: Boolean = false,
        runDeferredCronImmediately: Boolean = false,
    ): Boolean {
        // Find the trigger node
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }

        if (triggerNode == null) {
            AppLogger.d(TAG, "No schedule trigger found for workflow: ${workflow.id}")
            return false
        }

        val config = triggerNode.triggerConfig
        val scheduleType = config[CONFIG_SCHEDULE_TYPE] ?: return false
        val enabled = config[CONFIG_ENABLED]?.toBoolean() ?: true

        if (!enabled) {
            AppLogger.d(TAG, "Schedule is disabled for workflow: ${workflow.id}")
            return false
        }

        val scheduleFingerprint = scheduleFingerprint(workflow.id, triggerNode)

        return when (scheduleType) {
            SCHEDULE_TYPE_INTERVAL -> scheduleIntervalWorkflow(
                workflow.id,
                triggerNode.id,
                config,
                scheduleFingerprint,
                delayFirstIntervalRun,
            )
            SCHEDULE_TYPE_SPECIFIC_TIME -> scheduleOneTimeWorkflow(
                workflow.id,
                triggerNode.id,
                config,
                scheduleFingerprint,
                allowDuePreFingerprintOneTime,
            )
            SCHEDULE_TYPE_CRON -> scheduleCronWorkflow(
                workflow.id,
                triggerNode.id,
                config,
                scheduleFingerprint,
                runDeferredCronImmediately,
            )
            else -> {
                AppLogger.e(TAG, "Unknown schedule type: $scheduleType")
                false
            }
        }
    }

    /**
     * Schedule workflow with fixed interval
     */
    private fun scheduleIntervalWorkflow(
        workflowId: String,
        triggerNodeId: String,
        config: Map<String, String>,
        scheduleFingerprint: String,
        delayFirstRun: Boolean,
    ): Boolean {
        val intervalMs = config[CONFIG_INTERVAL_MS]?.toLongOrNull() ?: return false
        val repeat = config[CONFIG_REPEAT]?.toBoolean() ?: true

        if (!repeat) {
            AppLogger.w(TAG, "Interval scheduling requires repeat=true")
            return false
        }

        // Minimum interval is 15 minutes per WorkManager restrictions
        val intervalMinutes = (intervalMs / 60000).coerceAtLeast(15)

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WorkflowWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .apply {
                intervalReplacementInitialDelayMinutes(intervalMinutes, delayFirstRun)?.let { delay ->
                    setInitialDelay(delay, TimeUnit.MINUTES)
                }
            }
            .setInputData(
                workDataOf(
                    WorkflowWorker.KEY_WORKFLOW_ID to workflowId,
                    KEY_TRIGGER_NODE_ID to triggerNodeId,
                    KEY_SCHEDULE_FINGERPRINT to scheduleFingerprint,
                )
            )
            .addTag(workflowId)
            .addTag(scheduleFingerprintTag(scheduleFingerprint))
            .build()

        workManager.enqueueUniquePeriodicWork(
            getWorkName(workflowId),
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        ).result.get()

        AppLogger.d(TAG, "Scheduled interval workflow: $workflowId, trigger: $triggerNodeId, interval: $intervalMinutes minutes")
        return true
    }

    /**
     * Schedule workflow for specific time
     */
    private fun scheduleOneTimeWorkflow(
        workflowId: String,
        triggerNodeId: String,
        config: Map<String, String>,
        scheduleFingerprint: String,
        allowPastTarget: Boolean = false,
    ): Boolean {
        val specificTimeStr = config[CONFIG_SPECIFIC_TIME] ?: return false
        val repeat = config[CONFIG_REPEAT]?.toBoolean() ?: false

        val targetTime = try {
            parseDateTime(specificTimeStr)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse specific_time: $specificTimeStr", e)
            return false
        }

        val currentTime = System.currentTimeMillis()
        val initialDelay = specificTimeInitialDelay(
            targetTime = targetTime,
            currentTime = currentTime,
            allowPastTarget = allowPastTarget,
        )
        if (initialDelay == null) {
            AppLogger.w(TAG, "Specific time is in the past: $specificTimeStr")
            return false
        }

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    WorkflowWorker.KEY_WORKFLOW_ID to workflowId,
                    KEY_TRIGGER_NODE_ID to triggerNodeId,
                    KEY_SCHEDULE_FINGERPRINT to scheduleFingerprint,
                )
            )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(workflowId)
            .addTag(scheduleFingerprintTag(scheduleFingerprint))
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(workflowId),
            ExistingWorkPolicy.REPLACE,
            workRequest
        ).result.get()

        AppLogger.d(TAG, "Scheduled one-time workflow: $workflowId, trigger: $triggerNodeId, time: $specificTimeStr, delay: ${initialDelay}ms")
        return true
    }

    /**
     * Schedule workflow using cron expression
     * 
     * Simplified cron implementation that calculates next execution time
     */
    private fun scheduleCronWorkflow(
        workflowId: String,
        triggerNodeId: String,
        config: Map<String, String>,
        scheduleFingerprint: String,
        runImmediately: Boolean,
    ): Boolean {
        val cronExpression = config[CONFIG_CRON_EXPRESSION] ?: return false
        val repeat = config[CONFIG_REPEAT]?.toBoolean() ?: true

        val nextExecutionTime = calculateNextCronTime(cronExpression)
        if (nextExecutionTime == null) {
            AppLogger.e(TAG, "Failed to calculate next cron time for: $cronExpression")
            return false
        }

        val currentTime = System.currentTimeMillis()
        val delay = deferredCronInitialDelayMillis(
            calculatedDelay = nextExecutionTime - currentTime,
            runImmediately = runImmediately,
        )

        if (delay < 0) {
            AppLogger.w(TAG, "Calculated cron time is in the past")
            return false
        }

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        if (repeat) {
            // For repeated cron, we use the interval between executions
            // This is a simplified approach - ideally we'd reschedule after each execution
            val intervalMs = calculateCronInterval(cronExpression)
            if (intervalMs != null && intervalMs >= 15 * 60 * 1000) {
                val intervalMinutes = intervalMs / 60000
                val workRequest = PeriodicWorkRequestBuilder<WorkflowWorker>(
                    intervalMinutes, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            WorkflowWorker.KEY_WORKFLOW_ID to workflowId,
                            KEY_TRIGGER_NODE_ID to triggerNodeId,
                            KEY_SCHEDULE_FINGERPRINT to scheduleFingerprint,
                        )
                    )
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag(workflowId)
                    .addTag(scheduleFingerprintTag(scheduleFingerprint))
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    getWorkName(workflowId),
                    ExistingPeriodicWorkPolicy.REPLACE,
                    workRequest
                ).result.get()
                AppLogger.d(TAG, "Scheduled cron workflow (periodic): $workflowId, trigger: $triggerNodeId, expression: $cronExpression")
            } else {
                // Fallback to one-time for complex cron patterns
                scheduleOneTimeWorkflowWithDelay(
                    workflowId, triggerNodeId, delay, scheduleFingerprint
                )
                AppLogger.d(TAG, "Scheduled cron workflow (one-time): $workflowId, trigger: $triggerNodeId, expression: $cronExpression")
            }
        } else {
            scheduleOneTimeWorkflowWithDelay(
                workflowId, triggerNodeId, delay, scheduleFingerprint
            )
            AppLogger.d(TAG, "Scheduled cron workflow (one-time): $workflowId, trigger: $triggerNodeId, expression: $cronExpression")
        }

        return true
    }

    /**
     * Helper to schedule one-time work with delay
     */
    private fun scheduleOneTimeWorkflowWithDelay(
        workflowId: String,
        triggerNodeId: String,
        delayMs: Long,
        scheduleFingerprint: String,
    ) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    WorkflowWorker.KEY_WORKFLOW_ID to workflowId,
                    KEY_TRIGGER_NODE_ID to triggerNodeId,
                    KEY_SCHEDULE_FINGERPRINT to scheduleFingerprint,
                )
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(workflowId)
            .addTag(scheduleFingerprintTag(scheduleFingerprint))
            .build()

        workManager.enqueueUniqueWork(
            getWorkName(workflowId),
            ExistingWorkPolicy.REPLACE,
            workRequest
        ).result.get()
    }

    /**
     * Cancel scheduled workflow
     */
    fun cancelWorkflow(workflowId: String) {
        workManager.cancelUniqueWork(getWorkName(workflowId)).result.get()
        AppLogger.d(TAG, "Cancelled workflow schedule: $workflowId")
    }

    /**
     * Uses the same supported schedule grammar as [scheduleWorkflow] without enqueuing work.
     * Pre-fingerprint compatibility must not execute a stale request when the current definition
     * could not itself produce a trusted replacement request.
     */
    internal fun canClaimPreFingerprintSchedule(workflow: Workflow, triggerNodeId: String): Boolean =
        runCatching {
            val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
                .firstOrNull { it.triggerType == "schedule" }
                ?: return@runCatching false
            if (triggerNode.id != triggerNodeId) return@runCatching false

            val config = triggerNode.triggerConfig
            if (!(config[CONFIG_ENABLED]?.toBoolean() ?: true)) return@runCatching false
            when (config[CONFIG_SCHEDULE_TYPE]) {
                SCHEDULE_TYPE_INTERVAL ->
                    config[CONFIG_INTERVAL_MS]?.toLongOrNull() != null &&
                        (config[CONFIG_REPEAT]?.toBoolean() ?: true)
                SCHEDULE_TYPE_SPECIFIC_TIME -> {
                    val target = config[CONFIG_SPECIFIC_TIME]?.let(::parseDateTime)
                        ?: return@runCatching false
                    // A one-time Worker normally starts only after its target becomes due. A future
                    // current target means this request belongs to an older schedule definition.
                    target <= System.currentTimeMillis()
                }
                SCHEDULE_TYPE_CRON ->
                    config[CONFIG_CRON_EXPRESSION]?.let(::calculateNextCronTime) != null
                else -> false
            }
        }.getOrDefault(false)

    internal fun shouldReplacePreFingerprintSchedule(
        workflow: Workflow,
        triggerNodeId: String,
    ): Boolean {
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return false
        if (triggerNode.id != triggerNodeId) return false
        return when (triggerNode.triggerConfig[CONFIG_SCHEDULE_TYPE]) {
            SCHEDULE_TYPE_INTERVAL -> true
            SCHEDULE_TYPE_CRON -> triggerNode.triggerConfig[CONFIG_REPEAT]?.toBoolean() ?: true
            else -> false
        }
    }

    /** Classifies definitions that can be recovered into a fingerprinted WorkManager request. */
    internal fun isMigratablePreFingerprintScheduleRequest(workflow: Workflow): Boolean {
        if (
            workflow.scheduleFingerprintGeneration != null &&
                workflow.scheduleFingerprintGeneration !=
                    PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION
        ) return false
        return isSchedulableWorkflowDefinition(workflow)
    }

    internal fun isSchedulableWorkflowDefinition(workflow: Workflow): Boolean {
        if (!workflow.enabled) return false
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return false
        val config = triggerNode.triggerConfig
        if (!(config[CONFIG_ENABLED]?.toBoolean() ?: true)) return false
        return when (config[CONFIG_SCHEDULE_TYPE]) {
            SCHEDULE_TYPE_INTERVAL ->
                config[CONFIG_INTERVAL_MS]?.toLongOrNull() != null &&
                    (config[CONFIG_REPEAT]?.toBoolean() ?: true)
            SCHEDULE_TYPE_SPECIFIC_TIME -> runCatching {
                config[CONFIG_SPECIFIC_TIME]?.let(::parseDateTime) != null
            }.getOrDefault(false)
            SCHEDULE_TYPE_CRON ->
                config[CONFIG_CRON_EXPRESSION]?.let(::calculateNextCronTime) != null
            else -> false
        }
    }

    internal fun shouldPreserveActivePreFingerprintScheduleRequest(workflow: Workflow): Boolean {
        if (workflow.scheduleFingerprintGeneration != null) return false
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return false
        return canClaimPreFingerprintSchedule(workflow, triggerNode.id)
    }

    internal fun isSpecificTimeSchedule(workflow: Workflow): Boolean =
        workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?.triggerConfig
            ?.get(CONFIG_SCHEDULE_TYPE) == SCHEDULE_TYPE_SPECIFIC_TIME

    internal fun isFutureSpecificTimeSchedule(
        workflow: Workflow,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return false
        if (triggerNode.triggerConfig[CONFIG_SCHEDULE_TYPE] != SCHEDULE_TYPE_SPECIFIC_TIME) {
            return false
        }
        return runCatching {
            triggerNode.triggerConfig[CONFIG_SPECIFIC_TIME]?.let(::parseDateTime)?.let { it > now }
                ?: false
        }.getOrDefault(false)
    }

    internal fun shouldPrepareFingerprintScheduleReconciliation(
        workflow: Workflow,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isSchedulableWorkflowDefinition(workflow)) return false
        return !isSpecificTimeSchedule(workflow) || isFutureSpecificTimeSchedule(workflow, now)
    }

    internal fun isRejectedPastSpecificTimeDefinition(
        workflow: Workflow,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        isSchedulableWorkflowDefinition(workflow) &&
            isSpecificTimeSchedule(workflow) &&
            !isFutureSpecificTimeSchedule(workflow, now)

    internal fun hasActiveWorkflowScheduleAndWait(workflowId: String): Boolean =
        workManager.getWorkInfosForUniqueWork(getWorkName(workflowId)).get().any { info ->
            isActiveWorkflowScheduleState(info.state)
        }

    internal fun claimGateDeferredWorkflowScheduleIdsAndWait(workflowId: String): Set<UUID> {
        val workInfos = workManager.getWorkInfosForUniqueWork(getWorkName(workflowId)).get()
        return workInfos.mapNotNull { info ->
            info.id.takeIf(gateRetryStore::claimForReconciliation)
        }.toSet()
    }

    internal fun consumeGateDeferredWorkflowSchedules(workRequestIds: Set<UUID>) {
        workRequestIds.forEach(gateRetryStore::consume)
    }

    internal fun hasActiveMatchingWorkflowScheduleAndWait(workflow: Workflow): Boolean {
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return false
        val expectedTag = scheduleFingerprintTag(scheduleFingerprint(workflow.id, triggerNode))
        return workManager.getWorkInfosForUniqueWork(getWorkName(workflow.id)).get().any { info ->
            isActiveMatchingWorkflowSchedule(
                info.state,
                info.tags,
                expectedTag,
            )
        }
    }

    internal fun hasActivePreFingerprintWorkflowScheduleAndWait(workflowId: String): Boolean =
        workManager.getWorkInfosForUniqueWork(getWorkName(workflowId)).get().any { info ->
            isActivePreFingerprintWorkflowSchedule(info.state, info.tags)
        }

    internal fun shouldRetireCompletedPreFingerprintOneTimeAndWait(
        workflow: Workflow,
        excludedWorkRequestIds: Set<UUID> = emptySet(),
    ): Boolean =
        shouldRetireCompletedPreFingerprintOneTime(
            workflow = workflow,
            workInfos = workManager.getWorkInfosForUniqueWork(getWorkName(workflow.id)).get()
                .map { info -> WorkflowScheduleWorkInfo(info.state, info.tags, info.id) },
            targetIsDue = !isFutureSpecificTimeSchedule(workflow),
            excludedWorkRequestIds = excludedWorkRequestIds,
        )

    /** Used by security-sensitive legacy cleanup before a trusted replacement is scheduled. */
    internal fun cancelWorkflowAndWait(workflowId: String) {
        workManager.cancelUniqueWork(getWorkName(workflowId)).result.get()
        AppLogger.d(TAG, "Synchronously cancelled workflow schedule: $workflowId")
    }

    /**
     * Check if workflow is scheduled
     */
    suspend fun isWorkflowScheduled(workflowId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val workInfos = workManager.getWorkInfosForUniqueWork(getWorkName(workflowId)).await()
            workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking workflow schedule status", e)
            false
        }
    }

    /**
     * Get work name for workflow
     */
    private fun getWorkName(workflowId: String): String {
        return "$WORK_NAME_PREFIX$workflowId"
    }

    /**
     * Parse datetime string (ISO 8601 or common formats)
     */
    private fun parseDateTime(dateTimeStr: String): Long {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )

        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                val date = sdf.parse(dateTimeStr)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // Try next format
            }
        }

        throw IllegalArgumentException("Unsupported datetime format: $dateTimeStr")
    }

    /**
     * Calculate next execution time for cron expression
     * 
     * Simplified cron parser supporting basic patterns:
     * - 0 0 * * * (daily at midnight)
     * - 0 2 * * * (every 2 hours)
     * - 15 * * * * (every 15 minutes)
     */
    private fun calculateNextCronTime(cronExpression: String): Long? {
        val parts = cronExpression.trim().split("\\s+".toRegex())
        if (parts.size < 5) {
            AppLogger.e(TAG, "Invalid cron expression: $cronExpression")
            return null
        }

        val minute = parts[0]
        val hour = parts[1]
        val dayOfMonth = parts[2]
        val month = parts[3]
        val dayOfWeek = parts[4]

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 1) // Start from next minute

        // Simple cron pattern matching
        return when {
            // Daily at specific time: "0 0 * * *"
            minute.matches("\\d+".toRegex()) && hour.matches("\\d+".toRegex()) && 
            dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
                calculateDailyTime(minute.toInt(), hour.toInt())
            }
            // Every N hours: "0 */N * * *"
            minute == "0" && hour.startsWith("*/") && 
            dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
                val hourInterval = hour.substring(2).toIntOrNull() ?: return null
                val nextTime = Calendar.getInstance()
                nextTime.add(Calendar.HOUR_OF_DAY, hourInterval)
                nextTime.set(Calendar.MINUTE, 0)
                nextTime.set(Calendar.SECOND, 0)
                nextTime.timeInMillis
            }
            // Every N minutes: "*/N * * * *"
            minute.startsWith("*/") && hour == "*" && 
            dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
                val minuteInterval = minute.substring(2).toIntOrNull() ?: return null
                val nextTime = Calendar.getInstance()
                nextTime.add(Calendar.MINUTE, minuteInterval)
                nextTime.set(Calendar.SECOND, 0)
                nextTime.timeInMillis
            }
            else -> {
                AppLogger.w(TAG, "Unsupported cron pattern: $cronExpression")
                null
            }
        }
    }

    /**
     * Calculate next daily execution time
     */
    private fun calculateDailyTime(minute: Int, hour: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // If time has passed today, schedule for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return calendar.timeInMillis
    }

    /**
     * Calculate interval in milliseconds for cron expression
     * Returns null for complex patterns
     */
    private fun calculateCronInterval(cronExpression: String): Long? {
        val parts = cronExpression.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null

        val minute = parts[0]
        val hour = parts[1]

        return when {
            // Daily
            minute.matches("\\d+".toRegex()) && hour.matches("\\d+".toRegex()) -> {
                24 * 60 * 60 * 1000L
            }
            // Every N hours
            minute == "0" && hour.startsWith("*/") -> {
                val hourInterval = hour.substring(2).toLongOrNull() ?: return null
                hourInterval * 60 * 60 * 1000
            }
            // Every N minutes
            minute.startsWith("*/") && hour == "*" -> {
                val minuteInterval = minute.substring(2).toLongOrNull() ?: return null
                minuteInterval * 60 * 1000
            }
            else -> null
        }
    }

    /**
     * Get next execution time for a workflow
     */
    fun getNextExecutionTime(workflow: Workflow): Long? {
        val triggerNode = workflow.nodes.filterIsInstance<TriggerNode>()
            .firstOrNull { it.triggerType == "schedule" }
            ?: return null

        val config = triggerNode.triggerConfig
        val scheduleType = config[CONFIG_SCHEDULE_TYPE] ?: return null

        return when (scheduleType) {
            SCHEDULE_TYPE_SPECIFIC_TIME -> {
                config[CONFIG_SPECIFIC_TIME]?.let { parseDateTime(it) }
            }
            SCHEDULE_TYPE_CRON -> {
                config[CONFIG_CRON_EXPRESSION]?.let { calculateNextCronTime(it) }
            }
            SCHEDULE_TYPE_INTERVAL -> {
                // For interval, next execution is interval from now
                val intervalMs = config[CONFIG_INTERVAL_MS]?.toLongOrNull() ?: return null
                System.currentTimeMillis() + intervalMs
            }
            else -> null
        }
    }
}

