package com.ai.assistance.operit.util

import org.junit.Assert.*
import org.junit.Test

class SegmentedTextTest {
    @Test fun snapshotsRemainUnchangedAfterAppendAndReplace() {
        val buffer = ChunkedTextBuffer()
        val first = "首轮".repeat(30_000)
        buffer.append(first)
        val snapshot = buffer.snapshot()
        buffer.append("next")
        assertEquals(first, snapshot.toString())
        assertEquals(first + "next", buffer.snapshot().toString())
        buffer.replace("replacement")
        assertEquals(first, snapshot.toString())
        assertEquals("replacement", buffer.snapshot().toString())
    }

    @Test fun indexAndSlicesMatchStringAcrossAllBoundaries() {
        val text = SegmentedText(listOf("", "a", "中文", "", "xyz", "🙂"))
        val expected = "a中文xyz🙂"
        assertEquals(expected.length, text.length)
        expected.indices.forEach { assertEquals(expected[it], text[it]) }
        for (start in 0..expected.length) for (end in start..expected.length) {
            assertEquals(expected.substring(start, end), text.subSequence(start, end).toString())
        }
    }

    @Test fun windowScannerHandlesTagsSplitAcrossPiecesWithoutFlattening() {
        val open = "<" + "think>"
        val close = "</" + "think>"
        val source = " \nhead" + open + "hidden".repeat(20_000) + close + "tail ".repeat(20_000)
        val parts = SegmentedText(source.chunked(7))
        val noFlatten = object : CharSequence by parts {
            override fun toString(): String = error("window scanner must not flatten input")
        }
        for (size in listOf(0, 1, 30, 2000)) {
            val expected = ChatUtils.removeThinkingContentWindow(source, size)
            val actual = ChatUtils.removeThinkingContentWindow(noFlatten, size)
            assertEquals(expected.length, actual.length)
            assertEquals(expected.headWindow, actual.headWindow)
            assertEquals(expected.tailWindow, actual.tailWindow)
        }
    }
}
