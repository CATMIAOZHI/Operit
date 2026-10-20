package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cards Orbit puts in the chat with a tool, like Muse: a secure entry card for a login or an API
 * key, and a picture or file it sends. The tool's result carries the card under [KEY]; the Orbit
 * chat draws it where the tool ran. Nothing secret is ever in a card.
 */
internal object OrbitChatCards {
    const val KEY = "orbit_card"
    const val VAULT_LOGIN = "vault_login"
    const val VAULT_KEY = "vault_api_key"
    const val IMAGE = "image"
    const val FILE = "file"

    val imageTypes = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun vault(request: OrbitVault.Request): JSONObject = JSONObject()
        .put("kind", if (request.kind == OrbitVault.LOGIN) VAULT_LOGIN else VAULT_KEY)
        .put("orbit_id", request.orbitId)
        .put("chat_id", request.chatId)
        .put("site", request.site)
        .put("page_url", request.pageUrl)
        .put("username_hint", request.usernameHint)
        .put("api_hosts", JSONArray(request.apiHosts))
        .put("placement", request.placement)

    /** [width] and [height] of a picture let the chat size it before it loads, so nothing jumps. */
    fun attachment(orbitId: String, path: String, name: String, size: Long, width: Int = 0, height: Int = 0): JSONObject = JSONObject()
        .put("kind", if (name.substringAfterLast('.', "").lowercase() in imageTypes) IMAGE else FILE)
        .put("orbit_id", orbitId)
        .put("path", path)
        .put("name", name)
        .put("size", size)
        .apply { if (width > 0 && height > 0) put("width", width).put("height", height) }

    /** The entry sheet a vault card opens, or null when [card] is not a complete vault card. */
    fun vaultRequest(card: JSONObject): OrbitVault.Request? {
        val kind = when (card.optString("kind")) {
            VAULT_LOGIN -> OrbitVault.LOGIN
            VAULT_KEY -> OrbitVault.API_KEY
            else -> return null
        }
        val orbitId = card.optString("orbit_id").takeIf { it.isNotBlank() } ?: return null
        val site = card.optString("site").takeIf { it.isNotBlank() } ?: return null
        val hosts = card.optJSONArray("api_hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .filter { it.isNotBlank() }
        if (kind == OrbitVault.API_KEY && hosts.isEmpty()) return null
        return OrbitVault.Request(
            orbitId, card.optString("chat_id"), kind, site, card.optString("page_url"),
            card.optString("username_hint"), hosts, card.optString("placement").ifEmpty { "bearer" },
        )
    }
}

/**
 * The hidden note Orbit gets after the user saves a login or key from a card. Closing the card
 * says nothing, so Orbit never nags about a card the user chose to skip.
 */
internal object OrbitVaultNotice {
    const val MARKER = "[orbit-vault]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The title the work list shows for the turn this note started. */
    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun build(english: Boolean, request: OrbitVault.Request, id: String): String {
        val login = request.kind == OrbitVault.LOGIN
        return if (english) {
            val title = if (login) "Login for ${request.site} saved" else "API key for ${request.site} saved"
            "$MARKER $title\n" +
                "The user filled in the secure card and saved " +
                (if (login) "a login for ${request.site}" else "an API key for ${request.site} (sent only to ${request.apiHosts.joinToString()})") +
                " (credential_id $id). This is not a message from the user; they typed nothing here and cannot see this note.\n" +
                "If you were in the middle of something that needed it, carry on now with orbit_vault " +
                (if (login) "fill" else "http_request") +
                ". Otherwise reply with one short line, or call ${OrbitReactionNotice.NOTHING_TO_DO} if nothing needs saying. " +
                "Never repeat a saved value, and do not mention hidden notes."
        } else {
            val title = if (login) "已保存 ${request.site} 的登录" else "已保存 ${request.site} 的 API 密钥"
            "$MARKER $title\n" +
                "用户在安全卡片里填好并保存了" +
                (if (login) " ${request.site} 的登录" else " ${request.site} 的 API 密钥（只发往 ${request.apiHosts.joinToString()}）") +
                "（credential_id $id）。这不是用户发的消息，用户没有在这里打字，也看不到这段话。\n" +
                "如果刚才有事情正等着它，现在就用 orbit_vault 的 " + (if (login) "fill" else "http_request") +
                " 接着做；没有的话简短回一句，没什么要说就调用 ${OrbitReactionNotice.NOTHING_TO_DO}。" +
                "绝不复述保存的值，也不要提“隐藏消息”这些内部说法。"
        }
    }

    /** Tells Orbit in the card's chat once that chat is free; a busy chat is waited out a while. */
    fun send(context: Context, orbit: OrbitProfile, request: OrbitVault.Request, id: String) {
        val chatId = request.chatId.ifBlank { orbit.chatId }
        if (chatId.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            try {
                val notice = build(LocaleUtils.usesEnglishPrompt(app), request, id)
                // Another note or reply can take the chat at the same moment; wait for it and try again.
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, orbit, chatId, notice, left, left)
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about a saved credential: ${e.message}")
            }
        }
    }
}
