package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitMailBoxTest {
    @Test
    fun addressesNeedADomain() {
        assertTrue(OrbitMailAuth.isAddress("a@example.com"))
        assertFalse(OrbitMailAuth.isAddress("alice"))
        assertFalse(OrbitMailAuth.isAddress("alice@"))
        assertEquals(listOf("a@example.com", "Bob <b@example.org>"), OrbitMailBox.addressList("a@example.com; Bob <b@example.org>"))
        assertTrue(runCatching { OrbitMailBox.addressList("a@example.com, bob") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aStoredAddressReadsBackAsTheSameOne() {
        // What a send card stores is read back as exactly one address, its name kept and encodable.
        val stored = OrbitMailBox.addressList("\"张三\" <a@example.com>, \"Doe, John\" <j@example.org>")
        assertEquals(2, stored.size)
        val first = OrbitMailBox.single(stored[0])
        assertEquals("a@example.com", first.address)
        assertEquals("张三", first.personal)
        assertEquals("j@example.org", OrbitMailBox.single(stored[1]).address)
    }

    @Test
    fun aNewMailNeedsSomeoneToGoTo() {
        val none = OrbitMailBox.Draft(null, null, null, "Hi", "text")
        assertTrue(runCatching { OrbitMailBox.composeNew(none, needsRecipient = true) }.exceptionOrNull() is IllegalArgumentException)
        // A draft may be saved before it has recipients.
        assertEquals(0, OrbitMailBox.composeNew(none, needsRecipient = false).recipients)
        val many = (1..51).joinToString(",") { "u$it@example.com" }
        assertTrue(runCatching {
            OrbitMailBox.composeNew(OrbitMailBox.Draft(many, null, null, "Hi", "text"), needsRecipient = true)
        }.exceptionOrNull() is IllegalArgumentException)
    }
}
