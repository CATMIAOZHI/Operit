package com.ai.assistance.operit.api.chat.llmprovider

import java.io.IOException
import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** Adapts the steps protocol to the same rendering, usage and replay pipeline as generateContent. */
internal class GeminiInteractionResponse(
    private val continued: Boolean,
    private val interruptedMessage: String,
    private val unknownMessage: String,
    private val emitFrame: suspend (JSONObject) -> Unit,
) {
    private data class Step(val value: JSONObject, val arguments: StringBuilder = StringBuilder())
    private val openSteps = mutableMapOf<Int, Step>()
    private val searchQueries = linkedSetOf<String>()
    private val citations = linkedMapOf<String, String>()
    val pending = JSONArray()
    var completedId: String? = null
        private set
    private var ended = false
    private var generated = false

    private suspend fun part(value: JSONObject) {
        generated = true
        val content = JSONObject().put("role", "model").put("parts", JSONArray().put(value))
        emitFrame(JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", content))))
    }

    private suspend fun content(value: JSONObject, thought: Boolean) {
        for (annotation in value.optJSONArray("annotations")?.objects().orEmpty()) {
            if (annotation.optString("type") == "url_citation") {
                val url = annotation.optString("url")
                if (url.isNotBlank()) citations[url] = annotation.optString("title")
            }
        }
        when (value.optString("type")) {
            "text" -> part(JSONObject().put("text", value.getString("text")).apply {
                if (thought) put("thought", true)
            })
            "image" -> part(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", value.getString("mime_type")).put("data", value.getString("data"))))
        }
    }

    private fun search(step: JSONObject) {
        val queries = step.optJSONObject("arguments")?.optJSONArray("queries") ?: return
        for (i in 0 until queries.length()) searchQueries.add(queries.getString(i))
    }

    private suspend fun function(step: JSONObject, args: JSONObject) {
        val id = step.getString("id")
        val name = step.getString("name")
        val call = JSONObject().put("id", id).put("name", name).put("args", args)
        pending.put(JSONObject().put("id", id).put("name", name)
            .put("matchingName", StructuredToolCallBridge.toolCallName(call)))
        part(JSONObject().put("functionCall", call))
    }

    private fun fail(error: JSONObject?): Nothing {
        if (interactionCanFallback(0, error, continued)) throw interactionRejection(0, error, continued)
        throw IOException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: unknownMessage)
    }

    private suspend fun complete(interaction: JSONObject) {
        val successful = interaction.optString("status") in setOf("completed", "requires_action", "incomplete")
        val usage = interaction.optJSONObject("usage")
        val generatedTokens = usage?.let { it.optLong("total_output_tokens") > 0 || it.optLong("total_thought_tokens") > 0 } == true
        if (!successful && !generated && !generatedTokens) fail(interaction.optJSONObject("error"))
        if (searchQueries.isNotEmpty()) {
            val chunks = JSONArray()
            citations.forEach { (uri, title) -> chunks.put(JSONObject().put("web", JSONObject().put("uri", uri).put("title", title))) }
            val grounding = JSONObject().put("webSearchQueries", JSONArray(searchQueries)).put("groundingChunks", chunks)
            // Existing renderer attaches sources to a candidate carrying at least one part.
            val content = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "")))
            emitFrame(JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", content).put("groundingMetadata", grounding))))
        }
        if (usage != null) {
            val metadata = JSONObject()
            fun copy(from: String, to: String) {
                usage.optLong(from, -1).takeIf { it >= 0 }?.let { metadata.put(to, it) }
            }
            copy("total_input_tokens", "promptTokenCount")
            copy("total_cached_tokens", "cachedContentTokenCount")
            copy("total_thought_tokens", "thoughtsTokenCount")
            // Interactions reports visible output and reasoning separately. Operit's Gemini
            // normalizer expects billable output inclusive of reasoning.
            usage.optLong("total_output_tokens", -1).takeIf { it >= 0 }?.let {
                val thoughts = usage.optLong("total_thought_tokens", 0).coerceAtLeast(0)
                metadata.put("candidatesTokenCount", it.coerceAtMost(Long.MAX_VALUE - thoughts) + thoughts)
            }
            emitFrame(JSONObject().put("usageMetadata", metadata))
        }
        when (interaction.optString("status")) {
            "completed", "requires_action" -> completedId = interaction.getString("id")
            "incomplete" -> Unit // Preserve a length-limited reply without anchoring an incomplete call.
            else -> fail(interaction.optJSONObject("error"))
        }
        ended = true
    }

    /** Stream the top-level response and discard echoed historical steps at each input boundary.
     * In particular, do not hold a second full string of the body alongside its Base64 inputs. */
    private suspend fun readJson(reader: Reader) {
        val metadata = JSONObject()
        val outputs = mutableListOf<JSONObject>()
        val json = JsonReader(reader)
        json.beginObject()
        while (json.hasNext()) {
            when (val field = json.nextName()) {
                "steps" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        val step = readValue(json) as JSONObject
                        when (step.optString("type")) {
                            "user_input", "function_result" -> outputs.clear()
                            "model_output", "thought", "function_call", "google_search_call" -> outputs.add(step)
                        }
                    }
                    json.endArray()
                }
                "outputs", "candidates" -> throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
                "status", "error", "id", "usage" -> metadata.put(field, readValue(json))
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (!metadata.has("status")) metadata.optJSONObject("error")?.let { fail(it) }
        for (step in outputs) {
            when (step.getString("type")) {
                "model_output" -> step.getJSONArray("content").objects().forEach { content(it, false) }
                "thought" -> step.optJSONArray("summary")?.objects()?.forEach { content(it, true) }
                "function_call" -> function(step, step.getJSONObject("arguments"))
                "google_search_call" -> search(step)
            }
        }
        complete(metadata)
    }

    private fun readValue(json: JsonReader): Any = when (json.peek()) {
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            json.beginArray()
            while (json.hasNext()) put(readValue(json))
            json.endArray()
        }
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            json.beginObject()
            while (json.hasNext()) put(json.nextName(), readValue(json))
            json.endObject()
        }
        JsonToken.STRING -> json.nextString()
        JsonToken.NUMBER -> json.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> json.nextBoolean()
        JsonToken.NULL -> { json.nextNull(); JSONObject.NULL }
        else -> throw IOException(unknownMessage)
    }

    suspend fun read(reader: Reader, stream: Boolean) {
        if (!stream) {
            readJson(reader)
            return
        }
        val lines = reader.buffered()
        var recognized = false
        while (!ended) {
            val line = lines.readLine() ?: break
            if (line.trimStart().startsWith("{") && !recognized) {
                val error = runCatching { JSONObject(line).optJSONObject("error") }.getOrNull()
                if (error != null) fail(error)
                throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
            }
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val event = JSONObject(data)
            val index = event.optInt("index", -1)
            val type = event.optString("event_type")
            if (event.has("candidates") || type.startsWith("content.") || event.has("outputs")) {
                throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
            }
            when (type) {
                "error" -> fail(event.optJSONObject("error"))
                "interaction.created", "interaction.status_update" -> recognized = true
                "step.start" -> {
                    recognized = true
                    val step = event.getJSONObject("step")
                    openSteps[index] = Step(step)
                    when (step.optString("type")) {
                        "model_output" -> step.optJSONArray("content")?.objects()?.forEach { content(it, false) }
                        "thought" -> step.optJSONArray("summary")?.objects()?.forEach { content(it, true) }
                    }
                }
                "step.delta" -> {
                    val delta = event.getJSONObject("delta")
                    val step = openSteps[index] ?: throw IOException(interruptedMessage)
                    when (step.value.getString("type")) {
                        "model_output" -> content(delta, false)
                        "thought" -> if (delta.optString("type") == "thought_summary") content(delta.getJSONObject("content"), true)
                        "function_call" -> if (delta.optString("type") == "arguments_delta") step.arguments.append(delta.getString("arguments"))
                        "google_search_call" -> search(delta)
                    }
                }
                "step.stop" -> {
                    val step = openSteps.remove(index) ?: throw IOException(interruptedMessage)
                    if (step.value.optString("type") == "function_call") {
                        val args = step.value.optJSONObject("arguments") ?: JSONObject()
                        if (step.arguments.isNotEmpty()) {
                            val delta = JSONObject(step.arguments.toString())
                            for (key in delta.keys()) args.put(key, delta.get(key))
                        }
                        function(step.value, args)
                    }
                    if (step.value.optString("type") == "google_search_call") search(step.value)
                }
                "interaction.completed" -> {
                    if (openSteps.isNotEmpty()) throw IOException(interruptedMessage)
                    complete(event.getJSONObject("interaction"))
                }
            }
        }
        if (!ended && !recognized) throw InteractionUnavailable(InteractionUnavailable.Cache.SESSION)
        if (!ended) throw IOException(interruptedMessage)
    }
}
