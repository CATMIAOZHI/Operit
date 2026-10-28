package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import com.ai.assistance.operit.features.orbit.OrbitSecretFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class BrowserDownloadStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun existingJournalLargerThanConnectorLimitStillMigrates() {
        val legacy = folder.newFile("large.json").apply { writeText("[\"" + "x".repeat(2_000_000) + "\"]") }
        val file = folder.newFile("large.bin").apply { delete() }
        val key = key()
        val store = BrowserDownloadStore(OrbitSecretFile(file, Int.MAX_VALUE - 29) { key }, legacy)
        assertEquals(2_000_004, store.read()!!.length)
        assertFalse(legacy.exists())
        assertEquals(2_000_004, store.read()!!.length)
    }
    @Test fun legacyCredentialsMigrateOnlyAfterEncryptedCommit() {
        val legacy = folder.newFile("old.json").apply { writeText("""[{"headers":{"Cookie":"test-only-cookie"}}]""") }
        val encrypted = folder.newFile("protected.bin").apply { delete() }
        val key = key()
        val protected = OrbitSecretFile(encrypted) { key }
        val store = BrowserDownloadStore(protected, legacy)
        val expected = legacy.readText()
        assertEquals(expected, store.read())
        assertFalse(legacy.exists())
        assertFalse(encrypted.readText().contains("test-only-cookie"))
        assertEquals(expected, BrowserDownloadStore(OrbitSecretFile(encrypted) { key }, legacy).read())
        // Interrupted cleanup must not restore stale plaintext.
        legacy.writeText("[]")
        assertEquals(expected, store.read())
        assertFalse(legacy.exists())
    }
    @Test fun missingKeyAndMalformedLegacyPreserveBothSources() {
        val legacy = folder.newFile("old.json").apply { writeText("not json") }
        val encrypted = folder.newFile("protected.bin").apply { delete() }
        val key = key()
        val store = BrowserDownloadStore(OrbitSecretFile(encrypted) { key }, legacy)
        assertTrue(runCatching { store.read() }.isFailure)
        assertTrue(legacy.exists())
        assertFalse(encrypted.exists())
        OrbitSecretFile(encrypted) { key }.write("[]")
        val bytes = encrypted.readBytes()
        val broken = BrowserDownloadStore(OrbitSecretFile(encrypted) { key() }, legacy)
        assertTrue(runCatching { broken.read() }.isFailure)
        assertArrayEquals(bytes, encrypted.readBytes())
        assertEquals("not json", legacy.readText())
    }
}
