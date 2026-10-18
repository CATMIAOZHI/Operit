package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ownership lives outside importable skill archives, so an imported file cannot opt into auto-editing. */
class LearnedSkillRepository(private val context: Context, private val memorySpaceId: String? = null) {
    companion object {
        private val mutex = Mutex()
        private const val LAST_USED = "lastUsed"
        private const val ARCHIVED_AT = "archivedAt"
        /** A learned skill nobody loaded for this long is archived: hidden from the AI, kept on disk. */
        const val IDLE_ARCHIVE_MS = 28L * 24 * 60 * 60 * 1000
        /** Archiving counts in weeks, so a use only needs writing down a few times a day. */
        private const val TOUCH_EVERY_MS = 6L * 60 * 60 * 1000
        private val lastTouchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val touchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /**
         * Records that [name] was loaded, without making the load wait for the disk. Skills that are
         * not learned in [memorySpaceId] are left alone by [touch].
         */
        fun touchLater(context: Context, memorySpaceId: String, name: String) {
            val key = "$memorySpaceId/$name"
            val now = System.currentTimeMillis()
            val last = lastTouchAt[key]
            if (last != null && now - last < TOUCH_EVERY_MS) return
            lastTouchAt[key] = now
            val appContext = context.applicationContext
            touchScope.launch {
                try {
                    LearnedSkillRepository(appContext, memorySpaceId).touch(name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastTouchAt.remove(key)
                    com.ai.assistance.operit.util.AppLogger.w("LearnedSkillRepository", "Could not record skill use: $name", e)
                }
            }
        }
        /** Creation bounds for a new skill: short enough to stay scannable and cheap to read. */
        const val MIN_SKILL_BODY_CHARS = 50
        const val MAX_SKILL_BODY_CHARS = 6_000
        /** One-line description; longer lines are accepted but cost every session that loads the index. */
        const val MAX_SKILL_DESCRIPTION_CHARS = 240
        /** Hard ceiling for any single skill file, including a whole-file SKILL.md rewrite. */
        const val MAX_SKILL_FILE_CHARS = 24_000
        internal fun validatePath(path: String) {
            require(path=="SKILL.md" || Regex("(references|scripts|templates|assets)/[A-Za-z0-9_./-]+").matches(path)) {
                "Use SKILL.md or a file under references/, scripts/, templates/ or assets/"
            }
            require(path.split('/').none { it==".." || it=="." || it.isEmpty() }) { "Invalid skill file path" }
        }
        fun version(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    data class Snapshot(val text: String, val version: String, val exists: Boolean)
    private suspend fun manager() = memorySpaceId?.let { SkillManager.forMemorySpace(context, it) }
        ?: SkillManager.getInstance(context)
    private suspend fun registryFile(): File {
        val manager = manager()
        return if (memorySpaceId != null &&
            com.ai.assistance.operit.features.orbit.OrbitMemoryScope.privateOwner(context, memorySpaceId) != null)
            File(File(manager.getSkillsDirectoryPath()).parentFile, "learned_skill_ownership.json")
        else File(context.filesDir, "learned_skill_ownership.json")
    }
    private suspend fun registry() = registryFile().let { if (it.isFile) JSONObject(it.readText()) else JSONObject() }
    private fun atomic(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile(".learning-", ".tmp", file.parentFile)
        try {
            tmp.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        syncDirectory(file.parentFile!!)
    }
    suspend fun owned(profileId: String? = null): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manager = manager()
            val data = registry()
            data.keys().asSequence().filter { (profileId == null || data.getJSONObject(it).getString("profile")==profileId)
                && manager.getAvailableSkills()[it]?.directory?.canonicalPath ==
                    data.getJSONObject(it).optString("directory") }.toSet()
        }
    }
    suspend fun register(name: String, profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manager = manager()
            val registry = registryFile()
            val data = registry()
            val skill = manager.getAvailableSkills()[name] ?: error("Skill not installed")
            require(skill.directory.canonicalFile.parentFile == File(manager.getSkillsDirectoryPath()).canonicalFile)
            require(!data.has(name) || data.getJSONObject(name).getString("profile")==profileId)
            data.put(name,JSONObject().put("profile",profileId).put("directory",skill.directory.canonicalPath)
                .put(LAST_USED,System.currentTimeMillis()))
            atomic(registry,data.toString())
        }
    }

    /**
     * A learned skill was loaded: its idle clock starts again. Other skills are not tracked. An
     * archived skill stays archived (only [unarchive] brings it back), so a load racing the sweep
     * cannot leave a skill hidden from the AI yet missing from the archive folder.
     */
    suspend fun touch(name: String) = mark(name, unarchive = false)

    private suspend fun mark(name: String, unarchive: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = registryFile()
            val data = registry()
            val entry = data.optJSONObject(name) ?: return@withLock
            entry.put(LAST_USED, System.currentTimeMillis())
            if (unarchive) entry.remove(ARCHIVED_AT)
            atomic(registry, data.toString())
        }
    }

