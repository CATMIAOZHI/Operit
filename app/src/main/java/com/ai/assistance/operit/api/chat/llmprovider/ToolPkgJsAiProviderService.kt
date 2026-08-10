package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_CALCULATE_INPUT_TOKENS
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_LIST_MODELS
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_SEND_MESSAGE
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_AI_PROVIDER_TEST_CONNECTION
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistration
import com.ai.assistance.operit.plugins.toolpkg.decodeToolPkgHookResult
import com.ai.assistance.operit.plugins.toolpkg.jsonObjectToMap
import com.ai.assistance.operit.plugins.toolpkg.toolPkgPackageManager
import com.ai.assistance.operit.util.stream.Stream
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ToolPkgJsAiProviderService(
    private val config: ModelConfigData,
    private val provider: ToolPkgAiProviderRegistration
) : AIService, TokenStatIdentitySource {
    internal sealed interface ProviderHookValue {
        data object NullValue : ProviderHookValue

        data class TextValue(
            val value: String
        ) : ProviderHookValue

        data class BooleanValue(
            val value: Boolean
        ) : ProviderHookValue

        data class NumberValue(
            val value: Number
        ) : ProviderHookValue

        data class ObjectValue(
            val value: JSONObject
        ) : ProviderHookValue

        data class ArrayValue(
            val value: JSONArray
        ) : ProviderHookValue
    }

    @Volatile
    private var currentInputTokenCount: Int = 0

    @Volatile
    private var currentCachedInputTokenCount: Int = 0

    @Volatile
    private var currentOutputTokenCount: Int = 0

    private val executionChatId =
        "toolpkg-ai-provider:${provider.providerId}:${UUID.randomUUID().toString().replace("-", "")}"

    private val providerRuntimeContextKey =
        "toolpkg_provider:${provider.containerPackageName}:${provider.providerId.trim().lowercase()}"

    override val inputTokenCount: Int
        get() = currentInputTokenCount

    override val cachedInputTokenCount: Int
        get() = currentCachedInputTokenCount

    override val outputTokenCount: Int
        get() = currentOutputTokenCount

    override val providerModel: String
        get() = "${provider.displayName}:${config.modelName}"

    override val tokenStatProvider: String
        get() = provider.providerId

    override val tokenStatModel: String
        get() = config.modelName

    override fun resetTokenCounts() {
        currentInputTokenCount = 0
        currentCachedInputTokenCount = 0
        currentOutputTokenCount = 0
    }

    override fun cancelStreaming() {
        toolPkgPackageManager().cancelToolPkgExecutionsForChat(executionChatId)
    }

    /**
     * 测试缝：替换真实包管理器 hook 调用，使 sendMessage 的真实 hook 编排层
     * （intermediate channel、解码、usage 提取、chunk 发射、attempt 语义）
     * 可在 JVM 测试中验证；生产为 null（走真实 [PackageManager]）。
     */
    internal var mainHookRunnerOverride: ToolPkgMainHookRunner? = null

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return runCatching {
            val decoded =
                invokeProviderFunction(
                    functionName = provider.listModelsFunctionName,
                    functionSource = provider.listModelsFunctionSource,
                    event = TOOLPKG_EVENT_AI_PROVIDER_LIST_MODELS,
                    eventPayload = buildBasePayload(context)
                )
            ensureNoFatalError(decoded)
            parseModelOptions(decoded)
        }
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: com.ai.assistance.operit.data.stats.TokenStatCategory?
    ): Stream<String> = com.ai.assistance.operit.util.stream.stream {
        var hasIntermediateTextChunk = false
        val decoded =
            invokeProviderFunction(
                functionName = provider.sendMessageFunctionName,
                functionSource = provider.sendMessageFunctionSource,
                event = TOOLPKG_EVENT_AI_PROVIDER_SEND_MESSAGE,
                eventPayload =
                    buildBasePayload(context).apply {
                        put("chatHistory", JSONArray(chatHistory.map(::serializePromptTurn)))
                        put("modelParameters", JSONArray(modelParameters.map(::serializeModelParameter)))
                        put(
                            "availableTools",
                            availableTools?.let { tools -> JSONArray(tools.map(::serializeToolPrompt)) }
                        )
                        put("enableThinking", enableThinking)
                        put("stream", stream)
                        put("preserveThinkInHistory", preserveThinkInHistory)
                        put("enableRetry", enableRetry)
                    },
                onIntermediateResult = { intermediateDecoded ->
                    applyAndForwardUsage(
                        intermediateDecoded,
                        onTokensUpdated,
                        onUsageReported,
                    )
                    extractNonFatalError(intermediateDecoded)?.let { error ->
                        onNonFatalError(error)
                    }
                    extractMessageChunks(intermediateDecoded).forEach { chunk ->
                        hasIntermediateTextChunk = true
                        emit(chunk)
                    }
                }
            )

        // 最终结果先 apply/forward usage，再检查致命错误：失败结果里的 usage
        // 不能丢（与 intermediate 同一通道、同一 attempt 合并语义）；fatal
        // 抛出后不会执行下方最终 chunk 发射，因此失败结果不产生最终文本。
        applyAndForwardUsage(decoded, onTokensUpdated, onUsageReported)
        ensureNoFatalError(decoded)
        extractNonFatalError(decoded)?.let { error ->
            onNonFatalError(error)
        }
        if (!hasIntermediateTextChunk) {
            extractMessageChunks(decoded).forEach { chunk ->
                emit(chunk)
            }
        }
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?
    ): Result<String> {
        // 评审 P1-7：中间结果 + 最终结果与普通请求走同一 usage 提取/attempt 转发；
        // 取消必须原样传播，不能被 runCatching 吞成 Result.failure
        val decoded =
            try {
                invokeProviderFunction(
                    functionName = provider.testConnectionFunctionName,
                    functionSource = provider.testConnectionFunctionSource,
                    event = TOOLPKG_EVENT_AI_PROVIDER_TEST_CONNECTION,
                    eventPayload = buildBasePayload(context),
                    onIntermediateResult = { intermediateDecoded ->
                        extractUsage(intermediateDecoded)?.let { usage ->
                            forwardUsage(usage, onUsageReported)
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        // 失败结果里的 usage 同样先转发（不丢），再走致命检查
        extractUsage(decoded)?.let { usage ->
            forwardUsage(usage, onUsageReported)
        }
        return runCatching {
            ensureNoFatalError(decoded)
            parseConnectionMessage(decoded)
        }
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Int {
        val decoded =
            invokeProviderFunction(
                functionName = provider.calculateInputTokensFunctionName,
                functionSource = provider.calculateInputTokensFunctionSource,
                event = TOOLPKG_EVENT_AI_PROVIDER_CALCULATE_INPUT_TOKENS,
                eventPayload =
                    buildBasePayload(context = null).apply {
                        put("chatHistory", JSONArray(chatHistory.map(::serializePromptTurn)))
                        put(
                            "availableTools",
                            availableTools?.let { tools -> JSONArray(tools.map(::serializeToolPrompt)) }
                        )
                    }
            )
        ensureNoFatalError(decoded)
        return parseTokenCount(decoded)
    }

    override fun release() {
        cancelStreaming()
    }

    private suspend fun invokeProviderFunction(
        functionName: String,
        functionSource: String?,
        event: String,
        eventPayload: JSONObject,
        onIntermediateResult: (suspend (ProviderHookValue) -> Unit)? = null
    ): ProviderHookValue = coroutineScope {
        val intermediateChannel =
            if (onIntermediateResult == null) {
                null
            } else {
                Channel<Any?>(capacity = Channel.UNLIMITED)
            }
        val intermediateJob =
            intermediateChannel?.let { channel ->
                launch(Dispatchers.IO) {
                    for (raw in channel) {
                        onIntermediateResult?.invoke(
                            decodeProviderHookValue(decodeToolPkgHookResult(raw))
                        )
                    }
                }
            }

        try {
            val result =
                withContext(Dispatchers.IO) {
                    val override = mainHookRunnerOverride
                    if (override != null) {
                        override.run(
                            containerPackageName = provider.containerPackageName,
                            functionName = functionName,
                            event = event,
                            pluginId = "${provider.providerId}:$event",
                            inlineFunctionSource = functionSource,
                            eventPayload =
                                jsonObjectToMap(
                                    JSONObject(eventPayload.toString()).put("chatId", executionChatId)
                                ),
                            executionContextKey = providerRuntimeContextKey,
                            runtimeKind = "provider",
                            onIntermediateResult =
                                intermediateChannel?.let { channel ->
                                    { raw ->
                                        channel.trySend(raw)
                                    }
                                }
                        )
                    } else {
                        val manager = toolPkgPackageManager()
                        manager.runToolPkgMainHook(
                            containerPackageName = provider.containerPackageName,
                            functionName = functionName,
                            event = event,
                            pluginId = "${provider.providerId}:$event",
                            inlineFunctionSource = functionSource,
                            eventPayload =
                                jsonObjectToMap(
                                    JSONObject(eventPayload.toString()).put("chatId", executionChatId)
                                ),
                            executionContextKey = providerRuntimeContextKey,
                            runtimeKind = "provider",
                            dispatchIntermediateOnMain = false,
                            onIntermediateResult =
                                intermediateChannel?.let { channel ->
                                    { raw ->
                                        channel.trySend(raw)
                                    }
                                }
                        )
                    }
                }
            decodeProviderHookValue(
                result.getOrElse { error -> throw error }?.let { raw -> decodeToolPkgHookResult(raw) }
            )
        } finally {
            intermediateChannel?.close()
            intermediateJob?.join()
        }
    }

    private fun buildBasePayload(context: Context?): JSONObject {
        return jsonObjectOf(
            "providerId" to provider.providerId,
            "providerDisplayName" to provider.displayName,
            "providerDescription" to provider.description,
            "config" to serializeModelConfig(context)
        )
    }

    private fun serializeModelConfig(context: Context?): JSONObject {
        return jsonObjectOf(
            "id" to config.id,
            "name" to config.name,
            "apiProviderType" to config.apiProviderTypeId,
            "apiProviderTypeId" to config.apiProviderTypeId,
            "apiKey" to config.apiKey,
            "apiEndpoint" to config.apiEndpoint,
            "modelName" to config.modelName,
            "customHeaders" to decodeJsonObjectString(config.customHeaders),
            "customParameters" to decodeJsonArrayString(config.customParameters),
            "enableDirectImageProcessing" to config.enableDirectImageProcessing,
            "enableDirectAudioProcessing" to config.enableDirectAudioProcessing,
            "enableDirectVideoProcessing" to config.enableDirectVideoProcessing,
            "enableGoogleSearch" to config.enableGoogleSearch,
            "enableClaude1hPromptCache" to config.enableClaude1hPromptCache,
            "enableToolCall" to config.enableToolCall,
            "requestLimitPerMinute" to config.requestLimitPerMinute,
            "maxConcurrentRequests" to config.maxConcurrentRequests,
            "locale" to context?.resources?.configuration?.locales?.get(0)?.toLanguageTag()
        )
    }

    private fun serializePromptTurn(turn: PromptTurn): JSONObject {
        return jsonObjectOf(
            "kind" to turn.kind.name,
            "content" to turn.content,
            "toolName" to turn.toolName,
            "metadata" to turn.metadata
        )
    }

    private fun serializeModelParameter(parameter: ModelParameter<*>): JSONObject {
        return jsonObjectOf(
            "id" to parameter.id,
            "name" to parameter.name,
            "value" to parameter.currentValue,
            "type" to parameter.valueType.name,
            "category" to parameter.category.name,
            "enabled" to parameter.isEnabled,
            "custom" to parameter.isCustom
        )
    }

    private fun serializeToolPrompt(tool: ToolPrompt): JSONObject {
        return jsonObjectOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to tool.parameters,
            "parametersStructured" to
                JSONArray(
                    tool.parametersStructured?.map { schema ->
                        jsonObjectOf(
                            "name" to schema.name,
                            "type" to schema.type,
                            "description" to schema.description,
                            "required" to schema.required,
                            "default" to schema.default
                        )
                    } ?: emptyList<JSONObject>()
                )
        )
    }

    private fun decodeJsonObjectString(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "{}") {
            return JSONObject()
        }
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun decodeJsonArrayString(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return JSONArray()
        }
        return try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun parseModelOptions(decoded: ProviderHookValue): List<ModelOption> {
        val items =
            when (decoded) {
                is ProviderHookValue.ObjectValue ->
                    decodeProviderHookValue(
                        if (decoded.value.has("models")) decoded.value.opt("models") else decoded.value
                    )
                is ProviderHookValue.ArrayValue -> decoded
                else -> decoded
            }
        return when (items) {
            is ProviderHookValue.ArrayValue ->
                (0 until items.value.length()).mapNotNull { index ->
                    parseModelOption(decodeProviderHookValue(items.value.opt(index)))
                }
            else -> listOfNotNull(parseModelOption(items))
        }
    }

    private fun parseModelOption(raw: ProviderHookValue): ModelOption? {
        return when (raw) {
            ProviderHookValue.NullValue -> null
            is ProviderHookValue.TextValue ->
                raw.value.trim().takeIf { it.isNotBlank() }?.let { ModelOption(id = it, name = it) }
            is ProviderHookValue.ObjectValue -> {
                val id =
                    raw.value.optString("id")
                        .ifBlank { raw.value.optString("name") }
                        .ifBlank { raw.value.optString("model") }
                        .trim()
                val name =
                    raw.value.optString("name")
                        .ifBlank { raw.value.optString("displayName") }
                        .ifBlank { raw.value.optString("title") }
                        .ifBlank { id }
                        .trim()
                if (id.isBlank()) null else ModelOption(id = id, name = name)
            }
            else -> null
        }
    }

    private fun parseConnectionMessage(decoded: ProviderHookValue): String {
        return when (decoded) {
            is ProviderHookValue.TextValue -> decoded.value.ifBlank { "Connection successful" }
            is ProviderHookValue.BooleanValue ->
                if (decoded.value) "Connection successful" else error("Connection failed")
            is ProviderHookValue.ObjectValue -> {
                val success =
                    if (decoded.value.has("success")) {
                        decoded.value.optBoolean("success", true)
                    } else {
                        true
                    }
                if (!success) {
                    throw IllegalStateException(
                        decoded.value.optString("error").ifBlank { "Connection failed" }
                    )
                }
                decoded.value.optString("message").ifBlank { "Connection successful" }
            }
            else -> "Connection successful"
        }
    }

    private fun parseTokenCount(decoded: ProviderHookValue): Int {
        return when (decoded) {
            is ProviderHookValue.NumberValue -> decoded.value.toTokenCountInt()
            is ProviderHookValue.TextValue ->
                decoded.value.trim().toBigDecimalOrNull()?.toTokenCountInt()
                    ?: throw IllegalStateException("Invalid token count result: ${decoded.value}")
            is ProviderHookValue.ObjectValue -> {
                decoded.value.optTokenCount("tokens", "inputTokens", "count")
                    ?: throw IllegalStateException("Invalid token count result")
            }
            else -> throw IllegalStateException("Invalid token count result")
        }
    }

    private fun Number.toTokenCountInt(): Int {
        return when (this) {
            is java.math.BigDecimal -> toTokenCountInt()
            is java.math.BigInteger ->
                coerceIn(java.math.BigInteger.ZERO, java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong()))
                    .toLong()
                    .toTokenCountInt()
            else -> toLong().toTokenCountInt()
        }
    }

    private fun java.math.BigDecimal.toTokenCountInt(): Int {
        return coerceIn(java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(Int.MAX_VALUE.toLong()))
            .toLong()
            .toTokenCountInt()
    }

    private fun Long.toTokenCountInt(): Int {
        return coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun JSONObject.optTokenCount(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val parsed = when (val raw = opt(key)) {
                is Number -> raw.toTokenCountInt()
                is String -> raw.trim().toBigDecimalOrNull()?.toTokenCountInt()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * 账本路径的 Long 读取（评审 P2-1）：全程 Long，绝不 Int 截断/回绕；
     * 负值拒绝为未知（null）。
     */
    private fun JSONObject.optTokenCountLong(vararg keys: String): Long? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val parsed = when (val raw = opt(key)) {
                is Number -> raw.toLong()
                is String -> raw.trim().toBigDecimalOrNull()?.toLong()
                else -> null
            }
            if (parsed != null) return parsed.takeIf { it >= 0 }
        }
        return null
    }

    /** 旧 UI 计数边界（评审 P2-1）：Long 饱和为 Int，绝不回绕为负。 */
    private fun Long.saturateToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ensureNoFatalError(decoded: ProviderHookValue) {
        when (decoded) {
            is ProviderHookValue.ObjectValue -> {
                val success =
                    if (decoded.value.has("success")) {
                        decoded.value.optBoolean("success", true)
                    } else {
                        true
                    }
                if (!success) {
                    throw IllegalStateException(
                        decoded.value.optString("error").ifBlank { "ToolPkg AI provider call failed" }
                    )
                }
            }
            else -> Unit
        }
    }

    private fun extractNonFatalError(decoded: ProviderHookValue): String? {
        return when (decoded) {
            is ProviderHookValue.ObjectValue ->
                decoded.value.optString("nonFatalError").trim().ifBlank { null }
            else -> null
        }
    }

    /**
     * 提取 usage。usage 协议（评审 P1-6/P2-1，**不猜测 attempt、不继承全局计数**）：
     * - **新协议**：usage 对象（或顶层）携带 `attempt` / `attemptNumber`
     *   （provider 内部第几次尝试，从 1 开始）。同 attempt 的多次上报是流式
     *   部分更新（省略字段保留旧值）；不同 attempt 分别记账，聚合时累加。
     * - **旧协议**：不携带 attempt 字段。语义为**整个逻辑请求的累计快照**
     *   （跨内部重试累计的最终数字），固定按 attempt 1 完整快照记账（后报覆盖
     *   先报，绝不把多个无 attempt 上报误累加）。内部按 attempt 逐次上报的
     *   插件必须迁移到新协议。
     * - 账本字段可空：缺省字段 = 未知，**绝不**用全局 current 计数填充（避免
     *   跨 attempt 继承造成虚假累计）；负值拒绝为未知。
     */
    internal fun extractUsage(decoded: ProviderHookValue): TokenUsage? {
        return when (decoded) {
            is ProviderHookValue.ObjectValue -> extractUsageFromJson(decoded.value)
            else -> null
        }
    }

    private fun extractUsageFromJson(json: JSONObject): TokenUsage? {
        val usageObject = json.optJSONObject("usage")
        val source = usageObject ?: json
        val input = source.optTokenCountLong("input", "inputTokens")
        val cachedInput = source.optTokenCountLong("cachedInput", "cachedInputTokens")
        val output = source.optTokenCountLong("output", "outputTokens")
        if (input == null && cachedInput == null && output == null) {
            return null
        }
        val attemptPresent = source.has("attempt") || source.has("attemptNumber")
        val attempt =
            source.optTokenCountLong("attempt", "attemptNumber")?.coerceAtLeast(1)?.toInt() ?: 1
        return TokenUsage(
            input = input,
            cachedInput = cachedInput,
            output = output,
            attempt = attempt,
            attemptPresent = attemptPresent,
        )
    }

    /**
     * sendMessage 通道：提取 → 更新 UI 累计计数 → 转发规范化 usage。
     * UI 计数器与账本快照分离（评审 P1-6）：缺省字段只保留 UI 侧全局累计值，
     * 账本快照保持未知（由 [TokenStatRequestContext] 按 attempt 合并）。
     */
    private suspend fun applyAndForwardUsage(
        decoded: ProviderHookValue,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
    ) {
        extractUsage(decoded)?.let { usage ->
            applyUsage(usage)
            onTokensUpdated(
                currentInputTokenCount,
                currentCachedInputTokenCount,
                currentOutputTokenCount
            )
            forwardUsage(usage, onUsageReported)
        }
    }

    /** 只转发规范化 usage（testConnection 等无 UI 计数通道的场景共用）；接收已解析的 usage，避免重复解析。 */
    private suspend fun forwardUsage(
        usage: TokenUsage,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, Int) -> Unit)?,
    ) {
        onUsageReported?.invoke(
            com.ai.assistance.operit.data.stats.ProviderUsageNormalizer.toolPkg(
                input = usage.input,
                cachedInput = usage.cachedInput,
                output = usage.output,
                // 协议语义：attempt 在场 = 同 attempt 部分更新；缺省 = 整个
                // 逻辑请求的累计完整快照
                completeSnapshot = !usage.attemptPresent,
            ),
            usage.attempt
        )
    }

    private fun applyUsage(usage: TokenUsage) {
        usage.input?.let { currentInputTokenCount = it.saturateToInt() }
        usage.cachedInput?.let { currentCachedInputTokenCount = it.saturateToInt() }
        usage.output?.let { currentOutputTokenCount = it.saturateToInt() }
    }

    private fun extractMessageChunks(decoded: ProviderHookValue): List<String> {
        return when (decoded) {
            ProviderHookValue.NullValue -> emptyList()
            is ProviderHookValue.TextValue ->
                if (decoded.value.isEmpty()) emptyList() else listOf(decoded.value)
            is ProviderHookValue.ObjectValue -> {
                val chunks = mutableListOf<String>()
                if (decoded.value.has("chunk") && !decoded.value.isNull("chunk")) {
                    decoded.value.optString("chunk").takeIf { it.isNotEmpty() }?.let(chunks::add)
                }
                decoded.value.optJSONArray("chunks")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotEmpty() }?.let(chunks::add)
                    }
                }
                if (decoded.value.has("text") && !decoded.value.isNull("text")) {
                    decoded.value.optString("text").takeIf { it.isNotEmpty() }?.let(chunks::add)
                } else if (decoded.value.has("content") && !decoded.value.isNull("content")) {
                    decoded.value.optString("content").takeIf { it.isNotEmpty() }?.let(chunks::add)
                }
                chunks
            }
            else -> emptyList()
        }
    }

    private fun decodeProviderHookValue(raw: kotlin.Any?): ProviderHookValue {
        return when (raw) {
            null,
            JSONObject.NULL -> ProviderHookValue.NullValue
            is JSONObject -> ProviderHookValue.ObjectValue(raw)
            is JSONArray -> ProviderHookValue.ArrayValue(raw)
            is String -> ProviderHookValue.TextValue(raw)
            is Boolean -> ProviderHookValue.BooleanValue(raw)
            is Number -> ProviderHookValue.NumberValue(raw)
            is Map<*, *> -> ProviderHookValue.ObjectValue(JSONObject(raw))
            is List<*> -> ProviderHookValue.ArrayValue(JSONArray(raw))
            else -> ProviderHookValue.TextValue(raw.toString())
        }
    }

    private fun jsonObjectOf(vararg entries: Pair<String, *>): JSONObject {
        return JSONObject().apply {
            entries.forEach { (key, value) -> put(key, value) }
        }
    }

    internal data class TokenUsage(
        /** 可空（评审 P1-6）：缺省字段 = 未知，绝不继承全局累计计数。 */
        val input: Long?,
        val cachedInput: Long?,
        val output: Long?,
        val attempt: Int = 1,
        /** 上报是否显式携带 attempt 字段（新协议）；false = 旧协议累计快照。 */
        val attemptPresent: Boolean = false,
    )
}

/**
 * ToolPkg hook 调用抽象（测试缝）：与 [PackageManager.runToolPkgMainHook] 相同的
 * 调用面。生产路径由 [ToolPkgJsAiProviderService.mainHookRunnerOverride] 为 null
 * 时走真实包管理器；测试注入假 runner 驱动真实 hook 编排层。
 */
internal fun interface ToolPkgMainHookRunner {
    suspend fun run(
        containerPackageName: String,
        functionName: String,
        event: String,
        pluginId: String?,
        inlineFunctionSource: String?,
        eventPayload: Map<String, Any?>,
        executionContextKey: String?,
        runtimeKind: String?,
        onIntermediateResult: ((Any?) -> Unit)?,
    ): Result<Any?>
}
