package com.ai.assistance.operit.features.search

import android.content.Context
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.ProviderAccountManager
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grok's web search and X search with the app's Grok account login, the same OAuth token the Grok
 * account models and Grok Draw use, so no xAI API key is needed. The token stays in the host.
 * Requests follow xAI's Responses API with the `web_search` / `x_search` tools, as
 * grok-x-search-mcp does with the same login.
 */
object GrokSearchBridge {
    private val ENDPOINTS = listOf("https://api.x.ai/v1", "https://cli-chat-proxy.grok.com/v1")
    private const val DEFAULT_MODEL = "grok-4.3"
    private val MODEL_PATTERN = Regex("""grok-[a-z0-9.\-]+""")
    private const val MAX_QUERIES = 5
    private const val MAX_QUERY_CHARS = 2_000
    /** xAI's limits: 5 domains for each web filter, 20 handles for each X filter. */
    private const val MAX_DOMAINS = 5
    private const val MAX_HANDLES = 20
    private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    private const val ERROR_BYTES = 16L * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // The model searches, reads and answers before the response starts.
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()

    /** [mode] is "web" or "x". */
    suspend fun execute(context: Context, mode: String, parametersJson: String): String {
        val params = JSONObject(parametersJson)
        val queries = SearchResults.queries(params, MAX_QUERIES, MAX_QUERY_CHARS)
        val model = params.optString("model").trim().ifBlank { DEFAULT_MODEL }
        require(MODEL_PATTERN.matches(model)) { "model 必须是 grok 开头的模型，比如 $DEFAULT_MODEL" }
        val tool = when (mode) {
            "web" -> webTool(params)
            "x" -> xTool(params)
            else -> throw IllegalArgumentException("未知的搜索方式：$mode")
        }

        val manager = ProviderAccountManager.get(context, AccountProvider.GROK)
        require(manager.account.value != null) {
            "还没登录 Grok 账号。请先在模型设置里登录 Grok（Orbit 里可用 orbit_vault request_account_login provider=grok）"
        }
        val token = try {
            manager.validAccount().accessToken
        } catch (error: com.ai.assistance.operit.data.api.AccountHttpException) {
            // The token server only answers 400/401 when the saved login itself was refused.
            throw IOException(
                if (error.status in setOf(400, 401)) "Grok 登录已失效，请重新登录 Grok 账号"
                else "刷新 Grok 登录时服务器出错（HTTP ${error.status}），请稍后再试，不用重新登录",
                error,
            )
        } catch (error: IOException) {
            throw IOException("连不上 Grok 登录服务器，请检查网络后稍后再试，不用重新登录", error)
        }
        val searches = coroutineScope {
            queries.map { query ->
                async {
                    try {
                        search(token, query, model, tool)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        SearchResults.failed(query, error.message ?: "Grok 搜索失败")
                    }
                }
            }.awaitAll()
        }
        return SearchResults.wrap(if (mode == "x") "grok_x" else "grok_web", model, searches).toString()
    }

    private fun webTool(params: JSONObject): JSONObject {
        val allowed = list(params, "allowed_domains", MAX_DOMAINS, ::domain)
        val excluded = list(params, "excluded_domains", MAX_DOMAINS, ::domain)
        require(allowed.isEmpty() || excluded.isEmpty()) { "allowed_domains 和 excluded_domains 不能同时用" }
        val tool = JSONObject().put("type", "web_search")
        val filters = JSONObject()
        if (allowed.isNotEmpty()) filters.put("allowed_domains", JSONArray(allowed))
        if (excluded.isNotEmpty()) filters.put("excluded_domains", JSONArray(excluded))
        if (filters.length() > 0) tool.put("filters", filters)
        if (params.optBoolean("enable_image_search")) tool.put("enable_image_search", true)
        return tool
    }

    private fun xTool(params: JSONObject): JSONObject {
        val allowed = list(params, "allowed_x_handles", MAX_HANDLES, ::handle)
        val excluded = list(params, "excluded_x_handles", MAX_HANDLES, ::handle)
        require(allowed.isEmpty() || excluded.isEmpty()) { "allowed_x_handles 和 excluded_x_handles 不能同时用" }
        val from = date(params, "from_date")
        val to = date(params, "to_date")
        if (from != null && to != null) require(!to.isBefore(from)) { "to_date 不能早于 from_date" }
        val tool = JSONObject().put("type", "x_search")
        if (allowed.isNotEmpty()) tool.put("allowed_x_handles", JSONArray(allowed))
        if (excluded.isNotEmpty()) tool.put("excluded_x_handles", JSONArray(excluded))
        from?.let { tool.put("from_date", it.toString()) }
        to?.let { tool.put("to_date", it.toString()) }
        if (params.optBoolean("enable_image_understanding")) tool.put("enable_image_understanding", true)
        return tool
    }

