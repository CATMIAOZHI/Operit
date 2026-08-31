package com.ai.assistance.operit.features.reading

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终审 BLOCKING-2：openReadingAuditChat 的 run 级授权判定（隐藏路径与对话内路径）。
 *
 * 白盒直接驱动生产同一决策函数 [ReadingCompanionAudit.isAuthorizedAuditChat]：
 * 伪装 runId、前缀相同但 run 不同、他书 child、owner 链不一致一律拒绝。
 */
class ReadingCompanionAuditChatAuthorizationTest {

    private fun authorize(
        runId: Long = 7L,
        runBookId: String? = "book-1",
        runParentChatId: String? = "root-1",
        runSubagentRunId: String? = "s-1",
        chatIsHidden: Boolean = true,
        chatHiddenReason: String? = ReadingCompanionAudit.runHiddenReason(7L),
        chatParentChatId: String? = "root-1",
        chatParentHiddenReason: String? = ReadingCompanionAudit.rootHiddenReason("book-1"),
        subagentOwnerType: String? = ReadingCompanionAudit.OWNER_TYPE,
        subagentOwnerId: String? = "7",
        subagentRunId: String? = "s-1",
    ): Boolean =
        ReadingCompanionAudit.isAuthorizedAuditChat(
            runId = runId,
            runBookId = runBookId,
            runParentChatId = runParentChatId,
            runSubagentRunId = runSubagentRunId,
            chatIsHidden = chatIsHidden,
            chatHiddenReason = chatHiddenReason,
            chatParentChatId = chatParentChatId,
            chatParentHiddenReason = chatParentHiddenReason,
            subagentOwnerType = subagentOwnerType,
            subagentOwnerId = subagentOwnerId,
            subagentRunId = subagentRunId,
        )

    @Test
    fun `valid hidden audit child is authorized`() {
        assertTrue(authorize())
    }

    @Test
    fun `valid conversation child is authorized`() {
        assertTrue(
            authorize(
                runParentChatId = "user-chat",
                chatIsHidden = false,
                chatHiddenReason = null,
                chatParentChatId = "user-chat",
                chatParentHiddenReason = null,
            ),
        )
    }

    @Test
    fun `same prefix but different run id is rejected`() {
        // hiddenReason 是 run 7 的，请求的却是 run 8（伪装 runId）。
        assertFalse(
            authorize(
                runId = 8L,
                runSubagentRunId = "s-8",
                subagentOwnerId = "8",
                subagentRunId = "s-8",
            ),
        )
    }

    @Test
    fun `forged owner id is rejected even when the prefix matches`() {
        // 前缀全部匹配，但主库 subagent run 的 owner 链指向另一 run。
        assertFalse(authorize(subagentOwnerId = "999"))
    }

    @Test
    fun `cross book hidden child is rejected via root reason`() {
        assertFalse(
            authorize(
                chatParentHiddenReason =
                    ReadingCompanionAudit.rootHiddenReason("book-2"),
            ),
        )
    }

    @Test
    fun `hidden child under a non audit root is rejected`() {
        assertFalse(authorize(chatParentHiddenReason = "some_other_reason"))
    }

    @Test
    fun `subagent run id mismatch is rejected`() {
        assertFalse(authorize(subagentRunId = "s-other"))
    }

    @Test
    fun `missing subagent child run is rejected`() {
        assertFalse(
            authorize(
                subagentOwnerType = null,
                subagentOwnerId = null,
                subagentRunId = null,
            ),
        )
    }

    @Test
    fun `conversation child with mismatched owner is rejected`() {
        assertFalse(
            authorize(
                runParentChatId = "user-chat",
                chatIsHidden = false,
                chatHiddenReason = null,
                chatParentChatId = "user-chat",
                chatParentHiddenReason = null,
                subagentOwnerId = "999",
            ),
        )
    }

    @Test
    fun `hidden path with null run book id still requires run level binding`() {
        // 老数据 runBookId 为空时跳过父根校验，但 owner 链与 parent 绑定仍必须通过。
        assertTrue(authorize(runBookId = null, chatParentHiddenReason = null))
        assertFalse(
            authorize(
                runBookId = null,
                chatParentHiddenReason = null,
                subagentOwnerId = "999",
            ),
        )
    }

