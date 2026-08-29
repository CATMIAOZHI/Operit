package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.runBlockingIoPreservingToolRuntimeContext
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读伴侣段评审计子代理的 6 个专用工具。
 *
 * 执行器只信任 callerChatId -> subagent child -> 注册会话的反查结果；模型传入的任何
 * bookId/chapterIndex/书籍参数一律忽略（isolated prompts 也不暴露这些参数）。
 * submit_candidate 只写入本次 run 会话候选，绝不直接 replaceAutoComments；空候选被拒绝，
 * 由模型选择重试或 abstain。每次工具执行前后执行 claim 心跳，affected != 1 即
 * claim_lost 并停止本 run。
 */
object ReadingCompanionSubagentTools {
    const val TOOL_GET_TARGET_CHAPTER = "reading_commentary_get_target_chapter"
    const val TOOL_GET_PREVIOUS_CONTEXT = "reading_commentary_get_previous_context"
    const val TOOL_SEARCH = "reading_commentary_search"
    const val TOOL_GET_CONSTRAINTS = "reading_commentary_get_constraints"
    const val TOOL_SUBMIT_CANDIDATE = "reading_commentary_submit_candidate"
    const val TOOL_ABSTAIN = "reading_commentary_abstain"

    /** 隔离工具面（isolatedToolPrompts 只允许这 6 个名字；第 7 个名字会被硬拒绝）。 */
    val TOOL_NAMES: Set<String> =
        setOf(
            TOOL_GET_TARGET_CHAPTER,
            TOOL_GET_PREVIOUS_CONTEXT,
            TOOL_SEARCH,
            TOOL_GET_CONSTRAINTS,
            TOOL_SUBMIT_CANDIDATE,
            TOOL_ABSTAIN,
        )

    /** 终结性工具：执行后本轮立即收尾，模型不得继续调用其他工具。 */
    val TERMINAL_TOOL_NAMES: Set<String> = setOf(TOOL_SUBMIT_CANDIDATE, TOOL_ABSTAIN)

    /**
     * 内部能力受限工具集合（与 PermissionReviewInternalTools 同语义）：只在校验过的审计
     * 会话内可执行，权限检查直接放行，避免后台/对话内路径弹出确认框。
     */
    val CAPABILITY_BOUND_NAMES: Set<String> = TOOL_NAMES

    private const val ERROR_NO_CALLER =
        "无法确认阅读伴读子代理身份（缺少 callerChatId），本工具仅限段评审计子代理内部调用"
    private const val ERROR_NO_SESSION =
        "未找到阅读伴读执行会话（childChatId 未注册或已完成），无法继续"

