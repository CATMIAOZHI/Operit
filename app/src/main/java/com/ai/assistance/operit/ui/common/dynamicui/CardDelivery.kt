package com.ai.assistance.operit.ui.common.dynamicui

import com.ai.assistance.operit.util.ChatMarkupRegex
import java.util.UUID

/** Temporary capabilities: never resolve another run's card or use history as a fallback.
 * Expanded messages are self-contained; this store is not needed for history or export.
 */
internal object CardDelivery {
    private data class Revision(val document: String, val source: String) {
        val size get() = document.length + source.length
    }
    private val runs = mutableMapOf<String, MutableMap<String, Revision>>()
    @Synchronized fun begin(run: String) {
        if (runs.size < 64) runs.putIfAbsent(run, linkedMapOf())
    }
    @Synchronized fun end(run: String) { runs.remove(run) }
    @Synchronized fun register(run: String, document: String, source: String = ""): String {
        val cards = runs[run] ?: error("Card generation is unavailable outside an active chat turn.")
        val revision = Revision(document, source)
        require(cards.size < 16 && cards.values.sumOf { it.size } + revision.size <= 524_288) {
            "Too many cards in this turn."
        }
        require(runs.values.sumOf { map -> map.values.sumOf { it.size } } + revision.size <= 4_194_304) {
            "Card generation is busy. Try again after other tasks finish."
        }
        val id = UUID.randomUUID().toString()
        cards[id] = revision
        return id
    }
    @Synchronized fun resolve(run: String, id: String): String? = runs[run]?.get(id)?.document
    @Synchronized fun source(run: String, id: String): String? = runs[run]?.get(id)?.source?.takeIf { it.isNotEmpty() }
}

/** Incremental Markdown reference decoder. Ordinary prose is emitted immediately; only a
 * possible fence header / short reference is buffered. It runs before ALL transcript sinks.
 */
internal class CardReferenceExpander(
    private val resolve: (String) -> String?,
    private val unavailable: String,
) {
    private data class Snapshot(
        val pending: String, val reference: String?, val start: Boolean,
        val fence: Char?, val fenceLength: Int, val tag: String, val hidden: Int, val expanded: Int,
    )
    private var pending = ""
    private var reference: String? = null
    private var start = true
    private var fence: Char? = null
    private var fenceLength = 0
    private var tag = ""
    private var hidden = 0
    private var expanded = 0
    private val snapshots = mutableMapOf<String, Snapshot>()
    fun savepoint(id: String) {
        snapshots[id] = Snapshot(pending, reference, start, fence, fenceLength, tag, hidden, expanded)
    }
    fun rollback(id: String) {
        val s = snapshots[id] ?: return
        pending = s.pending; reference = s.reference; start = s.start
        fence = s.fence; fenceLength = s.fenceLength; tag = s.tag; hidden = s.hidden; expanded = s.expanded
    }
    fun push(chunk: String): String = buildString {
        for (c in chunk) {
            if (reference != null) {
                pending += c
                if (c == '\n') append(referenceLine())
                else if (pending.length > 128) {
                    append("```a2ui-ref\n").append(reference).append(pending)
                    reference = null; pending = ""; fence = '`'; fenceLength = 3; start = false
                }
                continue
            }
            if (pending.isNotEmpty() || start && (c == '`' || c == '~') && hidden == 0) {
                pending += c
                if (c == '\n') append(fenceLine())
                else if (pending.length > 256) {
                    // Long headers remain ordinary code; never interpret their later body as references.
                    val count = pending.takeWhile { it == pending[0] }.length
                    if (fence == null && count >= 3) { fence = pending[0]; fenceLength = count }
                    append(pending); pending = ""; start = false
                }
                continue
            }
            append(c)
            if (fence == null) trackMarkup(c)
            start = c == '\n'
        }
    }
    private fun trackMarkup(c: Char) {
        if (c == '<') tag = "<"
        else if (tag.isNotEmpty()) {
            if (tag.length < 512) tag += c else { tag = ""; return }
            if (c == '>') {
                val match = Regex("^<(/?)([A-Za-z_][A-Za-z0-9_:-]*)(?:\\s[^>]*)?>$").matchEntire(tag)
                if (match != null) {
                    val name = match.groupValues[2].lowercase()
                    val localName = name.substringAfterLast(':')
                    if (name in setOf("think", "thinking", "analysis", "reasoning", "tool_results", "tools") ||
                        ChatMarkupRegex.isToolTagName(localName) ||
                        ChatMarkupRegex.isToolResultTagName(localName)) {
                        if (match.groupValues[1] == "/") hidden = (hidden - 1).coerceAtLeast(0)
                        else if (!tag.endsWith("/>")) hidden++
                    }
                }
                tag = ""
            }
        }
    }
    private fun fenceLine(): String {
        val raw = pending
        pending = ""; start = raw.endsWith('\n')
        val line = raw.trimEnd('\r', '\n')
        val count = line.takeWhile { it == line.firstOrNull() }.length
        if (fence == null) {
            if (line == "```a2ui-ref") { reference = ""; return "" }
            if (count >= 3) { fence = line[0]; fenceLength = count }
        } else if (line.firstOrNull() == fence && count >= fenceLength && line.drop(count).isBlank()) {
            fence = null; fenceLength = 0
        }
        return raw
    }
    private fun referenceLine(): String {
        val raw = pending
        pending = ""
        val line = raw.trimEnd('\r', '\n')
        if (line == "```") {
            val id = reference!!.trim()
            reference = null; start = raw.endsWith('\n')
            val source = if (id.matches(Regex("[0-9a-f-]{36}"))) resolve(id) else null
            if (source == null || expanded + source.length > 524_288) return "$unavailable\n"
            expanded += source.length
            return "```a2ui\n$source\n```" + if (start) "\n" else ""
        }
        reference += raw
        if (reference!!.length > 128) {
            val result = "```a2ui-ref\n" + reference
            reference = null; fence = '`'; fenceLength = 3; start = raw.endsWith('\n')
            return result
        }
        return ""
    }
    /** Incomplete references stay plain code on cancellation; only a complete closing line expands. */
    fun finish(): String {
        if (reference != null) {
            if (pending == "```") return referenceLine()
            val tail = "```a2ui-ref\n" + reference + pending
            reference = null; pending = ""
            return tail
        }
        if (pending.isNotEmpty()) return fenceLine().let {
            if (reference != null) { reference = null; "```a2ui-ref" } else it
        }
        return ""
    }
}
