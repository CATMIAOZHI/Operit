package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.preferences.ApiPreferences
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import okio.Buffer

internal object GrokAccountPolicy {
    fun hasReasoning(model: String) = model !in setOf(
        "grok-4.20-0309-non-reasoning", "grok-build-0.1", "grok-composer-2.5-fast")
    fun efforts(model: String) = when (model) {
        "grok-4.6" -> listOf("low", "medium", "high", "xhigh")
        "grok-4.5" -> listOf("low", "medium", "high")
        else -> emptyList()
    }
    fun effort(model: String, requested: String): String? =
        if (!hasReasoning(model) || requested == "none") null
        else ThinkingRequestSemantics.catalogReasoningEffort(requested, efforts(model))
}

internal fun antigravityThinkingEffort(
    model: String, quality: Int, enabled: Boolean, parameters: List<ModelParameter<*>>,
): String {
    if (model == "gemini-pro-agent") return "high"
    if (model == "gemini-3.1-pro-low") return "low"
    if (!enabled) return "low"
    // Parse only explicit parameters here; Gemini 3.7's automatic medium is not a user override.
    val explicit = buildGeminiThinkingConfig(true, parameters, "")?.optString("thinkingLevel")
        ?.takeIf { it.isNotBlank() }
    return when ((explicit ?: ApiPreferences.thinkingQualityEffort(quality)).lowercase()) {
        "minimal", "low" -> "low"
        "medium" -> "medium"
        else -> "high"
    }
}

internal fun prepareAccountReplay(content: JSONObject, expectedScope: String): JSONObject {
    val value = JSONObject(content.toString())
    val storedScope = value.optString("_operit_account_scope")
    value.remove("_operit_account_scope")
    if (storedScope != expectedScope) {
        val parts = value.optJSONArray("parts") ?: JSONArray()
        val retained = JSONArray()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought")) continue
            part.remove("thoughtSignature")
            part.remove("thought_signature")
            retained.put(part)
        }
        value.put("parts", retained)
    }
    return value
}

class AccountApiKeyProvider(private val manager: ProviderAccountManager) : ApiKeyProvider {
    override suspend fun getApiKey() = manager.validAccount().accessToken
    override suspend fun getCandidateKeyCount() = if (manager.account.value == null) 0 else 1
}

class GrokAccountProvider(
    private val manager: ProviderAccountManager,
    model: String,
    client: OkHttpClient,
    customHeaders: Map<String, String>,
    vision: Boolean,
    tools: Boolean,
) : KimiProvider(
    apiEndpoint = ENDPOINT, apiKeyProvider = AccountApiKeyProvider(manager), modelName = model,
    client = client, customHeaders = customHeaders, providerType = ApiProviderType.GROK_ACCOUNT,
    supportsVision = vision, enableToolCall = tools, configureThinking = false,
    reasoningEfforts = GrokAccountPolicy.efforts(model),
) {
    private val fallbackSession = UUID.randomUUID().toString()
    override suspend fun applyAuthenticationHeaders(builder: Request.Builder, currentApiKey: String) {
        super.applyAuthenticationHeaders(builder, currentApiKey)
        val session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSession
        builder.header("x-grok-conv-id", session).header("x-grok-session-id", session)
        ProviderAccountManager.GROK_HEADERS.forEach { (key, value) -> builder.header(key, value) }
    }
    override fun applyRequestIdentityHeaders(builder: Request.Builder, logicalRequestId: String) {
        builder.header("x-grok-req-id", logicalRequestId)
    }
    override fun createRequestBody(
        context: Context, chatHistory: List<PromptTurn>, modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean, stream: Boolean, availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
    ): RequestBody {
        val body = super.createRequestBody(context, chatHistory, modelParameters, enableThinking,
            stream, availableTools, preserveThinkInHistory)
        val json = JSONObject(Buffer().also { body.writeTo(it) }.readUtf8())
        if (json.has("reasoning_effort")) {
            val effort = GrokAccountPolicy.effort(modelName, json.getString("reasoning_effort"))
            if (effort == null) json.remove("reasoning_effort") else json.put("reasoning_effort", effort)
        }
        if (!GrokAccountPolicy.hasReasoning(modelName)) {
            val messages = json.optJSONArray("messages")
            if (messages != null) for (i in 0 until messages.length()) messages.optJSONObject(i)?.remove("reasoning_content")
        }
        return createJsonRequestBody(json.toString())
    }
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        try { Result.success(manager.availableGrokModels()) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }

    companion object {
        const val ENDPOINT = "https://cli-chat-proxy.grok.com/v1/chat/completions"
    }
}

/** CCA transport reuses Gemini's native tool stream and lossless response-part replay. */
class AntigravityTransport(private val manager: ProviderAccountManager, private val model: String) {
    private val fallbackSession = UUID.randomUUID().toString()
    private val names = mutableMapOf<String, String>()
    var activeReplayScope: String = ""
        private set
    fun currentReplayScope() = "${manager.account.value?.identity}:$model"

    suspend fun request(body: RequestBody, streaming: Boolean): Request {
        val account = manager.validAccount()
        activeReplayScope = "${account.identity}:$model"
        val raw = Buffer().also { body.writeTo(it) }.readUtf8()
        names.clear()
        val session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSession
        val compiled = compile(JSONObject(raw), model, session, names)
        val envelope = JSONObject().put("project", account.projectId).put("model", compiled.first)
            .put("userAgent", "antigravity").put("requestType", "agent")
            .put("requestId", "agent-${UUID.randomUUID()}").put("request", compiled.second)
        return Request.Builder()
            .url("${ProviderAccountManager.CCA}/v1internal:" +
                if (streaming) "streamGenerateContent?alt=sse" else "generateContent")
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", ProviderAccountManager.ANTIGRAVITY_UA)
            .post(envelope.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun unwrap(envelope: JSONObject): JSONObject {
        if (envelope.has("error")) throw IOException("Antigravity returned an error")
        val response = envelope.optJSONObject("response")
            ?: throw IOException("Antigravity response envelope is missing")
        val candidates = response.optJSONArray("candidates") ?: return response
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val call = parts.optJSONObject(j)?.optJSONObject("functionCall") ?: continue
                names[call.optString("name")]?.let { call.put("name", it) }
            }
        }
        return response
    }

