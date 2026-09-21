package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ownership lives outside importable skill archives, so an imported file cannot opt into auto-editing. */
class LearnedSkillRepository(private val context: Context) {
    companion object {
        private val mutex = Mutex()
        fun version(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    data class Snapshot(val text: String, val version: String, val exists: Boolean)
    private val registry = File(context.filesDir, "learned_skill_ownership.json")
    private val manager get() = SkillManager.getInstance(context)
    private fun registry() = if (registry.isFile) JSONObject(registry.readText()) else JSONObject()
    private fun atomic(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile(".learning-", ".tmp", file.parentFile)
        try {
            tmp.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
    suspend fun owned(profileId: String? = null): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = registry()
            data.keys().asSequence().filter { (profileId == null || data.getJSONObject(it).getString("profile")==profileId)
                && manager.getAvailableSkills()[it]?.directory?.canonicalPath ==
                    data.getJSONObject(it).optString("directory") }.toSet()
        }
    }
    suspend fun register(name: String, profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = registry()
            val skill = manager.getAvailableSkills()[name] ?: error("Skill not installed")
            require(skill.directory.canonicalFile.parentFile == File(manager.getSkillsDirectoryPath()).canonicalFile)
            require(!data.has(name) || data.getJSONObject(name).getString("profile")==profileId)
            data.put(name,JSONObject().put("profile",profileId).put("directory",skill.directory.canonicalPath))
            atomic(registry,data.toString())
        }
    }
    suspend fun forget(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = registry()
            if(data.has(name)) { data.remove(name); atomic(registry,data.toString()) }
        }
    }
    private fun target(name: String, path: String): File {
        require(path=="SKILL.md" || Regex("(references|scripts|templates|assets)/[A-Za-z0-9_./-]+").matches(path))
        require(path.split('/').none { it==".." || it=="." || it.isEmpty() })
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
        val root = manager.getAvailableSkills()[name]?.directory?.canonicalFile ?: error("Skill not found")
        root.walkTopDown().maxDepth(5).filter { it.isFile && it.canonicalFile.toPath().startsWith(root.toPath()) }
            .take(100).map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private fun directoryManifest(name: String): String {
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
            require(body.length<=24_000)
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
