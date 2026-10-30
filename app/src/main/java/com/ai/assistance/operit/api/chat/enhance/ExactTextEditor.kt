package com.ai.assistance.operit.api.chat.enhance

/**
 * Exact text edits for edit_file / apply_file.
 *
 * Matching follows Codex apply_patch (seek_sequence): there is no similarity score.
 * 1. `old` must appear verbatim (line endings normalized) exactly once.
 * 2. Otherwise `old` is compared as whole lines, in increasingly lenient steps:
 *    ignore trailing whitespace, ignore leading/trailing whitespace, then also treat
 *    typographic dashes/quotes/odd spaces as ASCII. Each step must match exactly one place.
 * Nothing is written unless every edit matches exactly one place.
 * Line endings of untouched lines are preserved; new lines use the file's main line ending.
 */
object ExactTextEditor {

    enum class MatchMode(val description: String) {
        EXACT("exact match"),
        IGNORE_TRAILING_WHITESPACE("whole lines, ignoring trailing whitespace"),
        IGNORE_SURROUNDING_WHITESPACE("whole lines, ignoring leading and trailing whitespace"),
        NORMALIZED_PUNCTUATION("whole lines, ignoring surrounding whitespace and typographic quotes/dashes/spaces")
    }

    /** [newText] empty means delete [oldText]. */
    data class Edit(val oldText: String, val newText: String)

    data class AppliedEdit(
        val startLine: Int,
        val endLine: Int,
        val mode: MatchMode,
        val newLineCount: Int,
        val isDelete: Boolean
    )

    sealed class Result {
        data class Success(val content: String, val applied: List<AppliedEdit>) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val MAX_REPORTED_LINES = 5

    private class Doc(original: String) {
        val lines = ArrayList<String>()
        val eols = ArrayList<String>()
        val defaultEol: String

        init {
            val sb = StringBuilder()
            var crlf = 0
            var lf = 0
            var cr = 0
            var i = 0
            while (i < original.length) {
                val c = original[i]
                when {
                    c == '\r' && i + 1 < original.length && original[i + 1] == '\n' -> {
                        lines.add(sb.toString()); eols.add("\r\n"); sb.setLength(0); crlf++; i += 2
                    }
                    c == '\r' -> {
                        lines.add(sb.toString()); eols.add("\r"); sb.setLength(0); cr++; i++
                    }
                    c == '\n' -> {
                        lines.add(sb.toString()); eols.add("\n"); sb.setLength(0); lf++; i++
                    }
                    else -> {
                        sb.append(c); i++
                    }
                }
            }
            lines.add(sb.toString())
            eols.add("")
            defaultEol = when {
                crlf > lf && crlf >= cr -> "\r\n"
                cr > lf && cr > crlf -> "\r"
                else -> "\n"
            }
        }

        val lfText: String = lines.joinToString("\n")

        val lineStarts: IntArray = IntArray(lines.size).also { starts ->
            var pos = 0
            for (index in lines.indices) {
                starts[index] = pos
                pos += lines[index].length + 1
            }
        }

