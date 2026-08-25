package com.ai.assistance.operit.features.reading

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookMutation
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.core.chat.hooks.SystemPromptComposeHook
import com.ai.assistance.operit.core.chat.hooks.ToolPromptComposeHook
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.plugins.OperitPlugin
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object ReadingCompanionPlugin : OperitPlugin {
    override val id: String = "builtin.reading-companion"
    private val installed = AtomicBoolean(false)

    override fun register() {
        if (!installed.compareAndSet(false, true)) return
        val context = OperitApplication.instance.applicationContext
        val handler = AIToolHandler.getInstance(context)
        ReadingCompanionTools.register(context, handler)
        PromptHookRegistry.registerToolPromptComposeHook(
            ReadingCompanionToolPromptHook()
        )
        PromptHookRegistry.registerSystemPromptComposeHook(
            ReadingCompanionSystemPromptHook
        )
    }
}

private object ReadingCompanionSystemPromptHook : SystemPromptComposeHook {
    override val id: String = "builtin.reading-companion.policy"

    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        if (
            context.stage != "after_compose_system_prompt" ||
            context.metadata["enableTools"] != true
        ) {
            return null
        }
        val prompt = context.systemPrompt ?: return null
        val policy = if (context.useEnglish == true) {
            """

            AI READING COMPANION
            - When the user refers to the current passage or wants to react to the current plot, call reading_get_context.
            - For questions about earlier characters, events, causes, dialogue, or chapter locations, call reading_search.
            - For novel-specific answers, use only evidence returned by reading tools. Do not rely on outside plot knowledge or web search, and do not infer events beyond the returned reading boundary.
            - If the safe reading tools return no evidence, say so instead of guessing.
            """.trimIndent()
        } else {
            """

            AI 阅读伴侣
            - 用户提到“这段”“刚才的情节”或想交流、吐槽当前剧情时，调用 reading_get_context。
            - 用户询问前文人物、事件、原因、对话或章节位置时，调用 reading_search。
            - 回答具体小说剧情时只能使用伴读工具返回的依据；不得依赖外部剧情知识、网络搜索，也不得推断阅读边界之后的事件。
            - 安全伴读工具没有返回依据时，明确说明没有检索到，不要猜测。
            """.trimIndent()
        }
        return PromptHookMutation(systemPrompt = "$prompt\n\n$policy")
    }
}

private object ReadingCompanionTools {
    const val GET_CURRENT_BOOK = "reading_get_current_book"
    const val GET_CONTEXT = "reading_get_context"
    const val SEARCH = "reading_search"
    const val REFRESH_PROGRESS = "reading_refresh_progress"
    private const val TAG = "ReadingCompanion"

    fun register(context: Context, handler: AIToolHandler) {
        handler.registerTool(
            name = GET_CURRENT_BOOK,
            descriptionGenerator = {
                context.getString(R.string.reading_companion_tool_current_book)
            },
            executor = { tool -> execute(context, tool) },
        )
        handler.registerTool(
            name = GET_CONTEXT,
            descriptionGenerator = {
                context.getString(R.string.reading_companion_tool_context)
            },
            executor = { tool -> execute(context, tool) },
        )
        handler.registerTool(
            name = SEARCH,
            descriptionGenerator = {
                context.getString(R.string.reading_companion_tool_search)
            },
            executor = { tool -> execute(context, tool) },
        )
        handler.registerTool(
            name = REFRESH_PROGRESS,
            descriptionGenerator = {
                context.getString(R.string.reading_companion_tool_refresh)
            },
            executor = { tool -> execute(context, tool) },
        )
    }

