package com.ai.assistance.operit.api.chat.llmprovider

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLinkParserTagOrderTest {
    @Test fun selfClosingImage_doesNotConsumeFollowingImageOrText() {
        val input = "<link type=\"image\" id=first/>keep<link id=\"second\" type=\"image\">second</link>end"
        assertEquals(listOf("first", "second"), MediaLinkParser.extractImageLinkIds(input))
        assertEquals("keepend", MediaLinkParser.removeImageLinks(input))
    }

    @Test fun reorderedEscapedImageAttributes_areRecognized() {
        val input = """<link id=\"image1\" src=\"/tmp/a.png\" type=\"image\">I</link>"""
        assertEquals(listOf("image1"), MediaLinkParser.extractImageLinkIds(input))
    }

    @Test fun extractMediaLinkTags_preservesEncounterOrder() {
        val input = "<link type=\"video\" id=\"v1\">V</link><link type=\"audio\" id=\"a1\">A</link>"
        assertEquals(listOf(MediaLinkTag("video", "v1"), MediaLinkTag("audio", "a1")), MediaLinkParser.extractMediaLinkTags(input))
    }

    @Test fun extractImageLinkIds_preservesEncounterOrder() {
        val input = "<link type=\"image\" id=\"i1\">I</link><link type=\"image\" id=\"i2\">I</link>"
        assertEquals(listOf("i1", "i2"), MediaLinkParser.extractImageLinkIds(input))
    }

    // Self-closing links after the last `</link>` still go; links before it end at their `</link>`.
    @Test fun removeImageLinks_readsBothSidesOfTheLastClosing() {
        val input = "<link type=\"image\" id=\"a\"/>x<link type=\"image\" id=\"b\">y</link>z<link type=\"image\" id=\"c\"/>w"
        assertEquals("xzw", MediaLinkParser.removeImageLinks(input))
    }

    @Test(timeout = 2_000L)
    fun removeImageLinks_readsLongRunsOfUnclosedLinksOnce() {
        val tail = "z".repeat(200_000)
        val unclosed = "<link type=\"image\" id=\"a\">".repeat(20_000) + tail
        assertEquals(unclosed, MediaLinkParser.removeImageLinks(unclosed))
        assertEquals("", MediaLinkParser.removeImageLinks("$unclosed</link>"))
    }
}
