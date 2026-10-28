package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Messages the user kept from an Orbit's chats, like a messaging app's favorites. Each one keeps
 * the words as they were shown, so it still reads the same later, and where it was said, so the
 * search page can open the chat on it. Kept in the Orbit's own folder.
 */
internal object OrbitFavorites {
    const val MAX_KEPT = 2_000
    const val MAX_TEXT = 4_000

    data class Favorite(
        /** The chat and the bubble ("chatId/key"), so one bubble is kept once. */
        val id: String,
        val chatId: String,
        /** When the message was said; the chat opens on it. */
        val timestamp: Long,
        /** "user" or "ai". */
        val sender: String,
        val text: String,
        val savedAt: Long,
    )

    fun idOf(chatId: String, key: String) = "$chatId/$key"

    private val changes = MutableStateFlow(0)
    /** Bumped on every change, so lists and menus read again. */
    val version: StateFlow<Int> = changes
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }
    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "favorites.json")

    /** Newest saved first. */
    suspend fun list(context: Context, orbitId: String): List<Favorite> = withContext(Dispatchers.IO) {
        lock(orbitId).withLock { read(context, orbitId) }.sortedByDescending { it.savedAt }
    }

    /** Keeps [items]; ones already kept stay as they were. Returns how many were new. */
    suspend fun add(context: Context, orbitId: String, items: List<Favorite>): Int = withContext(Dispatchers.IO) {
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            val have = current.mapTo(HashSet()) { it.id }
            val fresh = items.filter { it.text.isNotBlank() && have.add(it.id) }
                .map { it.copy(text = it.text.take(MAX_TEXT)) }
            if (fresh.isNotEmpty()) {
                // The oldest saved ones go first once the list is full.
                write(context, orbitId, (current + fresh).sortedByDescending { it.savedAt }.take(MAX_KEPT))
            }
            fresh.size
        }
    }

    suspend fun remove(context: Context, orbitId: String, id: String) = withContext(Dispatchers.IO) {
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            val left = current.filterNot { it.id == id }
            if (left.size != current.size) write(context, orbitId, left)
        }
    }

    private fun read(context: Context, orbitId: String): List<Favorite> {
        val f = file(context, orbitId)
        if (!f.isFile) return emptyList()
        val array = runCatching { JSONObject(f.readText()).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Favorite(
                id = id,
                chatId = o.optString("chat_id"),
                timestamp = o.optLong("timestamp"),
                sender = o.optString("sender"),
                text = o.optString("text"),
                savedAt = o.optLong("saved_at"),
            )
        }
    }

    private fun write(context: Context, orbitId: String, items: List<Favorite>) {
        val f = file(context, orbitId)
        check(f.parentFile!!.mkdirs() || f.parentFile!!.isDirectory)
        val text = JSONObject().put("items", JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("chat_id", it.chatId).put("timestamp", it.timestamp)
                    .put("sender", it.sender).put("text", it.text).put("saved_at", it.savedAt))
            }
        }).toString()
        val temporary = File(f.parentFile, "${f.name}.tmp")
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        changes.value++
    }
}
