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
