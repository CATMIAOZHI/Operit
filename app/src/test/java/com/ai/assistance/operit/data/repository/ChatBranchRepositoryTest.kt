package com.ai.assistance.operit.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBranchRepositoryTest {
    @Test
    fun `extracts exact persisted tool call ids from copied markup`() {
        val contents =
            listOf(
                """<tool name="task" call_id="call-parent">...</tool>""",
                """<tool_result name="task" call_id='call-variant'>...</tool_result>""",
                """plain text mentioning call-ignored without an attribute""",
            )

        assertEquals(
            setOf("call-parent", "call-variant"),
            extractPersistedToolCallIds(contents),
        )
    }
}
