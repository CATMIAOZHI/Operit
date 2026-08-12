package com.ai.assistance.operit.util.stream.plugins

import com.ai.assistance.operit.util.DisplayEndMatchResult
import com.ai.assistance.operit.util.DisplayEndTagMatcher
import com.ai.assistance.operit.util.displayEndTagNames
import com.ai.assistance.operit.util.stream.*

private const val GROUP_TAG_NAME = 1

private enum class StartTagNameLexicalState {
    WAIT_LT,
    EXPECT_FIRST,
    IN_NAME,
    IN_ATTRS,
    INVALID,
}

/**
 * A stream processing plugin to identify and process XML-formatted data streams. This
 * implementation uses the group capturing mechanism of the StreamKmpGraph. This version has a known
 * limitation: it does not handle nested tags of the same name.
 *
 * @param includeTagsInOutput If true, the XML tags themselves (`<tag>`, `</tag>`) will be included
 * in the output stream. If false, they will be filtered out, leaving only the content between the
 * tags.
 */
class StreamXmlPlugin(private val includeTagsInOutput: Boolean = true) : StreamPlugin {

    override var state: PluginState = PluginState.IDLE
        private set

    override val blocksCompetingPluginsWhileTrying: Boolean
        get() = state == PluginState.TRYING && startTagQuote != null

    private var startTagMatcher: StreamKmpGraph
    private var endTagMatcher: StreamKmpGraph? = null
    private var displayEndTagMatcher: DisplayEndTagMatcher? = null
    // Allow matching a new start tag immediately after we just closed an end tag, even if not at start of line
    private var allowStartAfterEndTag: Boolean = false
    private var allowStartAfterPunctuation: Boolean = false
    private var lastChar: Char = '\u0000'
    private var startTagQuote: Char? = null
    private var startTagLastNonWhitespace: Char? = null
    private var pendingStartTagName: String? = null
    private var startTagNameLexicalState: StartTagNameLexicalState = StartTagNameLexicalState.WAIT_LT

    private val punctuationTriggers =
            setOf('，', '。', '？', '！', '：', '（', '）', '【', '】', '《', '》', ':', ',', '.', '?', '!', '~', '～')
    private val emojiContinuationChars = setOf('\u200D', '\uFE0E', '\uFE0F', '\u20E3')

    init {
        startTagMatcher =
                StreamKmpGraphBuilder()
                        .build(
                                kmpPattern {
                                    char('<')
                                    // Group 1: Capture the tag name. A valid tag name must start
                                    // with a letter.
                                    // This prevents matching comments (<!--) or closing tags (</).
                                    group(GROUP_TAG_NAME) {
                                        predicate("asciiXmlTagFirstChar") {
                                            it in 'A'..'Z' || it in 'a'..'z'
                                        }
                                        greedyStar {
                                            predicate("xmlTagNameContinuation") {
                                                (it in 'A'..'Z') ||
                                                    (it in 'a'..'z') ||
                                                    (it in '0'..'9') ||
                                                    it == '_' ||
                                                    it == '-' ||
                                                    it == '.' ||
                                                    it == ':'
                                            }
                                        }
                                    }
                                    // Optional: Match attributes until the tag closes. The plugin's
                                    // quote-aware lexical state independently verifies the exact
                                    // name boundary because this graph's greedy group does not
                                    // expose a stable suffix capture.
                                    greedyStar { notChar('>') }
                                    char('>')
                                }
                        )

        reset()
    }

