package com.ai.assistance.operit.features.reading

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 4 静态契约断言（纯 JVM，无 Android 依赖）。
 *
 * 覆盖：隐藏聊天列表入口（无取消隐藏）、受限宿主动作 openReadingAuditChat（不信任 JS
 * chatId）、Bridge list_audit_chats、run detail 弱关联字段、prune 联动挂账，以及不再
 * 向用户承诺无法兑现的硬性剧透隔离。
 */
class ReadingCompanionStage4StaticTest {

    private fun source(relativePath: String): String =
        File(relativePath).readText().replace("\r\n", "\n")

    private val hiddenChatsScreenSource =
        source(
            "src/main/java/com/ai/assistance/operit/ui/features/chat/screens/" +
                "HiddenChatsScreen.kt",
        )

    private val jsEngineSource =
        source(
            "src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt",
        )

    private val bridgeSource =
        source(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionBridge.kt",
        )

    private val storeSource =
        source(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionStore.kt",
        )

    private val autoCommentarySource =
        source(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionAutoCommentary.kt",
        )

    private val jsFiles =
        listOf(
            "../examples/reading_companion/ui/reading_companion_run_detail/index.ui.js",
            "../examples/reading_companion/ui/reading_companion_history/index.ui.js",
            "../examples/reading_companion/ui/reading_companion_entry/index.ui.js",
            "../examples/reading_companion/packages/reading_companion.js",
            "../examples/reading_companion/packages/reading_companion_auto_commentary.js",
        )

    private val spoilerPatterns =
        listOf(
            "never exposes",
            "never displayed",
            "不会暴露",
            "不会返回",
        )

    @Test
    fun `hidden chats screen lists, opens and subtree-deletes without any unhide entry`() {
        assertTrue(hiddenChatsScreenSource.contains("observeHiddenChats()"))
        assertTrue(hiddenChatsScreenSource.contains("deleteChatHistory(chat.id)"))
        assertTrue(hiddenChatsScreenSource.contains("chatHistoryDelegate.switchChat("))
        assertTrue(hiddenChatsScreenSource.contains("chat.id,"))
        assertTrue(
            hiddenChatsScreenSource.contains("syncToGlobal = !isReadingAuditChat"),
        )
        assertTrue(
            hiddenChatsScreenSource.contains("ReadingCompanionAudit.rememberReturnChat("),
        )
        assertTrue(hiddenChatsScreenSource.contains("hidden_chats_permanently_hidden_note"))
        assertFalse(
            "隐藏聊天入口不得提供取消隐藏按钮",
            hiddenChatsScreenSource.contains("取消隐藏"),
        )
        assertFalse(
            "不得调用 setChatHidden 取消隐藏",
            hiddenChatsScreenSource.contains("setChatHidden("),
        )
    }

    @Test
    fun `openReadingAuditChat validates run ownership and never trusts JS chatId`() {
        assertTrue(jsEngineSource.contains("fun openReadingAuditChat("))
        assertTrue(
            jsEngineSource.contains(
                "boundToolPkgContainerName == ReadingCompanionService.TOOLPKG_ID",
            ),
        )
        assertTrue(
            "必须由 runId 反查 child_chat_id，不接收 JS 传入的 chatId",
            jsEngineSource.contains("run.childChatId"),
        )
        assertTrue(jsEngineSource.contains("getChatById(childChatId)"))
        assertTrue(
            "终审 BLOCKING-2：JsEngine 必须委托共享的 run 级授权判定",
            jsEngineSource.contains("ReadingCompanionAudit.isAuthorizedAuditChat("),
        )
        assertTrue(jsEngineSource.contains("subagentOwnerId = subagentRun?.externalOwnerId"))
        assertTrue(jsEngineSource.contains("chatHistoryDelegate.switchChat("))
        assertTrue(jsEngineSource.contains("syncToGlobal = false"))
        assertTrue(jsEngineSource.contains("native.ai_chat"))
        assertFalse(
            "openReadingAuditChat 不得直接接受 JS chatId 参数",
            Regex("fun openReadingAuditChat\\([^)]*chatId").containsMatchIn(jsEngineSource),
        )
    }

