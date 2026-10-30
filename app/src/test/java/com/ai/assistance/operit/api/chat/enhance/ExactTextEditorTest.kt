package com.ai.assistance.operit.api.chat.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactTextEditorTest {

    private fun apply(original: String, vararg edits: Pair<String, String>): ExactTextEditor.Result =
        ExactTextEditor.apply(original, edits.map { ExactTextEditor.Edit(it.first, it.second) })

    private fun success(result: ExactTextEditor.Result): ExactTextEditor.Result.Success {
        assertTrue("expected success but got $result", result is ExactTextEditor.Result.Success)
        return result as ExactTextEditor.Result.Success
    }

    private fun failure(result: ExactTextEditor.Result): String {
        assertTrue("expected failure but got $result", result is ExactTextEditor.Result.Failure)
        return (result as ExactTextEditor.Result.Failure).message
    }

    @Test
    fun exactFragmentInsideLine() {
        val result = success(apply("val a = 1\nval b = 2\n", "b = 2" to "b = 3"))
        assertEquals("val a = 1\nval b = 3\n", result.content)
        assertEquals(2, result.applied.single().startLine)
        assertEquals(ExactTextEditor.MatchMode.EXACT, result.applied.single().mode)
    }

    @Test
    fun similarButDifferentTextIsRejected() {
        val original = "fun load(id: Int) {\n    fetch(id, cache = true)\n}\n"
        val message = failure(apply(original, "fun load(id: Int) {\n    fetch(id, cache = false)\n}" to "x"))
        assertTrue(message.contains("not found"))
        assertTrue(message.contains("line 1"))
    }

    @Test
    fun multipleMatchesAreRejectedWithLineNumbers() {
        val message = failure(apply("x()\ny()\nx()\n", "x()" to "z()"))
        assertTrue(message.contains("2 places"))
        assertTrue(message.contains("1, 3"))
    }

    @Test
    fun trailingWhitespaceDifferenceMatchesWholeLines() {
        val result = success(apply("a  \nb\nc\n", "a\nb" to "A\nB"))
        assertEquals("A\nB\nc\n", result.content)
        assertEquals(ExactTextEditor.MatchMode.IGNORE_TRAILING_WHITESPACE, result.applied.single().mode)
    }

    @Test
    fun indentationDifferenceMatchesAndKeepsNewTextAsGiven() {
        val result = success(apply("if (x) {\n\tcall()\n}\n", "    call()" to "    other()"))
        assertEquals("if (x) {\n    other()\n}\n", result.content)
        assertEquals(ExactTextEditor.MatchMode.IGNORE_SURROUNDING_WHITESPACE, result.applied.single().mode)
    }

    @Test
    fun typographicPunctuationMatches() {
        val result = success(apply("say \u201Chi\u201D \u2014 ok\nnext\n", "say \"hi\" - ok" to "say hello"))
        assertEquals("say hello\nnext\n", result.content)
        assertEquals(ExactTextEditor.MatchMode.NORMALIZED_PUNCTUATION, result.applied.single().mode)
    }

    @Test
    fun crlfIsPreserved() {
        val result = success(apply("one\r\ntwo\r\nthree\r\n", "two\nthree" to "2\n3\n3.5"))
        assertEquals("one\r\n2\r\n3\r\n3.5\r\n", result.content)
    }

    @Test
    fun mixedLineEndingsKeepUntouchedLines() {
        val result = success(apply("a\r\nb\nc\r\n", "b" to "B"))
        assertEquals("a\r\nB\nc\r\n", result.content)
    }

    @Test
    fun deleteWholeLineRemovesIt() {
        val result = success(apply("keep\ndrop me\nkeep2\n", "drop me" to ""))
        assertEquals("keep\nkeep2\n", result.content)
        assertTrue(result.applied.single().isDelete)
    }

    @Test
    fun deleteFragmentKeepsRestOfLine() {
        val result = success(apply("call(a, b)\n", ", b" to ""))
        assertEquals("call(a)\n", result.content)
    }

    @Test
    fun deleteWithTrailingNewline() {
        val result = success(apply("a\nb\nc", "b\n" to ""))
        assertEquals("a\nc", result.content)
    }

    @Test
    fun chineseAndUnicodeText() {
        val result = success(apply("# 标题\n喜欢猫\uD83D\uDC31\n", "喜欢猫\uD83D\uDC31" to "喜欢狗\uD83D\uDC36"))
        assertEquals("# 标题\n喜欢狗\uD83D\uDC36\n", result.content)
    }

    @Test
    fun multipleEditsApplyTogetherOrNotAtAll() {
        val original = "a\nb\nc\nd\n"
        val ok = success(apply(original, "a" to "A", "d" to "D"))
        assertEquals("A\nb\nc\nD\n", ok.content)
        val message = failure(apply(original, "a" to "A", "zzz" to "Z"))
        assertTrue(message.startsWith("Edit #2"))
    }

    @Test
    fun overlappingEditsAreRejected() {
        val message = failure(apply("one two\n", "one" to "1", "two" to "2"))
        assertTrue(message.contains("same lines"))
    }

    @Test
    fun noTrailingNewlineIsKept() {
        val result = success(apply("a\nb", "b" to "c"))
        assertEquals("a\nc", result.content)
    }

    @Test
    fun lenientMatchKeepsExplicitTrailingNewlineOfNew() {
        assertEquals("B\n", success(apply("a\t", "a " to "B\n")).content)
        assertEquals("A\nB\n", success(apply("a  \nb", "a\nb" to "A\nB\n")).content)
        assertEquals("A\nB\nc\n", success(apply("a  \nb\nc\n", "a\nb\n" to "A\nB\n")).content)
    }

    @Test
    fun lenientDeleteWithTrailingNewlineRemovesLines() {
        assertEquals("c\n", success(apply("a  \nb\nc\n", "a\nb\n" to "")).content)
    }

    @Test
    fun deleteKeepsBlankLineAfterMatch() {
        assertEquals("\nc", success(apply("a\n\nc", "a\n" to "")).content)
        assertEquals("\nc", success(apply("a\t\n\nc", "a \n" to "")).content)
        assertEquals("", success(apply("a\n", "a\n" to "")).content)
    }

    @Test
    fun sameLineMultipleMatchesReportsRealCount() {
        val message = failure(apply("foo foo\n", "foo" to "bar"))
        assertTrue(message, message.contains("matches 2 places (line 1)"))
    }

    @Test
    fun summaryCountsOnlyNewTextLines() {
        val result = success(apply("a\nb\n", "a\n" to "A\n"))
        assertEquals("A\nb\n", result.content)
        assertEquals(1, result.applied.single().newLineCount)
    }

    @Test
    fun longRunsOfIdenticalLinesStayLinear() {
        val lines = List(50_000) { "    same" } + "    unique"
        val original = lines.joinToString("\n")
        val old = (List(10_000) { "same" } + "unique").joinToString("\n")
        val started = System.nanoTime()
        val result = success(apply(original, old to "done"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
        assertEquals(40_001, result.applied.single().startLine)
        assertTrue(result.content.endsWith("    same\ndone"))
    }

    @Test
    fun invalidOperationFailsWholeBatch() {
        val service = FileBindingService(org.mockito.kotlin.mock())
        val result = service.applyStructuredEdits(
            "a\nb\n",
            listOf(
                FileBindingService.StructuredEditOperation(FileBindingService.StructuredEditAction.REPLACE, "a", "A"),
                FileBindingService.StructuredEditOperation(FileBindingService.StructuredEditAction.REPLACE, "b", "")
            )
        )
        assertTrue(result.error!!.startsWith("Edit #2"))
        assertEquals("a\nb\n", result.content)
    }
}
