package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrbitFolderGuardTest {
    @get:Rule val folder = TemporaryFolder()

    private val base by lazy { folder.newFolder("orbit") }
    private val root by lazy { File(base, "o1").apply { mkdirs() } }
    private val files by lazy { OrbitFiles(root) }

    private fun delete(path: String, recursive: Boolean? = null, environment: String? = null): ToolResult? = runBlocking {
        val parameters = listOfNotNull(
            ToolParameter("path", path),
            recursive?.let { ToolParameter("recursive", it.toString()) },
            environment?.let { ToolParameter("environment", it) },
        )
        OrbitFolderGuard.deleteFile(base, AITool("delete_file", parameters)) { OrbitFiles(File(base, it)) }
    }

    @Test fun pathsOutsideTheOrbitFoldersGoToTheApp() {
        assertNull(delete(folder.newFile("elsewhere.txt").absolutePath))
        assertNull(delete("files/a.md"))
        assertNull(delete(File(root, "files/a.md").absolutePath, environment = "linux"))
        assertNull(delete(File(root, "files/a.md").absolutePath, environment = "repo:x"))
        File(root, "packages.json").writeText("{}")
        assertFalse(delete(File(root, "packages.json").absolutePath, environment = "local")!!.success)
        assertTrue(File(root, "packages.json").exists())
        runBlocking { files.write("files/report.md", "r") }
        assertFalse(delete(File(root, "files/x/../report.md").path)!!.success)
        assertTrue(File(root, "files/report.md").exists())
    }

    @Test fun internalDataIsRefused() = runBlocking {
        File(root, "SOUL.md").writeText("soul")
        File(root, "user.md").writeText("user")
        File(root, "packages.json").writeText("{}")
        File(root, "docs").mkdirs()
        File(root, "docs/orbit.md").writeText("doc")
        files.write("memory/people/Alex.md", "# Alex")
        listOf("SOUL.md", "user.md", "packages.json", "docs/orbit.md", "memory/people/INDEX.md", "files").forEach { short ->
            File(root, "files").mkdirs()
            val result = delete(File(root, short).absolutePath)
            assertNotNull(short, result)
            assertFalse(short, result!!.success)
            assertTrue(short, File(root, short).exists())
        }
        assertFalse(delete(root.absolutePath)!!.success)
        assertFalse(delete(base.absolutePath)!!.success)
        assertTrue(root.isDirectory)
    }

    @Test fun aNoteIsDeletedForGoodAndTheIndexFollows() = runBlocking {
        files.write("memory/people/Alex.md", "# Alex")
        files.write("memory/people/Sam.md", "# Sam")
        val result = delete(File(root, "memory/people/Alex.md").absolutePath)!!
        assertTrue(result.error, result.success)
        assertTrue(result.result.toString().contains("for good"))
        assertFalse(File(root, "memory/people/Alex.md").exists())
        assertFalse(files.read("memory/people/INDEX.md").contains("Alex"))
        assertFalse(File(root, ".trash").exists())
    }

    @Test fun aFolderThatIsNotEmptyNeedsRecursive() = runBlocking {
        files.write("files/book/a.md", "a")
        val path = File(root, "files/book").absolutePath
        val refused = delete(path)!!
        assertFalse(refused.success)
        assertTrue(refused.error!!.contains("recursive=true"))
        assertTrue(File(root, "files/book/a.md").exists())
        assertTrue(delete(path, recursive = true)!!.success)
        assertFalse(File(root, "files/book").exists())
    }

    @Test fun aLinkedFolderUnderFilesCannotReachOrbitsOwnData() = runBlocking {
        File(root, "packages.json").writeText("{}")
        File(root, "docs").mkdirs()
        File(root, "docs/orbit.md").writeText("doc")
        File(root, "files").mkdirs()
        val made = runCatching { java.nio.file.Files.createSymbolicLink(File(root, "files/link").toPath(), root.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("symbolic links not allowed here", made)
        assertFalse(delete(File(root, "files/link/packages.json").absolutePath)!!.success)
        assertFalse(delete(File(root, "files/link/docs").absolutePath, recursive = true)!!.success)
        assertTrue(File(root, "packages.json").exists())
        assertTrue(File(root, "docs/orbit.md").exists())
    }

    @Test fun theTaskCopiesCannotBeWrittenOrDeleted() {
        fun write(path: String, environment: String? = null) = OrbitFolderGuard.refuseWrite(base,
            AITool("edit_file", listOfNotNull(ToolParameter("path", path), environment?.let { ToolParameter("environment", it) })))
        val copy = File(root, "tasks/看邮件-1a2b3c4d.md")
        copy.parentFile.mkdirs()
        copy.writeText("copy")
        val refused = write(copy.absolutePath)
        assertNotNull(refused)
        assertFalse(refused!!.success)
        assertTrue(refused.error.orEmpty().contains("orbit_task update"))
        assertNotNull(write(File(root, "files/../tasks/new.md").path))
        assertNotNull(write(File(root, "tasks").absolutePath))
        assertNull(write(File(root, "files/tasks/a.md").absolutePath))
        assertNull(write(File(root, "LESSONS.md").absolutePath))
        assertNull(write(copy.absolutePath, environment = "linux"))
        assertNull(write(folder.newFile("tasks.md").absolutePath))
        val elsewhere = File(root, "files/a.md").absolutePath
        fun move(name: String, source: String, destination: String) = OrbitFolderGuard.refuseWrite(base,
            AITool(name, listOf(ToolParameter("source", source), ToolParameter("destination", destination))),
            if (name == "move_file") listOf("source", "destination") else listOf("destination"))
        assertNotNull(move("move_file", copy.absolutePath, elsewhere))
        assertNotNull(move("move_file", elsewhere, File(root, "tasks/x.md").absolutePath))
        assertNotNull(move("copy_file", elsewhere, copy.absolutePath))
        assertNull(move("copy_file", copy.absolutePath, elsewhere))
        // A folder holding a tasks/: the Orbit's folder and the folder of all Orbits.
        assertNotNull(move("move_file", root.absolutePath, folder.newFolder("away").absolutePath))
        assertNotNull(move("copy_file", folder.newFolder("source").absolutePath, root.absolutePath))
        assertNotNull(move("copy_file", elsewhere, base.absolutePath))
        assertNotNull(move("copy_file", elsewhere, folder.root.absolutePath))
        val linuxCopy = AITool("copy_file", listOf(ToolParameter("source", elsewhere), ToolParameter("destination", copy.absolutePath),
            ToolParameter("dest_environment", "linux")))
        assertNull(OrbitFolderGuard.refuseWrite(base, linuxCopy, listOf("destination"), listOf("dest_environment", "environment")))
        // Only copy_file goes by dest_environment.
        val edit = AITool("edit_file", listOf(ToolParameter("path", copy.absolutePath), ToolParameter("dest_environment", "linux")))
        assertNotNull(OrbitFolderGuard.refuseWrite(base, edit))
        val deleted = delete(copy.absolutePath)
        assertFalse(deleted!!.success)
        assertTrue(copy.exists())
    }
}
