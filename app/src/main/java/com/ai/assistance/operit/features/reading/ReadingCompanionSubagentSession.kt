package com.ai.assistance.operit.features.reading

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读伴读子代理的一次 run 会话状态。
 *
 * 会话以主库 subagent 子聊天 id 为键注册（calerChatId -> child chat -> session），
 * 由 [ReadingCompanionSubagentCoordinator] 在 runTask 前注册、结束后注销。
 * submit_candidate 只把候选写入本会话（不直接 replace）；abstain 只标记弃权。
 */
class ReadingCompanionRunSession(
    val runId: Long,
    val bookId: String,
    val bookName: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val contentHash: String,
    val roleCardId: String,
    val roleCardName: String,
    /** 角色卡完整 CHAT 人设（combinePrompts(CHAT) 输出；任务 prompt 与 get_constraints 共用）。 */
    val rolePrompt: String = "",
    val targetContent: String,
    val previousContext: List<AutoCommentContextChapter>,
    val backend: ReadingCompanionSubagentBackend,
    val loopGuard: ReadingCompanionLoopGuard,
) {
    /** 本次 run 已接受的候选（submit_candidate 写入；发布由协调器走提交底线）。 */
    @Volatile
    var candidateDrafts: List<AutoCommentDraft> = emptyList()
        private set

    /** 模型是否已弃权（abstain）。 */
    @Volatile
    var abstained: Boolean = false
        private set

    /** 停止原因（claim_lost / no_progress / loop_detected）；非空后工具调用立即短路。 */
    @Volatile
    var stoppedReason: String? = null
        private set

    @Synchronized
    fun submitCandidates(drafts: List<AutoCommentDraft>) {
        if (stoppedReason != null) return
        candidateDrafts = drafts
    }

    @Synchronized
    fun markAbstained() {
        if (stoppedReason != null) return
        abstained = true
    }

    @Synchronized
    fun stop(reason: String) {
        if (stoppedReason != null) return
        stoppedReason = reason
    }

    /** 目标章段落列表（锚点校验与候选校验共用）。 */
    fun targetParagraphs(): List<String> = AutoCommentSupport.paragraphs(targetContent)

    /** 目标章建议段评数量（与单发路径同一策略）。 */
    fun targetCount(): Int = AutoCommentSupport.targetCount(targetContent)
}

/**
 * 阅读伴读工具执行所需的存储/检索后端抽象。
 *
 * 生产实现包装 [ReadingCompanionStore] 与 [ReadingCompanionService]；JVM 测试注入假实现，
 * 使工具执行器可以脱离 SQLiteOpenHelper 完成白名单、反查、提交底线相关断言。
 */
interface ReadingCompanionSubagentBackend {
    /** claim 心跳：affected != 1 => claim 已丢失，调用方必须立即停止。 */
    fun heartbeatClaimIfOwned(bookId: String, chapterIndex: Int, runId: Long): Boolean

    /** 记录 run trace（不写正文/密钥）。 */
    fun recordAutoCommentRunTrace(
        runId: Long,
        operation: String,
        status: String,
        startedAt: Long,
        finishedAt: Long? = null,
        metadataJson: String? = null,
    )

    /** 阅读侧 run 的工具调用计数（审计展示；不是上限）。 */
    fun incrementRunToolInvocation(runId: Long): Boolean

    /** 阅读侧 run 的模型轮次计数（可观测的模型轮次边界；不是上限）。 */
    fun incrementRunModelRound(runId: Long): Boolean

    /** 已读范围内检索（三级检索 + 读者记忆，单独返回）。 */
    suspend fun search(query: String): JSONObject
}

/**
 * 按 childChatId 注册/查找进行中的阅读伴读子代理会话。
 *
 * ToolExecutionManager 用它与普通 subagent 的第三次弹框路径区分：注册过的 child 走
 * 非交互护栏；工具执行器只用 callerChatId 反查会话，模型传入的 bookId/chapterIndex 一律忽略。
 */
object ReadingCompanionSubagentSessionRegistry {
    private val sessionsByChildChatId = ConcurrentHashMap<String, ReadingCompanionRunSession>()

    fun sessionForChildChat(childChatId: String?): ReadingCompanionRunSession? =
        childChatId?.let { sessionsByChildChatId[it] }

    fun register(childChatId: String, session: ReadingCompanionRunSession) {
        sessionsByChildChatId[childChatId] = session
    }

    fun unregister(childChatId: String) {
        sessionsByChildChatId.remove(childChatId)
    }

    /** 测试用：当前活跃会话数。 */
    fun activeSessionCount(): Int = sessionsByChildChatId.size
}
