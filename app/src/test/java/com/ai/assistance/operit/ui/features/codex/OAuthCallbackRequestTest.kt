package com.ai.assistance.operit.ui.features.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthCallbackRequestTest {
    private val redirect = "http://127.0.0.1:51121/callback"

    @Test
    fun googleScopeUrlsDoNotChangeCallbackPath() {
        val query = "state=synthetic&code=test%2Bcode&scope=email%20https://www.googleapis.com/auth/cloud-platform"
        assertEquals("$redirect?$query",
            parseOAuthCallbackRequest("GET /callback?$query HTTP/1.1", redirect))
    }

    @Test
    fun ordinaryAndEncodedQueriesArePreserved() {
        for (query in listOf("state=abc&code=a%2Fb%3Ac+z", "error=access_denied&state=abc")) {
            assertEquals("$redirect?$query",
                parseOAuthCallbackRequest("GET /callback?$query HTTP/1.1", redirect))
        }
    }

    @Test
    fun codexCallbackPathIsStillSupported() {
        val codex = "http://localhost:1455/auth/callback"
        assertEquals("$codex?state=test&code=code",
            parseOAuthCallbackRequest("GET /auth/callback?state=test&code=code HTTP/1.1", codex))
    }

    @Test
    fun unrelatedAndMalformedRequestsAreRejected() {
        for (line in listOf(
            "GET /favicon.ico HTTP/1.1",
            "GET //evil.test/callback HTTP/1.1",
            "GET http://evil.test/callback HTTP/1.1",
            "GET /callback#fragment HTTP/1.1",
            "GET /callback?code=%zz HTTP/1.1",
            "POST /callback HTTP/1.1",
            "GET",
        )) assertNull(line, parseOAuthCallbackRequest(line, redirect))
    }
}
