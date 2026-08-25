package com.ai.assistance.operit.features.reading

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class AutoCommentaryGenerationResult(
    val bookId: String,
    val chapterIndex: Int?,
    val status: String,
    val commentCount: Int,
)

class ReadingCompanionAutoCommentary private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)

    suspend fun generateNextChapter(force: Boolean = false): AutoCommentaryGenerationResult {
        val initialState = provider.getReadingState()
        store.updateBook(initialState)
        store.prepareForState(initialState)
        val nextChapterIndex = initialState.chapterIndex + 1
        val hasNextChapter = provider.getChapters(initialState.book.id)
            .any { chapter -> chapter.index == nextChapterIndex }
        if (!hasNextChapter) {
            return AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = null,
                status = STATUS_NO_NEXT_CHAPTER,
                commentCount = 0,
            )
        }

        val content = provider.getAnnotationChapterContent(
            initialState.book.id,
            nextChapterIndex,
        )
        val contentHash = content.contractHash
        val existing = store.getAutoCommentChapter(initialState.book.id, nextChapterIndex)
        if (!force && existing?.contentHash == contentHash) {
            if (existing.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_READY) {
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_CACHED,
                    commentCount = store.getAutoComments(initialState.book.id, nextChapterIndex).size,
                )
            }
            val age = System.currentTimeMillis() - existing.updatedAt
            if (
                existing.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_GENERATING &&
                age < GENERATING_STALE_AFTER_MS
            ) {
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_ALREADY_GENERATING,
                    commentCount = 0,
                )
            }
            if (
                existing.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_FAILED &&
                age < FAILED_RETRY_AFTER_MS
            ) {
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_RETRY_LATER,
                    commentCount = 0,
                )
            }
        }

        store.markAutoCommentGeneration(
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = content.chapterTitle,
            contentHash = contentHash,
            status = ReadingCompanionStore.AUTO_COMMENT_STATUS_GENERATING,
        )
        return try {
            val drafts = modelGateway.generateAutoComments(content)
            val latestState = provider.getReadingState()
            if (
                latestState.book.id != initialState.book.id ||
                nextChapterIndex > latestState.chapterIndex + 1
            ) {
                store.markAutoCommentGeneration(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    chapterTitle = content.chapterTitle,
                    contentHash = contentHash,
                    status = ReadingCompanionStore.AUTO_COMMENT_STATUS_FAILED,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_SUPERSEDED,
                    commentCount = 0,
                )
            }
            val records = drafts.map { draft ->
                AutoCommentRecord(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    paragraphIndex = draft.paragraphIndex,
                    text = draft.text,
                    kind = draft.kind,
                    evidenceJson = AutoCommentSupport.evidenceJson(draft),
                )
            }
            store.replaceAutoComments(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                chapterTitle = content.chapterTitle,
                contentHash = contentHash,
                comments = records,
            )
            notifyAnnotationChanged()
            AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                status = STATUS_GENERATED,
                commentCount = records.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            store.markAutoCommentGeneration(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                chapterTitle = content.chapterTitle,
                contentHash = contentHash,
                status = ReadingCompanionStore.AUTO_COMMENT_STATUS_FAILED,
            )
            throw error
        }
    }

    fun status(): JSONObject {
        return JSONObject().apply {
            put("enabled", isEnabled(appContext))
            put("mode", "whole_chapter_single_request")
            put("prefetchChapters", 1)
            put("selectiveSpoilerAudit", true)
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

    fun recentComments(bookId: String, chapterIndex: Int): JSONObject {
        val chapter = store.getAutoCommentChapter(bookId, chapterIndex)
        val comments = if (chapter?.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_READY) {
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

    companion object {
        const val AUTO_COMMENT_WORK_NAME = "reading_companion_auto_commentary"
        private const val AUTO_COMMENT_DELAY_SECONDS = 20L
        private const val GENERATING_STALE_AFTER_MS = 5 * 60_000L
        private const val FAILED_RETRY_AFTER_MS = 10 * 60_000L
        private const val STATUS_GENERATED = "generated"
        private const val STATUS_CACHED = "cached"
        private const val STATUS_ALREADY_GENERATING = "already_generating"
        private const val STATUS_RETRY_LATER = "retry_later"
        private const val STATUS_NO_NEXT_CHAPTER = "no_next_chapter"
        private const val STATUS_SUPERSEDED = "superseded"

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
                val generated = commentary.generateNextChapter()
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
