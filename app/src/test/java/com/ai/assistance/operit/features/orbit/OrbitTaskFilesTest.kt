package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.core.tools.OrbitTaskTools
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class OrbitTaskFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")

    private fun task(id: String = "1a2b3c4d-0000-0000-0000-000000000000", title: String = "看邮件") =
        OrbitTaskEntity(id, "orbit", "chat", title, "每天看一眼新邮件。\n有重要的就告诉我。", "ASSIST",
            "", 60, 9 * 60, "CHANGES", "ACTIVE", 1, 0, "", "", 0, 0, scheduleMode = "DAILY")

    @Test fun aCopyShowsTheTaskFieldsAndItsInstruction() {
        val text = OrbitTaskFiles.render(task().copy(nextRunAt = 86_400_000L), "每天看一眼新邮件。\n有重要的就告诉我。", english = false, zone = utc)
        assertTrue(text.startsWith("---\n${OrbitFiles.TASK_COPY_MARK}\n---\n# 看邮件\n\n> 应用自动更新的只读副本"))
        assertTrue(OrbitFiles.isTaskCopy(text))
        // A title with line breaks of any kind stays one heading line, the mark under it.
        val split = OrbitTaskFiles.render(task(title = "看\r\n邮\r件\n吧"), "x", english = false)
        assertTrue(split.startsWith("---\n${OrbitFiles.TASK_COPY_MARK}\n---\n# 看 邮 件 吧\n"))
        assertTrue(OrbitFiles.isTaskCopy(split))
        listOf("- task_id: 1a2b3c4d-0000-0000-0000-000000000000", "- state: ACTIVE", "- schedule_mode: DAILY",
            "- daily_time: 09:00", "- next_run: 1970-01-02 00:00", "- notify_policy: CHANGES")
            .forEach { assertTrue(it, text.contains("$it\n")) }
        assertFalse(text.contains("interval_minutes"))
        // Woken in the chat, it uses that chat's model; only a background task names one.
        assertFalse(text.contains("- model:"))
        val background = OrbitTaskFiles.render(task().copy(useSubagent = true), "x", english = false, zone = utc)
        assertTrue(background.contains("- timeout_minutes: 60\n- model: default\n"))
        assertTrue(text.endsWith("## 要做什么（instruction）\n\n每天看一眼新邮件。\n有重要的就告诉我。\n"))
    }

    @Test fun aScriptHookShowsItsScriptFileAndAPausedTaskNoNextRun() {
        val hook = task().copy(kind = OrbitWatchSources.KIND, scheduleMode = "INTERVAL", state = "PAUSED", nextRunAt = 5,
            source = OrbitWatchSources.scriptSource("files/hooks/mail-1234abcd.py", "0123456789abcdef", listOf("key1"), emptyList()))
        val text = OrbitTaskFiles.render(hook, hook.instruction, english = true, zone = utc)
        assertTrue(text.contains("- script_path: files/hooks/mail-1234abcd.py\n- language: python\n"))
        assertTrue(text.contains("- interval_minutes: 60\n"))
        assertFalse(text.contains("next_run"))
        assertFalse(text.contains("key1"))
        assertTrue(text.contains("## Instruction\n"))
    }

    @Test fun aRoutineCopySaysOnlyItsScheduleAndModelChange() {
        val feed = task(id = OrbitRoutines.feedId("orbit"))
        assertTrue(OrbitTaskFiles.render(feed, feed.instruction, english = false).contains("只能用 orbit_task update 改时间和模型"))
    }

    @Test fun fileNamesAreTheTitleAndTheStartOfTheId() {
        fun name(task: OrbitTaskEntity) = OrbitTaskFiles.fileNames(listOf(task)).getValue(task.id)
        assertEquals("看邮件-1a2b3c4d.md", name(task()))
        assertEquals("a-b-1a2b3c4d.md", name(task(title = "a/b")))
        assertEquals("task-1a2b3c4d.md", name(task(title = "../")))
        // Cut inside a character outside the basic plane: no half of it is left.
        assertEquals("a".repeat(39) + "-1a2b3c4d.md", name(task(title = "a".repeat(39) + "\uD840\uDC00")))
        val twin = task(id = "1a2b3c4d-1111-0000-0000-000000000000")
        val names = OrbitTaskFiles.fileNames(listOf(task(), twin))
        assertEquals("看邮件-1a2b3c4d000000000000000000000000.md", names[task().id])
        assertEquals("看邮件-1a2b3c4d111100000000000000000000.md", names[twin.id])
    }

    @Test fun syncWritesTheCopiesRemovesStaleOnesAndDropsTheEmptyFolder(): Unit = runBlocking {
        val root = folder.newFolder("o1")
        val files = OrbitFiles(root)
        val tasks = File(root, OrbitFiles.TASKS)
        fun copy(name: String) = "---\n${OrbitFiles.TASK_COPY_MARK}\n---\n# $name\n\n- task_id: $name\n"
        assertFalse(files.syncTaskCopies(emptyMap()))
        assertFalse(tasks.exists())

        assertTrue(files.syncTaskCopies(mapOf("a.md" to copy("a"), "b.md" to copy("b"))))
        assertEquals(copy("a"), File(tasks, "a.md").readText())
        assertEquals(copy("b"), File(tasks, "b.md").readText())
        File(tasks, "a.md").setLastModified(1_000)
        File(tasks, "note.txt").writeText("left alone")
        File(tasks, "mine.md").writeText("# My notes\n\nnot a copy\n")
        // A note of the user's that looks like a copy, without the app's mark.
        File(tasks, "plan.md").writeText("# 项目任务\n\n- task_id: 项目-01\n")

        assertTrue(files.syncTaskCopies(mapOf("a.md" to copy("a"))))
        assertEquals(1_000, File(tasks, "a.md").lastModified())
        assertFalse(File(tasks, "b.md").exists())
        assertTrue(File(tasks, "note.txt").exists())
        assertTrue(File(tasks, "mine.md").exists())
        assertTrue(File(tasks, "plan.md").exists())
        assertFalse(files.syncTaskCopies(mapOf("a.md" to copy("a"))))

        assertTrue(files.syncTaskCopies(mapOf("a.md" to copy("a2"))))
        assertEquals(copy("a2"), File(tasks, "a.md").readText())

        // A file of the same name that is not a copy is kept; the copy goes beside it.
        val failures = mutableListOf<String>()
        assertTrue(files.syncTaskCopies(mapOf("mine.md" to copy("m"), "c.md" to copy("c"))) { name, _ -> failures += name })
        assertEquals("# My notes\n\nnot a copy\n", File(tasks, "mine.md").readText())
        assertEquals(copy("m"), File(tasks, "mine-task.md").readText())
        assertEquals(copy("c"), File(tasks, "c.md").readText())
        // Both names taken: the next free one is used, and nothing is reported.
        File(tasks, "e.md").mkdirs()
        File(tasks, "e-task.md").mkdirs()
        assertTrue(files.syncTaskCopies(mapOf("mine.md" to copy("m"), "c.md" to copy("c2"), "e.md" to copy("e"))) { name, _ -> failures += name })
        assertEquals(emptyList<String>(), failures)
        assertEquals(copy("e"), File(tasks, "e-task-2.md").readText())
        assertEquals(copy("c2"), File(tasks, "c.md").readText())
        File(tasks, "e.md").delete()
        File(tasks, "e-task.md").delete()

        files.syncTaskCopies(emptyMap())
        assertFalse(File(tasks, "c.md").exists())
        assertFalse(File(tasks, "mine-task.md").exists())
        assertTrue(tasks.isDirectory)
        File(tasks, "note.txt").delete()
        File(tasks, "mine.md").delete()
        File(tasks, "plan.md").delete()
        files.syncTaskCopies(emptyMap())
        assertFalse(tasks.exists())

        // An Orbit folder deleted meanwhile is not made again.
        val gone = File(folder.root, "gone")
        assertFalse(OrbitFiles(gone).syncTaskCopies(mapOf("a.md" to copy("a"))))
        assertFalse(gone.exists())
    }

    @Test fun onePieceOfATextIsReplacedLikeEditFile() {
        assertEquals("每天 9 点看邮件", OrbitTaskTools.replaceOnce("每天 8 点看邮件", "8 点", "9 点", "instruction"))
        assertEquals("看邮件", OrbitTaskTools.replaceOnce("看邮件，顺便看天气", "，顺便看天气", "", "instruction"))
        val missing = runCatching { OrbitTaskTools.replaceOnce("看邮件", "看短信", "x", "instruction") }.exceptionOrNull()
        assertTrue(missing?.message.orEmpty().contains("instruction_old was not found"))
        val twice = runCatching { OrbitTaskTools.replaceOnce("a b a", "a", "c", "script") }.exceptionOrNull()
        assertTrue(twice?.message.orEmpty().contains("script_old appears more than once"))
        val overlapping = runCatching { OrbitTaskTools.replaceOnce("aaa", "aa", "b", "script") }.exceptionOrNull()
        assertNotNull(overlapping)
        assertNotNull(runCatching { OrbitTaskTools.replaceOnce("a", "", "b", "script") }.exceptionOrNull())
    }
}
