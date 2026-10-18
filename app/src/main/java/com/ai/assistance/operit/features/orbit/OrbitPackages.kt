package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which tool packages an Orbit uses, kept apart from the main app's list in packages.json in the
 * Orbit's folder. Turning a package off on one side leaves the other side as it was.
 *
 * The first time an Orbit needs the list it copies the main app's, and the user can copy it again
 * later. Only the list of what the model may see and call is separate: a package's runtime is still
 * the app's one. A plain package loads on use either way; a ToolPkg subpackage also needs its
 * container switched on in the main app, because that is where its runtime lives.
 */
object OrbitPackages {
    private const val FILE = "packages.json"
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRefresh = ConcurrentHashMap<String, Job>()

    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), FILE)

    private fun lock(file: File) = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    /** Packages a user can switch on: loadable, not a ToolPkg container and not archived. */
    fun candidates(packageManager: PackageManager): Map<String, ToolPackage> {
        val archived = packageManager.getArchivedBuiltInPackageNames()
        return packageManager.getAvailablePackages().filterKeys { name ->
            !packageManager.isToolPkgContainer(name) &&
                !packageManager.isArchivedBuiltInPackageOrSubpackage(name, archived)
        }
    }

    /** The main app's switched-on packages, the way an Orbit copies them. */
    fun mainEnabled(packageManager: PackageManager): Set<String> {
        val candidates = candidates(packageManager).keys
        return packageManager.getEnabledPackageNames().filterTo(LinkedHashSet()) { it in candidates }
    }

    /** The ToolPkg container a subpackage needs, when it is switched off in the main app. */
    fun missingContainer(packageManager: PackageManager, name: String): String? {
        val container = packageManager.resolveToolPkgSubpackageRuntimeInternal(name)?.containerPackageName
            ?: return null
        return container.takeIf { it !in packageManager.getEnabledPackageNames() }
    }

    private fun read(file: File): Set<String>? =
        file.takeIf { it.isFile }?.let { stored ->
            runCatching {
                val list = JSONObject(stored.readText()).getJSONArray("enabled")
                (0 until list.length()).mapTo(LinkedHashSet()) { list.getString(it) }
            }.getOrNull()
        }

    private fun write(file: File, names: Set<String>) {
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temp = File.createTempFile(".packages-", ".tmp", parent)
        try {
            temp.outputStream().use {
                it.write(JSONObject().put("enabled", JSONArray(names.sorted())).toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    /** The Orbit's own list, copied from the main app the first time it is read. */
    suspend fun enabled(context: Context, orbitId: String, packageManager: PackageManager): Set<String> =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock {
                read(file) ?: mainEnabled(packageManager).also { write(file, it) }
            }
        }

    suspend fun setEnabled(context: Context, orbitId: String, packageManager: PackageManager, name: String, on: Boolean) =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock {
                val current = read(file) ?: mainEnabled(packageManager)
                write(file, if (on) current + name else current - name)
            }
        }

    /** Replaces the Orbit's list with the main app's current one. */
    suspend fun importFromMain(context: Context, orbitId: String, packageManager: PackageManager) =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock { write(file, mainEnabled(packageManager)) }
        }

    /**
     * What an Orbit chat may see and call, or null in any other chat. Packages that no longer exist,
     * and subpackages whose container is off in the main app, are left out.
     */
    suspend fun scopeFor(context: Context, packageManager: PackageManager, chatId: String?): Set<String>? {
        if (chatId.isNullOrBlank()) return null
        val orbit = runCatching { OrbitMemoryScope.owner(context, chatId) }.getOrNull() ?: return null
        val candidates = candidates(packageManager).keys
        return enabled(context, orbit.id, packageManager).filterTo(LinkedHashSet()) { name ->
            name in candidates && missingContainer(packageManager, name) == null
        }
    }

    /**
     * Whether any chat of this Orbit is replying, in the main screen or the floating window. Both can
     * show the same chat, so a reply in either counts.
     */
    fun replying(context: Context, orbit: OrbitProfile): kotlinx.coroutines.flow.Flow<Boolean> {
        val holder = ChatRuntimeHolder.getInstance(context.applicationContext)
        return kotlinx.coroutines.flow.combine(
            holder.getCore(ChatRuntimeSlot.MAIN).activeStreamingChatIds,
            holder.getCore(ChatRuntimeSlot.FLOATING).activeStreamingChatIds,
        ) { main, floating -> orbit.talkChatIds.any { it in main || it in floating } }
    }

    /**
     * The system prompt lists packages, so after a change every chat of this Orbit rebuilds it on its
     * next reply. A chat that is replying right now is left alone until it finishes.
     */
    fun refreshPromptWhenIdle(context: Context, orbit: OrbitProfile) {
        val app = context.applicationContext
        pendingRefresh.remove(orbit.id)?.cancel()
        pendingRefresh[orbit.id] = refreshScope.launch {
            try {
                replying(app, orbit).first { !it }
                orbit.talkChatIds.forEach { LearningPromptSnapshotRepository(app, it).delete() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitPackages", "Could not refresh Orbit prompt: ${e.message}")
            }
        }
    }
}