    private fun execute(context: Context, tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        return try {
            val service = ReadingCompanionService.getInstance(context)
            val output = runBlocking(Dispatchers.IO) {
                when (tool.name) {
                    GET_CURRENT_BOOK -> readingStateJson(service.currentBook())
                    GET_CONTEXT -> {
                        val maxCharacters = tool.parameter("max_characters")
                            ?.toIntOrNull()
                            ?: 2600
                        service.currentContext(maxCharacters)
                    }
                    SEARCH -> {
                        val query = tool.parameter("query").orEmpty().trim()
                        service.search(query, runtime)
                    }
                    REFRESH_PROGRESS -> {
                        val refresh = service.refreshAndIndex(
                            maxCompletedChapters = 3,
                            scheduleMore = true,
                        )
                        refreshJson(refresh)
                    }
                    else -> error("未知的伴读工具: ${tool.name}")
                }
            }
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(output.toString()),
            )
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Reading companion tool failed: ${tool.name}", error)
            val message = when (error) {
                is ReaderProviderException -> error.message.orEmpty()
                is IllegalArgumentException -> error.message.orEmpty()
                else -> context.getString(R.string.reading_companion_error_generic)
            }.ifBlank { context.getString(R.string.reading_companion_error_generic) }
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(
                    JSONObject()
                        .put("error", message)
                        .put("tool", tool.name)
                        .toString()
                ),
                error = message,
            )
        }
    }

    private fun AITool.parameter(name: String): String? =
        parameters.firstOrNull { it.name == name }?.value

    private fun readingStateJson(state: ReadingState) = JSONObject().apply {
        put("book", state.book.name)
        put("bookId", state.book.id)
        put("author", state.book.author)
        put("currentChapterIndex", state.chapterIndex)
        put("currentChapterNumber", state.chapterIndex + 1)
        put("currentChapterTitle", state.chapterTitle)
        put("currentBodyPosition", state.bodyPosition)
        put("preciseCurrentPositionAvailable", state.bodyPosition != null)
        put("totalChapterCount", state.book.totalChapterCount)
        put("lastReadAt", state.book.lastReadAt)
        put("capturedAt", state.capturedAt)
    }

    private fun refreshJson(refresh: ReadingRefreshResult) = JSONObject().apply {
        put("book", refresh.state.book.name)
        put("currentChapterIndex", refresh.state.chapterIndex)
        put("currentChapterNumber", refresh.state.chapterIndex + 1)
        put("currentChapterTitle", refresh.state.chapterTitle)
        put("currentBodyPosition", refresh.state.bodyPosition)
        put("indexedChaptersThisRun", refresh.indexedChapters)
        put("remainingCompletedChapters", refresh.remainingCompletedChapters)
        put("currentChapterIndexedUntil", refresh.currentChapterIndexedUntil)
        put("backgroundIndexScheduled", refresh.remainingCompletedChapters > 0)
    }
}

private class ReadingCompanionToolPromptHook : ToolPromptComposeHook {
    override val id: String = "builtin.reading-companion.tools"

    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        if (context.functionType != null && context.functionType != "CHAT") return null
        if (
            context.stage != "before_compose_tool_prompt" &&
            context.stage != "filter_tool_call_tools"
        ) {
            return null
        }
        val additions = toolItems(context.useEnglish == true)
        val existingNames = context.availableTools.mapNotNullTo(hashSetOf()) {
            it["name"] as? String
        }
        return PromptHookMutation(
            availableTools = context.availableTools + additions.filterNot {
                it["name"] in existingNames
            }
        )
    }

    private fun toolItems(useEnglish: Boolean): List<Map<String, Any?>> {
        fun text(english: String, chinese: String) = if (useEnglish) english else chinese
        fun item(
            name: String,
            description: String,
            parameters: List<Map<String, Any?>> = emptyList(),
        ): Map<String, Any?> = mapOf(
            "categoryName" to text("AI Reading Companion", "AI 阅读伴侣"),
            "name" to name,
            "description" to description,
            "parameters" to "",
            "details" to "",
            "notes" to text(
                "All returned novel text is already constrained by Legado's reading boundary.",
                "所有返回的小说正文都已经过 Legado 阅读边界限制。",
            ),
            "parametersStructured" to parameters,
        )
        fun parameter(
            name: String,
            type: String,
            description: String,
            required: Boolean,
            default: String? = null,
        ) = mapOf(
            "name" to name,
            "type" to type,
            "description" to description,
            "required" to required,
            "default" to default,
        )
        return listOf(
            item(
                ReadingCompanionTools.GET_CURRENT_BOOK,
                text(
                    "Get the current Legado book and its safe reading progress.",
                    "获取当前 Legado 书籍及安全阅读进度。",
                ),
            ),
            item(
                ReadingCompanionTools.GET_CONTEXT,
                text(
                    "Get a safe excerpt immediately before the current reading position. Use when the user says 'this part', wants to react to the current plot, or asks for companionship.",
                    "获取当前阅读位置之前的安全正文。用户说“这段”“刚才的情节”、想吐槽或交流当前剧情时使用。",
                ),
                listOf(
                    parameter(
                        name = "max_characters",
                        type = "integer",
                        description = text(
                            "Maximum excerpt length, 400 to 6000.",
                            "最多返回字符数，范围 400 到 6000。",
                        ),
                        required = false,
                        default = "2600",
                    )
                ),
            ),
            item(
                ReadingCompanionTools.SEARCH,
                text(
                    "Search only previously read novel text to recall characters, events, dialogue, causes, or chapter locations. Results include chapter evidence.",
                    "只搜索已经阅读的小说正文，用于回顾人物、事件、对话、前因后果或定位章节，并返回章节依据。",
                ),
                listOf(
                    parameter(
                        name = "query",
                        type = "string",
                        description = text(
                            "The recall question or search description.",
                            "要回顾的问题或搜索描述。",
                        ),
                        required = true,
                    )
                ),
            ),
            item(
                ReadingCompanionTools.REFRESH_PROGRESS,
                text(
                    "Refresh Legado progress and incrementally index newly read content.",
                    "刷新 Legado 阅读进度并增量索引新读内容。",
                ),
            ),
        )
    }
}
