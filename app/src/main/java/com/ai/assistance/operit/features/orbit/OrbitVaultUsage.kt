package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Who used which saved key, merged into one row per key so a hook that checks every 15 minutes
 * does not bury the user's approvals. Kept apart from the approval list; a key's row stays after
 * the key is deleted, with the site it had.
 */
internal object OrbitVaultUsage {
    /** Orbit itself: its chat, terminal, proxy and http_request. */
    const val ORBIT = "orbit"
    /** Orbit filling a login in the built-in browser. */
    const val BROWSER = "browser"
    fun hook(taskId: String) = "hook:$taskId"
    fun pack(name: String) = "package:$name"

    data class Caller(val key: String, val label: String, val count: Long, val last: Long)
    data class Key(val entryId: String, val site: String, val kind: String, val total: Long, val last: Long,
        val callers: List<Caller>)

    private const val MAX_KEYS = 500
    private const val MAX_CALLERS = 50
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val changes = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }
    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "vault_usage.json")

    private class Use(val site: String, val kind: String, var count: Long, var last: Long, var label: String)
    /** Uses noted but not written yet, per Orbit, by entry id and caller; guarded by itself. */
    private val pending = HashMap<String, LinkedHashMap<Pair<String, String>, Use>>()
    private const val MAX_PENDING = 2_000
    /** Uses are gathered for this long and written together, so a busy proxy does not rewrite the file per request. */
    private const val FLUSH_DELAY_MS = 3_000L

    /**
     * Notes one use without making the caller wait; [label] is what the user sees for [caller]
     * (a hook's title), empty to keep the one already noted.
     */
    fun record(context: Context, orbitId: String, entry: OrbitVault.Entry, caller: String, label: String = "") {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val first = synchronized(pending) {
            val forOrbit = pending.getOrPut(orbitId) { LinkedHashMap() }
            val first = forOrbit.isEmpty()
            val use = forOrbit[entry.id to caller]
            if (use != null) {
                use.count++
                use.last = now
                if (label.isNotBlank()) use.label = label
            } else if (forOrbit.size < MAX_PENDING) {
                forOrbit[entry.id to caller] = Use(entry.site, entry.kind, 1, now, label)
            }
            first
        }
        if (first) scope.launch {
            delay(FLUSH_DELAY_MS)
            flush(app, orbitId)
        }
    }

    private suspend fun flush(app: Context, orbitId: String) {
        val uses = synchronized(pending) { pending.remove(orbitId) } ?: return
        runCatching {
            lock(orbitId).withLock {
                // A deleted Orbit's folder is not made again by a late write.
                if (orbitId in gone || OrbitPreferences.getInstance(app).current().orbits.none { it.id == orbitId && !it.deleting }) {
                    return@withLock
                }
                // An unreadable file is left alone rather than replaced by these uses.
                val root = read(app, orbitId, strict = true)
                val keys = root.optJSONObject("keys") ?: JSONObject().also { root.put("keys", it) }
                uses.forEach { (id, use) ->
                    val (entryId, caller) = id
                    val row = keys.optJSONObject(entryId) ?: JSONObject().also { keys.put(entryId, it) }
                    row.put("site", use.site).put("kind", use.kind)
                        .put("total", row.optLong("total") + use.count).put("last", maxOf(row.optLong("last"), use.last))
                    val callers = row.optJSONObject("callers") ?: JSONObject().also { row.put("callers", it) }
                    val one = callers.optJSONObject(caller) ?: JSONObject().also { callers.put(caller, it) }
                    one.put("count", one.optLong("count") + use.count).put("last", maxOf(one.optLong("last"), use.last))
                    // A hook seen only through the proxy has no title here: take it from its task while it exists.
                    val label = use.label.ifBlank {
                        if (caller.startsWith("hook:")) AppDatabase.getDatabase(app).orbitTaskDao()
                            .task(caller.removePrefix("hook:"))?.title.orEmpty() else ""
                    }
                    if (label.isNotBlank()) one.put("label", label.take(80))
                    prune(callers, MAX_CALLERS)
                }
                prune(keys, MAX_KEYS)
                write(app, orbitId, root)
            }
            changes.update { it + 1 }
        }
    }
    /** Drops the rows used longest ago once there are more than [max]. */
    private fun prune(rows: JSONObject, max: Int) {
        if (rows.length() <= max) return
        rows.keys().asSequence().toList().sortedBy { rows.optJSONObject(it)?.optLong("last") ?: 0L }
            .take(rows.length() - max).forEach { rows.remove(it) }
    }

    /** Orbits being deleted: nothing more is written for them. */
    private val gone = ConcurrentHashMap.newKeySet<String>()

    /**
     * Called before an Orbit's folder is deleted: drops its unwritten uses and waits for a write
     * already running, so none lands after the folder is gone.
     */
    suspend fun forget(orbitId: String) {
        gone.add(orbitId)
        synchronized(pending) { pending.remove(orbitId) }
        lock(orbitId).withLock { }
    }

    /** Most recently used first. */
    suspend fun list(context: Context, orbitId: String): List<Key> = withContext(Dispatchers.IO) {
        val keys = lock(orbitId).withLock { read(context, orbitId) }.optJSONObject("keys") ?: return@withContext emptyList()
        keys.keys().asSequence().mapNotNull { id ->
            val row = keys.optJSONObject(id) ?: return@mapNotNull null
            val callers = row.optJSONObject("callers")
            Key(id, row.optString("site"), row.optString("kind"), row.optLong("total"), row.optLong("last"),
                callers?.keys()?.asSequence()?.mapNotNull { key ->
                    val c = callers.optJSONObject(key) ?: return@mapNotNull null
                    Caller(key, c.optString("label"), c.optLong("count"), c.optLong("last"))
                }?.sortedByDescending { it.last }?.toList().orEmpty())
        }.sortedByDescending { it.last }.toList()
    }

    fun observe(context: Context, orbitId: String): Flow<List<Key>> =
        changes.map { list(context, orbitId) }.flowOn(Dispatchers.IO)

    /** [strict]: throw when the file exists but cannot be read, so a write does not wipe it. */
    private fun read(context: Context, orbitId: String, strict: Boolean = false): JSONObject {
        val f = file(context, orbitId)
        if (!f.isFile) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrElse { if (strict) throw it else JSONObject() }
    }

    private fun write(context: Context, orbitId: String, root: JSONObject) {
        val f = file(context, orbitId)
        check(f.parentFile!!.mkdirs() || f.parentFile!!.isDirectory)
        val temporary = File.createTempFile(".vault-usage-", ".tmp", f.parentFile)
        try {
            temporary.writeText(root.toString())
            java.nio.file.Files.move(temporary.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
