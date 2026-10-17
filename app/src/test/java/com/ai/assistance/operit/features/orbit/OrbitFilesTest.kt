package com.ai.assistance.operit.features.orbit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbitFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = OrbitFiles(folder.newFolder("orbit"))

    @Test fun pathsOutsideOrbitsFilesAreRefused() {
        listOf("../x.md", "memory/../../x.md", "/etc/passwd", "memory\\..\\x.md", ".trash/1/payload", "memory/people/.hidden.md")
            .forEach { bad -> assertTrue(bad, runCatching { store().fileFor(bad) }.isFailure) }
        assertFalse(OrbitFiles.allowed("memory.md"))
        assertTrue(OrbitFiles.allowed("memory/people/Alex.md"))
        assertTrue(OrbitFiles.allowed("memory/2026-10-17.md"))
        assertTrue(OrbitFiles.allowed("files/a/b/c/report.pdf"))
        assertFalse(OrbitFiles.allowed("files/a/b/c/d/report.pdf"))
    }

    @Test fun personNotesKeepAnIndexThatCannotBeWritten() = runBlocking {
        val files = store()
        files.write("memory/people/Alex.md", "# Alex\n\nLikes tea")
        assertTrue(files.read("memory/people/INDEX.md").contains("[Alex](Alex.md) — Likes tea"))
        assertTrue(runCatching { files.write("memory/people/INDEX.md", "x") }.isFailure)
    }

    @Test fun deletedFilesCanBeRestoredButNeverOverwriteANewOne() = runBlocking {
        val files = store()
        files.write("LESSONS.md", "old")
        val item = files.delete("LESSONS.md")
        assertEquals(listOf(item.id), files.trash().map { it.id })
        files.write("LESSONS.md", "new")
        assertTrue(runCatching { files.restore(item.id) }.isFailure)
        files.delete("LESSONS.md")
        files.restore(item.id)
        assertEquals("old", files.read("LESSONS.md"))
        files.purge(files.trash().single().id)
        assertTrue(files.trash().isEmpty())
    }

    @Test fun savingUnderADottedFolderKeepsTheFolderAndExtension() = runBlocking {
        val files = store()
        files.saveBytes("files/notes.v1/x.md", "a".byteInputStream())
        val second = files.saveBytes("files/notes.v1/x.md", "b".byteInputStream())
        assertEquals("files/notes.v1/x-2.md", second.path)
    }

    @Test fun dailyLogAppendsLines() = runBlocking {
        val files = store()
        val time = java.util.Calendar.getInstance().apply { set(2026, 9, 17, 9, 5) }.timeInMillis
        files.appendDaily("first", time)
        files.appendDaily("second", time)
        val text = files.read("memory/2026-10-17.md")
        assertTrue(text.startsWith("# 2026-10-17\n"))
        assertTrue(text.contains("- 09:05 first\n- 09:05 second"))
    }

    @Test fun linksOutOfTheFolderAreNotFollowed() = runBlocking {
        val outside = folder.newFolder("outside")
        java.io.File(outside, "secret.md").writeText("secret")
        val root = folder.newFolder("linked")
        java.io.File(root, "files").mkdirs()
        val link = java.io.File(root, "files/out").toPath()
        val made = runCatching { java.nio.file.Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("symbolic links not allowed here", made)
        val files = OrbitFiles(root)
        assertTrue(runCatching { files.read("files/out/secret.md") }.isFailure)
        assertTrue(runCatching { files.write("files/out/new.md", "x") }.isFailure)
        assertTrue(runCatching { files.saveBytes("files/out/new.bin", "x".byteInputStream()) }.isFailure)
        assertFalse(java.io.File(outside, "new.md").exists())
        assertTrue(files.list().none { it.path.startsWith("files/out/") })
        // A link to a folder that does not exist yet must not be created through.
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "files/gone").toPath(), java.io.File(outside, "missing").toPath())
        assertTrue(runCatching { files.write("files/gone/x.md", "x") }.isFailure)
        assertFalse(java.io.File(outside, "missing").exists())
    }

    @Test fun trashKeepsFilesForThirtyDays() = runBlocking {
        val root = folder.newFolder("trashy")
        val files = OrbitFiles(root)
        files.write("LESSONS.md", "a")
        val item = files.delete("LESSONS.md")
        val meta = java.io.File(root, ".trash/${item.id}/meta.json")
        val old = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        meta.writeText(meta.readText().replace(item.deletedAt.toString(), old.toString()))
        // A folder whose note cannot be read goes by its own age: a fresh one stays.
        val broken = java.io.File(root, ".trash/1-abcdef12").apply { mkdirs() }
        java.io.File(broken, "meta.json").writeText("not json")
        assertTrue(files.trash().isEmpty())
        assertFalse(meta.parentFile.exists())
        assertTrue(broken.exists())
        broken.setLastModified(old)
        files.trash()
        assertFalse(broken.exists())
    }

    @Test fun libraryPinsKeepTheNewestFirstAndOnlyMadeFiles() = runBlocking {
        val files = store()
        files.setPinned("files/a.png", true)
        files.setPinned("files/voice/b.mp3", true)
        assertEquals(listOf("files/voice/b.mp3", "files/a.png"), files.pins())
        files.setPinned("files/a.png", true)
        assertEquals(listOf("files/a.png", "files/voice/b.mp3"), files.pins())
        files.setPinned("files/a.png", false)
        assertEquals(listOf("files/voice/b.mp3"), files.pins())
        assertTrue(runCatching { files.setPinned("LESSONS.md", true) }.isFailure)
        assertTrue(runCatching { files.setPinned("files/../x", true) }.isFailure)
        // The pins file is the app's own and never shows up as one of Orbit's files.
        assertTrue(files.list().none { it.path.contains("library_pins") })
    }

    @Test fun libraryKindsComeFromTheExtension() {
        assertEquals(OrbitFiles.Kind.IMAGE, OrbitFiles.kindOf("files/x.JPG"))
        assertEquals(OrbitFiles.Kind.AUDIO, OrbitFiles.kindOf("files/voice/x.wav"))
        assertEquals(OrbitFiles.Kind.DOCUMENT, OrbitFiles.kindOf("files/report.pdf"))
        assertEquals(OrbitFiles.Kind.OTHER, OrbitFiles.kindOf("files/archive.zip"))
    }

    @Test fun goalNotesKeepTheirKind() = runBlocking {
        val board = OrbitBoardRepository(folder.newFolder("board"), OrbitBoardRepository.Board.GOAL_NOTES)
        val brief = board.create("Morning", "Body", "goal-1", "r1", state = "BRIEF")
        val log = board.create("Done step", "Done step", "goal-1", "r2")
        assertEquals("BRIEF", brief.state)
        assertEquals("LOG", log.state)
        assertTrue(runCatching { board.create("x", "y", "goal-1", "r3", state = "DONE") }.isFailure)
        assertEquals(setOf("r1", "r2").size, board.list().count { it.extra == "goal-1" })
    }

    @Test fun routineSourcesAreOnlyForAssistTasks() {
        OrbitTaskRepository.checkSource("ASSIST", OrbitRoutines.CHAT_SOURCE)
        OrbitTaskRepository.checkSource("ASSIST", OrbitRoutines.GOALS_SOURCE)
        assertTrue(runCatching { OrbitTaskRepository.checkSource("URL_CHANGE", OrbitRoutines.CHAT_SOURCE) }.isFailure)
        assertTrue(runCatching { OrbitTaskRepository.checkSource("WATCH", OrbitRoutines.GOALS_SOURCE) }.isFailure)
    }
}
