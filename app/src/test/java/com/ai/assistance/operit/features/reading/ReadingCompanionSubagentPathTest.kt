package com.ai.assistance.operit.features.reading

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成路径的静态契约断言（纯 JVM，无 Android 依赖）。
 *
 * 阶段 3 的验收要求：后台/手动/对话内三条路径全部走 coordinator（subagent），
 * modelGateway.generateAutoComments() 在段评路径零调用（失败不回退单发）；Worker 开头先
 * 跨库对账；新 run 每次全新 child（taskId=null），不续写旧对话。这里直接对源码做结构断言，
 * 防止未来改动悄悄把三条路径混回单发。
 */
class ReadingCompanionSubagentPathTest {

    private val autoCommentarySource: String =
        File(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionAutoCommentary.kt",
        ).readText()

    private val bridgeSource: String =
        File(
            "src/main/java/com/ai/assistance/operit/features/reading/ReadingCompanionBridge.kt",
        ).readText()

    private val coordinatorSource: String =
        File(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionSubagentCoordinator.kt",
        ).readText()

    private val subagentCoordinatorSource: String =
        File(
            "src/main/java/com/ai/assistance/operit/core/agent/SubagentCoordinator.kt",
        ).readText()

    @Test
    fun `all three triggers route through the subagent coordinator`() {
        assertTrue(autoCommentarySource.contains("fun generateViaSubagent("))
        assertTrue(autoCommentarySource.contains("subagentCoordinator.runGeneration("))
        assertTrue(autoCommentarySource.contains("fun usesSubagentExecution(trigger: String): Boolean"))
        assertTrue(
            "后台 trigger 阶段 3 起必须进入 subagent 分支",
            autoCommentarySource.contains(
                "trigger == TRIGGER_BACKGROUND",
            ),
        )
        assertTrue(
            "单发执行器已整体移除",
            !autoCommentarySource.contains("fun generateDirectly("),
        )
    }

    @Test
    fun `no direct segment-comment model call remains and no fallback exists`() {
        assertEquals(
            "generateAutoComments 全文件必须为零（三条路径全部 subagent，无任何回退），实际出现：",
            0,
            Regex("modelGateway\\.generateAutoComments\\(").findAll(autoCommentarySource).count(),
        )
    }

    @Test
    fun `bridge exposes the conversation entry and never trusts JS supplied parentChatId`() {
        assertTrue(bridgeSource.contains("\"request_next_chapter_comments\""))
        assertTrue(bridgeSource.contains("TRIGGER_CONVERSATION"))
        assertTrue(bridgeSource.contains("runtime?.callerChatId"))
        assertTrue(
            "不得信任 JS 传入的 parentChatId",
            !bridgeSource.contains("parentChatId") ||
                bridgeSource.contains("JS 传入的 parentChatId 一律不被信任"),
        )
    }

    @Test
    fun `coordinator creates isolated six-tool subagent turns that end with terminal tools`() {
        assertTrue(coordinatorSource.contains("isolatedToolPrompts = ReadingCompanionSubagentTools.prompts()"))
        assertTrue(coordinatorSource.contains("terminalToolNames ="))
        assertTrue(coordinatorSource.contains("ReadingCompanionSubagentTools.TERMINAL_TOOL_NAMES"))
        assertTrue(coordinatorSource.contains("setOf(ReadingCompanionSubagentTools.TOOL_SUBMIT_SUMMARY)"))
        assertTrue(coordinatorSource.contains("promptHooksEnabled = false"))
        assertTrue(coordinatorSource.contains("functionType = FunctionType.CHAT"))
        assertTrue(coordinatorSource.contains("taskId = null"))
        assertTrue(coordinatorSource.contains("childHidden = !conversation"))
        assertTrue(coordinatorSource.contains("externalOwnerType = ReadingCompanionAudit.OWNER_TYPE"))
        assertTrue(
            "会话必须按真实执行 child 注册（onRunCreated 钩子）",
            coordinatorSource.contains("onRunCreated = { createdRun ->"),
        )
        assertTrue(
            "阅读协调器不得预建 run/child（执行 run 由 SubagentCoordinator 创建）",
            !coordinatorSource.contains("subagentRunRepository.createSubagentChatAndRun"),
        )
    }

    @Test
    fun `subagent task passthrough keeps hidden and owner semantics on the only executed run`() {
        assertTrue(subagentCoordinatorSource.contains("val childHidden: Boolean = false"))
        assertTrue(subagentCoordinatorSource.contains("val externalOwnerType: String? = null"))
        assertTrue(subagentCoordinatorSource.contains("val onRunCreated: (suspend (SubagentRunEntity) -> Unit)? = null"))
        assertTrue(
            "resolveRun 必须把透传字段带进 CreateSubagentRunRequest",
            subagentCoordinatorSource.contains("childHidden = request.childHidden"),
        )
        assertTrue(subagentCoordinatorSource.contains("externalOwnerId = request.externalOwnerId"))
        assertTrue(
            "onRunCreated 必须在创建后立即调用（会话注册先于回合执行）",
            subagentCoordinatorSource.contains("request.onRunCreated?.invoke(created.run)"),
        )
    }

    @Test
    fun `workers reconcile at start and background lineage never resumes old task ids`() {
        assertTrue(autoCommentarySource.contains("reconcileCrossDatabase()"))
        assertTrue(
            "后台 Worker 必须捕获最近 interrupted run 作为谱系",
            autoCommentarySource.contains("AUTO_COMMENT_RUN_STATUS_INTERRUPTED"),
        )
        assertTrue(
            "恢复谱系只作记录，绝不续写旧对话（taskId 恒为 null）",
            coordinatorSource.contains("// 阶段 3 恢复语义：绝不复用旧 taskId，每次全新 child + run。"),
        )
    }
}
