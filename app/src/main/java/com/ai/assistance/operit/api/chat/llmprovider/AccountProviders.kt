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
import kotlinx.coroutines.asContextElement
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
    /** Null means this table has no mapping, so the user's chosen effort passes through. */
    fun declaredEfforts(model: String): List<String>? = efforts(model).takeIf { it.isNotEmpty() }
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
        // Kept as asked: the request picks xhigh where the account's model has that tier, else high.
        "xhigh", "max", "ultra" -> "xhigh"
        else -> "high"
    }
}

/**
 * The level shown for a request (the chat's thinking menu): xhigh only where the model has that
 * tier, the way the request will pick it, by the signed-in account's families.
 */
internal fun antigravityShownEffort(
    model: String, quality: Int, enabled: Boolean, parameters: List<ModelParameter<*>>,
): String {
    val effort = antigravityThinkingEffort(model, quality, enabled, parameters)
    // Read off the id the request will send: a family picks its tier, a fixed id (gemini-4-argon-low)
    // keeps its own, and anything else sends at most high.
    val wire = AntigravityFamilies.wire(model, effort, AntigravityFamilies.shown.value)
    Regex(".*-(low|medium|high|xhigh)").matchEntire(wire)?.let { return it.groupValues[1] }
    return if (effort == "xhigh") "high" else effort
}

/** Starts following the signed-in Antigravity account, so the thinking menu shows its tiers. */
fun watchAntigravityAccount(context: Context) {
    // Only for the menu: storage that cannot be opened now leaves it on the known tiers, and a
    // request reports the same failure the usual way.
    try {
        ProviderAccountManager.get(context, AccountProvider.ANTIGRAVITY)
    } catch (e: Exception) {
        com.ai.assistance.operit.util.AppLogger.w("AntigravityFamilies", "Account could not be opened: ${e.message}")
    }
}

/**
 * Models Antigravity lists as one wire id per tier (gemini-4-carbon-low/-medium/-high) show as one
 * model; the tier is picked from the chat's thinking level when the request is sent, as opencodex
 * does. The account's model list says which families exist; a few are known before it is read.
 */
internal object AntigravityFamilies {
    val TIERS = listOf("low", "medium", "high", "xhigh")
    private val BASE_TIERS = setOf("low", "medium", "high")
    // Used only until the signed-in account's own list has been read.
    private val KNOWN: Map<String, Set<String>> = mapOf(
        "gemini-3.8-flash" to BASE_TIERS, "claude-opus-5-5" to BASE_TIERS, "claude-sonnet-5-5" to BASE_TIERS,
        "gemini-4-argon" to BASE_TIERS, "gemini-4-carbon" to BASE_TIERS,
        "gemini-4-neon" to BASE_TIERS + "xhigh", "gemini-4-hydrogen" to BASE_TIERS + "xhigh",
    )
    private val shownFamilies = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<String>>?>(null)
    /**
     * The signed-in account's families, for showing a level in the thinking menu; a request uses
     * its own account's families (see ProviderAccountManager.antigravityFamilies).
     */
    val shown: kotlinx.coroutines.flow.StateFlow<Map<String, Set<String>>?> = shownFamilies

    fun publish(families: Map<String, Set<String>>?) { shownFamilies.value = families }

    /**
     * [model]'s tiers under [families], the account's families as its list showed them; with no
     * list read yet (null) only the known families count.
     */
    fun tiers(model: String, families: Map<String, Set<String>>?): Set<String> =
        families?.let { it[model] ?: emptySet() } ?: KNOWN[model] ?: emptySet()

