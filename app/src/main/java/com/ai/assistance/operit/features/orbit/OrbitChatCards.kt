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
 * Cards Orbit puts in the chat with a tool: a secure entry card for a login or an API
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
    /** The app's ChatGPT (Codex) login, which Codex models and the Codex Draw package use. */
    const val CHATGPT_LOGIN = "chatgpt_login"
    /** A provider account the app signs in to instead of using an API key (Claude, Grok, …). */
    const val ACCOUNT_LOGIN = "account_login"
    /** Opens the app's speech settings (read aloud and voice input) inside Orbit. */
    const val SPEECH_SETTINGS = "speech_settings"
    /** Exports this Orbit's data as one zip and hands it to the share sheet. */
    const val DATA_EXPORT = "data_export"
    /** Links the user's WeChat: the QR scan opens right from the card. */
    const val WEIXIN_LOGIN = "weixin_login"
    /** Opens a settings page from the chat: the WeChat link, role cards, model settings, tool access, models, notifications. */
    const val OPEN_PAGE = "open_page"
    const val PAGE_WEIXIN = "weixin"
    const val PAGE_ROLE_CARDS = "role_cards"
    const val PAGE_MODEL_CONFIG = "model_config"
    const val PAGE_TOOL_ACCESS = "tool_access"
    const val PAGE_TOOL_PERMISSIONS = "tool_permissions"
    const val PAGE_ORBIT_MODELS = "orbit_models"
    const val PAGE_NOTIFICATIONS = "notifications"
    val pages = listOf(PAGE_WEIXIN, PAGE_ROLE_CARDS, PAGE_MODEL_CONFIG, PAGE_TOOL_ACCESS, PAGE_TOOL_PERMISSIONS,
        PAGE_ORBIT_MODELS, PAGE_NOTIFICATIONS)

    /** Account sign-ins a card can offer: the id Orbit passes, and the account it stands for. */
    val accountProviders: Map<String, com.ai.assistance.operit.data.api.AccountProvider> = mapOf(
        "claude" to com.ai.assistance.operit.data.api.AccountProvider.CLAUDE,
        "grok" to com.ai.assistance.operit.data.api.AccountProvider.GROK,
        "antigravity" to com.ai.assistance.operit.data.api.AccountProvider.ANTIGRAVITY,
        "command_code" to com.ai.assistance.operit.data.api.AccountProvider.COMMAND_CODE,
    )

    fun accountLabel(provider: com.ai.assistance.operit.data.api.AccountProvider): String = when (provider) {
        com.ai.assistance.operit.data.api.AccountProvider.CLAUDE -> "Claude"
        com.ai.assistance.operit.data.api.AccountProvider.GROK -> "Grok"
        com.ai.assistance.operit.data.api.AccountProvider.ANTIGRAVITY -> "Antigravity"
        com.ai.assistance.operit.data.api.AccountProvider.COMMAND_CODE -> "Command Code"
    }

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
                (o?.optString("permission")?.takeIf { it in OrbitPermissionCards.kinds }
                    ?.let { OrbitPermissionCards.label(english, it) }.orEmpty())
            VAULT_LOGIN -> (if (english) "Vault login card" else "登录保险箱卡片") to field("site")
            VAULT_KEY -> (if (english) "API key card" else "API 密钥卡片") to field("site")
            VAULT_OAUTH -> (if (english) "OAuth login card" else "OAuth 登录卡片") to field("site")
            CHATGPT_LOGIN -> (if (english) "ChatGPT login card" else "ChatGPT 登录卡片") to ""
            ACCOUNT_LOGIN -> (if (english) "Account login card" else "账号登录卡片") to
                (accountProviders[o?.optString("provider")]?.let(::accountLabel).orEmpty())
            SPEECH_SETTINGS -> (if (english) "Voice settings card" else "语音设置卡片") to ""
            WEIXIN_LOGIN -> (if (english) "WeChat link card" else "连接微信卡片") to ""
            DATA_EXPORT -> (if (english) "Data export card" else "数据导出卡片") to ""
            OPEN_PAGE -> (if (english) "Settings card" else "设置卡片") to when (o?.optString("page")) {
                PAGE_WEIXIN -> if (english) "WeChat" else "微信"
                PAGE_ROLE_CARDS -> if (english) "Role cards" else "角色卡"
                PAGE_MODEL_CONFIG -> if (english) "Model settings" else "模型配置"
                PAGE_TOOL_ACCESS -> if (english) "Full access" else "全访问模式"
                PAGE_TOOL_PERMISSIONS -> if (english) "Tool permissions" else "工具权限"
                PAGE_ORBIT_MODELS -> if (english) "Models" else "模型"
                PAGE_NOTIFICATIONS -> if (english) "Notifications" else "通知"
                else -> ""
            }
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

    fun vault(request: OrbitVault.Request): JSONObject = signed(JSONObject()
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
        .apply { if (request.packageName.isNotEmpty()) put("package_name", request.packageName) }
        .apply { if (request.updateId.isNotEmpty()) put("update_id", request.updateId) }
        .apply { if (request.kind == OrbitVault.OAUTH) {
            put("authorization_url", request.authorizationUrl).put("token_url", request.tokenUrl)
                .put("client_id", request.clientId).put("scope", request.scope)
        } })

    /**
     * A vault card decides where a secret the user types goes (the site, the hosts, the sign-in
     * address). Only cards the vault tool made carry a valid signature; one Orbit wrote into its
     * own reply as a fake tool result has none, so it never opens an entry sheet.
     */
    private val signedVaultFields = listOf(
        "kind", "orbit_id", "chat_id", "site", "page_url", "username_hint", "api_hosts", "placement",
        "new_password", "requested_at", "env_name", "env_reason", "package_name", "update_id",
        "authorization_url", "token_url", "client_id", "scope",
    )

    private fun vaultSignature(card: JSONObject): String {
        val text = signedVaultFields.joinToString("\u0001") { name ->
            card.optJSONArray(name)?.let { a -> (0 until a.length()).joinToString("\u0002") { a.optString(it) } }
                ?: card.opt(name)?.toString().orEmpty()
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(OrbitCardKey.get())
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun signed(card: JSONObject): JSONObject = card.put("sig", vaultSignature(card))

    private fun signatureValid(card: JSONObject): Boolean {
        val sig = card.optString("sig").takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            java.security.MessageDigest.isEqual(sig.toByteArray(), vaultSignature(card).toByteArray())
        }.getOrDefault(false)
    }

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

    /**
     * A card that signs in to ChatGPT; [requestedAt] tells a login made from it from an older one.
     * [relogin] lets the user sign in again while an old, no longer working sign-in is still saved.
     */
    fun chatGptLogin(orbitId: String, chatId: String, requestedAt: Long, relogin: Boolean = false): JSONObject = JSONObject()
        .put("kind", CHATGPT_LOGIN)
        .put("orbit_id", orbitId)
        .put("chat_id", chatId)
        .put("requested_at", requestedAt)
        .put("relogin", relogin)

    /** A card that signs in to the account [provider] (a key of [accountProviders]); [relogin] as in [chatGptLogin]. */
    fun accountLogin(orbitId: String, chatId: String, provider: String, relogin: Boolean = false): JSONObject = JSONObject()
        .put("kind", ACCOUNT_LOGIN)
        .put("orbit_id", orbitId)
        .put("chat_id", chatId)
        .put("provider", provider)
        .put("relogin", relogin)

    fun speechSettings(orbitId: String): JSONObject = JSONObject()
        .put("kind", SPEECH_SETTINGS)
        .put("orbit_id", orbitId)

    fun weixinLogin(orbitId: String, chatId: String): JSONObject = JSONObject()
        .put("kind", WEIXIN_LOGIN)
        .put("orbit_id", orbitId)
        .put("chat_id", chatId)

    fun dataExport(orbitId: String): JSONObject = JSONObject()
        .put("kind", DATA_EXPORT)
        .put("orbit_id", orbitId)

    fun openPage(orbitId: String, page: String): JSONObject = JSONObject()
        .put("kind", OPEN_PAGE)
        .put("orbit_id", orbitId)
        .put("page", page)

    /** The entry sheet a vault card opens, or null when [card] is not a complete vault card. */
    fun vaultRequest(card: JSONObject): OrbitVault.Request? {
        val kind = when (card.optString("kind")) {
            VAULT_LOGIN -> OrbitVault.LOGIN
            VAULT_KEY -> OrbitVault.API_KEY
            VAULT_OAUTH -> OrbitVault.OAUTH
            else -> return null
        }
        if (!signatureValid(card)) return null
        val orbitId = card.optString("orbit_id").takeIf { it.isNotBlank() } ?: return null
        val site = card.optString("site").takeIf { it.isNotBlank() } ?: return null
        val hosts = card.optJSONArray("api_hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .filter { it.isNotBlank() }
        val updateId = card.optString("update_id").takeIf { kind != OrbitVault.OAUTH }.orEmpty()
        // A package's key: the variable the package reads, and no terminal.
        val packageName = card.optString("package_name")
            .takeIf { kind == OrbitVault.API_KEY && it.matches(OrbitVault.PACKAGE_NAME) }.orEmpty()
        // An update card offers the terminal for a login too; an adding card only for a key.
        val envName = card.optString("env_name")
            .takeIf { (kind == OrbitVault.API_KEY || updateId.isNotEmpty()) && it.matches(OrbitVault.ENV_NAME) }.orEmpty()
        if (packageName.isNotEmpty() && envName.isEmpty()) return null
        // A key that only goes into the terminal may have no hosts for the proxy.
        if (kind == OrbitVault.API_KEY && hosts.isEmpty() && envName.isEmpty()) return null
        return OrbitVault.Request(
            orbitId, card.optString("chat_id"), kind, site, card.optString("page_url"),
            card.optString("username_hint"), hosts, card.optString("placement").ifEmpty { "bearer" },
            newPassword = kind == OrbitVault.LOGIN && card.optBoolean("new_password"),
            requestedAt = card.optLong("requested_at"),
            envName = envName,
            envReason = if (envName.isEmpty() || packageName.isNotEmpty()) "" else card.optString("env_reason").take(200),
            updateId = updateId,
            authorizationUrl = card.optString("authorization_url"), tokenUrl = card.optString("token_url"),
            clientId = card.optString("client_id"), scope = card.optString("scope"),
            packageName = packageName,
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
        val pkg = request.packageName.takeIf { it.isNotEmpty() }
        val envEn = when {
            pkg != null -> "The tool package $pkg now reads it as \$${request.envName} whenever you run its tools; " +
                "nothing else gets it. If you have not loaded $pkg yet, use_package it first.\n"
            request.envName.isEmpty() -> ""
            envName.isNotEmpty() -> "The user allowed loading this key into the terminal as \$$envName; get the command with orbit_vault env.\n"
            else -> "The user did not allow loading this key into the terminal; use it through http_request or the terminal " +
                "proxy, or tell the user plainly what cannot be done without it.\n"
        }
        val envZh = when {
            pkg != null -> "工具包 $pkg 以后运行时会以环境变量 \$${request.envName} 读到它，别的地方拿不到。" +
                "还没加载 $pkg 的话先 use_package。\n"
            request.envName.isEmpty() -> ""
            envName.isNotEmpty() -> "用户允许把这把密钥载入终端，变量名 \$$envName；用 orbit_vault 的 env 拿载入命令。\n"
            else -> "用户没有允许把这把密钥载入终端；用 http_request 或终端代理来用它，做不到的就跟用户直说。\n"
        }
        val nextEn = when {
            pkg != null -> "$pkg"
            login -> "fill"
            envName.isNotEmpty() -> "env"
            else -> "http_request"
        }
        val nextToolEn = if (pkg != null) "the tools of " else "orbit_vault "
        val nextToolZh = if (pkg != null) "工具包 " else "orbit_vault 的 "
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
                "If you were in the middle of something that needed it, carry on now with " + nextToolEn +
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
                "如果刚才有事情正等着它，现在就用 " + nextToolZh + nextEn +
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
                deliver(app, orbit, chatId, notice)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about a saved credential: ${e.message}")
            }
        }
    }

    /** Another note or reply can take the chat at the same moment; wait for it and try again. */
    private suspend fun deliver(app: Context, orbit: OrbitProfile, chatId: String, notice: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
        while (true) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) break
            val told = OrbitWake.tell(app, orbit, chatId, notice, left, left)
            if (told != OrbitWake.Told.BUSY) break
            kotlinx.coroutines.delay(1_500)
        }
    }

    /** The user signed in to ChatGPT from a card in [chatId]; Orbit hears it once the chat is free. */
    fun sendChatGptLogin(context: Context, orbit: OrbitProfile, chatId: String) {
        val target = chatId.ifBlank { orbit.chatId }
        if (target.isBlank()) return
        val app = context.applicationContext
        val notice = if (LocaleUtils.usesEnglishPrompt(app)) {
            "$MARKER ChatGPT login done\n" +
                "The user signed in to ChatGPT from your card. This is not a message from the user and they cannot see this note.\n" +
                "Codex models and the codex_draw and codex_search packages can be used now. To use a Codex model, check orbit_models list for a " +
                "configuration with provider OPENAI_CODEX; if there is none, add one with orbit_models request_create provider=OPENAI_CODEX " +
                "(leave endpoint and models out). If you were in the middle of something that needed it, " +
                "carry on now; otherwise reply with one short line, or call ${OrbitReactionNotice.NOTHING_TO_DO}. " +
                "Do not mention hidden notes."
        } else {
            "$MARKER 已登录 ChatGPT\n" +
                "用户用你放的卡片登录了 ChatGPT。这不是用户发的消息，用户也看不到这段话。\n" +
                "Codex 模型和 codex_draw、codex_search 工具包现在可以用了。要用 Codex 模型，先用 orbit_models 的 list 看有没有服务商为 OPENAI_CODEX 的配置；" +
                "没有就用 orbit_models 的 request_create provider=OPENAI_CODEX 申请新建（endpoint 和 models 都不用填）。刚才有事情正等着它的话现在接着做；没有就简短回一句，" +
                "或者调用 ${OrbitReactionNotice.NOTHING_TO_DO}。不要提“隐藏消息”这些内部说法。"
        }
        scope.launch {
            try {
                deliver(app, orbit, target, notice)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about the ChatGPT login: ${e.message}")
            }
        }
    }

    /** The user linked WeChat from a card in [chatId]; Orbit hears it once the chat is free. */
    fun sendWeixinConnected(context: Context, orbit: OrbitProfile, chatId: String) {
        val target = chatId.ifBlank { orbit.chatId }
        if (target.isBlank()) return
        val app = context.applicationContext
        val notice = if (LocaleUtils.usesEnglishPrompt(app)) {
            "$MARKER WeChat linked\n" +
                "The user scanned the code on your card and linked WeChat. This is not a message from the user and they cannot see this note.\n" +
                "What they send you in WeChat now arrives in a side chat titled \"WeChat\", and what you say there goes back to WeChat. " +
                "Check it with orbit_channel status if needed. If you were in the middle of something that needed it, carry on now; " +
                "otherwise reply with one short line (for example, invite them to send you a message in WeChat), " +
                "or call ${OrbitReactionNotice.NOTHING_TO_DO}. Do not mention hidden notes."
        } else {
            "$MARKER 已连接微信\n" +
                "用户扫了你卡片上的码，连好了微信。这不是用户发的消息，用户也看不到这段话。\n" +
                "用户以后在微信里发的话会进一个标题为「微信」的侧聊，你在那个侧聊里说的话会发回微信。需要时用 orbit_channel 的 status 看状态。" +
                "刚才有事情正等着它的话现在接着做；没有就简短回一句（比如请用户在微信里给你发句话试试），或者调用 " +
                "${OrbitReactionNotice.NOTHING_TO_DO}。不要提“隐藏消息”这些内部说法。"
        }
        scope.launch {
            try {
                deliver(app, orbit, target, notice)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about the WeChat link: ${e.message}")
            }
        }
    }

    /** The user signed in to the account [label] from a card in [chatId]; Orbit hears it once the chat is free. */
    fun sendAccountLogin(context: Context, orbit: OrbitProfile, chatId: String, label: String, providerType: String) {
        val target = chatId.ifBlank { orbit.chatId }
        if (target.isBlank()) return
        val app = context.applicationContext
        val grok = providerType == com.ai.assistance.operit.data.model.ApiProviderType.GROK_ACCOUNT.name
        val notice = if (LocaleUtils.usesEnglishPrompt(app)) {
            "$MARKER $label account signed in\n" +
                "The user signed in to their $label account from your card. This is not a message from the user and they cannot see this note.\n" +
                (if (grok) "If you asked for it to draw with grok_draw or search with grok_search, those packages work now and need no model configuration; carry on.\n" else "") +
                "Signing in alone does not make it a model you use. A model configuration with provider $providerType is needed: " +
                "check orbit_models list; if there is none, add it yourself with orbit_models request_create provider=$providerType " +
                "(leave endpoint and models out; the user picks the models on the card). With one in place, orbit_models request_use switches to it. " +
                "If you were in the middle of something that needed it, carry on now; " +
                "otherwise reply with one short line, or call ${OrbitReactionNotice.NOTHING_TO_DO}. Do not mention hidden notes."
        } else {
            "$MARKER 已登录 $label 账号\n" +
                "用户用你放的卡片登录了 $label 账号。这不是用户发的消息，用户也看不到这段话。\n" +
                (if (grok) "如果你是为了用 grok_draw 画图或 grok_search 搜索才让用户登录，现在这两个包就能用了，不需要模型配置，直接接着做。\n" else "") +
                "光登录还不能直接当模型用，需要一个服务商为 $providerType 的模型配置：先用 orbit_models 的 list 看有没有；" +
                "没有的话，你自己用 orbit_models 的 request_create provider=$providerType 申请新建（endpoint 和 models 都不用填，用户在卡片上从账号的模型列表里勾）。" +
                "有了配置，再用 orbit_models 的 request_use 申请切换。" +
                "刚才有事情正等着它的话现在接着做；没有就简短回一句，或者调用 ${OrbitReactionNotice.NOTHING_TO_DO}。不要提“隐藏消息”这些内部说法。"
        }
        scope.launch {
            try {
                deliver(app, orbit, target, notice)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about the account login: ${e.message}")
            }
        }
    }

    /** What changed on an update card, for [sendUpdate]. */
    fun buildUpdate(english: Boolean, saved: OrbitVault.Entry, newValue: Boolean, newUsername: Boolean,
        envBefore: String): String {
        val login = saved.kind == OrbitVault.LOGIN
        val env = saved.envName
        return if (english) {
            val what = if (login) "login for ${saved.site}" else "API key for ${saved.site}"
            buildString {
                append("$MARKER Updated the $what\n")
                append("The user updated the $what on the secure card (credential_id ${saved.id}). ")
                append("This is not a message from the user; they typed nothing here and cannot see this note.\n")
                append(when {
                    newValue && login -> "The password was changed. "
                    newValue -> "The key was changed. "
                    else -> "The ${if (login) "password" else "key"} itself was not changed. "
                })
                if (newUsername) append("The user name was changed. ")
                append(when {
                    env.isNotEmpty() -> "It may be used in the terminal as \$$env" +
                        (if (login) " (user name in \$${env}_USERNAME)" else "") + "; get the command with orbit_vault env. "
                    envBefore.isNotEmpty() -> "It may no longer be used in the terminal. "
                    else -> "It is not allowed in the terminal. "
                })
                append("Any earlier terminal surrogate or env command for it no longer works; get a new one if needed.\n")
                append("If you were waiting on this, carry on now. Otherwise reply with one short line, or call " +
                    "${OrbitReactionNotice.NOTHING_TO_DO} if nothing needs saying. Never repeat a saved value, and do not mention hidden notes.")
            }
        } else {
            val what = if (login) "${saved.site} 的登录" else "${saved.site} 的 API 密钥"
            buildString {
                append("$MARKER 已更新 $what\n")
                append("用户在安全卡片里更新了 $what（credential_id ${saved.id}）。这不是用户发的消息，用户没有在这里打字，也看不到这段话。\n")
                append(when {
                    newValue && login -> "密码换了新的。"
                    newValue -> "密钥换了新的。"
                    else -> if (login) "密码本身没换。" else "密钥本身没换。"
                })
                if (newUsername) append("用户名改了。")
                append(when {
                    env.isNotEmpty() -> "现在允许在终端里用，变量名 \$$env" +
                        (if (login) "（用户名在 \$${env}_USERNAME）" else "") + "；用 orbit_vault 的 env 拿载入命令。"
                    envBefore.isNotEmpty() -> "现在不再允许在终端里用。"
                    else -> "不允许在终端里用。"
                })
                append("之前拿到的终端替身值和载入命令都失效了，要用就重新拿。\n")
                append("如果刚才有事情正等着它，现在就接着做；没有的话简短回一句，没什么要说就调用 " +
                    "${OrbitReactionNotice.NOTHING_TO_DO}。绝不复述保存的值，也不要提“隐藏消息”这些内部说法。")
            }
        }
    }

    /** Tells Orbit, in the card's chat, what the user changed on an update card. */
    fun sendUpdate(context: Context, orbit: OrbitProfile, request: OrbitVault.Request, saved: OrbitVault.Entry,
        newValue: Boolean, newUsername: Boolean, envBefore: String) {
        val chatId = request.chatId.ifBlank { orbit.chatId }
        if (chatId.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            try {
                deliver(app, orbit, chatId, buildUpdate(LocaleUtils.usesEnglishPrompt(app), saved, newValue, newUsername, envBefore))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitVaultNotice", "Could not tell Orbit about an updated credential: ${e.message}")
            }
        }
    }
}
