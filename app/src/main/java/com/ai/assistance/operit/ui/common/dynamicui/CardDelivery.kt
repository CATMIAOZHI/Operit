package com.ai.assistance.operit.ui.common.dynamicui

import com.ai.assistance.operit.util.ChatMarkupRegex
import java.util.UUID
import kotlinx.serialization.json.*

/** Temporary capabilities: never resolve another run's card or use history as a fallback.
 * Expanded messages contain their initial data; live final data uses a chat-scoped sidecar.
 * This temporary reference store is not needed to render history.
 * Models see short per-run names (card-1, card-2, ...); a 36-character UUID was easy to miscopy.
 * The UUID stays internal because live surfaces and state files are keyed by it across turns.
 */
internal object CardDelivery {
    private class Revision(val document: String, val source: String) {
        val size get() = document.length + source.length
        /** Shown in the answer (undone when the stream rolls back past it). */
        var placed = false
        /** Replaced by a later revision. */
        var superseded = false
        /** No longer waiting for a place in the answer. */
        val settled get() = placed || superseded
    }
    private val runs = mutableMapOf<String, MutableMap<String, Revision>>()
    @Synchronized fun begin(run: String) {
        if (runs.size < 64) runs.putIfAbsent(run, linkedMapOf())
    }
    @Synchronized fun end(run: String) { runs.remove(run); CardLiveStore.end(run) }
    @Synchronized fun register(run: String, document: String, source: String = "",
        liveChat: String? = null, liveDirectory: java.io.File? = null, revises: String? = null): String {
        val cards = runs[run] ?: error("Card generation is unavailable outside an active chat turn.")
        val id = UUID.randomUUID().toString()
        val stored = if (liveChat == null) document else document.lines().filter(String::isNotBlank).joinToString("\n") { line ->
            val event = Json.parseToJsonElement(line).jsonObject
            val kind = event.keys.single { it != "version" }
            val body = event.getValue(kind).jsonObject
            JsonObject(event + (kind to JsonObject(body + ("surfaceId" to JsonPrimitive(CardLiveStore.surface(id)))))).toString()
        }
        val revision = Revision(stored, source)
        require(cards.size < 16 && cards.values.sumOf { it.size } + revision.size <= 524_288) {
            "Too many cards in this turn."
        }
        require(runs.values.sumOf { map -> map.values.sumOf { it.size } } + revision.size <= 4_194_304) {
            "Card generation is busy. Try again after other tasks finish."
        }
        if (liveChat != null) CardLiveStore.register(run, id, liveChat, requireNotNull(liveDirectory), source, stored)
        revises?.let { canonical(run, it) }?.let { cards[it]?.superseded = true }
        cards[id] = revision
        return "card-${cards.size}"
    }
    /** Maps a model-facing name (or an internal UUID from this run) to the internal UUID. */
    @Synchronized fun canonical(run: String, reference: String): String? {
        val cards = runs[run] ?: return null
        val ref = reference.trim().lowercase()
        if (ref in cards) return ref
        val index = SHORT_NAME.matchEntire(ref)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return cards.keys.elementAtOrNull(index - 1)
    }
    @Synchronized fun resolve(run: String, reference: String): String? {
        val id = canonical(run, reference) ?: return null
        val revision = runs[run]?.get(id) ?: return null
        if (!CardLiveStore.publish(run, id)) return null
        revision.placed = true
        return revision.document
    }
    /** The stream rolled back past the place of [reference]; it waits for a place again. */
    @Synchronized fun unplace(run: String, reference: String) {
        canonical(run, reference)?.let { runs[run]?.get(it)?.placed = false }
    }
    /**
     * The model-facing name of the first card prepared in this turn that is still waiting for its
     * place. Used when the model writes the app's history summary line instead of the reference.
     */
    @Synchronized fun nextUnplaced(run: String): String? {
        val cards = runs[run] ?: return null
        val index = cards.values.indexOfFirst { !it.settled }
        return if (index < 0) null else "card-${index + 1}"
    }
    @Synchronized fun source(run: String, reference: String): String? =
        canonical(run, reference)?.let { runs[run]?.get(it)?.source?.takeIf(String::isNotEmpty) }
    private val SHORT_NAME = Regex("card-(\\d{1,2})")
    val REFERENCE = Regex("[0-9a-f-]{36}|card-\\d{1,2}", RegexOption.IGNORE_CASE)
}

/**
 * Model-facing form of an expanded card. History keeps the full document for rendering, but
 * sending it back each turn costs tokens and shows the model JSON it never wrote. The summary
 * keeps visible text and data so later turns can still discuss the card. Deterministic, so the
 * same message always produces the same prompt prefix.
 */
internal object CardTranscript {
    private val block = Regex("(?m)^```a2ui\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*$")
    const val PLACEHOLDER_HEAD = "[Dynamic UI card shown to the user here"
    /** A whole line in the history summary form; models sometimes copy it instead of a reference. */
    val PLACEHOLDER_LINE = Regex("\\[Dynamic UI card shown to the user here(?:\\. Visible content: [^\\n]*|…|\\.\\.\\.)?\\][ \\t]*")
    private const val MAX_SUMMARY = 1200
    private val structural = setOf(
        "version", "surfaceId", "catalogId", "id", "component", "child", "children", "path", "call",
        "action", "event", "context", "columnWidths", "maxHeight", "minItemWidth", "steps", "min", "max",
        "variant", "tone", "icon", "type", "weight", "align", "justify", "css", "key", "template",
        "style", "size", "color", "background", "span", "gap", "padding", "url", "src", "fit", "shape",
    )
    private val markup = Regex("<(style|script)\\b[\\s\\S]*?</\\1\\s*>|<[^>]*>", RegexOption.IGNORE_CASE)

