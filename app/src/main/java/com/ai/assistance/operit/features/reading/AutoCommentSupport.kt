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

internal object AutoCommentSupport {
    private val paragraphIdPattern = Regex("""^p(\d{1,6})$""", RegexOption.IGNORE_CASE)
    private val inferencePattern = Regex(
        """其实|原来|果然|说明|因为|终于|真相|伏笔|注定|将会|会被|要死|完了|幕后|身份|凶手|背叛|骗[了着]?|早就""",
    )
    private val pureReactionPattern = Regex(
        """^(?:卧槽|我靠|牛逼|好家伙|笑死|绷不住了|绝了|妙啊|爽|啊|哈哈+|呜呜+|离谱|逆天|草|艹|666|啧|嘶|哦豁|哇|哎哟)[！!？?…~。]*$""",
    )
    private val analyticalKinds = setOf(
        "analysis",
        "callback",
        "foreshadowing",
        "prediction",
        "character",
        "plot",
        "explanation",
    )
    private val lowRiskKinds = setOf("reaction", "banter")

    fun paragraphs(content: String): List<String> =
        content.split('\n').map(String::trimEnd)

    fun targetCount(characterCount: Int): Int = when {
        characterCount < 2_000 -> 5
        characterCount < 4_000 -> 9
        characterCount < 7_000 -> 14
        else -> 16
    }

    fun labeledParagraphs(paragraphs: List<String>): String =
        paragraphs.mapIndexed { index, text ->
            labeledParagraph(index + 1, text)
        }.joinToString("\n")

    fun labeledParagraph(index: Int, text: String): String =
        """<p id="${paragraphId(index)}">${escapeXml(text)}</p>"""

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

    fun isHighRisk(comment: AutoCommentDraft): Boolean {
        if (comment.kind in analyticalKinds) return true
        if (comment.evidenceIndices.size > 1) return true
        if (comment.text.length > LOW_RISK_MAX_LENGTH) return true
        if (inferencePattern.containsMatchIn(comment.text)) return true
        if (comment.kind !in lowRiskKinds) return true
        return !pureReactionPattern.matches(comment.text)
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

    private const val LOW_RISK_MAX_LENGTH = 18
    private const val MAX_COMMENT_LENGTH = 120
    private const val MAX_QUOTE_LENGTH = 120
    private const val MAX_KIND_LENGTH = 32
    private const val MAX_EVIDENCE_IDS = 8
    private const val MAX_COMMENTS = 16
}
