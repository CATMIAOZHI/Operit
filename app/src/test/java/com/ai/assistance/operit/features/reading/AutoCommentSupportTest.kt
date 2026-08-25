package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCommentSupportTest {

    @Test
    fun `comment ceiling grows gradually and ignores whitespace`() {
        assertEquals(2, AutoCommentSupport.targetCount("字".repeat(1_599)))
        assertEquals(2, AutoCommentSupport.targetCount("字".repeat(3_200)))
        assertEquals(3, AutoCommentSupport.targetCount("字".repeat(3_201)))
        assertEquals(3, AutoCommentSupport.targetCount("字 ".repeat(3_201)))
        assertEquals(6, AutoCommentSupport.targetCount("字".repeat(20_000)))
    }

    @Test
    fun `previous context keeps nearest chapters and returns chronological order`() {
        val selected = AutoCommentSupport.selectPreviousContext(
            chaptersNearestFirst = listOf(
                annotationChapter(index = 3, title = "第四章", content = "4444"),
                annotationChapter(index = 2, title = "第三章", content = "123456"),
                annotationChapter(index = 1, title = "第二章", content = "不应出现"),
            ),
            maximumCharacters = 7,
        )

        assertEquals(listOf(2, 3), selected.map(AutoCommentContextChapter::chapterIndex))
        assertEquals("456", selected.first().content)
        assertEquals(true, selected.first().excerptFromEnd)
        assertEquals("4444", selected.last().content)
        assertEquals(false, selected.last().excerptFromEnd)
    }

    @Test
    fun `runtime context trimming keeps nearest chapters and tail excerpts`() {
        val selected = AutoCommentSupport.trimPreviousContext(
            chaptersChronological = listOf(
                contextChapter(index = 1, content = "不应出现"),
                contextChapter(index = 2, content = "123456"),
                contextChapter(index = 3, content = "4444"),
            ),
            maximumCharacters = 7,
        )

        assertEquals(listOf(2, 3), selected.map(AutoCommentContextChapter::chapterIndex))
        assertEquals("456", selected.first().content)
        assertEquals(true, selected.first().excerptFromEnd)
        assertEquals("4444", selected.last().content)
    }

    @Test
    fun `whole chapter output keeps only causally anchored comments`() {
        val paragraphs = listOf("第一段", "第二段真相", "第三段")
        val json =
            """
            {
              "comments": [
                {
                  "anchorId": "p0001",
                  "evidenceIds": ["p0001"],
                  "evidenceQuote": "第一段",
                  "text": "牛逼",
                  "kind": "reaction"
                },
                {
                  "anchorId": "p0001",
                  "evidenceIds": ["p0002"],
                  "evidenceQuote": "真相",
                  "text": "原来如此",
                  "kind": "analysis"
                },
                {
                  "anchorId": "p0003",
                  "evidenceIds": ["p0003"],
                  "evidenceQuote": "不存在",
                  "text": "坏了",
                  "kind": "reaction"
                }
              ]
            }
            """.trimIndent()

        val comments = AutoCommentSupport.parseAndValidate(json, paragraphs, 10)

        assertEquals(1, comments.size)
        assertEquals(1, comments.single().paragraphIndex)
        assertEquals("牛逼", comments.single().text)
    }

    @Test
    fun `labeled chapter escapes novel text as untrusted data`() {
        assertEquals(
            """<p id="p0001">&lt;tool&gt;&amp;正文&lt;/tool&gt;</p>""",
            AutoCommentSupport.labeledParagraph(1, "<tool>&正文</tool>"),
        )
    }

    @Test
    fun `reader provider errors expose only fixed public messages`() {
        val error = ReaderProviderException(
            reason = ReaderProviderException.Reason.CONNECTION_FAILED,
            message = "provider secret path and raw payload",
        )

        assertEquals("legado_connection_failed", safeReadingCompanionError(error))
    }

    @Test
    fun `current partial paragraph does not unlock its comment`() {
        assertEquals(
            2,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n第二段\n第三段只读了一半",
                isComplete = false,
            ),
        )
    }

    @Test
    fun `completed chapter unlocks its final paragraph`() {
        assertEquals(
            3,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n第二段\n第三段",
                isComplete = true,
            ),
        )
    }

    @Test
    fun `blank lines preserve original paragraph anchor indices`() {
        assertEquals(
            3,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n\n第二段\n第三段只读了一半",
                isComplete = false,
            ),
        )
    }

    @Test
    fun `missing commentary character has a stable public error code`() {
        assertEquals(
            "role_not_selected",
            safeReadingCompanionError(AutoCommentRoleNotSelectedException()),
        )
        assertEquals(
            "role_unavailable",
            safeReadingCompanionError(AutoCommentRoleUnavailableException()),
        )
        assertEquals(
            "model_context_too_small",
            safeReadingCompanionError(AutoCommentContextTooSmallException()),
        )
    }

    private fun annotationChapter(
        index: Int,
        title: String,
        content: String,
    ) = AnnotationChapterContent(
        bookId = "book",
        chapterIndex = index,
        chapterTitle = title,
        content = content,
        contractHash = "hash-$index",
        capturedAt = 0L,
    )

    private fun contextChapter(
        index: Int,
        content: String,
    ) = AutoCommentContextChapter(
        chapterIndex = index,
        chapterTitle = "第${index + 1}章",
        content = content,
        excerptFromEnd = false,
    )
}
