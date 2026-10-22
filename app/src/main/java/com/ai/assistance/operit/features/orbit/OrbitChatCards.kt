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
    const val VAULT_OAUTH = "vault_oauth"
    const val IMAGE = "image"
    const val FILE = "file"
    const val APPROVAL = "approval"
    /** A phone setting Orbit needs, turned on from the card ([OrbitPermissionCards]). */
    const val PERMISSION = "permission"

    val imageTypes = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    /** Which bubble a card is, set when the chat moves cards to the end of a turn (see OrbitSpeech.history). */
    const val PIECE_KEY = "piece_key"

    /**
     * A card in a word or two, for a reply quote or a reaction notice: what it is and its title,
     * never a path, a value or the card's code.
     */
    fun summary(card: String, english: Boolean): String {
        val o = runCatching { JSONObject(card) }.getOrNull()
        fun field(name: String) = o?.optString(name).orEmpty().replace(Regex("[\\r\\n\\[\\]<>]"), " ").trim().take(60)
        val (what, title) = when (o?.optString("kind")) {
            APPROVAL -> (if (english) "Approval card" else "批准卡片") to field("title")
            PERMISSION -> (if (english) "Permission card" else "权限卡片") to
                (if (o?.optString("permission") == OrbitPermissionCards.KEEP_ALIVE) (if (english) "background keep-alive" else "后台保活") else "")
            VAULT_LOGIN -> (if (english) "Vault login card" else "登录保险箱卡片") to field("site")
            VAULT_KEY -> (if (english) "API key card" else "API 密钥卡片") to field("site")
            VAULT_OAUTH -> (if (english) "OAuth login card" else "OAuth 登录卡片") to field("site")
            IMAGE -> (if (english) "Picture" else "图片") to field("name")
            FILE -> (if (english) "File" else "文件") to field("name")
            else -> (if (english) "Card" else "卡片") to ""
        }
        return if (title.isEmpty()) "[$what]" else if (english) "[$what: $title]" else "[$what：$title]"
    }

    // The chat is shown again on every change; each card is marked only once.
    private val keyed = object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    /** [card] marked as bubble [key], so its reactions stay with it wherever the chat draws it. */
    fun withPieceKey(card: String, key: String): String {
        if (card.contains("\"$PIECE_KEY\"")) return card
        val cacheKey = "$key\u0000$card"
        synchronized(keyed) { keyed[cacheKey] }?.let { return it }
        val marked = runCatching { JSONObject(card).put(PIECE_KEY, key).toString() }.getOrDefault(card)
        synchronized(keyed) { keyed[cacheKey] = marked }
        return marked
    }

    fun vault(request: OrbitVault.Request): JSONObject = JSONObject()
        .put("kind", when (request.kind) {
            OrbitVault.LOGIN -> VAULT_LOGIN
            OrbitVault.OAUTH -> VAULT_OAUTH
            else -> VAULT_KEY
        })
        .put("orbit_id", request.orbitId)
        .put("chat_id", request.chatId)
        .put("site", request.site)
        .put("page_url", request.pageUrl)
        .put("username_hint", request.usernameHint)
        .put("api_hosts", JSONArray(request.apiHosts))
        .put("placement", request.placement)
        .put("new_password", request.newPassword)
        .put("requested_at", request.requestedAt)
        .apply { if (request.envName.isNotEmpty()) put("env_name", request.envName).put("env_reason", request.envReason) }
        .apply { if (request.kind == OrbitVault.OAUTH) {
            put("authorization_url", request.authorizationUrl).put("token_url", request.tokenUrl)
                .put("client_id", request.clientId).put("scope", request.scope)
        } }

    /** [width] and [height] of a picture let the chat size it before it loads, so nothing jumps. */
    fun attachment(orbitId: String, path: String, name: String, size: Long, width: Int = 0, height: Int = 0): JSONObject = JSONObject()
        .put("kind", if (name.substringAfterLast('.', "").lowercase() in imageTypes) IMAGE else FILE)
        .put("orbit_id", orbitId)
        .put("path", path)
        .put("name", name)
        .put("size", size)
        .apply { if (width > 0 && height > 0) put("width", width).put("height", height) }

    /** An approval request; the card reads its title and answer from the approval list. */
    fun approval(orbitId: String, approval: OrbitApprovals.Approval): JSONObject = JSONObject()
        .put("kind", APPROVAL)
        .put("orbit_id", orbitId)
        .put("approval_id", approval.id)
        .put("title", approval.title)
        .put("detail", approval.detail.take(400))

    /** A card that turns on [permission] (see [OrbitPermissionCards]); [cardId] keeps its own state. */
    fun permission(orbitId: String, chatId: String, permission: String, cardId: String): JSONObject = JSONObject()
        .put("kind", PERMISSION)
        .put("orbit_id", orbitId)
        .put("chat_id", chatId)
        .put("permission", permission)
        .put("card_id", cardId)

    /** The entry sheet a vault card opens, or null when [card] is not a complete vault card. */
    fun vaultRequest(card: JSONObject): OrbitVault.Request? {
        val kind = when (card.optString("kind")) {
            VAULT_LOGIN -> OrbitVault.LOGIN
            VAULT_KEY -> OrbitVault.API_KEY
            VAULT_OAUTH -> OrbitVault.OAUTH
            else -> return null
        }
        val orbitId = card.optString("orbit_id").takeIf { it.isNotBlank() } ?: return null
        val site = card.optString("site").takeIf { it.isNotBlank() } ?: return null
        val hosts = card.optJSONArray("api_hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .filter { it.isNotBlank() }
        val envName = card.optString("env_name").takeIf { kind == OrbitVault.API_KEY && it.matches(OrbitVault.ENV_NAME) }.orEmpty()
        // A key that only goes into the terminal may have no hosts for the proxy.
        if (kind == OrbitVault.API_KEY && hosts.isEmpty() && envName.isEmpty()) return null
        return OrbitVault.Request(
            orbitId, card.optString("chat_id"), kind, site, card.optString("page_url"),
            card.optString("username_hint"), hosts, card.optString("placement").ifEmpty { "bearer" },
            newPassword = kind == OrbitVault.LOGIN && card.optBoolean("new_password"),
            requestedAt = card.optLong("requested_at"),
            envName = envName,
            envReason = if (envName.isEmpty()) "" else card.optString("env_reason").take(200),
            authorizationUrl = card.optString("authorization_url"), tokenUrl = card.optString("token_url"),
            clientId = card.optString("client_id"), scope = card.optString("scope"),
        ).takeIf { kind != OrbitVault.OAUTH || runCatching { OrbitOAuthProtocol.validate(it) }.isSuccess }
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

    /**
     * [previous] is the password the site most likely still takes, which a new password left in
     * place; [untried] are earlier new passwords for the same user.
     */
    fun build(english: Boolean, request: OrbitVault.Request, id: String, previous: String? = null,
        untried: List<String> = emptyList(), envName: String = ""): String {
        val login = request.kind == OrbitVault.LOGIN
        val fresh = login && request.newPassword
        val hostsEn = request.apiHosts.takeIf { it.isNotEmpty() }?.let { " (sent only to ${it.joinToString()})" }.orEmpty()
        val hostsZh = request.apiHosts.takeIf { it.isNotEmpty() }?.let { "（只发往 ${it.joinToString()}）" }.orEmpty()
        val envEn = when {
            request.envName.isEmpty() -> ""
            envName.isNotEmpty() -> "The user allowed loading this key into the terminal as \$$envName; get the command with orbit_vault env.\n"
            else -> "The user did not allow loading this key into the terminal; use it through http_request or the terminal " +
                "proxy, or tell the user plainly what cannot be done without it.\n"
        }
        val envZh = when {
            request.envName.isEmpty() -> ""
            envName.isNotEmpty() -> "用户允许把这把密钥载入终端，变量名 \$$envName；用 orbit_vault 的 env 拿载入命令。\n"
            else -> "用户没有允许把这把密钥载入终端；用 http_request 或终端代理来用它，做不到的就跟用户直说。\n"
        }
        val nextEn = when {
            login -> "fill"
            envName.isNotEmpty() -> "env"
            else -> "http_request"
        }
        return if (english) {
            val title = when {
                fresh -> "New password for ${request.site} saved"
                login -> "Login for ${request.site} saved"
                request.kind == OrbitVault.OAUTH -> "OAuth login for ${request.site} saved"
                else -> "API key for ${request.site} saved"
            }
            "$MARKER $title\n" +
                "The user filled in the secure card and saved " +
                (when {
                    fresh -> "a new password for ${request.site}, to fill into its sign-up or reset form"
                    login -> "a login for ${request.site}"
                    request.kind == OrbitVault.OAUTH -> "an OAuth login for ${request.site}$hostsEn"
                    else -> "an API key for ${request.site}$hostsEn"
                }) +
                " (credential_id $id). This is not a message from the user; they typed nothing here and cannot see this note.\n" +
                envEn +
                (previous?.let {
                    "The earlier password for this user is still saved as credential_id $it, for a form that asks for the " +
                        "current password. Once the site has taken the new one, delete $it with orbit_vault delete.\n"
                }.orEmpty()) +
                (untried.takeIf { it.isNotEmpty() }?.let {
                    "Earlier new passwords for this user are saved too (credential_id ${it.joinToString()}). Go by what the site " +
                        "said: one it took is the current password; delete the ones it did not take.\n"
                }.orEmpty()) +
                "If you were in the middle of something that needed it, carry on now with orbit_vault " +
                nextEn +
                ". Otherwise reply with one short line, or call ${OrbitReactionNotice.NOTHING_TO_DO} if nothing needs saying. " +
                "Never repeat a saved value, and do not mention hidden notes."
        } else {
            val title = when {
                fresh -> "已保存 ${request.site} 的新密码"
                login -> "已保存 ${request.site} 的登录"
                request.kind == OrbitVault.OAUTH -> "已保存 ${request.site} 的 OAuth 登录"
                else -> "已保存 ${request.site} 的 API 密钥"
            }
            "$MARKER $title\n" +
                "用户在安全卡片里填好并保存了" +
                (when {
                    fresh -> " ${request.site} 的新密码，用来填进注册或重设密码的表单"
                    login -> " ${request.site} 的登录"
                    request.kind == OrbitVault.OAUTH -> " ${request.site} 的 OAuth 登录$hostsZh"
                    else -> " ${request.site} 的 API 密钥$hostsZh"
                }) +
                "（credential_id $id）。这不是用户发的消息，用户没有在这里打字，也看不到这段话。\n" +
                envZh +
                (previous?.let {
                    "这个用户原来的密码还留着（credential_id $it），表单要“当前密码”时用它；网站确认改好新密码后，用 orbit_vault 的 delete 删掉 $it。\n"
                }.orEmpty()) +
                (untried.takeIf { it.isNotEmpty() }?.let {
                    "之前设过的新密码也还在（credential_id ${it.joinToString()}）。按网站刚才的结果判断：网站采用了的就是当前密码，没采用的删掉。\n"
                }.orEmpty()) +
                "如果刚才有事情正等着它，现在就用 orbit_vault 的 " + nextEn +
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
                val entries = OrbitVault.entries(app, orbit.id)
                val (previous, untried) = if (request.kind == OrbitVault.LOGIN && request.newPassword) {
                    entries.firstOrNull { it.id == id }?.let { saved ->
                        OrbitVault.earlierPasswords(entries, saved).let { (current, rest) -> current?.id to rest.map { it.id } }
                    } ?: (null to emptyList())
                } else null to emptyList()
                val envName = entries.firstOrNull { it.id == id }?.envName.orEmpty()
                val notice = build(LocaleUtils.usesEnglishPrompt(app), request, id, previous, untried, envName)
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
