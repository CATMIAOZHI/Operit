package com.ai.assistance.operit.features.reading

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class AutoCommentaryGenerationResult(
    val bookId: String,
    val chapterIndex: Int?,
    val status: String,
    val commentCount: Int,
    val runId: Long? = null,
    val execution: AutoCommentModelExecution? = null,
)

class AutoCommentRoleNotSelectedException :
    IllegalStateException("请先为当前书籍选择段评角色卡")

class AutoCommentRoleUnavailableException :
    IllegalStateException("所选段评角色卡已不存在，请重新选择")

internal fun safeReadingCompanionError(error: Throwable): String = when (error) {
    is AutoCommentModelTimeoutException -> "model_timeout"
    is AutoCommentContextTooSmallException -> "model_context_too_small"
    is AutoCommentRoleNotSelectedException -> "role_not_selected"
    is AutoCommentRoleUnavailableException -> "role_unavailable"
    is ReaderProviderException -> when (error.reason) {
        ReaderProviderException.Reason.LEGADO_NOT_INSTALLED -> "legado_not_installed"
        ReaderProviderException.Reason.CONNECTION_FAILED -> "legado_connection_failed"
        ReaderProviderException.Reason.EMPTY_BOOKSHELF -> "legado_empty_bookshelf"
        ReaderProviderException.Reason.NO_RECENT_BOOK -> "legado_no_recent_book"
        ReaderProviderException.Reason.CHAPTER_READ_FAILED -> "legado_chapter_read_failed"
        ReaderProviderException.Reason.UNSAFE_POSITION -> "legado_unsafe_position"
        ReaderProviderException.Reason.INVALID_RESPONSE -> "legado_invalid_response"
    }
    is IllegalArgumentException -> "invalid_model_response"
    else -> "unknown_error"
}

