package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.core.chat.AuditChatNavigation
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog
import org.junit.Assert.*
import org.junit.Test

class MemoryExtractionAuditRepositoryTest {
    private val log = MemoryExtractionLog(id="log",sourceChatId="parent",graph=false,notes=true,skills=true)
    private val run = SubagentRunEntity(id="run",parentChatId="parent",childChatId="child",
        agentProfileId="memory-learning",title="Learning",externalOwnerType="memory-learning",externalOwnerId="log")
    private val chat = ChatEntity(id="child",title="Learning",parentChatId="parent",chatKind="SUBAGENT",
        isHidden=true,hiddenReason="MEMORY_LEARNING")

    @Test fun `old and new logs resolve only their actual hidden subagent`() {
        assertTrue(isAuthorizedMemoryAuditChat(log,run,chat))
        assertTrue(isAuthorizedMemoryAuditChat(log.copy(runId="run",childChatId="child"),run,chat))
        assertFalse(isAuthorizedMemoryAuditChat(log.copy(runId="other"),run,chat))
        assertFalse(isAuthorizedMemoryAuditChat(log.copy(childChatId="other"),run,chat))
        assertFalse(isAuthorizedMemoryAuditChat(log,run.copy(externalOwnerId="other"),chat))
        assertFalse(isAuthorizedMemoryAuditChat(log,run.copy(externalOwnerType="other"),chat))
        assertFalse(isAuthorizedMemoryAuditChat(log,run,chat.copy(parentChatId="other")))
        assertFalse(isAuthorizedMemoryAuditChat(log,run,chat.copy(isHidden=false)))
        assertFalse(isAuthorizedMemoryAuditChat(log,run,chat.copy(hiddenReason="other")))
        assertFalse(isAuthorizedMemoryAuditChat(log,run,chat.copy(chatKind="NORMAL")))
        assertFalse(isAuthorizedMemoryAuditChat(log,run,null))
    }

    @Test fun `native audit navigation carries the visible return point across sibling switches`() {
        AuditChatNavigation.rememberReturnChat("child","visible")
        assertTrue(AuditChatNavigation.hasPendingReturnFor("child"))
        assertNull(AuditChatNavigation.takeReturnChat("unrelated"))
        AuditChatNavigation.carryReturnChat("child","sibling")
        assertFalse(AuditChatNavigation.hasPendingReturnFor("child"))
        assertEquals("visible",AuditChatNavigation.takeReturnChat("sibling"))
        assertFalse(AuditChatNavigation.hasPendingReturnFor(null))
        assertNull(AuditChatNavigation.takeReturnChat("sibling"))
    }
}
