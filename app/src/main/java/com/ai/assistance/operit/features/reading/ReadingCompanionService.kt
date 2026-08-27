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

    suspend fun currentContext(
        maxCharacters: Int = DEFAULT_CONTEXT_CHARACTERS,
        callerRoleCardId: String? = null,
    ): JSONObject {
        val state = selectedReadingState()
        synchronizeBoundary(state)
        val content = provider.getReadableChapterContent(state.book.id, state.chapterIndex)
        val safeEnd = content.readableUntil.coerceIn(0, content.content.length)
        val requestedCharacters = maxCharacters.coerceIn(
            MIN_CONTEXT_CHARACTERS,
            MAX_CONTEXT_CHARACTERS,
        )
        // Reserve a bounded part of the requested budget for compact summaries, reader memories,
        // and the companion's own comments. The remaining budget is used for novel prose.
        val supplementalBudget = minOf(
            MAX_SUPPLEMENTAL_CONTEXT_CHARACTERS,
            requestedCharacters / 4,
        )
        val envelopeBudget = minOf(
            MAX_CONTEXT_ENVELOPE_CHARACTERS,
            requestedCharacters / 8,
        )
        val novelTextBudget =
            (requestedCharacters - supplementalBudget - envelopeBudget).coerceAtLeast(0)
        val currentTextBudget = minOf(CURRENT_CHAPTER_MAX_CHARACTERS, novelTextBudget)
        val start = (safeEnd - currentTextBudget).coerceAtLeast(0)
        val currentText = content.content.substring(start, safeEnd)
        val previousTextBudget = (novelTextBudget - currentText.length).coerceAtLeast(0)
        val previousChapters = loadPreviousContext(
            state = state,
            maximumCharacters = previousTextBudget,
        )

        // Re-read the boundary after provider calls. If the reader moved backwards or switched
        // books during assembly, fail closed instead of returning a mixed or unsafe context.
        val latestState = selectedReadingState(state.book.id)
        failIfBoundaryChanged(state, latestState)
        synchronizeBoundary(latestState)

        val completeTextChapterIndices = buildSet {
            if (start == 0) add(content.chapterIndex)
            previousChapters
                .filterNot(AutoCommentContextChapter::excerptFromEnd)
                .forEach { add(it.chapterIndex) }
        }
        val knowledge = store.getRecentKnowledge(
            bookId = state.book.id,
            throughChapterIndex = latestState.chapterIndex,
            limit = MAX_CONTEXT_CHAPTERS,
        ).filter { stored ->
            SpoilerGuard.isPositionAllowed(
                stored.chapterIndex,
                0,
                stored.sourceEndPosition,
                latestState,
            )
        }
        val contextSummaries = knowledge.mapNotNull { stored ->
            val summary = stored.summary.trim()
            if (summary.isBlank()) return@mapNotNull null
            JSONObject().apply {
                put("chapterIndex", stored.chapterIndex)
                put("chapterNumber", stored.chapterIndex + 1)
                put("chapterTitle", stored.chapterTitle)
                put("sourceEndPos", stored.sourceEndPosition)
                put("completeChapter", stored.isComplete)
                // A full prose excerpt already carries the chapter text. Keep structured facts,
                // while omitting a duplicate prose summary for that chapter.
                if (stored.chapterIndex !in completeTextChapterIndices) {
                    put("summary", summary)
                }
                put(
                    "summaryOmittedBecauseTextIncluded",
                    stored.chapterIndex in completeTextChapterIndices,
                )
                put("knowledge", compactKnowledgeJson(stored.structuredJson))
            }
        }.distinctBy { summary ->
            "${summary.optInt("chapterIndex")}:${summary.optString("summary")}:" +
                summary.optJSONObject("knowledge")?.toString().orEmpty()
        }

        val readerMemories = store.getRecentMemories(
            bookId = state.book.id,
            throughChapterIndex = latestState.chapterIndex,
            limit = MAX_CONTEXT_READER_MEMORIES,
        ).filter { memory ->
            memory.chapterIndex <= latestState.chapterIndex
        }
        val unlockedParagraphIndex =
            AutoCommentSupport.unlockedParagraphIndex(content.content, content.isComplete)
        val roleCardId = callerRoleCardId?.trim()?.takeIf(String::isNotBlank)
        val companionComments =
            if (roleCardId == null) {
                emptyList()
            } else {
                store.getRecentUnlockedAutoComments(
                    bookId = state.book.id,
                    currentChapterIndex = latestState.chapterIndex,
                    currentUnlockedParagraph = unlockedParagraphIndex,
                    roleCardId = roleCardId,
                    limit = MAX_CONTEXT_COMPANION_COMMENTS,
                )
            }

        val boundedSummaries = boundContextJson(
            entries = contextSummaries,
            maximumCharacters = supplementalBudget * SUMMARY_BUDGET_FRACTION / 100,
        ) { entry, remaining ->
            entry.put(
                "summary",
                entry.optString("summary").take(remaining.coerceAtLeast(0)),
            )
        }
        val boundedMemories = boundContextMemories(
            memories = readerMemories,
            maximumCharacters = supplementalBudget * MEMORY_BUDGET_FRACTION / 100,
        )
        val boundedComments = boundContextComments(
            comments = companionComments,
            maximumCharacters = supplementalBudget * COMMENT_BUDGET_FRACTION / 100,
        )
        val metadataCharacters =
            boundedSummaries.sumOf { it.toString().length } +
                boundedMemories.sumOf { it.content.length } +
                boundedComments.sumOf { it.text.length }
        val novelCharacters =
            currentText.length + previousChapters.sumOf { it.content.length }
        val totalCharacters = novelCharacters + metadataCharacters
        val returnState = selectedReadingState(state.book.id)
        failIfBoundaryChanged(latestState, returnState)
        synchronizeBoundary(returnState)

        val result = JSONObject().apply {
            put("book", returnState.book.name)
            put("author", returnState.book.author)
            put("chapterIndex", content.chapterIndex)
            put("chapterNumber", content.chapterIndex + 1)
            put("chapterTitle", content.chapterTitle)
            put("startPos", start)
            put("endPos", safeEnd)
            put("text", currentText)
            put(
                "previousChapters",
                JSONArray().apply {
                    previousChapters.forEach { chapter ->
                        put(
                            JSONObject()
                                .put("chapterIndex", chapter.chapterIndex)
                                .put("chapterNumber", chapter.chapterIndex + 1)
                                .put("chapterTitle", chapter.chapterTitle)
                                .put("text", chapter.content)
                                .put("excerptFromEnd", chapter.excerptFromEnd),
                        )
                    }
                },
            )
            put(
                "summaries",
                JSONArray().apply { boundedSummaries.forEach(::put) },
            )
            put(
                "readerMemories",
                JSONArray().apply {
                    boundedMemories.forEach { memory ->
                        put(
                            JSONObject()
                                .put("id", memory.id)
                                .put("chapterIndex", memory.chapterIndex)
                                .put("chapterNumber", memory.chapterIndex + 1)
                                .put("type", memory.type)
                                .put("content", memory.content)
                                .put("createdAt", memory.createdAt)
                                .put("source", "reader_memory")
                                .put("isNovelFact", false),
                        )
                    }
                },
            )
            put(
                "companionComments",
                JSONArray().apply {
                    boundedComments.forEach { comment ->
                        put(
                            comment.toCompanionCommentJson().apply {
                                put("source", "companion_comment")
                                put("isAiMemory", true)
                            },
                        )
                    }
                },
            )
            put(
                "memoryNotice",
                "readerMemories are reader-authored notes, reactions, questions, or predictions; " +
                    "they are not confirmed novel facts",
            )
            put(
                "companionMemoryNotice",
                "companionComments are this role's own unlocked AI commentary, not novel facts",
            )
            put("requestedCharacterBudget", requestedCharacters)
            put("contextCharacterCount", totalCharacters)
            put("novelCharacterCount", novelCharacters)
            put("metadataCharacterCount", metadataCharacters)
            put("previousChapterCount", previousChapters.size)
            put("summaryCount", boundedSummaries.size)
            put("readerMemoryCount", boundedMemories.size)
            put("companionCommentCount", boundedComments.size)
            put("capturedAt", content.capturedAt)
            put("boundary", "read_prefix_only")
        }
        return enforceSerializedContextBudget(result, requestedCharacters)
    }

    private suspend fun loadPreviousContext(
        state: ReadingState,
        maximumCharacters: Int,
    ): List<AutoCommentContextChapter> {
        if (maximumCharacters <= 0 || state.chapterIndex <= 0) return emptyList()
        val chaptersNearestFirst = buildList {
            val firstChapter = maxOf(0, state.chapterIndex - MAX_CONTEXT_CHAPTERS)
            for (chapterIndex in (state.chapterIndex - 1) downTo firstChapter) {
                val chapter = try {
                    provider.getReadableChapterContent(state.book.id, chapterIndex)
                } catch (_: ReaderProviderException) {
                    continue
                }
                // The provider is expected to enforce this identity and boundary itself. Keep a
                // second local check here so a malformed provider response cannot enter context.
                if (
                    chapter.bookId != state.book.id ||
                    chapter.chapterIndex != chapterIndex ||
                    !chapter.isComplete
                ) {
                    continue
                }
                val safeEnd = chapter.readableUntil.coerceIn(0, chapter.content.length)
                val safeText = chapter.content.substring(0, safeEnd)
                if (safeText.isBlank()) continue
                add(
                    AnnotationChapterContent(
                        bookId = chapter.bookId,
                        chapterIndex = chapter.chapterIndex,
                        chapterTitle = chapter.chapterTitle,
                        content = safeText,
                        contractHash = "",
                        capturedAt = chapter.capturedAt,
                    ),
                )
            }
        }
        return AutoCommentSupport.selectPreviousContext(
            chaptersNearestFirst = chaptersNearestFirst,
            maximumCharacters = maximumCharacters,
        )
    }

    private fun compactKnowledgeJson(rawJson: String): JSONObject {
        val knowledge = runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }
        // The summary is carried in its own field so the structured object does not repeat prose.
        knowledge.remove("summary")
        return knowledge
    }

    private fun boundContextJson(
        entries: List<JSONObject>,
        maximumCharacters: Int,
        trim: (JSONObject, Int) -> Unit,
    ): List<JSONObject> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<JSONObject>()
        entries.forEach { original ->
            if (remaining <= 0) return@forEach
            val entry = JSONObject(original.toString())
            val serializedLength = entry.toString().length
            if (serializedLength <= remaining) {
                bounded += entry
                remaining -= serializedLength
                return@forEach
            }
            trim(entry, remaining)
            val trimmedLength = entry.toString().length
            if (trimmedLength <= remaining && trimmedLength > 0) {
                bounded += entry
                remaining -= trimmedLength
            }
        }
        return bounded
    }

    private fun boundContextMemories(
        memories: List<ReaderMemory>,
        maximumCharacters: Int,
    ): List<ReaderMemory> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<ReaderMemory>()
        memories.forEach { memory ->
            if (remaining <= 0) return@forEach
            val content = memory.content.take(remaining)
            if (content.isBlank()) return@forEach
            bounded += memory.copy(content = content)
            remaining -= content.length
        }
        return bounded
    }

    private fun boundContextComments(
        comments: List<AutoCommentRecord>,
        maximumCharacters: Int,
    ): List<AutoCommentRecord> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<AutoCommentRecord>()
        comments.forEach { comment ->
            if (remaining <= 0) return@forEach
            val text = comment.text.take(remaining)
            if (text.isBlank()) return@forEach
            bounded += comment.copy(text = text)
            remaining -= text.length
        }
        return bounded
    }

    private fun enforceSerializedContextBudget(
        payload: JSONObject,
        maximumCharacters: Int,
    ): JSONObject {
        fun refreshCounts() {
            val previous = payload.optJSONArray("previousChapters") ?: JSONArray()
            val summaries = payload.optJSONArray("summaries") ?: JSONArray()
            val memories = payload.optJSONArray("readerMemories") ?: JSONArray()
            val comments = payload.optJSONArray("companionComments") ?: JSONArray()
            val novelCharacters =
                payload.optString("text").length +
                    (0 until previous.length()).sumOf { index ->
                        previous.optJSONObject(index)?.optString("text").orEmpty().length
                    }
            val metadataCharacters =
                summaries.toString().length +
                    memories.toString().length +
                    comments.toString().length
            payload.put("novelCharacterCount", novelCharacters)
            payload.put("metadataCharacterCount", metadataCharacters)
            payload.put("previousChapterCount", previous.length())
            payload.put("summaryCount", summaries.length())
            payload.put("readerMemoryCount", memories.length())
            payload.put("companionCommentCount", comments.length())
            payload.put("contextCharacterCount", 0)
            repeat(3) {
                payload.put("contextCharacterCount", payload.toString().length)
            }
        }

        fun currentLength(): Int {
            refreshCounts()
            return payload.toString().length
        }

        var serializedLength = currentLength()
        val optionalArrays = listOf("summaries", "readerMemories", "companionComments")
        optionalArrays.forEach { key ->
            val array = payload.optJSONArray(key) ?: return@forEach
            while (serializedLength > maximumCharacters && array.length() > 0) {
                array.remove(array.length() - 1)
                serializedLength = currentLength()
            }
        }

        val previous = payload.optJSONArray("previousChapters") ?: JSONArray()
        while (serializedLength > maximumCharacters && previous.length() > 0) {
            val oldest = previous.optJSONObject(0)
            val text = oldest?.optString("text").orEmpty()
            val overage = (serializedLength - maximumCharacters).coerceAtLeast(1)
            if (oldest != null && text.length > 256) {
                val removeCount = minOf(text.length - 128, overage + 64)
                oldest.put("text", text.drop(removeCount))
                oldest.put("excerptFromEnd", true)
            } else {
                previous.remove(0)
            }
            serializedLength = currentLength()
        }

        while (serializedLength > maximumCharacters) {
            val text = payload.optString("text")
            if (text.length <= 128) break
            val overage = (serializedLength - maximumCharacters).coerceAtLeast(1)
            val removeCount = minOf(text.length - 128, overage + 64)
            payload.put("text", text.drop(removeCount))
            payload.put("startPos", payload.optInt("startPos") + removeCount)
            serializedLength = currentLength()
        }
        if (serializedLength > maximumCharacters) {
            payload.remove("memoryNotice")
            payload.remove("companionMemoryNotice")
            currentLength()
        }
        return payload
    }

    suspend fun recentCompanionComments(
        limit: Int,
        callerRoleCardId: String?,
    ): JSONObject {
        val state = selectedReadingState()
        val content = provider.getReadableChapterContent(state.book.id, state.chapterIndex)
        val unlockedParagraphIndex =
            AutoCommentSupport.unlockedParagraphIndex(content.content, content.isComplete)
        val roleCardId = callerRoleCardId?.trim()?.takeIf(String::isNotBlank)
        val comments = store.getRecentUnlockedAutoComments(
            bookId = state.book.id,
            currentChapterIndex = state.chapterIndex,
            currentUnlockedParagraph = unlockedParagraphIndex,
            roleCardId = roleCardId,
            limit = limit,
        )
        return JSONObject().apply {
            put("book", state.book.name)
            put("roleCardId", roleCardId)
            put("boundary", "unlocked_comments_only")
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment -> put(comment.toCompanionCommentJson()) }
                },
            )
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

    private fun failIfBoundaryChanged(previous: ReadingState, latest: ReadingState) {
        val unchanged =
            previous.book.id == latest.book.id &&
                previous.chapterIndex == latest.chapterIndex &&
                previous.layoutPosition == latest.layoutPosition &&
                previous.bodyPosition == latest.bodyPosition
        if (unchanged) return
        synchronizeBoundary(latest)
        throw ReaderProviderException(
            ReaderProviderException.Reason.UNSAFE_POSITION,
            "阅读进度在上下文组装期间发生变化，请重试",
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

    private fun AutoCommentRecord.toCompanionCommentJson(): JSONObject = JSONObject().apply {
        put("authorRoleCardId", roleCardId)
        put("authorName", roleCardName)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex + 1)
        put("paragraphIndex", paragraphIndex)
        put("text", text)
        put("kind", kind)
        put("createdAt", createdAt)
    }

    companion object {
        const val TOOLPKG_ID = "com.operit.reading_companion"
        const val SUBPACKAGE_NAME = "reading_companion"
        const val AUTO_COMMENTARY_SUBPACKAGE_NAME = "reading_companion_auto_commentary"
        private const val DEFAULT_CONTEXT_CHARACTERS = 32_000
        private const val MIN_CONTEXT_CHARACTERS = 32_000
        private const val MAX_CONTEXT_CHARACTERS = 96_000
        private const val CURRENT_CHAPTER_MAX_CHARACTERS = 6_000
        private const val MAX_SUPPLEMENTAL_CONTEXT_CHARACTERS = 8_000
        private const val MAX_CONTEXT_ENVELOPE_CHARACTERS = 4_000
        private const val MAX_CONTEXT_CHAPTERS = 8
        private const val MAX_CONTEXT_READER_MEMORIES = 24
        private const val MAX_CONTEXT_COMPANION_COMMENTS = 24
        private const val SUMMARY_BUDGET_FRACTION = 50
        private const val MEMORY_BUDGET_FRACTION = 30
        private const val COMMENT_BUDGET_FRACTION = 20
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
