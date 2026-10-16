package com.ai.assistance.operit.features.orbit

import java.io.OutputStream

/**
 * Turns a Markdown note Orbit wrote into a web page, a Word file or a PDF, keeping headings,
 * lists, quotes, code, rules and bold or italic text. Plain enough to share; not a full Markdown
 * engine (tables and images come through as text).
 */
object OrbitExport {
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Item(val text: String, val number: Int?) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
    }

    /** A piece of inline text with its style. */
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

    val FORMATS = setOf("html", "docx", "pdf")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    blocks += Block.Code(code.toString().trimEnd('\n'))
                }
                trimmed.isEmpty() -> flush()
                // Tables keep their rows as fixed-width text; the |---| line under the header is dropped.
                trimmed.startsWith("|") -> {
                    flush()
                    val rows = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        if (!Regex("^\\|[\\s:|-]+\\|?$").matches(row)) rows += row
                        i++
                    }
                    i--
                    blocks += Block.Code(table(rows))
                }
                Regex("^#{1,6} ").containsMatchIn(trimmed) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, trimmed.drop(level).trim())
                }
                Regex("^([-*_])( ?\\1){2,}$").matches(trimmed) -> { flush(); blocks += Block.Rule }
                Regex("^[-*+] ").containsMatchIn(trimmed) -> {
                    flush()
                    blocks += Block.Item(trimmed.drop(2).trim().removePrefix("[ ] ").removePrefix("[x] "), null)
                }
                Regex("^\\d{1,3}[.)] ").containsMatchIn(trimmed) -> {
                    flush()
                    blocks += Block.Item(trimmed.substringAfter(' ').trim(), trimmed.takeWhile(Char::isDigit).toInt())
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        val part = lines[i].trim().removePrefix(">").trim()
                        if (part.isNotEmpty()) {
                            quote.append(if (quote.isEmpty() || (isCjk(quote.last()) && isCjk(part.first()))) part else " $part")
                        }
                        i++
                    }
                    i--
                    blocks += Block.Quote(quote.toString())
                }
                // Lines of one paragraph join with a space, except between Chinese or Japanese characters.
                else -> paragraph.append(
                    if (paragraph.isEmpty() || (isCjk(paragraph.last()) && isCjk(trimmed.first()))) trimmed else " $trimmed"
                )
            }
            i++
        }
        flush()
        return blocks
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
    } || c in "，。！？；：、（）「」《》"

    /** Lines a table up in columns; wide characters count as two. */
    private fun table(rows: List<String>): String {
        val cells = rows.map { row -> row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
        fun width(s: String) = s.sumOf { if (isCjk(it) || it.code > 0x2E80) 2L else 1L }.toInt()
        val columns = cells.maxOfOrNull { it.size } ?: 0
        val widths = (0 until columns).map { c -> cells.maxOf { row -> row.getOrNull(c)?.let(::width) ?: 0 } }
        return cells.joinToString("\n") { row ->
            (0 until columns).joinToString(" | ") { c ->
                val text = row.getOrNull(c).orEmpty()
                text + " ".repeat(widths[c] - width(text))
            }.trimEnd()
        }
    }

    private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)""")

    fun inline(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        var at = 0
        INLINE.findAll(text).forEach { m ->
            if (m.range.first > at) runs += Run(text.substring(at, m.range.first))
            val g = m.groupValues
            runs += when {
                g[1].isNotEmpty() -> Run(g[1], bold = true)
                g[2].isNotEmpty() -> Run(g[2], bold = true)
                g[3].isNotEmpty() -> Run(g[3], italic = true)
                g[4].isNotEmpty() -> Run(g[4], italic = true)
                g[5].isNotEmpty() -> Run(g[5], code = true)
                else -> Run(g[6], link = g[7])
            }
            at = m.range.last + 1
        }
        if (at < text.length) runs += Run(text.substring(at))
        return runs
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun htmlInline(text: String) = inline(text).joinToString("") { run ->
        val body = escape(run.text)
        when {
            run.link != null && Regex("^(https?|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(run.link) ->
                "<a href=\"${escape(run.link)}\">$body</a>"
            run.code -> "<code>$body</code>"
            run.bold -> "<strong>$body</strong>"
            run.italic -> "<em>$body</em>"
            else -> body
        }
    }

    fun toHtml(title: String, markdown: String): String {
        val body = StringBuilder()
        var list: String? = null
        fun closeList() { list?.let { body.append("</$it>\n") }; list = null }
        parse(markdown).forEach { block ->
            if (block !is Block.Item) closeList()
            when (block) {
                is Block.Heading -> body.append("<h${block.level}>${htmlInline(block.text)}</h${block.level}>\n")
                is Block.Paragraph -> body.append("<p>${htmlInline(block.text)}</p>\n")
                is Block.Item -> {
                    val tag = if (block.number != null) "ol" else "ul"
                    if (list != tag) { closeList(); body.append("<$tag>\n"); list = tag }
                    body.append("<li>${htmlInline(block.text)}</li>\n")
                }
                is Block.Quote -> body.append("<blockquote>${htmlInline(block.text)}</blockquote>\n")
                is Block.Code -> body.append("<pre><code>${escape(block.text)}</code></pre>\n")
                Block.Rule -> body.append("<hr>\n")
            }
        }
        closeList()
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
body{font-family:-apple-system,"Segoe UI",Roboto,"Noto Sans CJK SC","PingFang SC",sans-serif;max-width:760px;margin:40px auto;padding:0 20px;line-height:1.7;color:#1d1d1f}
h1,h2,h3{line-height:1.3}h1{font-size:2em}blockquote{margin:0;padding:4px 16px;border-left:3px solid #d2d2d7;color:#555}
code{background:#f2f2f4;padding:2px 5px;border-radius:5px}pre{background:#f5f5f7;padding:14px;border-radius:10px;overflow:auto}pre code{background:none;padding:0}
hr{border:0;border-top:1px solid #e5e5ea;margin:28px 0}a{color:#0a66d8}
@media (prefers-color-scheme:dark){body{background:#111;color:#eee}code,pre{background:#222}blockquote{color:#aaa}}
</style></head><body>
$body</body></html>
"""
    }

    fun toDocx(markdown: String, out: OutputStream) {
        val document = org.apache.poi.xwpf.usermodel.XWPFDocument()
        document.use { doc ->
            parse(markdown).forEach { block ->
                val p = doc.createParagraph()
                fun runs(text: String, size: Int, forceBold: Boolean = false, italic: Boolean = false) {
                    inline(text).forEach { r ->
                        val run = p.createRun()
                        run.setText(r.text)
                        run.fontSize = size
                        run.isBold = forceBold || r.bold
                        run.isItalic = italic || r.italic
                        if (r.code) run.fontFamily = "Consolas"
                    }
                }
                when (block) {
                    is Block.Heading -> { p.spacingBefore = 240; runs(block.text, (26 - block.level * 3).coerceAtLeast(12), forceBold = true) }
                    is Block.Paragraph -> runs(block.text, 11)
                    is Block.Item -> { p.indentationLeft = 360; runs((block.number?.let { "$it. " } ?: "• ") + block.text, 11) }
                    is Block.Quote -> { p.indentationLeft = 360; runs(block.text, 11, italic = true) }
                    is Block.Code -> block.text.split('\n').forEachIndexed { index, line ->
                        val run = p.createRun()
                        if (index > 0) run.addBreak()
                        run.setText(line)
                        run.fontFamily = "Consolas"
                        run.fontSize = 10
                    }
                    Block.Rule -> p.createRun().setText("———")
                }
            }
            doc.write(out)
        }
    }

    /** A4 PDF drawn with the system fonts, so Chinese and other scripts show correctly. */
    fun toPdf(markdown: String, out: OutputStream) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 56
        val width = pageWidth - margin * 2
        val pdf = android.graphics.pdf.PdfDocument()
        var pageNumber = 0
        var page: android.graphics.pdf.PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let(pdf::finishPage)
            pageNumber++
            page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin.toFloat()
        }
        fun styled(text: String): CharSequence {
            val builder = android.text.SpannableStringBuilder()
            inline(text).forEach { r ->
                val start = builder.length
                builder.append(r.text)
                val style = when { r.bold && r.italic -> android.graphics.Typeface.BOLD_ITALIC; r.bold -> android.graphics.Typeface.BOLD
                    r.italic -> android.graphics.Typeface.ITALIC; else -> null }
                style?.let { builder.setSpan(android.text.style.StyleSpan(it), start, builder.length, 0) }
                if (r.code) builder.setSpan(android.text.style.TypefaceSpan("monospace"), start, builder.length, 0)
            }
            return builder
        }
        fun draw(text: CharSequence, size: Float, bold: Boolean = false, indent: Int = 0, gapBefore: Float = 0f,
                 color: Int = android.graphics.Color.rgb(29, 29, 31), mono: Boolean = false) {
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = when { mono -> android.graphics.Typeface.MONOSPACE; bold -> android.graphics.Typeface.DEFAULT_BOLD
                    else -> android.graphics.Typeface.DEFAULT }
            }
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent)
                .setLineSpacing(size * 0.35f, 1f).build()
            y += gapBefore
            var line = 0
            while (line < layout.lineCount) {
                if (page == null || y + (layout.getLineBottom(line) - layout.getLineTop(line)) > pageHeight - margin) newPage()
                // Draw as many whole lines as fit on this page.
                val first = line
                var last = line
                while (last + 1 < layout.lineCount &&
                    y + layout.getLineBottom(last + 1) - layout.getLineTop(first) <= pageHeight - margin) last++
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate((margin + indent).toFloat(), y - layout.getLineTop(first))
                canvas.clipRect(0f, layout.getLineTop(first).toFloat(), (width - indent).toFloat(), layout.getLineBottom(last).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += (layout.getLineBottom(last) - layout.getLineTop(first)).toFloat()
                line = last + 1
            }
        }
        newPage()
        parse(markdown).forEach { block ->
            when (block) {
                is Block.Heading -> draw(styled(block.text), (24f - block.level * 2.5f).coerceAtLeast(12f), bold = true, gapBefore = 14f)
                is Block.Paragraph -> draw(styled(block.text), 11.5f, gapBefore = 8f)
                is Block.Item -> draw(styled((block.number?.let { "$it. " } ?: "•  ") + block.text), 11.5f, indent = 16, gapBefore = 4f)
                is Block.Quote -> draw(styled(block.text), 11.5f, indent = 16, gapBefore = 8f, color = android.graphics.Color.rgb(90, 90, 95))
                is Block.Code -> draw(block.text, 10f, indent = 8, gapBefore = 8f, mono = true)
                Block.Rule -> draw("—".repeat(20), 10f, gapBefore = 10f, color = android.graphics.Color.rgb(200, 200, 205))
            }
        }
        page?.let(pdf::finishPage)
        pdf.writeTo(out)
        pdf.close()
    }
}
