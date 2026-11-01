package com.ai.assistance.operit.features.search

import android.content.Context
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.data.api.CodexHttpException
import com.ai.assistance.operit.data.api.isCodexExpiredToken
import java.io.IOException
import java.io.InterruptedIOException
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
import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web search with the app's ChatGPT login, the same login Codex models and Codex Draw use. The
 * token stays in the host. It first uses Codex's own search endpoint (`alpha/search`, as the Codex
 * CLI's web tool does), which returns the search results themselves; when that endpoint refuses,
 * it falls back to a Responses request with the `web_search` tool, which returns a model's answer.
 */
object CodexSearchBridge {
    private const val ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
    private const val SEARCH_ENDPOINT = "https://chatgpt.com/backend-api/codex/alpha/search"
    private const val DEFAULT_MODEL = "gpt-6-luna"
    private val MODEL_PATTERN = Regex("""gpt-[a-zA-Z0-9.\-]+""")
    private val CONTEXT_SIZES = setOf("low", "medium", "high")
    private const val MAX_QUERIES = 5
    private const val MAX_QUERY_CHARS = 2_000
    /** An answer with its events stays far below this; more means something else came back. */
    private const val MAX_STREAM_CHARS = 4_000_000
    /** One SSE line is read only up to this, so a single huge line cannot fill memory. */
    private const val MAX_LINE_BYTES = 1_000_000L
    private const val ERROR_BYTES = 16L * 1024
    /**
     * Text and sources of all queries in one call share this, each query its equal part, so every
     * query still fits when the tool result is cut to its size limit.
     */
    private const val RESULT_BUDGET_CHARS = 40_000
    private const val MAX_QUERY_CHARS_BUDGET = 16_000
    private const val MAX_RESULTS = 10
    private const val MAX_SEARCH_BYTES = 4L * 1024 * 1024

