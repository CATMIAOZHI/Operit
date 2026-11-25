package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.MediaBase64Limiter
import com.ai.assistance.operit.util.MediaPoolManager

data class MediaLink(
    val type: String,
    val id: String,
    val base64Data: String,
    val mimeType: String,
    val fileName: String? = null,
)

data class ImageLink(
    val type: String,
    val id: String,
    val base64Data: String,
    val mimeType: String
)

data class MediaLinkTag(
    val type: String,
    val id: String,
    val fileName: String? = null,
)

/**
 * 图片链接标签。图片池持久保存附件，[sourcePath] 可用于恢复旧版本丢失的图片。
 */
data class ImageLinkTag(
    val id: String,
    val sourcePath: String? = null,
)

object MediaLinkParser {
    // id 之后可能还有别的属性（例如 src），所以用 [^<>]*> 收尾，而不是只允许 \s*>。
    // 收尾段排除 '<'：模型写出缺 '>' 的畸形标签时，不会一路吞掉后面的 <link> 标签。
    private const val IMAGE_LINK_OPENING_SOURCE =
        """<link\b(?=[^<>]*(?<![-\w])type\s*=\s*\\*["']?image\\*["']?(?=[\s/>]))(?=[^<>]*(?<![-\w])id\s*=\s*\\*["']?([^"'\\\s/>]+)\\*["']?)[^<>]*?"""

    private val IMAGE_LINK_PATTERN_PLAIN = Regex(
        IMAGE_LINK_OPENING_SOURCE + """(?:/>|>.*?</link>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val IMAGE_LINK_PATTERN_ESCAPED = IMAGE_LINK_PATTERN_PLAIN

    // The same link when it can only end as `/>`: after the text's last `</link>`.
    private val SELF_CLOSING_IMAGE_LINK_PATTERN = Regex(
        IMAGE_LINK_OPENING_SOURCE + "/>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // (?<![-\w]) 而不是 \b：data-src 之类的属性不算 src。
    // 取值用惰性匹配，好让转义形态（src=\"…\"）的收尾反斜杠被 \\? 吃掉，不留在路径里。
    private val IMAGE_SRC_ATTRIBUTE_PATTERN =
        Regex("""(?<![-\w])src\s*=\s*\\?["']([^"']*?)\\?["']""")

    private val IMAGE_SRC_ATTRIBUTE_UNQUOTED_PATTERN =
        Regex("""(?<![-\w])src\s*=\s*\\?([^"'\s>]+)""")

    private val LINK_PATTERN_PLAIN = Regex(
        """<link\s+type=\"?(audio|video)\"?\s+id=\"?([^\"\s>]+)\"?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val LINK_PATTERN_ESCAPED = Regex(
        """<link\s+type=\\\"?(audio|video)\\\"?\s+id=\\\"?([^\"\\\s>]+)\\\"?\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val FILE_LINK_PATTERN_PLAIN = Regex(
        """<link\s+type\s*=\s*\"file\"\s+id\s*=\s*\"([^\"]+)\"\s+filename\s*=\s*\"([^\"]+)\"\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val FILE_LINK_PATTERN_ESCAPED = Regex(
        """<link\s+type=\\\"file\\\"\s+id=\\\"([^\\\"]+)\\\"\s+filename=\\\"([^\\\"]+)\\\"\s*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun extractImageLinks(message: String): List<ImageLink> {
        return extractImageLinkTags(message).mapNotNull { tag ->
            val data = ImagePoolManager.resolveImage(tag.id, tag.sourcePath) ?: return@mapNotNull null
            ImageLink(type = "image", id = tag.id, base64Data = data.base64, mimeType = data.mimeType)
        }
    }

    /**
     * 构造图片链接标签。
     *
     * 传入 [sourcePath] 后，标签会带上 `src` 属性，用于恢复旧版本丢失的图片。
     */
    fun buildImageLink(id: String, sourcePath: String? = null): String {
        val escapedSourcePath =
            sourcePath?.takeIf { it.isNotBlank() }?.let { MediaLinkBuilder.escapeXmlAttribute(it) }
        return if (escapedSourcePath == null) {
            """<link type="image" id="$id"></link>"""
        } else {
            """<link type="image" id="$id" src="$escapedSourcePath"></link>"""
        }
    }

    fun extractImageLinkTags(message: String): List<ImageLinkTag> {
        val tags = mutableListOf<ImageLinkTag>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val id = match.groupValues[1]
                if (id == "error") {
                    return@forEach
                }
                if (seenIds.add(id)) {
                    tags.add(ImageLinkTag(id = id, sourcePath = extractImageSourcePath(match.value)))
                }
            }
        }

        collectFromPattern(IMAGE_LINK_PATTERN_PLAIN)
        collectFromPattern(IMAGE_LINK_PATTERN_ESCAPED)

        return tags
    }

    fun extractImageLinkIds(message: String): List<String> =
        extractImageLinkTags(message).map { it.id }

    private fun extractImageSourcePath(tag: String): String? {
        // 只扫开标签：标签正文（模型输出、用户文本）里出现的 src= 不是属性。
        val openTag = tag.substringBefore('>')
        val quoted = IMAGE_SRC_ATTRIBUTE_PATTERN.find(openTag)?.groupValues?.getOrNull(1)
        val sourcePath =
            quoted?.takeIf { it.isNotBlank() }
                ?: IMAGE_SRC_ATTRIBUTE_UNQUOTED_PATTERN.find(openTag)?.groupValues?.getOrNull(1)
        return sourcePath?.takeIf { it.isNotBlank() }?.let { unescapeXml(it) }
    }

