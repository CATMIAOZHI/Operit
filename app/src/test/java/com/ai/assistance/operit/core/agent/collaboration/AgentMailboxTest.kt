package com.ai.assistance.operit.core.agent.collaboration

import org.junit.Assert.*
import org.junit.Test

class AgentMailboxTest {
    @Test fun multilinePayloadDoesNotIndentProtocolHeaders() {
        val message = AgentMessage("message-id", "/root", "/root/worker", AgentMessageKind.MESSAGE, "one\ntwo")
        assertTrue(message.render().startsWith("Message ID: message-id\nMessage Type: MESSAGE\n"))
        assertTrue(message.render().endsWith("Payload:\none\ntwo"))
    }

    @Test fun aSenderIdIsWrittenBetweenTheSenderAndThePayload() {
        val message = AgentMessage("id", "/root/worker", "/root", AgentMessageKind.MESSAGE, "hi", senderId = "child-7")
        assertTrue(message.render().contains("Sender: /root/worker\nSender ID: child-7\nPayload:"))
        assertTrue(message.render().endsWith("Payload:\nhi"))
    }

    @Test fun onlyOrbitEnvelopeSaysWhenItWasSentAndWhoIsWho() {
        val toChild = AgentMessage("id", "/root", "/root/worker", AgentMessageKind.NEW_TASK, "go", sentAt = 0L)
        val text = toChild.render(orbit = true)
        assertTrue(Regex("\nSent at: \\[1970-01-0\\d \\w{3} \\d{2}:\\d{2}:\\d{2} UTC[^\\n]+]\n").containsMatchIn(text))
        assertTrue(text.contains("Roles: sender /root is the main agent, your parent; you (/root/worker) are a subagent\nPayload:\ngo"))
        val report = AgentMessage("id", "/root/worker", "/root", AgentMessageKind.FINAL_ANSWER, "done")
        assertTrue(report.render(orbit = true).contains("Roles: sender /root/worker is your subagent; you (/root) are the main agent\n"))
        val peer = AgentMessage("id", "/root/a", "/root/b", AgentMessageKind.MESSAGE, "hi")
        assertTrue(peer.render(orbit = true).contains("Roles: sender /root/a is another subagent; you (/root/b) are a subagent\n"))
        listOf(toChild, report, peer).forEach {
            assertFalse(it.render().contains("\nSent at:"))
            assertFalse(it.render().contains("\nRoles:"))
            assertTrue(it.render().endsWith("Payload:\n${it.text}"))
        }
    }

    @Test fun restoredOrdinaryAgentPromptDoesNotPromiseOrbitHeaders() {
        val prompt = """
            before
            You are the subagent /root/worker, not the main agent /root. Each agent message states when
            it was sent (Sent at) and who sent it (Roles); /root is the main agent that talks
            with the user.
            after
        """.trimIndent()
        assertEquals(prompt, collaborationSystemPromptForScope(prompt, true))
        val ordinary = collaborationSystemPromptForScope(prompt, false)
        assertEquals("before\nYou are the subagent /root/worker, not the main agent /root.\nafter", ordinary)
        assertEquals(ordinary, collaborationSystemPromptForScope(ordinary, false))
        val nested = prompt + "\n" + prompt.replace("/root/worker", "/root/worker/child")
        val cleaned = collaborationSystemPromptForScope(nested, false)
        assertFalse(cleaned.contains("Sent at"))
        assertFalse(cleaned.contains("Roles"))
        assertTrue(cleaned.contains("You are the subagent /root/worker/child, not the main agent /root."))
    }

    @Test fun orbitCompletionNoticeRetainsEnvelopeContextInBothLanguages() {
        val report = AgentMessage("report-id", "/root/worker", "/root", AgentMessageKind.FINAL_ANSWER,
            "done", senderId = "child-7", sentAt = 0L, sentZone = "UTC")
        listOf(false, true).forEach { english ->
            val notice = com.ai.assistance.operit.features.orbit.OrbitSubagentNotice.text(english, listOf(report))
            assertTrue(notice.contains("Sender ID: child-7\nSent at:"))
            assertTrue(notice.contains("\nRoles: sender /root/worker is your subagent;"))
            assertTrue(notice.contains("\nPayload:\ndone"))
        }
    }
    private val initial = CollaborationState(agents = listOf(
        CollaborationAgent("chat-a", "/root", "chat-a"),
        CollaborationAgent("chat-a", "/root/worker", "child-a"),
        CollaborationAgent("chat-b", "/root", "chat-b"),
        CollaborationAgent("chat-b", "/root/worker", "child-b"),
    ))

    @Test fun queueOnlyDeliveryPreservesIdleStateAndIsolatesRoots() {
        val message = AgentMessage("1", "/root", "/root/worker", AgentMessageKind.MESSAGE, "hello")
        val queued = initial.enqueue("chat-a", message)
        assertEquals(listOf(message), queued.find("chat-a", "/root/worker")!!.messages)
        assertEquals(CollaborationStatus.IDLE, queued.find("chat-a", "/root/worker")!!.status)
        assertTrue(queued.find("chat-b", "/root/worker")!!.messages.isEmpty())
    }

    @Test fun acknowledgementDoesNotConsumeLaterMessages() {
        val first = AgentMessage("1", "/root", "/root/worker", AgentMessageKind.MESSAGE, "first")
        val second = first.copy(id = "2", text = "second")
        val queued = initial.enqueue("chat-a", first).enqueue("chat-a", second)
        val acknowledged = queued.acknowledge("chat-a", "/root/worker", setOf("1"))
        assertEquals(listOf(second), acknowledged.find("chat-a", "/root/worker")!!.messages)
    }

    @Test fun followupCannotWakeRootButOrdinaryMessageCanTargetIt() {
        val message = AgentMessage("1", "/root/worker", "/root", AgentMessageKind.NEW_TASK, "work")
        assertThrows(IllegalArgumentException::class.java) { initial.enqueue("chat-a", message) }
        val queued = initial.enqueue("chat-a", message.copy(kind = AgentMessageKind.MESSAGE))
        assertEquals(1, queued.find("chat-a", "/root")!!.messages.size)
    }
}
