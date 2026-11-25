package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatMarkupRegex
import java.io.IOException
import java.util.Base64
import java.security.MessageDigest
import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class InteractionUnavailable(val cache: Cache = Cache.NONE) : IOException() {
    enum class Cache { NONE, MODEL, SESSION }
}

internal fun officialInteractionsEndpoint(endpoint: String): Boolean {
    val url = endpoint.trim().removeSuffix("#").toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host == "generativelanguage.googleapis.com" &&
        url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
        url.encodedPath.trimEnd('/') in setOf("", "/v1", "/v1beta", "/v1/models", "/v1beta/models")
}

internal fun interactionHttpError(body: String): JSONObject? {
    fun parse(json: String) = runCatching { JSONObject(json).optJSONObject("error") }.getOrNull()
    return parse(body) ?: body.lineSequence().filter { it.startsWith("data:") }
        .mapNotNull { parse(it.substringAfter(':').trim()) }.firstOrNull()
}

internal fun interactionCanFallback(status: Int, error: JSONObject?, continued: Boolean): Boolean {
    val code = error?.optString("code").orEmpty().lowercase()
    val message = error?.optString("message").orEmpty().lowercase()
    if (status in setOf(401, 403, 429) || status >= 500 && status != 501) return false
    if (!continued && (status == 400 || code in setOf("invalid_request", "invalid_argument"))) return true
    if (status in setOf(404, 405, 410, 501) || code in setOf("not_found", "unimplemented", "not_implemented")) return true
    val missing = listOf("expired", "not found", "invalid", "unknown", "does not exist", "not available")
    val referencesParent = "previous_interaction" in message || "previous interaction" in message ||
        "interaction id" in message || "interaction not found" in message || "interaction expired" in message
    if (continued && referencesParent &&
        missing.any { it in message }) return true
    if (status != 400 && code !in setOf("invalid_request", "invalid_argument")) return false
    if (listOf("not supported", "unsupported", "does not support", "not available", "not enabled").any { it in message }) return true
    val unknownField = listOf("unknown name", "unknown field", "unrecognized field", "unrecognized request argument").any { it in message }
    return unknownField && listOf("previous_interaction_id", "store", "generation_config", "temperature",
        "thinking_level", "thinking_summaries", "system_instruction", "parametersjsonschema", "tools[").any { it in message }
}

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

internal fun interactionHash(value: String): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

internal fun interactionRejection(status: Int, error: JSONObject?, continued: Boolean): InteractionUnavailable {
    val message = error?.optString("message").orEmpty().lowercase()
    val code = error?.optString("code").orEmpty().lowercase()
    val scope = when {
        status in setOf(405, 501) || code in setOf("not_implemented", "unimplemented") ||
            !continued && (status == 404 || code == "not_found" ||
            ("model" in message && ("not supported" in message || "does not support" in message))) -> InteractionUnavailable.Cache.MODEL
        continued -> InteractionUnavailable.Cache.NONE // Losing a single server record does not disable the model.
        else -> InteractionUnavailable.Cache.SESSION
    }
    return InteractionUnavailable(scope)
}

/** Availability probes and digest reuse are bounded. A stale negative only delays a fresh probe. */
internal object InteractionAvailability {
    private const val TTL = 60 * 60_000L
    private val failed = object : LinkedHashMap<String, Long>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }
    @Synchronized fun block(key: String, now: Long = System.currentTimeMillis()) { failed[key] = now + TTL }
    @Synchronized fun blocked(key: String, now: Long = System.currentTimeMillis()): Boolean =
        (failed[key] ?: 0L) > now
}

/** Private checkpoints are independent of message text and of the lifetime of a provider instance.
 * A history edit, process restart or concurrent fork is safe: only an exact local prefix can use it. */
internal object InteractionCheckpoints {
    private const val MAX_ENTRIES = 128
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("gemini_interaction_checkpoints", Context.MODE_PRIVATE)
    fun load(context: Context, key: String): JSONObject? = prefs(context).getString(key, null)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    @Synchronized fun save(context: Context, key: String, entry: JSONObject) {
        val store = prefs(context)
        val edit = store.edit()
        if (!store.contains(key)) {
            val entries = store.all
            if (entries.size >= MAX_ENTRIES) {
                entries.minByOrNull { runCatching { JSONObject(it.value.toString()).optLong("saved") }.getOrDefault(0L) }
                    ?.let { edit.remove(it.key) }
            }
        }
        edit.putString(key, entry.put("saved", System.currentTimeMillis()).toString()).apply()
    }
    @Synchronized fun forget(context: Context, key: String, id: String) {
        if (load(context, key)?.optString("id") == id) prefs(context).edit().remove(key).apply()
    }
}

