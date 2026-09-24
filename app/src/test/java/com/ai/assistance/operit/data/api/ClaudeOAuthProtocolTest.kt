package com.ai.assistance.operit.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeOAuthProtocolTest {
    @Test
    fun authorizationUrlCarriesTheClaudeCodeClientAndPkce() {
        val url =
            ClaudeOAuthProtocol.buildAuthorizationUrl(
                redirectUri = "http://localhost:54545/callback",
                challenge = "challenge-value",
                state = "state-value",
            )
        assertTrue(url.startsWith("${ClaudeOAuthProtocol.AUTHORIZE_ENDPOINT}?"))
        assertTrue(url.contains("client_id=${ClaudeOAuthProtocol.CLIENT_ID}"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code=true"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A54545%2Fcallback"))
        assertTrue(url.contains("code_challenge=challenge-value"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-value"))
        // Scopes travel as one form-encoded value: ':' percent-encoded and spaces as '+', which is
        // what the reference implementation's URLSearchParams produces as well.
        assertTrue(url.contains("scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference"))
    }

    @Test
    fun tokenExchangeBodiesMatchTheReference() {
        val exchange =
            ClaudeOAuthProtocol.authorizationCodeBody(
                code = "auth-code",
                state = "state-value",
                redirectUri = "http://localhost:54545/callback",
                verifier = "verifier-value",
            )
        assertEquals("authorization_code", exchange.getString("grant_type"))
        assertEquals(ClaudeOAuthProtocol.CLIENT_ID, exchange.getString("client_id"))
        assertEquals("auth-code", exchange.getString("code"))
        assertEquals("state-value", exchange.getString("state"))
        assertEquals("http://localhost:54545/callback", exchange.getString("redirect_uri"))
        assertEquals("verifier-value", exchange.getString("code_verifier"))

        // A refresh grant carries no code, redirect or verifier.
        val refresh = ClaudeOAuthProtocol.refreshBody("refresh-value")
        assertEquals("refresh_token", refresh.getString("grant_type"))
        assertEquals(ClaudeOAuthProtocol.CLIENT_ID, refresh.getString("client_id"))
        assertEquals("refresh-value", refresh.getString("refresh_token"))
        assertEquals(3, refresh.length())
    }

    @Test
    fun callbackStateCanRideInsideTheAuthorizationCode() {
        assertEquals(
            "code-value" to "state-value",
            ClaudeOAuthProtocol.splitAuthorizationCode("code-value#state-value", "fallback"),
        )
        assertEquals(
            "code-value" to "fallback",
            ClaudeOAuthProtocol.splitAuthorizationCode("code-value", "fallback"),
        )
        // A trailing '#' without a state keeps the query parameter's value.
        assertEquals(
            "code-value" to "fallback",
            ClaudeOAuthProtocol.splitAuthorizationCode("code-value#", "fallback"),
        )
    }

    @Test
    fun subscriptionToolNamesAreNamespacedBothWays() {
        assertEquals("custom_read_file", ClaudeOAuthProtocol.wireToolName("read_file"))
        assertEquals("read_file", ClaudeOAuthProtocol.modelToolName("custom_read_file"))
        assertEquals(
            "read_file",
            ClaudeOAuthProtocol.modelToolName(ClaudeOAuthProtocol.wireToolName("read_file")),
        )
        // Claude Code's own built-ins are already accepted and stay untouched.
        assertEquals("web_search", ClaudeOAuthProtocol.wireToolName("web_search"))
        assertEquals("web_search", ClaudeOAuthProtocol.modelToolName("web_search"))
        // Namespacing is idempotent (the reference behaves the same way), so a name that already
        // carries the prefix goes out untouched. No Operit tool name starts with `custom_`, so the
        // strip side only ever sees prefixes this side added.
        assertEquals("custom_thing", ClaudeOAuthProtocol.wireToolName("custom_thing"))
        assertEquals("thing", ClaudeOAuthProtocol.modelToolName("custom_thing"))
        assertEquals("", ClaudeOAuthProtocol.modelToolName(""))
    }

    @Test
    fun sessionIdIsStablePerCredentialAndUuidShaped() {
        val first = ClaudeOAuthProtocol.sessionId("token-a")
        assertEquals(first, ClaudeOAuthProtocol.sessionId("token-a"))
        assertNotEquals(first, ClaudeOAuthProtocol.sessionId("token-b"))
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(first)
        )
        // The credential itself must never appear in the derived id.
        assertTrue(!first.contains("token-a"))
    }

    @Test
    fun accountEmailReadsTheNestedAccountObject() {
        assertEquals(
            "user@example.com",
            ClaudeOAuthProtocol.accountEmail(
                JSONObject("""{"account":{"email_address":"user@example.com"}}""")
            ),
        )
        assertEquals("", ClaudeOAuthProtocol.accountEmail(JSONObject("""{"access_token":"x"}""")))
        assertEquals(
            "",
            ClaudeOAuthProtocol.accountEmail(JSONObject("""{"account":{"uuid":"ignored"}}""")),
        )
    }

    @Test
    fun fingerprintHeadersIdentifyTheFirstPartyClient() {
        assertEquals("cli", ClaudeOAuthProtocol.FINGERPRINT_HEADERS["X-App"])
        assertEquals("0.74.0", ClaudeOAuthProtocol.FINGERPRINT_HEADERS["X-Stainless-Package-Version"])
        assertEquals("@anthropic-ai/sdk/0.74.0", ClaudeOAuthProtocol.USER_AGENT)
        assertTrue(
            ClaudeOAuthProtocol.OAUTH_BETA.contains("claude-code-20250219") &&
                ClaudeOAuthProtocol.OAUTH_BETA.contains("oauth-2025-04-20")
        )
        assertTrue(ClaudeOAuthProtocol.OAUTH_USAGE_BETA.startsWith(ClaudeOAuthProtocol.OAUTH_BETA))
    }
}
