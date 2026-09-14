package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrokAccountPolicyTest {
    @Test fun accountModelsUseTheirDeclaredReasoningLadder() {
        assertEquals("xhigh", GrokAccountPolicy.effort("grok-4.6", "max"))
        assertEquals("high", GrokAccountPolicy.effort("grok-4.5", "xhigh"))
        assertNull(GrokAccountPolicy.effort("grok-4.6", "none"))
        assertNull(GrokAccountPolicy.effort("grok-build-0.1", "high"))
        assertEquals("max", GrokAccountPolicy.effort("grok-future", "max"))
    }
    @Test fun displayedEffortMatchesTheAccountRequestPolicy() {
        assertEquals(ThinkingRequestSummary.Effort("xhigh"),
            ThinkingRequestSemantics.resolve(providerType = ApiProviderType.GROK_ACCOUNT, modelName = "grok-4.6", qualityLevel = 5, modelParameters = emptyList()))
        assertEquals(ThinkingRequestSummary.NotSent,
            ThinkingRequestSemantics.resolve(providerType = ApiProviderType.GROK_ACCOUNT, modelName = "grok-build-0.1", qualityLevel = 5, modelParameters = emptyList()))
    }
    @Test fun streamedAccountRequestsAskForUsage() {
        val request = JSONObject()
        request.applyChatCompletionsStreamUsageOption(true, ApiProviderType.GROK_ACCOUNT, false)
        assertTrue(request.getJSONObject("stream_options").getBoolean("include_usage"))
    }
}
