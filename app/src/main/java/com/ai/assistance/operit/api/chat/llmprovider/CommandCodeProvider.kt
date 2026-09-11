package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject

class CommandCodeProvider(
    private val manager: ProviderAccountManager, model: String, client: OkHttpClient,
    headers: Map<String, String>, vision: Boolean, tools: Boolean,
) : KimiProvider(
    apiEndpoint = ENDPOINT, apiKeyProvider = AccountApiKeyProvider(manager), modelName = model,
    client = client.newBuilder().addInterceptor(CommandCodeTransport()).build(),
    customHeaders = headers, providerType = ApiProviderType.COMMAND_CODE,
    supportsVision = vision, enableToolCall = tools, configureThinking = false,
) {
    override val requiresStreamingResponse = true
    private val fallbackSession = UUID.randomUUID().toString()
    override suspend fun applyAuthenticationHeaders(builder: Request.Builder, currentApiKey: String) {
        super.applyAuthenticationHeaders(builder, currentApiKey)
        val session = currentCoroutineContext()[OpenCodeSessionContext]
        builder.header("x-session-id", session?.sessionId ?: fallbackSession)
        // The transport runs on a synchronous interceptor, so the workspace this request belongs to
        // travels on the request itself. A chat with no bound workspace reports an empty working
        // directory, mirroring the system prompt, which omits its workspace section in that case:
        // Operit's managed workspace is an app-sandbox path that must not be presented to the model
        // as if the conversation had a project directory.
        builder.tag(
            CommandCodeWorkspace::class.java,
            CommandCodeWorkspace(session?.workspacePath?.takeIf { it.isNotBlank() } ?: ""),
        )
    }
    override suspend fun getModelsList(context: android.content.Context): Result<List<ModelOption>> = try {
        Result.success(manager.availableCommandCodeModels())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    companion object { const val ENDPOINT = "https://api.commandcode.ai/alpha/generate" }
}

/** Workspace of one request, handed from the suspend request path to the synchronous transport. */
internal data class CommandCodeWorkspace(val path: String)

internal object CommandCodePolicy {
    fun effort(model: String, requested: String): String? {
        if (requested == "none") return null
        val supported = when (model.lowercase()) {
            "deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash", "zai-org/glm-5.2" -> listOf("high", "max")
            "zai-org/glm-5.3" -> listOf("low", "high", "max")
            "meta/muse-spark-1.2", "meta/muse-spark-1.2-contributor", "meta/muse-spark-1.1" ->
                listOf("low", "medium", "high", "xhigh", "max")
            else -> emptyList()
        }
        return ThinkingRequestSemantics.catalogReasoningEffort(requested, supported)
    }
}

/** Reuse Operit's established history/tool/reasoning loop; only the HTTP wire differs. */
internal class CommandCodeTransport : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        check(original.url.toString() == CommandCodeProvider.ENDPOINT) { "Unexpected Command Code endpoint" }
        val buffer = Buffer()
        requireNotNull(original.body).writeTo(buffer)
        val workspace = original.tag(CommandCodeWorkspace::class.java)?.path ?: ""
        val body = compile(JSONObject(buffer.readUtf8()), workspace)
        val request = original.newBuilder()
            .header("User-Agent", "cli").header("x-command-code-version", "0.52.1")
            .header("x-cli-environment", "production").header("x-taste-learning", "false").header("x-co-flag", "false")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        val raw = response.body ?: throw IOException("Command Code response body missing")
        val stream = CommandCodeEventSource(raw.source()).buffer()
        return response.newBuilder().removeHeader("Content-Length")
            .header("Content-Type", "text/event-stream")
            .body(object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = stream
            }).build()
    }

    companion object {
        fun compile(input: JSONObject, workspacePath: String): JSONObject {
            val messages = JSONArray()
            val pending = linkedMapOf<String, String>()
            val system = mutableListOf<String>()
            fun result(id: String, name: String, value: String, error: Boolean = false) = JSONObject()
                .put("role", "tool").put("content", JSONArray().put(JSONObject()
                    .put("type", "tool-result").put("toolCallId", id).put("toolName", name)
                    .put("output", JSONObject().put("type", if (error) "error-text" else "text").put("value", value))))
            fun closePending() {
                pending.forEach { (id, name) -> messages.put(result(id, name, "No result was recorded; execution status unknown.", true)) }
                pending.clear()
            }
            fun content(value: Any?): JSONArray {
                if (value is String) return JSONArray().put(JSONObject().put("type", "text").put("text", value))
                val parts = JSONArray()
                if (value is JSONArray) for (i in 0 until value.length()) {
                    val part = value.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text" -> parts.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                        "image_url" -> {
                            val url = part.getJSONObject("image_url").getString("url")
                            parts.put(JSONObject().put("type", "image").put("image", url).apply {
                                if (url.startsWith("data:")) put("mediaType", url.substringAfter("data:").substringBefore(';'))
                            })
                        }
                        else -> throw IOException("Unsupported Command Code message content")
                    }
                }
                return parts
            }
            val history = input.getJSONArray("messages")
            for (i in 0 until history.length()) {
                val row = history.getJSONObject(i)
                val role = row.getString("role")
                if (role == "system" || role == "developer") {
                    val parts = content(row.opt("content"))
                    system.add((0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).optString("text") })
                    continue
                }
                if (role == "tool") {
                    val id = row.optString("tool_call_id")
                    val name = pending.remove(id)
                    if (name != null) messages.put(result(id, name, row.optString("content")))
                    else {
                        closePending()
                        messages.put(JSONObject().put("role", "user").put("content",
                            content("[Unpaired tool result $id]\n${row.optString("content")}")))
                    }
                    continue
                }
                closePending()
                val parts = content(row.opt("content"))
                if (role == "assistant") {
                    row.optString("reasoning_content").takeIf { it.isNotBlank() }?.let {
                        parts.put(JSONObject().put("type", "reasoning").put("text", it))
                    }
                    val calls = row.optJSONArray("tool_calls") ?: JSONArray()
                    for (j in 0 until calls.length()) {
                        val call = calls.getJSONObject(j)
                        val id = call.getString("id")
                        val function = call.getJSONObject("function")
                        val name = function.getString("name")
                        val args = function.opt("arguments")
                        val parsed = if (args is String) JSONObject(args) else args ?: JSONObject()
                        parts.put(JSONObject().put("type", "tool-call").put("toolCallId", id)
                            .put("toolName", name).put("input", parsed))
                        pending[id] = name
                    }
                }
                messages.put(JSONObject().put("role", role).put("content", parts))
            }
            closePending()
            val tools = JSONArray()
            val originals = input.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until originals.length()) {
                val tool = originals.getJSONObject(i).getJSONObject("function")
                tools.put(JSONObject().put("name", tool.getString("name"))
                    .put("description", tool.optString("description"))
                    .put("input_schema", tool.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
            }
            val model = input.getString("model")
            val params = JSONObject().put("model", model).put("messages", messages).put("tools", tools)
                .put("system", system.joinToString("\n\n")).put("stream", true)
                .put("max_tokens", input.opt("max_completion_tokens") ?: input.opt("max_tokens") ?: 64000)
            input.opt("temperature")?.let { params.put("temperature", it) }
            input.optString("reasoning_effort").takeIf { it.isNotBlank() }?.let {
                CommandCodePolicy.effort(model, it)?.let { effort -> params.put("reasoning_effort", effort) }
            }
            return JSONObject().put("params", params)
                .put("config", JSONObject()
                    .put("workingDir", workspacePath)
                    .put("environment", "android")
                    .put("date", java.time.LocalDate.now().toString())
                    .put("structure", JSONArray())
                    // /alpha/generate requires the git block; Operit's app process has no git binary
                    // and cannot read the workspace repository state, so report the schema's
                    // "no repository" shape rather than guessing branch, status, or commits.
                    .put("isGitRepo", false).put("currentBranch", "").put("mainBranch", "")
                    .put("gitStatus", "").put("recentCommits", JSONArray()))
                .put("memory", "").put("taste", JSONObject.NULL).put("skills", JSONObject.NULL)
                .put("permissionMode", "standard").put("mode", "agent")
        }
    }
}

