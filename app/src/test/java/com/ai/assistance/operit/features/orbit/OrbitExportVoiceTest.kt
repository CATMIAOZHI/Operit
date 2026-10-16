package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OrbitExportVoiceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun markdownBlocksAreRecognised() {
        val blocks = OrbitExport.parse("# Title\n\nline one\nline two\n\n- a\n2. b\n> said\n---\n```\nx < y\n```")
        assertEquals(
            listOf(
                OrbitExport.Block.Heading(1, "Title"),
                OrbitExport.Block.Paragraph("line one line two"),
                OrbitExport.Block.Item("a", null),
                OrbitExport.Block.Item("b", 2),
                OrbitExport.Block.Quote("said"),
                OrbitExport.Block.Rule,
                OrbitExport.Block.Code("x < y"),
            ),
            blocks,
        )
    }

    @Test fun inlineStylesAndUnsafeLinks() {
        val runs = OrbitExport.inline("a **b** *c* `d` [e](https://x.y)")
        assertTrue(runs.any { it.text == "b" && it.bold })
        assertTrue(runs.any { it.text == "c" && it.italic })
        assertTrue(runs.any { it.text == "d" && it.code })
        assertTrue(runs.any { it.text == "e" && it.link == "https://x.y" })
        val html = OrbitExport.toHtml("<t>", "[x](javascript:alert(1)) <b>")
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("<b>"))
        assertTrue(html.contains("&lt;t&gt;"))
    }

    @Test fun tablesQuotesAndChineseLines() {
        val blocks = OrbitExport.parse("| 项目 | 金额 |\n| --- | ---: |\n| 午饭 | 30 |\n\n> 第一行\n> 第二行\n\n中文第一行\n中文第二行")
        assertEquals(OrbitExport.Block.Code("项目 | 金额\n午饭 | 30"), blocks[0])
        assertEquals(OrbitExport.Block.Quote("第一行第二行"), blocks[1])
        assertEquals(OrbitExport.Block.Paragraph("中文第一行中文第二行"), blocks[2])
    }

    @Test fun longTextIsCutAtSentenceEnds() {
        val text = "这是一句话。".repeat(100)
        val parts = OrbitVoice.chunks(text, 200)
        assertTrue(parts.all { it.length <= 200 })
        assertTrue(parts.all { it.endsWith("。") })
        assertEquals(text, parts.joinToString(""))
    }

    private fun wav(name: String, rate: Int, channels: Int, samples: ShortArray): File {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(samples.size * 2)
        return folder.newFile(name).apply { writeBytes(b.array() + data.array()) }
    }

    @Test fun piecesWithDifferentRatesAreJoined() {
        val a = wav("a.wav", 16000, 1, ShortArray(16000) { 1000 })
        val b = wav("b.wav", 8000, 2, ShortArray(16000) { 2000 })
        val out = File(folder.root, "out.wav")
        OrbitVoice.joinWavs(listOf(a, b), out, 500)
        val bytes = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bytes.getInt(24))
        assertEquals(1, bytes.getShort(22).toInt())
        // 1 s + 0.5 s pause + 1 s (8000 stereo frames resampled to 16000 mono frames).
        assertEquals((16000 + 8000 + 16000) * 2, bytes.getInt(40))
        assertEquals(2000, bytes.getShort(44 + (16000 + 8000 + 100) * 2).toInt())
    }

    @Test fun audioOverTheLimitIsRefusedBeforeWriting() {
        val a = wav("big.wav", 16000, 1, ShortArray(16000))
        val out = File(folder.root, "big-out.wav")
        val error = runCatching { OrbitVoice.joinWavs(listOf(a, a), out, 0, maxBytes = 40_000) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("split"))
        assertFalse(out.exists())
    }
}
