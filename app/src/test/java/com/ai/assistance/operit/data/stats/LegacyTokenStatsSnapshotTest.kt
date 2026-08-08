package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.BillingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTokenStatsSnapshotTest {

    private fun legacyPreferences(
        vararg pairs: Pair<String, Any?>,
    ): Map<String, Any?> = mapOf(*pairs)

    @Test
    fun `parses token counts request count and prices for provider model`() {
        val raw =
            legacyPreferences(
                "token_input_DEEPSEEK_deepseek-chat" to 100L,
                "token_cached_input_DEEPSEEK_deepseek-chat" to 30L,
                "token_output_DEEPSEEK_deepseek-chat" to 50L,
                "request_count_DEEPSEEK_deepseek-chat" to 4,
                "model_input_price_DEEPSEEK_deepseek-chat" to 1.0f,
                "model_cached_input_price_DEEPSEEK_deepseek-chat" to 0.5f,
                "model_output_price_DEEPSEEK_deepseek-chat" to 2.0f,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        assertEquals(1, snapshot.providerModels.size)
        val stats = snapshot.providerModels.getValue("DEEPSEEK:deepseek-chat")
        assertEquals(100L, stats.inputTokens)
        assertEquals(30L, stats.cachedInputTokens)
        assertEquals(50L, stats.outputTokens)
        assertEquals(4L, stats.requestCount)
        assertEquals(1.0, stats.priceSettings.inputPricePerMillion!!, 1e-9)
        assertEquals(0.5, stats.priceSettings.cachedInputPricePerMillion!!, 1e-9)
        assertEquals(2.0, stats.priceSettings.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `count mode billing and per request price are captured`() {
        val raw =
            legacyPreferences(
                "request_count_OPENAI_gpt-4o" to 2,
                "billing_mode_OPENAI_gpt-4o" to "COUNT",
                "price_per_request_OPENAI_gpt-4o" to 0.02f,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        val stats = snapshot.providerModels.getValue("OPENAI:gpt-4o")
        assertEquals(BillingMode.COUNT, stats.priceSettings.billingMode)
        assertEquals(0.02, stats.priceSettings.pricePerRequest!!, 1e-9)
        assertEquals(2L, stats.requestCount)
    }

    @Test
    fun `explicit token billing mode is preserved`() {
        val raw =
            legacyPreferences(
                "token_input_OPENAI_gpt-4o-mini-tts" to 100L,
                "billing_mode_OPENAI_gpt-4o-mini-tts" to "TOKEN",
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        val stats = snapshot.providerModels.getValue("OPENAI:gpt-4o-mini-tts")
        assertEquals(BillingMode.TOKEN, stats.priceSettings.billingMode)
    }

    @Test
    fun `missing counters are zero and all-zero models are dropped`() {
        val raw =
            legacyPreferences(
                "token_input_DEEPSEEK_deepseek-chat" to 10L,
                "token_cached_input_DEEPSEEK_deepseek-chat" to 0L,
                "token_output_DEEPSEEK_deepseek-chat" to 0L,
                "token_input_OTHER_some-model" to 0L,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        assertEquals(1, snapshot.providerModels.size)
        val stats = snapshot.providerModels.getValue("DEEPSEEK:deepseek-chat")
        assertEquals(10L, stats.inputTokens)
        assertEquals(0L, stats.cachedInputTokens)
        assertEquals(0L, stats.outputTokens)
    }

    @Test
    fun `int token values are widened to long`() {
        val raw =
            legacyPreferences(
                "token_input_MOONSHOT_moonshot-v1-8k" to 42,
                "token_output_MOONSHOT_moonshot-v1-8k" to 7,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        val stats = snapshot.providerModels.getValue("MOONSHOT:moonshot-v1-8k")
        assertEquals(42L, stats.inputTokens)
        assertEquals(7L, stats.outputTokens)
    }

    @Test
    fun `provider only keys are kept for the migrator to skip`() {
        val raw =
            legacyPreferences(
                "token_input_DEEPSEEK" to 10L,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        assertEquals(1, snapshot.providerModels.size)
        assertTrue(snapshot.providerModels.containsKey("DEEPSEEK"))
    }

    @Test
    fun `unknown provider decoding preserves model underscores`() {
        val raw =
            legacyPreferences(
                "token_input_Custom_gpt_4" to 10L,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        assertTrue(snapshot.providerModels.containsKey("Custom:gpt_4"))
    }

    @Test
    fun `registered provider decoding preserves provider and model underscores`() {
        val raw =
            legacyPreferences(
                "token_input_My_Custom_gpt_4" to 10L,
                "token_output_My_Custom_gpt_4" to 5L,
            )

        val snapshot =
            LegacyTokenStatsSnapshot.parse(
                rawPreferences = raw,
                additionalProviderNames = listOf("My", "My_Custom"),
            )

        val stats = snapshot.providerModels.getValue("My_Custom:gpt_4")
        assertEquals(10L, stats.inputTokens)
        assertEquals(5L, stats.outputTokens)
    }

    @Test
    fun `zero prices are treated as unset`() {
        val raw =
            legacyPreferences(
                "token_input_DEEPSEEK_deepseek-chat" to 10L,
                "model_input_price_DEEPSEEK_deepseek-chat" to 0.0f,
            )

        val snapshot = LegacyTokenStatsSnapshot.parse(raw)

        val stats = snapshot.providerModels.getValue("DEEPSEEK:deepseek-chat")
        assertNull(stats.priceSettings.inputPricePerMillion)
        assertFalse(stats.priceSettings.hasAnyUserSetting())
    }
}
