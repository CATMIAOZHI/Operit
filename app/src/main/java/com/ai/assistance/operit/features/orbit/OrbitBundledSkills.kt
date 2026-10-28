package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Orbit's preinstalled skills: messaging, scheduling, email, shopping, flights, hotels, transport,
 * voice messages, goals and trip planning. Each skill folder (SKILL.md and its reference files) is
 * copied into an Orbit's own skill folder in the prompt language, where Orbit finds it with
 * orbit_skills like any other.
 *
 * Later app versions update the files they installed, file by file: a file still as installed is
 * replaced, a file someone changed is left alone, a file someone deleted stays deleted, and a skill
 * someone deleted is not brought back. A skill that already existed under the same name is never touched.
 */
internal object OrbitBundledSkills {
    private const val ASSET_ROOT = "orbit_skills"
    private const val MARKER = "bundled_skills.json"
    val NAMES = listOf("messaging", "scheduling", "email-management", "shopping", "flight-booking",
        "hotel-booking", "trip-transport", "spoken-audio", "goals", "trip-planning")
    private val mutex = Mutex()

    /**
     * What was installed for one skill: the hash of each file as written; [ours] is false for a
     * same-named skill found there. [pending] is set just before the folder is moved into place and
     * cleared after, so an install cut off in between is sorted out on the next run.
     */
    private class Entry(val ours: Boolean, val files: MutableMap<String, String>, val pending: Boolean = false)

    /** Stands for a file installed before hashes were kept; it counts as unchanged. */
    private const val UNKNOWN = "unknown"
    /** A local file larger than this is not read to compare; it counts as changed and is left alone. */
    private const val MAX_COMPARE = 1_000_000L

