package com.ai.assistance.operit.util.stream.plugins

import com.ai.assistance.operit.util.markdown.InlineLatexRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineLatexPluginTest {
    /** The state after each character of [text]. */
    private fun states(text: String): List<PluginState> {
        val plugin = StreamMarkdownInlineLaTeXPlugin(includeDelimiters = true)
        return text.map { plugin.processChar(it, false); plugin.state }
    }

    // A price with no closing '$' is math only up to the end of its line; the next line is free.
    @Test fun aLonePriceStopsAtTheEndOfItsLine() {
        val text = "显示 \$99.99/月。也就是说\n下一行"
        val after = states(text)
        assertEquals(PluginState.PROCESSING, after[text.indexOf('9')])
        assertEquals(PluginState.IDLE, after[text.indexOf('\n')])
        assertTrue(after.drop(text.indexOf('\n')).all { it == PluginState.IDLE })
    }

    @Test fun aFormulaStillCloses() {
        val after = states("\$x^2\$ 后")
        assertEquals(PluginState.IDLE, after[4])
    }

    // "$20 and $30": the second '$' follows a space, so it does not close a formula.
    @Test fun aDollarAfterASpaceDoesNotClose() {
        val text = "\$20 and \$30"
        assertEquals(PluginState.PROCESSING, states(text).last())
        assertEquals(PluginState.IDLE, states("\$ 5").last())
    }

    // "\$" is a dollar sign inside the formula; the next '$' closes it.
    @Test fun anEscapedDollarDoesNotClose() {
        val text = "\$\\\$5\$"
        val after = states(text)
        assertEquals(PluginState.PROCESSING, after[2])
        assertEquals(PluginState.IDLE, after.last())
        // An escaped backslash leaves the '$' after it free to close.
        assertEquals(PluginState.IDLE, states("\$a\\\\\$").last())
    }

    @Test fun onlyARealFormulaIsShownAsOne() {
        assertTrue(InlineLatexRule.isFormula("\$x^2\$", ' '))
        assertTrue(InlineLatexRule.isFormula("\$x^2\$", null))
        assertTrue(InlineLatexRule.isFormula("\\(a+b\\)", null))
        assertFalse(InlineLatexRule.isFormula("\$99.99/月。也就是说\n", null))
        assertFalse(InlineLatexRule.isFormula("\$", null))
        // "$20–$30": the closing '$' is followed by a digit.
        assertFalse(InlineLatexRule.isFormula("\$20–\$", '3'))
        // The plugin would not close at a '$' after a space; at the end it must not count either.
        assertFalse(InlineLatexRule.isFormula("\$100 \$", null))
        // A group that ran past a '$' that did not close it.
        assertFalse(InlineLatexRule.isFormula("\$20 和 \$x\$", null))
        assertTrue(InlineLatexRule.isFormula("\$\\\$5\$", null))
        // The closing '$' itself escaped: still open.
        assertFalse(InlineLatexRule.isFormula("\$a\\\$", null))
        assertTrue(InlineLatexRule.isFormula("\$a\\\\\$", null))
    }
}
