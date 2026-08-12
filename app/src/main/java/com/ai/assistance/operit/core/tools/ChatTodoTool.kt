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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

internal object ChatTodoTool {
    const val NAME = "todowrite"

    fun execute(context: Context, tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        if (runtime?.isSubagent == true) {
            return failure(context.getString(R.string.chat_todo_tool_subagent_unavailable))
        }
        val chatId = runtime?.callerChatId?.trim().orEmpty()
        if (chatId.isBlank()) {
            return failure(context.getString(R.string.chat_todo_tool_active_chat_required))
        }

        return try {
            val rawTodos =
                tool.parameters.firstOrNull { it.name == "todos" }?.value
                    ?: return failure(context.getString(R.string.chat_todo_tool_missing_todos))
            val json = JSONArray(rawTodos)
            val todos =
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
                }
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
        } catch (_: Exception) {
            failure(context.getString(R.string.chat_todo_tool_invalid_list))
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
