package com.ai.assistance.operit.features.orbit

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reading text files of any size, such as a novel the user downloaded: which encoding they use and
 * their text a part at a time, so nothing has to hold the whole file.
 */
internal object OrbitTextFiles {
    private val GB18030: Charset = Charset.forName("GB18030")
    /** The biggest text file opened in the editor; the editor keeps the whole text and a draft of it. */
    const val EDIT_MAX_BYTES = 128 * 1024
    /** Bytes looked at to tell the encoding: all of a file small enough for the editor. */
    private const val SNIFF_BYTES = EDIT_MAX_BYTES

    /** Plain text kinds that can be read and searched, by extension. */
    val TEXT_TYPES = setOf(
        "txt", "text", "md", "markdown", "log", "json", "csv", "tsv", "xml", "yaml", "yml",
        "ini", "cfg", "conf", "properties", "toml", "html", "htm", "sh", "py",
    )

    fun isText(name: String) = name.substringAfterLast('.', "").lowercase() in TEXT_TYPES

    /**
     * The whole of [file] for the editor, read once: null when it is bigger than the editor takes or
     * not UTF-8, so text decoded wrongly can never be saved back over the file.
     */
    fun readEditable(file: File): String? {
        if (file.length() > EDIT_MAX_BYTES) return null
        // One more byte than allowed, so a file that grew since its size was checked is caught too.
        val buffer = ByteArray(EDIT_MAX_BYTES + 1)
        val read = file.inputStream().use { input ->
            var n = 0
            while (n < buffer.size) {
                val r = input.read(buffer, n, buffer.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        if (read > EDIT_MAX_BYTES) return null
        return runCatching { strictUtf8().decode(ByteBuffer.wrap(buffer, 0, read)).toString() }.getOrNull()
    }

    /** Whether [file] can open in the editor: small enough, and UTF-8, so saving keeps what it says. */
    fun editable(file: File): Boolean = readEditable(file) != null

    /** All of [file] as UTF-8, or null when any byte is not UTF-8; the caller checks its size first. */
    fun readStrictUtf8(file: File): String? =
        runCatching { strictUtf8().decode(ByteBuffer.wrap(file.readBytes())).toString() }.getOrNull()

    private fun strictUtf8() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

    /** Strict UTF-8 when the beginning reads as UTF-8; anything else (often GBK from Windows) as GB18030. */
    fun charsetOf(file: File): Charset {
        val head = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { input ->
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        return if (isUtf8(head, read, cut = read == head.size && file.length() > read)) Charsets.UTF_8 else GB18030
    }

    /**
     * Whether [bytes] read as UTF-8. A [cut] piece may end in the middle of one character: only an
     * unfinished last character is allowed then, never a wrong byte.
     */
    fun isUtf8(bytes: ByteArray, length: Int, cut: Boolean): Boolean {
        val decoder = strictUtf8()
        val output = CharBuffer.allocate(length + 1)
        val input = ByteBuffer.wrap(bytes, 0, length)
        if (decoder.decode(input, output, !cut).isError) return false
        if (cut) return length - input.position() < 4
        return !decoder.flush(output).isError
    }

    /** What a file was when reading began; a part read after it changed is refused. */
    data class Stamp(val length: Long, val modified: Long) {
        companion object {
            fun of(file: File) = Stamp(file.length(), file.lastModified())
        }
    }

    /** The file changed while it was being read a part at a time. */
    class Changed : java.io.IOException("The file changed while it was being read")

    /** One part of a file: its text, where the next part starts, and whether there is one. */
    class Part(val text: String, val next: Long, val more: Boolean)

    /**
     * The text from byte [offset], about [maxBytes] of it, ending at a line break when there is one so
     * no character or line is split between parts. A line break byte means the same in UTF-8 and GB18030.
     * With [stamp], a file that is no longer what it was when reading began throws [Changed].
     * [strict] (for searching) throws on bytes that are not this encoding, so nothing is skipped
     * unnoticed; otherwise they show as replacement characters, to read as much as possible.
     */
    fun part(file: File, offset: Long, maxBytes: Int, charset: Charset, stamp: Stamp? = null, strict: Boolean = false): Part =
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (stamp != null && Stamp(size, file.lastModified()) != stamp) throw Changed()
            if (offset >= size) return Part("", size, false)
            raf.seek(offset)
            val buffer = ByteArray(minOf(maxBytes.toLong(), size - offset).toInt())
            raf.readFully(buffer)
            // Checked again after reading, so a write during the read is caught too.
            if (stamp != null && Stamp(raf.length(), file.lastModified()) != stamp) throw Changed()
            val last = offset + buffer.size >= size
            val newline = if (last) -1 else buffer.lastIndexOf('\n'.code.toByte())
            val (text, end) = when {
                last -> decode(buffer, buffer.size, charset, strict) to buffer.size
                newline >= 0 -> decode(buffer, newline + 1, charset, strict) to newline + 1
                else -> decodeWhole(buffer, charset, strict)
            }
            Part(if (offset == 0L) text.removePrefix("\uFEFF") else text, offset + end, !last)
        }

    private fun decoder(charset: Charset, strict: Boolean) = charset.newDecoder().let {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        it.onMalformedInput(action).onUnmappableCharacter(action)
    }

    private fun decode(buffer: ByteArray, length: Int, charset: Charset, strict: Boolean): String =
        decoder(charset, strict).decode(ByteBuffer.wrap(buffer, 0, length)).toString()

    /**
     * A part with no line break: the characters it holds whole, and how many bytes they take. A
     * character the cut splits is left for the next part; the decoder knows where it starts in any encoding.
     */
    private fun decodeWhole(buffer: ByteArray, charset: Charset, strict: Boolean): Pair<String, Int> {
        val decoder = decoder(charset, strict)
        val input = ByteBuffer.wrap(buffer)
        val output = CharBuffer.allocate(buffer.size + 4)
        decoder.decode(input, output, false).let { if (it.isError) it.throwException() }
        output.flip()
        // Never an empty part: a buffer too small for one character is read as it is.
        return if (input.position() == 0) decode(buffer, buffer.size, charset, strict) to buffer.size
        else output.toString() to input.position()
    }
}
