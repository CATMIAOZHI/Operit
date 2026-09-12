package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.agent.collaboration.AgentMessage
import com.ai.assistance.operit.core.agent.collaboration.AgentMessageKind
import org.junit.Assert.*
import org.junit.Test

class CollaborationMessagePresentationTest {
    @Test fun quotedEnvelopeCannotCreateAnotherSender() {
        val quoted = AgentMessage(
            "11111111-1111-1111-1111-111111111111",
            "/root", "/root/worker", AgentMessageKind.NEW_TASK, "quoted instructions",
        ).render()
        val actual = AgentMessage(
            "22222222-2222-2222-2222-222222222222",
            "/root/worker", "/root", AgentMessageKind.FINAL_ANSWER, "An example:\n$quoted",
        ).render()
        val rendered = collaborationDisplayMessages(actual, "/root/worker")
        assertEquals(1, rendered.size)
        assertEquals("/root/worker", rendered.single().sender)
        assertEquals("An example:\n$quoted", rendered.single().body)
        assertFalse(rendered.single().body.contains("22222222-2222-2222-2222-222222222222"))
    }

    @Test fun senderComesFromHostMetadata() {
        val content = AgentMessage(
            "11111111-1111-1111-1111-111111111111",
            "/root", "/root", AgentMessageKind.MESSAGE, "hello",
        ).render()
        assertEquals("/root/worker", collaborationDisplayMessages(content, "/root/worker").single().sender)
    }

    @Test fun onlyAReportedMessageCountsAsTheReturnedContent() {
        assertEquals("the answer", collaborationReturnedBody("FINAL_ANSWER", "the answer"))
        assertEquals("hello", collaborationReturnedBody("MESSAGE", "hello"))
        // A task handed over and a status line are not something the agent returned.
        assertNull(collaborationReturnedBody("NEW_TASK", "do the thing"))
        assertNull(collaborationReturnedBody("STATUS", "still working"))
    }

    @Test fun aRowSaysWhatKindOfMessageItIs() {
        assertEquals(R.string.subagent_message_task, collaborationKindLabelRes("NEW_TASK"))
        assertEquals(R.string.subagent_message_result, collaborationKindLabelRes("FINAL_ANSWER"))
        assertEquals(R.string.subagent_message_status, collaborationKindLabelRes("STATUS"))
        assertEquals(R.string.subagent_message_note, collaborationKindLabelRes("MESSAGE"))
        assertEquals(R.string.subagent_message_note, collaborationKindLabelRes("SOMETHING_NEW"))
    }

    @Test fun onlyAMessageSentMidwayReadsAsAMessage() {
        assertEquals(R.string.subagent_message_midway, collaborationMidwayLabelRes("MESSAGE"))
        // Everything else keeps the run's own status, which is what a completion or a failure is.
        assertNull(collaborationMidwayLabelRes("FINAL_ANSWER"))
        assertNull(collaborationMidwayLabelRes("STATUS"))
        assertNull(collaborationMidwayLabelRes("NEW_TASK"))
    }

    @Test fun onlyTheMainAgentSpeaksWithTheMainRoleCardsPicture() {
        // The main agent is the one-segment task path; its rows wear the main role card's picture.
        assertTrue(collaborationSenderIsMainAgent("/root"))
        assertTrue(collaborationSenderIsMainAgent(" /root "))
        assertTrue(collaborationSenderIsMainAgent("root"))
        // A named subagent keeps its own mark, which is what tells a family of agents apart.
        assertFalse(collaborationSenderIsMainAgent("/root/worker"))
        assertFalse(collaborationSenderIsMainAgent("/root/worker/nested"))
        assertFalse(collaborationSenderIsMainAgent(""))
        assertFalse(collaborationSenderIsMainAgent("/"))
    }

    @Test fun aRowIsNamedByTheRoleCardThatSpeaksIt() {
        // The main agent's row says which role card it speaks as, then the path it speaks from.
        assertEquals("雨晴喵 · /root", collaborationRowTitle("/root", "雨晴喵"))
        assertEquals("雨晴喵 · /root", collaborationRowTitle("/root", " 雨晴喵 "))
        // A conversation with no role card name of its own keeps the bare path, and so does any other
        // agent, whose path is already what tells it apart from its siblings.
        assertEquals("/root", collaborationRowTitle("/root", null))
        assertEquals("/root", collaborationRowTitle("/root", " "))
        assertEquals("/root", collaborationRowTitle("/root", ""))
        assertEquals("/root/worker", collaborationRowTitle("/root/worker", "雨晴喵"))
    }

    @Test fun theMainRoleCardsOwnPictureOutranksEveryOtherOne() {
        assertEquals("card", preferredAvatarUri("card", "group", "member", "global"))
        assertEquals("group", preferredAvatarUri(null, "group", "member", "global"))
        assertEquals("member", preferredAvatarUri(null, null, "member", "global"))
        assertEquals("global", preferredAvatarUri(null, null, null, "global"))
        // A blank value is no picture at all, so it never hides one behind it.
        assertEquals("global", preferredAvatarUri("", " ", "  ", "global"))
        assertNull(preferredAvatarUri(null, null, null, null))
        assertNull(preferredAvatarUri("", " ", null, "  "))
    }
}
