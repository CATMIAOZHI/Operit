package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Emoji reactions Orbit puts on the user's messages, like a tap on a message in a chat app
 * (Muse's react_to_user_message), and the user's on Orbit's. Kept in Orbit's own folder, keyed by
 * chat and bubble, so the chat history itself is not changed. A bubble key is the message time
 * for the message's words, or [pieceKey] for a card inside it.
 */
internal class OrbitReactions private constructor(private val file: File) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, Map<String, String>>?>(null)

    /** Bubble key -> emoji for one chat. */
    fun observe(chatId: String): Flow<Map<String, String>> =
        state.asStateFlow().onStart { if (state.value == null) load() }.map { it?.get(chatId).orEmpty() }

    suspend fun set(chatId: String, key: String, emoji: String) = mutex.withLock {
        val all = read().toMutableMap()
        val chat = all[chatId].orEmpty().toMutableMap()
        chat[key] = emoji
        // Old reactions are only decoration; keep the newest ones.
        all[chatId] = chat.entries.sortedByDescending { timeOf(it.key) }.take(MAX_PER_CHAT).associate { it.key to it.value }
        write(all)
    }

    /** Sets [emoji] on a message, or takes it off when it is already the one there, like a tapback. */
    suspend fun toggle(chatId: String, key: String, emoji: String) = mutex.withLock {
        val all = read().toMutableMap()
        val chat = all[chatId].orEmpty().toMutableMap()
        if (chat[key] == emoji) chat.remove(key) else chat[key] = emoji
        all[chatId] = chat.entries.sortedByDescending { timeOf(it.key) }.take(MAX_PER_CHAT).associate { it.key to it.value }
        write(all)
    }

    /** Forgets every reaction in a chat that was deleted. */
    suspend fun removeChat(chatId: String) = mutex.withLock {
        val all = read()
        if (chatId in all) write(all - chatId)
    }

    private suspend fun load() {
        mutex.withLock { read() }
    }

    private suspend fun read(): Map<String, Map<String, String>> = state.value ?: withContext(Dispatchers.IO) {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        json.keys().asSequence().toList().associateWith { chatId ->
            val chat = json.optJSONObject(chatId) ?: JSONObject()
            chat.keys().asSequence().toList().map { key -> key to chat.optString(key) }
                .filter { it.second.isNotEmpty() }.toMap()
        }
    }.also { state.value = it }

    private suspend fun write(all: Map<String, Map<String, String>>) {
        val json = JSONObject()
        all.forEach { (chatId, chat) ->
            if (chat.isNotEmpty()) json.put(chatId, JSONObject().apply { chat.forEach { (t, e) -> put(t, e) } })
        }
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "Could not save reactions" }
            }
        }
        state.value = all.filterValues { it.isNotEmpty() }
    }

    companion object {
        private const val MAX_PER_CHAT = 300
        const val MAX_EMOJI_LENGTH = 16
        private val instances = ConcurrentHashMap<String, OrbitReactions>()

        /** A card or Dynamic UI bubble inside the message sent at [timestamp]: `card:<time>:<n>` or `ui:<time>:<n>`. */
        fun pieceKey(kind: String, timestamp: Long, ordinal: Int) = "$kind:$timestamp:$ordinal"

        /** The message time a bubble key belongs to, used to keep the newest reactions. */
        fun timeOf(key: String): Long = key.toLongOrNull() ?: key.substringAfter(':').substringBefore(':').toLongOrNull() ?: 0L

        /** Orbit's reactions on the user's messages. */
        fun of(context: Context, orbitId: String): OrbitReactions = instances.computeIfAbsent(orbitId) {
            OrbitReactions(File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "reactions.json"))
        }

        /** The user's reactions on Orbit's messages, kept apart from Orbit's own. */
        fun ofUser(context: Context, orbitId: String): OrbitReactions = instances.computeIfAbsent("$orbitId:user") {
            OrbitReactions(File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "user_reactions.json"))
        }

        /** Forgets the cached copy after the Orbit folder is deleted. */
        fun drop(orbitId: String) {
            instances.remove(orbitId)
            instances.remove("$orbitId:user")
        }

        /** One emoji (a short sequence like a flag or a skin tone is fine), never words. */
        fun isEmoji(text: String): Boolean {
            if (text.isEmpty() || text.length > MAX_EMOJI_LENGTH) return false
            // Keycaps such as 1️⃣ start with a digit; any other letter or digit means words.
            val keycap = text.contains('\u20E3')
            if (!keycap && text.any { it.isLetterOrDigit() }) return false
            if (keycap) return text.length <= 3 && text.first().let { it.isDigit() || it == '#' || it == '*' }
            val breaker = java.text.BreakIterator.getCharacterInstance()
            breaker.setText(text)
            var count = 0
            while (breaker.next() != java.text.BreakIterator.DONE) count++
            return count in 1..2 && text.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        }
    }
}