class ReadingCompanionAutoCommentary private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)

    suspend fun generateNextChapter(
        force: Boolean = false,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        trigger: String = TRIGGER_BACKGROUND,
    ): AutoCommentaryGenerationResult {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
        val runId = store.startAutoCommentRun(trigger = trigger)
        return try {
            generateNextChapterInRun(
                runId = runId,
                force = force,
                runtime = runtime,
                trigger = trigger,
            )
        } catch (cancelled: CancellationException) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_CANCELLED,
                errorMessage = "cancelled",
            )
            throw cancelled
        } catch (error: Throwable) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_FAILED,
                errorMessage = safeReadingCompanionError(error),
            )
            throw error
        }
    }

    private suspend fun generateNextChapterInRun(
        runId: Long,
        force: Boolean,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        trigger: String,
    ): AutoCommentaryGenerationResult {
        store.updateAutoCommentRunStage(runId, AutoCommentRunStages.READING_TARGET)
        val initialState = traceOperation(
            runId = runId,
            operation = "legado_reading_state",
            metadata = JSONObject()
                .put("route", "reading/progress/query")
                .put("providerAuthority", "${appContext.packageName}.readingCompanionAnnotations"),
        ) {
            provider.getReadingState()
        }
        store.updateBook(initialState)
        store.prepareForState(initialState)
        val nextChapterIndex = initialState.chapterIndex + 1
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
        )
        val chapterIndices = traceOperation(
            runId = runId,
            operation = "legado_chapter_list",
            metadata = JSONObject()
                .put("route", "books/chapters/query")
                .put("bookId", initialState.book.id),
        ) {
            provider.getChapters(initialState.book.id)
        }.mapTo(hashSetOf(), ReaderChapter::index)
        val hasNextChapter = nextChapterIndex in chapterIndices
        if (!hasNextChapter) {
            store.finishAutoCommentRun(
                runId = runId,
                status = STATUS_NO_NEXT_CHAPTER,
            )
            return AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = null,
                status = STATUS_NO_NEXT_CHAPTER,
                commentCount = 0,
                runId = runId,
            )
        }

        val selectedPersona = store.getAutoCommentPersona(initialState.book.id)
            ?: throw AutoCommentRoleNotSelectedException()
        val resolvedRole = modelGateway.resolveAutoCommentRole(selectedPersona.roleCardId)
        val persona = selectedPersona.copy(
            roleCardId = resolvedRole.id,
            roleCardName = resolvedRole.name,
        )
        store.updateAutoCommentPersonaNameIfRoleMatches(
            bookId = initialState.book.id,
            roleCardId = resolvedRole.id,
            roleCardName = resolvedRole.name,
        )
        val content = traceOperation(
            runId = runId,
            operation = "legado_annotation_read",
            metadata = JSONObject()
                .put("route", "book/annotationContent/query")
                .put("bookId", initialState.book.id)
                .put("chapterIndex", nextChapterIndex)
                .put("contentPolicy", "readable-prefix-only"),
        ) {
            provider.getAnnotationChapterContent(
                initialState.book.id,
                nextChapterIndex,
            )
        }
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = content.chapterTitle,
        )
        val contentHash = content.contractHash
        val latestPersonaBeforeGeneration =
            store.getAutoCommentPersona(initialState.book.id)
        if (
            latestPersonaBeforeGeneration?.roleCardId != persona.roleCardId ||
            latestPersonaBeforeGeneration?.roleCardName != persona.roleCardName
        ) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
            )
            return AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                status = STATUS_SUPERSEDED,
                commentCount = 0,
                runId = runId,
            )
        }
        val claim = store.tryClaimAutoCommentGeneration(
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = content.chapterTitle,
            contentHash = contentHash,
            roleCardId = persona.roleCardId,
            roleCardName = persona.roleCardName,
            generationRunId = runId,
            force = force,
            staleAfterMs = GENERATING_STALE_AFTER_MS,
        )
        when (claim.status) {
            AutoCommentGenerationClaimStatus.CACHED -> {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = STATUS_CACHED,
                    commentCount = claim.commentCount,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_CACHED,
                    commentCount = claim.commentCount,
                    runId = runId,
                )
            }
            AutoCommentGenerationClaimStatus.ALREADY_GENERATING -> {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = STATUS_ALREADY_GENERATING,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_ALREADY_GENERATING,
                    commentCount = 0,
                    runId = runId,
                )
            }
            AutoCommentGenerationClaimStatus.CLAIMED -> Unit
        }
        return try {
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.PREPARING_CONTEXT)
            val previousContext = traceOperation(
                runId = runId,
                operation = "local_context_prepare",
                metadata = JSONObject()
                    .put("source", "reading_companion.db+legado")
                    .put("maxPreviousChapters", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHAPTERS)
                    .put("maxPreviousCharacters", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHARS),
            ) {
                loadPreviousCommentaryContext(
                    bookId = initialState.book.id,
                    targetChapterIndex = nextChapterIndex,
                    availableChapterIndices = chapterIndices,
                )
            }
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.RESOLVING_MODEL)
            var resolvedExecution: AutoCommentModelExecution? = null
            var observedUsage: ProviderUsageSnapshot? = null
            val modelTraceStartedAt = System.currentTimeMillis()
            val generated = try {
                modelGateway.generateAutoComments(
                    content = content,
                    previousContext = previousContext,
                    roleCardId = persona.roleCardId,
                    runtime = runtime,
                    onExecutionResolved = { execution ->
                        resolvedExecution = execution
                        store.updateAutoCommentRunExecution(runId, execution)
                    },
                    onPromptPrepared = { metrics ->
                        store.updateAutoCommentRunPromptMetrics(
                            runId = runId,
                            targetCharacterCount =
                                content.content.count { character -> !character.isWhitespace() },
                            metrics = metrics,
                        )
                        store.updateAutoCommentRunStage(
                            runId,
                            AutoCommentRunStages.WAITING_MODEL,
                        )
                    },
                    onModelResponseReceived = { usage ->
                        observedUsage = usage
                        if (usage != null) {
                            store.updateAutoCommentRunUsage(runId, usage)
                        }
                        store.updateAutoCommentRunStage(
                            runId,
                            AutoCommentRunStages.VALIDATING_RESPONSE,
                        )
                    },
                )
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "model_direct_call",
                    status = "failed",
                    startedAt = modelTraceStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = modelTraceMetadata(
                        execution = resolvedExecution,
                        usage = observedUsage,
                        estimatedInputTokens = store.getAutoCommentRun(runId)?.estimatedInputTokens,
                        error = safeReadingCompanionError(error),
                    ).toString(),
                )
                throw error
            }
            store.recordAutoCommentRunTrace(
                runId = runId,
                operation = "model_direct_call",
                status = "completed",
                startedAt = modelTraceStartedAt,
                finishedAt = System.currentTimeMillis(),
                metadataJson = modelTraceMetadata(
                    execution = generated.execution,
                    usage = generated.usage ?: observedUsage,
                    estimatedInputTokens = store.getAutoCommentRun(runId)?.estimatedInputTokens,
                ).toString(),
            )
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.SAVING_COMMENTS)
            if (trigger == TRIGGER_BACKGROUND && !isEnabled(appContext)) {
                throw CancellationException("AI 自动段评已关闭")
            }
            val latestState = traceOperation(
                runId = runId,
                operation = "legado_reading_state_recheck",
                metadata = JSONObject()
                    .put("route", "reading/progress/query")
                    .put("purpose", "pre_save_consistency_check"),
            ) {
                provider.getReadingState()
            }
            val latestPersona = store.getAutoCommentPersona(initialState.book.id)
            if (
                latestState.book.id != initialState.book.id ||
                nextChapterIndex > latestState.chapterIndex + 1 ||
                latestPersona?.roleCardId != generated.execution.roleCardId
            ) {
                store.markAutoCommentGenerationFailedIfOwned(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    generationRunId = runId,
                )
                store.finishAutoCommentRun(
                    runId = runId,
                    status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_SUPERSEDED,
                    commentCount = 0,
                    runId = runId,
                    execution = generated.execution,
                )
            }
            val records = generated.comments.map { draft ->
                AutoCommentRecord(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    paragraphIndex = draft.paragraphIndex,
                    text = draft.text,
                    kind = draft.kind,
                    roleCardId = generated.execution.roleCardId,
                    roleCardName = generated.execution.roleCardName,
                    evidenceJson = AutoCommentSupport.evidenceJson(draft),
                )
            }
            if (records.isEmpty()) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "db_save_comments",
                    status = "skipped",
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("database", "reading_companion.db")
                        .put("table", "auto_comments+auto_comment_run_comments")
                        .put("bookId", initialState.book.id)
                        .put("chapterIndex", nextChapterIndex)
                        .put("commentCount", 0)
                        .put(
                            "reason",
                            "模型返回内容，但没有有效段评被接受（解析/校验过滤后为空）",
                        )
                        .toString(),
                )
                store.markAutoCommentGenerationFailedIfOwned(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    generationRunId = runId,
                )
                store.finishAutoCommentRun(
                    runId = runId,
                    status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_NO_VALID_COMMENTS,
                    commentCount = 0,
                    errorMessage = "模型返回内容，但没有有效段评被接受",
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_NO_VALID_COMMENTS,
                    commentCount = 0,
                    runId = runId,
                    execution = generated.execution,
                )
            }
            val saveStartedAt = System.currentTimeMillis()
            val stored = try {
                store.replaceAutoComments(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    chapterTitle = content.chapterTitle,
                    contentHash = contentHash,
                    roleCardId = requireNotNull(generated.execution.roleCardId),
                    roleCardName = requireNotNull(generated.execution.roleCardName),
                    generationRunId = runId,
                    comments = records,
                ).also {
                    store.recordAutoCommentRunTrace(
                        runId = runId,
                        operation = "db_save_comments",
                        status = if (it) "completed" else "skipped",
                        startedAt = saveStartedAt,
                        finishedAt = System.currentTimeMillis(),
                        metadataJson = JSONObject()
                            .put("database", "reading_companion.db")
                            .put("table", "auto_comments+auto_comment_run_comments")
                            .put("bookId", initialState.book.id)
                            .put("chapterIndex", nextChapterIndex)
                            .put("commentCount", records.size)
                            .toString(),
                    )
                }
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "db_save_comments",
                    status = "failed",
                    startedAt = saveStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("database", "reading_companion.db")
                        .put("table", "auto_comments+auto_comment_run_comments")
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
                throw error
            }
            if (!stored) {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_SUPERSEDED,
                    commentCount = 0,
                    runId = runId,
                    execution = generated.execution,
                )
            }
            val notifyStartedAt = System.currentTimeMillis()
            try {
                notifyAnnotationChanged()
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "legado_annotation_notify",
                    status = "completed",
                    startedAt = notifyStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("authority", "${appContext.packageName}.readingCompanionAnnotations")
                        .put("uri", "content://${appContext.packageName}.readingCompanionAnnotations/reviews")
                        .put("reason", "generated_comments_ready")
                        .toString(),
                )
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "legado_annotation_notify",
                    status = "failed",
                    startedAt = notifyStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("authority", "${appContext.packageName}.readingCompanionAnnotations")
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
            }
            AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                status = STATUS_GENERATED,
                commentCount = records.size,
                runId = runId,
                execution = generated.execution,
            )
        } catch (cancelled: CancellationException) {
            store.markAutoCommentGenerationFailedIfOwned(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                generationRunId = runId,
            )
            throw cancelled
        } catch (error: Throwable) {
            store.markAutoCommentGenerationFailedIfOwned(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                generationRunId = runId,
            )
            throw error
        }
    }

    private suspend fun loadPreviousCommentaryContext(
        bookId: String,
        targetChapterIndex: Int,
        availableChapterIndices: Set<Int>,
    ): List<AutoCommentContextChapter> {
        var loadedCharacters = 0
        val chaptersNearestFirst = buildList {
            for (
                chapterIndex in (targetChapterIndex - 1) downTo
                    maxOf(0, targetChapterIndex - AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHAPTERS)
            ) {
                if (chapterIndex !in availableChapterIndices) continue
                val chapter = try {
                    provider.getAnnotationChapterContent(bookId, chapterIndex)
                } catch (_: ReaderProviderException) {
                    continue
                }
                add(chapter)
                loadedCharacters += chapter.content.trim().length
                if (loadedCharacters >= AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHARS) break
            }
        }
        return AutoCommentSupport.selectPreviousContext(chaptersNearestFirst)
    }

    private suspend fun <T> traceOperation(
        runId: Long,
        operation: String,
        metadata: JSONObject,
        block: suspend () -> T,
    ): T {
        val startedAt = System.currentTimeMillis()
        return try {
            block().also {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = operation,
                    status = "completed",
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = metadata.toString(),
                )
            }
        } catch (error: Throwable) {
            store.recordAutoCommentRunTrace(
                runId = runId,
                operation = operation,
                status = "failed",
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                metadataJson = metadata
                    .put("error", safeReadingCompanionError(error))
                    .toString(),
            )
            throw error
        }
    }

    private fun modelTraceMetadata(
        execution: AutoCommentModelExecution?,
        usage: ProviderUsageSnapshot?,
        estimatedInputTokens: Int?,
        error: String? = null,
    ): JSONObject = JSONObject().apply {
        put("functionType", "CHAT")
        put("statsCategory", TokenStatCategory.READING_COMPANION.name)
        put("stream", false)
        put("enableThinking", false)
        put("availableTools", JSONArray())
        put("toolsEnabled", false)
        put("modelSource", execution?.modelSource)
        put("provider", execution?.provider)
        put("model", execution?.model)
        put("modelConfigId", execution?.configId)
        put("modelIndex", execution?.modelIndex)
        put("estimatedInputTokens", estimatedInputTokens)
        if (usage == null) {
            put("usageStatus", "unavailable_estimate_only")
        } else {
            put("usageStatus", "provider_reported")
            put("usageSource", usage.source)
            put("usageComplete", usage.completeSnapshot)
            put("actualUncachedInputTokens", usage.uncachedInputTokens)
            put("actualCachedInputTokens", usage.cachedInputTokens)
            put("actualInputTokens", usage.totalInputTokens)
            put("actualOutputTokens", usage.outputTokens)
            put("actualReasoningTokens", usage.reasoningTokens)
        }
        if (error != null) put("error", error)
    }

    suspend fun status(): JSONObject {
        settleInterruptedRuns()
        val configuration = runCatching {
            val state = provider.getReadingState()
            val persona = store.getAutoCommentPersona(state.book.id)
                ?: return@runCatching null
            modelGateway.previewAutoCommentConfiguration(persona.roleCardId)
        }.getOrNull()
        return JSONObject().apply {
            put("enabled", isEnabled(appContext))
            put("mode", "whole_chapter_single_request")
            put("prefetchChapters", 1)
            put("generationRequestsPerChapter", 1)
            put("generationPolicyVersion", AutoCommentSupport.GENERATION_POLICY_VERSION)
            put("previousContextChapterLimit", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHAPTERS)
            put("previousContextCharacterLimit", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHARS)
            put("roleCardPolicy", "per_book_selected_character_card")
            put("modelPolicy", "caller_model_or_role_card_binding_or_global_chat")
            put("tokenStatsCategory", TokenStatCategory.READING_COMPANION.name)
            put(
                "historyStorage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put(
                "configuration",
                configuration?.toJson() ?: JSONObject.NULL,
            )
            put("latestRun", store.getRecentAutoCommentRuns(1).firstOrNull()?.toJson())
        }
    }

    fun history(limit: Int = 10): JSONObject {
        settleInterruptedRuns()
        return JSONObject().apply {
            put(
                "storage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put(
                "runs",
                JSONArray().apply {
                    store.getRecentAutoCommentRuns(limit).forEach { run -> put(run.toJson()) }
                },
            )
        }
    }

    fun detail(runId: Long): JSONObject {
        require(runId > 0) { "runId is required" }
        settleInterruptedRuns()
        val run = store.getAutoCommentRun(runId)
            ?: throw IllegalArgumentException("段评任务记录不存在或已过期")
        val comments = store.getAutoCommentRunComments(runId)
        val snapshotAvailability = when {
            comments.isNotEmpty() -> "available"
            run.commentCount > 0 -> "unavailable_legacy"
            else -> "not_applicable"
        }
        return JSONObject().apply {
            put(
                "storage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put("run", run.toJson())
            put("snapshotAvailability", snapshotAvailability)
            put(
                "stages",
                JSONArray().apply {
                    store.getAutoCommentRunStageEvents(runId).forEach { event ->
                        put(event.toJson())
                    }
                },
            )
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment ->
                        put(comment.toJson())
                    }
                },
            )
            put(
                "operations",
                JSONArray().apply {
                    store.getAutoCommentRunTraceEvents(runId).forEach { event ->
                        put(event.toJson())
                    }
                },
            )
        }
    }

    suspend fun hasCaughtUp(result: AutoCommentaryGenerationResult): Boolean {
        if (
            result.status != STATUS_GENERATED &&
            result.status != STATUS_CACHED &&
            result.status != STATUS_NO_NEXT_CHAPTER
        ) {
            return false
        }
        val latestState = provider.getReadingState()
        if (latestState.book.id != result.bookId) return false
        val completedTarget = result.chapterIndex
            ?: return result.status == STATUS_NO_NEXT_CHAPTER
        return completedTarget == latestState.chapterIndex + 1
    }

    fun shouldStopWithoutCatchUp(result: AutoCommentaryGenerationResult): Boolean =
        result.status == STATUS_ALREADY_GENERATING

    fun recentComments(bookId: String, chapterIndex: Int): JSONObject {
        val chapter = store.getAutoCommentChapter(bookId, chapterIndex)
        val comments = if (
            chapter?.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_READY &&
            chapter.generationPolicyVersion == AutoCommentSupport.GENERATION_POLICY_VERSION
        ) {
            store.getAutoComments(bookId, chapterIndex)
        } else {
            emptyList()
        }
        return JSONObject().apply {
            put("bookId", bookId)
            put("chapterIndex", chapterIndex)
            put("chapterTitle", chapter?.chapterTitle)
            put("status", chapter?.status ?: "missing")
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment ->
                        put(
                            JSONObject()
                                .put("id", comment.id)
                                .put("paragraphIndex", comment.paragraphIndex)
                                .put("text", comment.text)
                                .put("kind", comment.kind)
                                .put("createdAt", comment.createdAt)
                        )
                    }
                },
            )
        }
    }

    private fun notifyAnnotationChanged() {
        val authority = "${appContext.packageName}.readingCompanionAnnotations"
        appContext.contentResolver.notifyChange(
            Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("reviews")
                .build(),
            null,
        )
    }

    private fun AutoCommentRun.toJson(): JSONObject = JSONObject().apply {
        put("runId", id)
        put("bookName", bookName)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex?.plus(1))
        put("trigger", trigger)
        put("status", status)
        put("stage", stage)
        put("stageUpdatedAt", stageUpdatedAt)
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("modelConfigId", modelConfigId)
        put("modelConfigName", modelConfigName)
        put("modelIndex", modelIndex)
        put("modelSource", modelSource)
        put("provider", provider)
        put("model", model)
        put("targetCharacterCount", targetCharacterCount)
        put("contextChapterCount", contextChapterCount)
        put("contextCharacterCount", contextCharacterCount)
        put("contextWindowTokens", contextWindowTokens)
        put("estimatedInputTokens", estimatedInputTokens)
        put("actualUncachedInputTokens", actualUncachedInputTokens)
        put("actualCachedInputTokens", actualCachedInputTokens)
        put("actualInputTokens", actualInputTokens)
        put("actualOutputTokens", actualOutputTokens)
        put("actualReasoningTokens", actualReasoningTokens)
        put("actualUsageSource", actualUsageSource)
        put("actualUsageComplete", actualUsageComplete)
        put("commentCount", commentCount)
        put("error", errorMessage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put(
            "durationMs",
            ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0),
        )
    }

    private fun AutoCommentRunStageEvent.toJson(): JSONObject = JSONObject().apply {
        put("stage", stage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("durationMs", durationMs)
    }

    private fun AutoCommentRunCommentSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("runId", runId)
        put("bookId", bookId)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex + 1)
        put("chapterTitle", chapterTitle)
        put("contentHash", contentHash)
        put("paragraphIndex", paragraphIndex)
        put("paragraphNumber", paragraphIndex + 1)
        put("text", text)
        put("kind", kind)
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("evidenceJson", evidenceJson)
        put("createdAt", createdAt)
    }

    private fun AutoCommentRunTraceEvent.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("runId", runId)
        put("operation", operation)
        put("status", status)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("durationMs", durationMs)
        val metadata = metadataJson?.trim().orEmpty()
        if (metadata.isBlank()) {
            put("metadata", JSONObject())
        } else {
            put(
                "metadata",
                runCatching { JSONObject(metadata) }.getOrElse { JSONObject().put("raw", metadata) },
            )
        }
    }

    private fun AutoCommentConfigurationPreview.toJson(): JSONObject = JSONObject().apply {
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("modelSource", modelSource)
        put("modelConfigId", modelConfigId)
        put("modelConfigName", modelConfigName)
        put("modelIndex", modelIndex)
        put("provider", provider)
        put("model", model)
        put("contextWindowTokens", contextWindowTokens)
    }

    private fun settleInterruptedRuns() {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
    }

    companion object {
        const val AUTO_COMMENT_WORK_NAME = "reading_companion_auto_commentary"
        const val AUTO_COMMENT_MANUAL_WORK_NAME =
            "reading_companion_auto_commentary_manual"
        private const val AUTO_COMMENT_DELAY_SECONDS = 20L
        private const val GENERATING_STALE_AFTER_MS = 5 * 60_000L
        private const val STATUS_GENERATED = "generated"
        private const val STATUS_CACHED = "cached"
        private const val STATUS_ALREADY_GENERATING = "already_generating"
        private const val STATUS_NO_NEXT_CHAPTER = "no_next_chapter"
        private const val STATUS_SUPERSEDED = "superseded"
        private const val STATUS_NO_VALID_COMMENTS = "no_valid_comments"
        const val ALREADY_GENERATING_QUEUED_AT = -1L
        const val TRIGGER_BACKGROUND = "background"
        const val TRIGGER_MANUAL = "manual"

        @Volatile
        private var instance: ReadingCompanionAutoCommentary? = null

        fun getInstance(context: Context): ReadingCompanionAutoCommentary =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionAutoCommentary(context).also { instance = it }
            }

        fun isEnabled(context: Context): Boolean {
            return AutoCommentSurfacePolicy.canGenerate(
                readingCompanionEnabled = isBasePackageEnabled(context),
                autoCommentaryEnabled = isAutoCommentaryPackageEnabled(context),
            )
        }

        /**
         * Stored comments remain readable while only the optional generator is disabled.
         *
         * The parent ToolPkg switch is the explicit boundary for the whole Reading Companion
         * surface (including the Legado read-only provider); the auto-commentary subpackage switch
         * controls future generation and scheduling only.
         */
        fun isBasePackageEnabled(context: Context): Boolean {
            val packageManager = packageManager(context)
            return AutoCommentSurfacePolicy.canReadStoredComments(
                packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID),
            )
        }

        private fun isAutoCommentaryPackageEnabled(context: Context): Boolean {
            val packageManager = packageManager(context)
            return packageManager.isPackageEnabled(
                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME,
            )
        }

        private fun packageManager(context: Context): PackageManager =
            PackageManager.getInstance(
                context.applicationContext,
                AIToolHandler.getInstance(context.applicationContext),
            )

        fun schedule(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReadingCompanionAutoCommentWorker>()
                    .setInitialDelay(AUTO_COMMENT_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun rescheduleAfterPersonaChange(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReadingCompanionAutoCommentWorker>()
                    .setInitialDelay(AUTO_COMMENT_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * Queues a manual generation request. Returns the request timestamp when the work was
         * actually enqueued, or [ALREADY_GENERATING_QUEUED_AT] when a manual task is still
         * running (ExistingWorkPolicy.KEEP would have silently dropped the new request).
         */
        fun enqueueManual(context: Context): Long {
            val store = ReadingCompanionStore(context.applicationContext)
            val latestRun = store.getRecentAutoCommentRuns(1).firstOrNull()
            if (
                latestRun != null &&
                latestRun.status == ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING
            ) {
                return ALREADY_GENERATING_QUEUED_AT
            }
            val queuedAt = System.currentTimeMillis()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_MANUAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReadingCompanionManualAutoCommentWorker>().build(),
            )
            return queuedAt
        }

        fun cancelIfDisabledPackage(
            context: Context,
            packageName: String,
        ) {
            if (
                packageName != ReadingCompanionService.TOOLPKG_ID &&
                packageName != ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
            ) {
                return
            }
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(AUTO_COMMENT_WORK_NAME)
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(AUTO_COMMENT_MANUAL_WORK_NAME)
        }
    }
}

class ReadingCompanionManualAutoCommentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
            return Result.success()
        }
        return try {
            ReadingCompanionAutoCommentary.getInstance(applicationContext)
                .generateNextChapter(
                    force = true,
                    trigger = ReadingCompanionAutoCommentary.TRIGGER_MANUAL,
                )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}

class ReadingCompanionAutoCommentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
            return Result.success()
        }
        return try {
            val commentary = ReadingCompanionAutoCommentary.getInstance(applicationContext)
            repeat(MAX_CATCH_UP_GENERATIONS) {
                if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
                    return Result.success()
                }
                val generated = commentary.generateNextChapter(
                    trigger = ReadingCompanionAutoCommentary.TRIGGER_BACKGROUND,
                )
                if (commentary.shouldStopWithoutCatchUp(generated)) return Result.success()
                if (commentary.hasCaughtUp(generated)) return Result.success()
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AutoCommentModelTimeoutException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: ReaderProviderException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private companion object {
        const val MAX_CATCH_UP_GENERATIONS = 3
    }
}