internal class InteractionHistory(
    private val history: List<PromptTurn>,
    private val scope: String,
) {
    data class Parent(val id: String, val pending: List<JSONObject>, val suffix: List<PromptTurn>)

    companion object {
        /** Each request reloads the chat as new strings, so a memo matches on content, not identity.
         * A 64-bit FNV-1a hash stays cheap, unlike String.hashCode where "Aa" and "BB" collide. */
        private data class Memo(val kind: PromptTurnKind, val length: Int, val hash: Long, val digest: String) {
            fun matches(turn: PromptTurn) = kind == turn.kind && length == turn.content.length && hash == fnv(turn.content)
        }
        private fun fnv(text: String): Long {
            var hash = -3750763034362895579L
            for (char in text) hash = (hash xor char.code.toLong()) * 1099511628211L
            return hash
        }
        private fun memo(turn: PromptTurn, digest: String) = Memo(turn.kind, turn.content.length, fnv(turn.content), digest)
        private val recent = object : LinkedHashMap<String, List<Memo>>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Memo>>?) = size > 12
        }
        private val transcriptTag = Regex(
            """</?(?:think(?:ing)?|search|meta|status|${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})(?=[\s/>])[^<>]*>""",
            RegexOption.IGNORE_CASE,
        )

        /** ConversationService splits display/tool blocks when reopening a saved chat and adds
         * line breaks between them. Ignore those separators while keeping the actual block data. */
        private fun transcript(text: String): String {
            val first = transcriptTag.find(text) ?: return text.trim()
            return buildString(text.length) {
                var from = 0
                var match: MatchResult? = first
                while (match != null) {
                    val start = match.range.first
                    val closing = text[start + 1] == '/'
                    var end = start
                    if (!closing) while (end > from && text[end - 1].isWhitespace()) end--
                    append(text, from, end)
                    from = match.range.last + 1
                    append(text, start, from)
                    if (closing || text[from - 2] == '/') {
                        while (from < text.length && text[from].isWhitespace()) from++
                    }
                    match = match.next()
                }
                append(text, from, text.length)
            }.trim()
        }
        private fun extend(prefix: String, turn: PromptTurn): String {
            val text = if (turn.kind == PromptTurnKind.ASSISTANT) transcript(turn.content) else turn.content.trim()
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(prefix.toByteArray())
            hash.update(turn.kind.ordinal.toByte())
            hash.update(text.toByteArray())
            return Base64.getEncoder().encodeToString(hash.digest())
        }
    }

    private val turns = history.filter { it.kind != PromptTurnKind.SYSTEM }
    private val digests = run {
        val prior = synchronized(recent) { recent[scope] }.orEmpty()
        var matched = true
        var previous = ""
        turns.mapIndexed { index, turn ->
            val memo = prior.getOrNull(index)
            matched = matched && memo?.matches(turn) == true
            val entry = if (matched) memo!! else memo(turn, extend(previous, turn))
            previous = entry.digest
            entry
        }.also { synchronized(recent) { recent[scope] = it } }
    }

    fun checkpoint(reply: String, id: String, pending: JSONArray): JSONObject {
        val output = PromptTurn(PromptTurnKind.ASSISTANT, reply)
        val last = memo(output, extend(digests.lastOrNull()?.digest.orEmpty(), output))
        synchronized(recent) { recent[scope] = digests + last }
        return JSONObject().put("id", id).put("pending", pending).put("count", turns.size + 1)
            .put("digest", last.digest).put("scope", scope)
    }

    fun parent(entry: JSONObject?): Parent? {
        if (entry == null || entry.optString("scope") != scope) return null
        val count = entry.optInt("count")
        if (count !in 1 until turns.size || turns[count - 1].kind != PromptTurnKind.ASSISTANT ||
            digests[count - 1].digest != entry.optString("digest") ||
            turns.subList(count, turns.size).any { it.kind == PromptTurnKind.ASSISTANT }) return null
        return Parent(entry.getString("id"), entry.getJSONArray("pending").objects(),
            history.filter { it.kind == PromptTurnKind.SYSTEM } + turns.drop(count))
    }
}

private fun interactionContent(part: JSONObject): JSONObject {
    if (part.has("text")) return JSONObject().put("type", "text").put("text", part.getString("text"))
    // Such a part stays in this chat's history, so the whole chat keeps the original protocol.
    val media = part.optJSONObject("inline_data") ?: part.optJSONObject("inlineData")
        ?: throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
    val mime = media.optString("mimeType", media.optString("mime_type"))
    val type = when (val category = mime.substringBefore('/')) {
        "audio", "video", "image" -> category
        "application" -> "document"
        else -> throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
    }
    return JSONObject().put("type", type).put("mime_type", mime).put("data", media.getString("data"))
}