    fun removeImageLinks(message: String): String {
        // The plain and the escaped pattern are one; the second pass is kept for what the first
        // one's removals join up.
        return removeImageLinksOnce(removeImageLinksOnce(message))
    }

    /**
     * [message] without the links [IMAGE_LINK_PATTERN_PLAIN] finds. After the last `</link>` a link
     * can only end as `/>`, and looking for a `</link>` there from every opening would read on to
     * the end each time, so that part is read for `/>` alone. No link spans the split: a tag holds
     * no `<`, and a link ending in `</link>` ends by the last one.
     */
    private fun removeImageLinksOnce(message: String): String {
        if (!message.contains("<link", ignoreCase = true)) return message
        val last = message.lastIndexOf("</link>", ignoreCase = true)
        if (last < 0) return message.replace(SELF_CLOSING_IMAGE_LINK_PATTERN, "")
        val split = last + "</link>".length
        return message.substring(0, split).replace(IMAGE_LINK_PATTERN_PLAIN, "") +
            message.substring(split).replace(SELF_CLOSING_IMAGE_LINK_PATTERN, "")
    }

    fun replaceImageLinks(message: String, replacer: (id: String) -> String): String {
        var result = message
        val patterns = listOf(IMAGE_LINK_PATTERN_PLAIN, IMAGE_LINK_PATTERN_ESCAPED)
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val id = match.groupValues.getOrNull(1) ?: return@replace ""
                if (id == "error") "" else replacer(id)
            }
        }
        return result
    }

    fun hasImageLinks(message: String): Boolean {
        return IMAGE_LINK_PATTERN_PLAIN.containsMatchIn(message) ||
            IMAGE_LINK_PATTERN_ESCAPED.containsMatchIn(message)
    }

    fun extractMediaLinks(message: String): List<MediaLink> {
        return extractMediaLinkTags(message).mapNotNull(::resolveMediaLink)
    }

    internal fun audioLinkRanges(message: String): List<IntRange> {
        if (!message.contains("<link") || !message.contains("audio")) return emptyList()
        return (LINK_PATTERN_PLAIN.findAll(message) + LINK_PATTERN_ESCAPED.findAll(message))
            .filter { it.groupValues[1] == "audio" && it.groupValues[2] != "error" }
            .map { it.range }.sortedBy { it.first }.toList()
    }

    internal fun resolveMediaLink(tag: MediaLinkTag): MediaLink? {
        val data = MediaPoolManager.getMedia(tag.id) ?: return null
        // Both pool writers persist normalized NO_WRAP data. A length check suffices on replay.
        val size = MediaBase64Limiter.estimateDecodedSizeBytes(data.base64, noWrap = true) ?: return null
        if (size > MediaBase64Limiter.DEFAULT_MAX_DECODED_BYTES) return null
        return MediaLink(tag.type, tag.id, data.base64, data.mimeType, tag.fileName)
    }

    fun extractMediaLinkTags(message: String): List<MediaLinkTag> {
        val tags = mutableListOf<MediaLinkTag>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val type = match.groupValues[1]
                val id = match.groupValues[2]
                if (id == "error") {
                    return@forEach
                }
                if (!seenIds.add("$type:$id")) {
                    return@forEach
                }
                tags.add(MediaLinkTag(type = type, id = id))
            }
        }

        collectFromPattern(LINK_PATTERN_PLAIN)
        collectFromPattern(LINK_PATTERN_ESCAPED)

        fun collectFileFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val id = match.groupValues[1]
                val fileName = unescapeXml(match.groupValues[2])
                if (id == "error" || fileName.isBlank() || !seenIds.add("file:$id")) {
                    return@forEach
                }
                tags.add(MediaLinkTag(type = "file", id = id, fileName = fileName))
            }
        }

        collectFileFromPattern(FILE_LINK_PATTERN_PLAIN)
        collectFileFromPattern(FILE_LINK_PATTERN_ESCAPED)

        return tags
    }

    fun replaceMediaLinks(message: String, replacer: (type: String, id: String) -> String): String {
        var result = message
        val mediaPatterns = listOf(
            LINK_PATTERN_PLAIN,
            LINK_PATTERN_ESCAPED,
        )
        mediaPatterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val type = match.groupValues.getOrNull(1) ?: return@replace ""
                val id = match.groupValues.getOrNull(2) ?: return@replace ""
                if (id == "error") "" else replacer(type, id)
            }
        }

        listOf(FILE_LINK_PATTERN_PLAIN, FILE_LINK_PATTERN_ESCAPED).forEach { pattern ->
            result = pattern.replace(result) { match ->
                val id = match.groupValues.getOrNull(1) ?: return@replace ""
                if (id == "error") "" else replacer("file", id)
            }
        }
        return result
    }

    fun removeMediaLinks(message: String): String {
        return message
            .replace(LINK_PATTERN_PLAIN, "")
            .replace(LINK_PATTERN_ESCAPED, "")
            .replace(FILE_LINK_PATTERN_PLAIN, "")
            .replace(FILE_LINK_PATTERN_ESCAPED, "")
    }

    fun hasMediaLinks(message: String): Boolean {
        return LINK_PATTERN_PLAIN.containsMatchIn(message) ||
            LINK_PATTERN_ESCAPED.containsMatchIn(message) ||
            FILE_LINK_PATTERN_PLAIN.containsMatchIn(message) ||
            FILE_LINK_PATTERN_ESCAPED.containsMatchIn(message)
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
