package com.ai.assistance.operit.data.repository

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.workflow.NodeExecutionState
import com.ai.assistance.operit.core.workflow.WorkflowAuthTokenManager
import com.ai.assistance.operit.core.workflow.WorkflowExecutor
import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.core.workflow.WorkflowScheduler
import com.ai.assistance.operit.data.model.ExecutionStatus
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import com.ai.assistance.operit.data.model.WorkflowExecutionRecord
import com.ai.assistance.operit.data.preferences.LegacyStoragePreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitManagedPaths
import com.ai.assistance.operit.util.SourcedEntry
import com.ai.assistance.operit.util.StorageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class ExternalWorkflowTriggerMatch(
    val workflowId: String,
    val workflowName: String,
    val triggerNodeId: String,
    val triggerNodeName: String
)

internal enum class WorkflowExecutionOrigin {
    USER_INITIATED,
    AUTOMATIC,
    AUTHENTICATED_EXTERNAL,
}

internal enum class WorkflowExecutionStorageAction {
    USE_PRIVATE,
    PROMOTE_LEGACY,
    REJECT,
}

internal fun workflowExecutionStorageAction(
    privateDefinitionExists: Boolean,
    origin: WorkflowExecutionOrigin,
): WorkflowExecutionStorageAction = when {
    privateDefinitionExists -> WorkflowExecutionStorageAction.USE_PRIVATE
    origin == WorkflowExecutionOrigin.USER_INITIATED -> WorkflowExecutionStorageAction.PROMOTE_LEGACY
    else -> WorkflowExecutionStorageAction.REJECT
}

internal fun projectLegacyWorkflowForDisplay(workflow: Workflow): Workflow =
    if (workflow.enabled) workflow.copy(enabled = false) else workflow

internal data class WorkflowScheduleRebuildResult(
    val scheduledCount: Int,
    val cancellationFailures: Int,
)

internal fun rebuildPrivateWorkflowSchedules(
    workflows: List<Workflow>,
    rebuildPrivate: (Workflow) -> Boolean,
    onCancellationFailure: (String, Throwable) -> Unit = { _, _ -> },
): WorkflowScheduleRebuildResult {
    var scheduledCount = 0
    var cancellationFailures = 0
    workflows.forEach { workflow ->
        val scheduled = runCatching { rebuildPrivate(workflow) }
            .onFailure { error ->
                cancellationFailures++
                onCancellationFailure(workflow.id, error)
            }
            .getOrDefault(false)
        if (scheduled) scheduledCount++
    }
    return WorkflowScheduleRebuildResult(scheduledCount, cancellationFailures)
}

internal fun cancelLegacyWorkflowScheduleIds(
    workflowIds: List<String>,
    cancelAndWait: (String) -> Unit,
    onCancellationFailure: (String, Throwable) -> Unit = { _, _ -> },
): Int {
    var failures = 0
    workflowIds.forEach { id ->
        runCatching { cancelAndWait(id) }
            .onFailure { error ->
                failures++
                onCancellationFailure(id, error)
            }
    }
    return failures
}

internal fun isTrustedScheduleExecutionAuthorized(
    workflow: Workflow,
    triggerNodeId: String,
    scheduleFingerprint: String,
): Boolean = trustedCurrentScheduleFingerprint(workflow, triggerNodeId) == scheduleFingerprint

internal fun trustedCurrentScheduleFingerprint(
    workflow: Workflow,
    triggerNodeId: String,
): String? {
    if (!workflow.enabled) return null
    val node = workflow.nodes.filterIsInstance<TriggerNode>().firstOrNull { candidate ->
        candidate.id == triggerNodeId && candidate.triggerType == "schedule"
    } ?: return null
    if (!(node.triggerConfig[WorkflowScheduler.CONFIG_ENABLED]?.toBoolean() ?: true)) return null
    return WorkflowScheduler.scheduleFingerprint(workflow.id, node)
}

internal data class PreFingerprintScheduleClaim(
    val workflow: Workflow,
    val scheduleFingerprint: String,
    val installReplacement: Boolean,
    val executeWorkflow: Boolean,
)

internal class PreFingerprintScheduleReplacementPendingException(workflowId: String) :
    Exception("Trusted replacement schedule is still pending for workflow: $workflowId")

internal fun claimPreFingerprintSchedule(
    workflow: Workflow,
    triggerNodeId: String,
    isEligible: (Workflow, String) -> Boolean,
    shouldInstallReplacement: (Workflow, String) -> Boolean,
): PreFingerprintScheduleClaim? {
    val replacementPending =
        workflow.scheduleFingerprintGeneration ==
            WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION
    if (
        workflow.scheduleFingerprintGeneration != null &&
            workflow.scheduleFingerprintGeneration !=
                WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION &&
            !replacementPending
    ) return null
    val fingerprint = trustedCurrentScheduleFingerprint(workflow, triggerNodeId) ?: return null
    if (!isEligible(workflow, triggerNodeId)) return null
    val installReplacement = shouldInstallReplacement(workflow, triggerNodeId)
    if (replacementPending && !installReplacement) return null
    return PreFingerprintScheduleClaim(
        workflow = if (replacementPending) workflow else workflow.copy(
            scheduleFingerprintGeneration =
                WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION,
        ),
        scheduleFingerprint = fingerprint,
        installReplacement = installReplacement,
        executeWorkflow = !replacementPending,
    )
}

internal fun markPreFingerprintReplacementPending(
    workflow: Workflow,
    triggerNodeId: String,
    scheduleFingerprint: String,
): Workflow? {
    if (
        workflow.scheduleFingerprintGeneration !=
            WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION
    ) return null
    if (trustedCurrentScheduleFingerprint(workflow, triggerNodeId) != scheduleFingerprint) return null
    return workflow.copy(
        scheduleFingerprintGeneration =
            WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION,
    )
}

internal fun completePreFingerprintScheduleClaim(
    workflow: Workflow,
    triggerNodeId: String,
    scheduleFingerprint: String,
): Workflow? {
    if (
        workflow.scheduleFingerprintGeneration !=
            WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION
    ) return null
    if (trustedCurrentScheduleFingerprint(workflow, triggerNodeId) != scheduleFingerprint) return null
    return workflow.copy(
        scheduleFingerprintGeneration =
            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
    )
}

internal fun isClaimedPreFingerprintExecutionAuthorized(
    workflow: Workflow,
    triggerNodeId: String,
    scheduleFingerprint: String,
): Boolean =
    workflow.scheduleFingerprintGeneration ==
        WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION &&
        isTrustedScheduleExecutionAuthorized(workflow, triggerNodeId, scheduleFingerprint)

internal fun shouldDeferClaimedScheduleRebuild(
    workflow: Workflow,
    allowClaimedMigration: Boolean,
): Boolean =
    workflow.scheduleFingerprintGeneration ==
        WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION &&
        !allowClaimedMigration

internal data class WorkflowFileScanLimits(
    val maxFiles: Int = Int.MAX_VALUE,
    val maxEntriesVisited: Int = Int.MAX_VALUE,
    val maxTotalBytes: Long = Long.MAX_VALUE,
    val maxFileBytes: Long = Long.MAX_VALUE,
)

internal data class WorkflowFileScanResult(
    val files: List<File>,
    val truncated: Boolean,
    val skippedEntries: Int,
)

internal class WorkflowByteBudget(initialBytes: Long) {
    var remainingBytes: Long = initialBytes
        private set

    fun tryConsume(bytes: Long): Boolean {
        require(bytes >= 0L)
        val consumed = minOf(bytes, remainingBytes)
        remainingBytes -= consumed
        return consumed == bytes
    }
}

internal fun readWorkflowTextBoundedNoFollow(
    file: File,
    maxBytes: Long,
    errorMessage: String,
    budget: WorkflowByteBudget? = null,
): String {
    require(maxBytes >= 0L)
    require(budget?.remainingBytes != 0L) { errorMessage }
    return Files.newByteChannel(
        file.toPath(),
        setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ).use { channel ->
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8192)
        var total = 0L
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val withinAggregateBudget = budget?.tryConsume(read.toLong()) ?: true
            total += read
            require(withinAggregateBudget && total <= maxBytes) { errorMessage }
            output.write(buffer.array(), 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

internal fun validateUntrustedWorkflowJson(
    content: String,
    maxDepth: Int = 64,
    maxStructuralTokens: Int = 65_536,
) {
    var depth = 0
    var structuralTokens = 0
    var inString = false
    var escaped = false
    content.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEach
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                structuralTokens++
                require(depth <= maxDepth) { "Untrusted workflow JSON is too deeply nested" }
            }
            '}', ']' -> {
                depth--
                structuralTokens++
                require(depth >= 0) { "Untrusted workflow JSON is malformed" }
            }
            ',', ':' -> structuralTokens++
        }
        require(structuralTokens <= maxStructuralTokens) {
            "Untrusted workflow JSON has too many structural elements"
        }
    }
    require(!inString && depth == 0) { "Untrusted workflow JSON is malformed" }
}

internal fun selectExternalWorkflowTriggerMatches(
    workflows: List<Workflow>,
    matcher: (TriggerNode) -> Boolean
): List<ExternalWorkflowTriggerMatch> = workflows.asSequence()
    .filter { it.enabled }
    .flatMap { workflow ->
        workflow.nodes.asSequence()
            .filterIsInstance<TriggerNode>()
            .filter(matcher)
            .map { node ->
                ExternalWorkflowTriggerMatch(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    triggerNodeId = node.id,
                    triggerNodeName = node.name
                )
            }
    }
    .toList()

