package com.ai.assistance.operit.features.reading

data class ReaderBook(
    val id: String,
    val name: String,
    val author: String,
    val totalChapterCount: Int,
    val lastReadAt: Long,
)

data class ReaderChapter(
    val bookId: String,
    val index: Int,
    val title: String,
)

data class ReadingState(
    val book: ReaderBook,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val layoutPosition: Int,
    val bodyPosition: Int?,
    val capturedAt: Long,
)

data class ReadableChapterContent(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val readableUntil: Int,
    val isComplete: Boolean,
    val readingChapterIndex: Int,
    val capturedAt: Long,
)

data class AnnotationChapterContent(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val contractHash: String,
    val capturedAt: Long,
)

interface ReaderProvider {
    suspend fun getBooks(): List<ReaderBook>

    suspend fun getReadingState(bookId: String? = null): ReadingState

    suspend fun getChapters(bookId: String): List<ReaderChapter>

    suspend fun getReadableChapterContent(
        bookId: String,
        chapterIndex: Int,
    ): ReadableChapterContent

    /**
     * Reads a complete chapter only for the isolated auto-comment generator.
     *
     * Implementations must not route this content into ordinary search, summaries, memories, or
     * user-visible tools. The caller may read the next chapter plus bounded earlier chapters as
     * private story context, but must never read beyond the next chapter.
     */
    suspend fun getAnnotationChapterContent(
        bookId: String,
        chapterIndex: Int,
    ): AnnotationChapterContent
}

internal object SpoilerGuard {
    fun isPositionAllowed(
        chapterIndex: Int,
        startPosition: Int,
        endPosition: Int,
        readingState: ReadingState,
    ): Boolean {
        if (chapterIndex < 0 || startPosition < 0 || endPosition < startPosition) return false
        return when {
            chapterIndex < readingState.chapterIndex -> true
            chapterIndex > readingState.chapterIndex -> false
            else -> {
                val bodyPosition = readingState.bodyPosition ?: return false
                endPosition <= bodyPosition
            }
        }
    }
}

internal object ReadingBoundaryGuard {
    fun hasRegressed(previous: ReadingState, latest: ReadingState): Boolean {
        if (previous.book.id != latest.book.id) return true
        if (latest.chapterIndex != previous.chapterIndex) {
            return latest.chapterIndex < previous.chapterIndex
        }
        val previousPosition = previous.bodyPosition ?: return false
        val latestPosition = latest.bodyPosition ?: return true
        return latestPosition < previousPosition
    }
}

class ReaderProviderException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        LEGADO_NOT_INSTALLED,
        CONNECTION_FAILED,
        EMPTY_BOOKSHELF,
        NO_RECENT_BOOK,
        CHAPTER_READ_FAILED,
        UNSAFE_POSITION,
        INVALID_RESPONSE,
    }
}
