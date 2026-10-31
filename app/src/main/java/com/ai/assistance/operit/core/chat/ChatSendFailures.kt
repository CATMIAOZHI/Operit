package com.ai.assistance.operit.core.chat

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.api.chat.llmprovider.HttpStatusCodeException
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * User messages whose turn never reached the model: the request did not get through, or no reply
 * came back before the connection gave up. Orbit marks them with a red sign, as a messenger does,
 * so they can be sent again. Saved, so the sign is still there after the app restarts.
 */
object ChatSendFailures {
    private const val PREFS = "orbit_unsent_messages"
    private const val KEY = "failed"
    /** Marks of deleted messages are never shown; a cap keeps them from piling up. */
    private const val MAX_PER_CHAT = 50
    private const val MAX_CHATS = 50

    private val _failed = MutableStateFlow<Map<String, Set<Long>>>(emptyMap())
    @Volatile private var prefs: SharedPreferences? = null
    private val loader = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Chat id to the times of its failed messages. Returns at once; the saved marks are read off
     * the main thread and show up when they are loaded.
     */
    fun failed(context: Context): StateFlow<Map<String, Set<Long>>> {
        if (prefs == null) {
            val app = context.applicationContext
            loader.launch { load(app) }
        }
        return _failed
    }

    fun mark(context: Context, chatId: String, timestamp: Long) = change(context) { all ->
        val times = (all[chatId].orEmpty() + timestamp).sorted().takeLast(MAX_PER_CHAT).toSet()
        (all - chatId).entries.toList().takeLast(MAX_CHATS - 1).associate { it.key to it.value } + (chatId to times)
    }

    fun clear(context: Context, chatId: String, timestamp: Long) = change(context) { all ->
        val left = all[chatId]?.minus(timestamp) ?: return@change all
        if (left.isEmpty()) all - chatId else all + (chatId to left)
    }

    /**
     * A later turn in the chat reached the model. Failed messages stay in the chat's history, so
     * that turn carried them too: they are no longer unsent.
     */
    fun clearChat(context: Context, chatId: String) {
        load(context)
        if (_failed.value.containsKey(chatId)) change(context) { it - chatId }
    }

    private fun change(context: Context, block: (Map<String, Set<Long>>) -> Map<String, Set<Long>>) {
        load(context)
        // One change at a time, state and saved copy together: a slower change can not write an
        // older snapshot over a newer one.
        synchronized(this) {
            val before = _failed.value
            val next = block(before)
            // Most sends have nothing marked: nothing to write.
            if (next == before) return
            _failed.value = next
            val json = JSONObject()
            next.forEach { (chat, times) -> json.put(chat, JSONArray(times.toList())) }
            prefs?.edit()?.putString(KEY, json.toString())?.apply()
        }
    }

    private fun load(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val loaded = runCatching {
                val json = JSONObject(p.getString(KEY, null) ?: "{}")
                json.keys().asSequence().associateWith { chat ->
                    val times = json.getJSONArray(chat)
                    (0 until times.length()).map { times.getLong(it) }.toSet()
                }.filterValues { it.isNotEmpty() }
            }.getOrDefault(emptyMap())
            _failed.value = loaded
            prefs = p
        }
    }

    /**
     * The request could not reach the server or got no answer from it. A server that answered
     * with an error status (a wrong key, a full quota) is not this: sending again would not help.
     */
    fun isUnreachable(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        var network = false
        while (current != null && depth < 8) {
            if (current is HttpStatusCodeException) return false
            if (
                current is UnknownHostException || current is ConnectException ||
                current is NoRouteToHostException || current is SocketException ||
                current is SSLException || current is InterruptedIOException ||
                current is EOFException
            ) network = true
            current = current.cause
            depth++
        }
        return network
    }
}
