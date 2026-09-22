package com.ai.assistance.operit.util

import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowableTextFormatterTest {
    @Test fun preservesSuppressedCoroutineDiagnosticsAndCause() {
        val failure = IOException("response failed", IOException("event too large"))
        // DiagnosticCoroutineContextException exposes its context only via localizedMessage.
        failure.addSuppressed(object : RuntimeException() {
            override fun getLocalizedMessage() = "[CoroutineName(ChatStreamPersistence)]"
        })
        val report = ThrowableTextFormatter.format(failure)
        assertTrue(report.contains("Suppressed: "))
        assertTrue(report.contains("CoroutineName(ChatStreamPersistence)"))
        assertTrue(report.contains("Caused by: java.io.IOException: event too large"))
    }

    @Test fun boundsCircularAndOversizedSuppressedExceptions() {
        val failure = IOException("root").apply { stackTrace = emptyArray() }
        val child = IOException("child").apply { stackTrace = emptyArray() }
        failure.addSuppressed(child)
        child.addSuppressed(failure)
        assertTrue(ThrowableTextFormatter.format(failure).contains("circular exception omitted"))
        failure.addSuppressed(IOException("x".repeat(10_000)))
        val report = ThrowableTextFormatter.format(failure, 512)
        assertTrue(report.length <= 512)
        assertTrue(report.contains("[truncated]"))
    }
}
