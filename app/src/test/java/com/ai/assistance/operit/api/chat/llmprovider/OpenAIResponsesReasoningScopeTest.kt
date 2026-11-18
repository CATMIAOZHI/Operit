package com.ai.assistance.operit.api.chat.llmprovider

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Encrypted reasoning only verifies where it was written. After the chat switches to another
 * Responses backend, account or model, replaying it fails every later request, so it stays out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenAIResponsesReasoningScopeTest {
    private fun reasoningItem(id: String) =
        JSONObject().put("type", "reasoning").put("id", id).put("encrypted_content", "enc-$id").put("summary", JSONArray())

    private fun replayedReasoningIds(assistantContent: String, scope: String?): List<String> {
        val request =
            JSONObject().put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "hi"))
                    .put(JSONObject().put("role", "assistant").put("content", assistantContent)),
            )
        val input = OpenAIResponsesPayloadAdapter.toResponsesRequest(request, scope).getJSONArray("input")
        return (0 until input.length()).map { input.getJSONObject(it) }
            .filter { it.optString("type") == "reasoning" }
            .map { it.getString("id") }
    }

    @Test
    fun `reasoning goes back only to the scope that wrote it`() {
        val content =
            OpenAIResponsesPayloadAdapter.createReasoningMetadataTag(reasoningItem("rs_a"), "scope-a")!! +
                OpenAIResponsesPayloadAdapter.createReasoningMetadataTag(reasoningItem("rs_b"), "scope-b")!! +
                "answer"

        assertEquals(listOf("rs_a"), replayedReasoningIds(content, "scope-a"))
        assertEquals(listOf("rs_b"), replayedReasoningIds(content, "scope-b"))
    }

    @Test
    fun `reasoning saved before scopes were kept is still replayed`() {
        val content = OpenAIResponsesPayloadAdapter.createReasoningMetadataTag(reasoningItem("rs_old"))!! + "answer"

        assertEquals(listOf("rs_old"), replayedReasoningIds(content, "scope-a"))
    }
}