        fun lineOf(pos: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (lineStarts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    /** Replace lines [startLine]..[endLine] (inclusive, 0-based) with [newLines]. */
    private data class Splice(
        val editIndex: Int,
        val startLine: Int,
        val endLine: Int,
        val newLines: List<String>,
        val reportStart: Int,
        val reportEnd: Int,
        val mode: MatchMode,
        val isDelete: Boolean,
        val newTextLineCount: Int
    )

    private sealed class Match {
        data class Found(val splice: Splice) : Match()
        data class Problem(val message: String) : Match()
    }

    fun apply(original: String, edits: List<Edit>): Result {
        if (edits.isEmpty()) return Result.Failure("No edits provided.")
        val doc = Doc(original)
        val multi = edits.size > 1
        val splices = ArrayList<Splice>(edits.size)
        for ((index, edit) in edits.withIndex()) {
            val prefix = if (multi) "Edit #${index + 1}: " else ""
            when (val match = locate(doc, edit, index)) {
                is Match.Problem -> return Result.Failure(prefix + match.message)
                is Match.Found -> splices.add(match.splice)
            }
        }

        val ordered = splices.sortedBy { it.startLine }
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val current = ordered[i]
            if (current.startLine <= previous.endLine) {
                return Result.Failure(
                    "Edit #${previous.editIndex + 1} and edit #${current.editIndex + 1} touch the same lines " +
                        "(around line ${current.reportStart}). Combine them into one edit."
                )
            }
        }

        val lines = ArrayList(doc.lines)
        val eols = ArrayList(doc.eols)
        for (splice in ordered.asReversed()) {
            val lastEol = eols[splice.endLine]
            for (i in splice.endLine downTo splice.startLine) {
                lines.removeAt(i)
                eols.removeAt(i)
            }
            val count = splice.newLines.size
            lines.addAll(splice.startLine, splice.newLines)
            eols.addAll(
                splice.startLine,
                List(count) { i -> if (i == count - 1) lastEol else doc.defaultEol }
            )
        }

        val out = StringBuilder(original.length + 64)
        for (i in lines.indices) {
            out.append(lines[i]).append(eols[i])
        }
        val applied = splices.map {
            AppliedEdit(it.reportStart, it.reportEnd, it.mode, it.newTextLineCount, it.isDelete)
        }
        return Result.Success(out.toString(), applied)
    }

    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun locate(doc: Doc, edit: Edit, editIndex: Int): Match {
        val old = normalizeNewlines(edit.oldText)
        val new = normalizeNewlines(edit.newText)
        val isDelete = new.isEmpty()
        if (old.isEmpty()) return Match.Problem("`old` is empty.")

        val positions = findAll(doc.lfText, old)
        if (positions.size > 1) {
            return Match.Problem(
                ambiguousMessage(positions.size, positions.map { doc.lineOf(it) + 1 }.distinct())
            )
        }
        if (positions.size == 1) {
            val start = positions[0]
            val end = start + old.length
            return Match.Found(
                spliceForSpan(
                    doc, start, end, new, editIndex, MatchMode.EXACT,
                    reportEnd = doc.lineOf(maxOf(start, end - 1)) + 1
                )
            )
        }

        val oldEndsWithNewline = old.length > 1 && old.endsWith("\n")
        val pattern = old.split("\n").let { if (oldEndsWithNewline) it.dropLast(1) else it }
        if (pattern.all { it.isBlank() }) return Match.Problem(notFoundMessage(doc, pattern))

        val tolerantModes = listOf(
            MatchMode.IGNORE_TRAILING_WHITESPACE to { s: String -> s.trimEnd() },
            MatchMode.IGNORE_SURROUNDING_WHITESPACE to { s: String -> s.trim() },
            MatchMode.NORMALIZED_PUNCTUATION to ::normalizePunctuation
        )
        for ((mode, transform) in tolerantModes) {
            val starts = findLineSequence(doc.lines, pattern, transform)
            if (starts.size > 1) {
                return Match.Problem(ambiguousMessage(starts.size, starts.map { it + 1 }))
            }
            if (starts.size == 1) {
                val startLine = starts[0]
                val endLine = startLine + pattern.size - 1
                // Same span the exact branch would use: whole lines, plus the line break
                // after them when `old` ends with one. `new` is then inserted as given.
                val start = doc.lineStarts[startLine]
                val end = if (oldEndsWithNewline && endLine + 1 < doc.lines.size) {
                    doc.lineStarts[endLine + 1]
                } else {
                    doc.lineStarts[endLine] + doc.lines[endLine].length
                }
                return Match.Found(
                    spliceForSpan(doc, start, end, new, editIndex, mode, reportEnd = endLine + 1)
                )
            }
        }
        return Match.Problem(notFoundMessage(doc, pattern))
    }

    /** Replace LF-text characters [start, end) with [new], expressed as a whole-line splice. */
    private fun spliceForSpan(
        doc: Doc,
        start: Int,
        end: Int,
        new: String,
        editIndex: Int,
        mode: MatchMode,
        reportEnd: Int
    ): Splice {
        val isDelete = new.isEmpty()
        val startLine = doc.lineOf(start)
        val endLine = doc.lineOf(end)
        val startCol = start - doc.lineStarts[startLine]
        val endCol = end - doc.lineStarts[endLine]
        val segment = doc.lines[startLine].substring(0, startCol) + new +
            doc.lines[endLine].substring(endCol)
        // When the match already consumed the line break before endLine, endLine itself is
        // untouched and must stay, even if it is blank. Otherwise an emptied line is removed.
        val consumedLineBreak = endLine > startLine && endCol == 0
        val newLines = if (isDelete && segment.isEmpty() && !consumedLineBreak) emptyList() else segment.split("\n")
        return Splice(
            editIndex = editIndex,
            startLine = startLine,
            endLine = endLine,
            newLines = newLines,
            reportStart = startLine + 1,
            reportEnd = reportEnd,
            mode = mode,
            isDelete = isDelete,
            newTextLineCount = if (isDelete) 0 else new.count { it == '\n' } + if (new.endsWith("\n")) 0 else 1
        )
    }

    private fun findAll(text: String, needle: String): List<Int> {
        val result = ArrayList<Int>(2)
        var index = text.indexOf(needle)
        while (index >= 0) {
            result.add(index)
            if (result.size > MAX_REPORTED_LINES) break
            index = text.indexOf(needle, index + 1)
        }
        return result
    }

    private fun findLineSequence(
        lines: List<String>,
        pattern: List<String>,
        transform: (String) -> String
    ): List<Int> {
        if (pattern.size > lines.size) return emptyList()
        // Map each transformed line to an id, then run KMP over the id arrays: linear time
        // even for long runs of identical lines.
        val ids = HashMap<String, Int>()
        val target = IntArray(pattern.size) { i -> ids.getOrPut(transform(pattern[i])) { ids.size } }
        val text = IntArray(lines.size) { i -> ids[transform(lines[i])] ?: -1 }
        val fail = IntArray(target.size)
        var k = 0
        for (i in 1 until target.size) {
            while (k > 0 && target[i] != target[k]) k = fail[k - 1]
            if (target[i] == target[k]) k++
            fail[i] = k
        }
        val result = ArrayList<Int>(2)
        k = 0
        for (i in text.indices) {
            while (k > 0 && text[i] != target[k]) k = fail[k - 1]
            if (text[i] == target[k]) k++
            if (k == target.size) {
                result.add(i - target.size + 1)
                if (result.size > MAX_REPORTED_LINES) break
                k = fail[k - 1]
            }
        }
        return result
    }

    /** Same character set as Codex seek_sequence `normalise`. */
    private fun normalizePunctuation(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.trim()) {
            sb.append(
                when (c) {
                    '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                    '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                    '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                    '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
                    '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun formatLines(lines: List<Int>): String {
        val shown = lines.take(MAX_REPORTED_LINES).joinToString(", ")
        return if (lines.size > MAX_REPORTED_LINES) "$shown, ..." else shown
    }

    private fun ambiguousMessage(matchCount: Int, lines: List<Int>): String {
        val count = if (matchCount > MAX_REPORTED_LINES) "more than $MAX_REPORTED_LINES" else matchCount.toString()
        return "`old` matches $count places (line ${formatLines(lines)}). Nothing was changed. " +
            "Add a few surrounding lines to `old` so it matches only one place."
    }

    private fun notFoundMessage(doc: Doc, pattern: List<String>): String {
        val base = "`old` was not found in the file. Nothing was changed. `old` must be copied exactly " +
            "from the current file (only whitespace at the ends of whole lines and typographic " +
            "quotes/dashes are tolerated). The file may have changed since you read it: read it again and retry."
        val firstLine = pattern.firstOrNull { it.isNotBlank() }?.let(::normalizePunctuation) ?: return base
        val hits = ArrayList<Int>()
        for (i in doc.lines.indices) {
            if (normalizePunctuation(doc.lines[i]).contains(firstLine)) {
                hits.add(i + 1)
                if (hits.size > MAX_REPORTED_LINES) break
            }
        }
        if (hits.isEmpty()) return base
        return "$base The first line of `old` appears at line ${formatLines(hits)}; read that part again."
    }

    fun describe(applied: List<AppliedEdit>): String {
        return applied.withIndex().joinToString("\n") { (index, edit) ->
            val prefix = if (applied.size > 1) "Edit #${index + 1}: " else ""
            val range = if (edit.startLine == edit.endLine) "line ${edit.startLine}" else "lines ${edit.startLine}-${edit.endLine}"
            val action = if (edit.isDelete) {
                "deleted text at $range"
            } else {
                "replaced $range of the original file with ${edit.newLineCount} line(s)"
            }
            val note = if (edit.mode == MatchMode.EXACT) {
                ""
            } else {
                " (matched as ${edit.mode.description}; the new text was inserted exactly as given, check its indentation)"
            }
            "$prefix$action [${if (edit.mode == MatchMode.EXACT) "exact match" else "lenient match"}]$note."
        }
    }
}
