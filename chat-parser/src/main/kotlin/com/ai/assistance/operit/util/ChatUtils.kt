package com.ai.assistance.operit.util

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.withContent

fun findMarkupTagEnd(content: CharSequence, tagStart: Int): Int {
    var quote: Char? = null
    var index = tagStart + 1
    while (index < content.length) {
        val char = content[index]
        if (quote != null) {
            if (char == quote) quote = null
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '>' -> return index
                '<' -> return -1
            }
        }
        index++
    }
    return -1
}

/** Utility functions for chat message handling */
object ChatUtils {
    // The version marker lives on the app-owned envelope, never in provider-controlled text.
    // Existing unmarked chat history therefore remains byte-for-byte unchanged.
    const val PROVIDER_REASONING_ENCODING_ATTRIBUTE =
        "data-operit-provider-reasoning=\"html-v1\""
    const val PROVIDER_REASONING_OPEN_TAG =
        "<think $PROVIDER_REASONING_ENCODING_ATTRIBUTE>"
    private data class DisplayOnlyBlockTag(
        val start: Int,
        val endExclusive: Int,
        val family: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val isMalformed: Boolean,
        val isProviderReasoningEncoded: Boolean = false,
    )

    private data class DisplayOnlyBlockFrame(
        val family: String,
        val bodyStart: Int,
        val isProviderReasoningEncoded: Boolean,
    )

    /**
     * The visible text a display-only scan kept for a caller that truncates it anyway: the head and
     * the tail of the trimmed visible text, and that text's exact length.
     *
     * [headWindow] holds the first characters and [tailWindow] the last ones, both kept to
     * [windowChars], so neither one alone is the whole text and [length] can exceed what the two
     * together hold. A cut wider than [windowChars] would read past what the scan kept, so a cut has
     * to use that bound.
     */
    class DisplayOnlyVisibleText internal constructor(
        val headWindow: String,
        val tailWindow: String,
        /** How long the whole trimmed visible text is, which the two windows only sample. */
        val length: Int,
        /** The bound the windows were kept to, which is also the widest cut they can serve. */
        val windowChars: Int,
    )

    /** Collects the visible text a scan keeps; each sink bounds it in its own way. */
    private interface VisibleTextSink {
        fun append(source: CharSequence, start: Int, endExclusive: Int)
    }

    /**
     * Keeps the whole visible text, which is what a caller that reads all of it needs.
     *
     * A scan that finds no display-only block hands the whole input over as a single range, and that
     * range is the input itself, so the sink keeps the reference instead of a copy and only builds
     * one when the scan actually cut something out. Content that carries no such block is the
     * common case, and it is the case where a copy of a multi-megabyte turn is pure waste.
     */
    private class WholeVisibleTextSink(private val source: String) : VisibleTextSink {
        private var builder: StringBuilder? = null
        private var wholeInputOnly = false

        override fun append(source: CharSequence, start: Int, endExclusive: Int) {
            // The scan offers a range per visible stretch, and a display-only block at the very start
            // makes the first of them empty. An empty range adds no visible text, so skipping it must
            // not be the reason the sink stops reusing the input it already holds.
            if (endExclusive <= start) return
            val existing = builder
            if (existing != null) {
                existing.append(source, start, endExclusive)
                return
            }
            if (!wholeInputOnly &&
                start == 0 &&
                endExclusive == this.source.length &&
                source === this.source
            ) {
                wholeInputOnly = true
                return
            }
            // The first visible range is the only size worth guessing at: a turn that is mostly
            // reasoning leaves it small, and one that keeps most of its text makes it the whole input
            // anyway. A whole-input range is the scan's only range, so the second range this is about
            // to add cannot follow one; materializing the input first guards against that changing.
            val materialized =
                if (wholeInputOnly) StringBuilder(this.source)
                else StringBuilder(endExclusive - start)
            wholeInputOnly = false
            materialized.append(source, start, endExclusive)
            builder = materialized
        }

