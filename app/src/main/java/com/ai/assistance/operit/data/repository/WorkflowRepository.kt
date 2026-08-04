package com.ai.assistance.operit.data.repository

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.workflow.NodeExecutionState
import com.ai.assistance.operit.core.workflow.WorkflowExecutor
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流仓库
 * 负责工作流的持久化存储和管理
 *
 * 存储分层（Phase 2 迁移）：
 * - **应用内部目录** [OperitManagedPaths.internalWorkflows]（`filesDir/operit/workflows/definitions`）
 *   是主存储和唯一默认写入位置。所有新建、修改、执行状态/统计写入都落在这里。
 * - **旧版 Download 目录** [OperitManagedPaths.legacyWorkflows]（`Download/Operit/workflow`，单数）
 *   仅当 [LegacyStoragePreferences.isReadLegacyWorkflows] 为 true 时作为只读读取源。旧目录
 *   访问不创建目录；同名工作流以内部版本优先；旧工作流第一次被修改/执行时通过写时复制
 *   进入内部目录，原文件不动。
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

    companion object {
        private const val TAG = "WorkflowRepository"
        private const val MAX_EXECUTION_LOG_FILES_PER_WORKFLOW = 30

        // Per-workflow-id lock serializing write-on-copy promotion so a concurrent mutator of
        // the same id cannot race the existence-check + atomic-rename sequence.
        private val promotionLocks = ConcurrentHashMap<String, Any>()

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
            workflowUpdateEvents.tryEmit(Unit)
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
        File(paths.internalWorkflows, "$workflowId.json")

    /**
     * Legacy `Download/Operit/workflow/<id>.json`. Non-creating: never calls mkdirs. Only
     * consulted when the legacy read switch is on.
     */
    private fun getLegacyWorkflowFile(workflowId: String): File =
        File(paths.legacyWorkflows, "$workflowId.json")

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

    private fun readWorkflowFile(file: File, workflowId: String = file.nameWithoutExtension): Workflow {
        val element = json.parseToJsonElement(file.readText())
        // id is filename-derived (the on-disk field is not trusted), keeping write-on-copy safe.
        val workflowElement = JsonObject((element as JsonObject) + ("id" to JsonPrimitive(workflowId)))
        return json.decodeFromJsonElement(Workflow.serializer(), workflowElement)
    }

    private fun getExecutionLogDirectory(workflowId: String, createIfMissing: Boolean = true): File {
        // Execution logs live under noBackupFilesDir (excluded from raw snapshots).
        val dir = File(paths.internalWorkflowLogs, workflowId)
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun saveExecutionRecord(record: WorkflowExecutionRecord) {
        try {
            val dir = getExecutionLogDirectory(record.workflowId)
            val safeRunId = record.runId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(dir, "${record.startedAt}_$safeRunId.json")
            file.writeText(json.encodeToString(record))

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
        val parent = file.parentFile
            ?: throw IllegalStateException("Workflow file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create workflow directory: ${parent.absolutePath}")
        }
        val atomicFile = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            output = null
        } catch (e: Exception) {
            try {
                output?.let { atomicFile.failWrite(it) }
            } catch (rollbackError: Exception) {
                AppLogger.e(TAG, "Failed to roll back workflow write: ${file.name}", rollbackError)
            }
            throw e
        }
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

        val parent = internal.parentFile
            ?: throw IllegalStateException("Workflow file has no parent directory")
        if (!parent.exists()) parent.mkdirs()
        if (!effective.isLegacy) return internal

        // Serialize per-id promotion. A concurrent createWorkflow could create `internal`
        // between a check and renameTo; on some platforms File.renameTo atomically REPLACES an
        // existing destination, which would clobber a newer internal copy with the stale legacy
        // source. The per-id lock closes that window: re-check existence inside the lock and
        // only rename when no internal copy exists. createWorkflow does not take this lock, but
        // createWorkflow only runs for genuinely new ids (no legacy copy), so it never competes
        // with a promotion of the same id; updateWorkflow/setWorkflowEnabled/execution writes
        // all funnel through ensureWorkflowInInternalStorage, so the lock covers them.
        val lock = promotionLocks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            if (internal.exists() && internal.isFile) return internal
            // Race-safe atomic promotion: copy into a process-unique temp, then renameTo only
            // when internal is still absent. File.createTempFile guarantees a unique path per
            // call, so two concurrent promotions (even across lock retries) never share a temp.
            val tmp = File.createTempFile("wf_promote_${id}_", ".tmp", parent)
            try {
                effective.sourceFile.copyTo(tmp, overwrite = true)
                if (!internal.exists()) {
                    if (!tmp.renameTo(internal)) {
                        // renameTo returned false but internal still absent — unexpected IO
                        // failure. Surface it rather than silently dropping the promotion.
                        if (!internal.exists()) {
                            throw java.io.IOException("Failed to promote workflow $id into internal storage")
                        }
                        // Otherwise a concurrent creator won; accept its internal copy.
                    }
                }
                AppLogger.d(TAG, "Write-on-copy: legacy workflow $id copied to internal storage")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
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
            scanWorkflowDir(paths.internalWorkflows, seenIds, workflows)

            // 2. Scan legacy Download store only if the read switch is on.
            if (legacyPrefs.isReadLegacyWorkflows()) {
                val hidden = legacyPrefs.hiddenLegacyWorkflowIds()
                val legacyDir = paths.legacyWorkflows
                if (legacyDir.isDirectory) {
                    scanWorkflowDir(legacyDir, seenIds, workflows, skipIds = hidden)
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
        skipIds: Set<String> = emptySet()
    ) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        for (file in files) {
            val id = file.nameWithoutExtension
            if (id in seenIds) continue   // internal already wins; skip the legacy copy
            if (id in skipIds) continue   // hidden legacy; skip without aborting the scan
            try {
                out += readWorkflowFile(file, id)
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
            val workflow = readWorkflowFile(entry.sourceFile, id)
            Result.success(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get workflow by id: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getLatestExecutionRecord(workflowId: String): Result<WorkflowExecutionRecord?> = withContext(Dispatchers.IO) {
        try {
            val dir = getExecutionLogDirectory(workflowId, createIfMissing = false)
            if (!dir.exists()) {
                return@withContext Result.success(null)
            }
            val latestFile =
                dir.listFiles { f -> f.isFile && f.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }
                    ?: return@withContext Result.success(null)

            val content = latestFile.readText()
            val record = json.decodeFromString<WorkflowExecutionRecord>(content)
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
            val file = getInternalWorkflowFile(workflow.id)
            val content = json.encodeToString(workflow)
            // Serialize with promotion so a concurrent write-on-copy of the same id cannot
            // have its atomic rename replace this freshly-created internal file on platforms
            // where File.renameTo atomically overwrites an existing destination.
            synchronized(promotionLocks.computeIfAbsent(workflow.id) { Any() }) {
                atomicWrite(file, content)
            }

            AppLogger.d(TAG, "Workflow created: ${workflow.id}")

            // Schedule if enabled and has schedule trigger
            if (workflow.enabled && hasScheduleTrigger(workflow)) {
                scheduleWorkflow(workflow.id)
            }

            notifyWorkflowsChanged()

            Result.success(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create workflow", e)
            Result.failure(e)
        }
    }

    /**
     * 更新工作流
     */
    suspend fun updateWorkflow(workflow: Workflow): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            require(workflow.id.isNotBlank()) { "Workflow id cannot be empty" }
            val updatedWorkflow = workflow.copy(updatedAt = System.currentTimeMillis())
            val file = ensureWorkflowInInternalStorage(updatedWorkflow.id)
            val content = json.encodeToString(updatedWorkflow)
            atomicWrite(file, content)

            AppLogger.d(TAG, "Workflow updated: ${updatedWorkflow.id}")

            // Keep WorkManager in sync with the latest workflow state.
            if (updatedWorkflow.enabled && hasScheduleTrigger(updatedWorkflow)) {
                rescheduleWorkflow(updatedWorkflow.id)
            } else {
                unscheduleWorkflow(updatedWorkflow.id)
            }

            notifyWorkflowsChanged()

            Result.success(updatedWorkflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update workflow", e)
            Result.failure(e)
        }
    }

    suspend fun setWorkflowEnabled(id: String, enabled: Boolean): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            require(id.isNotBlank()) { "Workflow id cannot be empty" }
            val entry = findEffectiveWorkflowFile(id)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_found)))

            val workflow = readWorkflowFile(entry.sourceFile, id)
            val updatedWorkflow = workflow.copy(enabled = enabled)
            val file = ensureWorkflowInInternalStorage(id)
            val content = json.encodeToString(updatedWorkflow)
            atomicWrite(file, content)

            AppLogger.d(TAG, "Workflow enabled state updated: ${updatedWorkflow.id} -> $enabled")

            if (updatedWorkflow.enabled && hasScheduleTrigger(updatedWorkflow)) {
                rescheduleWorkflow(updatedWorkflow.id)
            } else {
                unscheduleWorkflow(updatedWorkflow.id)
            }

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
            // Cancel schedule first
            unscheduleWorkflow(id)

            val internal = getInternalWorkflowFile(id)
            val legacy = getLegacyWorkflowFile(id)
            val internalExisted = internal.exists() && internal.isFile
            // Probe the legacy file existence directly, NOT gated by the read switch. If the
            // switch is currently off but a Download copy exists, a future re-enable would
            // resurrect it after the internal copy is deleted — so hide it now regardless.
            val legacyExisted = legacy.exists() && legacy.isFile

            var deleted = false
            if (internalExisted) {
                deleted = internal.delete()
            }

            // If a legacy copy still exists, hide it so it does not reappear on the next scan.
            if (legacyExisted) {
                legacyPrefs.hideLegacyWorkflowId(id)
                deleted = true
            }

            runCatching {
                val logDir = getExecutionLogDirectory(id, createIfMissing = false)
                if (logDir.exists()) {
                    logDir.deleteRecursively()
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
                // Re-schedule enabled legacy workflows that just became visible again.
                if (legacyPrefs.isReadLegacyWorkflows()) {
                    val workflows = getAllWorkflows().getOrNull().orEmpty()
                    workflows.forEach { w ->
                        if (w.enabled && hasScheduleTrigger(w)) {
                            scheduleWorkflow(w.id)
                        }
                    }
                }
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
        triggerExtras = triggerExtras
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
        onNodeStateChange = onNodeStateChange
    )

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
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val workflowResult = getWorkflowById(id)
        val workflow = workflowResult.getOrNull()

        if (workflow == null) {
            return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_exist, id)))
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
     * 更新工作流执行状态（仅状态和时间）。写入内部存储；若仅有旧版副本，先写时复制。
     */
    private suspend fun updateExecutionStatus(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext

            val updatedWorkflow = workflow.copy(
                lastExecutionStatus = status,
                lastExecutionTime = executionTime
            )

            val file = ensureWorkflowInInternalStorage(id)
            val content = json.encodeToString(updatedWorkflow)
            atomicWrite(file, content)

            AppLogger.d(TAG, "Workflow execution status updated: $id -> $status")
            notifyWorkflowsChanged()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update execution status", e)
        }
    }

    /**
     * 更新工作流执行统计信息。写入内部存储；若仅有旧版副本，先写时复制。
     */
    private suspend fun updateExecutionStatistics(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext

            val updatedWorkflow = workflow.copy(
                lastExecutionStatus = status,
                lastExecutionTime = executionTime,
                totalExecutions = workflow.totalExecutions + 1,
                successfulExecutions = if (status == ExecutionStatus.SUCCESS) {
                    workflow.successfulExecutions + 1
                } else {
                    workflow.successfulExecutions
                },
                failedExecutions = if (status == ExecutionStatus.FAILED) {
                    workflow.failedExecutions + 1
                } else {
                    workflow.failedExecutions
                }
            )

            val file = ensureWorkflowInInternalStorage(id)
            val content = json.encodeToString(updatedWorkflow)
            atomicWrite(file, content)

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
        return try {
            val workflowResult = kotlinx.coroutines.runBlocking { getWorkflowById(id) }
            val workflow = workflowResult.getOrNull()

            if (workflow == null) {
                AppLogger.w(TAG, "Workflow not found for scheduling: $id")
                return false
            }

            if (!workflow.enabled) {
                AppLogger.d(TAG, "Workflow is disabled, not scheduling: $id")
                return false
            }

            if (!hasScheduleTrigger(workflow)) {
                return false
            }

            scheduler.scheduleWorkflow(workflow)
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

    /**
     * Called when the user toggles the legacy-workflow read switch. Reschedules any workflow that
     * becomes effective/ineffective as a result.
     *
     * @param nowEnabled true if the switch was just turned on; false if just turned off.
     */
    suspend fun onLegacyReadSwitchChanged(nowEnabled: Boolean) = withContext(Dispatchers.IO) {
        val legacyDir = paths.legacyWorkflows
        val hidden = legacyPrefs.hiddenLegacyWorkflowIds()
        if (!legacyDir.isDirectory) {
            notifyWorkflowsChanged()
            return@withContext
        }
        val files = legacyDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        for (file in files) {
            val id = file.nameWithoutExtension
            if (id in hidden) continue
            val internal = getInternalWorkflowFile(id)
            if (internal.exists()) continue  // internal copy keeps its existing schedule
            val workflow = runCatching { readWorkflowFile(file, id) }.getOrNull() ?: continue
            if (!workflow.enabled || !hasScheduleTrigger(workflow)) continue
            if (nowEnabled) {
                // Newly-visible legacy workflow: schedule it.
                scheduleWorkflow(id)
            } else {
                // Switch just turned off: stop scheduling the legacy-only workflow.
                unscheduleWorkflow(id)
            }
        }
        notifyWorkflowsChanged()
    }


    /**
     * Finds and triggers workflows based on a Tasker event.
     * It checks all enabled workflows for a Tasker trigger node whose configuration matches the event data.
     *
     * @param params The list of parameters received from Tasker.
     */
    suspend fun triggerWorkflowsByTaskerEvent(params: List<String>?) = withContext(Dispatchers.IO) {
        if (params.isNullOrEmpty()) return@withContext

        AppLogger.d(TAG, "Checking for Tasker-triggered workflows with params: $params")
        val workflows = getAllWorkflows().getOrNull() ?: return@withContext

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node is TriggerNode && node.triggerType == "tasker") {
                        // Matching logic: The node's config expects a "command".
                        // It checks if any of the parameters from Tasker exactly matches this command.
                        // Example config: `{"command": "start_meeting"}`.
                        // This will match if any of the params from Tasker is "start_meeting" (case-insensitive).
                        val command = node.triggerConfig["command"]
                        if (command != null && params.any { it.equals(command, ignoreCase = true) }) {
                            AppLogger.d(TAG, "Tasker trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                            launch {
                                triggerWorkflow(workflow.id, node.id)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds and triggers workflows based on a received Intent.
     * It checks all enabled workflows for an Intent trigger node whose configuration matches the Intent's action.
     *
     * @param intent The Intent received by the BroadcastReceiver.
     */
    suspend fun triggerWorkflowsByIntentEvent(intent: Intent) = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Checking for Intent-triggered workflows for action: ${intent.action}")
        val workflows = getAllWorkflows().getOrNull() ?: return@withContext

        val extras: Map<String, String> = try {
            val bundle = intent.extras
            if (bundle == null) {
                emptyMap()
            } else {
                bundle.keySet().associateWith { key ->
                    bundle.get(key)?.toString() ?: ""
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node is TriggerNode && node.triggerType == "intent") {
                        // Match based on the Intent action.
                        // Example config: `{"action": "com.example.MY_ACTION"}`.
                        val expectedAction = node.triggerConfig["action"]
                        if (expectedAction != null && expectedAction.equals(intent.action, ignoreCase = true)) {
                            AppLogger.d(TAG, "Intent trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                            launch {
                                triggerWorkflow(workflow.id, node.id, extras)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun triggerWorkflowsByColdStartAppOpen(
        extras: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Checking for cold-start app-open-triggered workflows")
        val workflows = getAllWorkflows().getOrNull() ?: return@withContext
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
                            triggerWorkflow(workflow.id, node.id, triggerExtras)
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
            val loaded = getAllWorkflows().getOrNull() ?: emptyList()
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
                            triggerWorkflow(workflow.id, node.id)
                        }
                    }
                }
            }
        }
    }
}