    /**
     * Processes a single character for XML stream parsing and decides if it should be emitted. The
     * return value is used by `splitBy` as a filter.
     */
    override fun processChar(c: Char, atStartOfLine: Boolean): Boolean {
        fun finish(result: Boolean): Boolean {
            lastChar = c
            return result
        }

        if (state == PluginState.PROCESSING) {
            displayEndTagMatcher?.let { matcher ->
                return when (matcher.processChar(c)) {
                    DisplayEndMatchResult.MATCH -> {
                        StreamLogger.i("StreamXmlPlugin", "Found display end tag. Switching to IDLE.")
                        allowStartAfterEndTag = true
                        allowStartAfterPunctuation = false
                        reset()
                        finish(includeTagsInOutput)
                    }
                    DisplayEndMatchResult.IN_PROGRESS -> finish(includeTagsInOutput)
                    DisplayEndMatchResult.NO_MATCH -> finish(true)
                }
            }

            // We are inside a tag, looking for the end tag.
            val matcher = endTagMatcher!!
            val result = matcher.processChar(c)

            return when (result) {
                is StreamKmpMatchResult.Match -> {
                    // End tag fully matched. Reset state and filter this last character if needed.
                    StreamLogger.i("StreamXmlPlugin", "Found end tag. Switching to IDLE.")
                    // Enable one-time allowance for starting a new tag right after this end tag
                    allowStartAfterEndTag = true
                    allowStartAfterPunctuation = false
                    reset()
                    finish(includeTagsInOutput)
                }
                is StreamKmpMatchResult.InProgress -> {
                    // We are in the middle of matching the end tag (e.g., '</', '</t', etc.).
                    // The emission of these characters depends on the flag.
                    finish(includeTagsInOutput)
                }
                is StreamKmpMatchResult.NoMatch -> {
                    // The character `c` did not match the next char of the end tag.
                    // This means it's regular content between tags.
                    finish(true)
                }
            }
        } else {
            if (state == PluginState.IDLE && !atStartOfLine) {
                val allowStart = allowStartAfterEndTag || allowStartAfterPunctuation
                if (!allowStart) {
                    return finish(handleDefaultCharacter(c))
                }
                // Allow adjacent XML after an end tag/punctuation even if separated by spaces/tabs
                if (c == ' ' || c == '\t' || isEmojiContinuationChar(c)) {
                    return finish(handleDefaultCharacter(c))
                }
            }
            pendingStartTagName?.let { tagName ->
                updateStartTagLexicalState(c)
                if (startTagQuote == null && c == '>') {
                    return finish(completeStartTag(tagName))
                }
                return finish(includeTagsInOutput)
            }

            // We are in IDLE or TRYING state, looking for a start tag.
            val previousState = state
            if (previousState == PluginState.TRYING) {
                updateStartTagLexicalState(c)
            } else {
                resetStartTagLexicalState()
                updateStartTagLexicalState(c)
            }
            when (val result = startTagMatcher.processChar(c)) {
                is StreamKmpMatchResult.Match -> {
                    val tagName = result.groups[GROUP_TAG_NAME]
                    if (tagName != null) {
                        if (startTagNameLexicalState == StartTagNameLexicalState.INVALID) {
                            reset()
                            return finish(includeTagsInOutput)
                        }
                        if (startTagQuote != null) {
                            // The graph stops at every `>`, including one inside a quoted
                            // attribute. Keep the already recognized tag name and continue with
                            // the quote-aware lexical state until the real terminator arrives.
                            pendingStartTagName = tagName
                            state = PluginState.TRYING
                            startTagMatcher.reset()
                            return finish(includeTagsInOutput)
                        }
                        return finish(completeStartTag(tagName))
                    } else {
                        // Should not happen, but as a safeguard:
                        reset()
                    }
                    return finish(includeTagsInOutput)
                }
                is StreamKmpMatchResult.InProgress -> {
                    state = PluginState.TRYING
                    // We are attempting a new start, consume the allowance
                    // so only this potential sequence benefits from it
                    // (if it fails below, we will clear it)
                    // Keep it true while in-progress so subsequent chars can proceed
                    allowStartAfterPunctuation = false
                    return finish(includeTagsInOutput)
                }
                is StreamKmpMatchResult.NoMatch -> {
                    // If we were trying and the match failed, we must reset to idle.
                    if (previousState == PluginState.TRYING) {
                        reset()
                    }
                    // Clear the allowance if we failed to start a new tag
                    allowStartAfterEndTag = false
                    allowStartAfterPunctuation = false
                    // This is a default character, not part of a tag managed by this plugin.
                    return finish(handleDefaultCharacter(c))
                }
            }
        }
    }

    /** Initializes the plugin to its default state. */
    override fun initPlugin(): Boolean {
        reset()
        return true
    }

    /** Destroys the plugin. No-op as listener is removed. */
    override fun destroy() {}

    /** Resets the plugin state. */
    override fun reset() {
        endTagMatcher = null
        displayEndTagMatcher?.reset()
        displayEndTagMatcher = null
        startTagMatcher.reset()
        state = PluginState.IDLE
        lastChar = '\u0000'
        resetStartTagLexicalState()
    }