internal fun addInteractionInput(body: JSONObject, source: JSONObject, parent: String?) {
    val input = JSONArray()
    for (turn in source.getJSONArray("contents").objects()) {
        val isUser = turn.getString("role") == "user"
        var content = JSONArray()
        fun flush() {
            if (content.length() == 0) return
            input.put(JSONObject().put("type", if (isUser) "user_input" else "model_output").put("content", content))
            content = JSONArray()
        }
        for (part in turn.getJSONArray("parts").objects()) {
            val call = part.optJSONObject("functionCall")
            val result = part.optJSONObject("functionResponse")
            when {
                parent == null && call != null -> {
                    // A finished tool round from generateContent has signatures and call IDs in
                    // another protocol. Keep it as a past-tense note, worded unlike a call the
                    // model could copy instead of making a real function call.
                    content.put(JSONObject().put("type", "text").put("text",
                        "[Earlier step, already done: used ${call.getString("name")} with ${call.optJSONObject("args") ?: JSONObject()}]"))
                }
                parent == null && result != null -> {
                    content.put(JSONObject().put("type", "text").put("text",
                        "[What ${result.getString("name")} returned in that earlier step]\n${result.getJSONObject("response")}"))
                    result.optJSONArray("parts")?.objects()?.forEach { content.put(interactionContent(it)) }
                }
                parent == null && part.optBoolean("thought") -> Unit
                parent == null && part.has("text") && part.optString("text").isEmpty() &&
                    (part.has("thoughtSignature") || part.has("thought_signature")) -> Unit
                call != null -> {
                    flush()
                    // A generateContent call without an Interactions ID or with native replay
                    // signatures needs the original protocol for a faithful full-history replay.
                    if (call.optString("id").isBlank() || part.has("thoughtSignature") || part.has("thought_signature")) {
                        throw InteractionUnavailable()
                    }
                    input.put(JSONObject().put("type", "function_call").put("id", call.getString("id"))
                        .put("name", call.getString("name")).put("arguments", call.getJSONObject("args")))
                }
                result != null -> {
                    flush()
                    if (result.optString("id").isBlank()) throw InteractionUnavailable()
                    val payload = result.getJSONObject("response")
                    val text = if (payload.length() == 1 && payload.has("result")) payload.get("result").toString() else payload.toString()
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    result.optJSONArray("parts")?.objects()?.forEach { parts.put(interactionContent(it)) }
                    input.put(JSONObject().put("type", "function_result").put("call_id", result.getString("id"))
                        .put("name", result.getString("name")).put("result", parts))
                }
                !part.has("text") && !part.has("inlineData") && !part.has("inline_data") &&
                    (part.has("thoughtSignature") || part.has("thought_signature")) -> Unit
                else -> content.put(interactionContent(part))
            }
        }
        flush()
    }
    body.put("input", input)
    parent?.let { body.put("previous_interaction_id", it) }
    source.optJSONObject("systemInstruction")?.let {
        body.put("system_instruction", it.getJSONArray("parts").objects().joinToString("\n\n") { part -> part.getString("text") })
    }
}

internal fun interactionOptions(source: JSONObject, model: String, stream: Boolean, store: Boolean): JSONObject {
    if (source.keys().asSequence().any { it !in setOf("tools", "contents", "systemInstruction", "generationConfig", "toolConfig") }) {
        throw InteractionUnavailable()
    }
    if (source.has("toolConfig")) {
        val config = source.optJSONObject("toolConfig") ?: throw InteractionUnavailable()
        if (config.length() != 1 || !config.optBoolean("includeServerSideToolInvocations")) throw InteractionUnavailable()
    }
    val body = JSONObject().put("model", model.trim().removePrefix("models/")).put("store", store).put("stream", stream)
    val tools = JSONArray()
    for (group in source.optJSONArray("tools")?.objects().orEmpty()) {
        if (group.has("googleSearch")) {
            tools.put(JSONObject().put("type", "google_search"))
            continue
        }
        val functions = group.optJSONArray("function_declarations") ?: throw InteractionUnavailable()
        for (function in functions.objects()) {
            if (function.keys().asSequence().any { it !in setOf("name", "description", "parameters") }) throw InteractionUnavailable()
            tools.put(JSONObject().put("type", "function").put("name", function.getString("name"))
                .put("description", function.opt("description")).put("parameters", function.opt("parameters")))
        }
    }
    if (tools.length() > 0) body.put("tools", tools)
    val config = JSONObject()
    val sourceConfig = source.getJSONObject("generationConfig")
    for (name in sourceConfig.keys()) {
        when (name) {
            "thinkingConfig" -> {
                val thinking = sourceConfig.getJSONObject(name)
                if (thinking.keys().asSequence().any { it !in setOf("includeThoughts", "thinkingLevel") }) throw InteractionUnavailable()
                if (thinking.has("thinkingLevel")) config.put("thinking_level", thinking.getString("thinkingLevel").lowercase())
                if (thinking.has("includeThoughts")) config.put("thinking_summaries", if (thinking.getBoolean("includeThoughts")) "auto" else "none")
            }
            "maxOutputTokens" -> config.put("max_output_tokens", sourceConfig.get(name))
            "stopSequences", "stop_sequences" -> config.put("stop_sequences", sourceConfig.get(name))
            "temperature", "seed" -> config.put(name, sourceConfig.get(name))
            "candidateCount" -> if (sourceConfig.optInt(name) != 1) throw InteractionUnavailable()
            else -> throw InteractionUnavailable()
        }
    }
    if (config.length() > 0) body.put("generation_config", config)
    return body
}
