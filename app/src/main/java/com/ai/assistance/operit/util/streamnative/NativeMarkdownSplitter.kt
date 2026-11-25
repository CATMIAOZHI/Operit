package com.ai.assistance.operit.util.streamnative

import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType

private fun Int.toMarkdownTypeOrNull(): MarkdownProcessorType? =
    MarkdownProcessorType.entries.getOrNull(this)

/**
 * How many formula groups in a row that are no formula may be read again. The last reading takes
 * '$' as plain text, and what still fails there stays plain, so a long line is read a few times at most.
 */
private const val MAX_PRICE_REREADS = 4

/** A private character absent from [text] that can stand in for '$' while it is read, or null. */
private fun standInFor(text: String): Char? = ('\uF8F0'..'\uF8FF').firstOrNull { it !in text }

private fun IntArray.toInlineStableNodes(content: String, next: Char?, rereads: Int): List<MarkdownNodeStable> {
    val nodes = ArrayList<MarkdownNodeStable>(size / 3)
    var index = 0

    while (index + 2 < size) {
        val typeOrdinal = this[index]
        val start = this[index + 1]
        val end = this[index + 2]
        index += 3

        if (typeOrdinal < 0 || start < 0 || end < start || end > content.length) {
            continue
        }

        val type = typeOrdinal.toMarkdownTypeOrNull() ?: MarkdownProcessorType.PLAIN_TEXT
        val nodeContent =
            if (type == MarkdownProcessorType.HTML_BREAK) {
                "\n"
            } else {
                content.substring(start, end)
            }
        // A '$' group that is no formula (a price) is read again as ordinary Markdown after its
        // '$', so a link or bold text on the same line still works. After a few in a row (a line
        // full of prices) the rest is read once with no formulas, so a long line stays one pass.
        if (type == MarkdownProcessorType.INLINE_LATEX) {
            val after = if (end < content.length) content[end] else next
            if (!com.ai.assistance.operit.util.markdown.InlineLatexRule.isFormula(nodeContent, after)) {
                if (rereads >= MAX_PRICE_REREADS) {
                    nodes += MarkdownNodeStable(type = MarkdownProcessorType.PLAIN_TEXT, content = nodeContent, children = emptyList())
                    continue
                }
                nodes += MarkdownNodeStable(type = MarkdownProcessorType.PLAIN_TEXT, content = nodeContent.take(1), children = emptyList())
                val rest = nodeContent.substring(1)
                val standIn = if (rereads + 1 < MAX_PRICE_REREADS) null else standInFor(rest)
                nodes +=
                    when {
                        rereads + 1 < MAX_PRICE_REREADS -> NativeMarkdownSplitter.parseInline(rest, after, rereads + 1)
                        standIn == null ->
                            listOf(MarkdownNodeStable(type = MarkdownProcessorType.PLAIN_TEXT, content = rest, children = emptyList()))
                        // The last reading: no '$' formulas, and a '\(' that fails stays plain.
                        else -> NativeMarkdownSplitter.parseInline(rest.replace('$', standIn), after, MAX_PRICE_REREADS)
                            .map { it.copy(content = it.content.replace(standIn, '$')) }
                    }
                continue
            }
        }
        nodes +=
            MarkdownNodeStable(
                type = type,
                content = nodeContent,
                children = emptyList()
            )
    }

    return nodes
}

object NativeMarkdownSplitter {

    init {
        System.loadLibrary("streamnative")
    }

    private external fun nativeCreateBlockSession(): Long
    private external fun nativeCreateInlineSession(): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativePush(handle: Long, chunk: String): IntArray
    private external fun nativeFinish(handle: Long): IntArray

    class Session internal constructor(
        private val handle: Long,
    ) {
        fun push(chunk: String): IntArray = nativePush(handle, chunk)
        /** Ends the input: text still waiting for a closing delimiter becomes plain text. */
        fun finish(): IntArray = nativeFinish(handle)
        fun destroy() = nativeDestroySession(handle)
    }

    fun createBlockSession(): Session = Session(nativeCreateBlockSession())
    fun createInlineSession(): Session = Session(nativeCreateInlineSession())

    /**
     * [next] is the character right after [content] in the message (null at its end), so a '$'
     * group at the end of [content] is judged as it is there.
     */
    fun parseInlineToStableNodes(content: String, next: Char? = null): List<MarkdownNodeStable> =
        parseInline(content, next, 0)

    internal fun parseInline(content: String, next: Char?, rereads: Int): List<MarkdownNodeStable> {
        if (content.isEmpty()) return emptyList()

        val session = createInlineSession()
        val groups = try {
            session.push(content) + session.finish()
        } finally {
            session.destroy()
        }
        // The session is gone before any group is read again, so at most one is open at a time.
        return groups.toInlineStableNodes(content, next, rereads)
    }
}
