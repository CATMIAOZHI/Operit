package com.ai.assistance.operit.features.orbit

import okhttp3.HttpUrl.Companion.toHttpUrl
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitOAuthProtocolTest {
    private fun request() = OrbitVault.Request("orbit", "chat", OrbitVault.OAUTH, "Example", "", "",
        listOf("api.example.com"), "bearer", requestedAt = 123,
        authorizationUrl = "https://auth.example.com/authorize?access_type=offline&state=stale&response_type=token",
        tokenUrl = "https://auth.example.com/token", clientId = "public-app", scope = "calendar.read")

    @Test fun pkceMatchesRfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OrbitOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(OrbitOAuthProtocol.random().matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test fun authorizationUsesOurStateCodeFlowAndCallbackWhileKeepingProviderOptions() {
        val url = OrbitOAuthProtocol.authorizationUrl(request(), "app.oauth://callback/oauth2", "new-state", "verifier").toHttpUrl()
        assertEquals(listOf("new-state"), url.queryParameterValues("state"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("app.oauth://callback/oauth2", url.queryParameter("redirect_uri"))
        assertNull(url.queryParameter("code_verifier"))
    }

    @Test fun rejectsSecretAndInsecureEndpoints() {
        for (url in listOf("http://auth.example.com/token", "https://user:pass@auth.example.com/token",
            "https://auth.example.com/token#fragment", "https://auth.example.com/token?client_secret=invalid")) {
            assertTrue(runCatching { OrbitOAuthProtocol.endpoint(url) }.isFailure)
        }
    }

    @Test fun refusesUnboundTokenEndpointsBeforeCreatingOrRestoringACard() {
        for (token in listOf(
            "https://attacker.example/token",
            "https://auth.example.com.attacker.example/token",
            "https://sub.auth.example.com/token",
            "https://auth.example.com:8443/token",
            "http://auth.example.com/token",
        )) {
            val invalid = request().copy(tokenUrl = token)
            assertTrue(runCatching { OrbitOAuthProtocol.validate(invalid) }.isFailure)
            assertNull(OrbitChatCards.vaultRequest(OrbitChatCards.vault(invalid)))
        }
        assertTrue(runCatching { OrbitOAuthProtocol.validateEndpoints("", request().tokenUrl) }.isFailure)
    }

    @Test fun canonicalOriginsAcceptDefaultPortAndNeverExposeQueryParameters() {
        OrbitOAuthProtocol.validate(request().copy(tokenUrl = "https://AUTH.example.com:443/token"))
        assertEquals("https://auth.example.com", OrbitOAuthProtocol.origin(request().authorizationUrl))
        assertEquals("https://auth.example.com:8443",
            OrbitOAuthProtocol.origin("https://auth.example.com:8443/token?tenant=example"))
    }

    @Test fun rotatingRefreshTokenAndExpiryAreParsedWithoutAssumingExpiryWhenAbsent() {
        val tokens = OrbitOAuthProtocol.tokens(JSONObject()
            .put("access_token", "example-access").put("refresh_token", "example-refresh")
            .put("token_type", "Bearer").put("expires_in", 3600), now = 1_000)
        assertEquals("example-refresh", tokens.refreshToken)
        assertEquals(3_601_000L, tokens.expiresAt)
        assertFalse(OrbitOAuthProtocol.needsRefresh(tokens.expiresAt, now = 1_000))
        assertTrue(OrbitOAuthProtocol.needsRefresh(tokens.expiresAt, now = 3_600_000))
        val noExpiry = OrbitOAuthProtocol.tokens(JSONObject()
            .put("access_token", "example-access").put("token_type", "bearer"), now = 1_000)
        assertEquals(0L, noExpiry.expiresAt)
        assertNull(noExpiry.refreshToken)
        assertFalse(OrbitOAuthProtocol.needsRefresh(noExpiry.expiresAt))
    }

    @Test fun rejectsProviderErrorsAndNonBearerTokensWithoutRepeatingTheirValues() {
        val rejected = listOf(
            JSONObject().put("error", "example-secret-in-error"),
            JSONObject().put("access_token", "example-secret").put("token_type", "mac"),
            JSONObject().put("access_token", "invalid\nheader").put("token_type", "Bearer"),
            JSONObject().put("access_token", JSONObject.NULL).put("token_type", "Bearer"),
            JSONObject().put("access_token", "example-secret").put("token_type", "Bearer").put("refresh_token", JSONObject.NULL),
            JSONObject().put("access_token", "example-secret").put("token_type", "Bearer").put("expires_in", -1),
        )
        rejected.forEach {
            val error = runCatching { OrbitOAuthProtocol.tokens(it) }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error?.message.orEmpty().contains("example-secret"))
        }
    }

    @Test fun oauthCardRoundTripsPublicConfigurationAndHasItsOwnSummary() {
        val original = request()
        val card = OrbitChatCards.vault(original)
        assertEquals(OrbitChatCards.VAULT_OAUTH, card.getString("kind"))
        assertEquals(original, OrbitChatCards.vaultRequest(card))
        assertEquals("[OAuth login card: Example]", OrbitChatCards.summary(card.toString(), true))
        assertFalse(card.toString().contains("refresh_token"))
        assertFalse(card.toString().contains("code_verifier"))
    }

    @Test fun oauthToolResultDrawsAnIndependentBubbleWithReplyAndReactionIdentity() {
        val body = JSONObject().put(OrbitChatCards.KEY, OrbitChatCards.vault(request()))
        val shown = OrbitSpeech.of(
            "Authorize below.\n<tool_result name=\"orbit_vault\" status=\"success\"><content>$body</content></tool_result>")
        val pieces = OrbitSpeech.pieceList(shown, 42, english = true)
        assertEquals(2, pieces.size)
        assertEquals("[OAuth login card: Example]", pieces.last().summary)
        assertTrue(pieces.last().key.startsWith("card:"))
        assertTrue(pieces.last().showsBadge)
    }

    @Test fun responseTruncationMasksTokensThatCrossTheDisplayBoundary() {
        assertEquals("ok:••", OrbitVault.redact("ok:example-access-token rest", listOf("example-access-token"), 5))
        assertEquals("ok:••", OrbitVault.redact("ok:example%2Btoken rest", listOf("example+token"), 5))
    }

    @Test fun oauthOnlySendsToExactRegisteredHostsWhileExistingKeysKeepTheirSiteMatching() {
        val hosts = listOf("api.example.com")
        assertTrue(OrbitVault.permitsApiHost(OrbitVault.OAUTH, "API.EXAMPLE.COM", hosts))
        assertFalse(OrbitVault.permitsApiHost(OrbitVault.OAUTH, "www.api.example.com", hosts))
        assertFalse(OrbitVault.permitsApiHost(OrbitVault.OAUTH, "x.api.example.com", hosts))
        assertFalse(OrbitVault.permitsApiHost(OrbitVault.OAUTH, "api.example.com.evil.test", hosts))
        assertTrue(OrbitVault.permitsApiHost(OrbitVault.API_KEY, "x.api.example.com", hosts))
    }
}
