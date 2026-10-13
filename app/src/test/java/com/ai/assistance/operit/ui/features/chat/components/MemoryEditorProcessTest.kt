package com.ai.assistance.operit.ui.features.chat.components

import org.junit.Assert.*
import org.junit.Test

class MemoryEditorProcessTest {
    @Test fun foldsThinkingAndToolsButLeavesBodyAndCardsAvailable() {
        val original = "<think>plan</think><tool name=\"x\">args</tool><tool_result>result</tool_result>\nAnswer\n```a2ui\n{}\n```\n<details>useful detail</details>"
        val parts = parseMessageContentForEditor(original)
        val process = memoryEditorProcessIndices(parts, emptyList())
        assertEquals(listOf("think", "tool", "tool_result"), process.map { parts[it].tag })
        assertTrue(parts.indices.filterNot { it in process }.any { parts[it].type == PartType.DYNAMIC_UI })
        assertEquals(original, recomposeMessageFromParts(parts))
    }

    @Test fun groupsIntermediateTextAndCollaborationWithoutTouchingFinalSource() {
        val fragments = listOf(
            MemoryEditFragment(0, 1, "ai", "", "Checking", "Checking", isAssistant = true, isProcess = true),
            MemoryEditFragment(1, 2, "agent", "message", "event", "event"),
            MemoryEditFragment(2, 3, "ai", "", "<think>done</think>\nFinal", "<think>done</think>\nFinal", isAssistant = true),
        )
        val parts = memoryEditorParts(fragments)
        val process = memoryEditorProcessIndices(parts, fragments)
        assertEquals(setOf(0, 1, 2), process)
        val changed = parts.mapIndexed { index, part ->
            if (index !in process && part.type == PartType.TEXT) part.copy(content = "\nEdited") else part
        }
        val saved = updatedMemoryEditorFragments(fragments, parts, changed)
        assertEquals(fragments.take(2), saved.take(2))
        assertEquals("<think>done</think>\nEdited", saved.last().body)
    }

    @Test fun ordinaryMessagesDoNotGetAnEmptyProcessFold() {
        assertTrue(memoryEditorProcessIndices(parseMessageContentForEditor("Just text"), emptyList()).isEmpty())
    }
}