    @Test
    fun `bridge exposes list_audit_chats for the main package only`() {
        assertTrue(bridgeSource.contains("\"list_audit_chats\""))
        assertTrue(bridgeSource.contains("ReadingCompanionService.SUBPACKAGE_NAME"))
        assertTrue(bridgeSource.contains("listAuditChats("))
    }

    @Test
    fun `prune collects child_chat_id before deleting and exposes flush entry`() {
        assertTrue(storeSource.contains("child_chat_id"))
        assertTrue(storeSource.contains("flushPrunedRunChatCleanup()"))
        assertTrue(storeSource.contains("ReadingCompanionPruneCleanup("))
        assertTrue(
            "剪枝 SQL 与挂账必须处于同一剪枝函数",
            storeSource.contains("private fun pruneAutoCommentRuns(db: SQLiteDatabase)"),
        )
    }

    @Test
    fun `run detail json carries weak linkage fields and audit chat listing exists`() {
        assertTrue(autoCommentarySource.contains("put(\"executionMode\", executionMode)"))
        assertTrue(autoCommentarySource.contains("put(\"parentChatId\", parentChatId)"))
        assertTrue(autoCommentarySource.contains("put(\"childChatId\", childChatId)"))
        assertTrue(autoCommentarySource.contains("put(\"subagentRunId\", subagentRunId)"))
        assertTrue(autoCommentarySource.contains("suspend fun listAuditChats("))
        assertTrue(autoCommentarySource.contains("put(\"groups\", JSONArray(groups.values.toList()))"))
    }

    @Test
    fun `hard spoiler guarantees are gone from every reading companion js surface`() {
        jsFiles.forEach { path ->
            val content = source(path)
            spoilerPatterns.forEach { pattern ->
                assertFalse(
                    "$path 仍包含剧透文案：$pattern",
                    content.contains(pattern),
                )
            }
        }
    }

    @Test
    fun `run detail ui can open the audit chat and shows linked ids`() {
        val runDetail =
            source(
                "../examples/reading_companion/ui/reading_companion_run_detail/" +
                    "index.ui.js",
            )
        assertTrue(runDetail.contains("openReadingAuditChat"))
        assertTrue(runDetail.contains("Open subagent chat"))
        assertTrue(runDetail.contains("subagentRunId"))
    }

    @Test
    fun `no direct segment-comment fallback remains anywhere in the reading feature`() {
        val targets =
            listOf(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionAutoCommentary.kt",
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionBridge.kt",
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentCoordinator.kt",
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionStore.kt",
            )
        targets.forEach { path ->
            assertEquals(
                "$path 不允许出现单发段评调用",
                0,
                Regex("modelGateway\\.generateAutoComments\\(")
                    .findAll(source(path))
                    .count(),
            )
        }
    }

    @Test
    fun `main companion uses local grep and removes semantic search exposure`() {
        val promptSource = source("../examples/reading_companion/packages/reading_companion.js")
        val packageSource = source("../examples/reading_companion/packages/reading_companion.js")
        assertTrue(promptSource.contains("get_local_files first"))
        assertTrue(promptSource.contains("then read_file"))
        assertTrue(promptSource.contains("content.md"))
        assertTrue(promptSource.contains("Never edit content.md"))
        assertFalse(
            "主工具包元数据不得再暴露复杂 search",
            Regex("\"name\"\\s*:\\s*\"search\"").containsMatchIn(packageSource),
        )
        assertFalse(
            "主工具包不得再导出 search 函数",
            packageSource.contains("exports.search"),
        )
        assertTrue(packageSource.contains("8000 到 96000"))
        assertTrue(packageSource.contains("\"default\": 16000"))
        assertTrue(packageSource.contains("safeSearchPaths"))
        assertTrue(packageSource.contains("allCurrentSearchPaths"))
        assertTrue(promptSource.contains("Never grep chaptersRootPath directly"))
        assertTrue(promptSource.contains("绝不能直接 grep chaptersRootPath"))
    }
}
