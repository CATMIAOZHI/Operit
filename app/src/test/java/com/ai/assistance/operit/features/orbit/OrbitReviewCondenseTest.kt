package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitReviewCondenseTest {
    private val call =
        "<tool_ab12 name=\"binance_positions\"><param name=\"symbol\">ETHUSDT</param></tool_ab12>"
    private val result =
        "<tool_result_ab12 name=\"binance_positions\" status=\"success\"><content>" + "x".repeat(20_000) +
            "</content></tool_result_ab12>"

    @Test fun toolStepsBecomeOneLineAndTheReplyStays() {
        val reply = "<think>check</think>Checking positions.\n$call\n$result\nETH short still open, stop at 1650."
        assertEquals(
            "Checking positions.\n\n[tool call: binance_positions]\n\n" +
                "[tool result: binance_positions, 20000 chars of output folded]\n\nETH short still open, stop at 1650.",
            OrbitReviewReader.condense(reply),
        )
    }

    @Test fun aFailedStepKeepsTheStartOfItsError() {
        val failed = "<tool_result_ab12 name='binance_order' status=\"error\"><content><error>Order rejected:\r\n" +
            "margin is insufficient</error></content></tool_result_ab12>"
        assertEquals(
            "[tool result: binance_order, error: Order rejected: margin is insufficient]\n\nRetrying smaller.",
            OrbitReviewReader.condense("$failed\r\n\r\n\r\nRetrying smaller."),
        )
    }

    @Test fun outputHoldingItsOwnContentTagIsCountedWhole() {
        val inner = "<content>a</content>"
        val read = "<tool_result_ab12 name=\"read_file\" status=\"success\"><content>$inner</content></tool_result_ab12>"
        assertEquals("[tool result: read_file, ${inner.length} chars of output folded]", OrbitReviewReader.condense(read))
    }

    @Test fun aLongErrorKeepsAtMost200Characters() {
        val error = "a".repeat(199) + " b" + " c".repeat(50)
        val failed = "<tool_result_ab12 name=\"t\" status=\"error\"><content><error>$error</error></content></tool_result_ab12>"
        assertEquals("[tool result: t, error: ${"a".repeat(199)}…]", OrbitReviewReader.condense(failed))
        val unbroken = "<tool_result_ab12 name=\"t\" status=\"error\"><content><error>${"x".repeat(250)}</error></content></tool_result_ab12>"
        assertEquals("[tool result: t, error: ${"x".repeat(199)}…]", OrbitReviewReader.condense(unbroken))
    }

    @Test fun aReplyWithoutToolsIsUnchanged() {
        assertEquals("Good night.", OrbitReviewReader.condense("<think>short</think>Good night."))
    }
}
