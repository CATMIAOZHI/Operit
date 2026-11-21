package com.ai.assistance.operit.util

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.withContent

/** Where the tag starting at [tagStart] ends (its `>`), looking no further than [limit]; -1 if not by then. */
fun findMarkupTagEnd(content: CharSequence, tagStart: Int, limit: Int = content.length): Int {
    var quote: Char? = null
    var index = tagStart + 1
    while (index < limit) {
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

    /** What [visitOutsideThinking] finds in a text, in the order it comes. */
    interface OutsideThinkingVisitor {
        /** Text outside display-only blocks and tool blocks: [start] until [end] of the content. */
        fun text(start: Int, end: Int)

        /** A complete tool call or tool result: [start] until [end] of the content. */
        fun toolBlock(start: Int, end: Int)
    }

    /**
     * Reads [content] as [removeThinkingContent] does, for a caller that handles tool calls and
     * tool results itself, such as the Orbit chat working out what Orbit said. A complete tool
     * block the scan reaches outside a display-only block goes to [visitor] whole and is not looked
     * into, so a `<think>` in a tool's input or output (a log, an old message) can neither hide the
     * rest of the turn nor pair with a tag in another block or in the reply. Tool tags inside
     * thinking are hidden with it.
     *
     * A tool block opens with a tool or tool result tag (its whole name, not self-closing). A tool
     * call ends at its first closing tag outside `<![CDATA[ … ]]>` and `<!-- … -->`, as the executor
     * reads it; a tool result whose output is wrapped in `<content>`, as the app writes it, ends at
     * a closing tag right after `</content>`. A block is complete when it is laid out as the app and
     * the executor lay it out (a tool result's output in `<content>`, a tool call's input in
     * `<param>` elements), or when every display-only tag opened in it closes in it too. Anything
     * else, such as an opening that never closes or a "block" whose thinking runs on past its end
     * (it may have been cut out of the model's own thinking), is read exactly as
     * [removeThinkingContent] reads it, so doubt still fails closed. Searches that find no complete
     * block are bounded: past the bound, the next opening that would need one hides the rest of
     * the text from where it starts. Reading tool openings minding quotes is bounded too; past
     * that bound no further tool blocks are found.
     *
     * Callers that keep tool blocks (exports, chat reading, model history) use
     * [removeThinkingContent], which also strips thinking found inside them.
     */
    fun visitOutsideThinking(content: String, visitor: OutsideThinkingVisitor) {
        val blocks = ToolBlocks(content)
        // The same walk as scanDisplayOnlyContent, with complete tool blocks passed over whole.
        val activeFamilies = mutableListOf<String>()
        var cursor = 0
        var scanIndex = 0
        while (scanIndex < content.length) {
            val wasVisible = activeFamilies.isEmpty()
            val blockStart = if (wasVisible) blocks.nextStart(scanIndex) else -1
            val tag =
                findNextDisplayOnlyToken(
                    content = content,
                    fromIndex = scanIndex,
                    beforeExclusive = if (blockStart >= 0) blockStart else content.length,
                )
            if (tag == null) {
                if (blockStart < 0) break
                val blockEnd = blocks.endOf(blockStart)
                if (blockEnd == ToolBlocks.GIVEN_UP) {
                    if (blockStart > cursor) visitor.text(cursor, blockStart)
                    activeFamilies.add(MALFORMED_FAMILY)
                    break
                }
                if (blockEnd < 0) {
                    // Not a complete block: its opening is text, read on after its `<`.
                    scanIndex = blockStart + 1
                } else {
                    if (blockStart > cursor) visitor.text(cursor, blockStart)
                    visitor.toolBlock(blockStart, blockEnd)
                    cursor = blockEnd
                    scanIndex = blockEnd
                }
                continue
            }
            if (wasVisible && tag.isClosing) {
                // A closing tag that closes nothing is text.
                scanIndex = tag.endExclusive
                continue
            }
            if (wasVisible && tag.start > cursor) visitor.text(cursor, tag.start)
            if (tag.isMalformed) {
                activeFamilies.add(MALFORMED_FAMILY)
                break
            }
            if (wasVisible) {
                if (!tag.isSelfClosing) activeFamilies.add(tag.family)
            } else if (tag.isClosing) {
                if (activeFamilies.last() != tag.family) {
                    activeFamilies.add(MALFORMED_FAMILY)
                    break
                }
                activeFamilies.removeAt(activeFamilies.lastIndex)
            } else if (!tag.isSelfClosing) {
                activeFamilies.add(tag.family)
            }
            cursor = tag.endExclusive
            scanIndex = tag.endExclusive
        }
        if (activeFamilies.isEmpty() && content.length > cursor) visitor.text(cursor, content.length)
    }

    // Stands for "the plain scan hides the rest of the text from here"; never a tag's family.
    private const val MALFORMED_FAMILY = "malformed"

    // A tool or tool result tag name, whole: not followed by another character of an XML name.
    private const val TOOL_TAG_NAME =
        "(${ChatMarkupRegex.TOOL_RESULT_TAG_NAME_REGEX_SOURCE}|${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})" +
            "(?![A-Za-z0-9_.:-])"
    private val toolOpeningName = java.util.regex.Pattern.compile(
        "<$TOOL_TAG_NAME", java.util.regex.Pattern.CASE_INSENSITIVE,
    )
    private val toolClosingTagName = java.util.regex.Pattern.compile(
        "</$TOOL_TAG_NAME", java.util.regex.Pattern.CASE_INSENSITIVE,
    )
    private val toolCallOpeningName = java.util.regex.Pattern.compile(
        "<${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE}(?![A-Za-z0-9_.:-])", java.util.regex.Pattern.CASE_INSENSITIVE,
    )
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val CONTENT_OPEN = "<content>"
    private const val CONTENT_CLOSE = "</content>"
    private const val PARAM_OPEN = "<param"
    private const val PARAM_CLOSE = "</param"
    // What the executor takes as punctuation after a `usage: <bytes>` placeholder, with symbols.
    private const val PROTOCOL_PUNCTUATION =
        "\uFF0C\u3002\uFF1F\uFF01\uFF1A\uFF08\uFF09\u3010\u3011\u300A\u300B:,.?!~\uFF5E}]"

    /**
     * The tool blocks of a text, for [visitOutsideThinking]. Where each tool name closes for the
     * last time is found in one pass first, so an opening whose name never closes again is known
     * at once, and no search reads past that closing. A closing is searched for only from an
     * opening the scan reaches, forward from it; once a complete block is found, the scan goes on
     * after it, so it is never read twice. Searches that find none may cover the same text again;
     * their total is bounded.
     */
    private class ToolBlocks(private val content: String) {
        private val opening = toolOpeningName.matcher(content)
        // The opening found last: where it starts (-2 before the first search, -1 when there is
        // no further one), where it ends and its name.
        private var openingStart = -2
        private var openingEnd = 0
        private var openingName = ""
        // For each tool name, where its last closing tag starts and where it ends.
        private val lastClosing: Map<String, IntArray> by lazy(LazyThreadSafetyMode.NONE) { lastClosingTags() }
        // Characters read by searches that found no complete block.
        private var spent = 0L
        // Characters read, minding quotes, to find where openings end, and by tags in a call that
        // did not end (what they read is read again).
        private var tagSpent = 0L

        /**
         * Start of the next tool opening at or after [from], or -1; [from] never goes back. Once
         * reading openings has cost more than the whole text, there are no further ones: the rest
         * is read as the plain scan reads it.
         */
        fun nextStart(from: Int): Int {
            if (openingStart == -1 || openingStart >= from) return openingStart
            var at = content.indexOf("<tool", from, ignoreCase = true)
            while (at >= 0) {
                if (tagSpent > content.length) break
                opening.region(at, content.length)
                if (opening.lookingAt()) {
                    val name = opening.group(1)
                    val end = openingTagEnd(at, opening.end())
                    // One that only ends past the bound is not taken either.
                    if (tagSpent > content.length) break
                    if (end > 0 && !isSelfClosing(end)) {
                        openingEnd = end
                        openingName = name.lowercase()
                        openingNamed = namedOpening
                        openingStart = at
                        return at
                    }
                }
                at = content.indexOf("<tool", at + 1, ignoreCase = true)
            }
            openingStart = -1
            return -1
        }

        /**
         * After the `>` that ends the opening tag at [at] whose name ends at [from], or -1: read
         * minding quotes, as the executor reads a tag ([findMarkupTagEnd]), so a `>` or `/>` in an
         * attribute does not end it. An opening inside another's quotes is read again; [nextStart]
         * stops once this has read more than the whole text. Only an opening the executor would
         * read as one counts: its name ends at a space, `/` or `>`, and its attributes are
         * [hasValidAttributes].
         */
        private fun openingTagEnd(at: Int, from: Int): Int {
            val boundary = content.getOrNull(from) ?: return -1
            if (boundary != '>' && boundary != '/' && !boundary.isWhitespace()) return -1
            val stop = quotedStop(from, content.length)
            tagSpent += stop - at
            if (stop >= content.length || content[stop] != '>') return -1
            return if (hasValidAttributes(from, stop)) stop + 1 else -1
        }

        /** Where reading from [from] minding quotes stops: at a `>` or `<` outside quotes, or at [limit]. */
        private fun quotedStop(from: Int, limit: Int): Int {
            var quote = ' '
            var index = from
            while (index < limit) {
                val c = content[index]
                if (quote != ' ') {
                    if (c == quote) quote = ' '
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>' || c == '<') {
                    break
                }
                index++
            }
            return index
        }

        /**
         * Whether the attributes from [from] until the `>` at [tagEnd] are laid out as the executor
         * requires: `name="value"` pairs (either quote) and spaces, with an optional `/` last.
         * Sets [namedOpening] to whether the first `name` attribute has a value, as the executor
         * requires of a tool call it runs.
         */
        private fun hasValidAttributes(from: Int, tagEnd: Int): Boolean {
            namedOpening = false
            var sawName = false
            var index = from
            while (true) {
                // Spaces end at the `>` at the latest.
                index = skipWhitespace(index)
                if (index >= tagEnd) return true
                if (content[index] == '/') return skipWhitespace(index + 1) == tagEnd
                val nameStart = index
                while (index < tagEnd && isNameChar(content[index])) index++
                if (index == nameStart) return false
                val isName = !sawName && index - nameStart == 4 &&
                    startsWith(nameStart, "name")
                index = skipWhitespace(index)
                if (index >= tagEnd || content[index] != '=') return false
                index = skipWhitespace(index + 1)
                if (index >= tagEnd) return false
                val quote = content[index]
                if (quote != '"' && quote != '\'') return false
                val close = content.indexOf(quote, index + 1)
                if (close < 0 || close >= tagEnd) return false
                if (isName) {
                    sawName = true
                    namedOpening = close > index + 1
                }
                index = close + 1
            }
        }

        // Set by [hasValidAttributes] for the opening it read last; [openingNamed] keeps it for
        // the opening [nextStart] found.
        private var namedOpening = false
        private var openingNamed = false

        /**
         * End of the complete block opening at [start], the last [nextStart]; -1 when there is
         * none, or [GIVEN_UP] once searches that found none have read more than the whole text.
         */
        fun endOf(start: Int): Int {
            check(start == openingStart)
            val last = lastClosing[openingName] ?: return -1
            val bodyStart = openingEnd
            if (last[0] < bodyStart) return -1
            if (spent > content.length) return GIVEN_UP
            // A name such as `tool_resulted` is a call's, not a result's.
            val call = !ChatMarkupRegex.isToolResultTagName(openingName)
            reach = bodyStart
            // The executor runs a call with a name whose body holds parameters only.
            if (call && openingNamed) {
                val closing = paramsClosing(bodyStart, last[0])
                if (closing >= 0) {
                    val end = closing + openingName.length + 3
                    // What was read past the block may be read again.
                    if (reach > end) spent += reach - end
                    return end
                }
            }
            val wrapped = !call && startsWith(skipWhitespace(bodyStart), CONTENT_OPEN)
            val closing = closingStart(openingName, bodyStart, call, wrapped, last[0])
            if (closing < 0) {
                spent += maxOf(searchedTo, reach) - bodyStart + 1
                return -1
            }
            val end = if (closing == last[0]) last[1] else skipWhitespace(closing + 2 + openingName.length) + 1
            // A display-only tag in the opening's own attributes counts as in the block too.
            if (wrapped || closesItsThinking(start, closing)) {
                if (reach > end) spent += reach - end
                return end
            }
            // Read as text instead, from just after its `<`.
            spent += maxOf(end, reach) - start
            return -1
        }

        // Where the last search by [closingStart] stopped reading.
        private var searchedTo = 0

        /**
         * Start of the closing tag of a block whose body starts at [from], or -1 when none comes by
         * [last] or another opening of the same name comes first: the executor pairs tags as they
         * nest, so the closing could then be that one's. Tool tags carry a random suffix each, so
         * an old block in a log or a file has a name of its own and does not stop the search.
         * In a call, every other tag is read whole minding quotes, as the executor reads it, so a
         * closing tag in its quotes is not taken and a self-closing one of the same name opens
         * nothing; once reading tags has run past its bound, the search finds none.
         */
        private fun closingStart(name: String, from: Int, call: Boolean, wrapped: Boolean, last: Int): Int {
            var index = from
            while (index <= last) {
                // The last closing starts with `<`, so this finds one by then.
                val tag = content.indexOf('<', index)
                searchedTo = tag
                // The executor reads CDATA in capitals only.
                if (call && content.startsWith(CDATA_OPEN, tag)) {
                    index = sectionEnd(tag + CDATA_OPEN.length, CDATA_CLOSE, last) ?: run { searchedTo = last; return -1 }
                    continue
                }
                if (call && startsWith(tag, COMMENT_OPEN)) {
                    index = sectionEnd(tag + COMMENT_OPEN.length, COMMENT_CLOSE, last) ?: run { searchedTo = last; return -1 }
                    continue
                }
                if (isClosing(name, tag, last) && (!wrapped || endsWithContentClose(tag))) return tag
                if (call) {
                    // The last closing starts with `<`, so this stops by then.
                    val stop = quotedStop(tag + 1, last)
                    searchedTo = stop
                    if (content[stop] == '>') {
                        if (opensSameName(name, tag) && !isSelfClosing(stop + 1)) return -1
                        index = stop + 1
                        continue
                    }
                    // The next `<` may be in what this read, so it is read again: such reading is
                    // counted. A tag read whole is passed over and never read again by this search.
                    tagSpent += stop - tag
                    if (tagSpent > content.length) return -1
                }
                if (opensSameName(name, tag)) return -1
                index = tag + 1
            }
            return -1
        }

        /**
         * Whether a tag of [name] that is not a closing one starts at [tag]. A self-closing one
         * counts too where its end is not read, so an attribute holding `/>` can never make an
         * opening pass for one.
         */
        private fun opensSameName(name: String, tag: Int): Boolean {
            if (!startsWith(tag + 1, name)) return false
            val after = content.getOrNull(tag + 1 + name.length) ?: return false
            return !isNameChar(after)
        }

        private fun isNameChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == ':' || c == '-'

        /** Whether a closing tag of [name] starts at [tag], which is no later than its [last] one. */
        private fun isClosing(name: String, tag: Int, last: Int): Boolean {
            if (tag == last) return true
            if (content.getOrNull(tag + 1) != '/' || !startsWith(tag + 2, name)) {
                return false
            }
            // Spaces before `>` end before the last closing, which starts with `<`.
            return content.getOrNull(skipWhitespace(tag + 2 + name.length)) == '>'
        }

        /**
         * After the [close] of a section whose body starts at [from], or null when none starts by
         * [last]; it reads no further than [last], as the search it is part of is counted that far.
         */
        private fun sectionEnd(from: Int, close: String, last: Int): Int? {
            var at = from
            while (at <= last) {
                if (content.startsWith(close, at)) {
                    reach = maxOf(reach, at + close.length)
                    return at + close.length
                }
                at++
            }
            reach = maxOf(reach, last)
            return null
        }

        /**
         * Start of the exact `</name>` closing a tool call whose body starts at [from], when the
         * body holds only `<param>` elements, spaces and comments as the executor reads a call it
         * runs; -1 otherwise. Nothing past [last], where the name's last closing starts, is read.
         * Each value runs to its first `</param>` outside CDATA and comments, and each opening is
         * laid out as the executor requires ([paramOpeningEnd]). Every `<` in a value is read
         * minding quotes, and its tags are paired as the executor pairs them ([pairValueTag]); a
         * tag that does not end before [last], another `<param`, a closing of the call's own name,
         * and anything [pairValueTag] refuses make the executor read the call otherwise, so the
         * body is not taken as one. Only an exact `</param>` (in any case), once every tag opened
         * in the value has closed, ends it. Display-only tags in a value are the tool's input and
         * pair with nothing.
         *
         * A `<` in Markdown code in a value is text to the executor, so it is to this too
         * ([codeMaskOf]): the call's closing is only found outside the values, never in their
         * code. That holds up to where the executor's code mask ends the value ([paramEnd]);
         * where that is not where the value closes (a `</param>` in a comment, say), the mask
         * reads the rest of the call otherwise, so it must hold no code ([holdsCode]). The call is
         * read from its own opening on, as if the text before it left no code span open; outside
         * the values, in the opening and in comments, Markdown code must end where it starts, and
         * from a tag that changes how the mask reads on ([maskShift]), the rest holds no code too.
         */
        private fun paramsClosing(from: Int, last: Int): Int {
            reach = from
            val name = openingName
            val open = ArrayList<String>()
            // From here on, the executor's mask could read the call otherwise; it must hold no code.
            var plainFrom = maskShift(openingStart + 1, from, tags = true)
            if (plainFrom == UNENDED_CODE) return -1
            var index = from
            while (true) {
                // Spaces end at the last closing at the latest.
                index = skipWhitespace(index)
                reach = maxOf(reach, index)
                if (closesCall(index, name)) {
                    if (content[index + 2 + name.length] != '>') return -1
                    if (plainFrom >= 0 && holdsCode(plainFrom, index)) return -1
                    return index
                }
                if (startsWith(index, COMMENT_OPEN)) {
                    val text = index + COMMENT_OPEN.length
                    index = sectionEnd(text, COMMENT_CLOSE, last) ?: return -1
                    val shift = maskShift(text, index - COMMENT_CLOSE.length, tags = true)
                    if (shift == UNENDED_CODE) return -1
                    if (plainFrom < 0) plainFrom = shift
                    continue
                }
                val valueStart = paramOpeningEnd(index, last)
                if (valueStart < 0 || maskShift(index, valueStart, tags = false) == UNENDED_CODE) return -1
                val valueEnd = if (plainFrom >= 0) -1 else paramEnd(valueStart, last)
                // With no end to the value, the mask reads on from the parameter's own opening.
                if (plainFrom < 0 && valueEnd < 0) plainFrom = index
                val mask = if (plainFrom >= 0) null else codeMaskOf(valueStart, valueEnd)
                index = valueStart
                open.clear()
                while (true) {
                    // The last closing starts with `<`, so this finds one by then.
                    val tag = content.indexOf('<', index)
                    reach = maxOf(reach, tag)
                    if (tag < 0 || tag >= last) return -1
                    if (plainFrom < 0 && tag > valueEnd) plainFrom = valueEnd
                    if (content.startsWith(CDATA_OPEN, tag)) {
                        index = sectionEnd(tag + CDATA_OPEN.length, CDATA_CLOSE, last) ?: return -1
                        continue
                    }
                    if (plainFrom < 0 && mask != null && tag < valueEnd && mask[tag - valueStart]) {
                        index = tag + 1
                        continue
                    }
                    if (closesCall(tag, name)) return -1
                    if (startsWith(tag, COMMENT_OPEN)) {
                        index = sectionEnd(tag + COMMENT_OPEN.length, COMMENT_CLOSE, last) ?: return -1
                        continue
                    }
                    if (startsWith(tag, PARAM_OPEN) && !isNameChar(content.getOrElse(tag + PARAM_OPEN.length) { '>' })) {
                        return -1
                    }
                    if (startsWith(tag, PARAM_CLOSE) && !isNameChar(content.getOrElse(tag + PARAM_CLOSE.length) { '>' })) {
                        val close = tag + PARAM_CLOSE.length
                        // Closing before the mask ends the value, it would close in the mask's code.
                        if ((plainFrom < 0 && tag < valueEnd) || content[close] != '>' || open.isNotEmpty()) return -1
                        index = close + 1
                        break
                    }
                    if (isMarkupStart(tag)) {
                        // A tag that does not end before the last closing ends past every closing
                        // for the executor, or not at all; it then reads the call otherwise.
                        val stop = quotedStop(tag + 1, last)
                        reach = maxOf(reach, stop)
                        if (stop >= last || content[stop] != '>' || !pairValueTag(tag, stop + 1, open, last)) return -1
                        index = stop + 1
                        continue
                    }
                    // Not a tag. The executor reads it to the whole text's end, and passes over
                    // it whole when it ends at a `>`; past the last closing, that closing is in
                    // it. Otherwise it reads on just after this `<`, and later ones may read the
                    // same text again, so all such reading is counted.
                    if (valueSpent > content.length) return -1
                    val limit = minOf(content.length.toLong(), tag + 1 + content.length - valueSpent).toInt()
                    val end = quotedStop(tag + 1, limit)
                    valueSpent += end - tag
                    // The count ran out before the reading ended.
                    if (end >= limit && limit < content.length) return -1
                    if (end < content.length && content[end] == '>') {
                        if (end >= last) return -1
                        index = end + 1
                        continue
                    }
                    index = tag + 1
                }
            }
        }

        // How far the last [endOf] read, but for what [valueSpent] counts: every read that may go
        // past where its block ends moves it on.
        private var reach = 0

        /** Whether a closing tag of [name] (its whole name) starts at [at]. */
        private fun closesCall(at: Int, name: String): Boolean =
            content.getOrNull(at) == '<' && content.getOrNull(at + 1) == '/' && startsWith(at + 2, name) &&
                !isNameChar(content.getOrElse(at + 2 + name.length) { '>' })

        /**
         * How the executor's code mask reads the text from [from] until [until], read as outside
         * any code at [from]: [UNENDED_CODE] when a Markdown code span or fence it starts there
         * does not also end there (a span that does not close is code to the end of its line).
         * Otherwise, with [tags], the text is read outside a parameter, where the mask also reads
         * each `<` outside code: the first that changes how it reads on ([changesMask]), or -1.
         * Without [tags], -1.
         */
        private fun maskShift(from: Int, until: Int, tags: Boolean): Int {
            var index = from
            var lineStart = false
            var fence = 0
            var shift = -1
            while (index < until) {
                val c = content[index]
                if (fence > 0) {
                    if (lineStart && (c == ' ' || c == '\t')) {
                        index++
                        continue
                    }
                    if (lineStart) {
                        val end = runEnd(index, until)
                        if (end - index >= fence) {
                            index = end
                            fence = 0
                            lineStart = false
                            continue
                        }
                    }
                    lineStart = c == '\n'
                    index++
                    continue
                }
                if (c != '`') {
                    if (tags && shift < 0 && c == '<' && changesMask(index, until)) shift = index
                    lineStart = c == '\n'
                    index++
                    continue
                }
                val end = runEnd(index, until)
                val run = end - index
                if (run >= 3) {
                    index = end
                    fence = run
                    lineStart = false
                    continue
                }
                val closing = inlineClosing(end, run, until)
                if (closing >= 0) {
                    index = closing + run
                    lineStart = false
                    continue
                }
                // Unclosed by [until]: code through its line, which must end by then.
                var stop = end
                while (stop < until && content[stop] != '\n') stop++
                if (stop >= until) return UNENDED_CODE
                index = stop
            }
            return if (fence == 0) shift else UNENDED_CODE
        }

        /**
         * Whether the `<` at [at], outside code and outside a parameter, changes how the executor's
         * code mask reads on: the provider's reasoning envelope, whose body it marks, a tool's
         * opening or closing, or a parameter's opening, which it reads as such whether or not the
         * scan does. A tag whose name runs to [until] counts too, as its name is not known there.
         */
        private fun changesMask(at: Int, until: Int): Boolean {
            if (content.startsWith(PROVIDER_REASONING_OPEN_TAG, at)) return true
            if (content.getOrNull(at + 1) == '/') {
                // The executor reads a closing's name up to its `>`, trimmed as Kotlin trims (all
                // Unicode spaces), and matches it whole; a name holding a space or `<` is none.
                var index = at + 2
                while (index < until && content[index].isWhitespace()) index++
                val nameStart = index
                while (index < until && !content[index].isWhitespace() && content[index] != '>' &&
                    content[index] != '<'
                ) index++
                if (index >= until) return true
                if (!ChatMarkupRegex.isToolTagName(content.substring(nameStart, index))) return false
                while (index < until && content[index].isWhitespace()) index++
                return index >= until || content[index] == '>'
            }
            var index = at + 1
            while (index < until && isNameChar(content[index])) index++
            if (index >= until) return true
            val name = content.substring(at + 1, index)
            return ChatMarkupRegex.isToolTagName(name) || name.equals("param", ignoreCase = true)
        }

        // Characters read from a `<` in a value that is not a tag, which the next one may read again.
        private var valueSpent = 0L

        /** Whether a backtick, or a `<` when [markup], is from [from] until [until]. */
        private fun holds(from: Int, until: Int, markup: Boolean): Boolean {
            for (index in from until until) {
                val c = content[index]
                if (c == '`' || (markup && c == '<')) return true
            }
            return false
        }

        /**
         * Whether the executor's code mask could mark anything from [from] until [until]: a
         * backtick, or the provider's reasoning envelope, whose body it marks.
         */
        private fun holdsCode(from: Int, until: Int): Boolean {
            for (index in from until until) {
                val c = content[index]
                if (c == '`' || (c == '<' && content.startsWith(PROVIDER_REASONING_OPEN_TAG, index))) return true
            }
            return false
        }

        /**
         * Where the executor's code mask (`MarkdownCodeMask`) ends the value from [from]: its first
         * `</param>` (spaces allowed before `>`, any case) not in CDATA, found as it finds it; or -1
         * when there is none before [until].
         */
        private fun paramEnd(from: Int, until: Int): Int {
            var searchStart = from
            var closer = -1
            while (true) {
                // The first closer from an earlier start is still the first from a later one up to it.
                if (closer < searchStart) {
                    closer = nextParamCloser(searchStart, until)
                    if (closer < 0) return -1
                }
                var cdata = searchStart
                while (cdata < closer && !content.startsWith(CDATA_OPEN, cdata)) cdata++
                if (cdata >= closer) return closer
                searchStart = sectionEnd(cdata + CDATA_OPEN.length, CDATA_CLOSE, until) ?: return -1
            }
        }

        /** Start of the first `</param`, spaces and `>` from [from] before [until], or -1. */
        private fun nextParamCloser(from: Int, until: Int): Int {
            var at = from
            while (at < until) {
                if (content[at] == '<' && startsWith(at, PARAM_CLOSE)) {
                    var end = at + PARAM_CLOSE.length
                    while (end < content.length && isProtocolSpace(content[end])) end++
                    reach = maxOf(reach, end)
                    if (end < content.length && content[end] == '>') return at
                }
                at++
            }
            reach = maxOf(reach, until)
            return -1
        }

        /**
         * The executor's code mask over a value from [from] until [until], where it ends the value
         * ([paramEnd]): Markdown code spans and fences, as `MarkdownCodeMask` marks them in a
         * parameter, so the `<` in them are text. Null when the value holds no backtick.
         */
        private fun codeMaskOf(from: Int, until: Int): BooleanArray? {
            if (!holds(from, until, markup = false)) return null
            val mask = BooleanArray(until - from)
            var index = from
            var lineStart = false
            var fence = 0
            while (index < until) {
                val c = content[index]
                if (fence > 0) {
                    if (lineStart && (c == ' ' || c == '\t')) {
                        mask[index - from] = true
                        index++
                        continue
                    }
                    if (lineStart) {
                        val end = runEnd(index, until)
                        if (end - index >= fence) {
                            mask.fill(true, index - from, end - from)
                            index = end
                            fence = 0
                            lineStart = false
                            continue
                        }
                    }
                    mask[index - from] = true
                    lineStart = c == '\n'
                    index++
                    continue
                }
                if (c != '`') {
                    lineStart = c == '\n'
                    index++
                    continue
                }
                val end = runEnd(index, until)
                val run = end - index
                if (run >= 3) {
                    mask.fill(true, index - from, end - from)
                    index = end
                    fence = run
                    lineStart = false
                    continue
                }
                val closing = inlineClosing(end, run, until)
                if (closing >= 0) {
                    mask.fill(true, index - from, closing + run - from)
                    index = closing + run
                    lineStart = false
                    continue
                }
                // A span that does not close is code through its line.
                var stop = end
                while (stop < until && content[stop] != '\n') stop++
                mask.fill(true, end - from, stop - from)
                index = stop
            }
            return mask
        }

        private fun runEnd(from: Int, until: Int): Int {
            var index = from
            while (index < until && content[index] == '`') index++
            return index
        }

        /**
         * Start of the backticks that close a span of [run] from [from] on its line before [until],
         * or -1: the executor takes the first place where exactly [run] backticks are left, so a
         * longer run closes it with its last [run].
         */
        private fun inlineClosing(from: Int, run: Int, until: Int): Int {
            var index = from
            while (index < until && content[index] != '\n') {
                if (content[index] == '`') {
                    val end = runEnd(index, until)
                    if (end - index >= run) return end - run
                    index = end
                    continue
                }
                index++
            }
            return -1
        }

        /**
         * Pairs the tag from [tag] until [end] in a value as the executor does, or false where the
         * executor would not read the call as this does: an opening it does not take as a tag
         * (`<!`, `<?`, a bad name or attributes), or another tool call. Any other opening that
         * is not self-closing goes on [open]; a closing closes the last one when it names it, and
         * passes over otherwise. Display-only tags are the tool's input and go on nothing, and so
         * does the `<bytes>` placeholder the executor holds to be empty ([isUsagePlaceholder]).
         */
        private fun pairValueTag(tag: Int, end: Int, open: ArrayList<String>, until: Int): Boolean {
            // The tag ends at the `>` at end - 1, so each walk below stops by then.
            val first = content[tag + 1]
            if (first == '/') {
                val nameStart = tag + 2
                if (!isAsciiLetter(content[nameStart])) return true
                var index = nameStart + 1
                while (isNameChar(content[index])) index++
                val name = content.substring(nameStart, index).lowercase()
                while (isProtocolSpace(content[index])) index++
                if (index == end - 1 && open.isNotEmpty() && open.last() == name) open.removeAt(open.lastIndex)
                return true
            }
            if (!isAsciiLetter(first)) return false
            var index = tag + 2
            while (isNameChar(content[index])) index++
            val boundary = content[index]
            if (boundary != '>' && boundary != '/' && !boundary.isWhitespace()) return false
            if (!hasValidAttributes(index, end - 1)) return false
            if (isSelfClosing(end)) return true
            if (isToolCallOpening(tag)) return false
            val name = content.substring(tag + 1, index).lowercase()
            if (name == "think" || name == "thinking" || name == "search") return true
            if (name == "bytes" && isUsagePlaceholder(tag, end, until)) return true
            open.add(name)
            return true
        }

        /**
         * Whether the `<bytes>` from [tag] until [end] is the placeholder old tool output writes
         * after `usage:` or `limit:`, which the executor reads as a tag holding nothing
         * (`isLikelyLeafUnknownOpening`): that key right before the colon, and after the tag only
         * spaces and then punctuation, or a line break and then a tool tag.
         */
        private fun isUsagePlaceholder(tag: Int, end: Int, until: Int): Boolean {
            var colon = tag - 1
            while (colon >= 0 && isProtocolSpace(content[colon])) colon--
            if (colon < 0 || content[colon] != ':') return false
            val key = colon - 5
            if (key < 0 || !(startsWith(key, "usage") || startsWith(key, "limit"))) return false
            if (key > 0 && isNameChar(content[key - 1])) return false
            var index = end
            var lineBreak = false
            // The closing at [until] starts with `<`, so this stops by then.
            while (isProtocolSpace(content[index])) {
                if (content[index] == '\n' || content[index] == '\r') lineBreak = true
                index++
            }
            reach = maxOf(reach, index)
            if (isProtocolPunctuation(content[index])) return true
            return lineBreak && content[index] == '<' && isToolCallOpening(index) && isTagBefore(index, until)
        }

        /** Whether the opening at [at] is a tag the executor reads, ending before [until]. */
        private fun isTagBefore(at: Int, until: Int): Boolean {
            var index = at + 1
            // A name ends by the `<` at [until].
            while (isNameChar(content[index])) index++
            reach = maxOf(reach, index)
            val boundary = content[index]
            if (boundary != '>' && boundary != '/' && !boundary.isWhitespace()) return false
            val stop = quotedStop(index, until)
            reach = maxOf(reach, stop)
            return stop < until && content[stop] == '>' && hasValidAttributes(index, stop)
        }

        private fun isProtocolPunctuation(c: Char): Boolean =
            PROTOCOL_PUNCTUATION.indexOf(c) >= 0 || Character.isSurrogate(c) ||
                Character.getType(c) == Character.OTHER_SYMBOL.toInt()

        private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

        /**
         * After the `>` of the `<param name="…">` at [at], laid out as the executor requires a
         * parameter's opening (a non-empty quoted `name` and nothing else), or -1.
         */
        private fun paramOpeningEnd(at: Int, until: Int): Int {
            if (!content.startsWith(PARAM_OPEN, at)) return -1
            var index = at + PARAM_OPEN.length
            if (index >= until || !isProtocolSpace(content[index])) return -1
            index = skipProtocolSpace(index, until)
            if (!startsWith(index, "name")) return -1
            index = skipProtocolSpace(index + 4, until)
            if (index >= until || content[index] != '=') return -1
            index = skipProtocolSpace(index + 1, until)
            if (index >= until) return -1
            val quote = content[index]
            if (quote != '"' && quote != '\'') return -1
            var close = index + 1
            while (close < until && content[close] != quote) close++
            reach = maxOf(reach, close)
            if (close >= until || close == index + 1) return -1
            index = skipProtocolSpace(close + 1, until)
            return if (index < until && content[index] == '>') index + 1 else -1
        }

        private fun isProtocolSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

        private fun skipProtocolSpace(from: Int, until: Int): Int {
            var index = from
            while (index < until && isProtocolSpace(content[index])) index++
            reach = maxOf(reach, index)
            return index
        }

        // Where the executor reads a tag: `<` then a letter, `/`, `!` or `?`. Anything else it
        // passes over as text when it does not end.
        private fun isMarkupStart(tag: Int): Boolean {
            val c = content.getOrNull(tag + 1) ?: return false
            return c in 'a'..'z' || c in 'A'..'Z' || c == '/' || c == '!' || c == '?'
        }

        private val toolCall by lazy(LazyThreadSafetyMode.NONE) { toolCallOpeningName.matcher(content) }

        /** Whether a tool call's tag (its whole name) starts at [tag]. */
        private fun isToolCallOpening(tag: Int): Boolean {
            if (!startsWith(tag, "<tool")) return false
            toolCall.region(tag, content.length)
            return toolCall.lookingAt()
        }

        /**
         * Whether every display-only block opened from [from] until [until] also closes there, read
         * as [scanDisplayOnlyContent] reads it, so the text after reads the same either way.
         */
        private fun closesItsThinking(from: Int, until: Int): Boolean {
            val open = ArrayList<String>()
            var index = from
            while (index < until) {
                val tag = findNextDisplayOnlyToken(content, index, until, tagEndLimit = until) ?: break
                if (tag.endExclusive > until) return false
                when {
                    // Read up to [until] only, it may read otherwise in the whole text.
                    tag.isMalformed -> return false
                    tag.isClosing -> if (open.isNotEmpty()) {
                        if (open.last() != tag.family) return false
                        open.removeAt(open.lastIndex)
                    }
                    !tag.isSelfClosing -> open.add(tag.family)
                }
                index = tag.endExclusive
            }
            return open.isEmpty()
        }

        private fun endsWithContentClose(tag: Int): Boolean {
            var index = tag - 1
            while (index >= 0 && content[index].isWhitespace()) index--
            val start = index + 1 - CONTENT_CLOSE.length
            return start >= 0 && startsWith(start, CONTENT_CLOSE)
        }

        /**
         * Whether ASCII [prefix] is at [at], ignoring ASCII case only: the executor's names are
         * ASCII, so a letter such as `ſ` never stands for `s`.
         */
        private fun startsWith(at: Int, prefix: String): Boolean {
            if (at < 0 || at + prefix.length > content.length) return false
            for (i in prefix.indices) {
                val c = content[at + i]
                if (c != prefix[i] && (c.code >= 128 || c.lowercaseChar() != prefix[i].lowercaseChar())) return false
            }
            return true
        }

        private fun skipWhitespace(from: Int): Int {
            var index = from
            while (index < content.length && content[index].isWhitespace()) index++
            return index
        }

        private fun isSelfClosing(tagEnd: Int): Boolean {
            var index = tagEnd - 2
            while (index >= 0 && content[index].isWhitespace()) index--
            return index >= 0 && content[index] == '/'
        }

        /** Each tool name's last closing tag (`</name>`, spaces allowed before `>`): its start and end. */
        private fun lastClosingTags(): Map<String, IntArray> {
            val last = HashMap<String, IntArray>()
            val closing = toolClosingTagName.matcher(content)
            var at = content.indexOf("</tool", ignoreCase = true)
            while (at >= 0) {
                closing.region(at, content.length)
                if (closing.lookingAt()) {
                    val end = skipWhitespace(closing.end())
                    if (content.getOrNull(end) == '>') last[closing.group(1).lowercase()] = intArrayOf(at, end + 1)
                }
                at = content.indexOf("</tool", at + 1, ignoreCase = true)
            }
            return last
        }

        companion object {
            const val GIVEN_UP = -2
            // From [maskShift]: Markdown code that does not end where it starts.
            private const val UNENDED_CODE = -2
        }
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
        // How far a tag found may run; one running further counts as one that never ends.
        tagEndLimit: Int = content.length,
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

            val nextTerminator = findMarkupTagEnd(content, candidateStart, tagEndLimit)
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
