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
                        parameters.optInt("max_characters", 16_000),
                        runtime?.callerCardId,
                    )
                    "get_recent_comments" -> service.recentCompanionComments(
                        limit = parameters.optInt("limit", 20).coerceIn(1, 50),
                        callerRoleCardId = runtime?.callerCardId,
                    )
                    "search" -> service.search(
                        parameters.optString("query").trim(),
                        runtime,
                    )
                    "get_chapter_summary" -> service.chapterSummary(
                        chapterIndex = parameters.optNullableInt("chapter_index"),
                        // Summary generation is never implicit.  The only generation entrypoint
                        // is an explicit manual batch action below.
                        generateIfMissing = parameters.optBoolean("generate_if_missing", false),
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
                    "get_local_files" -> service.localBookFiles(runtime?.callerCardId)
                    "list_summary_files" -> service.persistedSummaryFiles()
                    "list_persisted_files" -> service.listPersistedFiles(
                        offset = parameters.optInt("offset", 0),
                        limit = parameters.optInt("limit", ReadingCompanionService.PERSISTED_FILES_DEFAULT_LIMIT),
                        callerRoleCardId = runtime?.callerCardId,
                    )
                    "read_persisted_file" -> service.readPersistedFile(
                        path = parameters.optString("path"),
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
                    "auto_commentary_history" ->
                        ReadingCompanionAutoCommentary.getInstance(context).history(
                            parameters.optInt("limit", 10).coerceIn(1, 50),
                        )
                    "auto_commentary_run_detail" ->
                        ReadingCompanionAutoCommentary.getInstance(context).detail(
                            parameters.optLong("runId", -1L),
                        )
                    "auto_commentary_get_config" -> {
                        val autoCommentary =
                            ReadingCompanionAutoCommentary.getInstance(context)
                        JSONObject().put(
                            "prefetchAheadChapters",
                            autoCommentary.prefetchAheadChapters(),
                        )
                    }
                    "auto_commentary_set_config" -> {
                        val autoCommentary =
                            ReadingCompanionAutoCommentary.getInstance(context)
                        JSONObject().put(
                            "prefetchAheadChapters",
                            autoCommentary.setPrefetchAheadChapters(
                                parameters.optInt(
                                    "prefetchAheadChapters",
                                    AutoCommentSupport.DEFAULT_PREFETCH_AHEAD_CHAPTERS,
                                ),
                            ),
                        )
                    }
                    "queue_regenerate_next_chapter_comments" -> {
                        require(
                            callerPackageName ==
                                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
                        ) { "该操作仅允许自动段评子包调用" }
                        val queuedAt =
                            ReadingCompanionAutoCommentary.enqueueManual(context)
                        val alreadyGenerating =
                            queuedAt == ReadingCompanionAutoCommentary.ALREADY_GENERATING_QUEUED_AT
                        JSONObject()
                            .put(
                                "queuedAt",
                                if (alreadyGenerating) {
                                    JSONObject.NULL
                                } else {
                                    queuedAt
                                },
                            )
                            .put(
                                "status",
                                if (alreadyGenerating) {
                                    "already_generating"
                                } else {
                                    "queued"
                                },
                            )
                    }
                    "regenerate_next_chapter_comments" -> {
                        require(
                            callerPackageName ==
                                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
                        ) { "该操作仅允许自动段评子包调用" }
                        val callerChatId = runtime?.callerChatId?.takeIf(String::isNotBlank)
                        // 对话内（包含插件自身聊天）走 conversation；无调用聊天或来自隐藏审计根
                        // 聊天则回退手动路径。JS 传入的 parentChatId 一律不被信任。
                        val conversation =
                            callerChatId != null &&
                                !isReadingCompanionAuditRootChat(context, callerChatId)
                        ReadingCompanionAutoCommentary.getInstance(context)
                            .generateNextChapter(
                                force = true,
                                runtime = runtime,
                                trigger =
                                    if (conversation) {
                                        ReadingCompanionAutoCommentary.TRIGGER_CONVERSATION
                                    } else {
                                        ReadingCompanionAutoCommentary.TRIGGER_MANUAL
                                    },
                            )
                            .toJson()
                    }
                    "request_next_chapter_comments" -> {
                        require(
                            callerPackageName == ReadingCompanionService.SUBPACKAGE_NAME
                        ) { "该操作仅允许阅读伴侣主包调用" }
                        require(
                            !runtime?.callerChatId.isNullOrBlank()
                        ) { "对话内段评生成需要当前聊天上下文" }
                        ReadingCompanionAutoCommentary.getInstance(context)
                            .generateNextChapter(
                                force = true,
                                runtime = runtime,
                                trigger = ReadingCompanionAutoCommentary.TRIGGER_CONVERSATION,
                            )
                            .toJson()
                    }
                    "auto_commentary_manual_batch" -> {
                        require(
                            callerPackageName ==
                                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME ||
                                callerPackageName == ReadingCompanionService.SUBPACKAGE_NAME
                        ) { "该操作仅允许阅读伴侣工具包调用" }
                        val count = parameters.optInt("count", -1)
                        val start = parameters.optNullableInt("start_chapter_index")
                        val end = parameters.optNullableInt("end_chapter_index")
                        ReadingCompanionAutoCommentary.getInstance(context)
                            .generateManualBatch(
                                count = count,
                                startChapterIndex = start,
                                endChapterIndex = end,
                                runtime = runtime,
                            )
                    }
                    "manual_batch_summaries" -> {
                        require(
                            callerPackageName == ReadingCompanionService.SUBPACKAGE_NAME
                        ) { "该操作仅允许阅读伴侣主包调用" }
                        val count = parameters.optInt("count", -1)
                        val start = parameters.optNullableInt("start_chapter_index")
                        val end = parameters.optNullableInt("end_chapter_index")
                        service.manualBatchSummaries(
                            count = count,
                            startChapterIndex = start,
                            endChapterIndex = end,
                            runtime = runtime,
                        )
                    }
                    "list_audit_chats" -> {
                        require(
                            callerPackageName == ReadingCompanionService.SUBPACKAGE_NAME
                        ) { "该操作仅允许阅读伴侣主包调用" }
                        ReadingCompanionAutoCommentary.getInstance(context).listAuditChats(
                            parameters.optString("bookId").trim()
                                .takeIf(String::isNotBlank),
                            parameters.optInt(
                                "limit",
                                ReadingCompanionAutoCommentary.AUDIT_CHAT_LIST_DEFAULT_LIMIT,
                            ),
                        )
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
                is ReaderProviderException -> safeReadingCompanionError(error)
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
            remainingCompletedChapters > 0,
        )
    }

    private fun AutoCommentaryGenerationResult.toJson(): JSONObject = JSONObject().apply {
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex?.plus(1))
        put("status", status)
        put("commentCount", commentCount)
        put("runId", runId)
        execution?.let { model ->
            put(
                "execution",
                JSONObject()
                    .put("roleCardId", model.roleCardId)
                    .put("roleCardName", model.roleCardName)
                    .put("modelConfigId", model.configId)
                    .put("modelConfigName", model.configName)
                    .put("modelIndex", model.modelIndex)
                    .put("modelSource", model.modelSource)
                    .put("provider", model.provider)
                    .put("model", model.model),
            )
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private suspend fun isReadingCompanionAuditRootChat(
        context: Context,
        chatId: String,
    ): Boolean =
        runCatching {
                com.ai.assistance.operit.data.db.AppDatabase
                    .getDatabase(context.applicationContext)
                    .chatDao()
                    .getChatById(chatId)
                    ?.let { chat -> ReadingCompanionAudit.isPermanentHiddenReason(chat.hiddenReason) }
                    ?: false
            }
            .getOrDefault(false)
}