    /** An array parameter, also accepted as one comma-separated string. */
    private fun list(params: JSONObject, name: String, max: Int, clean: (String) -> String): List<String> {
        val raw = params.opt(name)
        val items = when (raw) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"') }
            else -> throw IllegalArgumentException("$name 要写成数组")
        }.map { it.trim() }.filter { it.isNotEmpty() }.map(clean).distinct()
        require(items.size <= max) { "$name 最多 $max 个" }
        return items
    }

    private fun domain(value: String): String {
        val host = value.lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        require(Regex("""[a-z0-9.\-]+\.[a-z]{2,}""").matches(host)) { "域名格式不对：$value" }
        return host
    }

    private fun handle(value: String): String {
        val name = value.removePrefix("https://x.com/").removePrefix("https://twitter.com/").removePrefix("@").substringBefore('/')
        require(Regex("""[A-Za-z0-9_]{1,15}""").matches(name)) { "X 账号名格式不对：$value（不带 @）" }
        return name
    }

    private fun date(params: JSONObject, name: String): LocalDate? {
        val value = params.optString(name).trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("$name 要写成 YYYY-MM-DD，比如 2026-10-28")
        }
    }

    private suspend fun search(token: String, query: String, model: String, tool: JSONObject): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", query)))
            .put("tools", JSONArray().put(tool))
            // Without this the model may answer from memory, skipping the search and its filters.
            .put("tool_choice", "required")
        // The account token works on the public API; the CLI proxy is the fallback for accounts
        // whose token only works there, as with Grok Draw.
        var last: Pair<Int, String>? = null
        for (base in ENDPOINTS) {
            val response = request(base + "/responses", token, payload, viaProxy = base != ENDPOINTS.first())
            if (response.first in 200..299) return parse(query, response.second)
            last = response
            if (response.first !in setOf(401, 403, 404, 405)) break
        }
        val (status, body) = checkNotNull(last)
        throw IOException(SearchResults.httpFailure("Grok", status, SearchResults.errorText(body)).let {
            if (status == 403) "$it。Grok 账号搜索需要 X Premium 或 SuperGrok 等订阅" else it
        })
    }

    private fun parse(query: String, text: String): JSONObject {
        val body = try { JSONObject(text) } catch (_: Exception) { throw IOException("Grok 返回的内容看不懂") }
        // A failed or cut-off response is not a result, even if it carries some text.
        val status = body.optString("status")
        if (status.isNotEmpty() && status != "completed") {
            val reason = body.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: body.optJSONObject("incomplete_details")?.optString("reason")?.takeIf { it.isNotBlank() }
            throw IOException("Grok 搜索没有完成" + (reason?.let { "：${it.take(300)}" } ?: "（$status）"))
        }
        val summary = StringBuilder()
        val citations = LinkedHashMap<String, String?>()
        val searched = mutableListOf<String>()
        val output = body.optJSONArray("output") ?: JSONArray()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (part in 0 until content.length()) {
                        val piece = content.optJSONObject(part) ?: continue
                        if (piece.optString("type") != "output_text") continue
                        if (summary.isNotEmpty()) summary.append("\n")
                        summary.append(piece.optString("text"))
                        SearchResults.annotations(piece.optJSONArray("annotations"), citations)
                    }
                }
                "web_search_call", "x_search_call", "custom_tool_call" -> {
                    val action = item.optJSONObject("action")
                    action?.optString("query")?.takeIf { it.isNotBlank() }?.let { searched += it }
                }
            }
        }
        if (summary.isEmpty()) body.optString("output_text").takeIf { it.isNotBlank() }?.let { summary.append(it) }
        // Some responses list the sources only at the top level, as plain URLs.
        body.optJSONArray("citations")?.let { list ->
            for (index in 0 until list.length()) {
                val url = when (val value = list.opt(index)) {
                    is String -> value
                    is JSONObject -> value.optString("url")
                    else -> null
                }?.takeIf { it.startsWith("http") } ?: continue
                if (url !in citations) citations[url] = null
            }
        }
        if (summary.isBlank() && citations.isEmpty()) throw IOException("Grok 没有返回搜索结果，请换个说法再试")
        return SearchResults.entry(query, summary.toString().trim(), citations, searched)
    }

    private suspend fun request(url: String, token: String, payload: JSONObject, viaProxy: Boolean): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("x-grok-session-id", UUID.randomUUID().toString())
        ProviderAccountManager.GROK_HEADERS.forEach { (key, value) ->
            // The token-auth headers are only for the CLI proxy.
            if (viaProxy || key.lowercase() !in setOf("x-xai-token-auth", "x-authenticateresponse")) builder.header(key, value)
        }
        builder.header("Authorization", "Bearer $token")
        val call = client.newCall(builder.build())
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("Grok 返回空响应")
                    if (!response.isSuccessful) {
                        source.request(ERROR_BYTES)
                        return@use response.code to source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BYTES))
                    }
                    if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Grok 返回的内容过大")
                    response.code to source.readUtf8()
                }
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Grok 搜索超时，请稍后再试", error)
        } finally {
            watcher.cancel()
        }
    }
}
