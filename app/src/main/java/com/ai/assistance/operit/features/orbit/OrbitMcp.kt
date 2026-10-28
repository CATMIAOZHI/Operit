package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.tools.mcp.MCPManager
import com.ai.assistance.operit.core.tools.mcp.MCPPackage
import com.ai.assistance.operit.data.mcp.MCPLocalServer
import com.ai.assistance.operit.data.mcp.isRuntimeActivationAllowed
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.features.startup.screens.PluginInitializationCompletion
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingSnapshot
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.PluginStartupScope
import com.ai.assistance.operit.ui.features.startup.screens.PluginStatus
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OnDemandResources
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which of the app's MCP servers an Orbit may use, apart from the main app's chats. The servers are
 * set up in the app's MCP settings; Orbit only keeps its own on/off list (mcp.json in its folder)
 * and starts the app's MCP servers itself when it needs one and no screen has started them yet.
 *
 * A server switched off in the app's MCP settings stays off for Orbit too; Orbit never turns it on.
 *
 * "Ready" means the app has the server registered: its tools connect on use_package. Whether it
 * really answers shows only on connecting, so a failed connection is remembered here and shown.
 */
internal object OrbitMcp {
    private const val FILE = "mcp.json"
    private const val TAG = "OrbitMcp"
    /** How long a tool call waits for a start before saying it is still starting. */
    const val START_WAIT_MS = 90_000L
    private const val TERMINAL_MISSING = "The terminal environment is not installed yet; MCP servers run in it. " +
        "Ask to install it with orbit_components."

    data class Server(val id: String, val name: String, val description: String)

    enum class State(val key: String) {
        READY("ready"),
        STARTING("starting"),
        NOT_STARTED("not_started"),
        FAILED("failed"),
        OFF_IN_ORBIT("off_for_orbit"),
        OFF_IN_APP("off_in_app"),
        NOT_CONFIGURED("not_configured"),
    }

