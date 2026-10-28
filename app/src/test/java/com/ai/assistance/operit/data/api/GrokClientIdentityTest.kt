package com.ai.assistance.operit.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GrokClientIdentityTest {
    @Test
    fun advertisedVersionOnlyMovesForwardToPlainReleases() {
        assertEquals("1.0.71", GrokClientIdentity.newer("1.0.70", "1.0.71\n"))
        assertEquals("1.1.0", GrokClientIdentity.newer("1.0.70", "1.1.0"))
        assertEquals("1.0.70", GrokClientIdentity.newer("1.0.70", "1.0.9"))
        assertEquals("1.0.70", GrokClientIdentity.newer("1.0.70", "1.0.80-alpha.1"))
        assertEquals("1.0.70", GrokClientIdentity.newer("1.0.70", "<html>"))
        assertEquals("1.0.70", GrokClientIdentity.newer("1.0.70", null))
    }

    @Test
    fun proxyOnlyHeadersStayOffDirectApiCalls() {
        val proxy = GrokClientIdentity.headersFor("1.0.70")
        assertEquals("1.0.70", proxy["x-grok-client-version"])
        assertEquals("grok-pager", proxy["x-grok-client-identifier"])
        assertEquals("xai-grok-cli", proxy["x-xai-token-auth"])
        val direct = GrokClientIdentity.headersFor("1.0.70", viaProxy = false)
        assertFalse(direct.containsKey("x-xai-token-auth"))
        assertFalse(direct.containsKey("x-authenticateresponse"))
    }
}
