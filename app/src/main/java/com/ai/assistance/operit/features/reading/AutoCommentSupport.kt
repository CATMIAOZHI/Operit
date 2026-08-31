package com.ai.assistance.operit.features.reading

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AutoCommentDraft(
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val evidenceIndices: List<Int>,
    val evidenceQuote: String,
)

data class AutoCommentContextChapter(
    val sourceId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val excerptFromEnd: Boolean,
)

/**
 * 单条候选被拒绝时的诊断记录。只携带候选序号与原因码，绝不携带候选正文、
 * 引用段落文本或 quote 原文，避免把书内容回灌给模型。
 */
data class CandidateRejection(
    val candidateNumber: Int,
    val reasons: List<String>,
)

/**
 * 候选解析 + 校验的统一报告。错误诊断部分（code/reasonCounts/rejections）不含任何正文。
 */
data class AutoCommentValidationReport(
    val code: String,
    val inputCount: Int?,
    val accepted: List<AutoCommentDraft>,
    val rejectedCount: Int,
    val reasonCounts: Map<String, Int>,
    val rejections: List<CandidateRejection>,
)

/** 解析阶段的中间结果：errorCode 非空表示 payload 形状非法，items 不可用。 */
data class CandidatePayloadParseResult(
    val items: List<Any?>,
    val errorCode: String?,
)

internal object AutoCommentRunStages {
    const val STARTING = "starting"
    const val READING_TARGET = "reading_target"
    const val PREPARING_CONTEXT = "preparing_context"
    const val RESOLVING_MODEL = "resolving_model"
    const val WAITING_MODEL = "waiting_model"
    const val VALIDATING_RESPONSE = "validating_response"
    const val SAVING_COMMENTS = "saving_comments"
    const val COMPLETED = "completed"

    val ordered = listOf(
        READING_TARGET,
        PREPARING_CONTEXT,
        RESOLVING_MODEL,
        WAITING_MODEL,
        VALIDATING_RESPONSE,
        SAVING_COMMENTS,
        COMPLETED,
    )
}

/**
 * Keeps the read-only annotation surface independent from the optional generator switch.
 *
 * This is deliberately a pure policy so the distinction is regression-tested without requiring
 * Android PackageManager state: the parent ToolPkg controls whether stored comments are exposed,
 * while the auto-commentary subpackage controls only new generation.
 */
internal object AutoCommentSurfacePolicy {
    fun canReadStoredComments(readingCompanionEnabled: Boolean): Boolean =
        readingCompanionEnabled

    fun canGenerate(
        readingCompanionEnabled: Boolean,
        autoCommentaryEnabled: Boolean,
    ): Boolean = readingCompanionEnabled && autoCommentaryEnabled
}

internal object AutoCommentSupport {
    private val paragraphIdPattern = Regex("""^p(\d{1,6})$""", RegexOption.IGNORE_CASE)

    fun paragraphs(content: String): List<String> =
        content.split('\n').map(String::trimEnd)

    fun unlockedParagraphIndex(content: String, isComplete: Boolean): Int {
        if (content.isBlank()) return 0
        val paragraphs = content.split('\n')
        val fullyReadParagraphs =
            if (isComplete || content.endsWith('\n')) {
                paragraphs
            } else {
                paragraphs.dropLast(1)
            }
        return fullyReadParagraphs.indexOfLast(String::isNotBlank) + 1
    }

    fun labeledParagraphs(paragraphs: List<String>): String =
        paragraphs.mapIndexed { index, text ->
            labeledParagraph(index + 1, text)
        }.joinToString("\n")

    fun labeledParagraph(index: Int, text: String): String =
        """<p id="${paragraphId(index)}">${escapeXml(text)}</p>"""

