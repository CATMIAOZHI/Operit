package com.ai.assistance.operit.features.reading

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Native data/index bridge used by the Reading Companion ToolPkg.
 *
 * It deliberately registers no AI tool and no prompt hook. The user-facing tools and policy exist
 * only while the ToolPkg is enabled, so disabling the package removes the whole AI surface.
 */
object ReadingCompanionBridge {
    private const val TAG = "ReadingCompanion"

    @JvmStatic
    suspend fun execute(
        context: Context,
        callerPackageName: String,
        action: String,
        parametersJson: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): String {
        return try {
            require(
                callerPackageName == ReadingCompanionService.SUBPACKAGE_NAME ||
                    callerPackageName == ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
            ) {
                "阅读伴侣原生桥仅允许对应工具包调用"
            }
            val packageManager = PackageManager.getInstance(
                context.applicationContext,
                AIToolHandler.getInstance(context.applicationContext),
            )
            require(
                packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID) &&
                    packageManager.isPackageEnabled(callerPackageName)
            ) { "AI 阅读伴侣工具包当前未启用" }
            val parameters = parametersJson
                .takeIf(String::isNotBlank)
                ?.let(::JSONObject)
                ?: JSONObject()
            val result = withContext(ToolExecutionManager.toolRuntimeContextElement(runtime)) {
                val service = ReadingCompanionService.getInstance(context)
                when (action.trim()) {
                    "list_books" -> service.listBooks()
                    "select_book" -> {
                        val automatic = parameters.optBoolean("automatic", false)
                        service.selectBook(parameters.optString("book"), automatic)
                            .toJson()
                    }
                    "get_current_book" -> service.currentBook().toJson()
                    "get_context" -> service.currentContext(
                        parameters.optInt("max_characters", 2600),
                    )
                    "search" -> service.search(
                        parameters.optString("query").trim(),
                        runtime,
                    )
                    "get_chapter_summary" -> service.chapterSummary(
                        chapterIndex = parameters.optNullableInt("chapter_index"),
                        generateIfMissing = parameters.optBoolean("generate_if_missing", true),
                        runtime = runtime,
                    )
                    "get_character" -> service.character(
                        parameters.optString("name").trim(),
                        runtime,
                    )
                    "get_recent_summaries" -> service.recentSummaries(
                        count = parameters.optInt("count", 5),
                        runtime = runtime,
                    )
                    "refresh_progress" -> service.refreshAndIndex(
                        maxCompletedChapters = parameters.optInt("max_chapters", 3)
                            .coerceIn(0, 20),
                        maxKnowledgeChapters = parameters.optInt("max_summaries", 1)
                            .coerceIn(0, 4),
                        scheduleMore = true,
                        runtime = runtime,
                    ).toJson()
                    "add_memory" -> service.addMemory(
                        type = parameters.optString("type", "note"),
                        content = parameters.optString("content"),
                        chapterIndex = parameters.optNullableInt("chapter_index"),
                    )
                    "auto_commentary_status" ->
                        ReadingCompanionAutoCommentary.getInstance(context).status()
                    "regenerate_next_chapter_comments" -> {
                        require(
                            callerPackageName ==
                                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
                        ) { "该操作仅允许自动段评子包调用" }
                        ReadingCompanionAutoCommentary.getInstance(context)
                            .generateNextChapter(force = true)
                            .toJson()
                    }
                    else -> throw IllegalArgumentException("未知的伴读操作：$action")
                }
            }
            JSONObject()
                .put("success", true)
                .put("data", result)
                .toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Reading companion bridge failed: $action", error)
            val message = when (error) {
                is ReaderProviderException,
                is IllegalArgumentException,
                is IllegalStateException -> error.message.orEmpty()
                else -> "伴读操作失败，请确认 Legado 已安装并打开过目标书籍"
            }.ifBlank { "伴读操作失败" }
            JSONObject()
                .put("success", false)
                .put("message", message)
                .put("action", action)
                .toString()
        }
    }

    private fun ReadingState.toJson(): JSONObject = JSONObject().apply {
        put("book", book.name)
        put("bookId", book.id)
        put("author", book.author)
        put("currentChapterIndex", chapterIndex)
        put("currentChapterNumber", chapterIndex + 1)
        put("currentChapterTitle", chapterTitle)
        put("currentBodyPosition", bodyPosition)
        put("preciseCurrentPositionAvailable", bodyPosition != null)
        put("totalChapterCount", book.totalChapterCount)
        put("lastReadAt", book.lastReadAt)
        put("capturedAt", capturedAt)
    }

    private fun ReadingRefreshResult.toJson(): JSONObject = JSONObject().apply {
        put("book", state.book.name)
        put("currentChapterIndex", state.chapterIndex)
        put("currentChapterNumber", state.chapterIndex + 1)
        put("currentChapterTitle", state.chapterTitle)
        put("currentBodyPosition", state.bodyPosition)
        put("indexedChaptersThisRun", indexedChapters)
        put("remainingCompletedChapters", remainingCompletedChapters)
        put("summarizedChaptersThisRun", summarizedChapters)
        put("remainingKnowledgeChapters", remainingKnowledgeChapters)
        put("currentChapterIndexedUntil", currentChapterIndexedUntil)
        put(
            "backgroundWorkScheduled",
            remainingCompletedChapters > 0 || remainingKnowledgeChapters > 0,
        )
    }

    private fun AutoCommentaryGenerationResult.toJson(): JSONObject = JSONObject().apply {
        put("bookId", bookId)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex?.plus(1))
        put("status", status)
        put("commentCount", commentCount)
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)
}
