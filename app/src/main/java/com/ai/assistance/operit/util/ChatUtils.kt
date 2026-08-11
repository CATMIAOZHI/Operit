package com.ai.assistance.operit.util

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.withContent

/** Utility functions for chat message handling */
object ChatUtils {
    // The version marker lives on the app-owned envelope, never in provider-controlled text.
    // Existing unmarked chat history therefore remains byte-for-byte unchanged.
    internal const val PROVIDER_REASONING_ENCODING_ATTRIBUTE =
        "data-operit-provider-reasoning=\"html-v1\""
    internal const val PROVIDER_REASONING_OPEN_TAG =
        "<think $PROVIDER_REASONING_ENCODING_ATTRIBUTE>"
    private data class DisplayOnlyBlockTag(
        val start: Int,
        val endExclusive: Int,
        val family: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val isMalformed: Boolean,
    )

    private data class DisplayOnlyScanResult(
        val filteredContent: String,
        val boundaryVisibility: BooleanArray,
    )

    fun stripGeminiThoughtSignatureMeta(content: String): String {
        return ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)
    }

    fun stripGeminiThoughtSignatureMeta(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        return messages.map { (role, content) ->
            role to stripGeminiThoughtSignatureMeta(content)
        }
    }

    fun stripGeminiThoughtSignatureMetaTurns(messages: List<PromptTurn>): List<PromptTurn> {
        return messages.map { turn ->
            turn.withContent(stripGeminiThoughtSignatureMeta(turn.content))
        }
    }

    fun stripOpenAiResponsesReasoningMeta(content: String): String {
        return ChatMarkupRegex.removeOpenAiResponsesReasoningMeta(content)
    }

    fun stripOpenAiResponsesReasoningMetaTurns(messages: List<PromptTurn>): List<PromptTurn> {
        return messages.map { turn ->
            turn.withContent(stripOpenAiResponsesReasoningMeta(turn.content))
        }
    }

    fun isGeminiProviderModel(providerModel: String): Boolean {
        return when (providerModel.substringBefore(":").uppercase()) {
            "GOOGLE", "GEMINI_GENERIC" -> true
            else -> false
        }
    }

    fun isOpenAIResponsesProviderModel(providerModel: String): Boolean {
        return providerModel.substringBefore(":").uppercase() == "OPENAI_RESPONSES"
    }

    /**
     * Removes display-only thinking and search blocks before executable markup is parsed.
     *
     * This is deliberately a single forward pass. Malformed or unclosed blocks fail closed by
     * discarding the remaining tail, while self-closing tags discard only the tag itself.
     */
    fun removeThinkingContent(content: String): String {
        return scanDisplayOnlyContent(content, IntArray(0), IntArray(0)).filteredContent
    }

    /**
     * Reports whether monotonically ordered boundaries are outside display-only blocks. This lets
     * the real Markdown/XML tool parser mark executable candidates while the same global scanner
     * still carries fail-closed state across every surrounding group.
     */
    internal fun displayOnlyBoundaryVisibility(
        content: String,
        orderedBoundaries: IntArray,
        protectedRanges: IntArray,
    ): BooleanArray =
        scanDisplayOnlyContent(content, orderedBoundaries, protectedRanges).boundaryVisibility

    private fun scanDisplayOnlyContent(
        content: String,
        orderedBoundaries: IntArray,
        protectedRanges: IntArray,
    ): DisplayOnlyScanResult {
        require(orderedBoundaries.all { it in 0..content.length })
        for (index in 1 until orderedBoundaries.size) {
            require(orderedBoundaries[index - 1] <= orderedBoundaries[index])
        }
        require(protectedRanges.size % 2 == 0)
        var previousRangeEnd = 0
        protectedRanges.indices.step(2).forEach { rangeIndex ->
            val rangeStart = protectedRanges[rangeIndex]
            val rangeEnd = protectedRanges[rangeIndex + 1]
            require(rangeStart in previousRangeEnd..content.length)
            require(rangeEnd in rangeStart..content.length)
            previousRangeEnd = rangeEnd
        }

        val result = StringBuilder(content.length)
        val boundaryVisibility = BooleanArray(orderedBoundaries.size)
        var boundaryIndex = 0
        var protectedRangeIndex = 0
        var cursor = 0
        var scanIndex = 0
        val activeFamilies = mutableListOf<String>()

        fun markBoundariesAtOrBefore(position: Int, visible: Boolean) {
            while (boundaryIndex < orderedBoundaries.size &&
                orderedBoundaries[boundaryIndex] <= position
            ) {
                boundaryVisibility[boundaryIndex++] = visible
            }
        }

        fun markBoundariesBefore(position: Int, visible: Boolean) {
            while (boundaryIndex < orderedBoundaries.size &&
                orderedBoundaries[boundaryIndex] < position
            ) {
                boundaryVisibility[boundaryIndex++] = visible
            }
        }

        while (scanIndex < content.length) {
            while (protectedRangeIndex < protectedRanges.size &&
                protectedRanges[protectedRangeIndex + 1] <= scanIndex
            ) {
                protectedRangeIndex += 2
            }
            val protectedRangeStart =
                if (activeFamilies.isEmpty() && protectedRangeIndex < protectedRanges.size) {
                    protectedRanges[protectedRangeIndex]
                } else {
                    content.length
                }
            val tag =
                findNextDisplayOnlyToken(
                    content = content,
                    fromIndex = scanIndex,
                    beforeExclusive = maxOf(scanIndex, protectedRangeStart),
                )
            if (tag == null && activeFamilies.isEmpty() &&
                protectedRangeIndex < protectedRanges.size
            ) {
                val protectedRangeEnd = protectedRanges[protectedRangeIndex + 1]
                markBoundariesAtOrBefore(protectedRangeEnd, visible = true)
                scanIndex = protectedRangeEnd
                protectedRangeIndex += 2
                continue
            }
            if (tag == null) break
            val wasVisible = activeFamilies.isEmpty()
            markBoundariesAtOrBefore(tag.start, wasVisible)
            if (wasVisible) {
                result.append(content, cursor, tag.start)
            }

            if (tag.isMalformed && wasVisible && tag.isClosing) {
                result.append(content, tag.start, tag.endExclusive)
                cursor = tag.endExclusive
                scanIndex = tag.endExclusive
                markBoundariesAtOrBefore(tag.endExclusive, visible = true)
                continue
            }

            if (tag.isMalformed) {
                activeFamilies.add("malformed")
                cursor = content.length
                markBoundariesBefore(tag.endExclusive, visible = false)
                break
            }

            var failedClosed = false
            if (wasVisible) {
                when {
                    tag.isClosing -> result.append(content, tag.start, tag.endExclusive)
                    tag.isSelfClosing -> Unit
                    else -> activeFamilies.add(tag.family)
                }
            } else {
                when {
                    tag.isClosing -> {
                        if (activeFamilies.last() != tag.family) {
                            activeFamilies.add("malformed")
                            cursor = content.length
                            failedClosed = true
                        } else {
                            activeFamilies.removeAt(activeFamilies.lastIndex)
                        }
                    }
                    !tag.isSelfClosing -> activeFamilies.add(tag.family)
                }
            }

            val tagIsVisible = wasVisible && tag.isClosing
            markBoundariesBefore(tag.endExclusive, tagIsVisible)
            if (failedClosed) break

            cursor = tag.endExclusive
            scanIndex = tag.endExclusive
        }

        if (activeFamilies.isEmpty()) {
            result.append(content, cursor, content.length)
        }
        while (boundaryIndex < orderedBoundaries.size) {
            boundaryVisibility[boundaryIndex++] = activeFamilies.isEmpty()
        }
        return DisplayOnlyScanResult(result.toString().trim(), boundaryVisibility)
    }

    private fun findNextDisplayOnlyToken(
        content: String,
        fromIndex: Int,
        beforeExclusive: Int,
    ): DisplayOnlyBlockTag? {
        var candidateStart = content.indexOf('<', fromIndex)
        while (candidateStart >= 0 && candidateStart < beforeExclusive) {
            var nameStart = candidateStart + 1
            val isClosing = nameStart < content.length && content[nameStart] == '/'
            if (isClosing) nameStart++

            val tagName =
                when {
                    content.regionMatches(nameStart, "thinking", 0, 8, ignoreCase = true) -> "thinking"
                    content.regionMatches(nameStart, "think", 0, 5, ignoreCase = true) -> "think"
                    content.regionMatches(nameStart, "search", 0, 6, ignoreCase = true) -> "search"
                    else -> null
                }
            if (tagName == null) {
                candidateStart = content.indexOf('<', candidateStart + 1)
                continue
            }

            val nameEnd = nameStart + tagName.length
            val boundary = content.getOrNull(nameEnd)
            if (boundary != null && (boundary.isLetterOrDigit() || boundary == '_')) {
                candidateStart = content.indexOf('<', candidateStart + 1)
                continue
            }

            val nextTerminator = content.indexOf('>', nameEnd)
            val nestedStart = content.indexOf('<', nameEnd)
            if (nextTerminator < 0 || (nestedStart >= 0 && nestedStart < nextTerminator)) {
                return DisplayOnlyBlockTag(
                    start = candidateStart,
                    endExclusive = content.length,
                    family = if (tagName.startsWith("think")) "think" else "search",
                    isClosing = isClosing,
                    isSelfClosing = false,
                    isMalformed = true,
                )
            }

            var lastMeaningfulIndex = nextTerminator - 1
            while (lastMeaningfulIndex >= nameEnd && content[lastMeaningfulIndex].isWhitespace()) {
                lastMeaningfulIndex--
            }
            val isSelfClosing =
                !isClosing && lastMeaningfulIndex >= nameEnd && content[lastMeaningfulIndex] == '/'
            val suffixEndExclusive = if (isSelfClosing) lastMeaningfulIndex else nextTerminator
            val suffix = content.substring(nameEnd, suffixEndExclusive)
            val hasOnlyAllowedSuffix =
                suffix.all { it.isWhitespace() } ||
                    (!isClosing &&
                        !isSelfClosing &&
                        tagName.equals("think", ignoreCase = true) &&
                        suffix.trim() == PROVIDER_REASONING_ENCODING_ATTRIBUTE)
            return DisplayOnlyBlockTag(
                start = candidateStart,
                endExclusive = nextTerminator + 1,
                family = if (tagName.startsWith("think")) "think" else "search",
                isClosing = isClosing,
                isSelfClosing = isSelfClosing,
                isMalformed = !hasOnlyAllowedSuffix,
            )
        }
        return null
    }

    /**
     * Prevents provider-controlled reasoning text from injecting markup into the app-owned
     * thinking wrapper. Escaping every less-than sign is safe even when a tag is split across
     * streaming chunks, and the entity still renders as the original text.
     */
    fun escapeProviderReasoningMarkup(content: String): String =
        content.replace("&", "&amp;").replace("<", "&lt;")

    internal fun decodeProviderReasoningMarkup(content: String): String =
        content.replace("&lt;", "<").replace("&amp;", "&")

    internal fun isEncodedProviderReasoningEnvelope(content: String): Boolean =
        content.trimStart().startsWith(PROVIDER_REASONING_OPEN_TAG)

    internal fun decodeProviderReasoningForDisplay(envelope: String, body: String): String =
        if (isEncodedProviderReasoningEnvelope(envelope)) {
            decodeProviderReasoningMarkup(body)
        } else {
            body
        }

    /**
     * 提取think标签内的内容（用于DeepSeek的reasoning_content）
     * @param content 包含think标签的内容
     * @return Pair(移除think标签后的内容, think标签内的内容)
     */
    fun extractThinkingContent(content: String): Pair<String, String> {
        val thinkPattern =
            "<think(?:ing)?(\\s+$PROVIDER_REASONING_ENCODING_ATTRIBUTE)?>([\\s\\S]*?)</think(?:ing)?>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
        val thinkMatches = thinkPattern.findAll(content)
        
        // 收集所有think标签内的内容
        val thinkingContent =
            thinkMatches.joinToString("\n") {
                val body = it.groupValues[2].trim()
                if (it.groupValues[1].isNotEmpty()) decodeProviderReasoningMarkup(body) else body
            }
        
        // 移除think标签和search标签
        val contentWithoutThink = content
            .replace(thinkPattern, "")
            .replace("<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        
        return Pair(contentWithoutThink, thinkingContent)
    }

    /**
     * 估算给定文本的token数量
     * @param text 要估算token的文本
     * @return 估算的token数量
     */
    fun estimateTokenCount(text: String): Int {
        // 简单估算：中文每个字约1.5个token，英文每4个字符约1个token
        val chineseCharCount = text.count { it.code in 0x4E00..0x9FFF }
        val otherCharCount = text.length - chineseCharCount
        return (chineseCharCount * 1.5 + otherCharCount * 0.25).toInt()
    }

    /**
     * 从 AI 响应中提取 JSON 对象部分
     * AI 可能会在 JSON 前后添加说明文字或使用 ```json 代码块，需要提取出纯净的 JSON
     */
    fun extractJson(response: String): String {
        var text = response.trim()
        
        // 处理 markdown 代码块格式 ```json ... ```
        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        
        // 寻找第一个 { 和最后一个 }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        
        return if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            text.substring(firstBrace, lastBrace + 1)
        } else {
            // 如果没找到完整的 JSON 结构，返回原始字符串
            text
        }
    }

    /**
     * 从 AI 响应中提取 JSON 数组部分
     * AI 可能会在 JSON 前后添加说明文字或使用 ```json 代码块，需要提取出纯净的 JSON
     */
    fun extractJsonArray(response: String): String {
        var text = response.trim()
        
        // 处理 markdown 代码块格式 ```json ... ```
        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        
        // 寻找第一个 [ 和最后一个 ]
        val firstBracket = text.indexOf('[')
        val lastBracket = text.lastIndexOf(']')
        
        return if (firstBracket != -1 && lastBracket != -1 && firstBracket < lastBracket) {
            text.substring(firstBracket, lastBracket + 1)
        } else {
            // 如果没找到完整的 JSON 结构，返回原始字符串
            text
        }
    }
}