    data class Status(val state: State, val reason: String? = null)

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    /** Each Orbit's list as last read or written; only this process writes the file. */
    private val cache = ConcurrentHashMap<String, Set<String>>()
    /** Guards checking and launching a start only; callers wait for their server outside it. */
    private val startLock = Mutex()
    /** Why a server did not come up when Orbit started it; counts only while it is not registered. */
    private val startFailures = ConcurrentHashMap<String, String>()
    /** Why connecting to a registered server failed; counts only once it is registered. */
    private val connectFailures = ConcurrentHashMap<String, String>()
    /** Servers Orbit asked a start it launched for; the start's end records which did not come up. */
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), FILE)

    private fun local(context: Context) = MCPLocalServer.getInstance(context.applicationContext)

    private fun manager(context: Context) = MCPManager.getInstance(context.applicationContext)

    /** Every MCP server set up in the app, by its id (the name tools and use_package use). */
    fun configured(context: Context): Map<String, Server> {
        val server = local(context)
        val metadata = server.getAllPluginMetadata()
        val ids = (server.getAllMCPServers().keys + metadata.keys).filter { server.hasEffectiveServer(it) }
        return ids.sorted().associateWith { id ->
            val meta = metadata[id]
            Server(id, meta?.name?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/'),
                meta?.description.orEmpty().replace(Regex("\\s+"), " ").trim())
        }
    }

    /** The servers switched on for this Orbit. A new Orbit has none. */
    suspend fun enabled(context: Context, orbitId: String): Set<String> {
        cache[orbitId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(context, orbitId)
            lock(f).withLock { cache.getOrPut(orbitId) { read(f) } }
        }
    }

    private fun lock(f: File) = fileLocks.computeIfAbsent(f.absolutePath) { Mutex() }

    private fun read(f: File): Set<String> = runCatching {
        if (!f.isFile) return@runCatching emptySet()
        val list = JSONObject(f.readText()).optJSONArray("on") ?: return@runCatching emptySet()
        (0 until list.length()).mapTo(LinkedHashSet()) { list.getString(it) }
    }.getOrDefault(emptySet())

    /** Switches servers for this Orbit and returns the new list. Unknown ids are refused. */
    suspend fun set(context: Context, orbitId: String, on: Collection<String>, off: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val known = configured(context).keys
            val unknown = (on + off).filter { it !in known }
            require(unknown.isEmpty()) { "No such MCP server: ${unknown.joinToString()}" }
            val f = file(context, orbitId)
            lock(f).withLock {
                val next = (read(f) + on - off.toSet()).filterTo(LinkedHashSet()) { it in known }
                val parent = checkNotNull(f.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                val temp = File.createTempFile(".mcp-", ".tmp", parent)
                try {
                    temp.writeText(JSONObject().put("on", JSONArray(next.sorted())).toString())
                    Files.move(temp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temp.delete()
                }
                cache[orbitId] = next
                // A server switched on again may be started again.
                on.forEach { startFailures.remove(it); connectFailures.remove(it) }
                next
            }
        }

    private fun registered(context: Context, id: String) = manager(context).isServerRegistered(id)

    /** Where one server stands for this Orbit; [orbitOn] is the Orbit's list. */
    fun status(context: Context, id: String, orbitOn: Set<String>): Status {
        val server = local(context)
        return when {
            !server.hasEffectiveServer(id) -> Status(State.NOT_CONFIGURED)
            !server.isRuntimeActivationAllowed(id) -> Status(State.OFF_IN_APP)
            id !in orbitOn -> Status(State.OFF_IN_ORBIT)
            registered(context, id) -> (connectFailures[id] ?: manager(context).getLastConnectionFailureReason(id))
                ?.let { Status(State.FAILED, it.take(300)) } ?: Status(State.READY)
            PluginLoadingState.process.isInitializing() -> Status(State.STARTING)
            else -> (startFailures[id] ?: startupFailure(id))?.let { Status(State.FAILED, it) }
                ?: if (!OnDemandResources.terminalInstalled(context)) Status(State.NOT_STARTED, TERMINAL_MISSING)
                else Status(State.NOT_STARTED)
        }
    }

    /** The app's last start (any screen's) marked this server failed: why. */
    private fun startupFailure(id: String): String? {
        val snapshot = PluginLoadingState.process.snapshot()
        if (snapshot.plugins.none { it.id == id && it.status == PluginStatus.FAILED }) return null
        return reasonFor(snapshot, id)
    }

    /** The most specific reason the start left: the server's own log, then its line, then the batch's. */
    private fun reasonFor(snapshot: PluginLoadingSnapshot, id: String): String {
        val log = snapshot.pluginLogs[id].orEmpty().lineSequence().map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it != "START" }
        val line = snapshot.plugins.firstOrNull { it.id == id }?.message?.takeIf { it.isNotBlank() }
        return (log ?: line ?: snapshot.message.takeIf { it.isNotBlank() }
            ?: "It did not come up; check it in the app's MCP settings.").take(300)
    }

    /**
     * A start Orbit launched has ended: remember which wanted servers did not come up, and why. A
     * registered one is left alone: a connection tried after it registered may have failed since.
     */
    private fun finished(context: Context, completion: PluginInitializationCompletion) {
        val ids = wanted.toList()
        wanted.removeAll(ids.toSet())
        ids.forEach { id ->
            if (!registered(context, id)) startFailures[id] = reasonFor(completion.snapshot, id)
        }
    }

    /**
     * Gets [id] ready. Not registered yet: starts the app's MCP servers (every one the app has on,
     * the same way the main screen does, without a screen) and waits for it; an automatic start
     * ([manual] false) that already failed is not tried again. [manual] on a registered server also
     * connects to it once, so a dead server shows as failed and a fixed one as ready.
     */
    suspend fun ensureStarted(context: Context, id: String, orbitOn: Set<String>, manual: Boolean,
        waitMs: Long = START_WAIT_MS): Status {
        val app = context.applicationContext
        val before = status(app, id, orbitOn)
        if (before.state in setOf(State.NOT_CONFIGURED, State.OFF_IN_APP, State.OFF_IN_ORBIT)) return before
        if (before.state == State.NOT_STARTED && before.reason == TERMINAL_MISSING) return before
        if (registered(app, id)) return if (manual) connect(app, id, orbitOn) else before
        if (before.state == State.FAILED && !manual) return before
        val loading = PluginLoadingState.process
        startLock.withLock {
            if (!registered(app, id)) {
                if (manual) startFailures.remove(id)
                wanted += id
                if (!loading.isInitializing()) {
                    AppLogger.d(TAG, "Starting MCP servers for Orbit (needs $id)")
                    loading.initializeMCPServer(app, PluginStartupScope.MCP_ONLY, onFinished = { finished(app, it) })
                }
            }
        }
        // Registration happens as each server comes up; the start ends when all are done.
        val done = withTimeoutOrNull(waitMs) {
            while (!registered(app, id) && loading.isInitializing()) delay(300)
        }
        if (done == null) return Status(State.STARTING, "Still starting after ${waitMs / 1000}s; try again in a moment.")
        // A start that was already running is not Orbit's; when it ended without this one, say why.
        if (!registered(app, id) && id in wanted) {
            wanted -= id
            startFailures[id] = reasonFor(loading.snapshot(), id)
        }
        // Coming up from a tool cache does not run the server; a manual start checks it answers.
        if (manual && registered(app, id)) return connect(app, id, orbitOn)
        return status(app, id, orbitOn)
    }

    /** Connects to a registered server once: the real check that it answers. Stops with the caller. */
    private suspend fun connect(context: Context, id: String, orbitOn: Set<String>): Status {
        val config = manager(context).getRegisteredServers()[id] ?: return status(context, id, orbitOn)
        // loadFromServer blocks inside; being interrupted ends it when the chat is stopped.
        val result = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { MCPPackage.loadFromServer(context, config) }
        connected(context, id, if (result.mcpPackage != null) null else (result.errorMessage ?: "Connection failed."))
        return status(context, id, orbitOn)
    }

    /** After connecting to an Orbit's MCP server: remember whether it worked. */
    fun connected(context: Context, id: String, error: String?) {
        if (error == null) {
            connectFailures.remove(id)
            // The app keeps its own last failure; this connection shows it is out of date.
            manager(context).clearConnectionFailure(id)
        } else {
            connectFailures[id] = error.take(300)
        }
    }

    /** One line Orbit can act on, in English (tool results are read by the model). */
    fun explain(name: String, status: Status): String = when (status.state) {
        State.READY -> "MCP server '$name' is ready; use_package connects to it."
        State.STARTING -> "MCP server '$name' is still starting. ${status.reason.orEmpty()}".trim()
        State.NOT_STARTED -> "MCP server '$name' is not started. ${status.reason ?: "Use orbit_mcp start to start it."}".trim()
        State.FAILED -> "MCP server '$name' could not start or connect: ${status.reason.orEmpty()} " +
            "Retry with orbit_mcp start; if it keeps failing, tell the user the reason and to check it in the app's MCP settings."
        State.OFF_IN_ORBIT -> "MCP server '$name' is off for you. Ask the user with orbit_mcp request_toggle to switch it on."
        State.OFF_IN_APP -> "MCP server '$name' is switched off in the app's MCP settings. Only the user can switch it on there."
        State.NOT_CONFIGURED -> "There is no MCP server '$name'. See orbit_mcp list."
    }

    private suspend fun owner(context: Context, chatId: String?) = chatId?.takeIf { it.isNotBlank() }?.let {
        runCatching { OrbitMemoryScope.owner(context, it) }.getOrNull()
    }

    /** Servers to list in an Orbit's prompt (on for it and not off in the app), or null outside Orbit. */
    suspend fun promptServers(context: Context, chatId: String?): Map<String, String>? {
        val orbit = owner(context, chatId) ?: return null
        val on = enabled(context, orbit.id)
        if (on.isEmpty()) return emptyMap()
        val server = local(context)
        return configured(context).filterKeys { it in on && server.isRuntimeActivationAllowed(it) }
            .mapValues { (_, s) -> listOf(s.name, s.description).filter { it.isNotBlank() }.distinct().joinToString(" - ") }
    }

    /** For [orbit]: (servers on for it, every server set up). */
    suspend fun scopeFor(context: Context, orbit: OrbitProfile): Pair<Set<String>, Set<String>> =
        enabled(context, orbit.id) to configured(context).keys

    /** What [gate] decided. */
    sealed interface Gate {
        /** Not an Orbit chat or not an MCP server: nothing to do. */
        data object Skip : Gate
        /** An Orbit's MCP server that may be loaded now; report how connecting went with [connected]. */
        data class Go(val id: String) : Gate
        data class Blocked(val why: String) : Gate
    }

    /**
     * Before use_package loads [name] in an Orbit chat: starts it when it is not up yet, or says why
     * it cannot be used. One that failed is not tried again by itself; orbit_mcp start retries it.
     */
    suspend fun gate(context: Context, chatId: String?, name: String): Gate {
        val orbit = owner(context, chatId) ?: return Gate.Skip
        val server = configured(context)[name] ?: return Gate.Skip
        val on = enabled(context, orbit.id)
        val status = ensureStarted(context, name, on, manual = false)
        return if (status.state == State.READY && registered(context, name)) Gate.Go(name)
        else Gate.Blocked(explain(server.name, status))
    }
}