    /** The wire id to send for [model] at [effort]; the model itself when it is not a family. */
    fun wire(model: String, effort: String, families: Map<String, Set<String>>?): String {
        val tiers = tiers(model, families).takeIf { it.isNotEmpty() } ?: return model
        val wanted = when (effort.lowercase()) {
            "minimal", "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            "xhigh", "max", "ultra" -> if ("xhigh" in tiers) "xhigh" else "high"
            // No level asked: Hydrogen's default is high, others medium.
            else -> if (model == "gemini-4-hydrogen" || "medium" !in tiers) "high" else "medium"
        }
        return "$model-${if (wanted in tiers) wanted else "high"}"
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

class AccountApiKeyProvider(private val manager: ProviderAccountManager) : ApiKeyProvider, AccountSwitchNotices {
    private val notices = RequestSwitchNotices()

    /**
     * The account to sign the next request with. Moving to another account than the last request
     * used, as when a revoked sign-in gives way, is told once with the next notice.
     */
    suspend fun account(): com.ai.assistance.operit.data.api.ProviderAccount {
        val (value, moved) = manager.takeAccount()
        notices.signed(value.identity, moved) { manager.switchedNotice(value) }
        return value
    }

    override suspend fun getApiKey() = account().accessToken
    override suspend fun getCandidateKeyCount() = manager.usableAccountCount()

    override suspend fun takeSwitchNotice(): String? = notices.take()

    override suspend fun claimSwitchRetry(): Boolean = notices.claimRetry { manager.account.value?.identity }
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
    reasoningEfforts = GrokAccountPolicy.declaredEfforts(model),
) {
    private val fallbackSession = UUID.randomUUID().toString()
    override suspend fun applyAuthenticationHeaders(builder: Request.Builder, currentApiKey: String) {
        super.applyAuthenticationHeaders(builder, currentApiKey)
        val session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSession
        builder.header("x-grok-conv-id", session).header("x-grok-session-id", session)
        com.ai.assistance.operit.data.api.GrokClientIdentity.headers().forEach { (key, value) -> builder.header(key, value) }
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
        return createJsonRequestBody(json)
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
class AntigravityTransport(
    private val manager: ProviderAccountManager,
    private val model: String,
    private val keys: AccountApiKeyProvider = AccountApiKeyProvider(manager),
) {
    private val fallbackSession = UUID.randomUUID().toString()
    private val names = mutableMapOf<String, String>()
    /**
     * The account of the attempt running on this coroutine (see [scoped]); one provider may serve
     * several chats at once, so it is never kept on the provider itself.
     */
    private val attemptAccount = ThreadLocal<com.ai.assistance.operit.data.api.ProviderAccount?>()

    /** The replay scope of the attempt running here: its history and reply follow its account. */
    fun currentReplayScope() = "${(attemptAccount.get() ?: manager.account.value)?.identity}:$model"

    /** The account the next attempt signs with, fixed before its history is built. */
    suspend fun pick(): com.ai.assistance.operit.data.api.ProviderAccount = keys.account()

    /** Carries [account] with the attempt's coroutine, for building its history and reading its reply. */
    fun scoped(account: com.ai.assistance.operit.data.api.ProviderAccount): kotlin.coroutines.CoroutineContext =
        attemptAccount.asContextElement(account)

    /**
     * A Gemini request handed over as the object itself, made for one attempt only: [request]
     * rewrites it in place and encodes just the final envelope, instead of reading text back in.
     */
    class JsonBody(val json: JSONObject) : RequestBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun writeTo(sink: okio.BufferedSink) {
            sink.writeUtf8(json.toString())
        }
    }

    suspend fun request(
        body: RequestBody,
        streaming: Boolean,
        picked: com.ai.assistance.operit.data.api.ProviderAccount? = null,
    ): Request {
        val account = picked ?: keys.account()
        // This account's own families, fixed for this request whatever another account loads meanwhile.
        val families = manager.antigravityFamilies(account.identity)
        // Parsed straight from the body, so the text copy can go before the request is compiled.
        val input = (body as? JsonBody)?.json ?: JSONObject(Buffer().also { body.writeTo(it) }.readUtf8())
        names.clear()
        val session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSession
        val compiled = compile(input, model, session, names, families)
        val envelope = JSONObject().put("project", account.projectId).put("model", compiled.first)
            .put("userAgent", "antigravity").put("requestType", "agent")
            .put("requestId", "agent-${UUID.randomUUID()}").put("request", compiled.second)
        return Request.Builder()
            .url("${ProviderAccountManager.CCA_DAILY}/v1internal:" +
                if (streaming) "streamGenerateContent?alt=sse" else "generateContent")
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", ProviderAccountManager.antigravityUserAgent(compiled.first))
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

    suspend fun models(): List<ModelOption> {
        val (identity, body) = manager.availableModelsOfAccount()
        val catalog = parseCatalog(body)
        // A list that arrives after the account changed describes the old account: not kept or shown.
        if (!manager.rememberAntigravityFamilies(identity, catalog.second)) {
            throw IOException(manager.accountChangedMessage())
        }
        return catalog.first
    }

    companion object {
        private val TIER_SUFFIX = Regex("(.+)-(low|medium|high|xhigh)")
        private val TIERED_ALIAS = Regex("(.+)-tiered(?:-(?:low|medium|high|xhigh))?")
        private val TIER_LABEL = Regex("\\s*\\((?:low|medium|high|xhigh|extra high)\\)\\s*$", RegexOption.IGNORE_CASE)

        internal fun parseModels(root: JSONObject): List<ModelOption> = parseCatalog(root).first

        /** The model list, and which listed models are tier families (base id to its tiers). */
        internal fun parseCatalog(root: JSONObject): Pair<List<ModelOption>, Map<String, Set<String>>> {
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
            val usable = ids.filter { id ->
                val info = models.optJSONObject(id)
                info != null && !info.optBoolean("disabled") && !id.endsWith("-disabled")
            }.toSet()
            // Only a complete low/medium/high family becomes one model; a partial one stays as listed.
            val families = LinkedHashMap<String, Set<String>>()
            for (id in usable) {
                val base = TIER_SUFFIX.matchEntire(id)?.groupValues?.get(1) ?: continue
                if (base in families || listOf("low", "medium", "high").any { "$base-$it" !in usable }) continue
                families[base] = AntigravityFamilies.TIERS.filter { "$base-$it" in usable }.toSet()
            }
            val out = LinkedHashMap<String, ModelOption>()
            for (id in usable) {
                // A tiered alias (gemini-3.8-flash-tiered) of a family shown as one model is that model.
                if (TIERED_ALIAS.matchEntire(id)?.groupValues?.get(1) in families) continue
                val base = TIER_SUFFIX.matchEntire(id)?.groupValues?.get(1)?.takeIf { it in families }
                val shown = base ?: id
                if (shown in out) continue
                val name = models.optJSONObject(id)?.optString("displayName").orEmpty()
                    .let { if (base != null) it.replace(TIER_LABEL, "") else it }.ifBlank { shown }
                out[shown] = ModelOption(shown, name)
            }
            if (out.isEmpty()) throw IOException("No Antigravity agent models available")
            return out.values.toList() to families
        }

        internal fun compile(input: JSONObject, model: String, session: String,
            names: MutableMap<String, String> = mutableMapOf(),
            families: Map<String, Set<String>>? = null): Pair<String, JSONObject> {
            // Callers pass a request object of their own: changing it in place saves a full copy,
            // which for a long chat is several megabytes.
            val body = input
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
            val wire = if (model == "gemini-3.7-flash") "gemini-3.7-flash-tiered" else AntigravityFamilies.wire(model, effort, families)
            // A tier-suffixed wire id (gemini-4-argon-low, ...) already names its effort; a second,
            // possibly contradicting thinkingLevel is not what the real client sends.
            val tierInName = Regex(".*-(low|medium|high|xhigh)").matches(wire)
            if (effort.isNotBlank() && !tierInName && wire != "gemini-pro-agent") {
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
