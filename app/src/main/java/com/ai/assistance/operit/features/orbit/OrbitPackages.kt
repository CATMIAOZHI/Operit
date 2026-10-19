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
 * the app's one. Every package, plugin subpackages included, loads on use whatever the main app's
 * switches say.
 */
object OrbitPackages {
    private const val FILE = "packages.json"
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRefresh = ConcurrentHashMap<String, Job>()

    /** Every name in any Orbit's packages.json, kept until a list is written or an Orbit is removed. */
    @Volatile private var namesInUse: Set<String>? = null
    // Guards publishing and forgetting the list together, so a slow scan cannot bring back an old one.
    private val namesLock = Any()
    private var namesVersion = 0L

    /** A package could not be switched on because something it needs is missing or the wrong version. */
    class NeedsMissingException(val packageName: String, detail: String) : IllegalArgumentException(detail)

    /** A package could not be switched off because other switched-on packages need it. */
    class StillNeededException(val packageName: String, val dependents: List<String>) :
        IllegalArgumentException("Cannot switch off '$packageName'; it is required by: ${dependents.joinToString(", ")}.")

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
            forgetNamesInUse()
        }
    }

    /** Call after an Orbit's folder is removed so its plugins stop counting as in use. */
    fun forgetNamesInUse() {
        synchronized(namesLock) {
            namesVersion++
            namesInUse = null
        }
    }

    private fun allOrbitNames(context: Context): Set<String> {
        namesInUse?.let { return it }
        val version = synchronized(namesLock) { namesVersion }
        val root = File(context.applicationContext.filesDir, "orbit")
        val names = root.listFiles()?.flatMapTo(HashSet()) { dir -> read(File(dir, FILE)).orEmpty() }.orEmpty()
        synchronized(namesLock) { if (namesVersion == version) namesInUse = names }
        return names
    }

    /**
     * Plugins some Orbit has a package of switched on. The main app keeps their files and running
     * scripts while it has them off, so the Orbit's tools still work.
     */
    fun containersInUse(context: Context, packageManager: PackageManager): Set<String> =
        allOrbitNames(context).mapNotNullTo(HashSet()) { packageManager.toolPkgContainerOf(it) }

    fun usesContainer(context: Context, packageManager: PackageManager, containerName: String): Boolean =
        allOrbitNames(context).any { packageManager.toolPkgContainerOf(it) == containerName }

    /** The Orbit's own list, copied from the main app the first time it is read. */
    suspend fun enabled(context: Context, orbitId: String, packageManager: PackageManager): Set<String> =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock {
                read(file) ?: mainEnabled(packageManager).also { write(file, it) }
            }
        }

    /**
     * Switches [name] for this Orbit. Switching on also switches on the plugins it requires, and
     * returns every name that got switched on. A missing requirement, or switching off something
     * another switched-on package needs, throws with the reason and changes nothing.
     */
    suspend fun setEnabled(
        context: Context,
        orbitId: String,
        packageManager: PackageManager,
        name: String,
        on: Boolean,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            val added = if (on) {
                val needs = try {
                    packageManager.orbitRequiredPackages(name)
                } catch (e: IllegalArgumentException) {
                    throw NeedsMissingException(name, e.message ?: "'$name' needs a package that is not available.")
                }
                linkedSetOf(name) + needs
            } else {
                emptySet()
            }
            lock(file).withLock {
                val current = read(file) ?: mainEnabled(packageManager)
                if (!on) {
                    val dependents = current.filter { other ->
                        other != name && !packageManager.isToolPkgContainer(other) &&
                            runCatching { packageManager.orbitRequiredPackages(other) }.getOrDefault(emptyList())
                                .contains(name)
                    }
                    if (dependents.isNotEmpty()) throw StillNeededException(name, dependents)
                }
                write(file, if (on) current + added else current - name)
            }
            added
        }

    /** Replaces the Orbit's list with the main app's current one. */
    suspend fun importFromMain(context: Context, orbitId: String, packageManager: PackageManager) =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock { write(file, mainEnabled(packageManager)) }
        }

    /**
     * What an Orbit chat may see and call, or null in any other chat. Packages that no longer exist
     * are left out.
     */
    suspend fun scopeFor(context: Context, packageManager: PackageManager, chatId: String?): Set<String>? {
        if (chatId.isNullOrBlank()) return null
        val orbit = runCatching { OrbitMemoryScope.owner(context, chatId) }.getOrNull() ?: return null
        val candidates = candidates(packageManager).keys
        return enabled(context, orbit.id, packageManager).filterTo(LinkedHashSet()) { name ->
            name in candidates
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