    /**
     * The files of each preinstalled skill still exactly as the app installed them (by hash), so
     * a forget scan can skip text that holds nothing about the user. A skill not installed by the
     * app (one of the same name found there) has no entry.
     */
    suspend fun installedHashes(context: Context, orbitId: String): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val marker = File(OrbitPreferences.getInstance(context).orbitDir(orbitId), MARKER)
        read(runCatching { marker.readText() }.getOrNull()).first
            .filterValues { it.ours }.mapValues { (_, entry) -> entry.files.filterValues { it != UNKNOWN } }
    }

    fun hashOf(bytes: ByteArray) = hash(bytes)

    suspend fun ensureInstalled(context: Context, orbitId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val orbit = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId }
                ?: return@withContext
            val marker = File(OrbitPreferences.getInstance(context).orbitDir(orbitId), MARKER)
            val language = if (LocaleUtils.usesEnglishPrompt(context.applicationContext)) "en" else "zh"
            // A new install of the app or another language may bring new files; otherwise nothing to do.
            val installedAt = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val fingerprint = "$installedAt:$language"
            val saved = runCatching { marker.readText() }.getOrNull()
            val (state, lastFingerprint) = read(saved)
            if (lastFingerprint == fingerprint && NAMES.all { it in state }) return@withContext

            val manager = SkillManager.forMemorySpace(context, OrbitMemoryScope.ensureInitialized(context, orbit))
            val skillsRoot = File(manager.getSkillsDirectoryPath())
            check(skillsRoot.mkdirs() || skillsRoot.isDirectory) { "No skills folder" }
            // A half-copied folder from an interrupted install must not be scanned as a skill.
            skillsRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(".") && it.name.endsWith(".tmp") }
                ?.forEach { it.deleteRecursively() }
            var changed = false
            NAMES.forEach { name ->
                val bundle = assetFiles(context, "$ASSET_ROOT/$language/$name")
                if (bundle.isEmpty()) return@forEach
                val target = File(skillsRoot, name)
                // An install cut off between moving the folder and recording it: decide from what is there.
                val entry = state[name]?.let { saved ->
                    if (!saved.pending) return@let saved
                    when {
                        !target.exists() -> null
                        target.isDirectory && saved.files.all { (path, sum) ->
                            File(target, path).let { it.isFile && it.length() <= MAX_COMPARE && hash(it.readBytes()) == sum }
                        } -> Entry(true, saved.files)
                        else -> Entry(false, mutableMapOf())
                    }.also { if (it == null) state.remove(name) else state[name] = it }
                }
                when {
                    entry == null && target.exists() -> state[name] = Entry(false, mutableMapOf())
                    entry == null -> {
                        val temporary = File(skillsRoot, ".$name.tmp")
                        temporary.deleteRecursively()
                        check(temporary.mkdirs())
                        val files = mutableMapOf<String, String>()
                        bundle.forEach { (path, bytes) ->
                            File(temporary, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                            files[path] = hash(bytes)
                        }
                        // Recorded before and after the move, so an interruption anywhere leaves the folder known.
                        state[name] = Entry(true, files, pending = true)
                        write(marker, json(state, lastFingerprint).toByteArray())
                        check(temporary.renameTo(target)) { "Could not install $name" }
                        state[name] = Entry(true, files)
                        write(marker, json(state, lastFingerprint).toByteArray())
                        changed = true
                    }
                    // Someone else's skill, or one the user or Orbit deleted: left as it is.
                    !entry.ours || !target.isDirectory -> Unit
                    else -> bundle.forEach { (path, bytes) ->
                        val file = File(target, path)
                        val fresh = hash(bytes)
                        val written = entry.files[path]
                        val current = when {
                            !file.isFile -> null
                            file.length() > MAX_COMPARE -> "too_large"
                            else -> hash(file.readBytes())
                        }
                        val replace = when {
                            current == fresh -> false
                            // New in this version: added unless a file of the same name is already there.
                            written == null -> current == null
                            // Deleted after it was installed: it stays deleted.
                            current == null -> false
                            // Still as installed: updated. Changed by someone: left alone.
                            else -> written == UNKNOWN || written == current
                        }
                        if (replace) {
                            write(file, bytes)
                            changed = true
                        }
                        if (replace || current == fresh) entry.files[path] = fresh
                    }
                }
            }
            marker.parentFile?.mkdirs()
            write(marker, json(state, fingerprint).toByteArray())
            if (changed) manager.refreshAvailableSkills()
        }
    }

    /** Every file under asset folder [root], by path relative to it. */
    private fun assetFiles(context: Context, root: String): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        fun walk(relative: String) {
            val path = if (relative.isEmpty()) root else "$root/$relative"
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                if (relative.isNotEmpty()) out[relative] = context.assets.open(path).use { it.readBytes() }
            } else {
                children.forEach { walk(if (relative.isEmpty()) it else "$relative/$it") }
            }
        }
        walk("")
        return out
    }

    private fun read(text: String?): Pair<MutableMap<String, Entry>, String> {
        val state = mutableMapOf<String, Entry>()
        if (text.isNullOrBlank()) return state to ""
        // The first versions kept only the names installed, each with just its SKILL.md.
        runCatching { JSONArray(text) }.getOrNull()?.let { names ->
            (0 until names.length()).forEach { state[names.getString(it)] = Entry(true, mutableMapOf("SKILL.md" to UNKNOWN)) }
            return state to ""
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return state to ""
        val skills = root.optJSONObject("skills") ?: JSONObject()
        skills.keys().forEach { name ->
            val value = skills.getJSONObject(name)
            val files = value.optJSONObject("files") ?: JSONObject()
            state[name] = Entry(value.optBoolean("ours", true),
                files.keys().asSequence().associateWith { files.getString(it) }.toMutableMap(),
                value.optBoolean("pending", false))
        }
        return state to root.optString("fingerprint")
    }

    private fun json(state: Map<String, Entry>, fingerprint: String): String =
        JSONObject().put("fingerprint", fingerprint).put("skills", JSONObject().apply {
            state.forEach { (name, entry) ->
                put(name, JSONObject().put("ours", entry.ours).put("files", JSONObject(entry.files as Map<*, *>))
                    .apply { if (entry.pending) put("pending", true) })
            }
        }).toString()

    private fun write(file: File, bytes: ByteArray) {
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temporary = File.createTempFile(".skill-", ".tmp", parent)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
