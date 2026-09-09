package com.ai.assistance.operit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilsProviderModelTest {
    @Test fun antigravityRetainsGeminiReplayMetadata() {
        assertTrue(ChatUtils.isGeminiProviderModel("GOOGLE_ANTIGRAVITY:gemini-pro-agent"))
    }
    @Test fun responsesProviderModelIncludesCodexForReasoningReplay() {
        assertTrue(ChatUtils.isOpenAIResponsesProviderModel("OPENAI_CODEX:gpt-5.4"))
        assertTrue(ChatUtils.isOpenAIResponsesProviderModel("OPENAI_RESPONSES_GENERIC:gpt"))
        assertFalse(ChatUtils.isOpenAIResponsesProviderModel("OPENAI:gpt"))
    }

    @Test fun providerModel_acceptsGoogleWithoutSuffix() {
        assertTrue(ChatUtils.isGeminiProviderModel("google"))
    }

    @Test fun providerModel_acceptsGeminiGenericWithoutSuffix() {
        assertTrue(ChatUtils.isGeminiProviderModel("gemini_generic"))
    }

    @Test fun providerModel_rejectsEmptyString() {
        assertFalse(ChatUtils.isGeminiProviderModel(""))
    }

    @Test fun providerModel_rejectsAnthropic() {
        assertFalse(ChatUtils.isGeminiProviderModel("anthropic:claude"))
    }
}
