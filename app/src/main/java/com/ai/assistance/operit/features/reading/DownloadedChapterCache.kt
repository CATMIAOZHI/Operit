package com.ai.assistance.operit.features.reading

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Sequential local-only copy. A missing download is a skipped chapter, never a reason to stop the scan. */
internal suspend fun cacheDownloadedChapterSnapshots(
    chapters: List<ReaderChapter>,
    hasCompleteSnapshot: (ReaderChapter) -> Boolean,
    readLocal: suspend (ReaderChapter) -> ReadableChapterContent?,
    save: suspend (ReaderChapter, ReadableChapterContent) -> Unit,
    onProgress: (JSONObject) -> Unit,
): JSONObject {
    var copied = 0
    var existing = 0
    var skipped = 0
    var processed = 0
    fun progress() = JSONObject().put("status", "running").put("totalCount", chapters.size)
        .put("processedCount", processed).put("completedCount", copied + existing)
        .put("copiedCount", copied).put("existingCount", existing).put("unavailableCount", skipped)
    onProgress(progress())
    for (chapter in chapters) {
        currentCoroutineContext().ensureActive()
        if (hasCompleteSnapshot(chapter)) existing++ else {
            val content = readLocal(chapter)
            currentCoroutineContext().ensureActive()
            if (content == null) skipped++ else {
                require(content.bookId == chapter.bookId && content.sourceId == chapter.sourceId && content.isComplete) {
                    "本地正文与目标旧章不一致"
                }
                save(chapter, content)
                copied++
            }
        }
        processed++
        onProgress(progress())
    }
    return progress().put("status", "completed")
}
