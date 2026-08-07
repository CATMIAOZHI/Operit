package com.ai.assistance.operit.ui.features.tokenstats

import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.stats.TokenStatsGroupMemberInfo
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.LegacyPriceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenStatsManagementViewModelTest {
    @Test
    fun `pricing models combine identities configs and overrides without case duplicates`() {
        val groups = listOf(
            TokenStatsGroupModelInfo(
                displayModelId = "gpt",
                displayName = "GPT",
                memberIdentityIds = listOf("id-1"),
                members = listOf(TokenStatsGroupMemberInfo("id-1", "cfg-1", "OPENAI", "gpt-4o")),
            )
        )
        val configs = listOf(
            TokenStatsConfigOption("cfg-1", "Primary", "openai", listOf("GPT-4O")),
            TokenStatsConfigOption("cfg-2", "Backup", "ANTHROPIC", listOf("sonnet")),
        )
        val overrides = listOf(override("openai", "gpt-4o", ""))

        val result = buildPricingModels(groups, configs, overrides)

        assertEquals(2, result.size)
        val gpt = result.first { it.model.equals("gpt-4o", true) }
        assertEquals("OPENAI", gpt.provider)
        assertEquals(listOf("Primary"), gpt.configs.map { it.name })
    }

    @Test
    fun `pricing models retain config overrides whose config was deleted`() {
        val orphan = override("openai", "gpt-4o", "deleted-config", PriceOverrideScope.CONFIG)

        val result = buildPricingModels(emptyList(), emptyList(), listOf(orphan)).single()

        assertEquals("deleted-config", result.configs.single().id)
        assertEquals("deleted-config", result.configs.single().name)
    }

    @Test
    fun `pricing models retain legacy pricing key and avoid duplicate observed model`() {
        val groups = listOf(
            TokenStatsGroupModelInfo(
                displayModelId = "gpt",
                displayName = "GPT",
                memberIdentityIds = listOf("id-1"),
                members = listOf(TokenStatsGroupMemberInfo("id-1", "", "OPENAI", "gpt-4o")),
            )
        )
        val legacy = LegacyPriceSettings(inputPricePerMillion = 3.0)

        val result = buildPricingModels(
            groups,
            emptyList(),
            emptyList(),
            mapOf("OPENAI:gpt-4o" to legacy),
        ).single()

        assertEquals("OPENAI:gpt-4o", result.legacyProviderModel)
        assertEquals(legacy, result.legacyPricing)
    }

    private fun override(
        provider: String,
        model: String,
        configId: String,
        scope: PriceOverrideScope = PriceOverrideScope.PROVIDER_MODEL,
    ) = TokenStatPriceOverrideEntity.normalized(
        scope = scope.name,
        provider = provider,
        model = model,
        configId = configId,
        billingMode = "TOKEN",
        pricingCurrency = "USD",
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 2.0,
    )
}
