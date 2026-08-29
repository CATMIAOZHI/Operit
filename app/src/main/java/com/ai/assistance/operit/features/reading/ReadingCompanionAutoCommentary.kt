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
    private val subagentCoordinator = ReadingCompanionSubagentCoordinator.getInstance(appContext)

    suspend fun generateNextChapter(
        force: Boolean = false,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        trigger: String = TRIGGER_BACKGROUND,
        restartedFromRunId: Long? = null,
    ): AutoCommentaryGenerationResult {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
        // 阶段 3 起三条路径（后台/手动/对话内）全部走专用 subagent 执行器。
        val runId =
            store.startAutoCommentRun(
                trigger = trigger,
                executionMode = AUTO_COMMENT_RUN_EXECUTION_MODE_SUBAGENT,
                restartedFromRunId = restartedFromRunId,
            )
        return try {
            generateNextChapterInRun(
                runId = runId,
                force = force,
                runtime = runtime,
                trigger = trigger,
            )
        } catch (cancelled: CancellationException) {
            // 进程/Worker 停止：标 interrupted + 释放 claim；child transcript 保留（不删除），
            // 重启后由对账放行下一次全新 run。
            val now = System.currentTimeMillis()
            store.recordAutoCommentRunTrace(
                runId = runId,
                operation = "generation_cancelled",
                status = "interrupted",
                startedAt = now,
                finishedAt = now,
                metadataJson =
                    JSONObject()
                        .put("trigger", trigger)
                        .put("reason", "cancelled")
                        .toString(),
            )
            store.markRunInterrupted(runId = runId, errorMessage = "cancelled")
            throw cancelled
        } catch (error: Throwable) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_FAILED,
                errorMessage = safeReadingCompanionError(error),
            )
            throw error
        } finally {
            // 剪枝联动：把被删旧 run 的 hidden audit child / 空根同步到主库子树删除
            // （fail-safe：排空本身异常不掩盖生成结果）。
            runCatching { store.flushPrunedRunChatCleanup() }
            // 挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
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
            AutoCommentGenerationClaimStatus.RUN_INTERRUPTED -> {
                // run 已被 stale 清理标记为 interrupted，阻止已中断协程重新取得所有权。
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_INTERRUPTED,
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
            val generated =
                generateViaSubagent(
                    runId = runId,
                    trigger = trigger,
                    runtime = runtime,
                    initialState = initialState,
                    nextChapterIndex = nextChapterIndex,
                    content = content,
                    previousContext = previousContext,
                    persona = persona,
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
                            "没有有效段评被接受（解析/校验过滤后为空，或审计子代理弃权/未提交）",
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
                    errorMessage = "没有有效段评被接受",
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

    private fun usesSubagentExecution(trigger: String): Boolean =
        trigger == TRIGGER_BACKGROUND ||
            trigger == TRIGGER_MANUAL ||
            trigger == TRIGGER_CONVERSATION

    /**
     * 手动/对话内路径：走专用 subagent 执行器（完整 transcript、6 个专用工具、非交互护栏）。
     *
     * 只负责产生候选；提交底线（claim owner -> 重读进度/角色 -> 校验仍是下一章 ->
     * replaceAutoComments）由调用方原样保留，空候选/弃权走 no_valid_comments，绝不覆盖旧段评。
     */
    private suspend fun generateViaSubagent(
        runId: Long,
        trigger: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        initialState: ReadingState,
        nextChapterIndex: Int,
        content: AnnotationChapterContent,
        previousContext: List<AutoCommentContextChapter>,
        persona: AutoCommentPersona,
    ): GeneratedAutoComments {
        val modelTraceStartedAt = System.currentTimeMillis()
        val outcome =
            try {
                store.updateAutoCommentRunStage(runId, AutoCommentRunStages.WAITING_MODEL)
                // 完整人设与旧单发路径同源（combinePrompts(CHAT)），随任务 prompt 与
                // get_constraints 进入子代理上下文，而不是只给角色名。
                val rolePrompt = modelGateway.resolveAutoCommentRolePrompt(persona.roleCardId)
                subagentCoordinator.runGeneration(
                    runId = runId,
                    trigger = trigger,
                    runtime = runtime,
                    bookId = initialState.book.id,
                    bookName = initialState.book.name,
                    chapterIndex = nextChapterIndex,
                    chapterTitle = content.chapterTitle,
                    contentHash = content.contractHash,
                    persona = persona,
                    rolePrompt = rolePrompt,
                    targetContent = content.content,
                    previousContext = previousContext,
                )
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "model_subagent_turn",
                    status = "failed",
                    startedAt = modelTraceStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("trigger", trigger)
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
                throw error
            }
        store.updateAutoCommentRunExecution(runId, outcome.execution)
        store.updateAutoCommentRunPromptMetrics(
            runId = runId,
            targetCharacterCount =
                content.content.count { character -> !character.isWhitespace() },
            metrics =
                AutoCommentPromptMetrics(
                    previousContextChapterCount = previousContext.size,
                    previousContextCharacterCount =
                        previousContext.sumOf { it.content.trim().length },
                    contextWindowTokens = 0,
                    estimatedInputTokens =
                        (
                            content.content.trim().length +
                                previousContext.sumOf { it.content.trim().length }
                            ) / 2,
                ),
        )
        store.updateAutoCommentRunStage(runId, AutoCommentRunStages.VALIDATING_RESPONSE)
        store.recordAutoCommentRunTrace(
            runId = runId,
            operation = "model_subagent_turn",
            status = "completed",
            startedAt = modelTraceStartedAt,
            finishedAt = System.currentTimeMillis(),
            metadataJson = modelTraceMetadata(
                execution = outcome.execution,
                usage = null,
                estimatedInputTokens = store.getAutoCommentRun(runId)?.estimatedInputTokens,
            )
                .put("trigger", trigger)
                .put("subagentRunId", outcome.subagentRunId)
                .put("childChatId", outcome.childChatId)
                .put("candidateCount", outcome.comments.size)
                .put("abstained", outcome.abstained)
                .toString(),
        )
        return GeneratedAutoComments(
            comments = outcome.comments,
            execution = outcome.execution,
            usage = null,
        )
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

    suspend fun history(limit: Int = 10): JSONObject {
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

    suspend fun detail(runId: Long): JSONObject {
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

    /**
     * 列出审计隐藏聊天（hiddenReason 为 READING_COMPANION_AUDIT_ 前缀），按书分组返回
     * 根与 run 子聊天摘要；只含摘要字段，不含任何正文。对话内（isHidden=false）child
     * 不在此列。bookId 为空时列出全部书。
     */
    suspend fun listAuditChats(bookId: String? = null): JSONObject {
        val hiddenChats =
            com.ai.assistance.operit.data.db.AppDatabase
                .getDatabase(appContext)
                .chatDao()
                .getHiddenChatsDirectly()
        val rootPrefix = ReadingCompanionAudit.HIDDEN_ROOT_PREFIX
        val runPrefix = ReadingCompanionAudit.HIDDEN_RUN_PREFIX
        val groups = LinkedHashMap<String, JSONObject>()
        hiddenChats
            .filter { chat -> chat.hiddenReason?.startsWith(rootPrefix) == true }
            .forEach { root ->
                val groupBookId = root.hiddenReason.orEmpty().removePrefix(rootPrefix)
                if (bookId != null && groupBookId != bookId) return@forEach
                groups[groupBookId] =
                    JSONObject()
                        .put("bookId", groupBookId)
                        .put("bookName", JSONObject.NULL)
                        .put(
                            "root",
                            JSONObject()
                                .put("chatId", root.id)
                                .put("title", root.title),
                        )
                        .put("chats", JSONArray())
            }
        hiddenChats
            .filter { chat -> chat.hiddenReason?.startsWith(runPrefix) == true }
            .forEach { child ->
                val runId =
                    child.hiddenReason.orEmpty().removePrefix(runPrefix).toLongOrNull()
                        ?: return@forEach
                val run = store.getAutoCommentRun(runId) ?: return@forEach
                val childBookId = run.bookId
                if (bookId != null && childBookId != bookId) return@forEach
                val group =
                    groups.getOrPut(childBookId.orEmpty()) {
                        JSONObject()
                            .put("bookId", childBookId)
                            .put("bookName", run.bookName)
                            .put("root", JSONObject.NULL)
                            .put("chats", JSONArray())
                    }
                if (!group.has("bookName") || group.isNull("bookName")) {
                    group.put("bookName", run.bookName)
                }
                group
                    .getJSONArray("chats")
                    .put(
                        JSONObject()
                            .put("chatId", child.id)
                            .put("title", child.title)
                            .put("runId", runId)
                            .put("status", run.status)
                            .put("trigger", run.trigger),
                    )
            }
        return JSONObject().put("groups", JSONArray(groups.values.toList()))
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
        put("executionMode", executionMode)
        put("parentChatId", parentChatId)
        put("childChatId", childChatId)
        put("subagentRunId", subagentRunId)
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

    private suspend fun settleInterruptedRuns() {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
        // 剪枝联动与 status/history/detail 同一入口排空（fail-safe）。
        runCatching { store.flushPrunedRunChatCleanup() }
        // 挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
        runCatching { store.runOrphanChatCleanup() }
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
        private const val STATUS_INTERRUPTED = "interrupted"
        private const val STATUS_NO_VALID_COMMENTS = "no_valid_comments"
        const val ALREADY_GENERATING_QUEUED_AT = -1L
        const val TRIGGER_BACKGROUND = "background"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_CONVERSATION = ReadingCompanionAudit.TRIGGER_CONVERSATION

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
            store.interruptStaleAutoCommentRuns(
                staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
            )
            val latestRun = store.getRecentAutoCommentRuns(1).firstOrNull()
            if (
                AutoCommentSupport.shouldRejectManualEnqueue(
                    latestRunStatus = latestRun?.status,
                    activeGenerationRunning = store.hasActiveAutoCommentGeneration(
                        GENERATING_STALE_AFTER_MS,
                    ),
                )
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
            // 手动/后台共用：Worker 开头先做跨库对账（只 interrupt 孤儿 + 补链，不做重启式
            // 清扫，避免误杀同一进程内正在 heartbeat 的其他生成）。
            val store = ReadingCompanionStore(applicationContext)
            store.reconcileCrossDatabase()
            // 剪枝挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
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
            val store = ReadingCompanionStore(applicationContext)
            // Worker 开头跨库对账（只 interrupt 孤儿 + 补链，不清扫；进程重启语义由启动时的
            // reconcileAfterProcessStart 负责，这里不再重复清扫，避免误杀并发中的对话内 run）。
            store.reconcileCrossDatabase()
            // 剪枝挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
            // 恢复谱系：本次后台任务是上一次被中断 run 的重启，restarted_from_run_id 只作
            // 谱系记录，绝不续写旧 child（第二、三次追更迭代不重复标注旧 run）。
            val restartedFromRunId =
                store
                    .getRecentAutoCommentRuns(READING_RECENT_RUN_SCAN)
                    .firstOrNull {
                        it.status ==
                            ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_INTERRUPTED
                    }
                    ?.id
            val commentary = ReadingCompanionAutoCommentary.getInstance(applicationContext)
            repeat(MAX_CATCH_UP_GENERATIONS) { index ->
                if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
                    return Result.success()
                }
                val generated = commentary.generateNextChapter(
                    trigger = ReadingCompanionAutoCommentary.TRIGGER_BACKGROUND,
                    restartedFromRunId = if (index == 0) restartedFromRunId else null,
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
        /** 谱系扫描窗口（与 prune 保留量一致即可，只取最近一条 interrupted）。 */
        const val READING_RECENT_RUN_SCAN = 10
    }
}
