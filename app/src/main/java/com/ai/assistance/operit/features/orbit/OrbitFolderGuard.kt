package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.tools.FileOperationData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.io.File

/**
 * delete_file on a path inside an Orbit's folder. Orbit deletes its notes and files with the app's
 * own delete_file; this keeps that to the files Orbit manages (people and group notes, the daily
 * log, the lessons book, settings files, files/...), keeps the people/group INDEX.md current, and
 * refuses Orbit's internal data. Deleting is permanent: there is no recycle bin.
 */
object OrbitFolderGuard {

    /**
     * Where [path] falls under the Orbit folders: the Orbit's id and the short path inside it.
     * [dotted] when the path has a "." or ".." part, whose meaning a link inside can change, or a
     * backslash, which Android keeps in a file name but Orbit's paths read as a folder break.
     */
    private data class Place(val orbitId: String?, val shortPath: String, val dotted: Boolean)

    private fun place(base: File, path: String): Place? {
        val raw = path.trim().removePrefix("file://")
        if (raw.isEmpty()) return null
        val file = File(raw)
        if (!file.isAbsolute) return null
        val dotted = (File.separatorChar != '\\' && '\\' in raw) ||
            raw.split('/', File.separatorChar).any { it == "." || it == ".." }
        val candidates = listOf(base.absoluteFile.normalize() to file.absoluteFile.normalize()) +
            listOfNotNull(runCatching { base.canonicalFile to file.canonicalFile }.getOrNull())
        for ((root, target) in candidates) {
            val rel = target.relativeToOrNull(root)?.invariantSeparatorsPath ?: continue
            if (rel == ".." || rel.startsWith("../")) continue
            if (rel.isEmpty()) return Place(null, "", dotted)
            return Place(rel.substringBefore('/'), rel.substringAfter('/', ""), dotted)
        }
        return null
    }

    /**
     * Handles delete_file when its path is inside an Orbit folder; null when it is not (the app's
     * own delete runs then).
     */
    suspend fun deleteFile(context: Context, tool: AITool): ToolResult? =
        deleteFile(File(context.applicationContext.filesDir, "orbit"), tool) { OrbitFiles.of(context, it) }

    /** [deleteFile] for the Orbit folders under [base], each Orbit's files from [filesOf]. */
    internal suspend fun deleteFile(base: File, tool: AITool, filesOf: (String) -> OrbitFiles): ToolResult? {
        // Same routing as the app's delete_file: only "linux" and "repo:..." go elsewhere; anything
        // else deletes on Android, so it is checked here.
        val environment = tool.parameters.find { it.name == "environment" }?.value
        if (environment?.lowercase() == "linux" || environment?.startsWith("repo:", ignoreCase = true) == true) return null
        val path = tool.parameters.find { it.name == "path" }?.value.orEmpty()
        val place = place(base, path) ?: return null
        val recursive = tool.parameters.find { it.name == "recursive" }?.value?.trim()?.toBoolean() ?: false

        fun result(ok: Boolean, details: String) = ToolResult(
            toolName = tool.name,
            success = ok,
            result = FileOperationData(operation = "delete", env = "android", path = path, successful = ok, details = details),
            error = if (ok) "" else details,
        )

        val orbitId = place.orbitId ?: return result(false, "This is the folder of all Orbits; it cannot be deleted.")
        if (place.dotted) return result(false, "Nothing was deleted: write the full path with / only, without \".\" or \"..\" parts.")
        // A linked Orbit folder (or a linked folder of all Orbits) is not trusted as a place to delete in.
        val orbitDir = File(base, orbitId)
        val trusted = runCatching {
            !java.nio.file.Files.isSymbolicLink(base.toPath()) && !java.nio.file.Files.isSymbolicLink(orbitDir.toPath()) &&
                orbitDir.toPath().toRealPath().parent == base.toPath().toRealPath()
        }.getOrDefault(false)
        if (!trusted) return result(false, "Nothing was deleted: $path is not in a real Orbit folder.")
        val short = place.shortPath
        if (short.isEmpty()) return result(false, "This is the whole Orbit folder; it cannot be deleted.")
        val refused = refusal(short)
        if (refused != null) return result(false, refused)

        val target = File(path.trim().removePrefix("file://"))
        val files = filesOf(orbitId)
        return try {
            if (target.isDirectory) {
                val count = files.deleteFolder(short, recursive)
                result(true, "Deleted the folder $short and the $count file(s) in it for good (there is no recycle bin).")
            } else {
                val deleted = files.delete(short)
                result(true, "Deleted $deleted for good (there is no recycle bin).")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            result(false, e.message ?: e.toString())
        }
    }

    /** Why delete_file may not remove [short]; null when it may. */
    private fun refusal(short: String): String? {
        val p = runCatching { OrbitFiles.normalize(short) }.getOrNull()
            ?: return "$short is Orbit's internal data; delete_file cannot remove it."
        return when {
            p.equals("user.md", true) || p.equals("memory/memory.md", true) ->
                if (p.equals("user.md", true)) "user.md cannot be deleted. Change it with memory_review manage action=memory_change, target=user."
                else "memory.md cannot be deleted. Change it with memory_review notes."
            p.equals("SOUL.md", true) || p.equals("IDENTITY.md", true) ->
                "$p cannot be deleted. Change it with orbit_soul action=update, file=${if (p.equals("SOUL.md", true)) "soul" else "identity"} (read it first)."
            p.startsWith("docs/") || p == "docs" ->
                "docs/ is Orbit's read-only handbook; it cannot be deleted."
            OrbitFiles.isIndex(p) ->
                "INDEX.md is kept by the app. Delete the person or group note instead; the index updates itself."
            p == "files" -> "files/ itself cannot be deleted; delete the files or folders inside it."
            p.startsWith("files/") || OrbitFiles.allowed(p) -> null
            else -> "$p is Orbit's internal data; delete_file cannot remove it. Only Orbit's notes " +
                "(memory/people, memory/groups, the daily log, LESSONS.md and the other settings files) " +
                "and files/ can be deleted."
        }
    }
}
