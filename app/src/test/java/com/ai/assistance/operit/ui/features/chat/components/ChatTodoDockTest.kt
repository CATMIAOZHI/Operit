package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoPriority
import com.ai.assistance.operit.data.model.ChatTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTodoDockTest {
    @Test
    fun currentStepPrefersInProgressOverEarlierPendingItem() {
        assertEquals(
            2,
            currentTodoStep(
                listOf(
                    todo(ChatTodoStatus.PENDING),
                    todo(ChatTodoStatus.IN_PROGRESS),
                    todo(ChatTodoStatus.PENDING),
                )
            ),
        )
    }

    @Test
    fun currentStepFallsBackToFirstPendingItem() {
        assertEquals(
            2,
            currentTodoStep(
                listOf(
                    todo(ChatTodoStatus.COMPLETED),
                    todo(ChatTodoStatus.PENDING),
                )
            ),
        )
    }

    private fun todo(status: ChatTodoStatus): ChatTodo =
        ChatTodo(
            content = status.name,
            status = status,
            priority = ChatTodoPriority.MEDIUM,
        )
}
