package com.ai.assistance.operit.data.preferences

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class LegacyStorageInitializerTest {
    @Test
    fun legacyProbeIsNonCreatingAndStopsAfterFindingAnEntry() {
        val root = Files.createTempDirectory("legacy-storage-probe").toFile()
        try {
            val missing = File(root, "missing")
            assertFalse(legacyDirectoryHasAnyEntry(missing))
            assertFalse(missing.exists())

            val existing = File(root, "existing").apply { mkdirs() }
            assertFalse(legacyDirectoryHasAnyEntry(existing))
            File(existing, "entry").writeText("data")
            assertTrue(legacyDirectoryHasAnyEntry(existing))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyProbeRejectsASymlinkDirectory() {
        val root = Files.createTempDirectory("legacy-storage-probe-link").toFile()
        val outside = Files.createTempDirectory("legacy-storage-probe-outside").toFile()
        File(outside, "entry").writeText("data")
        val link = File(root, "linked").toPath()
        try {
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (error: Exception) {
                assumeNoException(error)
            }
            assertFalse(legacyDirectoryHasAnyEntry(link.toFile()))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