    suspend fun models(): List<ModelOption> = parseModels(manager.availableModels())

    companion object {
        internal fun parseModels(root: JSONObject): List<ModelOption> {
            val models = root.optJSONObject("models") ?: throw IOException("Antigravity models are missing")
            val ids = linkedSetOf<String>()
            val sorts = root.optJSONArray("agentModelSorts") ?: JSONArray()
            for (sort in 0 until sorts.length()) {
                val groups = sorts.optJSONObject(sort)?.optJSONArray("groups") ?: continue
                for (i in 0 until groups.length()) {
                    val values = groups.optJSONObject(i)?.optJSONArray("modelIds") ?: continue
                    for (j in 0 until values.length()) ids.add(values.getString(j))
                }
            }
            val flash = root.optJSONObject("tieredModelIds")?.optJSONArray("flash")
            if (flash != null) for (i in 0 until flash.length()) ids.add(flash.getString(i))
            val images = root.optJSONArray("imageGenerationModelIds")
            if (images != null && (0 until images.length()).any { images.optString(it) == "gemini-3.1-flash-image" }) {
                ids.add("gemini-3.1-flash-image")
            }
            return ids.mapNotNull { id ->
                models.optJSONObject(id)?.let { ModelOption(id, it.optString("displayName").ifBlank { id }) }
            }.also { if (it.isEmpty()) throw IOException("No Antigravity agent models available") }
        }

        internal fun compile(input: JSONObject, model: String, session: String,
            names: MutableMap<String, String> = mutableMapOf()): Pair<String, JSONObject> {
            val body = JSONObject(input.toString())
            // CCA accepts the Gemini wire shape, not arbitrary GenerateContent extensions.
            body.keys().asSequence().toList().filter {
                it !in setOf("contents", "systemInstruction", "tools", "generationConfig", "toolConfig")
            }.forEach(body::remove)
            body.put("sessionId", session)
            val generation = body.optJSONObject("generationConfig")
            generation?.keys()?.asSequence()?.toList()?.filter {
                it !in setOf("maxOutputTokens", "temperature", "topP", "stopSequences", "thinkingConfig", "responseModalities")
            }?.forEach(generation::remove)
            val thinking = generation?.optJSONObject("thinkingConfig")
            val effort = thinking?.optString("thinkingLevel").orEmpty()
            generation?.remove("thinkingConfig")
            val wire = if (model == "gemini-3.7-flash") "gemini-3.7-flash-tiered" else model
            if (effort.isNotBlank() && wire !in setOf("gemini-pro-agent", "gemini-3.1-pro-low")) {
                generation?.put("thinkingConfig", JSONObject().put("thinkingLevel",
                    when (effort.lowercase()) { "minimal", "low" -> "low"; "medium" -> "medium"; else -> "high" }))
            }
            fun wireName(name: String): String {
                val encoded = if (Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}").matches(name)) name else
                    "operit_" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
                        .joinToString("") { "%02x".format(it) }.take(48)
                check(names[encoded] == null || names[encoded] == name) { "Ambiguous tool name mapping" }
                names[encoded] = name
                return encoded
            }
            val tools = body.optJSONArray("tools")
            val retainedTools = JSONArray()
            if (tools != null) for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val declarations = tool.optJSONArray("functionDeclarations")
                    ?: tool.optJSONArray("function_declarations") ?: continue
                for (j in 0 until declarations.length()) {
                    val declaration = declarations.getJSONObject(j)
                    declaration.put("name", wireName(declaration.getString("name")))
                }
                retainedTools.put(JSONObject().put("functionDeclarations", declarations))
            }
            if (retainedTools.length() == 0) body.remove("tools") else body.put("tools", retainedTools)
            val contents = body.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until contents.length()) {
                val content = contents.getJSONObject(i)
                val parts = content.optJSONArray("parts") ?: continue
                val retained = JSONArray()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    for ((old, canonical) in mapOf("thought_signature" to "thoughtSignature",
                        "inline_data" to "inlineData", "file_data" to "fileData",
                        "function_call" to "functionCall", "function_response" to "functionResponse")) {
                        if (part.has(old)) { part.put(canonical, part.get(old)); part.remove(old) }
                    }
                    for (media in listOf("inlineData", "fileData")) {
                        part.optJSONObject(media)?.let { data ->
                            for ((old, canonical) in mapOf("mime_type" to "mimeType", "file_uri" to "fileUri")) {
                                if (data.has(old)) { data.put(canonical, data.get(old)); data.remove(old) }
                            }
                        }
                    }
                    if (content.optString("role") != "model") part.remove("thoughtSignature")
                    if (wire.startsWith("claude") && part.optBoolean("thought") && !part.has("thoughtSignature")) continue
                    for (key in listOf("functionCall", "functionResponse")) {
                        val function = part.optJSONObject(key) ?: continue
                        function.put("name", wireName(function.getString("name")))
                    }
                    retained.put(part)
                }
                content.put("parts", retained)
            }
            if (wire.startsWith("claude") && retainedTools.length() > 0) {
                body.put("toolConfig", JSONObject("""{"functionCallingConfig":{"mode":"VALIDATED"}}"""))
            }
            return wire to body
        }
    }
}