    /** ChatGPT's Cloudflare cookies, kept in memory so a challenge passed once is not met again. */
    private object Cookies : CookieJar {
        private val saved = mutableMapOf<String, Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { saved["${it.domain}|${it.path}|${it.name}"] = it }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            saved.values.removeAll { it.expiresAt < now }
            return saved.values.filter { it.matches(url) }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(Cookies)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    /** The direct endpoint only searches, so it answers fast; a slow one gives way to the fallback early. */
    private val directClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun execute(context: Context, parametersJson: String): String {
        val params = JSONObject(parametersJson)
        val queries = SearchResults.queries(params, MAX_QUERIES, MAX_QUERY_CHARS)
        val contextSize = params.optString("search_context_size").trim().lowercase().ifBlank { "medium" }
        require(contextSize in CONTEXT_SIZES) { "search_context_size 只能是 low、medium 或 high" }
        val model = params.optString("model").trim().ifBlank { DEFAULT_MODEL }
        require(MODEL_PATTERN.matches(model)) { "model 必须是 gpt 开头的 Codex 模型，比如 $DEFAULT_MODEL" }

        val auth = CodexAuthManager.getInstance(context)
        require(auth.authState.value != null) {
            "还没登录 ChatGPT。请先在模型设置里登录 Codex（Orbit 里可用 orbit_vault request_chatgpt_login）"
        }
        val session = UUID.randomUUID().toString()
        val budget = minOf(MAX_QUERY_CHARS_BUDGET, RESULT_BUDGET_CHARS / queries.size)
        val searches = coroutineScope {
            queries.map { query ->
                async {
                    try {
                        auth.withAuthRecovery { account ->
                            try {
                                direct(context, account, query, model, contextSize, session, budget)
                            } catch (error: CodexHttpException) {
                                // A refused login goes to the login recovery, not to the fallback.
                                throw error
                            } catch (error: IOException) {
                                // The direct endpoint is internal and may refuse; the model route still searches.
                                search(context, account, query, model, contextSize)
                                    .put("note", "直接搜索接口不可用，这次改由模型搜索并总结：${error.message?.take(200)}")
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        SearchResults.failed(query, error.message ?: "Codex 搜索失败")
                    }
                }
            }.awaitAll()
        }
        return SearchResults.wrap("codex", model, searches).toString()
    }

    /**
     * One query on Codex's search endpoint: the search results (title, link, snippet) and the
     * endpoint's own text of them, with no model answer written on top.
     */
    private suspend fun direct(
        context: Context,
        account: com.ai.assistance.operit.data.preferences.CodexAuthState,
        query: String,
        model: String,
        contextSize: String,
        session: String,
        budget: Int,
    ): JSONObject {
        val payload = JSONObject()
            .put("id", session)
            .put("model", model)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", query)))))
            // One command per request: the endpoint is known to refuse several at once.
            .put("commands", JSONObject().put("search_query", JSONArray().put(JSONObject().put("q", query))))
            .put("settings", JSONObject()
                // "low" is answered with a Cloudflare challenge on this endpoint, so it searches as "medium".
                .put("search_context_size", if (contextSize == "low") "medium" else contextSize)
                .put("allowed_callers", JSONArray().put("direct"))
                .put("external_web_access", true))
            .put("max_output_tokens", 8000)
        val builder = Request.Builder()
            .url(SEARCH_ENDPOINT)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-ID", account.accountId)
            .header("originator", "codex_cli_rs")
            .header("User-Agent", "Operit/${context.packageName}")
            .header("Accept", "application/json")
            .header("OpenAI-Beta", "responses=experimental")
        account.residency?.let { builder.header("x-openai-internal-codex-residency", it) }
        var (status, text) = post(builder.build())
        // A Cloudflare check usually passes on the next try, with the cookie it just set.
        if (status == 403 && Regex("(?i)cloudflare|just a moment|cf-chl|challenge").containsMatchIn(text)) {
            delay(750)
            val retry = post(builder.build())
            status = retry.first
            text = retry.second
            if (status == 403) throw IOException("直接搜索被 ChatGPT 的网页验证拦下（与账号权限无关）")
        }
        if (status == 401) throw CodexHttpException(401, isCodexExpiredToken(401, text))
        // Only a reason for the note on the fallback: the fallback itself reports real account problems.
        if (status !in 200..299) throw IOException("直接搜索接口没有接受这次请求（HTTP $status）" +
            SearchResults.errorText(text).takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }?.let { "：$it" }.orEmpty())
        val body = try { JSONObject(text) } catch (_: Exception) { throw IOException("Codex 搜索返回的内容看不懂") }
        val output = body.optString("output").trim()
        val sources = LinkedHashMap<String, SearchResults.Source>()
        // Sources take at most half of this query's part; the text gets the rest.
        var sourceChars = 0
        body.optJSONArray("results")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val url = item.optString("url").trim().takeIf { it.startsWith("http") } ?: continue
                if (url in sources || url.length > 500) continue
                fun field(name: String) = item.optString(name).trim().takeIf { it.isNotEmpty() }
                val source = SearchResults.Source(url, field("title")?.take(200), field("snippet")?.take(400))
                val size = url.length + (source.title?.length ?: 0) + (source.snippet?.length ?: 0) + 20
                if (sourceChars + size > budget / 2) break
                sources[url] = source
                sourceChars += size
                if (sources.size >= MAX_RESULTS) break
            }
        }
        if (output.isEmpty() && sources.isEmpty()) throw IOException("Codex 搜索没有返回结果")
        val textChars = budget - sourceChars
        val summary = if (output.length > textChars) output.take(textChars) + "\n…（后面的内容过长，已截断）" else output
        return SearchResults.entry(query, summary, sources.values.toList(), listOf(query))
    }