    /** Learned skills that were put away for not being used. */
    suspend fun archived(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = registry()
            data.keys().asSequence().filter { data.optJSONObject(it)?.has(ARCHIVED_AT) == true }.toSet()
        }
    }

    /** The user switched an archived skill back on: it counts as just used. */
    suspend fun unarchive(name: String) = mark(name, unarchive = true)

    /**
     * Marks the learned skills of [profileId] that went [idleMs] without being loaded as archived
     * and returns them; the caller hides them from the AI. The clock only runs while a skill is
     * shown to the AI: one the user hid starts again when shown, and an archived one the user
     * switched back on counts as used. Entries from before the clock existed start it now.
     */
    suspend fun archiveIdle(profileId: String, now: Long, idleMs: Long, isVisible: (String) -> Boolean): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val manager = manager()
                val registry = registryFile()
                val data = registry()
                val installed = manager.getAvailableSkills()
                val archived = mutableListOf<String>()
                var changed = false
                data.keys().asSequence().toList().forEach { name ->
                    val entry = data.optJSONObject(name) ?: return@forEach
                    if (entry.optString("profile") != profileId ||
                        installed[name]?.directory?.canonicalPath != entry.optString("directory")) return@forEach
                    val visible = isVisible(name)
                    // A load whose write is still on its way counts as use, so it is never archived.
                    val pendingUse = memorySpaceId?.let { lastTouchAt["$it/$name"] }
                        ?.takeIf { it > entry.optLong(LAST_USED, 0L) }
                    when {
                        entry.has(ARCHIVED_AT) -> if (visible) {
                            entry.remove(ARCHIVED_AT); entry.put(LAST_USED, now); changed = true
                        }
                        !visible || entry.optLong(LAST_USED, 0L) <= 0L -> {
                            entry.put(LAST_USED, now); changed = true
                        }
                        pendingUse != null -> {
                            entry.put(LAST_USED, pendingUse); changed = true
                        }
                        now - entry.getLong(LAST_USED) >= idleMs -> {
                            entry.put(ARCHIVED_AT, now); archived += name; changed = true
                        }
                    }
                }
                if (changed) atomic(registry, data.toString())
                archived
            }
        }
    suspend fun forget(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = registryFile()
            val data = registry()
            if(data.has(name)) { data.remove(name); atomic(registry,data.toString()) }
        }
    }
    private suspend fun target(name: String, path: String): File {
        val manager = manager()
        validatePath(path)
        val skill = manager.getAvailableSkills()[name] ?: error("Skill not found")
        val root = skill.directory.canonicalFile
        val file = File(root,path).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root)
        return file
    }
    suspend fun read(name: String, path: String = "SKILL.md"): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = target(name,path)
            require(!file.exists() || file.isFile)
            require(!file.exists() || file.length() <= 256_000)
            val text = if(file.exists()) file.readText() else ""
            Snapshot(text,version(if(file.exists()) "present:$text" else "absent"),file.exists())
        }
    }
    suspend fun files(name: String): List<String> = withContext(Dispatchers.IO) {
        val manager = manager()
        val root = manager.getAvailableSkills()[name]?.directory?.canonicalFile ?: error("Skill not found")
        root.walkTopDown().maxDepth(5).filter { it.isFile && it.canonicalFile.toPath().startsWith(root.toPath()) }
            .take(100).map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private suspend fun directoryManifest(name: String): String {
        val manager = manager()
        val root = manager.getAvailableSkills()[name]?.directory?.canonicalFile ?: error("Skill not found")
        val files = root.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) }
            .filter { it.isFile }.take(1001).toList()
        require(files.size<=1000)
        return files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString("\n") { file ->
            require(file.canonicalFile.toPath().startsWith(root.toPath()) && file.length()<=16_000_000)
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
            }
            file.relativeTo(root).invariantSeparatorsPath+":"+digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    suspend fun readDirectory(name: String): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock { directoryManifest(name).let { Snapshot(it,version(it),true) } }
    }
    suspend fun delete(name: String, expectedVersion: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manager = manager()
            val registry = registryFile()
            val skill=manager.getAvailableSkills()[name]
            if (skill==null) {
                val data=registry(); data.remove(name); atomic(registry,data.toString())
                return@withLock
            }
            require(skill.directory.canonicalFile.parentFile==File(manager.getSkillsDirectoryPath()).canonicalFile)
            check(version(directoryManifest(name))==expectedVersion) { "Skill changed since deletion was proposed" }
            check(manager.deleteSkill(name,clearLearningOwnership=false)) { "Could not delete skill" }
            val data=registry(); data.remove(name); atomic(registry,data.toString())
        }
    }
    suspend fun apply(profileId: String, name: String, path: String, body: String, expectedVersion: String,
        remove: Boolean = false, automatic: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manager = manager()
            val file = target(name,path)
            val skill = manager.getAvailableSkills()[name] ?: error("Skill not found")
            require(skill.directory.canonicalFile.parentFile == File(manager.getSkillsDirectoryPath()).canonicalFile) {
                "Legacy skill directories are read only"
            }
            if (automatic) {
                val owner = registry().optJSONObject(name)
                check(owner?.optString("profile")==profileId && owner?.optString("directory")==skill.directory.canonicalPath) {
                    "Only skills learned in this space may be revised automatically"
                }
            }
            val current = if(file.exists()) file.readText() else ""
            val actualVersion = version(if(file.exists()) "present:$current" else "absent")
            // Completed journal retries are harmless; a different concurrent edit must be preserved.
            if ((!remove && file.exists() && current==body) || (remove && !file.exists())) return@withLock
            check(actualVersion==expectedVersion) { "Skill changed since it was read; reload before editing" }
            require(body.length<=MAX_SKILL_FILE_CHARS)
            if (!remove && path=="SKILL.md") {
                val normalized = body.replace("\r\n","\n")
                val metadata = manager.parseSkillMetadataText(body)
                require(normalized.startsWith("---\n") && normalized.indexOf("\n---",4)>0 &&
                    metadata.first==name && metadata.second.isNotBlank()) {
                    "Keep the existing skill name and YAML frontmatter"
                }
            }
            require(!remove || path!="SKILL.md") { "Delete the skill explicitly instead of removing SKILL.md" }
            if (remove) Files.deleteIfExists(file.toPath()) else atomic(file,body)
            manager.refreshAvailableSkills()
        }
    }
}
