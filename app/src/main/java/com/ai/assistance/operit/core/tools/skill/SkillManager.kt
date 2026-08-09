package com.ai.assistance.operit.core.tools.skill

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.LegacyStoragePreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitManagedPaths
import com.ai.assistance.operit.util.StorageSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking

class SkillManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"

        @Volatile private var INSTANCE: SkillManager? = null

        fun getInstance(context: Context): SkillManager {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: SkillManager(context.applicationContext).also { INSTANCE = it }
                }
        }
    }

    private val availableSkills = mutableMapOf<String, SkillPackage>()
    private val skillLoadErrors = mutableMapOf<String, String>()

    private val paths = OperitManagedPaths(context)
    private val legacyPrefs = LegacyStoragePreferences.getInstance(context)

    /**
     * App-internal primary skills root (`filesDir/operit/skills`). The only write target;
     * creating it on access is fine.
     */
    private fun getInternalSkillsRootDir(): File {
        return paths.internalSkills
    }

    /**
     * Legacy `Download/Operit/skills` compatibility source. NON-creating: must never call
     * mkdirs, so probing a missing Download tree cannot resurrect it. Only consulted while the
     * legacy read switch is on.
     */
    private fun getLegacySkillsRootDir(): File {
        return paths.legacySkills
    }

    fun getSkillsDirectoryPath(): String {
        return getInternalSkillsRootDir().absolutePath
    }

    fun refreshAvailableSkills() {
        availableSkills.clear()
        skillLoadErrors.clear()

        val internalSkillsDir = try {
            getInternalSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            skillLoadErrors[context.getString(R.string.skills)] =
                context.getString(R.string.skill_error_cannot_access_dir, e.message ?: "")
            return
        }

        if (!internalSkillsDir.exists() || !internalSkillsDir.isDirectory) {
            return
        }

        val seenNames = mutableSetOf<String>()

        // 1. Scan the internal primary store first (internal wins on name conflict).
        scanSkillDirectory(internalSkillsDir, seenNames, StorageSource.INTERNAL)

        // 2. Scan the legacy Download store only if the read switch is on; probing must never
        // create the legacy tree, so require it to already be a directory.
        val legacySkillsDir = getLegacySkillsRootDir()
        if (runBlocking { legacyPrefs.isReadLegacySkills() } && legacySkillsDir.isDirectory) {
            val hiddenPaths = runBlocking { legacyPrefs.hiddenLegacySkillPaths() }
            scanSkillDirectory(
                legacySkillsDir,
                seenNames,
                StorageSource.LEGACY_DOWNLOAD,
                skipPaths = hiddenPaths
            )
        }
    }

    private fun scanSkillDirectory(
        dir: File,
        seenNames: MutableSet<String>,
        source: StorageSource,
        skipPaths: Set<String> = emptySet()
    ) {
        val children = dir.listFiles() ?: emptyArray()
        for (child in children) {
            if (!child.isDirectory) continue

            // Hidden legacy skill (deleted in-app): skip without aborting the scan.
            if (child.name in skipPaths) continue

            val skillFile = File(child, "SKILL.md").let { primary ->
                if (primary.exists()) primary else File(child, "skill.md")
            }

            if (!skillFile.exists() || !skillFile.isFile) {
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_missing_skill_md,
                    child.absolutePath
                )
                continue
            }

            try {
                val (name, description) = parseSkillMetadata(skillFile)
                val skillName = name.ifBlank { child.name }
                val skillDesc = description.ifBlank { "" }

                if (skillName in seenNames) {
                    // Legacy duplicates of an already-loaded name are shadowed silently;
                    // duplicates within the internal store keep the conflict error.
                    if (!source.isLegacy()) {
                        val existingDirName = availableSkills[skillName]?.directory?.name ?: skillName
                        skillLoadErrors[child.name] = context.getString(
                            R.string.skill_error_duplicate_scanned_name,
                            skillName,
                            existingDirName
                        )
                    }
                    continue
                }

                availableSkills[skillName] = SkillPackage(
                    name = skillName,
                    description = skillDesc,
                    directory = child,
                    skillFile = skillFile,
                    storageSource = source
                )
                seenNames += skillName
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error loading skill from ${skillFile.absolutePath}", e)
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_scan_failed,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun parseSkillMetadata(skillFile: File): Pair<String, String> {
        val lines = skillFile.bufferedReader().use { it.readLines() }

        var name = ""
        var description = ""

        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (endIndex >= 0) {
                val frontmatter = lines.subList(1, endIndex + 1)
                frontmatter.forEach { lineRaw ->
                    val line = lineRaw.trim()
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    val value = unquote(line.substring(idx + 1).trim())
                    when (key.lowercase()) {
                        "name" -> if (name.isBlank()) name = value
                        "description" -> if (description.isBlank()) description = value
                    }
                }
            }
        }

        if (name.isBlank() || description.isBlank()) {
            lines.take(40).forEach { lineRaw ->
                val line = lineRaw.trim()
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = unquote(line.substring(idx + 1).trim())
                when (key.lowercase()) {
                    "name" -> if (name.isBlank()) name = value
                    "description" -> if (description.isBlank()) description = value
                }
            }
        }

        return Pair(name, description)
    }

    private fun unquote(valueRaw: String): String {
        var value = valueRaw
        if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
            if (value.length >= 2) value = value.substring(1, value.length - 1)
        }
        return value
    }

    fun getAvailableSkills(): Map<String, SkillPackage> {
        refreshAvailableSkills()
        return availableSkills.toMap()
    }

    fun getAvailableSkillsSnapshot(): Pair<Map<String, SkillPackage>, Map<String, String>> {
        refreshAvailableSkills()
        return availableSkills.toMap() to skillLoadErrors.toMap()
    }

    fun getSkillLoadErrors(): Map<String, String> {
        refreshAvailableSkills()
        return skillLoadErrors.toMap()
    }

    fun readSkillContent(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null
        return try {
            skill.skillFile.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read SKILL.md for $skillName", e)
            null
        }
    }

    fun deleteSkill(skillName: String): Boolean {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return false
        return try {
            val ok =
                if (skill.storageSource.isLegacy()) {
                    // Never delete the Download original; hide it by relative path so it does
                    // not reappear on the next scan.
                    runBlocking { legacyPrefs.hideLegacySkillPath(skill.directory.name) }
                    true
                } else {
                    // Internal entries shadow legacy entries by parsed metadata name. Probe the
                    // persisted legacy source regardless of the current switch and tombstone all
                    // matches before deleting the winner; otherwise a later refresh/re-enable
                    // would make the shadowed Download copy immediately reappear.
                    val shadowedLegacyPaths =
                        legacySkillDirectoryNamesMatching(
                            root = getLegacySkillsRootDir(),
                            skillName = skillName,
                            metadataName = { file -> parseSkillMetadata(file).first },
                        )
                    runBlocking {
                        shadowedLegacyPaths.forEach { legacyPrefs.hideLegacySkillPath(it) }
                    }
                    skill.directory.deleteRecursively()
                }
            if (ok) {
                availableSkills.remove(skillName)
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete skill $skillName", e)
            false
        }
    }

    fun getSkillSystemPrompt(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null
        val content = try {
            skill.skillFile.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read skill content: ${skill.skillFile.absolutePath}", e)
            ""
        }

        val sb = StringBuilder()
        sb.appendLine("Using package (Skill): ${skill.name}")
        sb.appendLine("Use Time: ${java.time.LocalDateTime.now()}")
        sb.appendLine("Execution policy:")
        sb.appendLine("Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.")
        if (skill.description.isNotBlank()) {
            sb.appendLine("Description: ${skill.description}")
        }
        sb.appendLine("SKILL.md path: ${skill.skillFile.absolutePath}")
        sb.appendLine("Skill directory: ${skill.directory.absolutePath}")
        if (skill.storageSource.isLegacy()) {
            sb.appendLine("Note: Skills under the Download compatibility directory are read-only; do not modify them.")
        }
        sb.appendLine("Directory structure:")
        sb.appendLine(buildDirectoryTreeText(skill.directory))
        sb.appendLine()
        sb.appendLine("SKILL.md:")
        sb.appendLine(content)

        return sb.toString()
    }

    private fun buildDirectoryTreeText(rootDir: File): String {
        val sb = StringBuilder()

        fun walk(dir: File, indent: String) {
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            for (child in children) {
                sb.append(indent)
                sb.append("- ")
                sb.append(child.name)
                if (child.isDirectory) {
                    sb.appendLine("/")
                    walk(child, indent + "  ")
                } else {
                    sb.appendLine()
                }
            }
        }

        walk(rootDir, indent = "")

        if (sb.length == 0) return "(empty directory)"
        return sb.toString().trimEnd()
    }

    fun importSkillFromZip(zipFile: File): String {
        return importSkillFromZip(zipFile, null)
    }

    data class SkillImportResult(
        val message: String,
        val installedDir: File?
    )

    fun importSkillFromZip(zipFile: File, subDirPathInZip: String?): String {
        return importSkillFromZipDetailed(zipFile, subDirPathInZip).message
    }

    fun importSkillFromZipDetailed(zipFile: File, subDirPathInZip: String?): SkillImportResult {
        if (!zipFile.exists() || !zipFile.canRead()) {
            return SkillImportResult(context.getString(R.string.skill_error_cannot_read_file, zipFile.absolutePath), null)
        }
        if (!zipFile.name.endsWith(".zip", ignoreCase = true)) {
            return SkillImportResult(context.getString(R.string.skill_error_only_support_zip), null)
        }

        val skillsRoot = try {
            getInternalSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            return SkillImportResult(context.getString(R.string.skill_error_cannot_access_dir, e.message ?: ""), null)
        }

        val tmpDir = File(skillsRoot, ".import_tmp_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) {
            return SkillImportResult(context.getString(R.string.skill_error_create_tmp_dir_failed, tmpDir.absolutePath), null)
        }

        fun cleanupTmp() {
            try {
                tmpDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }

        try {
            unzipToDirectory(zipFile, tmpDir)

            val normalizedSubDir = subDirPathInZip
                ?.trim()
                ?.trimStart('/')
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }

            val zipRootDir = tmpDir
                .listFiles()
                ?.filter { it.isDirectory }
                ?.singleOrNull()
                ?: tmpDir

            val searchRoot: File = if (normalizedSubDir == null) {
                tmpDir
            } else {
                val baseCanonical = zipRootDir.canonicalFile
                val resolved = File(zipRootDir, normalizedSubDir)
                val resolvedCanonical = resolved.canonicalFile
                if (!resolvedCanonical.path.startsWith(baseCanonical.path + File.separator)) {
                    cleanupTmp()
                    return SkillImportResult(context.getString(R.string.skill_error_import_invalid_path), null)
                }
                if (!resolvedCanonical.exists()) {
                    cleanupTmp()
                    return SkillImportResult(context.getString(R.string.skill_error_import_path_not_found, normalizedSubDir), null)
                }
                resolvedCanonical
            }

            val directSkillFile = if (searchRoot.isDirectory) {
                File(searchRoot, "SKILL.md").let { primary ->
                    if (primary.exists()) primary else File(searchRoot, "skill.md")
                }.takeIf { it.exists() && it.isFile }
            } else {
                null
            }

            val skillMdCandidates = if (directSkillFile != null) {
                listOf(directSkillFile)
            } else {
                searchRoot.walkTopDown()
                    .filter { it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("skill.md", ignoreCase = true)) }
                    .take(10)
                    .toList()
            }

            if (skillMdCandidates.isEmpty()) {
                cleanupTmp()
                return SkillImportResult(if (normalizedSubDir == null) {
                    context.getString(R.string.skill_error_import_no_skill_md)
                } else {
                    context.getString(R.string.skill_error_import_no_skill_md_in_path)
                }, null)
            }

            val selectedSkillFile = skillMdCandidates.first()
            val selectedSkillDir = selectedSkillFile.parentFile ?: run {
                cleanupTmp()
                return SkillImportResult(context.getString(R.string.skill_error_import_skill_md_path_invalid), null)
            }

            val (metaName, metaDesc) = parseSkillMetadata(selectedSkillFile)
            val baseName = metaName.ifBlank {
                val isTmpRoot = try {
                    selectedSkillDir.canonicalFile == tmpDir.canonicalFile
                } catch (_: Exception) {
                    selectedSkillDir.absolutePath == tmpDir.absolutePath
                }
                if (isTmpRoot) {
                    zipFile.nameWithoutExtension
                } else {
                    selectedSkillDir.name.ifBlank { zipFile.nameWithoutExtension }
                }
            }
            val finalDir = File(skillsRoot, baseName.trim().ifBlank { "skill" })

            if (finalDir.exists()) {
                cleanupTmp()
                return SkillImportResult(context.getString(R.string.skill_error_import_duplicate_name, finalDir.name), null)
            }

            // Copy the detected skill directory to final location
            selectedSkillDir.copyRecursively(finalDir, overwrite = false)
            cleanupTmp()

            // refresh cache
            refreshAvailableSkills()

            val desc = metaDesc.ifBlank { "" }
            return SkillImportResult(if (desc.isNotBlank()) {
                context.getString(R.string.skill_imported_with_desc, finalDir.name, desc)
            } else {
                context.getString(R.string.skill_imported, finalDir.name)
            }, finalDir)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import skill from zip", e)
            cleanupTmp()
            return SkillImportResult(context.getString(R.string.skill_error_import_failed, e.message ?: ""), null)
        }
    }


    private fun unzipToDirectory(zipFile: File, destinationDir: File) {
        val destCanonical = destinationDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break

                val outFile = File(destinationDir, entry.name)
                val outCanonical = outFile.canonicalFile
                if (!outCanonical.path.startsWith(destCanonical.path + File.separator)) {
                    zis.closeEntry()
                    throw IllegalArgumentException("Zip entry is outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zis.closeEntry()
                    continue
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}

/**
 * Finds stable legacy directory identities whose effective metadata name equals [skillName].
 * Missing, unreadable, or invalid packages are ignored exactly as the normal legacy scan does.
 */
internal fun legacySkillDirectoryNamesMatching(
    root: File,
    skillName: String,
    metadataName: (File) -> String?,
): Set<String> {
    if (!root.isDirectory) return emptySet()
    return root.listFiles().orEmpty()
        .asSequence()
        .filter { it.isDirectory }
        .mapNotNull { child ->
            val skillFile = File(child, "SKILL.md").let { primary ->
                if (primary.isFile) primary else File(child, "skill.md")
            }
            if (!skillFile.isFile) return@mapNotNull null
            val parsed = runCatching { metadataName(skillFile) }.getOrElse {
                return@mapNotNull null
            }.orEmpty()
            val effectiveName = parsed.ifBlank { child.name }
            child.name.takeIf { effectiveName == skillName }
        }
        .toSet()
}
