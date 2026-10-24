package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.typeVaultValue
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitVault
import com.ai.assistance.operit.features.orbit.OrbitOAuth
import com.ai.assistance.operit.features.orbit.OrbitOAuthProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Muse's credentials.*: the Secure Vault. Orbit never sees a saved value. */
internal object OrbitVaultTools {
    const val NAME = "orbit_vault"
    private const val MAX_RESPONSE = 20_000
    /** Like Muse, one card per site in a turn: asking again soon after points back to that card. */
    private const val CARD_REPEAT_MS = 5 * 60_000L
    private val sentCards = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val prompt = ToolPrompt(
        name = NAME,
        description = "密码凭据保险箱：保存和使用登录密码、API 密钥、公开客户端 OAuth 登录。你永远看不到保存的值。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "request_login/request_new_password/request_api_key/request_oauth/request_update/request_chatgpt_login/list/fill/http_request/terminal/env/delete", required = true),
            ToolParameterSchema("page_url", description = "request_login / request_new_password：网站登录页（或注册、重设密码页）的 https 地址", required = false),
            ToolParameterSchema("username_hint", description = "request_login / request_new_password：可选，预填的用户名", required = false),
            ToolParameterSchema("provider", description = "request_api_key / request_oauth：服务名，比如 OpenWeather", required = false),
            ToolParameterSchema("api_hosts", description = "request_api_key / request_oauth：凭据只能发往的主机，逗号分隔。API 密钥给了 env_name 或 package_name 时可以不填", required = false),
            ToolParameterSchema("authorization_url", description = "request_oauth：服务商官方 https 授权地址，可含 access_type 等公开参数", required = false),
            ToolParameterSchema("token_url", description = "request_oauth：服务商官方 https token 地址", required = false),
            ToolParameterSchema("client_id", description = "request_oauth：已注册的公开客户端 ID；回调 URI 必须登记为 ${com.ai.assistance.operit.BuildConfig.APPLICATION_ID}.orbit-oauth://callback/oauth2", required = false),
            ToolParameterSchema("scope", description = "request_oauth：最少需要的授权范围，空格分隔", required = false),
            ToolParameterSchema("oauth_flow", description = "request_oauth：只支持 authorization_code_pkce（默认）；device flow 不支持，改用 request_api_key", required = false),
            ToolParameterSchema("client_auth", description = "request_oauth：只支持 none（默认）。需要 client_secret 的服务不支持，改用 request_api_key，绝不向用户要 client_secret", required = false),
            ToolParameterSchema("placement", description = "request_api_key：bearer（默认）、header:<名字> 或 query:<参数名>", required = false),
            ToolParameterSchema("package_name", description = "request_api_key：可选，要这把密钥的工具包名（比如 tavily）。给了它就必须给 env_name，填这个包要的环境变量名；密钥只交给这个包，不进终端", required = false),
            ToolParameterSchema("env_name", description = "request_api_key / request_update：可选，代理做不到时（要签名、SDK、多把密钥、脚本登录）请求载入终端的变量名，大写，比如 ALIYUN_ACCESS_KEY_SECRET；用户在卡片上决定是否允许。配 package_name 时是那个工具包读的环境变量名", required = false),
            ToolParameterSchema("env_reason", description = "request_api_key：给了 env_name 时必填；request_update：可选。一句话告诉用户为什么需要在终端里用", required = false),
            ToolParameterSchema("domain", description = "list：可选，只列这个网站的", required = false),
            ToolParameterSchema("credential_id", description = "request_update/fill/http_request/terminal/env/delete：保险箱条目编号（来自 list）", required = false),
            ToolParameterSchema("field", description = "fill：username 或 password", required = false),
            ToolParameterSchema("ref", description = "fill：浏览器快照里输入框的 ref", required = false),
            ToolParameterSchema("submit", description = "fill：填完后是否按回车，默认 false", required = false),
            ToolParameterSchema("url", description = "http_request：https 地址，主机必须在这个密钥的 api_hosts 里", required = false),
            ToolParameterSchema("method", description = "http_request：GET（默认）/POST/PUT/PATCH/DELETE", required = false),
            ToolParameterSchema("headers", description = "http_request：可选，JSON 对象", required = false),
            ToolParameterSchema("body", description = "http_request：可选，请求正文", required = false),
        ),
        details = """
  - 需要登录或 API 密钥时，先 list 看有没有存过；没有就用 request_login / request_api_key。要注册新账号或重设密码时用 request_new_password，用户在卡片里设一个新密码（输两遍，也可以一键生成）。这会立刻在聊天里放一张安全卡片，然后马上返回，不会等用户。用户点卡片、在弹出的输入框里填，值直接存进保险箱，不经过聊天。只能在和用户的聊天里用。
  - 放完卡片就结束这一轮，可以简单说一句“卡片在下面，填好我接着弄”。用户保存后你会收到一条隐藏通知，再接着做；用户不填或关掉卡片，你什么都不会收到，不要追问，也不要为同一个网站再放一张卡（再要会返回 already_sent，指向已经放的那张）。
  - request_api_key 之前，先从服务商的官方文档查清两件事：认证方式和 API 主机名（api_hosts），再定 placement。卡片建好后 placement 改不了，填错只能重新放一张让用户再填。保存时不会检查密钥对不对，第一次真实调用才知道；返回 401/403 时先查请求有没有带上密钥、placement 对不对，再怀疑密钥本身。
  - OAuth 用 request_oauth：先查服务商官方文档，确认公开客户端支持授权码 + PKCE S256、不需要 client_secret，并允许登记回调 ${com.ai.assistance.operit.BuildConfig.APPLICATION_ID}.orbit-oauth://callback/oauth2。传 provider、authorization_url、token_url、client_id、scope、api_hosts。卡片马上返回，用户在系统浏览器授权，token 由 App 直接存保险箱，成功后收到隐藏通知；取消不通知。用 http_request 调 API，过期且有 refresh_token 时 App 自动刷新。OAuth 暂不支持 terminal/env。不能保证每家服务都支持；要 client_secret、device flow 或不接受此回调的服务，明确说明做不了，改用 request_api_key。不要索要或伪造客户端密钥，也不要在 Orbit 浏览器里走 OAuth。
  - 已经存过的密码或 API 密钥要改（用户换了密码、密钥过期、要允许或不再允许在终端里用）：用 request_update 加 credential_id，在聊天里放一张更新卡片。用户可以换新值（留空就不换）、改登录的用户名，也可以打开或关掉“允许在终端使用”。想在终端用时可以带 env_name 和 env_reason；不带的话沿用原来的变量名，没有就自动起一个。保存后你会收到隐藏通知，说明改了什么、变量名是什么。OAuth 登录不能更新，要重连就用 request_oauth。
  - 不支持的认证方式要直说并讲原因，不要硬套最接近的一种：需要签名的请求（如 AWS 签名）、一次要多个密钥、用账号密码做 API 认证。
  - 要签名的请求（阿里云、AWS）、只认环境变量的 SDK、一次要两把密钥（每把各放一张卡）这类代理做不到的情况，可以在 request_api_key 里加 env_name 和 env_reason，请用户允许把密钥载入终端。用户打开了开关，保存后才能用 env；没打开就只能走代理，或者直说做不到。能用代理时不要要 env_name。
  - 绝不要让用户把密码、密钥、验证码发在聊天里。用户主动发了，也不要复述、不要存进记忆或文件。
  - 登录：先用浏览器打开该网站的登录页、拿快照，再用 fill 把用户名或密码填进对应输入框。只能填到同一网站的 https 页面。
  - API：用 http_request，宿主会把密钥加到请求里，只发往存密钥时登记的主机，返回里的密钥会被打码。
  - 终端里用 API 密钥（脚本、curl、python）：先 terminal 拿这个密钥的替身值（surrogate）和本机代理地址。脚本把替身值放在密钥原本的位置：Authorization 头、Basic 认证的“用户名:密钥”（比如 curl -u 'api:<替身值>'）、自定义头、查询参数或网址路径（比如 Telegram 的 /bot<替身值>/sendMessage），再把请求地址里的 https:// 换成代理地址，比如 https://api.github.com/user 改成 <proxy_base>api.github.com/user。代理核对替身值和主机，只在请求发出的那一刻换成真密钥，返回里的密钥会被打码。替身值不是密钥，可以写进脚本；密钥重新保存或删除后它就失效，代理地址在 App 重启后也可能变，连不上或提示过期就再调一次 terminal。只支持 API 密钥，登录密码不能在终端用。
  - 用户允许载入终端的密钥或密码（list 里有 env_name）：调 env 拿一行载入命令，在同一条终端命令里先执行它再跑脚本，比如 `<命令> && python sign.py`，脚本从环境变量读取。登录密码还会把用户名载入成 <env_name>_USERNAME。不要 echo、打印、写进文件或记忆，也不要把值放进命令行参数；工具输出里出现会被打码。值更新后旧命令会失效，再调一次 env。
  - 汇报时只说用没用成，不要说出任何保存的值。
  - 工具包要 API 密钥（use_package 或 orbit_packages list 提示缺环境变量）：用 request_api_key 加 package_name 和 env_name（那个包要的变量名，原样大写），provider 写服务名。用户保存后，这个包运行时会读到它，别的包和终端都拿不到；不用把它填进主程序的环境变量。
  - 要用 ChatGPT 账号的功能（Codex 模型、codex_draw 绘图）还没登录时，用 request_chatgpt_login 放一张登录卡片，用户点它登录，成功后你会收到隐藏通知。已经登录会直接告诉你。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            suspend fun entry() = OrbitVault.entries(context, orbit.id).firstOrNull { it.id == arg("credential_id") }
                ?: error("No vault entry with that credential_id; use list")
            val result: Any = when (arg("action")) {
                "request_chatgpt_login" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "The login card only works in a chat with the user; ask the user in chat"
                    }
                    val signedIn = com.ai.assistance.operit.data.preferences.CodexAuthPreferences
                        .getInstance(context).currentState() != null
                    val key = "${caller.orEmpty()}|chatgpt_login"
                    val now = System.currentTimeMillis()
                    sentCards.entries.removeIf { now - it.value > CARD_REPEAT_MS }
                    when {
                        signedIn -> JSONObject().put("already_logged_in", true)
                            .put("note", "ChatGPT is already signed in in this app; go ahead with what needed it.")
                        sentCards.putIfAbsent(key, now) != null -> JSONObject().put("already_sent", true)
                            .put("note", "The login card is already in the chat above. Do not send another. End this turn now.")
                        else -> JSONObject().put("card_sent", true)
                            .put("note", "A ChatGPT login card is now in the chat. The user signs in whenever they like; you get a " +
                                "hidden note once it is done, and nothing if they skip it. End this turn now.")
                            .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                                com.ai.assistance.operit.features.orbit.OrbitChatCards.chatGptLogin(orbit.id, caller.orEmpty(), now))
                    }
                }
                "request_login", "request_new_password", "request_api_key", "request_oauth", "request_update" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "The secure entry card only works in a chat with the user; ask the user in chat"
                    }
                    val chatId = caller.orEmpty()
                    val request = if (arg("action") == "request_update") {
                        val e = entry()
                        check(e.kind == OrbitVault.LOGIN || e.kind == OrbitVault.API_KEY) {
                            "An OAuth login cannot be updated; use request_oauth to connect it again"
                        }
                        // A package's key keeps its variable and stays out of the terminal.
                        val envName = if (e.packageName.isNotEmpty()) e.slot
                            else arg("env_name").uppercase().ifEmpty { e.envName.ifEmpty { e.slot } }
                                .ifEmpty { OrbitVault.defaultEnvName(e) }
                        require(envName.matches(OrbitVault.ENV_NAME)) {
                            "env_name must be a shell variable name in capitals, such as GITHUB_TOKEN"
                        }
                        // The user name stays out of the chat; the sheet reads it from the vault.
                        OrbitVault.Request(orbit.id, chatId, e.kind, e.site,
                            if (e.kind == OrbitVault.LOGIN) "https://${e.site}" else "", "", e.apiHosts, e.placement,
                            requestedAt = System.currentTimeMillis(), envName = envName,
                            envReason = if (e.packageName.isNotEmpty()) "" else arg("env_reason").take(200), updateId = e.id,
                            packageName = e.packageName)
                    } else if (arg("action") == "request_oauth") {
                        require(arg("oauth_flow").ifEmpty { "authorization_code_pkce" } == "authorization_code_pkce" &&
                            arg("client_auth").ifEmpty { "none" } == "none" && arg("client_secret").isEmpty()) {
                            OrbitOAuthProtocol.UNSUPPORTED
                        }
                        val provider = arg("provider").take(60)
                        require(provider.isNotEmpty()) { "Missing provider" }
                        OrbitVault.Request(orbit.id, chatId, OrbitVault.OAUTH, provider, "", "",
                            arg("api_hosts").split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
                            "bearer", requestedAt = System.currentTimeMillis(), authorizationUrl = arg("authorization_url"),
                            tokenUrl = arg("token_url"), clientId = arg("client_id"), scope = arg("scope"))
                            .also { OrbitOAuthProtocol.validate(it) }
                    } else if (arg("action") != "request_api_key") {
                        val url = arg("page_url")
                        val host = OrbitVault.httpsHost(url) ?: error("page_url must be an https address")
                        val fresh = arg("action") == "request_new_password"
                        OrbitVault.Request(orbit.id, chatId, OrbitVault.LOGIN, host, url, arg("username_hint").take(200), emptyList(), "",
                            newPassword = fresh, requestedAt = if (fresh) System.currentTimeMillis() else 0)
                    } else {
                        val provider = arg("provider").take(60)
                        require(provider.isNotEmpty()) { "Missing provider" }
                        val packageName = arg("package_name")
                        val envName = arg("env_name").uppercase()
                        if (packageName.isNotEmpty()) checkPackageEnv(context, packageName, envName)
                        val hosts = arg("api_hosts").split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
                        require(packageName.isEmpty() || hosts.isEmpty()) {
                            "A package key is only for that package; leave out api_hosts with package_name"
                        }
                        require((hosts.isNotEmpty() || envName.isNotEmpty()) && hosts.size <= 5 &&
                            hosts.all { it.matches(Regex("[a-z0-9.-]{3,120}")) && it.contains('.') }) {
                            "api_hosts must list 1-5 host names (it may be left out only with env_name or package_name)"
                        }
                        val placement = arg("placement").ifEmpty { "bearer" }
                        require(placement == "bearer" || placement.matches(Regex("(header|query):[A-Za-z0-9_-]{1,60}"))) {
                            "placement must be bearer, header:<name> or query:<name>"
                        }
                        if (envName.isNotEmpty() && packageName.isEmpty()) {
                            require(envName.matches(OrbitVault.ENV_NAME)) {
                                "env_name must be a shell variable name in capitals, such as ALIYUN_ACCESS_KEY_SECRET"
                            }
                            require(arg("env_reason").isNotEmpty()) {
                                "env_reason is required with env_name: one line telling the user why the proxy is not enough"
                            }
                        }
                        OrbitVault.Request(orbit.id, chatId, OrbitVault.API_KEY, provider, "", "", hosts, placement,
                            envName = envName,
                            envReason = if (envName.isEmpty() || packageName.isNotEmpty()) "" else arg("env_reason").take(200),
                            packageName = packageName)
                    }
                    // A key card asked again with other hosts or placement corrects the first one, so it is a new card.
                    val key = listOf(chatId, request.kind, request.newPassword, request.site.lowercase(),
                        request.apiHosts.sorted().joinToString(","), request.placement, request.envName,
                        request.authorizationUrl, request.tokenUrl, request.clientId, request.scope, request.updateId,
                        request.packageName).joinToString("|")
                    val now = System.currentTimeMillis()
                    sentCards.entries.removeIf { now - it.value > CARD_REPEAT_MS }
                    // The card goes into the chat and the turn moves on; a save sends a hidden note.
                    if (sentCards.putIfAbsent(key, now) != null) {
                        JSONObject().put("already_sent", true).put("site", request.site)
                            .put("note", "This card is already in the chat above. Do not send another; " +
                                "the user fills that one in whenever they like. End this turn now.")
                    } else JSONObject().put("card_sent", true).put("site", request.site)
                        .apply { if (request.kind == OrbitVault.OAUTH) put("redirect_uri", OrbitOAuth.redirectUri(context)) }
                        .put("note", "A secure card is now in the chat. The user fills it in or authorizes in their browser whenever they like; " +
                            "you get a hidden note once it is saved, and nothing if they skip it. End this turn now. " +
                            "Do not ask for the value in chat and do not send another card for this site.")
                        .put(com.ai.assistance.operit.features.orbit.OrbitChatCards.KEY,
                            com.ai.assistance.operit.features.orbit.OrbitChatCards.vault(request))
                }
                "list" -> {
                    val domain = arg("domain").lowercase()
                    JSONArray(OrbitVault.entries(context, orbit.id)
                        .filter { domain.isEmpty() || it.site.contains(domain) || it.apiHosts.any { h -> h.contains(domain) } }
                        .map {
                            JSONObject().put("credential_id", it.id).put("kind", it.kind).put("site", it.site)
                                .put("fields", JSONArray(when (it.kind) {
                                    OrbitVault.LOGIN -> listOf("username", "password")
                                    OrbitVault.OAUTH -> listOf("oauth_login")
                                    else -> listOf("api_key")
                                }))
                                .put("username", OrbitVault.masked(it.username))
                                .apply { if (it.kind != OrbitVault.LOGIN) put("api_hosts", JSONArray(it.apiHosts)).put("placement", it.placement) }
                                .apply { it.oauth?.let { auth ->
                                    put("expires_at", auth.expiresAt).put("scope", auth.scope)
                                        .put("can_refresh", auth.refreshToken.isNotEmpty())
                                } }
                                .apply { if (it.envName.isNotEmpty()) put("env_name", it.envName) }
                                .apply { if (it.packageName.isNotEmpty()) put("package_name", it.packageName).put("package_env", it.slot) }
                                // A password set from a new-password card, which the site may not have taken yet.
                                .apply { if (it.kind == OrbitVault.LOGIN && it.card != 0L) put("new_password", true) }
                                .put("saved_at", it.savedAt)
                        })
                }
                "fill" -> {
                    val e = entry()
                    check(e.kind == OrbitVault.LOGIN) { "fill only works with a saved login" }
                    val value = when (arg("field")) {
                        "username" -> e.username.ifEmpty { error("No user name was saved for this login") }
                        "password" -> e.secret
                        else -> error("field must be username or password")
                    }
                    val ref = arg("ref").ifEmpty { error("fill needs ref: the input box's ref from a browser snapshot of the login page") }
                    return@runBlockingIoPreservingToolRuntimeContext ToolGetter.getBrowserSessionTools(context)
                        .typeVaultValue(NAME, ref, value, e.site, arg("submit") == "true")
                }
                "http_request" -> {
                    val e = OrbitVault.forHttp(context, orbit.id, arg("credential_id"))
                    fun send(saved: OrbitVault.Entry) =
                        request(saved, arg("url"), arg("method").ifEmpty { "GET" }.uppercase(), arg("headers"), args["body"])
                    val response = send(e)
                    // Retry only a definite authentication rejection, once; never a transport failure.
                    if (response.optInt("status") == 401 && e.kind == OrbitVault.OAUTH &&
                        !e.oauth?.refreshToken.isNullOrEmpty()) {
                        send(OrbitVault.forHttp(context, orbit.id, e.id, rejectedToken = e.secret))
                    } else response
                }
                "terminal" -> terminal(context, orbit.id, entry())
                "env" -> {
                    val e = entry()
                    check(e.kind == OrbitVault.API_KEY || e.kind == OrbitVault.LOGIN) { "env only works with a saved API key or login" }
                    check(e.envName.isNotEmpty()) {
                        "The user has not allowed this in the terminal. " + (if (e.kind == OrbitVault.LOGIN)
                            "Use fill in the browser; if a script really needs it, " else
                            "Use http_request or terminal (the proxy); if those cannot do it, ") +
                            "ask with request_update (credential_id, env_name, env_reason), or tell the user."
                    }
                    check(e.secret.length >= OrbitVault.MIN_ENV_SECRET) {
                        "This value is too short to be masked in output, so it cannot be loaded into the terminal. " +
                            "Use fill, http_request or terminal instead, or tell the user."
                    }
                    JSONObject().put("credential_id", e.id).put("site", e.site).put("env_name", e.envName)
                        .apply { if (e.kind == OrbitVault.LOGIN) put("username_env", "${e.envName}_USERNAME") }
                        .put("command", com.ai.assistance.operit.features.orbit.OrbitVaultBroker.envCommand(context, orbit.id, e))
                        .put("note", "Run command in the same terminal command as your script, e.g. `<command> && python " +
                            "script.py`; the value is then in \$${e.envName}" +
                            (if (e.kind == OrbitVault.LOGIN) " and the user name in \$${e.envName}_USERNAME" else "") +
                            ". Never echo, print or write it to a file or memory, " +
                            "and do not pass its value as a command-line argument. If the command says the surrogate is out of " +
                            "date or cannot connect, call env again.")
                }
                "delete" -> {
                    val id = arg("credential_id").ifEmpty { error("delete needs credential_id; get it from list") }
                    val e = OrbitVault.entries(context, orbit.id).firstOrNull { it.id == id }
                    val deleted = OrbitVault.delete(context, orbit.id, id)
                    JSONObject().put("credential_id", id).put("deleted", deleted)
                        .apply { e?.let { put("kind", it.kind).put("site", it.site) } }
                        .apply { if (!deleted) put("note", "No vault entry with that credential_id; nothing was deleted. Use list.") }
                }
                else -> error("Unknown action \"${arg("action")}\". Use request_login, request_new_password, request_api_key, request_oauth, request_update, list, fill, http_request, terminal, env or delete.")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun noHosts(e: OrbitVault.Entry) =
        if (e.packageName.isNotEmpty()) "This key was saved for the tool package ${e.packageName}; use that package's tools"
        else "This key was saved without API hosts, so it only works through env"

    /**
     * A package key card must name a package Orbit can use and a variable it reads: one it declares
     * when it declares any (some packages read variables they never list).
     */
    private fun checkPackageEnv(context: Context, packageName: String, envName: String) {
        require(packageName.matches(OrbitVault.PACKAGE_NAME)) { "package_name is not a package name" }
        require(envName.matches(OrbitVault.ENV_NAME)) {
            "With package_name, env_name must be the variable the package reads, in capitals, such as TAVILY_API_KEY"
        }
        val manager = com.ai.assistance.operit.core.tools.packTool.PackageManager.getInstance(
            context, AIToolHandler.getInstance(context))
        val pkg = manager.getAvailablePackages()[packageName]
            ?: error("No tool package named $packageName; see orbit_packages list")
        val declared = pkg.env.map { it.name.trim() }.filter { it.isNotEmpty() }
        require(declared.isEmpty() || envName in declared) {
            "$packageName reads ${declared.joinToString()}; use one of these as env_name"
        }
    }

    private val client by lazy {
        // Redirects could carry a key in the query to another host, so they are reported, not followed.
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    }

    /** A surrogate and the loopback proxy for using an API key from terminal scripts; see [OrbitVaultBroker]. */
    private fun terminal(context: Context, orbitId: String, e: OrbitVault.Entry): JSONObject {
        check(e.kind == OrbitVault.API_KEY) { "terminal only works with a saved API key; OAuth uses http_request and logins use fill" }
        check(e.packageName.isEmpty()) { noHosts(e) }
        check(e.apiHosts.isNotEmpty()) { noHosts(e) }
        val base = com.ai.assistance.operit.features.orbit.OrbitVaultBroker.base(context)
        val surrogate = com.ai.assistance.operit.features.orbit.OrbitVaultBroker.surrogate(context, orbitId, e)
        val host = e.apiHosts.first()
        val curl = when {
            e.placement == "bearer" -> "curl -sS -H \"Authorization: Bearer $surrogate\" \"${base}$host/\""
            e.placement.startsWith("header:") -> "curl -sS -H \"${e.placement.removePrefix("header:")}: $surrogate\" \"${base}$host/\""
            else -> "curl -sS \"${base}$host/?${e.placement.removePrefix("query:")}=$surrogate\""
        }
        return JSONObject().put("credential_id", e.id).put("site", e.site)
            .put("proxy_base", base).put("surrogate", surrogate)
            .put("api_hosts", JSONArray(e.apiHosts)).put("placement", e.placement)
            .put("example", curl)
            .put("note", "Put the surrogate where the key goes (placement ${e.placement}) and send the request to " +
                "proxy_base + host + path instead of https://host/path. Only these hosts are allowed: " +
                "${e.apiHosts.joinToString()}. The proxy swaps in the real key as the request leaves and masks it in the " +
                "response; redirects are passed back, not followed. Send bodies with a Content-Length. If the proxy " +
                "cannot be reached or says the surrogate is out of date, call terminal again.")
    }

    private fun request(e: OrbitVault.Entry, url: String, method: String, headers: String, body: String?): JSONObject {
        check(e.kind == OrbitVault.API_KEY || e.kind == OrbitVault.OAUTH) { "http_request only works with a saved API key or OAuth login" }
        check(e.packageName.isEmpty()) { noHosts(e) }
        check(e.apiHosts.isNotEmpty()) { noHosts(e) }
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported method $method; use GET, POST, PUT, PATCH or DELETE" }
        var parsed = url.toHttpUrlOrNull() ?: error("url \"${url.take(200)}\" is not a valid https address")
        require(parsed.isHttps) { "url must be https" }
        require(OrbitVault.permitsApiHost(e.kind, parsed.host, e.apiHosts)) { "This credential may only be sent to ${e.apiHosts.joinToString()}" }
        val builder = Request.Builder()
        var contentType = "application/json"
        if (headers.isNotBlank()) {
            val obj = runCatching { JSONObject(headers) }.getOrElse { error("headers must be a JSON object such as {\"Accept\":\"application/json\"}") }
            obj.keys().forEach { name ->
                val value = obj.getString(name)
                if (name.equals("content-type", true)) contentType = value else builder.header(name, value)
            }
        }
        when {
            e.placement == "bearer" -> builder.header("Authorization", "Bearer ${e.secret}")
            e.placement.startsWith("header:") -> builder.header(e.placement.removePrefix("header:"), e.secret)
            e.placement.startsWith("query:") -> parsed = parsed.newBuilder().setQueryParameter(e.placement.removePrefix("query:"), e.secret).build()
        }
        val requestBody = if (method == "GET" || method == "DELETE" && body.isNullOrEmpty()) null
            else (body ?: "").toRequestBody(contentType.toMediaTypeOrNull())
        client.newCall(builder.url(parsed).method(method, requestBody).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val secrets = listOf(e.secret, e.oauth?.refreshToken.orEmpty())
            val code = response.code
            val next = when {
                code in 200..299 -> "The request worked."
                (code == 401 || code == 403) && e.kind == OrbitVault.OAUTH ->
                    "The service refused this OAuth login. Check the API and scope. If expired or revoked, use request_oauth to reconnect."
                code == 401 || code == 403 -> "The service refused the key. First check the url is the right API and placement " +
                    "(${e.placement}) matches the service docs; if those are right, the key itself may be wrong or lack access. " +
                    "A wrong placement can only be fixed with a new request_api_key card."
                code in 300..399 -> "The service redirected; redirects are not followed so the key is not sent elsewhere. Call the redirect url directly if it is on an allowed host."
                code == 429 -> "Rate limited; wait before trying again."
                code >= 500 -> "The service had an error; try again later."
                else -> "The service rejected the request; read body for the reason."
            }
            return JSONObject().put("status", code).put("http_ok", code in 200..299)
                .put("placement", e.placement).put("host", parsed.host).put("next_step", next)
                .put("content_type", OrbitVault.redact(response.header("Content-Type").orEmpty(), secrets))
                .apply { response.header("Location")?.let { put("redirect", OrbitVault.redact(it, secrets)) } }
                .put("body", OrbitVault.redact(text, secrets, MAX_RESPONSE))
                .put("truncated", text.length > MAX_RESPONSE)
                .apply { if (text.length > MAX_RESPONSE) put("body_chars", text.length) }
        }
    }
}
