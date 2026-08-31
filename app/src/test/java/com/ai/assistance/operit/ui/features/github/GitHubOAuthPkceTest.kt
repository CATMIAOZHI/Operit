package com.ai.assistance.operit.ui.features.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubOAuthPkceTest {
    @Test
    fun codeChallengeMatchesRfc7636S256Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GitHubOAuthPkce.codeChallenge(verifier)
        )
    }
}
