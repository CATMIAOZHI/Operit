package com.ai.assistance.operit.features.reading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ReadingRefreshResult(
    val state: ReadingState,
    val indexedChapters: Int,
    val remainingCompletedChapters: Int,
    val currentChapterIndexedUntil: Int?,
)

class ReadingCompanionService private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)

    suspend fun currentBook(): ReadingState = provider.getReadingState()

    suspend fun currentContext(maxCharacters: Int = 2600): JSONObject {
        val state = provider.getReadingState()
        val content = provider.getReadableChapterContent(
            state.book.id,
            state.chapterIndex,
        )
        val safeEnd = content.readableUntil.coerceIn(0, content.content.length)
        val start = (safeEnd - maxCharacters.coerceIn(400, 6000)).coerceAtLeast(0)
        return JSONObject().apply {
            put("book", state.book.name)
            put("author", state.book.author)
            put("chapterIndex", content.chapterIndex)
            put("chapterNumber", content.chapterIndex + 1)
            put("chapterTitle", content.chapterTitle)
            put("startPos", start)
            put("endPos", safeEnd)
            put("text", content.content.substring(start, safeEnd))
            put("capturedAt", content.capturedAt)
        }
    }

    suspend fun refreshAndIndex(
        maxCompletedChapters: Int,
        scheduleMore: Boolean,
    ): ReadingRefreshResult = withContext(Dispatchers.IO) {
        val state = provider.getReadingState()
        val chapters = provider.getChapters(state.book.id)
        store.updateBook(state)
        store.prepareForState(state)

        var indexed = 0
        var currentIndexedUntil: Int? = null
        if (state.bodyPosition != null) {
            runCatching {
                provider.getReadableChapterContent(state.book.id, state.chapterIndex)
            }.getOrNull()?.let { current ->
                store.replaceChapter(current)
                currentIndexedUntil = current.readableUntil
            }
        }

        val missingCompleted = chapters
            .asSequence()
            .filter { it.index < state.chapterIndex }
            .filterNot { store.isCompleteChapterIndexed(state.book.id, it.index) }
            .sortedByDescending(ReaderChapter::index)
            .toList()
        for (chapter in missingCompleted.take(maxCompletedChapters.coerceAtLeast(0))) {
            val content = provider.getReadableChapterContent(state.book.id, chapter.index)
            store.replaceChapter(content)
            indexed += 1
        }
        val remaining = (missingCompleted.size - indexed).coerceAtLeast(0)
        if (scheduleMore && remaining > 0) {
            scheduleBackgroundIndex()
        }
        ReadingRefreshResult(
            state = state,
            indexedChapters = indexed,
            remainingCompletedChapters = remaining,
            currentChapterIndexedUntil = currentIndexedUntil,
        )
    }

    suspend fun search(
        query: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        require(query.isNotBlank()) { "搜索问题不能为空" }
        val refresh = refreshAndIndex(maxCompletedChapters = 3, scheduleMore = true)
        val heuristicTerms = ReadingTextIndexSupport.extractQueryTerms(query)
        val expandedTerms = runCatching {
            modelGateway.expandQuery(query, runtime)
        }.getOrDefault(emptyList())
        val terms = (expandedTerms + heuristicTerms)
            .flatMap(ReadingTextIndexSupport::extractQueryTerms)
            .distinct()
            .take(16)
        val searchState = provider.getReadingState(refresh.state.book.id)
        synchronizeBoundary(searchState)
        val initiallyGuarded = store.search(searchState.book.id, terms, limit = 50)
            .filter { hit ->
                SpoilerGuard.isPositionAllowed(
                    chapterIndex = hit.chapterIndex,
                    startPosition = hit.startPosition,
                    endPosition = hit.endPosition,
                    readingState = searchState,
                )
            }
            .take(20)
        val rerankState = provider.getReadingState(searchState.book.id)
        failIfBoundaryRegressed(searchState, rerankState)
        synchronizeBoundary(rerankState)
        val guardedCandidates = initiallyGuarded.filter { hit ->
            SpoilerGuard.isPositionAllowed(
                chapterIndex = hit.chapterIndex,
                startPosition = hit.startPosition,
                endPosition = hit.endPosition,
                readingState = rerankState,
            )
        }
        val rerankedIds = runCatching {
            modelGateway.rerank(query, guardedCandidates, runtime)
        }.getOrDefault(emptyList())
        val returnState = provider.getReadingState(rerankState.book.id)
        failIfBoundaryRegressed(rerankState, returnState)
        synchronizeBoundary(returnState)
        val candidatesById = guardedCandidates.associateBy(ReadingSearchHit::id)
        val selectedBeforeFinalGuard = if (rerankedIds.isNotEmpty()) {
            rerankedIds.mapNotNull(candidatesById::get)
        } else {
            guardedCandidates.take(8)
        }
        val selected = selectedBeforeFinalGuard.filter { hit ->
            SpoilerGuard.isPositionAllowed(
                chapterIndex = hit.chapterIndex,
                startPosition = hit.startPosition,
                endPosition = hit.endPosition,
                readingState = returnState,
            )
        }.take(8)
        return JSONObject().apply {
            put("book", returnState.book.name)
            put("query", query)
            put("keywords", JSONArray(terms))
            put("indexingRemainingChapters", refresh.remainingCompletedChapters)
            put(
                "results",
                JSONArray().apply {
                    selected.forEach { hit ->
                        put(
                            JSONObject().apply {
                                put("id", hit.id)
                                put("chapterIndex", hit.chapterIndex)
                                put("chapterNumber", hit.chapterIndex + 1)
                                put("chapterTitle", hit.chapterTitle)
                                put("startPos", hit.startPosition)
                                put("endPos", hit.endPosition)
                                put("text", hit.text)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun synchronizeBoundary(state: ReadingState) {
        store.updateBook(state)
        store.prepareForState(state)
    }

    private fun failIfBoundaryRegressed(previous: ReadingState, latest: ReadingState) {
        if (!ReadingBoundaryGuard.hasRegressed(previous, latest)) return
        synchronizeBoundary(latest)
        throw ReaderProviderException(
            ReaderProviderException.Reason.UNSAFE_POSITION,
            "阅读进度在检索期间发生回退，已清除越界索引，请重试",
        )
    }

    fun scheduleBackgroundIndex() {
        val request = createIndexRequest()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal fun scheduleBackgroundIndexContinuation() {
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            createIndexRequest(),
        )
    }

    private fun createIndexRequest() =
        OneTimeWorkRequestBuilder<ReadingCompanionIndexWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()

    companion object {
        private const val INDEX_WORK_NAME = "reading_companion_incremental_index"

        @Volatile
        private var instance: ReadingCompanionService? = null

        fun getInstance(context: Context): ReadingCompanionService =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionService(context).also { instance = it }
            }
    }
}

class ReadingCompanionIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val refresh = ReadingCompanionService.getInstance(applicationContext)
                .refreshAndIndex(maxCompletedChapters = 8, scheduleMore = false)
            if (refresh.remainingCompletedChapters > 0) {
                ReadingCompanionService.getInstance(applicationContext)
                    .scheduleBackgroundIndexContinuation()
            }
            Result.success()
        } catch (_: ReaderProviderException) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
