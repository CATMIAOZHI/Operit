package com.ai.assistance.operit.api.chat.llmprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinkParserImageSourceTest {

    @Test fun buildImageLink_withoutSourcePathKeepsOriginalShape() {
        assertEquals(
            "<link type=\"image\" id=\"img1\"></link>",
            MediaLinkParser.buildImageLink("img1")
        )
    }

    @Test fun buildImageLink_withSourcePathAddsSrcAttribute() {
        assertEquals(
            "<link type=\"image\" id=\"img1\" src=\"/sdcard/a.png\"></link>",
            MediaLinkParser.buildImageLink("img1", "/sdcard/a.png")
        )
    }

    @Test fun buildImageLink_ignoresBlankSourcePath() {
        assertEquals(
            "<link type=\"image\" id=\"img1\"></link>",
            MediaLinkParser.buildImageLink("img1", "   ")
        )
    }

    @Test fun buildImageLink_escapesSourcePath() {
        val link = MediaLinkParser.buildImageLink("img1", "/sdcard/a & \"b\"<c>.png")

        assertEquals(
            "<link type=\"image\" id=\"img1\" src=\"/sdcard/a &amp; &quot;b&quot;&lt;c&gt;.png\"></link>",
            link
        )
    }

    @Test fun extractImageLinkTags_readsAndUnescapesSourcePath() {
        val link = MediaLinkParser.buildImageLink("img1", "/sdcard/我的图片 & 素材/a.png")

        val tags = MediaLinkParser.extractImageLinkTags("$link 后续文字")

        assertEquals(listOf("img1"), tags.map { it.id })
        assertEquals("/sdcard/我的图片 & 素材/a.png", tags.single().sourcePath)
    }

    @Test fun extractImageLinkTags_returnsNullSourcePathWhenAbsent() {
        val tags = MediaLinkParser.extractImageLinkTags("<link type=\"image\" id=\"img1\"></link>")

        assertEquals(listOf(ImageLinkTag(id = "img1")), tags)
        assertNull(tags.single().sourcePath)
    }

    @Test fun extractImageLinkTags_toleratesUnquotedSourcePath() {
        val tags = MediaLinkParser.extractImageLinkTags("<link type=\"image\" id=\"img1\" src=/sdcard/a.png></link>")

        assertEquals("/sdcard/a.png", tags.single().sourcePath)
    }

    @Test fun imageLinkApisStillMatchLinksWithSourceAttribute() {
        val content = "前<link type=\"image\" id=\"img1\" src=\"/sdcard/a.png\"></link>后"

        assertEquals(listOf("img1"), MediaLinkParser.extractImageLinkIds(content))
        assertTrue(MediaLinkParser.hasImageLinks(content))
        assertEquals("前后", MediaLinkParser.removeImageLinks(content))
        assertEquals("前[img1]后", MediaLinkParser.replaceImageLinks(content) { id -> "[$id]" })
    }

    @Test fun extractImageLinkTags_keepsOrderAndDeduplicatesAcrossSourceVariants() {
        val first = MediaLinkParser.buildImageLink("img1", "/sdcard/a.png")
        val second = MediaLinkParser.buildImageLink("img2", "/sdcard/b.png")

        val tags = MediaLinkParser.extractImageLinkTags("$first$second$first")

        assertEquals(listOf("img1", "img2"), tags.map { it.id })
        assertEquals(listOf("/sdcard/a.png", "/sdcard/b.png"), tags.map { it.sourcePath })
    }

    @Test fun malformedImageTagDoesNotSwallowFollowingLinkTags() {
        // 模型写出缺 '>' 的图片标签时，收尾段不能跨过后面的 '<link>'。
        val content =
            "before <link type=\"image\" id=\"img1\" junk <link type=\"audio\" id=\"aud1\">Sound</link> after"

        assertTrue(MediaLinkParser.extractImageLinkTags(content).isEmpty())
        assertEquals(
            "before <link type=\"image\" id=\"img1\" junk <link type=\"audio\" id=\"aud1\">Sound</link> after",
            MediaLinkParser.removeImageLinks(content)
        )
        assertTrue(MediaLinkParser.extractMediaLinkTags(content).map { it.id }.contains("aud1"))
    }

    @Test fun sourcePathIsOnlyReadFromTheOpenTag() {
        // 标签正文里出现 src= 不是属性。
        val content = "<link type=\"image\" id=\"img1\">look at src=/etc/passwd here</link>"

        assertNull(MediaLinkParser.extractImageLinkTags(content).single().sourcePath)
    }

    @Test fun sourcePathIgnoresPrefixedAttributeNames() {
        val content = "<link type=\"image\" id=\"img1\" data-src=\"/sdcard/a.png\"></link>"

        assertNull(MediaLinkParser.extractImageLinkTags(content).single().sourcePath)
    }

    @Test fun escapedTagSourcePathHasNoTrailingBackslash() {
        // 转义形态（模型/MCP 拼出的 src=\"…\"）：收尾反斜杠不能留在路径里，
        // 否则 File.exists() 永远失败，恢复静默退回“已过期”。
        val content = "<link type=\\\"image\\\" id=\\\"img1\\\" src=\\\"/sdcard/a.png\\\"></link>"

        val tag = MediaLinkParser.extractImageLinkTags(content).single()

        assertEquals("img1", tag.id.removeSuffix("\\"))
        assertEquals("/sdcard/a.png", tag.sourcePath)
    }
}
