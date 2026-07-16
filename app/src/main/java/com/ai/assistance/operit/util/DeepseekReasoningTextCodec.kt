package com.ai.assistance.operit.util

internal object DeepseekReasoningTextCodec {
    const val ENCODING_ATTRIBUTE = " data-operit-content-encoding=\"xml-text-v1\""
    const val OPENING_TAG = "<think$ENCODING_ATTRIBUTE>"
    const val CLOSING_TAG = "</think>"

    data class HistoryContent(
        val regularContent: String,
        val reasoningContent: String,
    )

    fun encodeBody(raw: String): String = buildString(raw.length) {
        raw.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

    fun decodeBody(encoded: String): String = buildString(encoded.length) {
        var index = 0
        while (index < encoded.length) {
            when {
                encoded.startsWith("&amp;", index) -> {
                    append('&')
                    index += 5
                }

                encoded.startsWith("&lt;", index) -> {
                    append('<')
                    index += 4
                }

                encoded.startsWith("&gt;", index) -> {
                    append('>')
                    index += 4
                }

                else -> append(encoded[index++])
            }
        }
    }

    fun isEncodedThinkBlock(xml: String): Boolean = xml.startsWith(OPENING_TAG)

    fun decodeThinkBodyForPresentation(xml: String, body: String): String {
        return if (isEncodedThinkBlock(xml)) decodeBody(body) else body
    }

    fun decodeMarkedThinkBodiesForPresentation(content: String): String {
        return transformMarkedThinkBodies(content, "<think>")
    }

    fun decodeMarkedThinkBodiesForTokenEstimate(content: String): String {
        return transformMarkedThinkBodies(content, "<think>")
    }

    fun extractHistoryContent(content: String): HistoryContent {
        val regularContent = StringBuilder(content.length)
        val reasoningContent = StringBuilder()
        var hasReasoning = false
        var index = 0

        while (index < content.length) {
            val encodedStart = content.indexOf(OPENING_TAG, index).takeIf { it >= 0 }
            val thinkStart = content.indexOf("<think>", index).takeIf { it >= 0 }
            val thinkingStart = content.indexOf("<thinking>", index).takeIf { it >= 0 }
            val nextStart = listOfNotNull(encodedStart, thinkStart, thinkingStart).minOrNull()

            if (nextStart == null) {
                regularContent.append(content, index, content.length)
                break
            }

            regularContent.append(content, index, nextStart)
            val isEncoded = nextStart == encodedStart
            val openingTag =
                when {
                    isEncoded -> OPENING_TAG
                    nextStart == thinkStart -> "<think>"
                    else -> "<thinking>"
                }
            val closingTag = if (openingTag == "<thinking>") "</thinking>" else CLOSING_TAG
            val bodyStart = nextStart + openingTag.length
            val bodyEnd = content.indexOf(closingTag, bodyStart)

            if (bodyEnd < 0) {
                regularContent.append(content, nextStart, content.length)
                break
            }

            val body = content.substring(bodyStart, bodyEnd)
            if (isEncoded) {
                reasoningContent.append(decodeBody(body))
            } else {
                if (hasReasoning) reasoningContent.append('\n')
                reasoningContent.append(body.trim())
            }
            hasReasoning = true
            index = bodyEnd + closingTag.length
        }

        val withoutSearch =
            regularContent
                .toString()
                .replace("<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                .trim()
        return HistoryContent(withoutSearch, reasoningContent.toString())
    }

    private fun transformMarkedThinkBodies(content: String, replacementOpeningTag: String): String {
        val result = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val openingIndex = content.indexOf(OPENING_TAG, index)
            if (openingIndex < 0) {
                result.append(content, index, content.length)
                break
            }

            result.append(content, index, openingIndex)
            result.append(replacementOpeningTag)
            val bodyStart = openingIndex + OPENING_TAG.length
            val closingIndex = content.indexOf(CLOSING_TAG, bodyStart)
            if (closingIndex < 0) {
                result.append(decodeBody(content.substring(bodyStart)))
                break
            }

            result.append(decodeBody(content.substring(bodyStart, closingIndex)))
            result.append(CLOSING_TAG)
            index = closingIndex + CLOSING_TAG.length
        }
        return result.toString()
    }

    class StreamingDecoder {
        private val pendingEntity = StringBuilder()

        fun feed(text: String): String = buildString(text.length) {
            text.forEach { char ->
                if (pendingEntity.isEmpty()) {
                    if (char == '&') pendingEntity.append(char) else append(char)
                    return@forEach
                }

                pendingEntity.append(char)
                val candidate = pendingEntity.toString()
                val decoded = ENTITY_VALUES[candidate]
                when {
                    decoded != null -> {
                        append(decoded)
                        pendingEntity.clear()
                    }

                    ENTITY_VALUES.keys.any { it.startsWith(candidate) } -> Unit
                    else -> {
                        append(candidate)
                        pendingEntity.clear()
                    }
                }
            }
        }

        fun finish(): String {
            val remaining = pendingEntity.toString()
            pendingEntity.clear()
            return remaining
        }

        private companion object {
            val ENTITY_VALUES =
                mapOf(
                    "&amp;" to '&',
                    "&lt;" to '<',
                    "&gt;" to '>',
                )
        }
    }
}
