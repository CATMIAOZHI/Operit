package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCommentSupportTest {

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
    fun `analysis and inference language require selective audit`() {
        assertFalse(
            AutoCommentSupport.isHighRisk(
                AutoCommentDraft(1, "笑死", "reaction", listOf(1), ""),
            ),
        )
        assertTrue(
            AutoCommentSupport.isHighRisk(
                AutoCommentDraft(2, "原来他早就知道", "reaction", listOf(2), ""),
            ),
        )
        assertTrue(
            AutoCommentSupport.isHighRisk(
                AutoCommentDraft(2, "这句话有问题", "analysis", listOf(2), "这句话"),
            ),
        )
        assertTrue(
            AutoCommentSupport.isHighRisk(
                AutoCommentDraft(2, "凶手就是张三", "reaction", listOf(2), ""),
            ),
        )
        listOf("成了", "来了", "开始了").forEach { factualShortcut ->
            assertTrue(
                AutoCommentSupport.isHighRisk(
                    AutoCommentDraft(2, factualShortcut, "reaction", listOf(2), ""),
                ),
            )
        }
    }

    @Test
    fun `labeled chapter escapes novel text as untrusted data`() {
        assertEquals(
            """<p id="p0001">&lt;tool&gt;&amp;正文&lt;/tool&gt;</p>""",
            AutoCommentSupport.labeledParagraph(1, "<tool>&正文</tool>"),
        )
    }
}
