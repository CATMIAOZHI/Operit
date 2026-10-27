package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.util.AppLogger
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps a message the user sent while the chat compacts its history on disk until it goes out.
 * The input box was already emptied for it, so if the app is closed before the summary ends the
 * message would otherwise be gone; on the next start it comes back to the input box instead.
 */
internal object HeldSendStore {
    data class Saved(
        val chatId: String,
        val text: String,
        val attachments: List<AttachmentInfo>,
        val reply: ChatMessage?,
    )

    private const val PREFS = "chat_held_sends"
    private const val TAG = "HeldSendStore"
    private val json = Json { ignoreUnknownKeys = true }
    /** Writes run in order off the main thread, so a removal never lands before its save. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Messages this process is still holding; they are never handed back while in flight. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, key: String, saved: Saved) {
        live += key
        val app = context.applicationContext
        writer.execute { runCatching {
            val attachments = JSONArray()
            saved.attachments.forEach {
                attachments.put(
                    JSONObject()
                        .put("filePath", it.filePath)
                        .put("fileName", it.fileName)
                        .put("mimeType", it.mimeType)
                        .put("fileSize", it.fileSize)
                        .put("content", it.content)
                )
            }
            val entry = JSONObject()
                .put("chatId", saved.chatId)
                .put("text", saved.text)
                .put("attachments", attachments)
            saved.reply?.let { entry.put("reply", json.encodeToString(ChatMessage.serializer(), it)) }
            if (key in live) prefs(app).edit().putString(key, entry.toString()).commit()
        }.onFailure { AppLogger.w(TAG, "Could not keep the waiting message: ${it.message}") } }
    }

    fun forget(context: Context, key: String) {
        live -= key
        val app = context.applicationContext
        writer.execute { prefs(app).edit().remove(key).commit() }
    }

    /** Takes every message left from an earlier run; each is handed out once. Call off the main thread. */
    fun claimAll(context: Context): List<Saved> = writer.submit<List<Saved>> { claimNow(context) }.get()

    private fun claimNow(context: Context): List<Saved> {
        val prefs = prefs(context)
        val left = prefs.all.filterKeys { it !in live }
        if (left.isEmpty()) return emptyList()
        prefs.edit().apply { left.keys.forEach(::remove) }.commit()
        return left.values.mapNotNull { value ->
            runCatching {
                val entry = JSONObject(value as String)
                val list = entry.optJSONArray("attachments") ?: JSONArray()
                Saved(
                    chatId = entry.getString("chatId"),
                    text = entry.optString("text"),
                    attachments = (0 until list.length()).map { i ->
                        val a = list.getJSONObject(i)
                        AttachmentInfo(
                            filePath = a.getString("filePath"),
                            fileName = a.optString("fileName"),
                            mimeType = a.optString("mimeType"),
                            fileSize = a.optLong("fileSize"),
                            content = a.optString("content"),
                        )
                    },
                    reply = entry.optString("reply").takeIf { it.isNotBlank() }
                        ?.let { json.decodeFromString(ChatMessage.serializer(), it) },
                )
            }.getOrNull()
        }
    }
}