        /** The visible text with its surrounding whitespace removed, copied only when needed. */
        fun trimmed(): String {
            val builder = builder
            if (builder == null) {
                return if (wholeInputOnly) trimWhitespace(source) else ""
            }
            var start = 0
            var end = builder.length
            while (start < end && builder[start].isWhitespace()) start++
            while (end > start && builder[end - 1].isWhitespace()) end--
            return if (start == 0 && end == builder.length) {
                builder.toString()
            } else {
                builder.substring(start, end)
            }
        }
    }

    /**
     * [String.trim] under its own rule, [Char.isWhitespace], and without a copy when it removes
     * nothing. Kotlin's trim is that rule as well; the ASCII-only rule is Java's.
     */
    private fun trimWhitespace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start].isWhitespace()) start++
        while (end > start && value[end - 1].isWhitespace()) end--
        return if (start == 0 && end == value.length) value else value.substring(start, end)
    }

    /** Keeps nothing, for the boundary caller that only reads the scan's own visibility answer. */
    private object DiscardingVisibleTextSink : VisibleTextSink {
        override fun append(source: CharSequence, start: Int, endExclusive: Int) = Unit
    }

    /**
     * Keeps the head and the tail of the visible text while the scan still walks every character.
     *
     * A caller that truncates the visible text reads only its two ends, so the window is what makes
     * a scan of a multi-megabyte turn cost a bounded amount instead of a copy of its input. [storage]
     * bounds the rings themselves, so a window wider than the input cannot allocate more than the
     * input the scan is already reading. The surrounding whitespace
     * [WholeVisibleTextSink.trimmed] removes is accounted for as it is scanned: leading whitespace is
     * never stored, and trailing whitespace is held back until a visible character proves it was
     * interior.
     */
    private class WindowedVisibleTextSink(
        private val windowChars: Int,
        private val storage: Int,
    ) : VisibleTextSink {
        private val head = StringBuilder()
        private val tail = CharArray(storage.coerceAtLeast(1))
        private val pending = CharArray(storage.coerceAtLeast(1))
        private var tailWriteIndex = 0
        private var tailLength = 0
        private var pendingWriteIndex = 0
        private var pendingLength = 0
        private var seenVisibleChar = false
        private var visibleChars = 0
        private var trailingWhitespace = 0

        override fun append(source: CharSequence, start: Int, endExclusive: Int) {
            for (index in start until endExclusive) {
                val char = source[index]
                if (!seenVisibleChar) {
                    if (char.isWhitespace()) continue
                    seenVisibleChar = true
                }
                visibleChars++
                if (head.length < storage) head.append(char)
                if (char.isWhitespace()) {
                    trailingWhitespace++
                    writePending(char)
                    continue
                }
                if (trailingWhitespace > 0) {
                    flushPending()
                    trailingWhitespace = 0
                }
                writeTail(char)
            }
        }

        private fun writePending(char: Char) {
            if (storage <= 0) return
            pending[pendingWriteIndex] = char
            pendingWriteIndex = (pendingWriteIndex + 1) % storage
            if (pendingLength < storage) pendingLength++
        }

        private fun flushPending() {
            val length = minOf(pendingLength, storage)
            for (offset in 0 until length) {
                writeTail(
                    pending[(pendingWriteIndex - length + offset + storage * 2) % storage]
                )
            }
            pendingWriteIndex = 0
            pendingLength = 0
        }

        private fun writeTail(char: Char) {
            if (storage <= 0) return
            tail[tailWriteIndex] = char
            tailWriteIndex = (tailWriteIndex + 1) % storage
            if (tailLength < storage) tailLength++
        }

        fun result(): DisplayOnlyVisibleText {
            val length = if (seenVisibleChar) visibleChars - trailingWhitespace else 0
            val headText = if (head.length <= length) head.toString() else head.substring(0, length)
            val tailText = StringBuilder(tailLength)
            for (offset in 0 until tailLength) {
                tailText.append(
                    tail[(tailWriteIndex - tailLength + offset + storage * 2) % storage]
                )
            }
            return DisplayOnlyVisibleText(
                headWindow = headText,
                tailWindow = tailText.toString(),
                length = length,
                windowChars = windowChars,
            )
        }
    }

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

    /**
     * Removes the OpenAI Responses protocol markup that must not reach another provider's history:
     * the reasoning meta this module already knows about, plus the `<search>` blocks that carry
     * web-search results.
     *
     * Mirrors upstream `ChatUtils.stripOpenAiResponsesProtocolMarkup`, except that upstream also
     * drops the `openai:responses_output_item` meta layer; this module's `ChatMarkupRegex` has no
     * provider constant for it.
     */
    fun stripOpenAiResponsesProtocolMarkup(content: String): String {
        return stripOpenAiResponsesReasoningMeta(content)
            .replace(ChatMarkupRegex.searchTag, "")
            .replace(ChatMarkupRegex.searchSelfClosingTag, "")
            .trim()
    }

    fun isGeminiProviderModel(providerModel: String): Boolean {
        return when (providerModel.substringBefore(":").uppercase()) {
            "GOOGLE", "GEMINI_GENERIC", "GOOGLE_ANTIGRAVITY" -> true
            else -> false
        }
    }

    fun isOpenAIResponsesProviderModel(providerModel: String): Boolean {
        return providerModel.substringBefore(":").uppercase() in
            setOf("OPENAI_RESPONSES", "OPENAI_RESPONSES_GENERIC", "OPENAI_CODEX")
    }

    /**
     * Removes display-only thinking and search blocks before executable markup is parsed.
     *
     * This is deliberately a single forward pass. Malformed or unclosed blocks fail closed by
     * discarding the remaining tail, while self-closing tags discard only the tag itself.
     *
     * Content without a display-only block is returned as the trimmed input itself, so a caller
     * that reads the whole text does not pay for a copy of a multi-megabyte turn.
     */
    fun removeThinkingContent(content: String): String {
        val visibleText = WholeVisibleTextSink(content)
        scanDisplayOnlyContent(content, IntArray(0), IntArray(0), visibleText)
        return visibleText.trimmed()
    }

    /**
     * Removes display-only thinking and search blocks, keeping only what a caller that truncates the
     * result reads.
     *
     * [windowChars] bounds both ends: the returned head holds the first characters of the visible
     * text and the tail the last ones, so scanning a multi-megabyte turn costs a bounded window
     * instead of a copy of its input. The fail-closed state machine still walks every character, so
     * the visible text those windows come from is exactly what [removeThinkingContent] returns.
     *
     * A window wider than the input is stored to the input's own length, which is all it could ever
     * need, so asking for one never allocates more than the input the scan already reads.
     */
    fun removeThinkingContentWindow(content: CharSequence, windowChars: Int): DisplayOnlyVisibleText {
        val visibleText =
            WindowedVisibleTextSink(
                windowChars = windowChars,
                storage = windowChars.coerceIn(0, content.length),
            )
        scanDisplayOnlyContent(content, IntArray(0), IntArray(0), visibleText)
        return visibleText.result()
    }

    /**
     * Reports whether monotonically ordered boundaries are outside display-only blocks. This lets
     * the real Markdown/XML tool parser mark executable candidates while the same global scanner
     * still carries fail-closed state across every surrounding group.
     */
    fun displayOnlyBoundaryVisibility(
        content: String,
        orderedBoundaries: IntArray,
        protectedRanges: IntArray,
    ): BooleanArray =
        scanDisplayOnlyContent(
            content,
            orderedBoundaries,
            protectedRanges,
            DiscardingVisibleTextSink,
        )

    private fun scanDisplayOnlyContent(
        content: CharSequence,
        orderedBoundaries: IntArray,
        protectedRanges: IntArray,
        visibleText: VisibleTextSink,
    ): BooleanArray {
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
                visibleText.append(content, cursor, tag.start)
            }

            if (tag.isMalformed && wasVisible && tag.isClosing) {
                visibleText.append(content, tag.start, tag.endExclusive)
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
                    tag.isClosing -> visibleText.append(content, tag.start, tag.endExclusive)
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
            visibleText.append(content, cursor, content.length)
        }
        while (boundaryIndex < orderedBoundaries.size) {
            boundaryVisibility[boundaryIndex++] = activeFamilies.isEmpty()
        }
        return boundaryVisibility
    }

    private fun findNextDisplayOnlyToken(
        content: CharSequence,
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
            if (boundary != null && boundary != '>' && boundary != '/' && !boundary.isWhitespace()) {
                // Only an exact XML name boundary can open a display-only block. Qualified names
                // and malformed lookalikes such as <think!foo> are ordinary visible markup; an
                // exact trailing `<think` with no boundary remains fail-closed below.
                candidateStart = content.indexOf('<', candidateStart + 1)
                continue
            }

            val nextTerminator = findMarkupTagEnd(content, candidateStart)
            if (nextTerminator < 0) {
                val nestedTagStart =
                    if (isClosing) content.indexOf('<', nameEnd) else -1
                return DisplayOnlyBlockTag(
                    start = candidateStart,
                    endExclusive =
                        if (nestedTagStart >= 0) nestedTagStart else content.length,
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
            // Opening display tags follow the same permissive attribute grammar as the XML
            // splitter and the legacy ChatMarkupRegex patterns. The scanner has already proved
            // the suffix is bounded by this tag's `>` and contains no nested `<`, so attributes
            // cannot escape the hidden block. Closing tags remain strict: only whitespace may
            // appear between the family name and `>`.
            val hasOnlyAllowedSuffix = !isClosing || suffix.all { it.isWhitespace() }
            val family = if (tagName.startsWith("think")) "think" else "search"
            return DisplayOnlyBlockTag(
                start = candidateStart,
                endExclusive = nextTerminator + 1,
                family = family,
                isClosing = isClosing,
                isSelfClosing = isSelfClosing,
                isMalformed = !hasOnlyAllowedSuffix,
                isProviderReasoningEncoded =
                    !isClosing &&
                        !isSelfClosing &&
                        family == "think" &&
                        suffix.trim() == PROVIDER_REASONING_ENCODING_ATTRIBUTE,
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

    fun decodeProviderReasoningMarkup(content: String): String =
        content.replace("&lt;", "<").replace("&amp;", "&")

    fun isEncodedProviderReasoningEnvelope(content: String): Boolean =
        content.trimStart().startsWith(PROVIDER_REASONING_OPEN_TAG)

    fun decodeProviderReasoningForDisplay(envelope: String, body: String): String =
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
        val frames = mutableListOf<DisplayOnlyBlockFrame>()
        val thinkingBodies = mutableListOf<String>()
        var scanIndex = 0
        var activeThinkDepth = 0

        while (scanIndex < content.length) {
            val tag =
                findNextDisplayOnlyToken(
                    content = content,
                    fromIndex = scanIndex,
                    beforeExclusive = content.length,
                ) ?: break
            val wasVisible = frames.isEmpty()

            if (tag.isMalformed) {
                if (wasVisible && tag.isClosing) {
                    scanIndex = tag.endExclusive
                    continue
                }
                break
            }

            when {
                tag.isSelfClosing -> Unit
                tag.isClosing && wasVisible -> Unit
                tag.isClosing -> {
                    val frame = frames.last()
                    if (frame.family != tag.family) break
                    frames.removeAt(frames.lastIndex)
                    if (frame.family == "think") activeThinkDepth--
                    if (frame.family == "think" && activeThinkDepth == 0) {
                        val body = content.substring(frame.bodyStart, tag.start).trim()
                        thinkingBodies +=
                            if (frame.isProviderReasoningEncoded) {
                                decodeProviderReasoningMarkup(body)
                            } else {
                                body
                            }
                    }
                }
                else -> {
                    if (tag.family == "think") activeThinkDepth++
                    frames +=
                        DisplayOnlyBlockFrame(
                            family = tag.family,
                            bodyStart = tag.endExclusive,
                            isProviderReasoningEncoded = tag.isProviderReasoningEncoded,
                        )
                }
            }
            scanIndex = tag.endExclusive
        }

        return Pair(removeThinkingContent(content), thinkingBodies.joinToString("\n"))
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
