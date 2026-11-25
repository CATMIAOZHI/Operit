package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeminiToolResultMediaTest {
    private val tags = """[android] Content of sample:
        <link type="image" id="picture" src="/ignored.png"></link>
        <link type=\"audio\" id=\"clip\"></link>
        <link type="audio" id="clip"></link>
        <link type="video" id="movie"></link>""".trimIndent()

    private fun response() = JSONObject().put("name", "read_file")
        .put("id", "call-1").put("response", JSONObject().put("result", tags))

    private fun image(id: String): ImagePoolManager.ImageData {
        assertEquals("picture", id)
        return ImagePoolManager.ImageData("cGljdHVyZQ==", "image/png", 1, 1)
    }

    private fun audio(tag: MediaLinkTag): MediaLink {
        assertEquals(MediaLinkTag("audio", "clip"), tag)
        return MediaLink("audio", tag.id, "Y2xpcA==", "audio/x-wav")
    }

    @Test fun olderEpochAudioIsReplacedBeforeLoadingAndKeepsTheFileNameAndImage() {
        val image = MediaLinkParser.buildImageLink("portrait")
        fun toolAudio(name: String) = PromptTurn(PromptTurnKind.TOOL_RESULT,
            """<tool_result name="read_file"><content>[android] Content of /music/$name.wav:
                <link type="audio" id="$name"></link>$image</content></tool_result>""")
        val call = PromptTurn(PromptTurnKind.ASSISTANT, """<tool name="read_file"></tool>""")
        val original = listOf(call, toolAudio("first"), call, toolAudio("second"), call, toolAudio("third"))
        // Each clip fills an epoch, so the third starts epoch 2 and only epochs 1 and 2 are sent.
        val replay = limitGeminiAudioReplay(original, epoch = 10) { 10L }
        assertEquals(true, replay[1].content.contains("/music/first.wav:"))
        assertEquals(true, replay[1].content.contains("Earlier audio omitted"))
        assertEquals(true, replay[1].content.contains(image))
        assertEquals(emptyList<IntRange>(), MediaLinkParser.audioLinkRanges(replay[1].content))
        assertEquals(original.subList(2, 6), replay.subList(2, 6))
        assertEquals(1, MediaLinkParser.audioLinkRanges(original[1].content).size)
    }

    @Test fun ordinaryAudioIsReplayedUnchangedSoTheCachedPrefixStaysTheSame() {
        val call = PromptTurn(PromptTurnKind.ASSISTANT, """<tool name="read_file"></tool>""")
        fun toolAudio(id: String) = PromptTurn(PromptTurnKind.TOOL_RESULT, """<link type="audio" id="$id"></link>""")
        var history = emptyList<PromptTurn>()
        var previous = emptyList<PromptTurn>()
        for (n in 1..12) {
            history = history + call + toolAudio("clip$n")
            val replay = limitGeminiAudioReplay(history) { 2L shl 20 } // 2 MiB each, 24 MiB in all
            assertEquals(history, replay)
            assertEquals(previous, replay.take(previous.size))
            previous = replay
        }
    }

    @Test fun theOmittedSetOnlyGrowsWhenANewEpochStarts() {
        val call = PromptTurn(PromptTurnKind.ASSISTANT, """<tool name="read_file"></tool>""")
        fun toolAudio(id: String) = PromptTurn(PromptTurnKind.TOOL_RESULT, """<link type="audio" id="$id"></link>""")
        val history = (1..9).flatMap { listOf(call, toolAudio("clip$it")) }
        fun omitted(turns: Int) = limitGeminiAudioReplay(history.take(turns), epoch = 30) { 10L }
            .count { it.content.contains("omitted from replay") }
        // Clips 1-3 are epoch 0, 4-6 epoch 1, 7-9 epoch 2.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 3, 3, 3), (1..9).map { omitted(it * 2) })
    }

    @Test fun currentParallelAudioAndUserAudioAreNeverDropped() {
        val audio = """<link type="audio" id="clip"></link>"""
        val history = listOf(
            PromptTurn(PromptTurnKind.USER, audio),
            PromptTurn(PromptTurnKind.ASSISTANT, """<tool name="read_file"></tool>"""),
            PromptTurn(PromptTurnKind.TOOL_RESULT, (1..3).joinToString("\n") { audio.replace("clip", "clip$it") }),
        )
        val replay = limitGeminiAudioReplay(history)
        assertEquals(history, replay)
    }

    @Test fun generateContentNestsImageAndSendsAudioOnceAlongsideResponses() {
        val response = response()
        val following = JSONArray()
        attachGeminiToolResultMedia(response, following, "models/gemini-3.7-flash", true, true, false, ::image, ::audio)
        assertEquals(tags, response.getJSONObject("response").getString("result"))
        assertEquals("call-1", response.getString("id"))
        val nested = response.getJSONArray("parts")
        assertEquals(1, nested.length())
        assertEquals("cGljdHVyZQ==", nested.getJSONObject(0).getJSONObject("inlineData").getString("data"))
        assertEquals(2, following.length())
        assertEquals("Tool attachment clip", following.getJSONObject(0).getString("text"))
        val blob = following.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("Y2xpcA==", blob.getString("data"))
        assertEquals("audio/wav", blob.getString("mime_type"))
    }

    @Test fun interactionsNestsAudioWithItsOwnFunctionResultAndSkipsDisabledImages() {
        val response = response()
        val following = JSONArray()
        attachGeminiToolResultMedia(response, following, "google/gemini-4-argon", false, true, true,
            { error("image processing disabled") }, ::audio)
        val blob = response.getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
        assertEquals("clip", blob.getString("displayName"))
        assertEquals("audio/wav", blob.getString("mimeType"))
        assertEquals(0, following.length())
    }

    @Test fun olderGeminiSendsImageAsOrdinaryInputAndNeverLoadsDisabledAudio() {
        val response = response()
        val following = JSONArray()
        attachGeminiToolResultMedia(response, following, "gemini-2.5-pro", true, false, false,
            ::image, { error("audio processing disabled") })
        assertFalse(response.has("parts"))
        assertEquals("picture", following.getJSONObject(0).getString("text").substringAfterLast(' '))
        assertEquals("image/png", following.getJSONObject(1).getJSONObject("inline_data").getString("mime_type"))
    }

    @Test fun missingMediaLeavesToolResultTextAndDoesNotInventAnAttachment() {
        val response = response()
        val following = JSONArray()
        attachGeminiToolResultMedia(response, following, "gemini-3.7-flash", true, true, false, { null }, { null })
        assertEquals(tags, response.getJSONObject("response").getString("result"))
        assertFalse(response.has("parts"))
        assertEquals(0, following.length())
    }
}
