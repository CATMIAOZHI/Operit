package com.ai.assistance.operit.features.reading

import org.json.JSONArray
import org.json.JSONObject

data class AutoCommentDraft(
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val evidenceIndices: List<Int>,
    val evidenceQuote: String,
)

data class AutoCommentContextChapter(
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val excerptFromEnd: Boolean,
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

    fun targetCount(content: String): Int {
        val readableCharacters = content.count { character -> !character.isWhitespace() }
        return ((readableCharacters + CHARACTERS_PER_COMMENT - 1) / CHARACTERS_PER_COMMENT)
            .coerceIn(MIN_COMMENTS, MAX_COMMENTS)
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

    fun parseAndValidate(
        rawJson: String,
        paragraphs: List<String>,
        maximumComments: Int,
    ): List<AutoCommentDraft> {
        val root = JSONObject(rawJson)
        val array = root.optJSONArray("comments") ?: JSONArray()
        val accepted = mutableListOf<AutoCommentDraft>()
        val seenParagraphs = hashSetOf<Int>()
        val seenTexts = hashSetOf<String>()
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            val paragraphIndex = parseParagraphId(item.optString("anchorId")) ?: return@repeat
            if (paragraphIndex !in 1..paragraphs.size || paragraphIndex in seenParagraphs) {
                return@repeat
            }
            val text = item.optString("text").trim().take(MAX_COMMENT_LENGTH)
            val normalizedText = text.replace(Regex("""\s+"""), "")
            if (text.isBlank() || normalizedText in seenTexts) return@repeat
            val kind = item.optString("kind", "reaction")
                .trim()
                .lowercase()
                .ifBlank { "reaction" }
                .take(MAX_KIND_LENGTH)
            val evidence = parseEvidence(item.optJSONArray("evidenceIds"))
                .ifEmpty { listOf(paragraphIndex) }
                .distinct()
            if (
                evidence.any { it !in 1..paragraphIndex } ||
                paragraphIndex !in evidence
            ) {
                return@repeat
            }
            val evidenceQuote = item.optString("evidenceQuote").trim().take(MAX_QUOTE_LENGTH)
            if (
                evidenceQuote.isNotBlank() &&
                evidence.none { evidenceIndex ->
                    paragraphs[evidenceIndex - 1].contains(evidenceQuote)
                }
            ) {
                return@repeat
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
        return accepted
            .sortedBy(AutoCommentDraft::paragraphIndex)
            .take(maximumComments.coerceIn(1, MAX_COMMENTS))
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
    const val MAX_PREVIOUS_CONTEXT_CHAPTERS = 8
    const val MAX_PREVIOUS_CONTEXT_CHARS = 48_000

    private const val CHARACTERS_PER_COMMENT = 1600
    private const val MIN_COMMENTS = 2
    private const val MAX_COMMENT_LENGTH = 80
    private const val MAX_QUOTE_LENGTH = 120
    private const val MAX_KIND_LENGTH = 32
    private const val MAX_EVIDENCE_IDS = 8
    private const val MAX_COMMENTS = 6
}
