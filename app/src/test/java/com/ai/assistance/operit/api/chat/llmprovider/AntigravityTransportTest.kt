package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AntigravityTransportTest {
    @Test fun flashWithoutExplicitOverrideUsesUserQualityAndFixedTiersRemainFixed() {
        assertEquals("low", antigravityThinkingEffort("gemini-3.7-flash-tiered", 1, true, emptyList()))
        assertEquals("high", antigravityThinkingEffort("gemini-3.7-flash-tiered", 5, true, emptyList()))
        assertEquals("low", antigravityThinkingEffort("claude-sonnet-4-6", 5, false, emptyList()))
        assertEquals("high", antigravityThinkingEffort("gemini-pro-agent", 1, true, emptyList()))
    }
    @Test fun nativeGeminiToolDeclarationsCallsAndResultsKeepTheirAssociation() {
        val source = JSONObject("""{
          "tools":[{"function_declarations":[{"name":"package:read","parameters":{"type":"OBJECT"}}]}],
          "contents":[
            {"role":"model","parts":[{"functionCall":{"id":"call-1","name":"package:read","args":{"line":2}},"thought_signature":"realSignature123456=="}]},
            {"role":"user","parts":[{"functionResponse":{"id":"call-1","name":"package:read","response":{"result":"text"}}}]}
          ]}""")
        val names = mutableMapOf<String, String>()
        val body = AntigravityTransport.compile(source, "claude-sonnet-4-6", "session-1", names).second
        val declaration = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        val call = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        val result = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals(declaration.getString("name"), call.getJSONObject("functionCall").getString("name"))
        assertEquals(declaration.getString("name"), result.getString("name"))
        assertEquals("package:read", names[declaration.getString("name")])
        assertEquals("call-1", result.getString("id"))
        assertEquals("realSignature123456==", call.getString("thoughtSignature"))
        assertEquals("VALIDATED", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertEquals("session-1", body.getString("sessionId"))
    }

    @Test fun imagePayloadAndThinkingUseCcaFieldNames() {
        val body = AntigravityTransport.compile(JSONObject("""{
          "contents":[{"role":"user","parts":[{"inline_data":{"mime_type":"image/png","data":"YWJj"}}]}],
          "generationConfig":{"thinkingConfig":{"thinkingBudget":4096,"includeThoughts":true,"thinkingLevel":"xhigh"}}
        }"""), "gemini-3.7-flash", "stable").let {
            assertEquals("gemini-3.7-flash-tiered", it.first)
            it.second
        }
        assertEquals("image/png", body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        val thinking = body.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertEquals("high", thinking.getString("thinkingLevel"))
        assertFalse(thinking.has("thinkingBudget"))
        assertFalse(thinking.has("includeThoughts"))
    }

    @Test fun discoveryKeepsAgentAndTieredModelsButNotUnadvertisedModels() {
        val models = AntigravityTransport.parseModels(JSONObject("""{
          "models":{"a":{"displayName":"A"},"gemini-3.7-flash-tiered":{},"hidden":{}},
          "agentModelSorts":[{"groups":[{"modelIds":["a"]}]}],
          "tieredModelIds":{"flash":["gemini-3.7-flash-tiered"]}
        }"""))
        assertEquals(listOf("a", "gemini-3.7-flash-tiered"), models.map { it.id })
    }

    @Test fun switchingAccountsDropsBothSignatureSpellingsButKeepsToolIdentity() {
        val original = JSONObject("""{"_operit_account_scope":"a:model","role":"model","parts":[
          {"thought":true,"text":"private reasoning","thoughtSignature":"sig"},
          {"functionCall":{"id":"c1","name":"tool","args":{}},"thought_signature":"sig"}
        ]}""")
        val same = prepareAccountReplay(original, "a:model").getJSONArray("parts")
        assertEquals(2, same.length())
        assertTrue(same.getJSONObject(1).has("thought_signature"))
        val other = prepareAccountReplay(original, "b:model").getJSONArray("parts")
        assertEquals(1, other.length())
        assertEquals("c1", other.getJSONObject(0).getJSONObject("functionCall").getString("id"))
        assertFalse(other.getJSONObject(0).has("thought_signature"))
        assertTrue(original.has("_operit_account_scope"))
    }
}
