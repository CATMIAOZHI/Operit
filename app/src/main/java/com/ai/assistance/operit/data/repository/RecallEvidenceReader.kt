package com.ai.assistance.operit.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A bounded-memory scan. Filtering starts at the real message boundary, so paging through a
 * thought or a protocol envelope cannot accidentally expose its body. Offsets are code points.
 */
internal object RecallEvidenceReader {
    data class Page(val text: String, val start: Int, val total: Int, val matched: Boolean, val revision: String) {
        val next: Int? get() = (start + text.codePointCount(0, text.length)).takeIf { matched && it < total }
    }

    suspend fun read(
        readBytes: suspend (Long) -> ByteArray?,
        assistant: Boolean,
        includeThinking: Boolean,
        start: Int = 0,
        limit: Int = 8000,
        queries: List<String> = emptyList(),
        fts: Boolean = false,
        onEvidence: (String) -> Unit = {}
    ): Page? {
        require(start >= 0 && limit > 0)
        val page = StringBuilder()
        val rolling = StringBuilder()
        var count = 0
        var selectedStart = start
        var matched = queries.isEmpty()
        var pagePoints = 0
        // Enough look-behind for the longest supported query plus a short leading context.
        val lookBehind = 240 + (queries.maxOfOrNull { it.length } ?: 0)
        val patterns = if (fts) queries.mapNotNull { query ->
            val words=Regex("[\\p{L}\\p{N}]+").findAll(fold(query)).map { Regex.escape(it.value) }.toList()
            if (words.isEmpty()) null else Regex(
                "(?:^|[^\\p{L}\\p{N}])("+words.joinToString("[^\\p{L}\\p{N}]+")+")(?=[^\\p{L}\\p{N}]|$)")
        } else emptyList()
        fun selectMatch(atEnd: Boolean = false) {
            if (matched) return
            if (fts && !atEnd && rolling.lastOrNull()?.isLetterOrDigit()==true) return
            val hit = if (fts) {
                if (patterns.any { it.containsMatchIn(fold(rolling.toString())) }) 0 else null
            } else queries.map { rolling.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
            if (hit != null) {
                val hitPoint = rolling.codePointCount(0, hit)
                val from = (hitPoint - 120).coerceAtLeast(0)
                val points = rolling.codePointCount(0, rolling.length)
                selectedStart = count - points + from
                page.append(rolling.substring(rolling.offsetByCodePoints(0, from)))
                pagePoints = points - from
                matched = true
            }
        }
        val filter = EvidenceFilter(assistant, includeThinking) { text ->
            onEvidence(text)
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                val chars = String(Character.toChars(cp))
                if (!matched) {
                    rolling.append(chars)
                    if (rolling.length > lookBehind * 2) {
                        val drop = rolling.offsetByCodePoints(0,
                            rolling.codePointCount(0, rolling.length) - lookBehind)
                        rolling.delete(0, drop)
                    }
                } else if (count >= selectedStart && pagePoints < limit) {
                    page.append(chars)
                    pagePoints++
                }
                count++
                selectMatch()
                index += Character.charCount(cp)
            }
        }
        var offset = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytes = readBytes(offset) ?: return null
            if (bytes.isEmpty()) break
            var length = bytes.size
            // SQLite returns at most 32768 bytes, possibly in the middle of a UTF-8 sequence.
            var head = bytes.lastIndex
            while (head > 0 && (bytes[head].toInt() and 0xc0) == 0x80) head--
            val lead = bytes[head].toInt() and 0xff
            val width = when { lead >= 0xf0 -> 4; lead >= 0xe0 -> 3; lead >= 0xc0 -> 2; else -> 1 }
            if (head + width > bytes.size) length = head
            check(length > 0) { "Incomplete UTF-8 history data" }
            digest.update(bytes, 0, length)
            filter.feed(String(bytes, 0, length, Charsets.UTF_8))
            offset += length
        }
        filter.finish()
        selectMatch(atEnd=true)
        return Page(page.toString(), selectedStart.coerceAtMost(count), count, matched,
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun fold(text: String) = java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").lowercase(java.util.Locale.ROOT)
}

/** Carries tag/quote/nesting state across database chunks; never retains a whole hidden block. */
private class EvidenceFilter(
    private val assistant: Boolean,
    private val includeThinking: Boolean,
    private val emit: (String) -> Unit
) {
    private val tag = StringBuilder()
    private val hidden = mutableListOf<String>()
    private var quote: Char? = null
    private var failed = false

    fun feed(text: String) {
        if (!assistant) { emit(text); return }
        var index = 0
        while (index < text.length && !failed) {
            val cp = text.codePointAt(index)
            val value = String(Character.toChars(cp))
            index += Character.charCount(cp)
            if (tag.isEmpty()) {
                if (cp == '<'.code) tag.append('<')
                else if (hidden.isEmpty()) emit(value)
                continue
            }
            if (cp == '<'.code && quote == null) {
                finishTag(complete = false)
                tag.clear().append('<')
                continue
            }
            tag.append(value)
            val prefix=tag.toString().lowercase().removePrefix("<").removePrefix("/")
            if (prefix.firstOrNull()?.isWhitespace()==true ||
                prefix.none { it.isWhitespace() || it=='>' || it=='/' } &&
                listOf("think","thinking","search","meta").none { it.startsWith(prefix) }) {
                if (hidden.isEmpty()) emit(tag.toString())
                tag.clear()
                quote=null
                continue
            }
            if (quote != null) {
                if (value == quote.toString()) quote = null
            } else if (cp == '"'.code || cp == '\''.code) quote = cp.toChar()
            else if (cp == '>'.code) {
                finishTag(complete = true)
                tag.clear()
            }
            // Protocol tags never need unbounded attributes. Fail visibly rather than leak data.
            check(tag.length <= 16_384) { "History markup tag exceeds safe parsing size" }
        }
    }

    private fun finishTag(complete: Boolean) {
        val raw = tag.toString()
        val match = Regex("^<(/?)(think|thinking|search|meta)(?=[\\s/>]|$)", RegexOption.IGNORE_CASE).find(raw)
        val name = match?.groupValues?.get(2)?.lowercase()
        val closing = match?.groupValues?.get(1) == "/"
        val family = when {
            name == "meta" && (hidden.lastOrNull() == "meta" && closing ||
                Regex("""\bprovider\s*=\s*["'](?:openai:responses_reasoning|gemini:thought_signature|gemini:content)["']""",
                    RegexOption.IGNORE_CASE).containsMatchIn(raw)) -> "meta"
            !includeThinking && name in setOf("think", "thinking") -> "think"
            !includeThinking && name == "search" -> "search"
            else -> null
        }
        if (family == null) {
            if (hidden.isEmpty()) emit(raw)
            return
        }
        val malformed = !complete || (closing && !raw.substring(match!!.value.length).removeSuffix(">").isBlank())
        if (malformed) {
            if (closing && hidden.isEmpty()) emit(raw) else failed = true
        } else if (closing) {
            if (hidden.isEmpty()) emit(raw)
            else if (hidden.last() == family) hidden.removeAt(hidden.lastIndex)
            else failed = true
        } else if (!raw.dropLast(1).trimEnd().endsWith("/")) {
            check(hidden.size < 1024) { "History markup nesting exceeds safe parsing size" }
            hidden.add(family)
        }
    }

    fun finish() {
        if (!failed && tag.isNotEmpty()) finishTag(complete = false)
    }
}