/** Decode one bounded NDJSON event at a time, preserving backpressure and cancellation. */
internal class CommandCodeEventSource(private val upstream: BufferedSource) : Source {
    private val output = Buffer()
    private var terminal: JSONObject? = null
    private var ended = false
    private var toolIndex = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0
        while (output.size == 0L && !ended) {
            val line = try { upstream.readUtf8LineStrict(8L * 1024 * 1024) }
            catch (_: EOFException) {
                if (upstream.buffer.size > 8L * 1024 * 1024) throw IOException("Command Code event too large")
                if (upstream.buffer.size > 0) upstream.readUtf8() else {
                    finish(terminal ?: throw IOException("Command Code stream ended before completion"))
                    continue
                }
            }.trim().removePrefix("data:").trim()
            if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:")) continue
            val event = try { JSONObject(line) } catch (_: org.json.JSONException) {
                throw IOException("Invalid Command Code stream event")
            }
            when (event.optString("type")) {
                "text-delta" -> emit(JSONObject().put("content", event.optString("text")))
                "reasoning-delta" -> emit(JSONObject().put("reasoning_content", event.optString("text")))
                "tool-call" -> {
                    val args = event.opt("input") ?: event.opt("args") ?: JSONObject()
                    emit(JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                        .put("index", toolIndex++).put("id", event.getString("toolCallId")).put("type", "function")
                        .put("function", JSONObject().put("name", event.getString("toolName")).put("arguments", args.toString())))))
                }
                "finish-step" -> terminal = event
                "finish" -> finish(event)
                "error" -> throw IOException("Command Code stream error: " +
                    (event.optJSONObject("error")?.optString("message") ?: event.optString("error")).take(1024))
            }
        }
        return if (output.size == 0L) -1 else output.read(sink, minOf(byteCount, output.size))
    }

    private fun emit(delta: JSONObject, finish: String? = null, usage: JSONObject? = null) {
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", finish ?: JSONObject.NULL)))
        if (usage != null) event.put("usage", usage)
        output.writeUtf8("data: $event\n\n")
    }

    private fun finish(event: JSONObject) {
        if (ended) return
        val raw = event.optJSONObject("totalUsage") ?: event.optJSONObject("usage")
            ?: terminal?.optJSONObject("usage")
        val usage = raw?.let {
            JSONObject().put("prompt_tokens", it.optLong("inputTokens")).put("completion_tokens", it.optLong("outputTokens"))
                .put("total_tokens", it.optLong("inputTokens") + it.optLong("outputTokens"))
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", it.optJSONObject("inputTokenDetails")?.optLong("cacheReadTokens") ?: 0))
                .put("cache_creation_input_tokens", it.optJSONObject("inputTokenDetails")?.optLong("cacheWriteTokens") ?: 0)
        }
        val reason = event.optString("rawFinishReason").ifBlank { event.optString("finishReason") }
        emit(JSONObject(), if (toolIndex > 0) "tool_calls" else if (reason == "length") "length" else "stop", usage)
        output.writeUtf8("data: [DONE]\n\n")
        ended = true
    }
    override fun timeout(): Timeout = upstream.timeout()
    override fun close() = upstream.close()
}
