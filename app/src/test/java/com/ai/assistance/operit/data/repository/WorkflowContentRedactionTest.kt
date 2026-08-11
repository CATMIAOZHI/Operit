package com.ai.assistance.operit.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowContentRedactionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }

    @Test
    fun malformedWorkflowException_neverContainsAuthTokenInput() {
        val sentinel = "sentinel-auth-token-must-never-enter-logs"
        val malformed = """{"id":"workflow-1","auth_token":"$sentinel" BROKEN}"""

        val failure = runCatching {
            decodeWorkflowContentSafely(json, malformed, "workflow-1")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure!!.stackTraceToString().contains(sentinel))
        assertTrue(failure.cause == null)
    }
}
