package com.ai.assistance.operit.data.model

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ModelProtocolCatalog private constructor(private val providers: List<Provider>) {
    private data class Provider(val id: String, val endpoint: String, val models: Map<String, ModelProtocolSettings>)

    fun matchAll(endpoint: String, models: List<String>): Map<String, ModelProtocolSettings> {
        val url = endpoint.trim().removeSuffix("#").toHttpUrlOrNull() ?: return emptyMap()
        // Match the account URL, not the original model author: a gateway can expose a different API.
        val candidates = providers.mapNotNull { provider ->
            val base = provider.endpoint.toHttpUrlOrNull() ?: return@mapNotNull null
            val path = base.encodedPath.trimEnd('/')
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                !(url.encodedPath.trimEnd('/') == path || url.encodedPath.startsWith("$path/"))
            ) return@mapNotNull null
            path.length to provider
        }
        val longestPath = candidates.maxOfOrNull { it.first } ?: return emptyMap()
        val matches = candidates.filter { it.first == longestPath }.map { it.second }
        return models.mapNotNull { model ->
            val settings = matches.mapNotNull { it.models[model.trim()] }.distinct()
            settings.singleOrNull()?.let { model to it }
        }.toMap()
    }

    companion object {
        // Go's published endpoint table differs from models.dev's inherited SDK for these models.
        // Source: https://opencode.ai/docs/zh-cn/go/#api-端点 (2026-09-07).
        private val goAnthropicModels = setOf(
            "qwen3.6-plus", "qwen3.7-plus", "qwen3.7-max", "qwen3.8-max", "qwen3.8-flash",
        )

        /**
         * models.dev lists no address for providers whose SDK has one built in. Accounts on these
         * official addresses keep the protocol their provider type already speaks; the catalog only
         * adds the model's thinking levels. Keep in step with
         * `tools/model_catalog/generate_model_protocols_catalog.py`.
         */
        internal val OFFICIAL_ENDPOINTS = mapOf(
            "openai" to "https://api.openai.com/v1",
            "anthropic" to "https://api.anthropic.com/v1",
            "google" to "https://generativelanguage.googleapis.com",
            "xai" to "https://api.x.ai/v1",
        )

        fun parse(raw: String): ModelProtocolCatalog {
            val root = Json.parseToJsonElement(raw).jsonObject
            val providers = root.mapNotNull { (id, value) ->
                val provider = value as? JsonObject ?: return@mapNotNull null
                val listedEndpoint = provider.text("api")
                val endpoint = listedEndpoint ?: OFFICIAL_ENDPOINTS[id] ?: return@mapNotNull null
                val official = listedEndpoint == null
                val models = (provider["models"] as? JsonObject)?.mapNotNull model@{ (modelId, item) ->
                    val model = item as? JsonObject ?: return@model null
                    val override = model["provider"] as? JsonObject
                    val npm = override?.text("npm") ?: provider.text("npm")
                    val field = (model["interleaved"] as? JsonObject)?.text("field")
                    val protocol = when {
                        official -> ModelProtocol.INHERIT
                        id == "opencode-go" && modelId in goAnthropicModels -> ModelProtocol.ANTHROPIC
                        npm == "@ai-sdk/anthropic" -> ModelProtocol.ANTHROPIC
                        npm == "@ai-sdk/google" -> ModelProtocol.GEMINI
                        npm == "@ai-sdk/deepseek" -> ModelProtocol.DEEPSEEK
                        npm == "@ai-sdk/openai" -> ModelProtocol.RESPONSES
                        npm == "@ai-sdk/openai-compatible" && field == "reasoning_content" -> ModelProtocol.CHAT_REASONING
                        npm == "@ai-sdk/openai-compatible" && field == null -> ModelProtocol.CHAT_COMPLETIONS
                        // OpenRouter speaks Chat Completions under its own SDK name.
                        npm == "@openrouter/ai-sdk-provider" -> ModelProtocol.INHERIT
                        else -> return@model null
                    }
                    val reasoningEfforts = (model["reasoning_options"] as? JsonArray)?.flatMap { option ->
                        val objectOption = option as? JsonObject
                        if (objectOption?.text("type") == "effort") {
                            (objectOption["values"] as? JsonArray)?.mapNotNull {
                                (it as? JsonPrimitive)?.contentOrNull
                            }.orEmpty()
                        } else emptyList()
                    }
                    val modelEndpoint = if (protocol == ModelProtocol.INHERIT) "" else override?.text("api").orEmpty()
                    modelId to ModelProtocolSettings(protocol, modelEndpoint, reasoningEfforts)
                }?.toMap().orEmpty()
                if (models.isEmpty()) null else Provider(id, endpoint, models)
            }.flatMap { provider ->
                // Go moved to a new address; accounts saved with the old one still match its models
                // and keep sending to the address they were saved with.
                if (provider.id == "opencode-go" && provider.endpoint.trimEnd('/') != GO_LEGACY_ENDPOINT) {
                    listOf(provider, provider.copy(
                        endpoint = GO_LEGACY_ENDPOINT,
                        models = provider.models.mapValues { (_, settings) ->
                            if ("/inference/go/" in settings.endpoint) settings.copy(endpoint = "") else settings
                        },
                    ))
                } else listOf(provider)
            }
            require(providers.isNotEmpty()) { "Model protocol catalog contains no usable providers" }
            return ModelProtocolCatalog(providers)
        }

        private const val GO_LEGACY_ENDPOINT = "https://opencode.ai/zen/go/v1"

        /**
         * models.dev's full api.json cut down to what [parse] and the Codex model list read, in
         * the format of the bundled copy (`tools/model_catalog/generate_model_protocols_catalog.py`
         * writes the same). The full file is several MB; the cut one is a tenth of that.
         */
        fun slim(raw: String): String = slim(java.io.StringReader(raw))

        /**
         * [slim] read from a stream one provider at a time, so a download is never held as one
         * whole tree.
         */
        fun slim(reader: java.io.Reader): String {
            val json = com.google.gson.stream.JsonReader(reader)
            val slimmed = buildJsonObject {
                json.beginObject()
                while (json.hasNext()) {
                    val id = json.nextName()
                    val provider = Json.parseToJsonElement(
                        com.google.gson.JsonParser.parseReader(json).toString()
                    ) as? JsonObject ?: continue
                    slimProvider(id, provider)?.let { put(id, it) }
                }
                json.endObject()
            }
            return slimmed.toString()
        }

        private fun slimProvider(id: String, provider: JsonObject): JsonObject? {
            val api = provider.text("api")
            if (api == null && id !in OFFICIAL_ENDPOINTS) return null
            val models = (provider["models"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: return null
            return buildJsonObject {
                api?.let { put("api", it) }
                provider.text("npm")?.let { put("npm", it) }
                putJsonObject("models") {
                    models.forEach model@{ (modelId, item) ->
                        val model = item as? JsonObject ?: return@model
                        put(modelId, slimModel(id, model))
                    }
                }
            }
        }

        private fun slimModel(providerId: String, model: JsonObject): JsonObject = buildJsonObject {
            // The Codex provider lists OpenAI's models by name when no account is signed in.
            if (providerId == "openai") model.text("name")?.let { put("name", it) }
            model["interleaved"]?.let { put("interleaved", it) }
            (model["reasoning_options"] as? JsonArray)?.let { put("reasoning_options", it) }
            (model["provider"] as? JsonObject)?.let { override ->
                val kept = listOf("npm", "api", "shape").mapNotNull { key -> override.text(key)?.let { key to it } }
                if (kept.isNotEmpty()) putJsonObject("provider") { kept.forEach { (key, text) -> put(key, text) } }
            }
            val modes = ((model["experimental"] as? JsonObject)?.get("modes") as? JsonObject)
            if (providerId == "openai" && modes != null && modes.isNotEmpty()) {
                putJsonObject("experimental") {
                    putJsonObject("modes") {
                        modes.forEach { (mode, config) ->
                            val reasoning = (((config as? JsonObject)?.get("provider") as? JsonObject)
                                ?.get("body") as? JsonObject)?.get("reasoning") as? JsonObject
                            putJsonObject(mode) {
                                if (reasoning != null) {
                                    putJsonObject("provider") { putJsonObject("body") { put("reasoning", reasoning) } }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun JsonObject.text(name: String): String? =
            (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
