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

        fun parse(raw: String): ModelProtocolCatalog {
            val root = Json.parseToJsonElement(raw).jsonObject
            val providers = root.mapNotNull { (id, value) ->
                val provider = value as? JsonObject ?: return@mapNotNull null
                val endpoint = provider.text("api") ?: return@mapNotNull null
                val models = (provider["models"] as? JsonObject)?.mapNotNull model@{ (modelId, item) ->
                    val model = item as? JsonObject ?: return@model null
                    val override = model["provider"] as? JsonObject
                    val npm = override?.text("npm") ?: provider.text("npm")
                    val field = (model["interleaved"] as? JsonObject)?.text("field")
                    val protocol = when {
                        id == "opencode-go" && modelId in goAnthropicModels -> ModelProtocol.ANTHROPIC
                        npm == "@ai-sdk/anthropic" -> ModelProtocol.ANTHROPIC
                        npm == "@ai-sdk/google" -> ModelProtocol.GEMINI
                        npm == "@ai-sdk/deepseek" -> ModelProtocol.DEEPSEEK
                        npm == "@ai-sdk/openai" -> ModelProtocol.RESPONSES
                        npm == "@ai-sdk/openai-compatible" && field == "reasoning_content" -> ModelProtocol.CHAT_REASONING
                        npm == "@ai-sdk/openai-compatible" && field == null -> ModelProtocol.CHAT_COMPLETIONS
                        else -> return@model null
                    }
                    modelId to ModelProtocolSettings(protocol, override?.text("api").orEmpty())
                }?.toMap().orEmpty()
                if (models.isEmpty()) null else Provider(id, endpoint, models)
            }
            require(providers.isNotEmpty()) { "Model protocol catalog contains no usable providers" }
            return ModelProtocolCatalog(providers)
        }

        private fun JsonObject.text(name: String): String? =
            (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
