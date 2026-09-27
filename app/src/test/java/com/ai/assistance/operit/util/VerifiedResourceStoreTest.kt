package com.ai.assistance.operit.util

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedResourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "resource content".toByteArray()
    private val largeBytes = ByteArray(64 * 1024 * 3 + 17) { (it % 251).toByte() }
    private fun spec() = DownloadResource("test", "https://example.invalid/resource", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun largeSpec() = DownloadResource("test", "https://example.invalid/resource", largeBytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(largeBytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun verifiesAndPublishesCompleteFile() = runBlocking {
        val target = File(temporary.newFolder(), "resource")
        var progress = 0L
        VerifiedResourceStore.write(target, spec(), ByteArrayInputStream(bytes), 0L) { progress = it }
        assertTrue(VerifiedResourceStore.isValid(target, spec()))
        assertEquals(bytes.size.toLong(), progress)
        assertFalse(File(target.parentFile, "resource.part").exists())
        target.writeText("corrupt")
        assertFalse(VerifiedResourceStore.isValid(target, spec()))
    }

    @Test fun badContentDoesNotReplaceExistingFile() = runBlocking {
        for (bad in listOf("short".toByteArray(), ByteArray(bytes.size) { 2 }, ByteArray(bytes.size + 1))) {
            val target = File(temporary.newFolder(), "resource").apply { writeText("old file") }
            try {
                VerifiedResourceStore.write(target, spec(), ByteArrayInputStream(bad), 0L) {}
                fail("Invalid download must fail")
            } catch (_: IOException) { }
            assertEquals("old file", target.readText())
            assertFalse(File(target.parentFile, "resource.part").exists())
        }
    }

    @Test fun interruptionKeepsPartialForResume() = runBlocking {
        val target = File(temporary.newFolder(), "resource")
        val limit = 64L * 1024
        try {
            VerifiedResourceStore.write(target, largeSpec(), ByteArrayInputStream(largeBytes), 0L) {
                if (it > limit) throw IOException("connection lost")
            }
            fail("Interrupted download must fail")
        } catch (_: IOException) { }
        assertFalse(target.exists())
        val part = File(target.parentFile, "resource.part")
        assertTrue(part.isFile)
        assertTrue(part.length() > 0L)
        assertTrue(part.length() < largeBytes.size.toLong())
    }

    @Test fun resumedWriteCompletesAndVerifies() = runBlocking {
        val folder = temporary.newFolder()
        val target = File(folder, "resource")
        val done = 64 * 1024 + 3
        val part = File(folder, "resource.part").apply { writeBytes(largeBytes.copyOf(done)) }
        var progress = 0L
        VerifiedResourceStore.write(
            target,
            largeSpec(),
            ByteArrayInputStream(largeBytes.copyOfRange(done, largeBytes.size)),
            part.length()
        ) { progress = it }
        assertTrue(VerifiedResourceStore.isValid(target, largeSpec()))
        assertEquals(largeBytes.size.toLong(), progress)
        assertFalse(part.exists())
    }

    @Test fun oversizedPartialIsDiscardedBeforeResume() = runBlocking {
        val folder = temporary.newFolder()
        val part = File(folder, "resource.part").apply { writeBytes(ByteArray(bytes.size + 5)) }
        assertEquals(0L, VerifiedResourceStore.resumeOffset(part, spec()))
        assertFalse(part.exists())
    }
}
