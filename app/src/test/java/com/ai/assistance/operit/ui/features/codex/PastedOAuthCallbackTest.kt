package com.ai.assistance.operit.ui.features.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PastedOAuthCallbackTest {
    private val redirect = "http://127.0.0.1:51121/callback"
    private val state = "s-123"

    @Test
    fun fullAddressKeepsItsQueryOnThisLoginsRedirect() {
        val query = "state=s-123&code=4%2F0Ab&scope=email%20https://www.googleapis.com/auth/cloud-platform"
        assertEquals("$redirect?$query", pastedOAuthCallback("http://localhost:51121/callback?$query#_", redirect, state))
    }

    @Test
    fun addressWithoutSchemeAndBareQueryAreAccepted() {
        assertEquals("$redirect?code=abc&state=s-123",
            pastedOAuthCallback("  127.0.0.1:51121/callback?code=abc&state=s-123\n", redirect, state))
        assertEquals("$redirect?code=abc&state=s-123", pastedOAuthCallback("?code=abc&state=s-123", redirect, state))
    }

    @Test
    fun addressOrQueryMustCarryThisLoginsState() {
        assertNull(pastedOAuthCallback("$redirect?code=abc", redirect, state))
        assertNull(pastedOAuthCallback("$redirect?code=abc&state=other", redirect, state))
        assertNull(pastedOAuthCallback("code=abc&state=s-123&state=other", redirect, state))
    }

    @Test
    fun bareCodeTakesThisLoginsStateAndChecksAnAttachedOne() {
        assertEquals("$redirect?code=a%2Fb&state=s-123", pastedOAuthCallback("a/b", redirect, state))
        assertEquals("$redirect?code=abc&state=s-123", pastedOAuthCallback("abc#s-123", redirect, state))
        assertNull(pastedOAuthCallback("abc#other", redirect, state))
    }

    @Test
    fun declinedSignInIsPassedOnAndEmptyOrUnrelatedTextIsNot() {
        assertEquals("$redirect?error=access_denied&state=s-123",
            pastedOAuthCallback("$redirect?error=access_denied&state=s-123", redirect, state))
        assertNull(pastedOAuthCallback("", redirect, state))
        assertNull(pastedOAuthCallback("https://auth.example.com/authorize?client_id=x&state=s-123", redirect, state))
        assertNull(pastedOAuthCallback("two words", redirect, state))
    }
}
