package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestBodyLogTest {
    @Test fun streamsLargeValuesAndRedactsWithoutSerializingOrMutatingSource() {
        val source = object : JSONObject() {
            override fun toString(): String = error("must not serialize the whole request")
        }
        val content = "中文🙂 \"\\\n\t\u0001".repeat(100_000)
        val image = "data:image/png;base64," + "a".repeat(400_000)
        source.put("messages", JSONArray().put(JSONObject().put("content", content)))
        source.put("image_url", image)
        source.put("tools", JSONArray().put(JSONObject().put("name", "tool")))
        source.put("source", JSONObject().put("media_type", "image/png").put("data", "short"))
        source.put("audio", JSONObject().put("data", "a".repeat(400)))
        val chunks = ArrayList<String>()
        RequestBodyLog.chunks(source, 97) { chunks.add(it) }
        assertTrue(chunks.all { it.length <= 97 })
        val parsed = JSONObject(chunks.joinToString("") { String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) })
        assertEquals(content, parsed.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(parsed.getString("image_url").contains("omitted"))
        assertTrue(parsed.getJSONObject("source").getString("data").contains("omitted"))
        assertTrue(parsed.getJSONObject("audio").getString("data").contains("omitted"))
        assertEquals("[1 tools omitted for brevity]", parsed.getString("tools"))
        assertEquals(image, source.getString("image_url"))
        assertEquals("short", source.getJSONObject("source").getString("data"))
        assertEquals(1, source.getJSONArray("tools").length())
    }
}
