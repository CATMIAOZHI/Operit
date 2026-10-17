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

    val prompt = ToolPrompt(
        name = NAME,
        description = "密码保险箱：保存和使用登录密码、API 密钥。你永远看不到保存的值。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "request_login/request_api_key/list/fill/http_request/delete", required = true),
            ToolParameterSchema("page_url", description = "request_login：网站登录页的 https 地址", required = false),
            ToolParameterSchema("username_hint", description = "request_login：可选，预填的用户名", required = false),
            ToolParameterSchema("provider", description = "request_api_key：服务名，比如 OpenWeather", required = false),
            ToolParameterSchema("api_hosts", description = "request_api_key：这个密钥只能发往的主机，逗号分隔，比如 api.openweathermap.org", required = false),
            ToolParameterSchema("placement", description = "request_api_key：bearer（默认）、header:<名字> 或 query:<参数名>", required = false),
            ToolParameterSchema("domain", description = "list：可选，只列这个网站的", required = false),
            ToolParameterSchema("credential_id", description = "fill/http_request/delete：保险箱条目编号（来自 list）", required = false),
            ToolParameterSchema("field", description = "fill：username 或 password", required = false),
            ToolParameterSchema("ref", description = "fill：浏览器快照里输入框的 ref", required = false),
            ToolParameterSchema("submit", description = "fill：填完后是否按回车，默认 false", required = false),
            ToolParameterSchema("url", description = "http_request：https 地址，主机必须在这个密钥的 api_hosts 里", required = false),
            ToolParameterSchema("method", description = "http_request：GET（默认）/POST/PUT/PATCH/DELETE", required = false),
            ToolParameterSchema("headers", description = "http_request：可选，JSON 对象", required = false),
            ToolParameterSchema("body", description = "http_request：可选，请求正文", required = false),
        ),
        details = """
  - 需要登录或 API 密钥时，先 list 看有没有存过；没有就用 request_login / request_api_key。这会在 Orbit 页面弹出一张安全录入卡，用户在卡里输入，值直接存进保险箱，不经过聊天。调用会等用户填完（最多 5 分钟），只能在和用户的聊天里用。
  - 绝不要让用户把密码、密钥、验证码发在聊天里。用户主动发了，也不要复述、不要存进记忆或文件。
  - 登录：先用浏览器打开该网站的登录页、拿快照，再用 fill 把用户名或密码填进对应输入框。只能填到同一网站的 https 页面。
  - API：用 http_request，宿主会把密钥加到请求里，只发往存密钥时登记的主机，返回里的密钥会被打码。
  - 汇报时只说用没用成，不要说出任何保存的值。
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
                "request_login", "request_api_key" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "The secure entry card only works in a chat with the user; ask the user in chat"
                    }
                    val request = if (arg("action") == "request_login") {
                        val url = arg("page_url")
                        val host = OrbitVault.httpsHost(url) ?: error("page_url must be an https address")
                        OrbitVault.Request(orbit.id, OrbitVault.LOGIN, host, url, arg("username_hint").take(200), emptyList(), "")
                    } else {
                        val provider = arg("provider").take(60)
                        require(provider.isNotEmpty()) { "Missing provider" }
                        val hosts = arg("api_hosts").split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
                        require(hosts.isNotEmpty() && hosts.size <= 5 && hosts.all { it.matches(Regex("[a-z0-9.-]{3,120}")) && it.contains('.') }) {
                            "api_hosts must list 1-5 host names"
                        }
                        val placement = arg("placement").ifEmpty { "bearer" }
                        require(placement == "bearer" || placement.matches(Regex("(header|query):[A-Za-z0-9_-]{1,60}"))) {
                            "placement must be bearer, header:<name> or query:<name>"
                        }
                        OrbitVault.Request(orbit.id, OrbitVault.API_KEY, provider, "", "", hosts, placement)
                    }
                    val id = OrbitVault.ask(request)
                    if (id == null) JSONObject().put("saved", false)
                        .put("note", "The user closed the card or did not fill it in. Do not ask for the value in chat.")
                    else JSONObject().put("saved", true).put("credential_id", id).put("site", request.site)
                }
                "list" -> {
                    val domain = arg("domain").lowercase()
                    JSONArray(OrbitVault.entries(context, orbit.id)
                        .filter { domain.isEmpty() || it.site.contains(domain) || it.apiHosts.any { h -> h.contains(domain) } }
                        .map {
                            JSONObject().put("credential_id", it.id).put("kind", it.kind).put("site", it.site)
                                .put("fields", JSONArray(if (it.kind == OrbitVault.LOGIN) listOf("username", "password") else listOf("api_key")))
                                .put("username", OrbitVault.masked(it.username))
                                .apply { if (it.kind == OrbitVault.API_KEY) put("api_hosts", JSONArray(it.apiHosts)) }
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
                    val ref = arg("ref").ifEmpty { error("Missing ref") }
                    return@runBlockingIoPreservingToolRuntimeContext ToolGetter.getBrowserSessionTools(context)
                        .typeVaultValue(NAME, ref, value, e.site, arg("submit") == "true")
                }
                "http_request" -> request(entry(), arg("url"), arg("method").ifEmpty { "GET" }.uppercase(), arg("headers"), args["body"])
                "delete" -> JSONObject().put("deleted", OrbitVault.delete(context, orbit.id, arg("credential_id")))
                else -> error("Unknown action")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private val client by lazy {
        // Redirects could carry a key in the query to another host, so they are reported, not followed.
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    }

    private fun request(e: OrbitVault.Entry, url: String, method: String, headers: String, body: String?): JSONObject {
        check(e.kind == OrbitVault.API_KEY) { "http_request only works with a saved API key" }
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported method" }
        var parsed = url.toHttpUrlOrNull() ?: error("Bad url")
        require(parsed.isHttps) { "url must be https" }
        require(e.apiHosts.any { OrbitVault.matchesHost(parsed.host, it) }) { "This key may only be sent to ${e.apiHosts.joinToString()}" }
        val builder = Request.Builder()
        var contentType = "application/json"
        if (headers.isNotBlank()) {
            val obj = JSONObject(headers)
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
            val secrets = listOf(e.secret)
            return JSONObject().put("status", response.code)
                .put("content_type", response.header("Content-Type").orEmpty())
                .apply { response.header("Location")?.let { put("redirect", OrbitVault.redact(it, secrets)) } }
                .put("body", OrbitVault.redact(text.take(MAX_RESPONSE), secrets))
                .put("truncated", text.length > MAX_RESPONSE)
        }
    }
}