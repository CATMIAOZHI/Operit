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
 * 图片链接标签。图片池是会话级缓存，[sourcePath] 记录原始文件路径，
 * 池里图片被回收后可以凭它重新入池。
 */
data class ImageLinkTag(
    val id: String,
    val sourcePath: String? = null,
)

object MediaLinkParser {
    // id 之后可能还有别的属性（例如 src），所以用 [^<>]*> 收尾，而不是只允许 \s*>。
    // 收尾段排除 '<'：模型写出缺 '>' 的畸形标签时，不会一路吞掉后面的 <link> 标签。
    private val IMAGE_LINK_PATTERN_PLAIN = Regex(
        """<link\s+type\s*=\s*\\?["']?image\\?["']?\s+id\s*=\s*\\?["']?([^"'\s>]+)\\?["']?[^<>]*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val IMAGE_LINK_PATTERN_ESCAPED = Regex(
        """<link\s+type\s*=\s*\\?["']?image\\?["']?\s+id\s*=\s*\\?["']?([^"'\s>]+)\\?["']?[^<>]*>.*?</link>""",
        RegexOption.DOT_MATCHES_ALL
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
        """<link\s+type=\\\"?(audio|video)\\\"?\s+id=\\\"?([^\"\s>]+)\\\"?\s*>.*?</link>""",
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
        val imageLinks = mutableListOf<ImageLink>()
        val seenIds = mutableSetOf<String>()

        fun collectFromPattern(pattern: Regex) {
            pattern.findAll(message).forEach { match ->
                val id = match.groupValues[1]
                if (id == "error") {
                    return@forEach
                }
                if (!seenIds.add(id)) {
                    return@forEach
                }
                val imageData = ImagePoolManager.getImage(id) ?: return@forEach
                imageLinks.add(
                    ImageLink(
                        type = "image",
                        id = id,
                        base64Data = imageData.base64,
                        mimeType = imageData.mimeType
                    )
                )
            }
        }

        collectFromPattern(IMAGE_LINK_PATTERN_PLAIN)
        collectFromPattern(IMAGE_LINK_PATTERN_ESCAPED)

        return imageLinks
    }

    /**
     * 构造图片链接标签。
     *
     * 传入 [sourcePath] 后，标签会带上 `src` 属性；图片池把这张图回收掉时（进程重启、LRU
     * 淘汰），可以凭它重新入池，历史消息不必一直显示“图片已过期”。
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
        return message
            .replace(IMAGE_LINK_PATTERN_PLAIN, "")
            .replace(IMAGE_LINK_PATTERN_ESCAPED, "")
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
        val links = mutableListOf<MediaLink>()
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

                val mediaData = MediaPoolManager.getMedia(id) ?: return@forEach

                val limited = MediaBase64Limiter.limitBase64ForAi(mediaData.base64, mediaData.mimeType)
                    ?: return@forEach
                links.add(
                    MediaLink(
                        type = type,
                        id = id,
                        base64Data = limited.base64,
                        mimeType = limited.mimeType
                    )
                )
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

                val mediaData = MediaPoolManager.getMedia(id) ?: return@forEach
                val limited = MediaBase64Limiter.limitBase64ForAi(mediaData.base64, mediaData.mimeType)
                    ?: return@forEach
                links.add(
                    MediaLink(
                        type = "file",
                        id = id,
                        base64Data = limited.base64,
                        mimeType = limited.mimeType,
                        fileName = fileName,
                    )
                )
            }
        }

        collectFileFromPattern(FILE_LINK_PATTERN_PLAIN)
        collectFileFromPattern(FILE_LINK_PATTERN_ESCAPED)

        return links
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