    /**
     * 6 个工具的 isolated 提示词（同一来源：ToolRegistration 注册、SubagentCoordinator
     * 透传、测试断言）。
     */
    fun prompts(): List<ToolPrompt> =
        listOf(
            ToolPrompt(
                name = TOOL_GET_TARGET_CHAPTER,
                description =
                    "Read the complete next-chapter text that this audit run must comment on. " +
                        "Paragraphs are labeled with anchor ids like p0001; quote only read " +
                        "content from this chapter. Never outputs content outside the target chapter.",
            ),
            ToolPrompt(
                name = TOOL_GET_PREVIOUS_CONTEXT,
                description =
                    "Read the already-loaded previous-chapter context (up to 8 recent chapters, " +
                    "bounded characters) in reading order. Use it to keep commentary consistent " +
                    "with what already happened.",
            ),
            ToolPrompt(
                name = TOOL_SEARCH,
                description =
                    "Search the strictly read portion of the book (structured knowledge, chapter " +
                    "summaries, read text) plus reader memories, and return evidence for the " +
                    "commentary. Never invents facts beyond the returned evidence.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "query",
                            description = "Question or description of what to recall.",
                        ),
                    ),
            ),
            ToolPrompt(
                name = TOOL_GET_CONSTRAINTS,
                description =
                    "Show the persona (character card) this audit run works for and the exact " +
                    "comment count, length, anchor, kind and evidence rules that submit_candidate " +
                    "will enforce. Call it before drafting candidates.",
            ),
            ToolPrompt(
                name = TOOL_SUBMIT_CANDIDATE,
                description =
                    "Submit the final candidate comments for this run in one call. Provide a JSON " +
                    "comments array; each item uses anchorId (like p0001), text, kind, " +
                    "evidenceIds and optional evidenceQuote exactly as get_constraints specifies. " +
                    "An empty or fully-invalid list is rejected: submit a non-empty valid list or " +
                    "call abstain instead. This ends the turn.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "comments",
                            description =
                                "JSON array of candidate comments, for example " +
                                    "[{\"anchorId\":\"p0001\",\"text\":\"...\",\"kind\":\"reaction\"," +
                                    "\"evidenceIds\":[\"p0001\"],\"evidenceQuote\":\"...\"}]",
                        ),
                    ),
            ),
            ToolPrompt(
                name = TOOL_ABSTAIN,
                description =
                    "Abstain from commenting on this chapter (for example the chapter has no " +
                    "suitable content). Marks the run as abstained and ends the turn; existing " +
                    "comments are never overwritten.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "reason",
                            description = "Short reason for abstaining.",
                            required = false,
                        ),
                    ),
            ),
        )

    /** ToolRegistration 使用的统一执行器入口。 */
    fun execute(tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val callerChatId = runtime?.callerChatId?.takeIf(String::isNotBlank)
        if (callerChatId == null) return failure(tool, ERROR_NO_CALLER)
        val session = ReadingCompanionSubagentSessionRegistry.sessionForChildChat(callerChatId)
        if (session == null) return failure(tool, ERROR_NO_SESSION)
        val stopped = session.stoppedReason
        if (stopped != null) return failure(tool, "生成已停止（$stopped）")
        if (
            !session.backend.heartbeatClaimIfOwned(
                session.bookId,
                session.chapterIndex,
                session.runId,
            )
        ) {
            session.stop("claim_lost")
            return failure(tool, "claim_lost：段评 claim 已被抢占或释放，本 run 停止生成")
        }
        val startedAt = System.currentTimeMillis()
        val executed = try {
            dispatch(tool, session)
        } catch (error: Throwable) {
            failure(tool, safeReadingCompanionError(error))
        }
        val finishedAt = System.currentTimeMillis()
        if (
            session.stoppedReason == null &&
                !session.backend.heartbeatClaimIfOwned(
                    session.bookId,
                    session.chapterIndex,
                    session.runId,
                )
        ) {
            session.stop("claim_lost")
        }
        session.backend.recordAutoCommentRunTrace(
            runId = session.runId,
            operation = "subagent_tool",
            status = if (executed.success) "completed" else "failed",
            startedAt = startedAt,
            finishedAt = finishedAt,
            metadataJson =
                JSONObject()
                    .put("runId", session.runId)
                    .put("tool", tool.name)
                    .put("stoppedReason", session.stoppedReason)
                    .apply {
                        if (!executed.success) put("error", executed.error)
                    }
                    .toString(),
        )
        session.backend.incrementRunToolInvocation(session.runId)
        val evidenceAdvanced =
            tool.name == TOOL_SUBMIT_CANDIDATE && executed.success &&
                session.candidateDrafts.isNotEmpty()
        return when (
            session.loopGuard.recordResult(
                toolName = tool.name,
                normalizedArguments = normalizeReadingCompanionToolCall(tool),
                resultFingerprint = executed.result.toString(),
                evidenceAdvanced = evidenceAdvanced,
            )
        ) {
            ReadingCompanionProgressVerdict.NO_PROGRESS -> {
                session.stop("no_progress")
                executed.copy(
                    error =
                        (executed.error?.let { "$it；" } ?: "") +
                            "no_progress：同一调用与结果重复 3 次且无新增证据，本 run 停止",
                )
            }
            ReadingCompanionProgressVerdict.OK -> executed
        }
    }

    private fun dispatch(
        tool: AITool,
        session: ReadingCompanionRunSession,
    ): ToolResult =
        when (tool.name) {
            TOOL_GET_TARGET_CHAPTER -> getTargetChapter(tool, session)
            TOOL_GET_PREVIOUS_CONTEXT -> getPreviousContext(tool, session)
            TOOL_SEARCH -> search(tool, session)
            TOOL_GET_CONSTRAINTS -> getConstraints(tool, session)
            TOOL_SUBMIT_CANDIDATE -> submitCandidate(tool, session)
            TOOL_ABSTAIN -> abstain(tool, session)
            else -> failure(tool, "未知的阅读伴读审计工具：${tool.name}")
        }

    private fun getTargetChapter(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val paragraphs = session.targetParagraphs()
        val payload =
            JSONObject()
                .put("bookName", session.bookName)
                .put("chapterIndex", session.chapterIndex)
                .put("chapterNumber", session.chapterIndex + 1)
                .put("chapterTitle", session.chapterTitle)
                .put("paragraphCount", paragraphs.size)
                .put("content", AutoCommentSupport.labeledParagraphs(paragraphs))
        return success(tool, payload)
    }

    private fun getPreviousContext(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val chapters =
            JSONArray().apply {
                session.previousContext.forEach { chapter ->
                    put(
                        JSONObject()
                            .put("chapterIndex", chapter.chapterIndex)
                            .put("chapterNumber", chapter.chapterIndex + 1)
                            .put("chapterTitle", chapter.chapterTitle)
                            .put("excerptFromEnd", chapter.excerptFromEnd)
                            .put("content", chapter.content),
                    )
                }
            }
        return success(
            tool,
            JSONObject()
                .put("chapterCount", chapters.length())
                .put("chapters", chapters),
        )
    }

    private fun search(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val query = tool.parameters.firstOrNull { it.name == "query" }?.value?.trim()
        if (query.isNullOrBlank()) {
            return failure(tool, "search 需要非空 query 参数")
        }
        val result = runBlockingIoPreservingToolRuntimeContext {
            session.backend.search(query)
        }
        return success(tool, result)
    }

    private fun getConstraints(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        // 数字与 AutoCommentSupport.parseAndValidate 的接受规则保持一致；提交校验以
        // parseAndValidate 为唯一事实来源，此处文案仅为模型指引。
        val payload =
            JSONObject()
                .put("roleCardName", session.roleCardName)
                .put("roleCardPrompt", session.rolePrompt)
                .put("bookName", session.bookName)
                .put("chapterIndex", session.chapterIndex)
                .put("chapterNumber", session.chapterIndex + 1)
                .put("chapterTitle", session.chapterTitle)
                .put(
                    "rules",
                    "Write 2 to 6 in-character comments for the target chapter only. " +
                        "One comment per paragraph; anchorId like p0001 must exist in the " +
                        "labeled content. Each text is at most 80 characters, concise, " +
                        "in the persona's voice, and must not summarize the whole chapter or " +
                        "spoil anything beyond the anchored paragraph. kind is a short " +
                        "category such as reaction/echo/question. evidenceIds must include " +
                        "the anchored paragraph and stay within this chapter; evidenceQuote " +
                        "must be an exact substring of the cited paragraph. Candidates are " +
                        "deduplicated by paragraph and by text.",
                )
                .put("suggestedCount", session.targetCount())
        return success(tool, payload)
    }

    private fun submitCandidate(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val commentsJson =
            tool.parameters.firstOrNull { it.name == "comments" }?.value?.trim().orEmpty()
        val drafts =
            runCatching {
                    AutoCommentSupport.parseAndValidate(
                        rawJson = commentsJson,
                        paragraphs = session.targetParagraphs(),
                        maximumComments = session.targetCount(),
                    )
                }
                .getOrElse { emptyList() }
        if (drafts.isEmpty()) {
            return failure(
                tool,
                "候选为空或全部校验失败：请提交至少一条有效候选（anchorId/text/kind/evidence " +
                    "按 get_constraints 规则），或调用 abstain 弃权；空列表不会覆盖任何已有段评",
            )
        }
        session.submitCandidates(drafts)
        return success(
            tool,
            JSONObject()
                .put("accepted", drafts.size)
                .put(
                    "anchors",
                    JSONArray().apply {
                        drafts.forEach { put(AutoCommentSupport.paragraphId(it.paragraphIndex)) }
                    },
                )
                .put("status", "submitted"),
        )
    }

    private fun abstain(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val reason =
            tool.parameters.firstOrNull { it.name == "reason" }?.value?.trim()?.take(200)
        session.markAbstained()
        return success(
            tool,
            JSONObject().put("status", "abstained").put("reason", reason ?: ""),
        )
    }

    private fun success(tool: AITool, payload: JSONObject): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = true,
            result = StringResultData(payload.toString()),
        )

    private fun failure(tool: AITool, message: String): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = message,
        )
}

/** 生产实现：包装 reading.db 与伴读服务（search 走同一兼容模型）。 */
class ProductionReadingCompanionSubagentBackend(
    private val store: ReadingCompanionStore,
    private val service: ReadingCompanionService,
) : ReadingCompanionSubagentBackend {
    override fun heartbeatClaimIfOwned(bookId: String, chapterIndex: Int, runId: Long): Boolean =
        store.heartbeatClaimIfOwned(bookId, chapterIndex, runId)

    override fun recordAutoCommentRunTrace(
        runId: Long,
        operation: String,
        status: String,
        startedAt: Long,
        finishedAt: Long?,
        metadataJson: String?,
    ) {
        store.recordAutoCommentRunTrace(
            runId = runId,
            operation = operation,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            metadataJson = metadataJson,
        )
    }

    override fun incrementRunToolInvocation(runId: Long): Boolean =
        store.incrementRunToolInvocation(runId)

    override fun incrementRunModelRound(runId: Long): Boolean =
        store.incrementRunModelRound(runId)

    override suspend fun search(query: String): JSONObject =
        service.search(query, ToolExecutionManager.currentToolRuntimeContext())
}