internal fun summarizeExternalTriggerResults(
    results: List<Result<String>>
): Result<Int> {
    if (results.isEmpty()) {
        return Result.failure(IllegalStateException("No enabled workflow matched this external trigger"))
    }
    val failureCount = results.count(Result<String>::isFailure)
    if (failureCount > 0) {
        return Result.failure(
            IllegalStateException("$failureCount of ${results.size} matched workflow triggers failed")
        )
    }
    return Result.success(results.size)
}

internal fun decodeWorkflowContentSafely(
    json: Json,
    content: String,
    workflowId: String
): Workflow = try {
    val element = json.parseToJsonElement(content)
    val workflowElement = JsonObject((element as JsonObject) + ("id" to JsonPrimitive(workflowId)))
    json.decodeFromJsonElement(Workflow.serializer(), workflowElement)
} catch (error: Exception) {
    // kotlinx.serialization parse messages may echo the surrounding JSON, including auth_token.
    // Do not retain the original exception as a cause or expose its message to logs/callers.
    throw IllegalArgumentException("Invalid workflow JSON (${error::class.java.simpleName})")
}

internal fun decodeWorkflowExecutionRecordSafely(
    json: Json,
    content: String,
    expectedWorkflowId: String,
): WorkflowExecutionRecord = try {
    requireWorkflowExecutionRecordOwnership(
        json.decodeFromString(WorkflowExecutionRecord.serializer(), content),
        expectedWorkflowId,
    )
} catch (error: Exception) {
    // Execution logs can contain tool results and exception text. Serialization failures may
    // echo the nearby JSON input, so never preserve the original message or cause.
    throw IllegalArgumentException(
        "Invalid workflow execution record (${error::class.java.simpleName})"
    )
}

internal fun writeWorkflowContentAtomically(file: File, content: String) {
    val parent = file.parentFile ?: throw IllegalStateException("Workflow file has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IllegalStateException("Failed to create workflow directory")
    }
    val atomicFile = AtomicFile(file)
    val output = atomicFile.startWrite()
    try {
        output.write(content.toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(output)
    } catch (error: Throwable) {
        atomicFile.failWrite(output)
        throw error
    }
}

internal fun readWorkflowContentAtomically(file: File): String =
    AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }

internal fun mutateWorkflowFileAtomically(
    file: File,
    workflowId: String,
    json: Json,
    lock: Any,
    reader: (File) -> String = ::readWorkflowContentAtomically,
    writer: (File, String) -> Unit = ::writeWorkflowContentAtomically,
    transform: (Workflow) -> Workflow
): Workflow? = synchronized(lock) {
    if (!file.isFile) return@synchronized null
    val latestWorkflow = decodeWorkflowContentSafely(json, reader(file), workflowId)
    val updatedWorkflow = transform(latestWorkflow)
    writer(file, json.encodeToString(updatedWorkflow))
    updatedWorkflow
}

internal fun mergeWorkflowDefinitionWithLatestRuntime(
    requestedWorkflow: Workflow,
    latestWorkflow: Workflow
): Workflow = requestedWorkflow.copy(
    lastExecutionStatus = latestWorkflow.lastExecutionStatus,
    lastExecutionTime = latestWorkflow.lastExecutionTime,
    totalExecutions = latestWorkflow.totalExecutions,
    successfulExecutions = latestWorkflow.successfulExecutions,
    failedExecutions = latestWorkflow.failedExecutions
)

internal fun markExplicitLegacyPromotionAsCurrent(workflow: Workflow): Workflow = workflow.copy(
    scheduleFingerprintGeneration = WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
)

internal fun updateWorkflowFileAtomically(
    file: File,
    workflowId: String,
    requestedWorkflow: Workflow,
    json: Json,
    lock: Any,
    reader: (File) -> String = ::readWorkflowContentAtomically,
    writer: (File, String) -> Unit = ::writeWorkflowContentAtomically,
    prepareRequested: (Workflow, Workflow) -> Workflow = ::mergeWorkflowDefinitionWithLatestRuntime
): Workflow = synchronized(lock) {
    val latestWorkflow = decodeWorkflowContentSafely(json, reader(file), workflowId)
    val workflowToWrite = prepareRequested(requestedWorkflow, latestWorkflow)
    writer(file, json.encodeToString(workflowToWrite))
    workflowToWrite
}

internal fun updateEffectiveWorkflowFileAtomically(
    internalFile: File,
    workflowId: String,
    fallbackWorkflow: Workflow?,
    json: Json,
    lock: Any,
    reader: (File) -> String = ::readWorkflowContentAtomically,
    writer: (File, String) -> Unit = ::writeWorkflowContentAtomically,
    transform: (latest: Workflow, promotingLegacy: Boolean) -> Workflow,
): Workflow = synchronized(lock) {
    val promotingLegacy = !internalFile.isFile
    val latest = if (promotingLegacy) {
        requireNotNull(fallbackWorkflow) { "Workflow not found for atomic promotion" }
    } else {
        decodeWorkflowContentSafely(json, reader(internalFile), workflowId)
    }
    val updated = transform(latest, promotingLegacy)
    writer(internalFile, json.encodeToString(updated))
    updated
}

internal fun applyWorkflowExecutionStatus(
    workflow: Workflow,
    status: ExecutionStatus,
    executionTime: Long
): Workflow = workflow.copy(
    lastExecutionStatus = status,
    lastExecutionTime = executionTime
)

internal fun applyWorkflowExecutionStatistics(
    workflow: Workflow,
    status: ExecutionStatus,
    executionTime: Long
): Workflow = workflow.copy(
    lastExecutionStatus = status,
    lastExecutionTime = executionTime,
    totalExecutions = workflow.totalExecutions + 1,
    successfulExecutions = workflow.successfulExecutions + if (status == ExecutionStatus.SUCCESS) 1 else 0,
    failedExecutions = workflow.failedExecutions + if (status == ExecutionStatus.FAILED) 1 else 0
)

/**
 * 工作流仓库
 * 负责工作流的持久化存储和管理
 *
 * 存储分层（Phase 2 迁移）：
 * - **应用内部目录** [OperitManagedPaths.internalWorkflows]（`filesDir/operit/workflows/definitions`）
 *   是主存储和唯一默认写入位置。所有新建、修改、执行状态/统计写入都落在这里。
 * - **旧版 Download 目录** [OperitManagedPaths.legacyWorkflows]（`Download/Operit/workflow`，单数）
 *   仅当 [LegacyStoragePreferences.isReadLegacyWorkflows] 为 true 时作为只读读取源。旧目录
 *   访问不创建目录；同名工作流以内部版本优先；旧工作流仅在明确的用户编辑、启用、
 *   手动执行或手动调度操作中通过写时复制进入内部目录，原文件不动。自动入口与 AI
 *   工具只读取内部定义。
 * - **运行日志** 移到 [OperitManagedPaths.internalWorkflowLogs]
 *   （`noBackupFilesDir/operit/workflows/execution_logs`），不纳入 raw snapshot 备份。
 */
class WorkflowRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }

    private val paths = OperitManagedPaths(context)
    private val legacyPrefs = LegacyStoragePreferences.getInstance(context)

    // Lazy initialization to avoid WorkManager initialization issues during app startup
    private val scheduler by lazy { WorkflowScheduler(context) }
    private val authTokenManager by lazy { WorkflowAuthTokenManager(context) }

    companion object {
        private const val TAG = "WorkflowRepository"
        private const val MAX_EXECUTION_LOG_FILES_PER_WORKFLOW = 30
        private const val MAX_LEGACY_WORKFLOW_FILE_BYTES = 1024 * 1024
        private const val MAX_LEGACY_WORKFLOW_FILES = 1000
        private const val MAX_LEGACY_WORKFLOW_DIRECTORY_ENTRIES = 4000
        private const val MAX_LEGACY_WORKFLOW_TOTAL_BYTES = 64L * 1024L * 1024L
        private const val MAX_LEGACY_EXECUTION_LOG_FILE_BYTES = 1024 * 1024
        private const val MAX_LEGACY_EXECUTION_LOG_FILES = 64
        private const val MAX_LEGACY_EXECUTION_LOG_DIRECTORY_ENTRIES = 256

        private val LEGACY_WORKFLOW_SCAN_LIMITS = WorkflowFileScanLimits(
            maxFiles = MAX_LEGACY_WORKFLOW_FILES,
            maxEntriesVisited = MAX_LEGACY_WORKFLOW_DIRECTORY_ENTRIES,
            maxTotalBytes = MAX_LEGACY_WORKFLOW_TOTAL_BYTES,
            maxFileBytes = MAX_LEGACY_WORKFLOW_FILE_BYTES.toLong(),
        )
        private val LEGACY_EXECUTION_LOG_SCAN_LIMITS = WorkflowFileScanLimits(
            maxFiles = MAX_LEGACY_EXECUTION_LOG_FILES,
            maxEntriesVisited = MAX_LEGACY_EXECUTION_LOG_DIRECTORY_ENTRIES,
            maxTotalBytes = MAX_LEGACY_EXECUTION_LOG_FILES.toLong() * MAX_LEGACY_EXECUTION_LOG_FILE_BYTES,
            maxFileBytes = MAX_LEGACY_EXECUTION_LOG_FILE_BYTES.toLong(),
        )

        // Per-workflow-id lock serializing promotion and every workflow definition mutation.
        private val promotionLocks = ConcurrentHashMap<String, Any>()

        private val externalTriggerCacheMutex = Mutex()
        private val externalTriggerCacheGeneration = AtomicLong(0L)

        @Volatile
        private var externalTriggerCachedWorkflows: List<Workflow>? = null

        private const val SPEECH_TRIGGER_CACHE_TTL_MS = 2000L
        private val speechTriggerLastFireAtMs = ConcurrentHashMap<String, Long>()

        @Volatile
        private var speechTriggerCachedWorkflows: List<Workflow>? = null

        @Volatile
        private var speechTriggerCachedAtMs: Long = 0L

        val workflowUpdateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val runningWorkflowLock = Any()
        private val runningWorkflowJobs = ConcurrentHashMap<String, MutableSet<Job>>()
        private val _runningWorkflowIds = MutableStateFlow<Set<String>>(emptySet())
        val runningWorkflowIds: StateFlow<Set<String>> = _runningWorkflowIds.asStateFlow()

        fun notifyWorkflowsChanged() {
            speechTriggerCachedWorkflows = null
            speechTriggerCachedAtMs = 0L
            invalidateExternalTriggerCache()
            workflowUpdateEvents.tryEmit(Unit)
        }

        private fun invalidateExternalTriggerCache() {
            externalTriggerCacheGeneration.incrementAndGet()
            externalTriggerCachedWorkflows = null
        }

        private fun publishRunningWorkflowIdsLocked() {
            val emptyWorkflowIds = mutableListOf<String>()
            runningWorkflowJobs.forEach { (workflowId, jobs) ->
                jobs.removeAll { !it.isActive }
                if (jobs.isEmpty()) {
                    emptyWorkflowIds += workflowId
                }
            }
            emptyWorkflowIds.forEach(runningWorkflowJobs::remove)
            _runningWorkflowIds.value = runningWorkflowJobs.keys.toSet()
        }

        private fun registerRunningWorkflow(workflowId: String, job: Job) {
            synchronized(runningWorkflowLock) {
                runningWorkflowJobs.getOrPut(workflowId) { mutableSetOf() }.add(job)
                publishRunningWorkflowIdsLocked()
            }
        }

        private fun unregisterRunningWorkflow(workflowId: String, job: Job) {
            synchronized(runningWorkflowLock) {
                runningWorkflowJobs[workflowId]?.remove(job)
                publishRunningWorkflowIdsLocked()
            }
        }

        private fun getRunningWorkflowJobs(
            workflowId: String,
            targetJob: Job? = null
        ): List<Job> {
            synchronized(runningWorkflowLock) {
                publishRunningWorkflowIdsLocked()
                val jobs = runningWorkflowJobs[workflowId].orEmpty().filter { it.isActive }
                return if (targetJob == null) {
                    jobs
                } else {
                    jobs.filter { it === targetJob }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Path resolution: internal primary, legacy read-only fallback.
    // ------------------------------------------------------------------

    /** Internal primary workflow definition file (always writable). */
    private fun getInternalWorkflowFile(workflowId: String): File =
        requireNotNull(
            resolveWorkflowStorageChild(
                root = paths.internalWorkflows,
                workflowId = workflowId,
                suffix = ".json",
                trustedAnchor = paths.internalRoot,
            )
        ) {
            "Invalid workflow id"
        }

    /**
     * Legacy `Download/Operit/workflow/<id>.json`. Non-creating: never calls mkdirs. Only
     * consulted when the legacy read switch is on.
     */
    private fun getLegacyWorkflowFile(workflowId: String): File =
        requireNotNull(
            resolveWorkflowStorageChild(
                root = paths.legacyWorkflows,
                workflowId = workflowId,
                suffix = ".json",
                trustedAnchor = requireNotNull(paths.legacyRoot.parentFile),
            )
        ) {
            "Invalid workflow id"
        }

    /**
     * Resolves the effective file for [id]: internal first, then legacy (gated by the read
     * switch and the hidden-list). Returns null if neither exists or if the legacy entry is
     * hidden. The returned [SourcedEntry.source] tells callers whether a write-on-copy is
     * needed before mutating.
     */
    private suspend fun findEffectiveWorkflowFile(id: String): SourcedEntry<File>? {
        val internal = getInternalWorkflowFile(id)
        if (internal.exists() && internal.isFile) {
            return SourcedEntry(internal, StorageSource.INTERNAL, internal)
        }
        if (legacyPrefs.isReadLegacyWorkflows() && id !in legacyPrefs.hiddenLegacyWorkflowIds()) {
            val legacy = getLegacyWorkflowFile(id)
            if (legacy.exists() && legacy.isFile) {
                return SourcedEntry(legacy, StorageSource.LEGACY_DOWNLOAD, legacy)
            }
        }
        return null
    }

    private fun readWorkflowFile(
        file: File,
        workflowId: String = file.nameWithoutExtension,
        isLegacy: Boolean = false,
        legacyReadBudget: WorkflowByteBudget? = null,
    ): Workflow {
        if (isLegacy) {
            val content = readLegacyWorkflowContentBounded(file, legacyReadBudget)
            validateUntrustedWorkflowJson(content)
            return decodeWorkflowContentSafely(json, content, workflowId)
        }

        return synchronized(promotionLocks.computeIfAbsent(workflowId) { Any() }) {
            val workflow = decodeWorkflowContentSafely(
                json,
                readWorkflowContentAtomically(file),
                workflowId,
            )
            val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                workflow = workflow,
                tokenValidator = authTokenManager::isAuthenticAuthToken,
                tokenFactory = authTokenManager::newAuthToken,
            )
            if (normalized != workflow) {
                // Definitions may be restored without the installation-bound no-backup secret.
                // Persist fresh credentials before returning them to UI or trigger caches.
                atomicWrite(file, json.encodeToString(normalized))
                invalidateExternalTriggerCache()
                AppLogger.w(TAG, "Rotated restored external-trigger credentials for workflow $workflowId")
            }
            normalized
        }
    }

    private fun readLegacyWorkflowContentBounded(
        file: File,
        budget: WorkflowByteBudget? = null,
    ): String {
        return readWorkflowTextBoundedNoFollow(
            file,
            MAX_LEGACY_WORKFLOW_FILE_BYTES.toLong(),
            "Legacy workflow file is too large",
            budget,
        )
    }

    private fun getExecutionLogDirectory(workflowId: String, createIfMissing: Boolean = true): File {
        // Execution logs live under noBackupFilesDir (excluded from raw snapshots).
        val dir = requireNotNull(
            resolveWorkflowStorageChild(
                root = paths.internalWorkflowLogs,
                workflowId = workflowId,
                trustedAnchor = context.noBackupFilesDir,
            )
        ) {
            "Invalid workflow id"
        }
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Pre-migration execution logs under `Download/Operit/workflow/_execution_logs/<id>`.
     * This accessor is strictly non-creating; the compatibility read is independent of the
     * workflow-definition switch because these are historical user-visible run records.
     */
    private fun getLegacyExecutionLogDirectory(workflowId: String): File =
        requireNotNull(
            resolveWorkflowStorageChild(
                File(paths.legacyWorkflows, "_execution_logs"),
                workflowId,
                trustedAnchor = requireNotNull(paths.legacyRoot.parentFile),
            )
        ) { "Invalid workflow id" }

    private fun saveExecutionRecord(record: WorkflowExecutionRecord) {
        try {
            val dir = getExecutionLogDirectory(record.workflowId)
            val safeRunId = record.runId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(dir, "${record.startedAt}_$safeRunId.json")
            writeWorkflowContentAtomically(file, json.encodeToString(record))

            val allFiles = dir.listFiles { f -> f.isFile && f.extension == "json" }?.toList().orEmpty()
            if (allFiles.size > MAX_EXECUTION_LOG_FILES_PER_WORKFLOW) {
                allFiles.sortedBy { it.lastModified() }
                    .take(allFiles.size - MAX_EXECUTION_LOG_FILES_PER_WORKFLOW)
                    .forEach { oldFile ->
                        runCatching { oldFile.delete() }
                    }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save workflow execution record: ${record.workflowId}", e)
        }
    }

    private fun hasScheduleTrigger(workflow: Workflow): Boolean {
        return workflow.nodes.filterIsInstance<TriggerNode>().any { it.triggerType == "schedule" }
    }

    /**
     * Atomically write [content] to [file] via [AtomicFile], so a crash mid-write leaves either
     * the previous or the new content, never a truncated hybrid. Replaces the prior `writeText`
     * overwrite that could corrupt a workflow JSON on crash.
     */
    private fun atomicWrite(file: File, content: String) {
        writeWorkflowContentAtomically(file, content)
    }

    /**
     * Ensures [id] lives in internal storage before a write. If only a legacy copy exists,
     * copies it into internal storage (preserving the filename stem as the id). Subsequent
     * writes then target the internal copy and never mutate the Download original. Returns the
     * internal file to write to.
     */
    private suspend fun ensureWorkflowInInternalStorage(id: String): File {
        val internal = getInternalWorkflowFile(id)
        if (internal.exists() && internal.isFile) return internal

        val effective = findEffectiveWorkflowFile(id)
            ?: throw IllegalStateException("Workflow not found for write-on-copy: $id")

        if (!effective.isLegacy) return internal

        // Serialize per-id promotion and normalize external-trigger tokens before the internal
        // copy becomes visible. A legacy Downloads file is not trusted to carry a token issued
        // for this workflow, even if an attacker copied a valid token from another workflow.
        val lock = promotionLocks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            if (internal.exists() && internal.isFile) return internal
            val legacyWorkflow = readWorkflowFile(effective.sourceFile, id, isLegacy = true)
            // The first private snapshot must already reject old no-fingerprint work. Publishing
            // null and fixing it in a later scheduling lock lets an already-due legacy Worker
            // claim and execute attacker-writable legacy state.
            val promotedWorkflow = markExplicitLegacyPromotionAsCurrent(
                WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                    workflow = legacyWorkflow,
                    replaceExistingTokens = true,
                    tokenFactory = authTokenManager::newAuthToken,
                )
            )
            atomicWrite(internal, json.encodeToString(promotedWorkflow))
            AppLogger.d(TAG, "Write-on-copy: legacy workflow $id normalized into internal storage")
        }
        return internal
    }

    /**
     * 获取所有工作流
     */
    suspend fun getAllWorkflows(): Result<List<Workflow>> = withContext(Dispatchers.IO) {
        try {
            val workflows = mutableListOf<Workflow>()
            val seenIds = mutableSetOf<String>()

            // 1. Scan internal primary store first (internal wins on id conflict).
            scanWorkflowDir(
                paths.internalWorkflows,
                seenIds,
                workflows,
                isLegacy = false,
                trustedAnchor = paths.internalRoot,
            )

            // 2. Scan legacy Download store only if the read switch is on.
            if (legacyPrefs.isReadLegacyWorkflows()) {
                val hidden = legacyPrefs.hiddenLegacyWorkflowIds()
                val legacyDir = paths.legacyWorkflows
                if (legacyDir.isDirectory) {
                    scanWorkflowDir(
                        legacyDir,
                        seenIds,
                        workflows,
                        skipIds = hidden,
                        isLegacy = true,
                        scanLimits = LEGACY_WORKFLOW_SCAN_LIMITS,
                        trustedAnchor = requireNotNull(paths.legacyRoot.parentFile),
                    )
                }
            }

            workflows.sortByDescending { it.updatedAt }
            Result.success(workflows)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get all workflows", e)
            Result.failure(e)
        }
    }

    private fun scanWorkflowDir(
        dir: File,
        seenIds: MutableSet<String>,
        out: MutableList<Workflow>,
        skipIds: Set<String> = emptySet(),
        isLegacy: Boolean,
        scanLimits: WorkflowFileScanLimits = WorkflowFileScanLimits(),
        trustedAnchor: File = dir,
    ) {
        val scan = scanCanonicalWorkflowJsonFiles(dir, trustedAnchor, scanLimits)
        if (isLegacy && (scan.truncated || scan.skippedEntries > 0)) {
            AppLogger.w(
                TAG,
                "Legacy workflow scan was limited; some public definitions were not shown " +
                    "(truncated=${scan.truncated}, skipped=${scan.skippedEntries})"
            )
        }
        val legacyReadBudget = if (isLegacy) {
            WorkflowByteBudget(scanLimits.maxTotalBytes)
        } else {
            null
        }
        for (file in scan.files) {
            val id = file.nameWithoutExtension
            if (id in seenIds) continue   // internal already wins; skip the legacy copy
            if (id in skipIds) continue   // hidden legacy; skip without aborting the scan
            try {
                val workflow = readWorkflowFile(file, id, isLegacy, legacyReadBudget)
                out += if (isLegacy) projectLegacyWorkflowForDisplay(workflow) else workflow
                seenIds += id
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse workflow file: ${file.name}", e)
            }
        }
    }

    /**
     * 根据ID获取工作流
     */
    suspend fun getWorkflowById(id: String): Result<Workflow?> = withContext(Dispatchers.IO) {
        try {
            val entry = findEffectiveWorkflowFile(id)
                ?: return@withContext Result.success(null)
            val workflow = readWorkflowFile(entry.sourceFile, id, entry.isLegacy)
            Result.success(if (entry.isLegacy) projectLegacyWorkflowForDisplay(workflow) else workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get workflow by id: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getLatestExecutionRecord(workflowId: String): Result<WorkflowExecutionRecord?> = withContext(Dispatchers.IO) {
        try {
            val internalDirectory = getExecutionLogDirectory(workflowId, createIfMissing = false)
            val legacyDirectory = getLegacyExecutionLogDirectory(workflowId)
            val internalFiles = canonicalWorkflowJsonFiles(internalDirectory)
            val legacyScan = if (internalFiles.isEmpty()) {
                scanCanonicalWorkflowJsonFiles(
                    directory = legacyDirectory,
                    trustedAnchor = requireNotNull(paths.legacyRoot.parentFile),
                    limits = LEGACY_EXECUTION_LOG_SCAN_LIMITS,
                )
            } else {
                WorkflowFileScanResult(emptyList(), truncated = false, skippedEntries = 0)
            }
            if (legacyScan.truncated || legacyScan.skippedEntries > 0) {
                AppLogger.w(
                    TAG,
                    "Legacy workflow log scan was limited for workflow $workflowId; " +
                        "some public records were ignored"
                )
            }
            val latest = latestWorkflowExecutionRecordFile(internalFiles, legacyScan.files)
                ?: return@withContext Result.success(null)
            val isLegacy = internalFiles.isEmpty()

            val content = if (isLegacy) {
                readWorkflowTextBoundedNoFollow(
                    latest,
                    MAX_LEGACY_EXECUTION_LOG_FILE_BYTES.toLong(),
                    "Legacy workflow execution record is too large",
                )
            } else {
                latest.readText()
            }
            if (isLegacy) validateUntrustedWorkflowJson(content)
            val record = decodeWorkflowExecutionRecordSafely(json, content, workflowId)
            Result.success(record)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get latest execution record for workflow: $workflowId", e)
            Result.failure(e)
        }
    }

    /**
     * 创建工作流
     */
    suspend fun createWorkflow(workflow: Workflow): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            require(workflow.id.isNotBlank()) { "Workflow id cannot be empty" }
            val normalizedWorkflow = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                workflow = workflow,
                tokenValidator = authTokenManager::isAuthenticAuthToken,
                tokenFactory = authTokenManager::newAuthToken
            ).copy(
                scheduleFingerprintGeneration =
                    WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            )
            val file = getInternalWorkflowFile(normalizedWorkflow.id)
            val content = json.encodeToString(normalizedWorkflow)
            // Serialize with promotion and every other definition mutation of the same id.
            invalidateExternalTriggerCache()
            synchronized(promotionLocks.computeIfAbsent(normalizedWorkflow.id) { Any() }) {
                atomicWrite(file, content)
            }

            AppLogger.d(TAG, "Workflow created: ${normalizedWorkflow.id}")

            reconcileInternalWorkflowSchedule(normalizedWorkflow.id)

            notifyWorkflowsChanged()

            Result.success(normalizedWorkflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create workflow", e)
            Result.failure(e)
        }
    }

    /**
     * 更新工作流
     */
    suspend fun updateWorkflow(workflow: Workflow): Result<Workflow> =
        updateWorkflowInternal(workflow, allowLegacyPromotion = true)

    internal suspend fun updateWorkflowFromPrivateStorage(workflow: Workflow): Result<Workflow> =
        updateWorkflowInternal(workflow, allowLegacyPromotion = false)

    private suspend fun updateWorkflowInternal(
        workflow: Workflow,
        allowLegacyPromotion: Boolean,
    ): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            require(workflow.id.isNotBlank()) { "Workflow id cannot be empty" }
            val requestedWorkflow = workflow.copy(
                updatedAt = System.currentTimeMillis(),
                scheduleFingerprintGeneration =
                    WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            )
            val internal = getInternalWorkflowFile(requestedWorkflow.id)
            val effective = if (allowLegacyPromotion) {
                findEffectiveWorkflowFile(requestedWorkflow.id)
            } else {
                internal.takeIf { file -> file.isFile }
                    ?.let { file -> SourcedEntry(file, StorageSource.INTERNAL, file) }
            }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_found)))
            val fallbackWorkflow = effective.takeIf { entry -> entry.isLegacy }?.let { entry ->
                readWorkflowFile(entry.sourceFile, requestedWorkflow.id, isLegacy = true)
            }
            val file = internal
            invalidateExternalTriggerCache()
            val updatedWorkflow = updateEffectiveWorkflowFileAtomically(
                internalFile = file,
                workflowId = requestedWorkflow.id,
                fallbackWorkflow = fallbackWorkflow,
                json = json,
                lock = promotionLocks.computeIfAbsent(requestedWorkflow.id) { Any() },
            ) { latest, promotingLegacy ->
                val normalized = if (promotingLegacy) {
                    WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                        workflow = requestedWorkflow,
                        replaceExistingTokens = true,
                        tokenFactory = authTokenManager::newAuthToken,
                    )
                } else {
                    WorkflowIntentSecurity.normalizeExternalTriggerTokensForUpdate(
                        requestedWorkflow = requestedWorkflow,
                        latestWorkflow = latest,
                        tokenValidator = authTokenManager::isAuthenticAuthToken,
                        tokenFactory = authTokenManager::newAuthToken,
                    )
                }
                    mergeWorkflowDefinitionWithLatestRuntime(normalized, latest)
            }

            AppLogger.d(TAG, "Workflow updated: ${updatedWorkflow.id}")

            reconcileInternalWorkflowSchedule(updatedWorkflow.id)

            notifyWorkflowsChanged()

            Result.success(updatedWorkflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update workflow", e)
            Result.failure(e)
        }
    }

    suspend fun setWorkflowEnabled(id: String, enabled: Boolean): Result<Workflow> =
        setWorkflowEnabledInternal(id, enabled, allowLegacyPromotion = true)

    internal suspend fun setWorkflowEnabledFromPrivateStorage(
        id: String,
        enabled: Boolean,
    ): Result<Workflow> = setWorkflowEnabledInternal(id, enabled, allowLegacyPromotion = false)

    private suspend fun setWorkflowEnabledInternal(
        id: String,
        enabled: Boolean,
        allowLegacyPromotion: Boolean,
    ): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            require(id.isNotBlank()) { "Workflow id cannot be empty" }
            val internal = getInternalWorkflowFile(id)
            val effective = if (allowLegacyPromotion) {
                findEffectiveWorkflowFile(id)
            } else {
                internal.takeIf { file -> file.isFile }
                    ?.let { file -> SourcedEntry(file, StorageSource.INTERNAL, file) }
            }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_found)))
            val fallbackWorkflow = effective.takeIf { entry -> entry.isLegacy }?.let { entry ->
                readWorkflowFile(entry.sourceFile, id, isLegacy = true)
            }
            val file = internal
            invalidateExternalTriggerCache()
            val updatedWorkflow = updateEffectiveWorkflowFileAtomically(
                internalFile = file,
                workflowId = id,
                fallbackWorkflow = fallbackWorkflow,
                json = json,
                lock = promotionLocks.computeIfAbsent(id) { Any() }
            ) { latest, promotingLegacy ->
                WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                    workflow = latest.copy(
                        enabled = enabled,
                        updatedAt = System.currentTimeMillis(),
                        scheduleFingerprintGeneration =
                            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                    ),
                    replaceExistingTokens = promotingLegacy,
                    tokenValidator = authTokenManager::isAuthenticAuthToken,
                    tokenFactory = authTokenManager::newAuthToken
                )
            }

            AppLogger.d(TAG, "Workflow enabled state updated: ${updatedWorkflow.id} -> $enabled")

            reconcileInternalWorkflowSchedule(updatedWorkflow.id)

            notifyWorkflowsChanged()

            Result.success(updatedWorkflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update workflow enabled state", e)
            Result.failure(e)
        }
    }

    /**
     * 删除工作流
     *
     * If a legacy Download copy exists for the same id, deleting the internal copy alone would
     * cause the legacy one to reappear on the next scan. The id is therefore added to the
     * hidden-list so legacy scans skip it. The Download original file is never deleted.
     */
    suspend fun deleteWorkflow(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val internal = getInternalWorkflowFile(id)
            val legacy = getLegacyWorkflowFile(id)
            // Probe the legacy file existence directly, NOT gated by the read switch. If the
            // switch is currently off but a Download copy exists, a future re-enable would
            // resurrect it after the internal copy is deleted — so hide it now regardless.
            val legacyExisted = legacy.exists() && legacy.isFile

            invalidateExternalTriggerCache()
            val (internalExisted, internalRemoved) = synchronized(
                promotionLocks.computeIfAbsent(id) { Any() }
            ) {
                val existed = internal.exists() && internal.isFile
                val removed = !existed || AtomicFile(internal).run {
                    delete()
                    !internal.exists()
                }
                if (removed) scheduler.cancelWorkflowAndWait(id)
                existed to removed
            }

            // If a legacy copy still exists, hide it so it does not reappear on the next scan.
            if (legacyExisted) {
                legacyPrefs.hideLegacyWorkflowId(id)
            }

            val deleted =
                workflowDeletionSucceeded(
                    internalExisted = internalExisted,
                    internalRemoved = internalRemoved,
                    legacyExisted = legacyExisted
                )

            if (deleted) {
                runCatching {
                    val logDir = getExecutionLogDirectory(id, createIfMissing = false)
                    if (logDir.exists()) {
                        logDir.deleteRecursively()
                    }
                }
            }

            AppLogger.d(TAG, "Workflow deleted: $id, success: $deleted")
            if (deleted) {
                notifyWorkflowsChanged()
            }
            Result.success(deleted)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete workflow", e)
            Result.failure(e)
        }
    }

    /** Restores all legacy Download workflows previously hidden via [deleteWorkflow]. */
    suspend fun restoreHiddenLegacyWorkflows(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = legacyPrefs.hiddenLegacyWorkflowIds().size
            legacyPrefs.clearHiddenLegacyWorkflowIds()
            if (count > 0) {
                notifyWorkflowsChanged()
                // Restored public definitions remain display-only until an explicit import action.
            }
            AppLogger.d(TAG, "Restored $count hidden legacy workflows")
            Result.success(count)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restore hidden legacy workflows", e)
            Result.failure(e)
        }
    }

    /**
     * 触发工作流执行
     * @param id 工作流ID
     * @param triggerNodeId 指定要触发的节点ID，如果为null则触发所有触发节点
     */
    suspend fun triggerWorkflow(
        id: String,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap()
    ): Result<String> = triggerWorkflowInternal(
        id = id,
        triggerNodeId = triggerNodeId,
        triggerExtras = triggerExtras,
        executionOrigin = WorkflowExecutionOrigin.USER_INITIATED,
    ) { nodeId, state ->
        AppLogger.d(TAG, "Node $nodeId state: $state")
    }

    /**
     * 触发工作流执行（带状态回调）
     * @param id 工作流ID
     * @param triggerNodeId 指定要触发的节点ID，如果为null则触发所有触发节点
     * @param onNodeStateChange 节点状态变化回调
     */
    suspend fun triggerWorkflowWithCallback(
        id: String,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap(),
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Result<String> = triggerWorkflowInternal(
        id = id,
        triggerNodeId = triggerNodeId,
        triggerExtras = triggerExtras,
        executionOrigin = WorkflowExecutionOrigin.USER_INITIATED,
        onNodeStateChange = onNodeStateChange
    )

    /** WorkManager and other unattended entry points must never execute a public legacy file. */
    internal suspend fun triggerScheduledWorkflow(
        id: String,
        triggerNodeId: String,
        scheduleFingerprint: String,
    ): Result<String> = triggerWorkflowInternal(
        id = id,
        triggerNodeId = triggerNodeId,
        executionOrigin = WorkflowExecutionOrigin.AUTOMATIC,
        authorizationCheck = { latest ->
            isTrustedScheduleExecutionAuthorized(latest, triggerNodeId, scheduleFingerprint)
        },
    ) { nodeId, state ->
        AppLogger.d(TAG, "Node $nodeId state: $state")
    }

    /** Compatibility for a private WorkManager request created before fingerprints existed. */
    internal suspend fun triggerPreFingerprintScheduledWorkflow(
        id: String,
        triggerNodeId: String,
    ): Result<String> {
        val claim = withContext(Dispatchers.IO) {
            val file = getInternalWorkflowFile(id)
            synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
                if (!file.isFile) return@synchronized null
                val latest = decodeWorkflowContentSafely(
                    json,
                    readWorkflowContentAtomically(file),
                    id,
                )
                val claim = claimPreFingerprintSchedule(
                    workflow = latest,
                    triggerNodeId = triggerNodeId,
                    isEligible = scheduler::canClaimPreFingerprintSchedule,
                    shouldInstallReplacement = scheduler::shouldReplacePreFingerprintSchedule,
                ) ?: return@synchronized null
                writeWorkflowContentAtomically(file, json.encodeToString(claim.workflow))
                claim
            }
        } ?: return Result.failure(Exception("Pre-fingerprint schedule request is no longer eligible"))

        val result = if (claim.executeWorkflow) {
            triggerWorkflowInternal(
                id = id,
                triggerNodeId = triggerNodeId,
                executionOrigin = WorkflowExecutionOrigin.AUTOMATIC,
                authorizationCheck = { latest ->
                    isClaimedPreFingerprintExecutionAuthorized(
                        latest,
                        triggerNodeId,
                        claim.scheduleFingerprint,
                    )
                },
            ) { nodeId, state ->
                AppLogger.d(TAG, "Node $nodeId state: $state")
            }
        } else {
            Result.success("Pre-fingerprint workflow execution already completed")
        }
        if (claim.installReplacement) {
            if (claim.executeWorkflow) {
                val markedPending = withContext(Dispatchers.IO) {
                    val file = getInternalWorkflowFile(id)
                    synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
                        if (!file.isFile) return@synchronized false
                        val latest = decodeWorkflowContentSafely(
                            json,
                            readWorkflowContentAtomically(file),
                            id,
                        )
                        val pending = markPreFingerprintReplacementPending(
                            latest,
                            triggerNodeId,
                            claim.scheduleFingerprint,
                        ) ?: return@synchronized false
                        writeWorkflowContentAtomically(file, json.encodeToString(pending))
                        true
                    }
                }
                if (!markedPending) {
                    return Result.failure(
                        Exception("Pre-fingerprint schedule changed before replacement: $id")
                    )
                }
            }
            // The completed execution is durably distinguished from replacement work. If the
            // one-time fallback enqueue fails, WorkManager retries only this replacement step;
            // startup/boot may also finish PENDING without executing the workflow again.
            if (!replaceClaimedPreFingerprintSchedule(id)) {
                AppLogger.e(TAG, "Failed to replace pre-fingerprint schedule request: $id")
                return Result.failure(PreFingerprintScheduleReplacementPendingException(id))
            }
        } else {
            withContext(Dispatchers.IO) {
                val file = getInternalWorkflowFile(id)
                synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
                    if (file.isFile) {
                        val latest = decodeWorkflowContentSafely(
                            json,
                            readWorkflowContentAtomically(file),
                            id,
                        )
                        completePreFingerprintScheduleClaim(
                            latest,
                            triggerNodeId,
                            claim.scheduleFingerprint,
                        )?.let { completed ->
                            writeWorkflowContentAtomically(file, json.encodeToString(completed))
                        }
                    }
                }
            }
        }
        return result
    }

    /** AI tools are not an explicit user import action and may execute private definitions only. */
    internal suspend fun triggerWorkflowFromPrivateStorage(
        id: String,
        triggerNodeId: String? = null,
    ): Result<String> = triggerWorkflowInternal(
        id = id,
        triggerNodeId = triggerNodeId,
        executionOrigin = WorkflowExecutionOrigin.AUTOMATIC,
    ) { nodeId, state ->
        AppLogger.d(TAG, "Node $nodeId state: $state")
    }

    suspend fun cancelWorkflow(
        id: String,
        targetJob: Job? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jobs = getRunningWorkflowJobs(id, targetJob)
            if (jobs.isEmpty()) {
                return@withContext Result.success(false)
            }

            AppLogger.d(TAG, "Cancelling workflow execution: $id, jobs=${jobs.size}")
            jobs.forEach { job ->
                job.cancel(CancellationException(context.getString(R.string.workflow_execution_cancelled)))
            }
            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cancel workflow: $id", e)
            Result.failure(e)
        }
    }

    private suspend fun triggerWorkflowInternal(
        id: String,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap(),
        executionOrigin: WorkflowExecutionOrigin,
        authorizationCheck: ((Workflow) -> Boolean)? = null,
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val internal = getInternalWorkflowFile(id)
        val wasLegacyOnly = !internal.isFile
        when (workflowExecutionStorageAction(internal.isFile, executionOrigin)) {
            WorkflowExecutionStorageAction.USE_PRIVATE -> Unit
            WorkflowExecutionStorageAction.PROMOTE_LEGACY -> {
                runCatching { ensureWorkflowInInternalStorage(id) }
                    .getOrElse { return@withContext Result.failure(it) }
            }
            WorkflowExecutionStorageAction.REJECT -> {
                return@withContext Result.failure(
                    Exception(context.getString(R.string.workflow_not_exist, id))
                )
            }
        }
        val workflow = synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
            runCatching {
                if (!internal.isFile) null else decodeWorkflowContentSafely(
                    json,
                    readWorkflowContentAtomically(internal),
                    id
                )
            }.getOrNull()?.takeIf { latest -> authorizationCheck?.invoke(latest) != false }
        }

        if (workflow == null) {
            val message = if (authorizationCheck == null) {
                context.getString(R.string.workflow_not_exist, id)
            } else {
                "External workflow trigger authorization is no longer valid"
            }
            return@withContext Result.failure(Exception(message))
        }

        if (wasLegacyOnly && executionOrigin == WorkflowExecutionOrigin.USER_INITIATED) {
            // A deliberate manual run is also an explicit import action. Restore the workflow's
            // existing schedule only after the normalized private copy is durable. Route through
            // the repository scheduler so the imported definition also leaves legacy fingerprint
            // generation and invalidates any pre-upgrade request.
            if (workflow.enabled && hasScheduleTrigger(workflow)) {
                scheduleInternalWorkflow(workflow.id)
            }
            notifyWorkflowsChanged()
        }

        if (!workflow.enabled) {
            return@withContext Result.failure(Exception(context.getString(R.string.workflow_disabled_message, workflow.name)))
        }

        if (getRunningWorkflowJobs(id).isNotEmpty()) {
            return@withContext Result.failure(Exception(context.getString(R.string.workflow_already_running, workflow.name)))
        }

        val workflowJob = currentCoroutineContext().job
        registerRunningWorkflow(id, workflowJob)

        try {
            AppLogger.d(TAG, "Triggering workflow: ${workflow.name} (${workflow.id})")
            if (triggerNodeId != null) {
                AppLogger.d(TAG, "With specific trigger node: $triggerNodeId")
            }

            updateExecutionStatus(id, ExecutionStatus.RUNNING, System.currentTimeMillis())

            val executor = WorkflowExecutor(context)
            val result = executor.executeWorkflow(workflow, triggerNodeId, triggerExtras, onNodeStateChange)
            result.executionRecord?.let { saveExecutionRecord(it) }

            val executionStatus = if (result.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
            updateExecutionStatistics(id, executionStatus, result.executionTime)

            if (result.success) {
                Result.success(context.getString(R.string.workflow_execute_success, workflow.name))
            } else {
                Result.failure(Exception(result.message))
            }
        } catch (e: CancellationException) {
            AppLogger.d(TAG, "Workflow execution cancelled: $id")
            withContext(NonCancellable) {
                updateExecutionStatus(id, ExecutionStatus.FAILED, System.currentTimeMillis())
            }
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger workflow", e)
            updateExecutionStatus(id, ExecutionStatus.FAILED, System.currentTimeMillis())
            Result.failure(e)
        } finally {
            unregisterRunningWorkflow(id, workflowJob)
        }
    }

    /**
     * 更新工作流执行状态（仅状态和时间）。执行开始前定义已经位于内部存储；若用户在
     * 执行期间删除了它，状态写回必须 no-op，绝不能从同 ID 公共 legacy 文件复活定义。
     */
    private suspend fun updateExecutionStatus(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val file = getInternalWorkflowFile(id)
            invalidateExternalTriggerCache()
            val updatedWorkflow = mutateWorkflowFileAtomically(
                file = file,
                workflowId = id,
                json = json,
                lock = promotionLocks.computeIfAbsent(id) { Any() }
            ) { latest ->
                val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                    workflow = latest,
                    tokenValidator = authTokenManager::isAuthenticAuthToken,
                    tokenFactory = authTokenManager::newAuthToken
                )
                applyWorkflowExecutionStatus(normalized, status, executionTime)
            } ?: return@withContext

            AppLogger.d(TAG, "Workflow execution status updated: $id -> $status")
            notifyWorkflowsChanged()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update execution status", e)
        }
    }

    /** 执行统计仅 patch 仍存在的内部定义；删除竞态下不得从公共 legacy 存储导入。 */
    private suspend fun updateExecutionStatistics(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val file = getInternalWorkflowFile(id)
            invalidateExternalTriggerCache()
            val updatedWorkflow = mutateWorkflowFileAtomically(
                file = file,
                workflowId = id,
                json = json,
                lock = promotionLocks.computeIfAbsent(id) { Any() }
            ) { latest ->
                val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
                    workflow = latest,
                    tokenValidator = authTokenManager::isAuthenticAuthToken,
                    tokenFactory = authTokenManager::newAuthToken
                )
                applyWorkflowExecutionStatistics(normalized, status, executionTime)
            } ?: return@withContext

            AppLogger.d(TAG, "Workflow execution statistics updated: $id (total: ${updatedWorkflow.totalExecutions}, success: ${updatedWorkflow.successfulExecutions})")
            notifyWorkflowsChanged()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update execution statistics", e)
        }
    }

    /**
     * Schedule a workflow
     */
    fun scheduleWorkflow(id: String): Boolean {
        return scheduleWorkflowInternal(id, allowLegacyPromotion = true)
    }

    /** Startup/boot paths must never turn a public legacy definition into executable work. */
    internal fun scheduleInternalWorkflow(id: String): Boolean {
        return scheduleWorkflowInternal(
            id,
            allowLegacyPromotion = false,
            allowClaimedMigration = false,
        )
    }

    private fun replaceClaimedPreFingerprintSchedule(id: String): Boolean =
        scheduleWorkflowInternal(
            id,
            allowLegacyPromotion = false,
            allowClaimedMigration = true,
        )

    /** Caller holds this workflow's promotion lock for the entire WorkManager operation. */
    private fun scheduleWorkflowLocked(
        internal: File,
        workflow: Workflow,
        allowClaimedMigration: Boolean,
    ): Boolean {
        if (shouldDeferClaimedScheduleRebuild(workflow, allowClaimedMigration)) {
            AppLogger.d(TAG, "Schedule migration already owned by a running Worker: ${workflow.id}")
            return true
        }
        if (!workflow.enabled) {
            AppLogger.d(TAG, "Workflow is disabled, not scheduling: ${workflow.id}")
            return false
        }
        if (!hasScheduleTrigger(workflow)) return false

        val enqueued = scheduler.scheduleWorkflow(workflow)
        if (
            enqueued &&
                workflow.scheduleFingerprintGeneration !=
                    WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION
        ) {
            writeWorkflowContentAtomically(
                internal,
                json.encodeToString(
                    workflow.copy(
                        scheduleFingerprintGeneration =
                            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                    )
                ),
            )
        }
        return enqueued
    }

    private fun reconcileInternalWorkflowSchedule(id: String): Boolean = try {
        val internal = getInternalWorkflowFile(id)
        synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
            if (!internal.isFile) {
                scheduler.cancelWorkflowAndWait(id)
                return@synchronized true
            }
            val latest = decodeWorkflowContentSafely(
                json,
                readWorkflowContentAtomically(internal),
                id,
            )
            if (!latest.enabled || !hasScheduleTrigger(latest)) {
                scheduler.cancelWorkflowAndWait(id)
                true
            } else {
                scheduleWorkflowLocked(
                    internal,
                    latest,
                    allowClaimedMigration = false,
                )
            }
        }
    } catch (error: Exception) {
        AppLogger.e(TAG, "Failed to reconcile workflow schedule: $id", error)
        false
    }

    private fun scheduleWorkflowInternal(
        id: String,
        allowLegacyPromotion: Boolean,
        allowClaimedMigration: Boolean = false,
    ): Boolean {
        return try {
            val internal = getInternalWorkflowFile(id)
            val wasLegacyOnly = !internal.isFile
            kotlinx.coroutines.runBlocking {
                if (!internal.isFile && allowLegacyPromotion) {
                    runCatching { ensureWorkflowInInternalStorage(id) }.getOrNull()
                }
            }
            val scheduled = synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
                if (!internal.isFile) {
                    AppLogger.w(TAG, "Workflow not found for scheduling: $id")
                    return@synchronized false
                }
                val workflow = decodeWorkflowContentSafely(
                    json,
                    readWorkflowContentAtomically(internal),
                    id,
                )
                // Keep definition read, WorkManager REPLACE, and generation commit in the same
                // per-id order. A concurrent update can only run before this block (so we schedule
                // its latest definition) or after it (and its own reconciliation wins last).
                scheduleWorkflowLocked(internal, workflow, allowClaimedMigration)
            }
            if (wasLegacyOnly && internal.isFile) notifyWorkflowsChanged()
            scheduled
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule workflow: $id", e)
            false
        }
    }

    /**
     * Unschedule a workflow
     */
    fun unscheduleWorkflow(id: String) {
        try {
            scheduler.cancelWorkflow(id)
            AppLogger.d(TAG, "Workflow unscheduled: $id")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unschedule workflow: $id", e)
        }
    }

    /**
     * Reschedule a workflow (cancel + schedule)
     */
    fun rescheduleWorkflow(id: String): Boolean {
        unscheduleWorkflow(id)
        return scheduleWorkflow(id)
    }

    /**
     * Check if workflow is scheduled
     */
    suspend fun isWorkflowScheduled(id: String): Boolean {
        return try {
            scheduler.isWorkflowScheduled(id)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check schedule status: $id", e)
            false
        }
    }

    /**
     * Get next execution time for a workflow
     */
    suspend fun getNextExecutionTime(id: String): Long? = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext null
            scheduler.getNextExecutionTime(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get next execution time: $id", e)
            null
        }
    }

    /** Returns only private definitions suitable for unattended execution and scheduling. */
    internal suspend fun getAllInternalWorkflows(): Result<List<Workflow>> = withContext(Dispatchers.IO) {
        try {
            Result.success(loadInternalWorkflowsForAutomaticTriggers().sortedByDescending { it.updatedAt })
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get private workflows", e)
            Result.failure(e)
        }
    }

    private fun rebuildOneInternalWorkflowSchedule(id: String): Boolean {
        val internal = getInternalWorkflowFile(id)
        return synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
            if (!internal.isFile) {
                scheduler.cancelWorkflowAndWait(id)
                return@synchronized false
            }
            val latest = decodeWorkflowContentSafely(
                json,
                readWorkflowContentAtomically(internal),
                id,
            )
            // A running/retrying legacy Worker owns this migration. Cancelling its unique request
            // here would leave CLAIMED durable with neither an owner nor a trusted replacement.
            if (shouldDeferClaimedScheduleRebuild(latest, allowClaimedMigration = false)) {
                return@synchronized false
            }
            scheduler.cancelWorkflowAndWait(id)
            if (!latest.enabled || !hasScheduleTrigger(latest)) {
                false
            } else {
                scheduleWorkflowLocked(
                    internal,
                    latest,
                    allowClaimedMigration = false,
                )
            }
        }
    }

    /**
     * Rebuild every trusted private schedule from a clean WorkManager slot. This does not depend
     * on enumerating the attacker-writable legacy directory, so an old same-id request cannot
     * survive by hiding beyond the legacy scan cap.
     */
    internal suspend fun rebuildInternalWorkflowSchedules(): Result<WorkflowScheduleRebuildResult> =
        withContext(Dispatchers.IO) {
            try {
                val workflows = loadInternalWorkflowsForAutomaticTriggers()
                Result.success(
                    rebuildPrivateWorkflowSchedules(
                        workflows = workflows,
                        rebuildPrivate = { workflow ->
                            rebuildOneInternalWorkflowSchedule(workflow.id)
                        },
                        onCancellationFailure = { id, error ->
                            AppLogger.e(TAG, "Failed to clear existing private schedule: $id", error)
                        },
                    )
                )
            } catch (error: Exception) {
                AppLogger.e(TAG, "Failed to rebuild private workflow schedules", error)
                Result.failure(error)
            }
        }

    private fun cancelLegacyScheduleUnlessClaimed(id: String) {
        val internal = getInternalWorkflowFile(id)
        synchronized(promotionLocks.computeIfAbsent(id) { Any() }) {
            val latest = internal.takeIf { it.isFile }?.let { file ->
                decodeWorkflowContentSafely(
                    json,
                    readWorkflowContentAtomically(file),
                    id,
                )
            }
            if (
                latest != null &&
                    shouldDeferClaimedScheduleRebuild(latest, allowClaimedMigration = false)
            ) return@synchronized
            scheduler.cancelWorkflowAndWait(id)
        }
    }

    private fun loadInternalWorkflowsForAutomaticTriggers(): List<Workflow> =
        canonicalWorkflowJsonFiles(paths.internalWorkflows, paths.internalRoot).mapNotNull { file ->
            runCatching { readWorkflowFile(file, file.nameWithoutExtension, isLegacy = false) }
                .onFailure {
                    AppLogger.e(TAG, "Failed to parse internal workflow file: ${file.name}", it)
                }
                .getOrNull()
        }

    private suspend fun findExternalTriggerMatches(
        matcher: (TriggerNode) -> Boolean
    ): List<ExternalWorkflowTriggerMatch>? = externalTriggerCacheMutex.withLock {
        var resolvedMatches: List<ExternalWorkflowTriggerMatch>? = null
        var resolved = false
        while (!resolved) {
            val generationBeforeRead = externalTriggerCacheGeneration.get()
            val cached = externalTriggerCachedWorkflows
            if (cached != null) {
                val matches = selectExternalWorkflowTriggerMatches(cached, matcher)
                if (externalTriggerCacheGeneration.get() == generationBeforeRead) {
                    resolvedMatches = matches
                    resolved = true
                }
                continue
            }

            val loaded = runCatching { loadInternalWorkflowsForAutomaticTriggers() }.getOrNull()
                ?: return@withLock null
            if (externalTriggerCacheGeneration.get() != generationBeforeRead) {
                continue
            }
            externalTriggerCachedWorkflows = loaded
            resolvedMatches = selectExternalWorkflowTriggerMatches(loaded, matcher)
            resolved = true
        }
        resolvedMatches
    }

    /**
     * Clears WorkManager jobs that may have been created from public definitions by older builds.
     * If a private definition now owns the same id, its trusted schedule is rebuilt afterwards.
     */
    internal suspend fun resetSchedulesForLegacyWorkflowIds() = withContext(Dispatchers.IO) {
        val legacyDir = paths.legacyWorkflows
        if (!legacyDir.isDirectory) return@withContext
        val files = canonicalWorkflowJsonFiles(
            legacyDir,
            requireNotNull(paths.legacyRoot.parentFile),
            LEGACY_WORKFLOW_SCAN_LIMITS,
        )
        cancelLegacyWorkflowScheduleIds(
            workflowIds = files.map { file -> file.nameWithoutExtension },
            cancelAndWait = ::cancelLegacyScheduleUnlessClaimed,
            onCancellationFailure = { id, error ->
                AppLogger.e(TAG, "Failed to synchronously clear legacy workflow schedule: $id", error)
            },
        )
    }

    /**
     * Called when the user toggles the legacy-workflow read switch. Public definitions are
     * display-only; clear any stale WorkManager requests created by older versions.
     *
     * @param nowEnabled true if the switch was just turned on; false if just turned off.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun onLegacyReadSwitchChanged(nowEnabled: Boolean) = withContext(Dispatchers.IO) {
        resetSchedulesForLegacyWorkflowIds()
        rebuildInternalWorkflowSchedules().getOrThrow()
        notifyWorkflowsChanged()
    }


    /** Finds authenticated Tasker triggers in the private workflow store only. */
    suspend fun triggerWorkflowsByTaskerEvent(
        command: String?,
        authToken: String?
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (command.isNullOrBlank() || !authTokenManager.isAuthenticAuthToken(authToken)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid external workflow credentials"))
        }

        AppLogger.d(TAG, "Checking for Tasker-triggered workflows with command: $command")
        val matches = findExternalTriggerMatches { node ->
            node.triggerType == "tasker" &&
                WorkflowIntentSecurity.matchesTasker(node, command, authToken)
        } ?: return@withContext Result.failure(IllegalStateException("Unable to read private workflows"))

        val results = coroutineScope {
            matches.map { match ->
                async {
                    triggerWorkflowInternal(
                        id = match.workflowId,
                        triggerNodeId = match.triggerNodeId,
                        executionOrigin = WorkflowExecutionOrigin.AUTHENTICATED_EXTERNAL,
                        authorizationCheck = { latest ->
                            latest.enabled && latest.nodes.filterIsInstance<TriggerNode>().any { node ->
                                node.id == match.triggerNodeId &&
                                    WorkflowIntentSecurity.matchesTasker(node, command, authToken)
                            }
                        }
                    ) { nodeId, state ->
                        AppLogger.d(TAG, "Node $nodeId state: $state")
                    }
                }
            }.awaitAll()
        }
        summarizeExternalTriggerResults(results)
    }

    /**
     * Finds and triggers workflows based on a received Intent.
     * It checks all enabled workflows for an Intent trigger node whose configuration matches the Intent's action.
     *
     * @param intent The Intent received by the BroadcastReceiver.
     */
    suspend fun triggerWorkflowsByIntentEvent(intent: Intent) = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Checking for Intent-triggered workflows for action: ${intent.action}")
        val suppliedToken = WorkflowIntentSecurity.readAuthTokenSafely(intent)
        if (!authTokenManager.isAuthenticAuthToken(suppliedToken)) return@withContext

        val extras: Map<String, String> = try {
            val bundle = intent.extras
            if (bundle == null) {
                emptyMap()
            } else {
                WorkflowIntentSecurity.sanitizeExternalTriggerExtras(
                    bundle.keySet().associateWith { key -> bundle.get(key)?.toString() ?: "" }
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }

        val matches = findExternalTriggerMatches { node ->
            node.triggerType == "intent" &&
                WorkflowIntentSecurity.matches(node, intent.action, suppliedToken)
        } ?: return@withContext

        coroutineScope {
            matches.forEach { match ->
                launch {
                    triggerWorkflowInternal(
                        id = match.workflowId,
                        triggerNodeId = match.triggerNodeId,
                        triggerExtras = extras,
                        executionOrigin = WorkflowExecutionOrigin.AUTHENTICATED_EXTERNAL,
                        authorizationCheck = { latest ->
                            latest.enabled && latest.nodes.filterIsInstance<TriggerNode>().any { node ->
                                node.id == match.triggerNodeId &&
                                    WorkflowIntentSecurity.matches(node, intent.action, suppliedToken)
                            }
                        }
                    ) { nodeId, state ->
                        AppLogger.d(TAG, "Node $nodeId state: $state")
                    }
                }
            }
        }
    }

    suspend fun triggerWorkflowsByColdStartAppOpen(
        extras: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Checking for cold-start app-open-triggered workflows")
        val workflows = getAllInternalWorkflows().getOrNull() ?: return@withContext
        val triggerExtras =
            buildMap {
                put("trigger_source", "cold_start_app_open")
                putAll(extras)
            }

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node is TriggerNode && node.triggerType == "app_open") {
                        AppLogger.d(
                            TAG,
                            "Cold-start app-open trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering."
                        )
                        launch {
                            triggerWorkflowInternal(
                                id = workflow.id,
                                triggerNodeId = node.id,
                                triggerExtras = triggerExtras,
                                executionOrigin = WorkflowExecutionOrigin.AUTOMATIC,
                            ) { nodeId, state ->
                                AppLogger.d(TAG, "Node $nodeId state: $state")
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun triggerWorkflowsBySpeechEvent(text: String, isFinal: Boolean) = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext

        val now = System.currentTimeMillis()
        val cached = speechTriggerCachedWorkflows
        val workflows = if (cached != null && now - speechTriggerCachedAtMs < SPEECH_TRIGGER_CACHE_TTL_MS) {
            cached
        } else {
            val loaded = getAllInternalWorkflows().getOrNull() ?: emptyList()
            speechTriggerCachedWorkflows = loaded
            speechTriggerCachedAtMs = now
            loaded
        }

        fun parseBoolean(value: String?, defaultValue: Boolean): Boolean {
            val normalized = value?.trim()?.lowercase() ?: return defaultValue
            return when (normalized) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> defaultValue
            }
        }

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node !is TriggerNode || node.triggerType != "speech") return@forEach

                    val pattern = node.triggerConfig["pattern"].orEmpty()
                    if (pattern.isBlank()) return@forEach

                    val requireFinal = parseBoolean(node.triggerConfig["require_final"], true)
                    if (requireFinal && !isFinal) return@forEach

                    val ignoreCase = parseBoolean(node.triggerConfig["ignore_case"], true)
                    val cooldownMs = node.triggerConfig["cooldown_ms"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 3000L
                    val cooldownKey = "${workflow.id}:${node.id}"

                    val lastFireAt = speechTriggerLastFireAtMs[cooldownKey] ?: 0L
                    if (cooldownMs > 0 && now - lastFireAt < cooldownMs) return@forEach

                    val matches = try {
                        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        Regex(pattern, options).containsMatchIn(trimmed)
                    } catch (_: Exception) {
                        false
                    }

                    if (matches) {
                        speechTriggerLastFireAtMs[cooldownKey] = now
                        AppLogger.d(TAG, "Speech trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                        launch {
                            triggerWorkflowInternal(
                                id = workflow.id,
                                triggerNodeId = node.id,
                                executionOrigin = WorkflowExecutionOrigin.AUTOMATIC,
                            ) { nodeId, state ->
                                AppLogger.d(TAG, "Node $nodeId state: $state")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves one workflow-owned file or directory directly below [root]. Workflow IDs are file
 * identities, not paths: separators, NULs and dot-directory aliases are rejected, while ordinary
 * user-visible characters remain compatible. Canonical parent equality also protects callers if
 * a managed root or child is replaced with a symlink.
 */
internal fun resolveWorkflowStorageChild(
    root: File,
    workflowId: String,
    suffix: String = "",
    trustedAnchor: File = root,
): File? {
    if (!isWorkflowManagedRootTrusted(root, trustedAnchor)) return null
    if (
        workflowId.isBlank() ||
        workflowId == "." ||
        workflowId == ".." ||
        workflowId.indexOf('\u0000') >= 0 ||
        workflowId.indexOf('/') >= 0 ||
        workflowId.indexOf('\\') >= 0
    ) {
        return null
    }
    return runCatching {
        val canonicalAnchor = trustedAnchor.canonicalFile
        val canonicalRoot = root.canonicalFile
        if (
            canonicalRoot != canonicalAnchor &&
            !canonicalRoot.path.startsWith(canonicalAnchor.path + File.separator)
        ) {
            return@runCatching null
        }
        File(canonicalRoot, workflowId + suffix)
            .canonicalFile
            .takeIf { candidate -> candidate.parentFile == canonicalRoot }
    }.getOrNull()
}

/** Lists JSON files whose canonical target remains a direct child of [directory]. */
internal fun canonicalWorkflowJsonFiles(
    directory: File,
    trustedAnchor: File = directory,
    limits: WorkflowFileScanLimits = WorkflowFileScanLimits(),
): List<File> = scanCanonicalWorkflowJsonFiles(directory, trustedAnchor, limits).files

internal fun scanCanonicalWorkflowJsonFiles(
    directory: File,
    trustedAnchor: File = directory,
    limits: WorkflowFileScanLimits = WorkflowFileScanLimits(),
): WorkflowFileScanResult {
    require(limits.maxFiles >= 0)
    require(limits.maxEntriesVisited >= 0)
    require(limits.maxTotalBytes >= 0L)
    require(limits.maxFileBytes >= 0L)
    val empty = WorkflowFileScanResult(emptyList(), truncated = false, skippedEntries = 0)
    if (limits.maxFiles == 0 || limits.maxEntriesVisited == 0) {
        return WorkflowFileScanResult(emptyList(), truncated = directory.isDirectory, skippedEntries = 0)
    }
    if (!isWorkflowManagedRootTrusted(directory, trustedAnchor)) return empty
    val canonicalAnchor = runCatching { trustedAnchor.canonicalFile }.getOrNull() ?: return empty
    val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return empty
    if (
        canonicalDirectory != canonicalAnchor &&
        !canonicalDirectory.path.startsWith(canonicalAnchor.path + File.separator)
    ) {
        return empty
    }
    if (!canonicalDirectory.isDirectory) return empty

    val accepted = ArrayList<File>(minOf(limits.maxFiles, 64))
    var visited = 0
    var totalBytes = 0L
    var skipped = 0
    var truncated = false
    return runCatching {
        Files.newDirectoryStream(canonicalDirectory.toPath()).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (visited >= limits.maxEntriesVisited || accepted.size >= limits.maxFiles) {
                    truncated = true
                    break
                }
                val path = iterator.next()
                visited++
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    skipped++
                    continue
                }
                val file = path.toFile()
                if (file.extension != "json" || Files.isSymbolicLink(path)) {
                    skipped++
                    continue
                }
                if (runCatching { file.canonicalFile.parentFile == canonicalDirectory }.getOrDefault(false).not()) {
                    skipped++
                    continue
                }
                val attributes = runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                if (attributes == null || !attributes.isRegularFile) {
                    skipped++
                    continue
                }
                val fileBytes = attributes.size()
                if (
                    fileBytes > limits.maxFileBytes ||
                    fileBytes > limits.maxTotalBytes - totalBytes
                ) {
                    skipped++
                    continue
                }
                accepted += file
                totalBytes += fileBytes
            }
        }
        WorkflowFileScanResult(accepted, truncated, skipped)
    }.getOrDefault(empty)
}

/** Rejects a managed root when it or any lexical component below [trustedAnchor] is a symlink. */
private fun isWorkflowManagedRootTrusted(root: File, trustedAnchor: File): Boolean {
    val anchorPath = trustedAnchor.toPath().toAbsolutePath().normalize()
    val rootPath = root.toPath().toAbsolutePath().normalize()
    if (!rootPath.startsWith(anchorPath) || Files.isSymbolicLink(anchorPath)) return false

    var current = rootPath
    while (current != anchorPath) {
        if (Files.isSymbolicLink(current)) return false
        current = current.parent ?: return false
    }
    return true
}

internal fun workflowDeletionSucceeded(
    internalExisted: Boolean,
    internalRemoved: Boolean,
    legacyExisted: Boolean
): Boolean = if (internalExisted) internalRemoved else legacyExisted

/** Private execution history is authoritative; legacy history is only a fallback. */
internal fun latestWorkflowExecutionRecordFile(
    internalFiles: List<File>,
    legacyFiles: List<File>,
): File? = (internalFiles.ifEmpty { legacyFiles })
    .maxWithOrNull(compareBy<File>({ it.lastModified() }, { it.name }))

internal fun requireWorkflowExecutionRecordOwnership(
    record: WorkflowExecutionRecord,
    expectedWorkflowId: String,
): WorkflowExecutionRecord {
    require(record.workflowId == expectedWorkflowId) {
        "Workflow execution record belongs to a different workflow"
    }
    return record
}
