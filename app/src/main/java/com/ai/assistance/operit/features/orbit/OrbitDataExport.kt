package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.model.OrbitGoalEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip with everything an Orbit keeps: its own folder (Soul, identity, memory, notes, skills,
 * files, boards), its tasks, goals and run history, and its chat as Markdown. Like Muse's data
 * export, the user gets it to keep or move elsewhere. The trash and the app's temporary files stay out.
 */
internal object OrbitDataExport {
    private const val FOLDER = "orbit_export"
    // Every export shares one output folder, so only one runs at a time, app-wide.
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Whether an export started with [start] is running, for every export button to show. */
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState

    /**
     * Exports outside any screen, so scrolling a card away does not stop it; [onDone] gets the zip,
     * or null when it failed. False when an export is already running.
     */
    fun start(context: Context, orbit: OrbitProfile, onDone: (File?) -> Unit): Boolean {
        if (!runningState.compareAndSet(false, true)) return false
        val app = context.applicationContext
        scope.launch {
            val zip = try {
                export(app, orbit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                runningState.value = false
            }
            onDone(zip)
        }
        return true
    }

    suspend fun export(context: Context, orbit: OrbitProfile): File = lock.withLock { withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, FOLDER)
        // An earlier zip may still be read by the app it was shared to; only old ones go.
        val cutoff = System.currentTimeMillis() - 60 * 60_000L
        outDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        check(outDir.mkdirs() || outDir.isDirectory)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val safeName = orbit.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifEmpty { "Orbit" }.take(40)
        val zip = File(outDir, "Orbit-$safeName-$stamp.zip")
        val dao = OrbitTaskRepository(context).dao
        val tasks = dao.tasks(orbit.id)
        val goals = dao.goals(orbit.id)
        val activity = dao.observeActivity(orbit.id).first()
        val history = ChatHistoryManager.getInstance(context)
        val root = OrbitPreferences.getInstance(context).orbitDir(orbit.id)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            put("README.txt", readme(orbit))
            if (root.isDirectory) {
                val base = root.toPath().toRealPath()
                root.walkTopDown()
                    .onEnter { dir -> dir == root || (!dir.name.startsWith(".") && !Files.isSymbolicLink(dir.toPath())) }
                    .filter { it.isFile && !it.name.startsWith(".") && !Files.isSymbolicLink(it.toPath()) }
                    .filter { runCatching { it.toPath().toRealPath().startsWith(base) }.getOrDefault(false) }
                    .forEach { file ->
                        out.putNextEntry(ZipEntry("orbit/" + file.relativeTo(root).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
            put("records/tasks.json", JSONArray(tasks.map { taskJson(context, it) }).toString(2))
            put("records/goals.json", JSONArray(goals.map(::goalJson)).toString(2))
            put("records/activity.json", JSONArray(activity.map {
                JSONObject().put("task", it.title).put("kind", it.kind).put("state", it.state)
                    .put("observation", it.observation).put("error", it.error)
                    .put("startedAt", time(it.startedAt)).put("finishedAt", time(it.finishedAt))
            }).toString(2))
            // A chat is read a page at a time and written as it goes, so a long history never sits in memory whole.
            suspend fun chatMarkdown(name: String, title: String, chatId: String) {
                out.putNextEntry(ZipEntry(name))
                out.write("# $title\n\n".toByteArray(Charsets.UTF_8))
                var after = Long.MIN_VALUE
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val page = try {
                        history.loadChatMessagesAscAfter(chatId, after, EXPORT_PAGE)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    page.forEach { m ->
                        val who = if (m.sender == "user") "User" else orbit.name
                        out.write("### $who · ${time(m.timestamp)}\n\n${ChatUtils.removeThinkingContent(m.content).trim()}\n\n"
                            .toByteArray(Charsets.UTF_8))
                    }
                    if (page.size < EXPORT_PAGE) break
                    after = page.last().timestamp
                }
                out.closeEntry()
            }
            chatMarkdown("chat.md", orbit.name, orbit.chatId)
            orbit.sideChats.forEachIndexed { index, side ->
                val name = side.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifEmpty { "chat" }.take(40)
                chatMarkdown("side-chats/${index + 1}-$name.md", "${orbit.name} · ${side.title}", side.chatId)
            }
        }
        zip
    } }

    private const val EXPORT_PAGE = 200

    private fun time(millis: Long) =
        if (millis <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private fun readme(orbit: OrbitProfile) = """
        Orbit export: ${orbit.name}
        Exported ${time(System.currentTimeMillis())}

        orbit/      Orbit's own folder: SOUL.md, IDENTITY.md, user.md, memory/, skills/, files/ and its boards
        records/    tasks, goals and the history of background runs
        chat.md     the Orbit chat
        side-chats/ Orbit's side chats, one file each
    """.trimIndent() + "\n"

    private fun taskJson(context: Context, task: OrbitTaskEntity) = JSONObject().put("id", task.id).put("title", task.title)
        .put("instruction", OrbitRoutines.runningInstruction(context, task)).put("kind", task.kind).put("source", task.source)
        .put("schedule", task.scheduleMode).put("state", task.state).put("notify", task.notifyPolicy)
        .put("model", task.modelRole).put("effectiveModelRole", OrbitModels.taskRole(task))
        .put("lastObservation", task.lastObservation).put("goalId", task.goalId)
        .put("createdAt", time(task.createdAt)).put("updatedAt", time(task.updatedAt))

    private fun goalJson(goal: OrbitGoalEntity) = JSONObject().put("id", goal.id).put("title", goal.title)
        .put("plan", goal.plan).put("state", goal.state)
        .put("createdAt", time(goal.createdAt)).put("updatedAt", time(goal.updatedAt))
}