    /** Posts one request and reads the status and body, cancelling the call when the caller is cancelled. */
    private suspend fun post(request: Request): Pair<Int, String> {
        val call = directClient.newCall(request)
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("Codex 返回空响应")
                    if (!response.isSuccessful) {
                        source.request(ERROR_BYTES)
                        return@use response.code to source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BYTES))
                    }
                    if (source.request(MAX_SEARCH_BYTES + 1)) throw IOException("Codex 返回的内容过大")
                    response.code to source.readUtf8()
                }
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Codex 搜索超时，请稍后再试", error)
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun search(
        context: Context,
        account: com.ai.assistance.operit.data.preferences.CodexAuthState,
        query: String,
        model: String,
        contextSize: String,
    ): JSONObject {
        val tool = JSONObject()
            .put("type", "web_search")
            .put("external_web_access", true)
            .put("search_context_size", contextSize)
        val payload = JSONObject()
            .put("model", model)
            .put("instructions", "You are a concise web search assistant. Use web search, answer the query, " +
                "and preserve source citations from annotations. Answer in the language of the query.")
            .put("input", JSONArray().put(JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", query)))))
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", "required")
            .put("parallel_tool_calls", true)
            .put("store", false)
            .put("stream", true)
            .put("include", JSONArray())
        val builder = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-ID", account.accountId)
            .header("originator", "codex_cli_rs")
            .header("User-Agent", "Operit/${context.packageName}")
            .header("Accept", "text/event-stream")
            .header("OpenAI-Beta", "responses=experimental")
            .header("session-id", UUID.randomUUID().toString())
        account.residency?.let { builder.header("x-openai-internal-codex-residency", it) }
        val call = client.newCall(builder.build())
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("Codex 返回空响应")
                    if (!response.isSuccessful) {
                        source.request(ERROR_BYTES)
                        val text = source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BYTES))
                        // 401 goes to the login recovery, which refreshes the token or asks to sign in again.
                        if (response.code == 401) throw CodexHttpException(401, isCodexExpiredToken(401, text))
                        throw IOException(SearchResults.httpFailure("Codex", response.code, SearchResults.errorText(text)))
                    }
                    val text = StringBuilder()
                    val citations = LinkedHashMap<String, String?>()
                    val searched = mutableListOf<String>()
                    val streamed = StringBuilder()
                    var read = 0
                    var completed = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = try {
                            source.readUtf8LineStrict(MAX_LINE_BYTES)
                        } catch (_: java.io.EOFException) {
                            // The stream ended (a last line without a newline carries no event), or a line ran too long.
                            if (source.buffer.size > MAX_LINE_BYTES) throw IOException("Codex 返回的内容过大")
                            break
                        }
                        read += line.length
                        if (read > MAX_STREAM_CHARS) throw IOException("Codex 返回的内容过大")
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val event = try { JSONObject(data) } catch (_: Exception) { continue }
                        when (event.optString("type")) {
                            "response.output_text.delta" -> streamed.append(event.optString("delta"))
                            "response.output_item.done" -> collect(event.optJSONObject("item"), text, citations, searched)
                            "response.failed", "error" -> {
                                val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
                                val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                                    ?: error?.optString("code")?.takeIf { it.isNotBlank() } ?: "Codex 搜索失败"
                                throw IOException("Codex 搜索失败：${message.take(300)}")
                            }
                            "response.completed" -> { completed = true; break }
                        }
                    }
                    // A stream that stops before the end is a cut-off answer, not a result.
                    if (!completed) throw IOException("Codex 搜索中途断开，请稍后再试")
                    val summary = text.toString().ifBlank { streamed.toString() }.trim()
                    if (summary.isEmpty() && citations.isEmpty()) throw IOException("Codex 没有返回搜索结果，请换个说法再试")
                    SearchResults.entry(query, summary, citations, searched)
                }
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Codex 搜索超时，请稍后再试", error)
        } finally {
            watcher.cancel()
        }
    }

    /** The answer text, its url citations and the searches the model ran, from one finished output item. */
    private fun collect(item: JSONObject?, text: StringBuilder, citations: MutableMap<String, String?>, searched: MutableList<String>) {
        item ?: return
        when (item.optString("type")) {
            "web_search_call" -> {
                val action = item.optJSONObject("action")
                action?.optString("query")?.takeIf { it.isNotBlank() }?.let { searched += it }
                action?.optJSONArray("queries")?.let { list ->
                    for (index in 0 until list.length()) list.optString(index).takeIf { it.isNotBlank() }?.let { searched += it }
                }
            }
            "message" -> {
                if (item.optString("role") != "assistant") return
                val content = item.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") != "output_text") continue
                    text.append(part.optString("text"))
                    SearchResults.annotations(part.optJSONArray("annotations"), citations)
                }
            }
        }
    }
}
