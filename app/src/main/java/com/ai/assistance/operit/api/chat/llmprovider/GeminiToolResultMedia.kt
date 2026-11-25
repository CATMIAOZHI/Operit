package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.MediaPoolManager
import org.json.JSONArray
import org.json.JSONObject

private val geminiGeneration = Regex("""^gemini-(\d+)(?:[.-]|$)""")
private val nestedImageTypes = setOf("image/jpeg", "image/png", "image/webp")

/** Base64 characters of tool audio per replay epoch. See [limitGeminiAudioReplay]. Two epochs
 * and one clip crossing into them stay well under generateContent's limit on inline data. */
internal const val GEMINI_AUDIO_REPLAY_EPOCH = 16L shl 20

/**
 * Tool audio is replayed unchanged so the request prefix, and with it the provider's prompt
 * cache, stays the same from turn to turn. Only a chat holding a lot of audio is bounded: clips
 * are grouped oldest first into epochs of [epoch] Base64 characters, and only the newest two
 * epochs are sent. A clip's epoch never changes as the chat grows, so the omitted set moves only
 * when a new epoch starts, not on every turn. An omitted clip keeps read_file's file path and the
 * surrounding result text, so the model can read it again. Audio of the current round is always
 * sent, and audio the user sent has no such path and is never omitted.
 */
internal fun limitGeminiAudioReplay(
    history: List<PromptTurn>,
    epoch: Long = GEMINI_AUDIO_REPLAY_EPOCH,
    sizeOf: (String) -> Long? = MediaPoolManager::base64Length,
): List<PromptTurn> {
    class Clip(val turn: Int, val range: IntRange, val epoch: Long)
    val roundStart = history.indexOfLast { it.kind == PromptTurnKind.ASSISTANT || it.kind == PromptTurnKind.TOOL_CALL } + 1
    val clips = mutableListOf<Clip>()
    var total = 0L
    history.forEachIndexed { index, turn ->
        if (turn.kind != PromptTurnKind.TOOL_RESULT) return@forEachIndexed
        for ((range, id) in MediaLinkParser.audioLinks(turn.content)) {
            clips.add(Clip(index, range, total / epoch))
            total += sizeOf(id) ?: 0L
        }
    }
    val keepFrom = (clips.lastOrNull()?.epoch ?: return history) - 1
    val omitted = clips.filter { it.epoch < keepFrom && it.turn < roundStart }.groupBy { it.turn }
    if (omitted.isEmpty()) return history
    val revised = history.toMutableList()
    for ((index, turnClips) in omitted) {
        val turn = history[index]
        revised[index] = turn.copy(content = buildString {
            var start = 0
            for (clip in turnClips) {
                append(turn.content, start, clip.range.first)
                append("[Earlier audio omitted from replay; read the file again if needed]")
                start = clip.range.last + 1
            }
            append(turn.content, start, turn.content.length)
        })
    }
    return revised
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
