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
 * Which tool packages an Orbit has and uses, kept apart from the main app in packages.json in the
 * Orbit's folder: the packages imported into it, and which of those are switched on. Turning a
 * package off on one side leaves the other side as it was.
 *
 * A new Orbit starts with only the [REQUIRED] packages, which stay on. The user picks more of the
 * main app's packages to import, and an imported package is switched on; nothing follows the main
 * app's switches afterwards. Only these
 * lists are the Orbit's own: a package's runtime is still the app's one. Every package, plugin
 * subpackages included, loads on use whatever the main app's switches say.
 */
object OrbitPackages {
    private const val FILE = "packages.json"

    /**
     * Built-in packages every Orbit has on and cannot switch off or take out: its terminal, web
     * search, memory tools, cards and browser, which Orbit's own prompt and routines rely on.
     */
    val REQUIRED: Set<String> = linkedSetOf("super_admin", "various_search", "memory_review", "dynamic_ui", "browser")

    /**
     * Main-app packages Orbit never gets. extended_chat manages the app's ordinary chats (character
     * cards, and inside Orbit its subagent and review chats too); Orbit's own chats go through
     * orbit_chat. operit_editor edits the main app's models, voice, packages and environment
     * directly; Orbit has its own tools for those (orbit_models, orbit_voice, orbit_packages,
     * orbit_vault), which keep its settings apart and ask the user with cards.
     */
    val NOT_FOR_ORBIT: Set<String> = setOf("extended_chat", "operit_editor")

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRefresh = ConcurrentHashMap<String, Job>()

    /** Every name in any Orbit's packages.json, kept until a list is written or an Orbit is removed. */
    @Volatile private var namesInUse: Set<String>? = null
    // Guards publishing and forgetting the list together, so a slow scan cannot bring back an old one.
    private val namesLock = Any()
    private var namesVersion = 0L
    private val enabledByOrbit = ConcurrentHashMap<String, Set<String>>()

    /** A package could not be switched on because something it needs is missing or the wrong version. */
    class NeedsMissingException(val packageName: String, detail: String) : IllegalArgumentException(detail)

    /** A package could not be switched off because other switched-on packages need it. */
    class StillNeededException(val packageName: String, val dependents: List<String>) :
        IllegalArgumentException("Cannot switch off '$packageName'; it is required by: ${dependents.joinToString(", ")}.")

    /** A package was not imported into this Orbit, so it cannot be switched here. */
    class NotImportedException(val packageName: String) :
        IllegalArgumentException("'$packageName' has not been imported into this Orbit. The user can import it in Orbit's settings, under Packages.")

    /** A package Orbit always keeps on: it cannot be switched off or taken out. */
    class RequiredException(val packageName: String) :
        IllegalArgumentException("'$packageName' is always on in Orbit; it cannot be switched off or removed.")

