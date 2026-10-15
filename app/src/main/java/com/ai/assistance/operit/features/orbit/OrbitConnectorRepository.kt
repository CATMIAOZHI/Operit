package com.ai.assistance.operit.features.orbit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** No model-facing API returns endpoint paths, authentication material or response bodies. */
internal class OrbitConnectorRepository(private val context: Context, private val orbitId: String) {
    data class Description(val id: String, val revision: String, val name: String,
        val purpose: String, val host: String, val enabled: Boolean)
    internal data class Secret(val description: Description, val url: String, val bearer: String)
    private val file = OrbitSecretFile.forOrbit(context, orbitId)
    private val mutex = locks.computeIfAbsent(orbitId) { Mutex() }

    private fun read(): List<Secret> {
        try {
            val array = JSONArray(file.read() ?: "[]")
            return (0 until array.length()).map {
                val json = array.getJSONObject(it)
                val url = json.getString("url")
                Secret(Description(json.getString("id"), json.getString("revision"), json.getString("name"),
                    json.getString("purpose"), url.toHttpUrl().host, json.getBoolean("enabled")),
                    url, json.getString("bearer"))
            }
        } catch (_: Exception) {
            error("Protected connections could not be read. The existing store has been preserved.")
        }
    }
    private fun write(items: List<Secret>) = file.write(JSONArray(items.map {
        JSONObject().put("id", it.description.id).put("revision", it.description.revision)
            .put("name", it.description.name).put("purpose", it.description.purpose)
            .put("enabled", it.description.enabled).put("url", it.url).put("bearer", it.bearer)
    }).toString())
    private suspend fun requireOwner() {
        check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context)
            .current().orbits.any { it.id == orbitId && !it.deleting }) { "Orbit is unavailable" }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        file.delete()
        calls.values.filter { it.orbitId == orbitId }.forEach { it.call.cancel() }
    } }

    suspend fun list(): List<Description> = withContext(Dispatchers.IO) {
        mutex.withLock { read().map { it.description } }
    }
    /** A new connector is disabled until the user explicitly enables it. Secrets are never echoed. */
    suspend fun add(name: String, purpose: String, url: String, bearer: String) = withContext(Dispatchers.IO) {
        validate(name, purpose, url, bearer)
        mutex.withLock {
            requireOwner()
            val items = read()
            check(items.size < 20) { "At most 20 connections per Orbit" }
            val description = Description(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                name.trim(), purpose.trim(), url.toHttpUrl().host, false)
            write(items + Secret(description, url.trim(), bearer))
        }
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireOwner()
            val items = read()
            check(items.any { it.description.id == id })
            write(items.map { if (it.description.id == id) it.copy(description = it.description.copy(
                revision = UUID.randomUUID().toString(), enabled = enabled)) else it })
            cancel(id)
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(read().filterNot { it.description.id == id })
            cancel(id)
        }
    }
    /** Caller must enqueue while holding this lock, but must not await the response inside it. */
    internal suspend fun <T> authorized(id: String, revision: String, action: suspend (Secret) -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireOwner()
            val item = read().singleOrNull { it.description.id == id } ?: error("Connection is unavailable")
            check(item.description.enabled && item.description.revision == revision) {
                "Connection changed or was disabled. Read the connection list and request a new review."
            }
            action(item)
        } }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private data class ActiveCall(val orbitId: String, val connectorId: String, val call: Call)
        private val calls = ConcurrentHashMap<String, ActiveCall>()
        fun register(actionId: String, orbitId: String, connectorId: String, call: Call) {
            calls[actionId] = ActiveCall(orbitId, connectorId, call)
        }
        fun unregister(actionId: String, call: Call) {
            calls.computeIfPresent(actionId) { _, entry -> if (entry.call === call) null else entry }
        }
        private fun cancel(connectorId: String) { calls.values.filter { it.connectorId == connectorId }.forEach { it.call.cancel() } }
        internal fun validate(name: String, purpose: String, url: String, bearer: String) {
            require(name.trim().length in 1..80 && purpose.trim().length in 1..500)
            require(url.length <= 2048 && bearer.length <= 8192 && bearer.all { it.code in 33..126 })
            val endpoint = url.trim().toHttpUrl()
            require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null) {
                "Use a fixed HTTPS address without user information or fragment"
            }
        }
    }
}
