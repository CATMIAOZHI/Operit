package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AntigravityTransportTest {
    @Test fun flashWithoutExplicitOverrideUsesUserQualityAndFixedTiersRemainFixed() {
        assertEquals("low", antigravityThinkingEffort("gemini-3.7-flash-tiered", 1, true, emptyList()))
        // The top level stays xhigh until the request knows whether the model has that tier.
        assertEquals("xhigh", antigravityThinkingEffort("gemini-3.7-flash-tiered", 5, true, emptyList()))
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

    @Test fun tierFamiliesShowAsOneModelAndSendTheChosenTier() {
        val (models, families) = AntigravityTransport.parseCatalog(JSONObject("""{
          "models":{
            "gemini-4-carbon-low":{"displayName":"Gemini 4 Carbon (Low)"},
            "gemini-4-carbon-medium":{"displayName":"Gemini 4 Carbon (Medium)"},
            "gemini-4-carbon-high":{"displayName":"Gemini 4 Carbon (High)"},
            "gemini-4-neon-low":{},"gemini-4-neon-medium":{},"gemini-4-neon-high":{},"gemini-4-neon-xhigh":{},
            "gemini-3.8-flash-low":{},"gemini-3.8-flash-medium":{},"gemini-3.8-flash-high":{},
            "gemini-3.8-flash-tiered":{},"gemini-4-neon-tiered-high":{},
            "gpt-oss-120b-medium":{"displayName":"GPT-OSS 120B (Medium)"},
            "old-low":{"disabled":true},"old-medium":{},"old-high":{}
          },
          "agentModelSorts":[{"groups":[{"modelIds":["gemini-4-neon-high","gemini-4-neon-xhigh","gemini-4-neon-medium",
            "gemini-4-neon-low","gemini-4-carbon-medium","gemini-4-carbon-high","gemini-4-carbon-low",
            "gemini-3.8-flash-high","gemini-3.8-flash-medium","gemini-3.8-flash-low","gpt-oss-120b-medium",
            "old-low","old-medium","old-high"]}]}],
          "tieredModelIds":{"flash":["gemini-4-neon-tiered-high","gemini-3.8-flash-tiered"]}
        }"""))
        assertEquals(listOf("gemini-4-neon", "gemini-4-carbon", "gemini-3.8-flash", "gpt-oss-120b-medium",
            "old-medium", "old-high"), models.map { it.id })
        assertEquals("Gemini 4 Carbon", models[1].name)
        assertEquals("GPT-OSS 120B (Medium)", models[3].name)
        assertEquals(setOf("low", "medium", "high", "xhigh"), families["gemini-4-neon"])
        run {
            fun wire(model: String, level: String) = AntigravityTransport.compile(JSONObject("""{
              "contents":[{"role":"user","parts":[{"text":"hi"}]}],
              "generationConfig":{"thinkingConfig":{"thinkingLevel":"$level"}}}"""), model, "s", families = families)
            val carbon = wire("gemini-4-carbon", "medium")
            assertEquals("gemini-4-carbon-medium", carbon.first)
            assertFalse(carbon.second.optJSONObject("generationConfig")?.has("thinkingConfig") ?: false)
            assertEquals("gemini-4-carbon-high", wire("gemini-4-carbon", "xhigh").first)
            assertEquals("gemini-4-neon-xhigh", wire("gemini-4-neon", "xhigh").first)
            assertEquals("gemini-4-neon-low", wire("gemini-4-neon", "low").first)
            assertEquals("gemini-4-argon-low", wire("gemini-4-argon-low", "high").first)
            assertEquals("gpt-oss-120b-medium", wire("gpt-oss-120b-medium", "high").first)
            assertEquals("xhigh", antigravityThinkingEffort("gemini-4-carbon", 5, true, emptyList()))
        }
    }

    @Test fun listedModelsOutsideAFamilyAreSentAsListed() {
        val (models, families) = AntigravityTransport.parseCatalog(JSONObject("""{
          "models":{"gemini-4-solo":{},"gemini-5-flash-low":{},"gemini-5-flash-tiered":{}},
          "agentModelSorts":[{"groups":[{"modelIds":["gemini-4-solo","gemini-5-flash-low"]}]}],
          "tieredModelIds":{"flash":["gemini-5-flash-tiered"]}
        }"""))
        assertEquals(listOf("gemini-4-solo", "gemini-5-flash-low", "gemini-5-flash-tiered"), models.map { it.id })
        assertEquals("gemini-4-solo", AntigravityFamilies.wire("gemini-4-solo", "high", families))
        // Once the account's list is read, a known name it does not list as a family is not one.
        assertEquals("gemini-4-carbon", AntigravityFamilies.wire("gemini-4-carbon", "high", families))
        assertEquals("gemini-4-carbon-high", AntigravityFamilies.wire("gemini-4-carbon", "high", null))
        assertEquals("gemini-4-neon-xhigh", AntigravityFamilies.wire("gemini-4-neon", "xhigh", null))
        assertEquals("low", antigravityShownEffort("gemini-4-argon-low", 5, true, emptyList()))
        assertEquals("high", antigravityShownEffort("gemini-4-carbon", 5, true, emptyList()))
        assertEquals("xhigh", antigravityShownEffort("gemini-4-neon", 5, true, emptyList()))
        assertEquals("high", antigravityShownEffort("gemini-3.7-flash-tiered", 5, true, emptyList()))
    }

    @Test fun gemini4UsesCliClientAndTierSuffixedIdsSendNoSecondLevel() {
        val manager = com.ai.assistance.operit.data.api.ProviderAccountManager
        assertEquals(manager.ANTIGRAVITY_CLI_UA, manager.antigravityUserAgent("gemini-4-argon-low"))
        assertEquals(manager.ANTIGRAVITY_CLI_UA, manager.antigravityUserAgent("gemini-4-hydrogen-xhigh"))
        assertEquals(manager.ANTIGRAVITY_CLI_UA, manager.antigravityUserAgent("gemini-4-neon-tiered-high"))
        assertEquals(manager.ANTIGRAVITY_UA, manager.antigravityUserAgent("gemini-3.7-flash-tiered"))
        assertEquals(manager.ANTIGRAVITY_UA, manager.antigravityUserAgent("gemini-3.1-pro-low"))
        val body = AntigravityTransport.compile(JSONObject("""{
          "contents":[{"role":"user","parts":[{"text":"hi"}]}],
          "generationConfig":{"thinkingConfig":{"thinkingLevel":"high"}}
        }"""), "gemini-4-argon-low", "s").second
        assertFalse(body.optJSONObject("generationConfig")?.has("thinkingConfig") ?: false)
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
