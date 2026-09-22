package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ModelMultimodalCapabilities
import com.ai.assistance.operit.data.model.ModelProtocol
import com.ai.assistance.operit.data.model.OfficialModelCapabilitiesCatalog
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

internal object OpenCodeZenFree {
    const val CHAT_ENDPOINT = "https://opencode.ai/zen/v1/chat/completions"
    const val RESPONSES_ENDPOINT = "https://opencode.ai/zen/v1/responses"
    const val MODELS_ENDPOINT = "https://opencode.ai/zen/v1/models"
    const val DEFAULT_MODEL = "mimo-v2.5-free"

    fun isResponsesModel(id: String): Boolean = id.startsWith("muse-spark", ignoreCase = true)

    fun usesResponses(modelId: String, declaredProtocol: ModelProtocol): Boolean =
        when (declaredProtocol) {
            ModelProtocol.RESPONSES -> true
            ModelProtocol.CHAT_COMPLETIONS, ModelProtocol.CHAT_REASONING -> false
            else -> isResponsesModel(modelId)
        }

    fun isFreeModel(id: String): Boolean =
        id == "big-pickle" || id.endsWith("-free")

    fun matchMultimodalCapabilities(
        catalog: OfficialModelCapabilitiesCatalog,
        models: List<String>,
    ): Map<String, ModelMultimodalCapabilities> {
        val matched = catalog.matchAll(models)
        // An older cached catalog can predate Muse 1.3. Meta lists these inputs for 1.3.
        val muse13 = ModelMultimodalCapabilities(image = true, audio = true, video = true)
        return matched + models.filter {
            it !in matched && it.equals("muse-spark-1.3-contributor-free", ignoreCase = true)
        }.associateWith { muse13 }
    }

    fun enforceFreeModel(request: JSONObject, modelName: String) {
        require(isFreeModel(modelName))
        request.put("model", modelName)
    }

    fun ensureAnonymousRequestShape(request: JSONObject) {
        request.put("stream", true)
        val responses = request.has("input")
        val existing = request.optJSONArray("tools")
        val hadNoTools = existing == null || existing.length() == 0
        val tools = existing ?: JSONArray()
        val names = (0 until tools.length()).mapNotNull {
            val tool = tools.optJSONObject(it)
            if (responses) tool?.optString("name") else tool?.optJSONObject("function")?.optString("name")
        }.toSet()
        for (name in listOf("bash", "read")) {
            if (name !in names) {
                val function = if (responses) {
                    JSONObject()
                        .put("type", "function")
                        .put("name", name)
                        .put("description", "Reserved for the host runtime; do not call it.")
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
                } else {
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("description", "Reserved for the host runtime; do not call it.")
                            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
                    )
                }
                tools.put(function)
            }
        }
        request.put("tools", tools)
        if (responses) {
            if (request.optJSONObject("reasoning")?.optString("effort") == "none") request.remove("reasoning")
            if (hadNoTools && request.optString("tool_choice") == "none") request.remove("tool_choice")
        } else if (hadNoTools) {
            request.put("tool_choice", "none")
        }
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun stableHex(prefix: String, value: String): String =
        sha256("$prefix\u0000$value").take(12).joinToString("") { "%02x".format(it) }

    fun canonicalSessionId(signal: String): String {
        if (Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(signal)) return signal
        val digest = sha256("ses\u0000$signal")
        val time = digest.take(6).joinToString("") { "%02x".format(it) }
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        var number = BigInteger(1, digest.copyOfRange(6, 16))
        val suffix = CharArray(14)
        for (index in suffix.indices.reversed()) {
            val parts = number.divideAndRemainder(BigInteger.valueOf(62))
            suffix[index] = alphabet[parts[1].toInt()]
            number = parts[0]
        }
        return "ses_$time${String(suffix)}"
    }

    fun projectId(): String = "prj_" + stableHex("prj", "operit:default-project")
    fun requestId(signal: String): String = "req_" + sha256("req\u0000$signal").take(16)
        .joinToString("") { "%02x".format(it) }
}

/**
 * The anonymous Zen tier expects an OpenCode-shaped request. A stable session is reused for
 * retries and tool continuations; a supplied Zen key uses the same request identity.
 */
internal class OpenCodeZenFreeHeaders {
    private val fallbackSessionId = UUID.randomUUID().toString()

    suspend fun applyTo(builder: Request.Builder, requestId: String) {
        val session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSessionId
        val canonicalSession = OpenCodeZenFree.canonicalSessionId(session)
        builder.header("x-opencode-session", canonicalSession)
        builder.header("x-session-affinity", canonicalSession)
        builder.header("X-Session-Id", canonicalSession)
        builder.header("x-opencode-request", OpenCodeZenFree.requestId(requestId))
        builder.header("x-opencode-project", OpenCodeZenFree.projectId())
        builder.header("x-opencode-client", "cli")
        builder.header("User-Agent", "opencode/1.18.32")
    }
}
