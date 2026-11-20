package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class OrbitTextFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val gbk = Charset.forName("GB18030")

    /** Reads [file] part by part, joined back together. */
    private fun readAll(file: java.io.File, partBytes: Int): String {
        val charset = OrbitTextFiles.charsetOf(file)
        val out = StringBuilder()
        var offset = 0L
        do {
            val part = OrbitTextFiles.part(file, offset, partBytes, charset)
            out.append(part.text)
            offset = part.next
        } while (part.more)
        return out.toString()
    }

    @Test fun aGbkNovelReadsWholeInPartsEvenWithoutLineBreaks() {
        val text = "三体：地球往事。\uD83D\uDE00".repeat(5000)
        val file = folder.newFile("novel.txt").apply { writeBytes(text.toByteArray(gbk)) }
        assertEquals(gbk, OrbitTextFiles.charsetOf(file))
        assertFalse(OrbitTextFiles.editable(file))
        assertEquals(text, readAll(file, 1001))
    }

    @Test fun utf8TextReadsWholeInPartsWithAndWithoutLineBreaks() {
        val lines = (1..3000).joinToString("\n") { "第 $it 行 \uD83D\uDE00 line" }
        val oneLine = "长\uD83D\uDE00".repeat(20000)
        for ((name, text) in listOf("a.md" to lines, "b.md" to oneLine)) {
            val file = folder.newFile(name).apply { writeText(text) }
            assertEquals(Charsets.UTF_8, OrbitTextFiles.charsetOf(file))
            assertEquals(text, readAll(file, 997))
        }
    }

    @Test fun onlySmallUtf8FilesOpenInTheEditor() {
        assertTrue(OrbitTextFiles.editable(folder.newFile("small.md").apply { writeText("# 笔记\n内容") }))
        assertFalse(OrbitTextFiles.editable(folder.newFile("big.md").apply { writeText("x".repeat(OrbitTextFiles.EDIT_MAX_BYTES.toInt() + 1)) }))
        assertFalse(OrbitTextFiles.editable(folder.newFile("gbk.txt").apply { writeBytes("中文内容".toByteArray(gbk)) }))
    }

    @Test fun aCutSampleMayEndInsideACharacterButNotOnAWrongByte() {
        val text = "中文".toByteArray()
        assertTrue(OrbitTextFiles.isUtf8(text, text.size - 1, cut = true))
        val wrong = "abc".toByteArray() + byteArrayOf(0xFF.toByte())
        assertFalse(OrbitTextFiles.isUtf8(wrong, wrong.size, cut = true))
    }

    @Test fun aFileThatChangesWhileReadIsRefused() {
        val file = folder.newFile("log.txt").apply { writeText("a\n".repeat(1000)) }
        val stamp = OrbitTextFiles.Stamp.of(file)
        val first = OrbitTextFiles.part(file, 0, 100, Charsets.UTF_8, stamp)
        file.appendText("more\n")
        assertTrue(runCatching { OrbitTextFiles.part(file, first.next, 100, Charsets.UTF_8, stamp) }
            .exceptionOrNull() is OrbitTextFiles.Changed)
    }
}
