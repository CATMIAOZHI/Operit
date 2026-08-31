package com.ai.assistance.operit.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubOAuthBrokerServiceTest {
    @Test
    fun brokerOriginRequiresHttps() {
        assertThrows(IllegalStateException::class.java) {
            GitHubOAuthBrokerService.requireBrokerOrigin("http://oauth.example.com")
        }
    }

    @Test
    fun brokerOriginNormalizesTrailingSlash() {
        assertEquals(
            "https://oauth.example.com/",
            GitHubOAuthBrokerService.requireBrokerOrigin("https://oauth.example.com/").toString()
        )
    }
}
