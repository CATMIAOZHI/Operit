package com.ai.assistance.operit.features.reading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class ReadingRefreshResult(
    val state: ReadingState,
    val indexedChapters: Int,
    val remainingCompletedChapters: Int,
    val summarizedChapters: Int,
    val remainingKnowledgeChapters: Int,
    val currentChapterIndexedUntil: Int?,
)

class ReadingCompanionService private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)

    suspend fun listBooks(): JSONObject {
        val selectedId = store.getSelectedBookId()
        val books = provider.getBooks().sortedByDescending(ReaderBook::lastReadAt)
        return JSONObject().apply {
            put("selectionMode", if (selectedId == null) "automatic_recent" else "manual")
            put("selectedBookId", selectedId)
            put(
                "books",
                JSONArray().apply {
                    books.forEach { book ->
                        put(
                            JSONObject()
                                .put("bookId", book.id)
                                .put("name", book.name)
                                .put("author", book.author)
                                .put("totalChapterCount", book.totalChapterCount)
                                .put("lastReadAt", book.lastReadAt)
                                .put("selected", book.id == selectedId)
                        )
                    }
                },
            )
        }
    }

    suspend fun selectBook(identifier: String?, automatic: Boolean): ReadingState {
        if (automatic) {
            store.setSelectedBookId(null)
            return provider.getReadingState()
        }
        val query = identifier.orEmpty().trim()
        require(query.isNotBlank()) { "请选择书籍名称或 book_id" }
        val books = provider.getBooks()
        val exactMatches = books.filter {
            it.id == query || it.name.equals(query, ignoreCase = true)
        }
        val matches = if (exactMatches.isNotEmpty()) exactMatches else books.filter {
            it.name.contains(query, ignoreCase = true)
        }
        require(matches.isNotEmpty()) { "书架中没有找到“$query”" }
        require(matches.size == 1) {
            "匹配到多本书，请改用完整书名或 book_id：${matches.take(5).joinToString { it.name }}"
        }
        val selected = matches.single()
        val state = provider.getReadingState(selected.id)
        store.setSelectedBookId(selected.id)
        synchronizeBoundary(state)
        return state
    }

    suspend fun currentBook(): ReadingState = selectedReadingState()

    suspend fun currentContext(maxCharacters: Int = 2600): JSONObject {
        val state = selectedReadingState()
        val content = provider.getReadableChapterContent(state.book.id, state.chapterIndex)
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
            put("boundary", "read_prefix_only")
        }
    }

    suspend fun refreshAndIndex(
        maxCompletedChapters: Int,
        maxKnowledgeChapters: Int,
        scheduleMore: Boolean,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
    ): ReadingRefreshResult = withContext(Dispatchers.IO) {
        val state = selectedReadingState()
        val chapters = provider.getChapters(state.book.id)
        synchronizeBoundary(state)

        var indexed = 0
        var currentIndexedUntil: Int? = null
        if (state.bodyPosition != null) {
            runCatching {
                provider.getReadableChapterContent(state.book.id, state.chapterIndex)
            }.getOrNull()?.let { current ->
                validateContentBeforeStore(state, current)
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
            validateContentBeforeStore(state, content)
            store.replaceChapter(content)
            indexed += 1
        }
        val remainingText = (missingCompleted.size - indexed).coerceAtLeast(0)

        var summarized = 0
        val knowledgeTargets = store.missingKnowledgeChapterIndices(
            bookId = state.book.id,
            throughChapterIndexExclusive = state.chapterIndex,
            limit = maxKnowledgeChapters.coerceAtLeast(0).coerceAtMost(8),
        )
        for (chapterIndex in knowledgeTargets) {
            val content = provider.getReadableChapterContent(state.book.id, chapterIndex)
            validateContentBeforeStore(state, content)
            store.replaceChapter(content)
            val generated = modelGateway.summarizeChapter(state.book, content, runtime)
            val latest = selectedReadingState(state.book.id)
            failIfBoundaryRegressed(state, latest)
            require(
                SpoilerGuard.isPositionAllowed(
                    content.chapterIndex,
                    0,
                    content.readableUntil,
                    latest,
                )
            ) { "章节摘要生成期间阅读边界发生变化" }
            store.storeKnowledge(content, generated.knowledge, generated.json)
            summarized += 1
        }
        val remainingKnowledge = store.countMissingKnowledge(
            state.book.id,
            state.chapterIndex,
        )
        val finalState = selectedReadingState(state.book.id)
        failIfBoundaryRegressed(state, finalState)
        synchronizeBoundary(finalState)
        if (scheduleMore && (remainingText > 0 || remainingKnowledge > 0)) {
            scheduleBackgroundIndex()
        }
        ReadingRefreshResult(
            state = state,
            indexedChapters = indexed,
            remainingCompletedChapters = remainingText,
            summarizedChapters = summarized,
            remainingKnowledgeChapters = remainingKnowledge,
            currentChapterIndexedUntil = currentIndexedUntil,
        )
    }

    suspend fun chapterSummary(
        chapterIndex: Int?,
        generateIfMissing: Boolean,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        val state = selectedReadingState()
        val targetIndex = chapterIndex ?: state.chapterIndex
        require(targetIndex in 0..state.chapterIndex) { "只能总结当前或已经读过的章节" }
        synchronizeBoundary(state)
        store.getChapterKnowledge(state.book.id, targetIndex)
            ?.takeIf { stored ->
                SpoilerGuard.isPositionAllowed(
                    stored.chapterIndex,
                    0,
                    stored.sourceEndPosition,
                    state,
                )
            }
            ?.let { return knowledgeJson(state, it, generatedNow = false) }
        if (!generateIfMissing) {
            return JSONObject()
                .put("book", state.book.name)
                .put("chapterIndex", targetIndex)
                .put("chapterNumber", targetIndex + 1)
                .put("status", "not_generated")
        }

        val content = provider.getReadableChapterContent(state.book.id, targetIndex)
        validateContentBeforeStore(state, content)
        store.replaceChapter(content)
        val generated = modelGateway.summarizeChapter(state.book, content, runtime)
        val latest = selectedReadingState(state.book.id)
        failIfBoundaryRegressed(state, latest)
        require(
            SpoilerGuard.isPositionAllowed(targetIndex, 0, content.readableUntil, latest)
        ) { "章节摘要生成期间阅读边界发生变化" }
        store.storeKnowledge(content, generated.knowledge, generated.json)
        return knowledgeJson(
            state = latest,
            stored = store.getChapterKnowledge(state.book.id, targetIndex)
                ?: error("章节摘要保存失败"),
            generatedNow = true,
        )
    }

    suspend fun recentSummaries(
        count: Int,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        val state = selectedReadingState()
        synchronizeBoundary(state)
        val safeCount = count.coerceIn(1, 10)
        var summaries = store.getRecentKnowledge(state.book.id, state.chapterIndex, safeCount)
            .filter { SpoilerGuard.isPositionAllowed(it.chapterIndex, 0, it.sourceEndPosition, state) }
        if (summaries.size < safeCount) {
            refreshAndIndex(
                maxCompletedChapters = safeCount,
                maxKnowledgeChapters = minOf(2, safeCount - summaries.size),
                scheduleMore = true,
                runtime = runtime,
            )
            val latest = selectedReadingState(state.book.id)
            summaries = store.getRecentKnowledge(state.book.id, latest.chapterIndex, safeCount)
                .filter {
                    SpoilerGuard.isPositionAllowed(it.chapterIndex, 0, it.sourceEndPosition, latest)
                }
        }
        return JSONObject().apply {
            put("book", state.book.name)
            put(
                "summaries",
                JSONArray().apply {
                    summaries.forEach { stored ->
                        put(
                            JSONObject()
                                .put("chapterIndex", stored.chapterIndex)
                                .put("chapterNumber", stored.chapterIndex + 1)
                                .put("chapterTitle", stored.chapterTitle)
                                .put("sourceEndPos", stored.sourceEndPosition)
                                .put("completeChapter", stored.isComplete)
                                .put("summary", stored.summary)
                        )
                    }
                },
            )
        }
    }

    suspend fun character(
        name: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        require(name.isNotBlank()) { "人物名称不能为空" }
        val state = selectedReadingState()
        synchronizeBoundary(state)
        var hits = store.getCharacterEvidence(state.book.id, name.trim(), 12)
            .filter { SpoilerGuard.isPositionAllowed(it.chapterIndex, 0, it.endPosition, state) }
        if (hits.isEmpty()) {
            refreshAndIndex(
                maxCompletedChapters = 5,
                maxKnowledgeChapters = 2,
                scheduleMore = true,
                runtime = runtime,
            )
            val latest = selectedReadingState(state.book.id)
            hits = store.getCharacterEvidence(latest.book.id, name.trim(), 12)
                .filter {
                    SpoilerGuard.isPositionAllowed(it.chapterIndex, 0, it.endPosition, latest)
                }
        }
        return JSONObject().apply {
            put("book", state.book.name)
            put("queryName", name)
            put(
                "evidence",
                JSONArray().apply { hits.forEach { put(hitJson(it)) } },
            )
            put("status", if (hits.isEmpty()) "not_found_in_structured_index" else "found")
        }
    }

    suspend fun addMemory(
        type: String,
        content: String,
        chapterIndex: Int?,
    ): JSONObject {
        val state = selectedReadingState()
        val targetChapter = chapterIndex ?: state.chapterIndex
        require(targetChapter in 0..state.chapterIndex) { "记忆不能绑定到尚未阅读的章节" }
        val normalizedType = type.trim().ifBlank { "note" }.take(40)
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "记忆内容不能为空" }
        require(normalizedContent.length <= 4000) { "单条记忆不能超过 4000 字" }
        synchronizeBoundary(state)
        val memory = store.addMemory(
            bookId = state.book.id,
            chapterIndex = targetChapter,
            type = normalizedType,
            content = normalizedContent,
        )
        return JSONObject()
            .put("id", memory.id)
            .put("book", state.book.name)
            .put("chapterIndex", memory.chapterIndex)
            .put("chapterNumber", memory.chapterIndex + 1)
            .put("type", memory.type)
            .put("content", memory.content)
            .put("createdAt", memory.createdAt)
            .put("source", "reader_memory")
    }

    suspend fun search(
        query: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        require(query.isNotBlank()) { "搜索问题不能为空" }
        var refresh = refreshAndIndex(
            maxCompletedChapters = 3,
            maxKnowledgeChapters = 0,
            scheduleMore = true,
            runtime = runtime,
        )
        val plan = runOptionalSearchModelStep {
            modelGateway.analyzeQuery(query, runtime)
        } ?: ReadingQueryPlan(
            intent = "综合",
            keywords = emptyList(),
            entities = emptyList(),
            timeHint = null,
        )
        val heuristicTerms = ReadingTextIndexSupport.extractQueryTerms(query)
        val terms = (plan.keywords + plan.entities + heuristicTerms)
            .flatMap(ReadingTextIndexSupport::extractQueryTerms)
            .distinct()
            .take(16)
        var searchState = selectedReadingState(refresh.state.book.id)
        synchronizeBoundary(searchState)
        var candidates = searchEvidence(searchState, terms)
        if (candidates.isEmpty()) {
            refresh = refreshAndIndex(
                maxCompletedChapters = 5,
                maxKnowledgeChapters = 0,
                scheduleMore = true,
                runtime = runtime,
            )
            searchState = selectedReadingState(searchState.book.id)
            synchronizeBoundary(searchState)
            candidates = searchEvidence(searchState, terms)
        }
        val memories = store.searchMemories(
            bookId = searchState.book.id,
            terms = terms,
            throughChapterIndex = searchState.chapterIndex,
            limit = 8,
        )

        val rerankState = selectedReadingState(searchState.book.id)
        failIfBoundaryRegressed(searchState, rerankState)
        synchronizeBoundary(rerankState)
        val guardedCandidates = candidates.filter { hit ->
            SpoilerGuard.isPositionAllowed(
                hit.chapterIndex,
                hit.startPosition,
                hit.endPosition,
                rerankState,
            )
        }.take(24)
        val rerankedIds = runOptionalSearchModelStep {
            modelGateway.rerank(query, guardedCandidates, runtime)
        }.orEmpty()
        val returnState = selectedReadingState(rerankState.book.id)
        failIfBoundaryRegressed(rerankState, returnState)
        synchronizeBoundary(returnState)
        val candidatesById = guardedCandidates.associateBy(ReadingSearchHit::id)
        val ordered = if (rerankedIds.isNotEmpty()) {
            rerankedIds.mapNotNull(candidatesById::get)
        } else {
            guardedCandidates.sortedByDescending(ReadingSearchHit::score)
        }
        val selected = ordered.filter { hit ->
            SpoilerGuard.isPositionAllowed(
                hit.chapterIndex,
                hit.startPosition,
                hit.endPosition,
                returnState,
            )
        }.take(8)

        return JSONObject().apply {
            put("book", returnState.book.name)
            put("query", query)
            put("intent", plan.intent)
            put("timeHint", plan.timeHint)
            put("keywords", JSONArray(terms))
            put("indexingRemainingChapters", refresh.remainingCompletedChapters)
            put("knowledgeRemainingChapters", refresh.remainingKnowledgeChapters)
            put(
                "results",
                JSONArray().apply { selected.forEach { put(hitJson(it)) } },
            )
            put(
                "readerMemories",
                JSONArray().apply {
                    memories.forEach { memory ->
                        put(
                            JSONObject()
                                .put("id", memory.id)
                                .put("chapterIndex", memory.chapterIndex)
                                .put("chapterNumber", memory.chapterIndex + 1)
                                .put("type", memory.type)
                                .put("content", memory.content)
                                .put("createdAt", memory.createdAt)
                                .put("source", "reader_memory")
                        )
                    }
                },
            )
            put(
                "memoryNotice",
                "readerMemories are the reader's own notes or predictions, not confirmed novel facts",
            )
        }
    }

    private fun searchEvidence(
        state: ReadingState,
        terms: List<String>,
    ): List<ReadingSearchHit> {
        val structured = store.searchKnowledge(
            state.book.id,
            terms,
            summaryOnly = false,
            limit = 30,
        )
        val summaries = store.searchKnowledge(
            state.book.id,
            terms,
            summaryOnly = true,
            limit = 20,
        )
        val fullText = store.searchText(state.book.id, terms, limit = 50)
        return (structured + summaries + fullText)
            .filter {
                SpoilerGuard.isPositionAllowed(
                    it.chapterIndex,
                    it.startPosition,
                    it.endPosition,
                    state,
                )
            }
            .distinctBy(ReadingSearchHit::id)
            .sortedWith(
                compareByDescending<ReadingSearchHit> { sourcePriority(it.source) }
                    .thenByDescending { it.score }
            )
            .take(30)
    }

    private fun sourcePriority(source: String): Int = when {
        source.startsWith("structured_") -> 3
        source == "chapter_summary" -> 2
        else -> 1
    }

    private suspend fun <T> runOptionalSearchModelStep(block: suspend () -> T): T? {
        return try {
            withTimeout(SEARCH_MODEL_STEP_TIMEOUT_MS) { block() }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun selectedReadingState(explicitBookId: String? = null): ReadingState {
        val selected = explicitBookId ?: store.getSelectedBookId()
        return provider.getReadingState(selected)
    }

    private suspend fun validateContentBeforeStore(
        previousState: ReadingState,
        content: ReadableChapterContent,
    ) {
        val latest = selectedReadingState(previousState.book.id)
        failIfBoundaryRegressed(previousState, latest)
        synchronizeBoundary(latest)
        require(
            SpoilerGuard.isPositionAllowed(
                content.chapterIndex,
                0,
                content.readableUntil,
                latest,
            )
        ) { "正文写入前阅读边界发生变化" }
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
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            createIndexRequest(),
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

    private fun knowledgeJson(
        state: ReadingState,
        stored: StoredChapterKnowledge,
        generatedNow: Boolean,
    ): JSONObject = JSONObject().apply {
        put("book", state.book.name)
        put("chapterIndex", stored.chapterIndex)
        put("chapterNumber", stored.chapterIndex + 1)
        put("chapterTitle", stored.chapterTitle)
        put("sourceEndPos", stored.sourceEndPosition)
        put("completeChapter", stored.isComplete)
        put("generatedNow", generatedNow)
        put("summary", stored.summary)
        put("knowledge", JSONObject(stored.structuredJson))
        put("updatedAt", stored.updatedAt)
    }

    private fun hitJson(hit: ReadingSearchHit): JSONObject = JSONObject().apply {
        put("id", hit.id)
        put("source", hit.source)
        put("entityName", hit.entityName)
        put("chapterIndex", hit.chapterIndex)
        put("chapterNumber", hit.chapterIndex + 1)
        put("chapterTitle", hit.chapterTitle)
        put("startPos", hit.startPosition)
        put("endPos", hit.endPosition)
        put("text", hit.text)
    }

    companion object {
        const val TOOLPKG_ID = "com.operit.reading_companion"
        const val SUBPACKAGE_NAME = "reading_companion"
        private const val INDEX_WORK_NAME = "reading_companion_incremental_index"
        private const val SEARCH_MODEL_STEP_TIMEOUT_MS = 8_000L

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
            val packageManager = PackageManager.getInstance(
                applicationContext,
                AIToolHandler.getInstance(applicationContext),
            )
            if (
                !packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID) ||
                !packageManager.isPackageEnabled(ReadingCompanionService.SUBPACKAGE_NAME)
            ) {
                return Result.success()
            }
            val service = ReadingCompanionService.getInstance(applicationContext)
            val refresh = service.refreshAndIndex(
                maxCompletedChapters = 8,
                maxKnowledgeChapters = 2,
                scheduleMore = false,
            )
            if (
                refresh.remainingCompletedChapters > 0 ||
                refresh.remainingKnowledgeChapters > 0
            ) {
                service.scheduleBackgroundIndexContinuation()
            }
            Result.success()
        } catch (_: ReaderProviderException) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