    /** An Orbit's packages: what was imported, and which of those are on. */
    data class State(val imported: Set<String>, val enabled: Set<String>) {
        /** The same lists with the [REQUIRED] packages in and on. */
        fun withRequired() = State(imported + REQUIRED, enabled + REQUIRED)
    }

    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), FILE)

    private fun lock(file: File) = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    /**
     * Packages a user can switch on: loadable, not a ToolPkg container and not archived. A required
     * package, or one in [kept] (what an Orbit already has), stays usable here even while the main
     * app has it archived; a deleted one an Orbit had is Orbit's copy and stays too.
     */
    fun candidates(packageManager: PackageManager, kept: Set<String> = emptySet()): Map<String, ToolPackage> {
        val archived = packageManager.getArchivedBuiltInPackageNames()
        return packageManager.getAvailablePackages().filterKeys { name ->
            name !in NOT_FOR_ORBIT && !packageManager.isToolPkgContainer(name) &&
                (name in REQUIRED || name in kept || !packageManager.isArchivedBuiltInPackageOrSubpackage(name, archived))
        }
    }

    /** What can still be imported: the main app's packages, without Orbit's copies of deleted ones. */
    fun importable(packageManager: PackageManager): Map<String, ToolPackage> =
        packageManager.withoutOrbitCopies(candidates(packageManager))

    /** Drops the copies of deleted packages no Orbit has any more. */
    fun pruneCopies(context: Context, packageManager: PackageManager) {
        runCatching { packageManager.pruneOrbitCopies(importedByAnyOrbit(context)) }
            .onFailure { AppLogger.w("OrbitPackages", "Could not drop unused package copies", it) }
    }

    private fun names(json: JSONObject, key: String): Set<String>? =
        json.optJSONArray(key)?.let { list -> (0 until list.length()).mapTo(LinkedHashSet()) { list.getString(it) } }

    /**
     * The stored lists, always with the [REQUIRED] packages in and on. A file from before imports
     * existed counts every package it had on as imported.
     */
    private fun read(file: File): State? =
        file.takeIf { it.isFile }?.let { stored ->
            runCatching {
                val json = JSONObject(stored.readText())
                val enabled = names(json, "enabled").orEmpty() - NOT_FOR_ORBIT
                val imported = (names(json, "imported") ?: enabled) - NOT_FOR_ORBIT
                State(imported, enabled.filterTo(LinkedHashSet()) { it in imported }).withRequired()
            }.getOrNull()
        }

    private fun readOrEmpty(file: File) = read(file) ?: State(emptySet(), emptySet()).withRequired()

    private fun write(file: File, given: State) {
        val state = given.withRequired()
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temp = File.createTempFile(".packages-", ".tmp", parent)
        try {
            temp.outputStream().use {
                val json = JSONObject()
                    .put("imported", JSONArray(state.imported.sorted()))
                    .put("enabled", JSONArray(state.enabled.filter { name -> name in state.imported }.sorted()))
                it.write(json.toString().toByteArray(Charsets.UTF_8))
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
            enabledByOrbit.clear()
        }
    }

    /**
     * The packages switched on for this Orbit, for code that cannot suspend (plugin prompt hooks).
     * Kept in memory until a package switch changes, so it normally reads no file.
     */
    fun enabledNow(context: Context, orbitId: String): Set<String> {
        enabledByOrbit[orbitId]?.let { return it }
        val version = synchronized(namesLock) { namesVersion }
        val enabled = readOrEmpty(file(context, orbitId)).enabled
        synchronized(namesLock) { if (namesVersion == version) enabledByOrbit[orbitId] = enabled }
        return enabled
    }

    private fun allOrbitNames(context: Context): Set<String> {
        namesInUse?.let { return it }
        val version = synchronized(namesLock) { namesVersion }
        val root = File(context.applicationContext.filesDir, "orbit")
        val names = root.listFiles()?.flatMapTo(HashSet()) { dir -> read(File(dir, FILE))?.enabled.orEmpty() }.orEmpty()
        synchronized(namesLock) { if (namesVersion == version) namesInUse = names }
        return names
    }

    /**
     * Every package some Orbit has imported, on or off. A package the main app deletes is kept as a
     * copy for Orbit while it is in here.
     */
    fun importedByAnyOrbit(context: Context): Set<String> {
        val root = File(context.applicationContext.filesDir, "orbit")
        return root.listFiles()?.flatMapTo(HashSet()) { dir ->
            read(File(dir, FILE))?.imported.orEmpty() - REQUIRED
        }.orEmpty()
    }

    /**
     * Plugins some Orbit has a package of switched on. The main app keeps their files and running
     * scripts while it has them off, so the Orbit's tools still work.
     */
    fun containersInUse(context: Context, packageManager: PackageManager): Set<String> =
        allOrbitNames(context).mapNotNullTo(HashSet()) { packageManager.toolPkgContainerOf(it) }

    fun usesContainer(context: Context, packageManager: PackageManager, containerName: String): Boolean =
        allOrbitNames(context).any { packageManager.toolPkgContainerOf(it) == containerName }

    /** The Orbit's imported packages and which of them are on. */
    suspend fun state(context: Context, orbitId: String): State =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock { readOrEmpty(file) }
        }

    /** The packages switched on for this Orbit. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun enabled(context: Context, orbitId: String, packageManager: PackageManager): Set<String> =
        state(context, orbitId).enabled

    /** What [name] needs, or [NeedsMissingException] when something it needs is not available. */
    private fun needs(packageManager: PackageManager, name: String): List<String> = try {
        packageManager.orbitRequiredPackages(name)
    } catch (e: IllegalArgumentException) {
        throw NeedsMissingException(name, e.message ?: "'$name' needs a package that is not available.")
    }

    /** Switched-on packages other than [name] that need it. */
    private fun dependents(packageManager: PackageManager, enabled: Set<String>, name: String): List<String> =
        enabled.filter { other ->
            other != name && !packageManager.isToolPkgContainer(other) &&
                runCatching { packageManager.orbitRequiredPackages(other) }.getOrDefault(emptyList()).contains(name)
        }

    /**
     * Switches the imported package [name] for this Orbit. Switching on also imports and switches on
     * the plugins it requires, and returns every name that got switched on. A package that was not
     * imported, a missing requirement, or switching off something another switched-on package
     * needs, throws with the reason and changes nothing. An approval can restrict [allowedEnabled]
     * so a dependency that changed while the user was deciding is not silently switched on.
     */
    suspend fun setEnabled(
        context: Context,
        orbitId: String,
        packageManager: PackageManager,
        name: String,
        on: Boolean,
        allowedEnabled: Set<String>? = null,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            val file = file(context, orbitId)
            lock(file).withLock {
                val current = readOrEmpty(file)
                if (!on && name in REQUIRED) throw RequiredException(name)
                if (name !in current.imported) throw NotImportedException(name)
                if (on) {
                    val added = linkedSetOf(name) + needs(packageManager, name)
                    if (allowedEnabled != null) {
                        val missing = added - current.imported
                        require(missing.isEmpty()) { "No longer imported: ${missing.joinToString()}; request import first" }
                        val unapproved = added - current.enabled - allowedEnabled
                        require(unapproved.isEmpty()) {
                            "Dependencies changed; request approval to switch on: ${unapproved.joinToString()}"
                        }
                    }
                    write(file, State(current.imported + added, current.enabled + added))
                    added
                } else {
                    val needed = dependents(packageManager, current.enabled, name)
                    if (needed.isNotEmpty()) throw StillNeededException(name, needed)
                    write(file, current.copy(enabled = current.enabled - name))
                    emptySet()
                }
            }
        }

    /** What [importSelected] did: the names now on, and the ones left out with the reason. */
    data class ImportResult(val added: Set<String>, val failed: Map<String, NeedsMissingException>)

    /**
     * Imports the main app's packages [names] into this Orbit, switched on, with the plugins they
     * need. Nothing else changes: other imported packages keep their switches. A package whose
     * requirement is missing is left out and reported.
     */
    suspend fun importSelected(
        context: Context,
        orbitId: String,
        packageManager: PackageManager,
        names: Collection<String>,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val available = importable(packageManager).keys
            val added = LinkedHashSet<String>()
            val failed = LinkedHashMap<String, NeedsMissingException>()
            names.filter { it in available }.forEach { name ->
                try {
                    added += name
                    added += needs(packageManager, name)
                } catch (e: NeedsMissingException) {
                    added -= name
                    failed[name] = e
                }
            }
            val file = file(context, orbitId)
            lock(file).withLock {
                val current = readOrEmpty(file)
                if (added.isNotEmpty()) write(file, State(current.imported + added, current.enabled + added))
            }
            ImportResult(added, failed)
        }

    /**
     * Takes [name] out of this Orbit; the main app keeps it. Throws [RequiredException] for a
     * required package and [StillNeededException] while a switched-on package needs it.
     */
    suspend fun remove(context: Context, orbitId: String, packageManager: PackageManager, name: String) =
        withContext(Dispatchers.IO) {
            if (name in REQUIRED) throw RequiredException(name)
            val file = file(context, orbitId)
            lock(file).withLock {
                val current = readOrEmpty(file)
                val needed = dependents(packageManager, current.enabled, name)
                if (needed.isNotEmpty()) throw StillNeededException(name, needed)
                write(file, State(current.imported - name, current.enabled - name))
            }
            pruneCopies(context, packageManager)
        }

    /**
     * What an Orbit chat may see and call, or null in any other chat. Packages that no longer exist
     * are left out.
     */
    suspend fun scopeFor(context: Context, packageManager: PackageManager, chatId: String?): Set<String>? {
        if (chatId.isNullOrBlank()) return null
        val orbit = runCatching { OrbitMemoryScope.owner(context, chatId) }.getOrNull() ?: return null
        return scopeFor(context, packageManager, orbit)
    }

    /** [scopeFor] when the chat's Orbit is already known. */
    suspend fun scopeFor(context: Context, packageManager: PackageManager, orbit: OrbitProfile): Set<String> {
        val enabled = enabled(context, orbit.id, packageManager)
        val candidates = candidates(packageManager, enabled).keys
        return enabled.filterTo(LinkedHashSet()) { name ->
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