    /** [maxSummary] bounds chat prompts; memory learning passes a larger bound to keep the whole card. */
    fun forModel(content: String, maxSummary: Int = MAX_SUMMARY): String {
        if (!content.contains("```a2ui")) return content
        return block.replace(content) { match ->
            PLACEHOLDER_HEAD + summary(match.groupValues[1], maxSummary) + "]"
        }
    }

    private fun summary(document: String, maxSummary: Int): String {
        val parts = mutableListOf<String>()
        document.lineSequence().filter(String::isNotBlank).forEach { line ->
            val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            event["updateComponents"]?.jsonObject?.get("components")?.jsonArray?.forEach { component ->
                (component as? JsonObject)?.let { flatten(it) }?.takeIf(String::isNotBlank)?.let(parts::add)
            }
            event["updateDataModel"]?.jsonObject?.let { flatten(it) }?.takeIf(String::isNotBlank)?.let(parts::add)
        }
        if (parts.isEmpty()) return ""
        val text = parts.joinToString("; ")
        return ". Visible content: " + if (text.length <= maxSummary) text else text.take(maxSummary) + "…"
    }

    private fun flatten(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> if (element.isString || element.booleanOrNull == null) element.content else ""
        is JsonArray -> element.map(::flatten).filter(String::isNotBlank)
            .joinToString(if (element.any { it is JsonArray || it is JsonObject }) " | " else ", ")
        is JsonObject -> element.filterKeys { it !in structural }.map { (key, value) ->
            // Html cards keep their readable text; tags and styles are layout.
            if (key == "html" && value is JsonPrimitive && value.isString)
                value.content.replace(markup, " ").replace(Regex("\\s+"), " ").trim()
            else flatten(value)
        }.filter(String::isNotBlank).joinToString(" ")
    }
}

/** Incremental Markdown reference decoder. Ordinary prose is emitted immediately; only a
 * possible fence header / short reference is buffered. It runs before ALL transcript sinks.
 * An unresolved reference stays as its a2ui-ref fence; the renderer shows a short notice and the
 * model later sees exactly what it wrote.
 * History shows earlier cards to the model as a "[Dynamic UI card shown …]" line, and a model may
 * copy that line instead of placing the reference it just prepared. Such a whole line takes the
 * first card of this turn that has no place yet ([nextUnplaced]); without one it becomes an
 * unresolved reference, so the user sees the usual "card unavailable" notice instead of the line.
 */
internal class CardReferenceExpander(
    private val resolve: (String) -> String?,
    private val nextUnplaced: () -> String? = { null },
    private val unplace: (String) -> Unit = {},
) {
    private data class Snapshot(
        val pending: String, val reference: String?, val start: Boolean,
        val fence: Char?, val fenceLength: Int, val tag: String, val hidden: Int, val expanded: Int,
        val summary: String?, val placed: Int,
    )
    private var pending = ""
    private var reference: String? = null
    private var summary: String? = null
    /** References this stream has placed, in order, so a rollback can give them back. */
    private val placed = mutableListOf<String>()
    private var start = true
    private var fence: Char? = null
    private var fenceLength = 0
    private var tag = ""
    private var hidden = 0
    private var expanded = 0
    private val snapshots = mutableMapOf<String, Snapshot>()
    fun savepoint(id: String) {
        snapshots[id] = Snapshot(pending, reference, start, fence, fenceLength, tag, hidden, expanded, summary, placed.size)
    }
    fun rollback(id: String) {
        val s = snapshots[id] ?: return
        pending = s.pending; reference = s.reference; start = s.start
        fence = s.fence; fenceLength = s.fenceLength; tag = s.tag; hidden = s.hidden; expanded = s.expanded
        summary = s.summary
        // A card placed twice keeps its place while the earlier placement is still in the text.
        while (placed.size > s.placed) placed.removeAt(placed.lastIndex).let { if (it !in placed) unplace(it) }
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
            summary?.let { buffered ->
                val line = buffered + c
                summary = line
                if (c == '\n') append(summaryLine())
                else if (line.length <= HEAD.length && !HEAD.startsWith(line) || line.length > 4096) {
                    // Ordinary prose that only starts with "[": give it back unchanged.
                    summary = null; start = false
                    append(line)
                    if (fence == null) line.forEach(::trackMarkup)
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
            if (start && c == '[' && fence == null && hidden == 0) {
                summary = "["
                continue
            }
            append(c)
            if (fence == null) trackMarkup(c)
            start = c == '\n'
        }
    }
    private fun summaryLine(): String {
        val raw = summary!!
        summary = null; start = raw.endsWith('\n')
        if (CardTranscript.PLACEHOLDER_LINE.matches(raw.trimEnd('\r', '\n'))) {
            val name = nextUnplaced()
            val source = name?.let(resolve)
            if (source != null && expanded + source.length <= 524_288) {
                expanded += source.length
                placed += name
                return "```a2ui\n$source\n```" + if (start) "\n" else ""
            }
            // Nothing to show here: the renderer's notice beats an English line the app wrote.
            return "```a2ui-ref\nunavailable\n```" + if (start) "\n" else ""
        }
        if (fence == null) raw.forEach(::trackMarkup)
        return raw
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
            val source = if (id.matches(CardDelivery.REFERENCE)) resolve(id) else null
            if (source == null || expanded + source.length > 524_288) return "```a2ui-ref\n$id\n$raw"
            expanded += source.length
            placed += id
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
        if (summary != null) return summaryLine()
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
    private companion object {
        const val HEAD = CardTranscript.PLACEHOLDER_HEAD
    }
}
