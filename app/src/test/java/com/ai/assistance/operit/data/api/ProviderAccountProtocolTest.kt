package com.ai.assistance.operit.data.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderAccountProtocolTest {
    @Test fun discoveryRejectsUntrustedTokenRecipients() {
        for (url in listOf("http://auth.x.ai/token", "https://x.ai.evil.test/token", "https://user@auth.x.ai/token")) {
            try { ProviderAccountManager.validateXaiEndpoint(url); fail(url) }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://auth.x.ai/token", ProviderAccountManager.validateXaiEndpoint("https://auth.x.ai/token"))
    }

    @Test fun projectDiscoveryAcceptsBothPublishedResponseShapes() {
        assertEquals("one", ProviderAccountManager.extractProject(JSONObject("""{"cloudaicompanionProject":"one"}""")))
        assertEquals("two", ProviderAccountManager.extractProject(JSONObject("""{"cloudaicompanionProject":{"id":"two"}}""")))
        assertNull(ProviderAccountManager.extractProject(JSONObject("{}")))
    }
}
