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

    suspend fun export(context: Context, orbit: OrbitProfile): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, FOLDER)
        outDir.listFiles()?.forEach { it.delete() }
        check(outDir.mkdirs() || outDir.isDirectory)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val safeName = orbit.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifEmpty { "Orbit" }.take(40)
        val zip = File(outDir, "Orbit-$safeName-$stamp.zip")
        val dao = OrbitTaskRepository(context).dao
        val tasks = dao.tasks(orbit.id)
        val goals = dao.goals(orbit.id)
        val activity = dao.observeActivity(orbit.id).first()
        suspend fun load(chatId: String) = try {
            ChatHistoryManager.getInstance(context).loadChatMessages(chatId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val messages = load(orbit.chatId)
        val sideChats = orbit.sideChats.map { it to load(it.chatId) }
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
            fun chatMarkdown(title: String, list: List<com.ai.assistance.operit.data.model.ChatMessage>) = buildString {
                append("# $title\n\n")
                list.forEach { m ->
                    val who = if (m.sender == "user") "User" else orbit.name
                    append("### $who · ${time(m.timestamp)}\n\n")
                    append(ChatUtils.removeThinkingContent(m.content).trim()).append("\n\n")
                }
            }
            put("chat.md", chatMarkdown(orbit.name, messages))
            sideChats.forEachIndexed { index, (side, list) ->
                val name = side.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifEmpty { "chat" }.take(40)
                put("side-chats/${index + 1}-$name.md", chatMarkdown("${orbit.name} · ${side.title}", list))
            }
        }
        zip
    }

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
        .put("lastObservation", task.lastObservation).put("goalId", task.goalId)
        .put("createdAt", time(task.createdAt)).put("updatedAt", time(task.updatedAt))

    private fun goalJson(goal: OrbitGoalEntity) = JSONObject().put("id", goal.id).put("title", goal.title)
        .put("plan", goal.plan).put("state", goal.state)
        .put("createdAt", time(goal.createdAt)).put("updatedAt", time(goal.updatedAt))
}
