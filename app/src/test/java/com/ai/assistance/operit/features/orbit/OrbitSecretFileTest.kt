package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class OrbitSecretFileTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun ciphertextSurvivesRestartAndDoesNotExposeCredential() {
        val file = folder.newFile("a.bin").apply { delete() }
        val key = key()
        val store = OrbitSecretFile(file) { key }
        assertNull(store.read())
        store.write("""{"bearer":"synthetic-credential"}""")
        assertFalse(file.readText().contains("synthetic-credential"))
        assertEquals("""{"bearer":"synthetic-credential"}""", OrbitSecretFile(file) { key }.read())
        val first = file.readBytes()
        store.write("""{"bearer":"synthetic-credential"}""")
        assertFalse(first.contentEquals(file.readBytes()))
    }
    @Test fun changedCiphertextKeyOrOwnerFailsWithoutOverwritingStoredData() {
        val file = folder.newFile("a.bin")
        val key = key()
        OrbitSecretFile(file) { key }.write("sensitive")
        val bytes = file.readBytes()
        assertTrue(runCatching { OrbitSecretFile(file) { key() }.read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        val other = folder.newFile("b.bin").apply { writeBytes(bytes) }
        assertTrue(runCatching { OrbitSecretFile(other) { key }.read() }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { OrbitSecretFile(file) { key }.read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
}
