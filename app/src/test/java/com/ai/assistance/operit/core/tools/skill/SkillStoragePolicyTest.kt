package com.ai.assistance.operit.core.tools.skill

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillStoragePolicyTest {

    @Test
    fun `import metadata name stays one direct child below skills root`() {
        val root = Files.createTempDirectory("internal-skills").toFile()
        try {
            assertEquals(
                File(root, "safe skill").canonicalFile,
                resolveSkillImportDestination(root, "safe skill"),
            )
            listOf(
                "../mcp/packages/evil",
                "nested/skill",
                "nested\\skill",
                ".",
                "..",
                "\u0000bad",
            ).forEach { unsafe ->
                assertNull("must reject $unsafe", resolveSkillImportDestination(root, unsafe))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shadowed legacy directories are found by effective metadata name`() {
        val root = Files.createTempDirectory("legacy-skills").toFile()
        try {
            skill(root, "legacy-a", "shared-name")
            skill(root, "legacy-b", "shared-name")
            skill(root, "other", "other-name")
            skill(root, "directory-fallback", "")
            File(root, "missing-file").mkdirs()
            skill(root, "unreadable", "throws")

            val matches =
                legacySkillDirectoryNamesMatching(root, "shared-name") { file ->
                    file.readText().also { if (it == "throws") error("unreadable") }
                }
            val fallback =
                legacySkillDirectoryNamesMatching(root, "directory-fallback") { it.readText() }

            assertEquals(setOf("legacy-a", "legacy-b"), matches)
            assertEquals(setOf("directory-fallback"), fallback)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun skill(root: File, directory: String, metadataName: String) {
        val dir = File(root, directory).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(metadataName)
    }
}
