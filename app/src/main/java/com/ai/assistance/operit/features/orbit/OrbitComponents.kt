package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.text.format.Formatter
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.util.OnDemandResources
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's downloadable parts (the terminal environment, offline speech, local voices, export
 * templates) as Orbit sees them: what is there, and installing some once the user approved.
 * Downloads run in the background; Orbit hears how they went in a hidden note.
 */
internal object OrbitComponents {
    /** First line of the note that reports finished downloads; it names the turn in the work list. */
    const val MARKER = "[orbit-component]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 30 * 60 * 1000L
    /** Approved installs not yet reported to Orbit, kept on disk so a restart finishes them. */
    private const val PREFS = "orbit_components"
    private const val KEY_PENDING = "pending"
    private val pendingLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One install run at a time, so two approvals do not download the same parts side by side. */
    private val installs = Mutex()
    /** Parts queued or downloading for Orbit, so list can say so and a second request is not needed. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    data class Part(val id: String, val name: String, val bytes: Long, val installed: Boolean)

    fun parts(context: Context): List<Part> = OnDemandResources.componentGroups(context).map { group ->
        val name = LocalVoiceModels.models.firstOrNull { "voice-${it.id}" == group.id }?.name
            ?: context.getString(group.labelRes)
        Part(
            group.id, name, group.items.sumOf { it.resource.bytes },
            group.items.all { it.target.isFile && it.target.length() == it.resource.bytes },
        )
    }

    private fun what(id: String): String = when {
        id == OnDemandResources.terminalComponentId -> "Ubuntu terminal environment for the terminal tool (needed for running commands, Python, git and the like)"
        id.startsWith("voice-") -> "local voice model for reading replies aloud offline"
        id.contains("speech", ignoreCase = true) -> "offline speech recognition for voice input"
        else -> "template for exporting a web project as an Android or Windows app"
    }

    fun list(context: Context): JSONObject {
        val terminalSetUp = OnDemandResources.terminalInstalled(context)
        val items = JSONArray()
        parts(context).forEach { p ->
            items.put(JSONObject().put("id", p.id).put("name", p.name).put("what", what(p.id))
                .put("size", Formatter.formatShortFileSize(context, p.bytes))
                .put("state", when {
                    p.id in queued || OnDemandResources.isGroupActive(p.id) -> "downloading"
                    p.installed -> "installed"
                    p.id == OnDemandResources.terminalComponentId && terminalSetUp -> "installed"
                    else -> "not_installed"
                }))
        }
        return JSONObject().put("components", items)
            .put("note", "Use request_install with ids that are not_installed. The terminal is unpacked the first time you run a terminal command after it is downloaded (about a minute).")
    }

    fun action(ids: List<String>): String = JSONObject()
        .put("type", OrbitApprovalActions.INSTALL_COMPONENTS)
        .put("ids", JSONArray(ids))
        .toString()

    fun detail(context: Context, parts: List<Part>, reason: String): String = buildString {
        append(context.getString(com.ai.assistance.operit.R.string.orbit_component_detail_head))
        parts.forEach {
            append("\n").append(context.getString(com.ai.assistance.operit.R.string.orbit_component_detail_item,
                it.name, Formatter.formatShortFileSize(context, it.bytes)))
        }
        append("\n").append(context.getString(com.ai.assistance.operit.R.string.orbit_component_detail_total,
            Formatter.formatShortFileSize(context, parts.sumOf { it.bytes })))
        if (reason.isNotBlank()) append("\n\n").append(context.getString(com.ai.assistance.operit.R.string.orbit_approval_reason, reason.trim()))
    }

    /**
     * Starts downloading the approved parts in the background and says so at once; the downloads
     * themselves can take minutes, and the answer to the approval should not wait for them.
     */
    fun start(context: Context, orbit: OrbitProfile, chatId: String, action: JSONObject, english: Boolean): String {
        val app = context.applicationContext
        val ids = action.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val all = parts(app).associateBy { it.id }
        val missing = ids.filter { it !in all }
        val wanted = ids.mapNotNull { all[it] }.filter { !it.installed && it.id !in queued }
        val skipped = ids.mapNotNull { all[it] }.filter { it.installed || it.id in queued }
        wanted.forEach { queued += it.id }
        if (wanted.isNotEmpty()) {
            val entry = savePending(app, orbit.id, chatId, wanted.map { it.id })
            scope.launch { install(app, orbit, chatId, wanted, entry) }
        }
        val size = wanted.sumOf { it.bytes }
        return if (english) buildString {
            if (wanted.isNotEmpty()) append("Started downloading ${wanted.joinToString { it.name }} " +
                "(${Formatter.formatShortFileSize(app, size)} in all) in the background; the user sees the progress at the bottom of the screen. " +
                "You get a hidden note when it is done or if it fails.")
            if (skipped.isNotEmpty()) append(" Already installed or downloading: ${skipped.joinToString { it.name }}.")
            if (missing.isNotEmpty()) append(" Not found, so not installed: ${missing.joinToString()}.")
            if (wanted.isEmpty()) append(" Nothing new to download.")
        }.trim() else buildString {
            if (wanted.isNotEmpty()) append("已在后台开始下载 ${wanted.joinToString("、") { it.name }}" +
                "（共 ${Formatter.formatShortFileSize(app, size)}），用户能在屏幕底部看到进度。下载完或失败时你会收到隐藏消息。")
            if (skipped.isNotEmpty()) append("已经装好或正在下载：${skipped.joinToString("、") { it.name }}。")
            if (missing.isNotEmpty()) append("没找到，没有安装：${missing.joinToString("、")}。")
            if (wanted.isEmpty()) append("没有需要新下载的。")
        }
    }

