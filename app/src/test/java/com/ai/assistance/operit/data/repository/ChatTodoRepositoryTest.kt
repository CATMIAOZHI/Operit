package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoPriority
import com.ai.assistance.operit.data.model.ChatTodoStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTodoRepositoryTest {
    @Test
    fun switchingChatsClearsPreviousRowsBeforeNewQueryEmits() = runTest {
        val chatIds = MutableSharedFlow<String?>()
        val firstChatRows = MutableSharedFlow<List<ChatTodo>>()
        val secondChatRows = MutableSharedFlow<List<ChatTodo>>()
        val rowsByChatId = mapOf("first" to firstChatRows, "second" to secondChatRows)
        val emissions = mutableListOf<List<ChatTodo>>()
        val firstTodo = todo("first todo")
        val secondTodo = todo("second todo")

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            chatIds.observeChatTodos(rowsByChatId::getValue).collect(emissions::add)
        }

        chatIds.emit("first")
        firstChatRows.emit(listOf(firstTodo))
        chatIds.emit("second")
        runCurrent()

        assertEquals(listOf(emptyList(), listOf(firstTodo), emptyList()), emissions)

        secondChatRows.emit(listOf(secondTodo))
        runCurrent()

        assertEquals(
            listOf(emptyList(), listOf(firstTodo), emptyList(), listOf(secondTodo)),
            emissions,
        )
    }

    private fun todo(content: String) =
        ChatTodo(
            content = content,
            status = ChatTodoStatus.IN_PROGRESS,
            priority = ChatTodoPriority.MEDIUM,
        )
}
