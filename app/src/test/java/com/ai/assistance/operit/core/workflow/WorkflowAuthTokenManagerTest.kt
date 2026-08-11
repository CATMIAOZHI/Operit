package com.ai.assistance.operit.core.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAuthTokenManagerTest {
    @Test
    fun codec_acceptsOnlyTokensSignedForCurrentInstallation() {
        val codec = WorkflowAuthTokenCodec(ByteArray(32) { 7 }) { size ->
            ByteArray(size) { index -> index.toByte() }
        }
        val otherInstallation = WorkflowAuthTokenCodec(ByteArray(32) { 8 })
        val token = codec.newToken()
        val replacement = if (token.last() == 'A') 'B' else 'A'
        val tampered = token.dropLast(1) + replacement

        assertTrue(WorkflowIntentSecurity.isValidAuthToken(token))
        assertTrue(codec.isAuthentic(token))
        assertFalse(codec.isAuthentic(tampered))
        assertFalse(otherInstallation.isAuthentic(token))
    }
}
