package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

class BrowserKeyCancellationTest {
    @Test
    fun stoppedMouseReleaseDoesNotWaitAndPreservesTheInterruptFlag() {
        val connection = Mockito.mock(CdpConnection::class.java)
        var released = false
        Mockito.doAnswer { invocation ->
            assertEquals("Input.dispatchMouseEvent", invocation.getArgument<String>(0))
            val params = invocation.getArgument<JSONObject>(1)
            assertEquals("mouseReleased", params.getString("type"))
            assertEquals(0, params.getInt("buttons"))
            assertFalse(Thread.currentThread().isInterrupted)
            released = true
            null
        }.`when`(connection).sendWithoutWaiting(any(), any())

        Thread.currentThread().interrupt()
        try {
            releaseCdpMouseQuietly(connection, 12.0, 34.0, "left", 1)
            org.junit.Assert.assertTrue(released)
            org.junit.Assert.assertTrue(Thread.currentThread().isInterrupted)
            Mockito.verify(connection, Mockito.never()).send(any(), any(), any())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun interruptedKeyDownReleasesEveryAttemptedKeyInReverseOrder() {
        for (interruptAt in 1..3) {
            val connection = Mockito.mock(CdpConnection::class.java)
            val events = mutableListOf<Pair<String, String>>()
            Mockito.doAnswer { invocation ->
                val params = invocation.getArgument<JSONObject>(1)
                events.add(params.getString("type") to params.getString("key"))
                if (events.size == interruptAt) throw InterruptedException("stopped")
                assertFalse(Thread.currentThread().isInterrupted)
                JSONObject()
            }.`when`(connection).send(any(), any(), any())
            Mockito.doAnswer { invocation ->
                val params = invocation.getArgument<JSONObject>(1)
                events.add(params.getString("type") to params.getString("key"))
                assertFalse(Thread.currentThread().isInterrupted)
                null
            }.`when`(connection).sendWithoutWaiting(any(), any())

            assertThrows(InterruptedException::class.java) {
                cdpPressKey(connection, BrowserKeys.parse("Control+Shift+A"))
            }
            val attempted = listOf("Control", "Shift", "A").take(interruptAt)
            assertEquals(attempted.asReversed().map { "keyUp" to it }, events.drop(interruptAt))
        }
    }
}
