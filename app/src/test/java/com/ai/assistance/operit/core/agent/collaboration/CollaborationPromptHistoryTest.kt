package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.mergeAdjacentTurns
import org.junit.Assert.*
import org.junit.Test

class CollaborationPromptHistoryTest {
    @Test fun forkKeepsParentRequestSeparateFromLastNamedAssignment() {
        val parent = CollaborationTurn("USER", "Create two child agents")
        val assignment = AgentMessage(
            "00000000-0000-0000-0000-000000000001", "/root", "/root/child",
            AgentMessageKind.NEW_TASK, "Read the inherited marker and reply without tools",
        )
        val request = (CollaborationPromptHistory.inheritedPrefix(listOf(parent), "/root/child")
            .map { it.toPromptTurn() } + PromptTurn(PromptTurnKind.USER, assignment.render()))
            .mergeAdjacentTurns { a, b -> a.kind == PromptTurnKind.USER && b.kind == PromptTurnKind.USER }
        assertEquals(3, request.size)
        assertEquals(parent.content, request.first().content)
        assertEquals(PromptTurnKind.SUMMARY, request[1].kind)
        assertTrue(request[1].content.contains("/root/child"))
        assertEquals(assignment.render(), request.last().content)
        assertTrue(request.last().content.endsWith(assignment.text))
    }

    @Test fun noForkAddsNoInheritedBoundary() {
        assertTrue(CollaborationPromptHistory.inheritedPrefix(emptyList(), "/root/child").isEmpty())
    }
}