    private fun completeStartTag(tagName: String): Boolean {
        if (startTagLastNonWhitespace == '/') {
            // Treat self-closing tags like <br/> as plain text to avoid entering XML mode.
            reset()
            // Only app-owned display tags may expose an immediately adjacent tool. Treating
            // arbitrary HTML/XML tags as boundaries expands executable contexts such as
            // <br/><tool ...>.
            allowStartAfterEndTag =
                tagName.equals("think", ignoreCase = true) ||
                    tagName.equals("thinking", ignoreCase = true) ||
                    tagName.equals("search", ignoreCase = true)
            return true
        }

        StreamLogger.i("StreamXmlPlugin", "Found start tag '$tagName'. Switching to PROCESSING.")
        state = PluginState.PROCESSING
        allowStartAfterEndTag = false
        allowStartAfterPunctuation = false
        displayEndTagMatcher = displayEndTagNames(tagName)?.let(::DisplayEndTagMatcher)
        endTagMatcher =
            if (displayEndTagMatcher == null) {
                StreamKmpGraphBuilder()
                    .build(
                        kmpPattern {
                            literal("</")
                            literal(tagName)
                            char('>')
                        }
                    )
            } else {
                null
            }
        startTagMatcher.reset()
        resetStartTagLexicalState()
        return includeTagsInOutput
    }

    private fun updateStartTagLexicalState(c: Char) {
        when (startTagNameLexicalState) {
            StartTagNameLexicalState.WAIT_LT -> {
                if (c == '<') startTagNameLexicalState = StartTagNameLexicalState.EXPECT_FIRST
                return
            }

            StartTagNameLexicalState.EXPECT_FIRST -> {
                startTagNameLexicalState =
                    if (c in 'A'..'Z' || c in 'a'..'z') {
                        StartTagNameLexicalState.IN_NAME
                    } else {
                        StartTagNameLexicalState.INVALID
                    }
                if (startTagNameLexicalState == StartTagNameLexicalState.IN_NAME) {
                    startTagLastNonWhitespace = c
                }
                return
            }

            StartTagNameLexicalState.IN_NAME -> {
                val isContinuation =
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.:-"
                when {
                    isContinuation -> startTagLastNonWhitespace = c
                    c == '>' -> Unit
                    c == '/' || c.isWhitespace() -> {
                        startTagNameLexicalState = StartTagNameLexicalState.IN_ATTRS
                        if (c == '/') startTagLastNonWhitespace = c
                    }
                    else -> startTagNameLexicalState = StartTagNameLexicalState.INVALID
                }
                return
            }

            StartTagNameLexicalState.INVALID -> return
            StartTagNameLexicalState.IN_ATTRS -> Unit
        }

        val quote = startTagQuote
        if (quote != null) {
            if (!c.isWhitespace()) startTagLastNonWhitespace = c
            if (c == quote) startTagQuote = null
            return
        }
        when (c) {
            '\'', '"' -> {
                startTagLastNonWhitespace = c
                startTagQuote = c
            }
            '>' -> Unit
            else -> if (!c.isWhitespace()) startTagLastNonWhitespace = c
        }
    }

    private fun resetStartTagLexicalState() {
        startTagQuote = null
        startTagLastNonWhitespace = null
        pendingStartTagName = null
        startTagNameLexicalState = StartTagNameLexicalState.WAIT_LT
    }

    private fun handleDefaultCharacter(c: Char): Boolean {
        updatePunctuationAllowance(c)
        return true
    }

    private fun updatePunctuationAllowance(c: Char) {
        when {
            punctuationTriggers.contains(c) || isEmojiTrigger(c) -> {
                allowStartAfterPunctuation = true
            }
            c == ' ' || c == '\t' || isEmojiContinuationChar(c) -> {
                // Keep current state so `<` after spaces/tabs or emoji joiners/selectors still benefits.
            }
            else -> {
                allowStartAfterPunctuation = false
            }
        }
    }

    private fun isEmojiTrigger(c: Char): Boolean {
        // Most modern emojis are surrogate pairs in UTF-16. Treat either half as a trigger.
        if (Character.isSurrogate(c)) {
            return true
        }
        // BMP emoji/symbols (e.g. ☀, ❤) are usually "OTHER_SYMBOL".
        return Character.getType(c) == Character.OTHER_SYMBOL.toInt()
    }

    private fun isEmojiContinuationChar(c: Char): Boolean = emojiContinuationChars.contains(c)
}
