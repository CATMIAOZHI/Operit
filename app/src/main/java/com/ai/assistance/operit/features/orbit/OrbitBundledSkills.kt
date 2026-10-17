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
import java.io.File

/**
 * Muse's preinstalled skills, rewritten for Orbit: messaging, scheduling, email, shopping, flights,
 * hotels, transport and voice messages. Each is copied once into an Orbit's own skill folder, in the
 * prompt language, where Orbit finds it with orbit_skills like any other. A skill the user or Orbit
 * deleted is not brought back; one that already exists under the same name is left alone.
 */
internal object OrbitBundledSkills {
    private const val ASSET_ROOT = "orbit_skills"
    val NAMES = listOf("messaging", "scheduling", "email-management", "shopping", "flight-booking",
        "hotel-booking", "trip-transport", "spoken-audio")
    private val mutex = Mutex()

    suspend fun ensureInstalled(context: Context, orbitId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val marker = File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "bundled_skills.json")
            val done = runCatching { JSONArray(marker.readText()) }.getOrNull()
                ?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf()
            val missing = NAMES.filterNot { it in done }
            if (missing.isEmpty()) return@withContext
            val manager = SkillManager.forMemorySpace(context, OrbitMemoryScope.ensureInitialized(context,
                OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId } ?: return@withContext))
            val skillsRoot = File(manager.getSkillsDirectoryPath())
            // A half-copied folder from an interrupted install must not be scanned as a skill.
            skillsRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(".") && it.name.endsWith(".tmp") }
                ?.forEach { it.deleteRecursively() }
            val language = if (LocaleUtils.usesEnglishPrompt(context.applicationContext)) "en" else "zh"
            missing.forEach { name ->
                val target = File(skillsRoot, name)
                if (!target.exists()) {
                    val text = context.assets.open("$ASSET_ROOT/$language/$name/SKILL.md").use { it.readBytes() }
                    val temporary = File(skillsRoot, ".$name.tmp")
                    temporary.deleteRecursively()
                    check(temporary.mkdirs())
                    File(temporary, "SKILL.md").writeBytes(text)
                    check(temporary.renameTo(target)) { "Could not install $name" }
                }
                done += name
            }
            marker.parentFile?.mkdirs()
            marker.writeText(JSONArray(done.toList()).toString())
            manager.refreshAvailableSkills()
        }
    }
}
