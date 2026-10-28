package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import com.ai.assistance.operit.features.orbit.OrbitSecretFile
import java.io.File
import java.nio.file.Files
import org.json.JSONArray

/** Migration commits ciphertext before removing the old file. A damaged store never falls back. */
internal class BrowserDownloadStore(
    private val protectedFile: OrbitSecretFile,
    private val legacyFile: File
) {
    fun read(): String? {
        protectedFile.read()?.let {
            JSONArray(it)
            Files.deleteIfExists(legacyFile.toPath())
            return it
        }
        if (!legacyFile.exists()) return null
        val text = legacyFile.readText().ifBlank { "[]" }
        JSONArray(text)
        protectedFile.write(text)
        check(protectedFile.read() == text)
        Files.delete(legacyFile.toPath())
        return text
    }

    fun write(text: String) {
        protectedFile.write(text)
        Files.deleteIfExists(legacyFile.toPath())
    }
}
