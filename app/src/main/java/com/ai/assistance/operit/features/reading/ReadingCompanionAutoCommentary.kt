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
        val initialState = provider.getReadingState()
        store.updateBook(initialState)
        store.prepareForState(initialState)
        val nextChapterIndex = initialState.chapterIndex + 1
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
        )
        val chapterIndices = provider.getChapters(initialState.book.id)
            .mapTo(hashSetOf(), ReaderChapter::index)
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
        val content = provider.getAnnotationChapterContent(
            initialState.book.id,
            nextChapterIndex,
        )
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
            val previousContext = loadPreviousCommentaryContext(
                bookId = initialState.book.id,
                targetChapterIndex = nextChapterIndex,
                availableChapterIndices = chapterIndices,
            )
            val generated = modelGateway.generateAutoComments(
                content = content,
                previousContext = previousContext,
                roleCardId = persona.roleCardId,
                runtime = runtime,
                onExecutionResolved = { execution ->
                    store.updateAutoCommentRunExecution(runId, execution)
                },
            )
            if (trigger == TRIGGER_BACKGROUND && !isEnabled(appContext)) {
                throw CancellationException("AI 自动段评已关闭")
            }
            val latestState = provider.getReadingState()
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
            val stored = store.replaceAutoComments(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                chapterTitle = content.chapterTitle,
                contentHash = contentHash,
                roleCardId = requireNotNull(generated.execution.roleCardId),
                roleCardName = requireNotNull(generated.execution.roleCardName),
                generationRunId = runId,
                comments = records,
            )
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
            notifyAnnotationChanged()
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATED,
                commentCount = records.size,
            )
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

    fun status(): JSONObject {
        settleInterruptedRuns()
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
            put("historyStorage", "reading_companion.db:auto_comment_runs")
            put("latestRun", store.getRecentAutoCommentRuns(1).firstOrNull()?.toJson())
        }
    }

    fun history(limit: Int = 10): JSONObject {
        settleInterruptedRuns()
        return JSONObject().apply {
            put("storage", "reading_companion.db:auto_comment_runs")
            put(
                "runs",
                JSONArray().apply {
                    store.getRecentAutoCommentRuns(limit).forEach { run -> put(run.toJson()) }
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
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex?.plus(1))
        put("trigger", trigger)
        put("status", status)
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("modelConfigId", modelConfigId)
        put("modelConfigName", modelConfigName)
        put("modelIndex", modelIndex)
        put("provider", provider)
        put("model", model)
        put("commentCount", commentCount)
        put("error", errorMessage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
    }

    private fun settleInterruptedRuns() {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
    }

    companion object {
        const val AUTO_COMMENT_WORK_NAME = "reading_companion_auto_commentary"
        private const val AUTO_COMMENT_DELAY_SECONDS = 20L
        private const val GENERATING_STALE_AFTER_MS = 5 * 60_000L
        private const val STATUS_GENERATED = "generated"
        private const val STATUS_CACHED = "cached"
        private const val STATUS_ALREADY_GENERATING = "already_generating"
        private const val STATUS_NO_NEXT_CHAPTER = "no_next_chapter"
        private const val STATUS_SUPERSEDED = "superseded"
        const val TRIGGER_BACKGROUND = "background"
        const val TRIGGER_MANUAL = "manual"

        @Volatile
        private var instance: ReadingCompanionAutoCommentary? = null

        fun getInstance(context: Context): ReadingCompanionAutoCommentary =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionAutoCommentary(context).also { instance = it }
            }

        fun isEnabled(context: Context): Boolean {
            val packageManager = PackageManager.getInstance(
                context.applicationContext,
                AIToolHandler.getInstance(context.applicationContext),
            )
            return packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID) &&
                packageManager.isPackageEnabled(
                    ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME,
                )
        }

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
