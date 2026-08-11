package com.ai.assistance.operit.ui.common.markdown

import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderReasoningDisplayTest {
    @Test
    fun entityDecodeRunsAfterMarkdownClassificationWithoutReparsingToolMarkup() {
        val parsedPlainText =
            MarkdownNodeStable(
                type = MarkdownProcessorType.PLAIN_TEXT,
                content = "reasoning &lt;tool name=\"unsafe\"> &amp; literal &amp;lt;tag&gt;",
                children = emptyList(),
            )

        val decoded = decodeProviderReasoningEntitiesAfterMarkdownParsing(parsedPlainText)

        assertEquals(MarkdownProcessorType.PLAIN_TEXT, decoded.type)
        assertEquals(
            "reasoning <tool name=\"unsafe\"> & literal &lt;tag&gt;",
            decoded.content,
        )
    }

    @Test
    fun entityDecodeRecursesIntoAlreadyClassifiedInlineChildren() {
        val parsedBold =
            MarkdownNodeStable(
                type = MarkdownProcessorType.BOLD,
                content = "**&lt;x>**",
                children =
                    listOf(
                        MarkdownNodeStable(
                            type = MarkdownProcessorType.PLAIN_TEXT,
                            content = "&lt;x>",
                            children = emptyList(),
                        )
                    ),
            )

        val decoded = decodeProviderReasoningEntitiesAfterMarkdownParsing(parsedBold)

        assertEquals("**<x>**", decoded.content)
        assertEquals("<x>", decoded.children.single().content)
    }
}
