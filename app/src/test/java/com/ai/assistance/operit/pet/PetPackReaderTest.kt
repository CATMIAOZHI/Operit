package com.ai.assistance.operit.pet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PetPackReaderTest {
    private fun pack(entries: Map<String, ByteArray>, check: (File) -> Unit) {
        val file = File.createTempFile("pet-pack-", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            check(file)
        } finally {
            file.delete()
        }
    }

    @Test fun nestedCodexPackReadsOnlyReferencedArtwork() {
        val manifest = """{"spriteVersionNumber":2,"displayName":"Test pet","spritesheetPath":"art/pet.webp"}"""
        pack(mapOf(
            "custom/pet.json" to manifest.toByteArray(),
            "custom/art/pet.webp" to byteArrayOf(1, 2, 3),
            "other.txt" to byteArrayOf(9),
        )) { file ->
            val output = ByteArrayOutputStream()
            assertEquals("Test pet", readPetPack(file, output))
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }
    }

    @Test fun unsupportedVersionDoesNotProduceArtwork() {
        pack(mapOf("pet.json" to """{"spriteVersionNumber":1}""".toByteArray())) { file ->
            val output = ByteArrayOutputStream()
            val error = assertThrows(PetImportException::class.java) { readPetPack(file, output) }
            assertEquals(PetImportException.Reason.INVALID_PACK, error.reason)
            assertEquals(0, output.size())
        }
    }

    @Test fun missingImageAndAmbiguousPacksAreRejected() {
        for (entries in listOf(
            mapOf("pet.json" to """{"spriteVersionNumber":2,"spritesheetPath":"missing.webp"}""".toByteArray()),
            mapOf("a/pet.json" to "{}".toByteArray(), "b/pet.json" to "{}".toByteArray()),
        )) {
            pack(entries) { file ->
                assertThrows(PetImportException::class.java) { readPetPack(file, ByteArrayOutputStream()) }
            }
        }
    }

    @Test fun decompressedDataCannotExceedCopyBudget() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(PetImportException::class.java) {
            copyPetBytes(ByteArrayInputStream(ByteArray(20_000)), output, 10_000)
        }
        assertEquals(PetImportException.Reason.TOO_LARGE, error.reason)
        assertTrue(output.size() <= 10_000)
    }
}