    /**
     * Called once when the app starts: installs that were approved but cut off by the app closing
     * are started again, and Orbit is told the result as usual.
     */
    suspend fun restore(context: Context) {
        val app = context.applicationContext
        val entries = synchronized(pendingLock) { readPending(app) }
        if (entries.length() == 0) return
        val orbits = OrbitPreferences.getInstance(app).current().orbits
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val entry = e.optString("id")
            val orbit = orbits.firstOrNull { it.id == e.optString("orbit") }
            val ids = e.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val all = parts(app).associateBy { it.id }
            val wanted = ids.mapNotNull { all[it] }.filter { it.id !in queued }
            if (orbit == null || wanted.isEmpty()) { forget(app, entry); continue }
            wanted.forEach { queued += it.id }
            scope.launch { install(app, orbit, e.optString("chat"), wanted, entry) }
        }
    }

    private fun readPending(context: Context): JSONArray =
        runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, "[]")) }
            .getOrElse { JSONArray() }

    private fun writePending(context: Context, entries: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, entries.toString()).commit()
    }

    private fun savePending(context: Context, orbitId: String, chatId: String, ids: List<String>): String {
        val entry = java.util.UUID.randomUUID().toString()
        synchronized(pendingLock) {
            val entries = readPending(context)
            entries.put(JSONObject().put("id", entry).put("orbit", orbitId).put("chat", chatId).put("parts", JSONArray(ids)))
            writePending(context, entries)
        }
        return entry
    }

    private fun forget(context: Context, entry: String) {
        synchronized(pendingLock) {
            val entries = readPending(context)
            val kept = JSONArray()
            for (i in 0 until entries.length()) entries.optJSONObject(i)?.takeIf { it.optString("id") != entry }?.let { kept.put(it) }
            writePending(context, kept)
        }
    }

    private suspend fun install(context: Context, orbit: OrbitProfile, chatId: String, wanted: List<Part>, entry: String) {
        val done = mutableListOf<Part>()
        val failed = mutableListOf<Pair<Part, String>>()
        installs.withLock {
            for (part in wanted) {
                try {
                    // After a restart a part may already be complete; then only the report is left.
                    if (parts(context).firstOrNull { it.id == part.id }?.installed != true) {
                        OnDemandResources.downloadComponent(context, part.id)
                    }
                    done += part
                } catch (e: kotlinx.coroutines.CancellationException) {
                    failed += part to "cancelled"
                } catch (e: Exception) {
                    failed += part to (e.message ?: e.javaClass.simpleName).take(160)
                } finally {
                    queued -= part.id
                }
            }
        }
        if (OrbitPreferences.getInstance(context).current().orbits.none { it.id == orbit.id }) {
            forget(context, entry)
            return
        }
        val english = LocaleUtils.usesEnglishPrompt(context)
        val note = notice(english, done, failed, done.any { it.id == OnDemandResources.terminalComponentId })
        val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
        // Kept on disk until Orbit got the report; otherwise the next app start sends it again.
        var delivered = false
        try {
            while (true) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) break
                val told = OrbitWake.tell(context, orbit, chatId.ifBlank { orbit.chatId }, note, left, left)
                if (told != OrbitWake.Told.BUSY) { delivered = true; break }
                kotlinx.coroutines.delay(1_500)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("OrbitComponents", "Could not tell Orbit about the downloads: ${e.message}")
        }
        if (delivered) forget(context, entry)
    }

    private fun notice(english: Boolean, done: List<Part>, failed: List<Pair<Part, String>>, terminal: Boolean): String {
        val title = (done + failed.map { it.first }).joinToString(if (english) ", " else "、") { it.name }.take(80)
        return if (english) buildString {
            append("$MARKER Downloads finished: $title\n")
            append("The parts you asked to install have finished downloading. This is not a message from the user; they cannot see this note.\n")
            if (done.isNotEmpty()) append("Installed: ${done.joinToString { it.name }}.\n")
            failed.forEach { (p, why) -> append("Failed: ${p.name} ($why).\n") }
            if (terminal) append("The terminal is unpacked the first time a terminal command runs; that first command takes about a minute.\n")
            append("Tell the user in one short line how it went and carry on with what you needed it for. ")
            append("If something failed, say why plainly; you can ask again with orbit_components later. Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 组件下载结束：$title\n")
            append("你申请安装的组件下载结束了。这不是用户发的消息，用户也看不到这段话。\n")
            if (done.isNotEmpty()) append("已装好：${done.joinToString("、") { it.name }}。\n")
            failed.forEach { (p, why) -> append("失败：${p.name}（$why）。\n") }
            if (terminal) append("终端会在第一次运行终端命令时自动解压安装，第一条命令要等一分钟左右。\n")
            append("用一句话告诉用户结果，接着做你需要它的事。")
            append("失败的就直说原因，之后可以再用 orbit_components 申请。不要提“隐藏消息”这些内部说法。")
        }
    }
}
