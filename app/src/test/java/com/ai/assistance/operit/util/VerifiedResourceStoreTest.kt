package com.ai.assistance.operit.util

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedResourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "resource content".toByteArray()
    private fun spec() = DownloadResource("test", "https://example.invalid/resource", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun verifiesAndPublishesCompleteFile() = runBlocking {
        val target = File(temporary.newFolder(), "resource")
        var progress = 0L
        VerifiedResourceStore.write(target, spec(), ByteArrayInputStream(bytes)) { progress = it }
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
                VerifiedResourceStore.write(target, spec(), ByteArrayInputStream(bad)) {}
                fail("Invalid download must fail")
            } catch (_: IOException) { }
            assertEquals("old file", target.readText())
            assertFalse(File(target.parentFile, "resource.part").exists())
        }
    }

    @Test fun cancellationCleansPartialWithoutPublishing() = runBlocking {
        val target = File(temporary.newFolder(), "resource")
        try {
            VerifiedResourceStore.write(target, spec(), ByteArrayInputStream(bytes)) {
                throw CancellationException("cancel")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "resource.part").exists())
    }
}
