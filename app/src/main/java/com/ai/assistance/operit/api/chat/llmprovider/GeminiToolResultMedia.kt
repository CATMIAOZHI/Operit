package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ImagePoolManager
import org.json.JSONArray
import org.json.JSONObject

private val geminiGeneration = Regex("""^gemini-(\d+)(?:[.-]|$)""")
private val nestedImageTypes = setOf("image/jpeg", "image/png", "image/webp")

/** Keep all new tool audio available for this answer. Replay at most two tool clips including
 * those new clips, keeping read_file's file path and the surrounding result text for earlier ones,
 * so the model can read them again. Audio the user sent has no such path and is always replayed. */
internal fun limitGeminiAudioReplay(history: List<PromptTurn>): List<PromptTurn> {
    var remaining = 2
    var current = true
    var revised: MutableList<PromptTurn>? = null
    for (index in history.indices.reversed()) {
        val turn = history[index]
        when (turn.kind) {
            PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL -> current = false
            PromptTurnKind.TOOL_RESULT -> {
                val ranges = MediaLinkParser.audioLinkRanges(turn.content)
                val omit = if (current) 0 else (ranges.size - remaining).coerceAtLeast(0)
                remaining = (remaining - ranges.size).coerceAtLeast(0)
                if (omit == 0) continue
                val content = buildString {
                    var start = 0
                    for (i in 0 until omit) {
                        val range = ranges[i]
                        append(turn.content, start, range.first)
                        append("[Earlier audio attachment omitted from replay]")
                        start = range.last + 1
                    }
                    append(turn.content, start, turn.content.length)
                }
                if (revised == null) revised = history.toMutableList()
                revised[index] = turn.copy(content = content)
            }
            else -> Unit
        }
    }
    return revised ?: history
}

/** Collect ordinary inputs separately so parallel function responses stay in their own turn. */
internal fun attachGeminiToolResultMedia(
    response: JSONObject,
    followingParts: JSONArray,
    modelName: String,
    supportsVision: Boolean,
    supportsAudio: Boolean,
    forInteractions: Boolean,
    imageById: (String) -> ImagePoolManager.ImageData? = ImagePoolManager::getImage,
    mediaByTag: (MediaLinkTag) -> MediaLink? = MediaLinkParser::resolveMediaLink,
) {
    val result = response.getJSONObject("response").getString("result")
    if ((!supportsVision && !supportsAudio) || !result.contains("<link", ignoreCase = true)) return
    val generation = geminiGeneration.find(normalizedGeminiModelName(modelName))
        ?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun attach(id: String, mimeType: String, data: String) {
        // GenerateContent documents only JPEG/PNG/WebP as nested image inputs and no nested
        // audio. Interactions also supports audio in a Gemini 3+ function_result.
        if (generation >= 3 && (mimeType in nestedImageTypes || forInteractions)) {
            val parts = response.optJSONArray("parts") ?: JSONArray().also { response.put("parts", it) }
            val blob = JSONObject().put("mimeType", mimeType).put("data", data).put("displayName", id)
            parts.put(JSONObject().put("inlineData", blob))
        } else {
            // The label connects the bytes to the ID in the appropriate parallel tool's result.
            followingParts.put(JSONObject().put("text", "Tool attachment $id"))
            val blob = JSONObject().put("mime_type", mimeType).put("data", data)
            followingParts.put(JSONObject().put("inline_data", blob))
        }
    }

    if (supportsVision) {
        // Tool output can contain arbitrary file or webpage text. Only load registered image
        // IDs here; an src attribute in that text does not authorize reading a local path.
        for (tag in MediaLinkParser.extractImageLinkTags(result)) {
            imageById(tag.id)?.let { attach(tag.id, it.mimeType, it.base64) }
        }
    }
    if (supportsAudio) {
        for (tag in MediaLinkParser.extractMediaLinkTags(result)) {
            if (tag.type != "audio") continue
            val audio = mediaByTag(tag) ?: continue
            val mimeType = when (audio.mimeType.lowercase()) {
                "audio/mp4", "audio/x-m4a" -> "audio/m4a"
                "audio/x-wav" -> "audio/wav"
                "audio/x-flac" -> "audio/flac"
                else -> audio.mimeType
            }
            if (mimeType.startsWith("audio/")) attach(tag.id, mimeType, audio.base64Data)
        }
    }
}
