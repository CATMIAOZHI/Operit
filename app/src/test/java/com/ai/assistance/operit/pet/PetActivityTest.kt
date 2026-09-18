package com.ai.assistance.operit.pet

import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import org.junit.Test

class PetActivityTest {
    @Test fun viewingOnlyAcknowledgesSuccessfulInactiveMainChat() {
        val completed = PetTask("run", "chat", ChatRuntimeSlot.MAIN, "title", PetActivity.COMPLETE, false, 1)
        assertTrue(shouldAcknowledgeViewedPetTask(completed, "chat"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed, "other"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(active = true), "chat"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(activity = PetActivity.ERROR), "chat"))
        ChatRuntimeSlot.values().filter { it != ChatRuntimeSlot.MAIN }.forEach {
            assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(slot = it), "chat"))
        }
        val confirmed = mapOf(completed.key to completed.startedOrder)
        assertTrue(completed.isAcknowledged(confirmed))
        assertFalse(completed.copy(startedOrder = 2).isAcknowledged(confirmed))
        assertFalse(completed.copy(active = true).isAcknowledged(confirmed))
        assertFalse(completed.copy(activity = PetActivity.ERROR).isAcknowledged(confirmed))
    }

    @Test fun confirmedRunLeavesThePetTaskList() {
        val completed = PetTask("run", "chat", ChatRuntimeSlot.MAIN, "title", PetActivity.COMPLETE, false, 1)
        val running = PetTask("live", "other", ChatRuntimeSlot.MAIN, "busy", PetActivity.THINKING, true, 2)
        val confirmed = mapOf(completed.key to completed.startedOrder)

        assertEquals(listOf(completed, running), visiblePetTasks(listOf(completed, running), emptyMap()))
        assertEquals(listOf(running), visiblePetTasks(listOf(completed, running), confirmed))

        // The next run of that conversation is a fresh task again.
        val rerun = completed.copy(key = "again", startedOrder = 3, active = true, activity = PetActivity.THINKING)
        assertEquals(listOf(rerun), visiblePetTasks(listOf(rerun), confirmed))

        // Only a finished run can be confirmed.
        assertFalse(running.isAcknowledged(mapOf(running.key to running.startedOrder)))
        assertEquals(listOf(running), visiblePetTasks(listOf(running), mapOf(running.key to running.startedOrder)))

        // Confirming the selected card moves the pet on to the last task still listed.
        assertEquals(running.key, visiblePetTasks(listOf(completed, running), confirmed).lastOrNull()?.key)
        assertEquals(null, visiblePetTasks(listOf(completed), confirmed).lastOrNull()?.key)
    }

    @Test fun removedRunDoesNotClaimSuccessWithoutCompletion() {
        assertEquals(PetActivity.ENDED, petActivity(InputProcessingState.Idle, false))
        assertEquals(PetActivity.ENDED, petActivity(InputProcessingState.ExecutingTool("read_file"), false))
        assertEquals(PetActivity.COMPLETE, petActivity(InputProcessingState.Completed, false))
        assertEquals(PetActivity.ERROR, petActivity(InputProcessingState.Error("failed"), false))
    }

    @Test fun toolResultAndProgressRemainToolActivity() {
        assertEquals(PetActivity.TOOL, petActivity(InputProcessingState.ProcessingToolResult("read_file"), true))
        assertEquals(PetActivity.TOOL, petActivity(InputProcessingState.ToolProgress("read_file", 0.5f), true))
        assertEquals(PetActivity.SUMMARIZING, petActivity(InputProcessingState.Summarizing("summary"), true))
    }

    @Test fun subagentAndHiddenChatsAreNotPetTasks() {
        val normal = ChatHistory(title = "写作", messages = emptyList())
        val subagent = normal.copy(
            id = "child",
            chatKind = ChatKind.SUBAGENT.name,
            parentChatId = normal.id,
        )
        val hidden = normal.copy(id = "audit", isHidden = true)
        assertTrue(petChatMetadataOf(normal).petVisible)
        assertFalse(petChatMetadataOf(subagent).petVisible)
        assertTrue(petChatMetadataOf(subagent).subagent)
        assertFalse(petChatMetadataOf(hidden).petVisible)
        assertEquals("写作", petChatMetadataOf(subagent).title)
        assertEquals(normal.id, subagent.parentChatId)
    }

    @Test fun bubbleButtonOnlyMinimizesTheConversationItShows() {
        val task = PetTask("run", "chat", ChatRuntimeSlot.MAIN, "title", PetActivity.THINKING, true, 1)

        // No chat window yet: the button opens the task in the bubble.
        assertEquals(PetBubbleAction.OPEN, petBubbleAction(task, FloatingPetEntryMode.NONE, null))
        assertEquals(PetBubbleAction.OPEN, petBubbleAction(task, FloatingPetEntryMode.PET, "chat"))
        assertEquals(PetBubbleAction.OPEN, petBubbleAction(task, FloatingPetEntryMode.LEGACY_BALL, "chat"))

        // The window already shows this task: minimizing is the only sensible action.
        assertEquals(PetBubbleAction.MINIMIZE, petBubbleAction(task, FloatingPetEntryMode.CHAT_WINDOW, "chat"))

        // The window shows another conversation, so the button has to open this task instead.
        assertEquals(PetBubbleAction.OPEN, petBubbleAction(task, FloatingPetEntryMode.CHAT_WINDOW, "other"))
        // An unknown conversation must not claim the window already shows the task.
        assertEquals(PetBubbleAction.OPEN, petBubbleAction(task, FloatingPetEntryMode.CHAT_WINDOW, null))

        // With no task left to open, the window is all the button can act on.
        assertEquals(PetBubbleAction.MINIMIZE, petBubbleAction(null, FloatingPetEntryMode.CHAT_WINDOW, "chat"))
    }

    @Test fun petFollowsTheConversationTheWindowShows() {
        val completed = PetTask("run", "chat-a", ChatRuntimeSlot.MAIN, "a", PetActivity.COMPLETE, false, 1)
        val running = PetTask("live", "chat-b", ChatRuntimeSlot.MAIN, "b", PetActivity.THINKING, true, 2)
        val tasks = listOf(completed, running)

        assertEquals(null, petTaskForChat(tasks, null))
        assertEquals(null, petTaskForChat(tasks, "chat-c"))
        assertEquals(completed, petTaskForChat(tasks, "chat-a"))
        assertEquals(running, petTaskForChat(tasks, "chat-b"))

        // One conversation per slot, so the newest run of that conversation wins.
        val floatingRun = running.copy(key = "live-2", slot = ChatRuntimeSlot.FLOATING)
        assertEquals(floatingRun, petTaskForChat(tasks + floatingRun, "chat-b"))
    }
}
