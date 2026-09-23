package com.ai.assistance.operit.features.draw

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The draw package is a script entry point, so its reference images follow the shared boundary. */
class CodexDrawBridgePathTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsReferenceImageInsidePermittedStorage() {
        val root = temporary.newFolder("shared")
        val image = File(root, "photo.png").apply { writeText("image") }
        assertEquals(
            image.canonicalFile,
            CodexDrawBridge.resolveScriptImagePath(image.path, listOf(root)),
        )
    }

    @Test fun acceptsGeneratedImageInsideDrawOutputDirectory() {
        val draws = temporary.newFolder("draws")
        val generated = File(draws, "codex_image.png").apply { writeText("image") }
        assertEquals(
            generated.canonicalFile,
            CodexDrawBridge.resolveScriptImagePath(generated.path, listOf(draws)),
        )
    }

    @Test fun rejectsReferenceImageOutsidePermittedStorage() {
        val root = temporary.newFolder("shared")
        val privateDir = temporary.newFolder("private")
        val image = File(privateDir, "photo.png").apply { writeText("image") }
        val failure = runCatching { CodexDrawBridge.resolveScriptImagePath(image.path, listOf(root)) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("允许的存储目录"))
    }

    @Test fun rejectsRelativePath() {
        val root = temporary.newFolder("shared")
        val failure = runCatching { CodexDrawBridge.resolveScriptImagePath("photo.png", listOf(root)) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun reportsMissingFileInsidePermittedStorageAsUnreadable() {
        val root = temporary.newFolder("shared")
        val failure = runCatching {
            CodexDrawBridge.resolveScriptImagePath(File(root, "missing.png").path, listOf(root))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("无法读取参考图"))
    }
}