    fun selectPreviousContext(
        chaptersNearestFirst: List<AnnotationChapterContent>,
        maximumCharacters: Int = MAX_PREVIOUS_CONTEXT_CHARS,
    ): List<AutoCommentContextChapter> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        val selectedNearestFirst = mutableListOf<AutoCommentContextChapter>()
        for (chapter in chaptersNearestFirst.take(MAX_PREVIOUS_CONTEXT_CHAPTERS)) {
            if (remaining == 0) break
            val fullContent = chapter.content.trim()
            if (fullContent.isBlank()) continue
            val includedContent = if (fullContent.length <= remaining) {
                fullContent
            } else {
                fullContent.takeLast(remaining)
            }
            selectedNearestFirst += AutoCommentContextChapter(
                sourceId = chapter.sourceId,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.chapterTitle,
                content = includedContent,
                excerptFromEnd = includedContent.length < fullContent.length,
            )
            remaining -= includedContent.length
        }
        return selectedNearestFirst.asReversed()
    }

    fun trimPreviousContext(
        chaptersChronological: List<AutoCommentContextChapter>,
        maximumCharacters: Int,
    ): List<AutoCommentContextChapter> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        val selectedNearestFirst = mutableListOf<AutoCommentContextChapter>()
        for (chapter in chaptersChronological.asReversed()) {
            if (remaining == 0) break
            val content = chapter.content.trim()
            if (content.isBlank()) continue
            val includedContent = if (content.length <= remaining) {
                content
            } else {
                content.takeLast(remaining)
            }
            selectedNearestFirst += chapter.copy(
                content = includedContent,
                excerptFromEnd = chapter.excerptFromEnd || includedContent.length < content.length,
            )
            remaining -= includedContent.length
        }
        return selectedNearestFirst.asReversed()
    }

    /**
     * [selectPreviousContext] removes leading/trailing whitespace before storing either the full
     * chapter or its tail excerpt. Compare the re-read provider body using the same normalization;
     * otherwise a stable trailing newline is incorrectly reported as a changed chapter.
     */
    fun previousContextStillMatches(
        latestContent: String,
        captured: AutoCommentContextChapter,
    ): Boolean {
        val normalizedLatest = latestContent.trim()
        return if (captured.excerptFromEnd) {
            normalizedLatest.endsWith(captured.content)
        } else {
            normalizedLatest == captured.content
        }
    }

    fun labeledPreviousContext(chapters: List<AutoCommentContextChapter>): String =
        chapters.joinToString("\n") { chapter ->
            """
            <previous_chapter>
            <chapter_number>${chapter.chapterIndex + 1}</chapter_number>
            <chapter_title>${escapeXml(chapter.chapterTitle)}</chapter_title>
            <excerpt_from_end>${chapter.excerptFromEnd}</excerpt_from_end>
            <content>${escapeXml(chapter.content)}</content>
            </previous_chapter>
            """.trimIndent()
        }

    /**
     * 阶段一：解析候选 payload，接受两种形状：
     * - 裸 JSON 数组：`[{...}, {...}]`（与工具描述/示例一致）
     * - 包装对象：`{"comments":[{...}]}`（历史兼容）
     * 其他任何输入（空串、非 JSON、JSON 字符串/数字、包装对象缺 comments、
     * comments 非数组）都返回 invalid_json_shape。
     */
    fun parseCandidatePayload(rawJson: String): CandidatePayloadParseResult {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return CandidatePayloadParseResult(emptyList(), REPORT_INVALID_JSON_SHAPE)
        }
        return try {
            when {
                trimmed.startsWith('[') -> {
                    val array = JSONArray(trimmed)
                    CandidatePayloadParseResult(
                        items = buildList {
                            repeat(array.length()) { add(array.get(it)) }
                        },
                        errorCode = null,
                    )
                }
                trimmed.startsWith('{') -> {
                    val root = JSONObject(trimmed)
                    val comments =
                        root.opt("comments")
                            ?: return CandidatePayloadParseResult(
                                emptyList(),
                                REPORT_INVALID_JSON_SHAPE,
                            )
                    if (comments !is JSONArray) {
                        return CandidatePayloadParseResult(emptyList(), REPORT_INVALID_JSON_SHAPE)
                    }
                    CandidatePayloadParseResult(
                        items = buildList {
                            repeat(comments.length()) { add(comments.get(it)) }
                        },
                        errorCode = null,
                    )
                }
                else -> CandidatePayloadParseResult(emptyList(), REPORT_INVALID_JSON_SHAPE)
            }
        } catch (exception: JSONException) {
            CandidatePayloadParseResult(emptyList(), REPORT_INVALID_JSON_SHAPE)
        }
    }

    /**
     * 阶段二：逐条校验候选。接受/拒绝语义与原 parseAndValidate 完全一致
     * （不放松任何安全校验），仅新增错误码诊断。部分成功仍发布：
     * 至少一条有效即 code=submitted。
     */
    fun validateCandidates(
        items: List<Any?>,
        paragraphs: List<String>,
        maximumComments: Int,
    ): AutoCommentValidationReport {
        val accepted = mutableListOf<AutoCommentDraft>()
        val seenParagraphs = hashSetOf<Int>()
        val seenTexts = hashSetOf<String>()
        val rejections = mutableListOf<CandidateRejection>()
        val reasonCounts = mutableMapOf<String, Int>()

        fun reject(candidateNumber: Int, reasons: List<String>) {
            rejections += CandidateRejection(candidateNumber, reasons.distinct())
            reasons.distinct().forEach { reason ->
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            }
        }

        items.forEachIndexed { index, item ->
            val candidateNumber = index + 1
            if (item !is JSONObject) {
                reject(candidateNumber, listOf(REASON_INVALID_ITEM_SHAPE))
                return@forEachIndexed
            }
            val paragraphIndex = parseParagraphId(item.optString("anchorId"))
            if (paragraphIndex == null || paragraphIndex !in 1..paragraphs.size) {
                reject(candidateNumber, listOf(REASON_INVALID_ANCHOR))
                return@forEachIndexed
            }
            if (paragraphIndex in seenParagraphs) {
                reject(candidateNumber, listOf(REASON_DUPLICATE_ANCHOR))
                return@forEachIndexed
            }
            val text = item.optString("text").trim().take(MAX_COMMENT_LENGTH)
            if (text.isBlank()) {
                reject(candidateNumber, listOf(REASON_EMPTY_TEXT))
                return@forEachIndexed
            }
            val normalizedText = text.replace(Regex("""\s+"""), "")
            if (normalizedText in seenTexts) {
                reject(candidateNumber, listOf(REASON_DUPLICATE_TEXT))
                return@forEachIndexed
            }
            val kind = item.optString("kind", "reaction")
                .trim()
                .lowercase()
                .ifBlank { "reaction" }
                .take(MAX_KIND_LENGTH)
            val evidence = parseEvidence(item.optJSONArray("evidenceIds"))
                .ifEmpty { listOf(paragraphIndex) }
                .distinct()
            val evidenceReasons = mutableListOf<String>()
            for (evidenceIndex in evidence) {
                if (evidenceIndex !in 1..paragraphs.size) {
                    evidenceReasons += REASON_INVALID_EVIDENCE_ID
                } else if (evidenceIndex > paragraphIndex) {
                    evidenceReasons += REASON_EVIDENCE_AFTER_ANCHOR
                }
            }
            if (evidenceReasons.isNotEmpty()) {
                reject(candidateNumber, evidenceReasons)
                return@forEachIndexed
            }
            if (paragraphIndex !in evidence) {
                reject(candidateNumber, listOf(REASON_MISSING_ANCHOR_EVIDENCE))
                return@forEachIndexed
            }
            val evidenceQuote = item.optString("evidenceQuote").trim().take(MAX_QUOTE_LENGTH)
            if (
                evidenceQuote.isNotBlank() &&
                evidence.none { evidenceIndex ->
                    paragraphs[evidenceIndex - 1].contains(evidenceQuote)
                }
            ) {
                reject(candidateNumber, listOf(REASON_QUOTE_NOT_FOUND))
                return@forEachIndexed
            }
            seenParagraphs.add(paragraphIndex)
            seenTexts.add(normalizedText)
            accepted += AutoCommentDraft(
                paragraphIndex = paragraphIndex,
                text = text,
                kind = kind,
                evidenceIndices = evidence,
                evidenceQuote = evidenceQuote,
            )
        }

        // 只按全局硬顶 MAX_COMMENTS 截断：不做任何按字数的数量推导，
        // 模型按提示词 0-6 条自由生成，超出 6 条的部分才按锚点顺序丢弃。
        val sortedAccepted = accepted
            .sortedBy(AutoCommentDraft::paragraphIndex)
            .take(MAX_COMMENTS)
        val code =
            when {
                items.isEmpty() -> REPORT_EMPTY_CANDIDATES
                sortedAccepted.isNotEmpty() -> REPORT_SUBMITTED
                else -> REPORT_ALL_CANDIDATES_REJECTED
            }
        return AutoCommentValidationReport(
            code = code,
            inputCount = items.size,
            accepted = sortedAccepted,
            rejectedCount = rejections.size,
            reasonCounts = reasonCounts,
            rejections = rejections,
        )
    }

    /** 统一入口：解析 + 校验，返回诊断报告。 */
    fun parseAndValidateReport(
        rawJson: String,
        paragraphs: List<String>,
        maximumComments: Int,
    ): AutoCommentValidationReport {
        val parsed = parseCandidatePayload(rawJson)
        if (parsed.errorCode != null) {
            return AutoCommentValidationReport(
                code = parsed.errorCode,
                inputCount = null,
                accepted = emptyList(),
                rejectedCount = 0,
                reasonCounts = emptyMap(),
                rejections = emptyList(),
            )
        }
        return validateCandidates(parsed.items, paragraphs, maximumComments)
    }

    /**
     * 旧接口兼容：返回报告中的 accepted。
     * 保留原语义——非对象输入抛 JSONException；包装对象缺 comments 返回空列表。
     */
    fun parseAndValidate(
        rawJson: String,
        paragraphs: List<String>,
        maximumComments: Int,
    ): List<AutoCommentDraft> {
        val report = parseAndValidateReport(rawJson, paragraphs, maximumComments)
        if (report.code == REPORT_INVALID_JSON_SHAPE) {
            if (rawJson.trim().startsWith("{")) return emptyList()
            throw JSONException("auto comment payload must be a JSON object wrapping a comments array")
        }
        if (report.code == REPORT_EMPTY_CANDIDATES && !rawJson.trim().startsWith("{")) {
            throw JSONException("auto comment payload must be a JSON object wrapping a comments array")
        }
        return report.accepted
    }

    fun evidenceJson(comment: AutoCommentDraft): String = JSONObject().apply {
        put("paragraphs", JSONArray(comment.evidenceIndices))
        put("quote", comment.evidenceQuote)
    }.toString()

    /**
     * 发布段评前的最后一道防线：空列表绝不能替换已发布的旧缓存，
     * 否则一次“模型返回但全部被校验过滤”的运行会清空已有段评。
     * 任何调用方（包括未来的新入口）都必须先经过这里。
     */
    fun requireReplacementComments(comments: List<AutoCommentRecord>): List<AutoCommentRecord> {
        require(comments.isNotEmpty()) {
            "replaceAutoComments 拒绝空列表，避免清空已发布段评"
        }
        return comments
    }

    /**
     * 手动入队前判断是否已有生成中的任务。
     *
     * 只看“最近一条 run”会漏报并发：后启动的任务 B 在 claim 阶段发现任务 A 仍持有
     * claim 后会立刻以 already_generating 结束，按 started_at 排序反而排在仍运行的 A
     * 前面，导致后续入队把“已有任务生成中”误判成可入队。这里把判定收敛为纯函数，
     * 任何证据（最新 run 状态 + 是否存在未过期 claim/生成中 run）都由调用方提供，
     * 便于在纯 JVM 测试中覆盖该并发顺序。
     */
    fun shouldRejectManualEnqueue(
        latestRunStatus: String?,
        activeGenerationRunning: Boolean,
    ): Boolean {
        if (latestRunStatus == ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING) {
            return true
        }
        return activeGenerationRunning
    }

    fun paragraphId(index: Int): String = "p${index.toString().padStart(4, '0')}"

    private fun parseEvidence(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return buildList {
            repeat(array.length().coerceAtMost(MAX_EVIDENCE_IDS)) { index ->
                parseParagraphId(array.optString(index))?.let(::add)
            }
        }
    }

    private fun parseParagraphId(value: String): Int? =
        paragraphIdPattern.matchEntire(value.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    const val GENERATION_POLICY_VERSION = 3
    const val REPORT_INVALID_JSON_SHAPE = "invalid_json_shape"
    const val REPORT_EMPTY_CANDIDATES = "empty_candidates"
    const val REPORT_ALL_CANDIDATES_REJECTED = "all_candidates_rejected"
    const val REPORT_SUBMITTED = "submitted"

    const val REASON_INVALID_ITEM_SHAPE = "invalid_item_shape"
    const val REASON_INVALID_ANCHOR = "invalid_anchor"
    const val REASON_DUPLICATE_ANCHOR = "duplicate_anchor"
    const val REASON_EMPTY_TEXT = "empty_text"
    const val REASON_DUPLICATE_TEXT = "duplicate_text"
    const val REASON_INVALID_EVIDENCE_ID = "invalid_evidence_id"
    const val REASON_MISSING_ANCHOR_EVIDENCE = "missing_anchor_evidence"
    const val REASON_EVIDENCE_AFTER_ANCHOR = "evidence_after_anchor"
    const val REASON_QUOTE_NOT_FOUND = "quote_not_found"

    const val MAX_PREVIOUS_CONTEXT_CHAPTERS = 8
    const val MAX_PREVIOUS_CONTEXT_CHARS = 48_000
    const val DEFAULT_PREFETCH_AHEAD_CHAPTERS = 5
    const val MIN_PREFETCH_AHEAD_CHAPTERS = 1
    const val MAX_PREFETCH_AHEAD_CHAPTERS = 10
    /** 某章最近一次失败/无有效段评后的重试冷静期：避免流水线反复烧 Token 重试同一章。 */
    const val RETRY_FAILED_CHAPTER_AFTER_MS = 15 * 60_000L

    fun clampPrefetchAheadChapters(value: Int): Int =
        value.coerceIn(MIN_PREFETCH_AHEAD_CHAPTERS, MAX_PREFETCH_AHEAD_CHAPTERS)

    /**
     * 预取窗口上界（含）：current + ahead 的饱和加法。所有“窗口扫描/清理/保存校验”
     * 必须统一使用该函数，避免极端章节索引下不同路径计算出不一致的窗口。
     */
    fun prefetchWindowUpperIndex(currentChapterIndex: Int, prefetchAhead: Int): Int {
        val ahead = clampPrefetchAheadChapters(prefetchAhead)
        return if (currentChapterIndex > Int.MAX_VALUE - ahead) {
            Int.MAX_VALUE
        } else {
            currentChapterIndex + ahead
        }
    }

    /**
     * 提前生成窗口 (currentChapterIndex, currentChapterIndex + prefetchAhead] 内第一个
     * “缺失”章节：必须存在于章节列表、未被 isCovered 覆盖、且不在失败重试冷静期
     * （isCoolingDown）内。窗口内全部已覆盖或冷却时返回 null（无需再生成）。
     */
    fun firstMissingPrefetchChapter(
        currentChapterIndex: Int,
        prefetchAhead: Int,
        chapterIndices: Set<Int>,
        isCovered: (Int) -> Boolean,
        isCoolingDown: (Int) -> Boolean = { false },
    ): Int? {
        val clampedAhead = clampPrefetchAheadChapters(prefetchAhead)
        if (clampedAhead <= 0) return null
        val upper = prefetchWindowUpperIndex(currentChapterIndex, clampedAhead)
        if (currentChapterIndex >= upper) return null
        for (chapterIndex in (currentChapterIndex + 1)..upper) {
            if (chapterIndex !in chapterIndices) continue
            if (isCovered(chapterIndex)) continue
            if (isCoolingDown(chapterIndex)) continue
            return chapterIndex
        }
        return null
    }

    private const val MAX_COMMENT_LENGTH = 80
    private const val MAX_QUOTE_LENGTH = 120
    private const val MAX_KIND_LENGTH = 32
    private const val MAX_EVIDENCE_IDS = 8
    internal const val MAX_COMMENTS = 6
}