    @Test
    fun `JsEngine delegates to the shared authorization decision`() {
        val jsEngineSource =
            File(
                "src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt",
            ).readText()
        assertTrue(
            "openReadingAuditChat 必须调用同一授权判定",
            jsEngineSource.contains("ReadingCompanionAudit.isAuthorizedAuditChat("),
        )
        assertFalse(
            "旧的仅前缀判定必须移除",
            jsEngineSource.contains("isPermanentHiddenReason(chat.hiddenReason)") &&
                jsEngineSource.contains("val hiddenPath"),
        )
    }

    @Test
    fun `hidden audit run is distinguished from its hidden root`() {
        assertTrue(ReadingCompanionAudit.isHiddenAuditRun(ReadingCompanionAudit.runHiddenReason(7L)))
        assertFalse(
            ReadingCompanionAudit.isHiddenAuditRun(
                ReadingCompanionAudit.rootHiddenReason("book-1"),
            ),
        )
    }

    @Test
    fun `audit navigation return point is safe transferable and one shot`() {
        val firstChild = "audit-child-${System.nanoTime()}"
        val siblingChild = "$firstChild-sibling"
        val visibleChat = "$firstChild-visible"
        val noReturnChat = "$firstChild-no-return"

        ReadingCompanionAudit.rememberReturnChat(firstChild, visibleChat)
        ReadingCompanionAudit.carryReturnChat(firstChild, siblingChild)

        assertTrue(ReadingCompanionAudit.takeReturnChat(siblingChild) == visibleChat)
        assertTrue(ReadingCompanionAudit.takeReturnChat(siblingChild) == null)
        assertTrue(ReadingCompanionAudit.takeReturnChat(firstChild) == null)

        ReadingCompanionAudit.rememberReturnChat(noReturnChat, null)
        assertTrue(ReadingCompanionAudit.hasPendingReturnFor(noReturnChat))
        assertTrue(ReadingCompanionAudit.takeReturnChat(noReturnChat) == null)
        assertFalse(ReadingCompanionAudit.hasPendingReturnFor(noReturnChat))
    }

    @Test
    fun `audit chat back exits route instead of switching to hidden parent`() {
        val aiChatScreen =
            File(
                "src/main/java/com/ai/assistance/operit/ui/features/chat/screens/AIChatScreen.kt",
            ).readText()
        val chatHeader =
            File(
                "src/main/java/com/ai/assistance/operit/ui/features/chat/components/ChatScreenHeader.kt",
            ).readText()
        val jsEngine =
            File(
                "src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt",
            ).readText()
        val chatViewModel =
            File(
                "src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/ChatViewModel.kt",
            ).readText()
        val hiddenChatsScreen =
            File(
                "src/main/java/com/ai/assistance/operit/ui/features/chat/screens/HiddenChatsScreen.kt",
            ).readText()

        assertTrue(aiChatScreen.contains("ReadingCompanionAudit.takeReturnChat("))
        assertTrue(aiChatScreen.contains("ReadingCompanionAudit.hasPendingReturnFor("))
        assertTrue(aiChatScreen.contains("ReadingCompanionAudit.isPermanentHiddenReason("))
        assertTrue(aiChatScreen.contains("isReadOnlyTranscript"))
        assertTrue(aiChatScreen.contains("actualViewModel.switchChatLocally("))
        assertTrue(aiChatScreen.contains("onNavigateBack()"))
        assertTrue(chatHeader.contains("onExitHiddenReadingAuditRun()"))
        assertTrue(chatHeader.contains("ReadingCompanionAudit.carryReturnChat("))
        assertTrue(
            Regex("""actualViewModel\.switchChatLocally\(""")
                .findAll(chatHeader)
                .count() >= 2,
        )
        assertTrue(jsEngine.contains("ReadingCompanionAudit.rememberReturnChat("))
        assertTrue(jsEngine.contains("?.takeIf { !it.isHidden }"))
        assertTrue(jsEngine.contains("syncToGlobal = false"))
        assertTrue(chatViewModel.contains("fun switchChatLocally("))
        assertTrue(hiddenChatsScreen.contains("ReadingCompanionAudit.isPermanentHiddenReason("))
        assertTrue(hiddenChatsScreen.contains("ReadingCompanionAudit.rememberReturnChat("))
        assertTrue(hiddenChatsScreen.contains("syncToGlobal = !isReadingAuditChat"))
    }
}
