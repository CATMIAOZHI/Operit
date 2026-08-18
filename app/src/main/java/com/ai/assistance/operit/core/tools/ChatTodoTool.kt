package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoPriority
import com.ai.assistance.operit.data.model.ChatTodoStatus
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.repository.ChatTodoRepository
import com.ai.assistance.operit.data.repository.validateChatTodoSnapshot
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

internal object ChatTodoTool {
    const val NAME = "todowrite"
    private const val TAG = "ChatTodoTool"

    fun execute(context: Context, tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        if (runtime?.isSubagent == true) {
            return failure(context.getString(R.string.chat_todo_tool_subagent_unavailable))
        }
        val chatId = runtime?.callerChatId?.trim().orEmpty()
        if (chatId.isBlank()) {
            return failure(context.getString(R.string.chat_todo_tool_active_chat_required))
        }

        val rawTodos =
            tool.parameters.firstOrNull { it.name == "todos" }?.value
                ?: return failure(context.getString(R.string.chat_todo_tool_missing_todos))
        val todos =
            try {
                val json = JSONArray(rawTodos)
                buildList {
                    repeat(json.length()) { index ->
                        val item = json.getJSONObject(index)
                        add(
                            ChatTodo(
                                content = item.getString("content").trim(),
                                status =
                                    ChatTodoStatus.valueOf(
                                        item.getString("status").trim().uppercase()
                                    ),
                                priority =
                                    ChatTodoPriority.valueOf(
                                        item.getString("priority").trim().uppercase()
                                    ),
                            )
                        )
                    }
                }.also(::validateChatTodoSnapshot)
            } catch (_: Exception) {
                return failure(context.getString(R.string.chat_todo_tool_invalid_list))
            }

        return try {
            runBlocking(Dispatchers.IO) {
                ChatTodoRepository.getInstance(context).replace(chatId, todos)
            }
            ToolResult(
                toolName = NAME,
                success = true,
                result =
                    StringResultData(
                        context.getString(R.string.chat_todo_tool_updated, todos.size)
                    ),
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist Todo snapshot for chat $chatId", e)
            failure(context.getString(R.string.chat_todo_tool_save_failed))
        }
    }

    private fun failure(message: String): ToolResult =
        ToolResult(
            toolName = NAME,
            success = false,
            result = StringResultData(message),
            error = message,
        )
}
