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

    @Test
    fun `same provider and model expose each api configuration independently`() {
        val groups = listOf(
            TokenStatsGroupModelInfo(
                displayModelId = "deepseek",
                displayName = "DeepSeek",
                memberIdentityIds = listOf("official-id", "relay-id"),
                members = listOf(
                    TokenStatsGroupMemberInfo("official-id", "official", "DEEPSEEK", "deepseek-chat"),
                    TokenStatsGroupMemberInfo("relay-id", "relay", "DEEPSEEK", "deepseek-chat"),
                ),
            )
        )
        val configs = listOf(
            TokenStatsConfigOption(
                id = "official",
                name = "官方配置",
                provider = "DEEPSEEK",
                models = listOf("deepseek-chat"),
                endpoint = "https://api.deepseek.com",
            ),
            TokenStatsConfigOption(
                id = "relay",
                name = "中转站",
                provider = "DEEPSEEK",
                models = listOf("deepseek-chat"),
                endpoint = "https://relay.example.com",
            ),
        )
        val overrides = listOf(
            override("DEEPSEEK", "deepseek-chat", "official", PriceOverrideScope.CONFIG),
            override("DEEPSEEK", "deepseek-chat", "relay", PriceOverrideScope.CONFIG),
        )

        val result = buildPricingModels(groups, configs, overrides).single()

        assertEquals(listOf("official", "relay"), result.configs.map { it.id })
        assertEquals(
            listOf("https://api.deepseek.com", "https://relay.example.com"),
            result.configs.map { it.endpoint },
        )
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
