package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSourcePathPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsNestedImageAndReturnsCanonicalPath() {
        val root = temporary.newFolder("images")
        File(root, "nested").mkdir()
        File(root, "photo.png").writeText("image")
        val image = File(root, "nested/../photo.png")
        assertEquals(File(root, "photo.png").canonicalFile,
            ImageSourcePathPolicy.resolve(image.path, listOf(root)))
    }

    @Test fun rejectsTraversalAndSiblingPrefix() {
        val root = temporary.newFolder("images")
        File(temporary.newFolder("private"), "photo.png").writeText("image")
        File(temporary.newFolder("images-other"), "photo.png").writeText("image")
        listOf(File(root, "../private/photo.png"), File(root.parentFile, "images-other/photo.png"))
            .forEach { source ->
                assertThrows(IllegalArgumentException::class.java) {
                    ImageSourcePathPolicy.resolve(source.path, listOf(root))
                }
            }
    }

    @Test fun rejectsRelativePathAndEmptyAllowlist() {
        val root = temporary.newFolder("images")
        File(root, "photo.png").writeText("image")
        assertThrows(IllegalArgumentException::class.java) {
            ImageSourcePathPolicy.resolve("photo.png", listOf(root))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSourcePathPolicy.resolve(File(root, "photo.png").path, emptyList())
        }
    }

    @Test fun rejectsSymlinkEscapingRoot() {
        val root = temporary.newFolder("images")
        val privateDir = temporary.newFolder("private")
        File(privateDir, "photo.png").writeText("image")
        val link = File(root, "outside").toPath()
        try {
            Files.createSymbolicLink(link, privateDir.toPath())
        } catch (e: java.nio.file.FileSystemException) {
            org.junit.Assume.assumeNoException("Symlinks unavailable on this host", e)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSourcePathPolicy.resolve(link.resolve("photo.png").toString(), listOf(root))
        }
    }
}
