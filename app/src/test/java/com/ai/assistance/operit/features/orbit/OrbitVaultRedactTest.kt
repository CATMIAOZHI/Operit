package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrbitVaultRedactTest {
    @Test
    fun masksEveryCopyOfTheKeyInBytes() {
        val secret = "sk-test_ABC+123"
        val body = "{\"token\":\"$secret\",\"again\":\"$secret\",\"url\":\"sk-test_ABC%2B123\"}".toByteArray()
        val masked = String(OrbitVault.redact(body, listOf(secret)))
        assertFalse(masked.contains("ABC"))
        assertEquals(3, Regex("••••").findAll(masked).count())
    }

    @Test
    fun leavesBytesWithoutTheKeyUntouched() {
        val body = byteArrayOf(0, 1, 2, -1, 115, 107)
        assertArrayEquals(body, OrbitVault.redact(body, listOf("sk-secret")))
    }

    @Test(expected = OrbitVault.TooLarge::class)
    fun stopsWhenMaskingAShortKeyWouldGrowPastTheLimit() {
        OrbitVault.redact(ByteArray(1000) { 'a'.code.toByte() }, listOf("a"), maxSize = 2000)
    }
}
