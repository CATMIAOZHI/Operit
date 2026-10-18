package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.LocaleUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An Orbit's own system files, kept in its folder next to Soul.md:
 *
 * - PROMPT.md: how Orbit works. Missing means the built-in default in the app's language, so app
 *   updates still reach it until the user edits it.
 * - IDENTITY.md: Orbit's name, character, vibe and signature emoji, written by Orbit and the user.
 *
 * Every layer always goes into the system prompt; none of them can be left out.
 * Work files, chats and the memory library stay where they are; only these describe who Orbit is.
 */
object OrbitPromptFiles {
    const val PROMPT_FILE = "PROMPT.md"
    const val IDENTITY_FILE = "IDENTITY.md"
    const val MAX_CHARS = 24_000

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun dir(context: Context, orbitId: String): File =
        OrbitPreferences.getInstance(context).orbitDir(orbitId)

    private fun lock(file: File) = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    private fun useEnglish(context: Context) =
        LocaleUtils.usesEnglishPrompt(context)

    private fun atomic(file: File, content: String) {
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temp = File.createTempFile(".orbit-", ".tmp", parent)
        try {
            temp.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        lock(file).withLock { file.takeIf { it.isFile }?.readText() }
    }

    private suspend fun write(file: File, content: String) = withContext(Dispatchers.IO) {
        require(content.length <= MAX_CHARS) { "At most $MAX_CHARS characters" }
        lock(file).withLock { atomic(file, content) }
    }

    fun defaultPrompt(context: Context): String =
        if (useEnglish(context)) OrbitPromptDefaults.PROMPT_EN else OrbitPromptDefaults.PROMPT_ZH

    /** PROMPT.md as Orbit reads it: the user's copy, or the built-in default. */
    suspend fun prompt(context: Context, orbitId: String): String =
        read(File(dir(context, orbitId), PROMPT_FILE))?.takeIf { it.isNotBlank() } ?: defaultPrompt(context)

    suspend fun isPromptCustom(context: Context, orbitId: String): Boolean =
        read(File(dir(context, orbitId), PROMPT_FILE))?.isNotBlank() == true

    suspend fun savePrompt(context: Context, orbitId: String, body: String) {
        val file = File(dir(context, orbitId), PROMPT_FILE)
        // Saving the default unchanged keeps following app updates.
        if (body.trim() == defaultPrompt(context).trim()) resetPrompt(context, orbitId)
        else write(file, body)
    }

    suspend fun resetPrompt(context: Context, orbitId: String) = withContext(Dispatchers.IO) {
        val file = File(dir(context, orbitId), PROMPT_FILE)
        lock(file).withLock { file.delete(); Unit }
    }

    fun identityTemplate(context: Context, name: String): String =
        (if (useEnglish(context)) OrbitPromptDefaults.IDENTITY_EN else OrbitPromptDefaults.IDENTITY_ZH)
            .replace("{name}", name.ifBlank { "Orbit" })

    /** IDENTITY.md, made from the template the first time it is read. */
    suspend fun identity(context: Context, orbitId: String): String {
        val file = File(dir(context, orbitId), IDENTITY_FILE)
        read(file)?.let { return it }
        val name = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId }?.name.orEmpty()
        val template = identityTemplate(context, name)
        withContext(Dispatchers.IO) { lock(file).withLock { if (!file.isFile) atomic(file, template) } }
        return read(file) ?: template
    }

    suspend fun saveIdentity(context: Context, orbitId: String, body: String) {
        require(body.isNotBlank()) { "IDENTITY.md cannot be empty" }
        write(File(dir(context, orbitId), IDENTITY_FILE), body)
    }

    /** Keeps the name line in IDENTITY.md in step with the name Orbit picked for itself. */
    suspend fun syncIdentityName(context: Context, orbitId: String, name: String) {
        identity(context, orbitId) // Makes the file from the template if it is missing.
        val file = File(dir(context, orbitId), IDENTITY_FILE)
        val pattern = Regex("""(?m)^(\s*[-*]\s*(?:\*\*)?(?:名字|Name)(?:\*\*)?\s*[:：]\s*).*$""")
        // Read and write under one lock so a save from the editor is not lost.
        withContext(Dispatchers.IO) {
            lock(file).withLock {
                val current = file.takeIf { it.isFile }?.readText() ?: return@withLock
                val match = pattern.find(current) ?: return@withLock
                val updated = current.replaceRange(match.range, match.groupValues[1] + name)
                if (updated != current) atomic(file, updated)
            }
        }
    }

    /** Changes whenever what these files put into the system prompt changes. */
    suspend fun revision(context: Context, orbitId: String): String =
        LearnedSkillRepository.version(prompt(context, orbitId) + "\u0000" + identity(context, orbitId)).take(16)
}
