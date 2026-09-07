package com.ai.assistance.operit.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModelProtocolResolverTest {
    private val endpoint = "https://opencode.ai/zen/go/v1/chat/completions"
    private fun config() = ModelConfigData(
        id = "account", name = "Go", apiEndpoint = endpoint, modelName = "deepseek,gpt,minimax",
        modelProtocolSettings = mapOf(
            "deepseek" to ModelProtocolSettings(ModelProtocol.DEEPSEEK),
            "gpt" to ModelProtocolSettings(ModelProtocol.RESPONSES),
            "minimax" to ModelProtocolSettings(ModelProtocol.ANTHROPIC),
        ),
    )

    @Test
    fun selectedModelsResolveDifferentProtocolsWithoutChangingAccount() {
        val account = config()
        val response = account.forSelectedModel(1)
        assertEquals(ApiProviderType.OPENAI_RESPONSES_GENERIC, response.apiProviderType)
        assertEquals(response.apiProviderType.name, response.apiProviderTypeId)
        assertEquals("https://opencode.ai/zen/go/v1/responses", response.apiEndpoint)
        assertEquals("https://opencode.ai/zen/go/v1/messages", account.forSelectedModel(2).apiEndpoint)
        assertEquals(ApiProviderType.DEEPSEEK, account.forSelectedModel(0).apiProviderType)
        assertEquals(endpoint, account.apiEndpoint)
        assertEquals(response, response.withModelProtocol())
    }

    @Test
    fun oldConfigurationsInheritAndOverridesSurviveBackup() {
        val old = Json.decodeFromString<ModelConfigData>(
            """{"id":"old","name":"old","modelName":"model","apiEndpoint":"https://example.test/custom"}"""
        )
        assertTrue(old.modelProtocolSettings.isEmpty())
        assertEquals(old, old.forSelectedModel(0))
        val restored = Json.decodeFromString<ModelConfigData>(Json.encodeToString(config()))
        assertEquals(config().modelProtocolSettings, restored.modelProtocolSettings)
        assertEquals(config().forSelectedModel(1), restored.forSelectedModel(1))
    }

    @Test
    fun modelOrderDoesNotChangeOverridesAndRemovedModelsArePruned() {
        val updated = config().withModelNames("minimax,gpt,new")
        assertEquals(ApiProviderType.ANTHROPIC_GENERIC, updated.forSelectedModel(0).apiProviderType)
        assertEquals(ModelProtocol.INHERIT, updated.protocolSettingsForModel("new").protocol)
        assertFalse(updated.modelProtocolSettings.containsKey("deepseek"))
    }

    @Test
    fun customEndpointsAndExplicitOptOutArePreserved() {
        val exact = "https://example.test/custom/action?api-version=test#"
        val account = config().copy(modelProtocolSettings = mapOf(
            "gpt" to ModelProtocolSettings(ModelProtocol.RESPONSES, exact)
        ))
        assertEquals(exact, account.forSelectedModel(1).apiEndpoint)
        assertEquals(endpoint + "#", protocolEndpoint(endpoint + "#", ModelProtocol.RESPONSES))
        assertEquals(exact, protocolEndpoint(exact, ModelProtocol.RESPONSES))
        assertEquals(
            "https://opencode.ai/zen/go/v1/messages?revision=2",
            protocolEndpoint(endpoint + "?revision=2", ModelProtocol.ANTHROPIC),
        )
        assertEquals("https://example.test/custom/action",
            protocolEndpoint("https://example.test/custom/action", ModelProtocol.RESPONSES))
    }

    @Test
    fun localAndPluginProvidersKeepTheirOwnExecutionPath() {
        for (type in listOf(ApiProviderType.MNN.name, ApiProviderType.LLAMA_CPP.name, "plugin-provider")) {
            val account = config().copy(apiProviderTypeId = type)
            val selected = account.forSelectedModel(1)
            assertEquals(type, selected.apiProviderTypeId)
            assertEquals(endpoint, selected.apiEndpoint)
        }
    }

    @Test
    fun inheritedChoiceIgnoresAnOldOverrideEndpoint() {
        val account = config().copy(modelProtocolSettings = mapOf(
            "gpt" to ModelProtocolSettings(ModelProtocol.INHERIT, "https://example.test/old"),
        ))
        assertEquals(endpoint, account.forSelectedModel(1).apiEndpoint)
        assertEquals(account.apiProviderTypeId, account.forSelectedModel(1).apiProviderTypeId)
    }
}
