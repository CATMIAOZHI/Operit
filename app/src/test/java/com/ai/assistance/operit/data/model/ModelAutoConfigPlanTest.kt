package com.ai.assistance.operit.data.model

import org.junit.Assert.*
import org.junit.Test

class ModelAutoConfigPlanTest {
    private val protocolsJson = """
        {
          "deepseek": {"api":"https://api.deepseek.com","npm":"@ai-sdk/openai-compatible",
            "models":{"deepseek-flash":{"interleaved":{"field":"reasoning_content"},
              "reasoning_options":[{"type":"effort","values":["low","high","max"]}]}}},
          "openai": {"npm":"@ai-sdk/openai",
            "models":{"gpt-5.5":{"reasoning_options":[{"type":"effort","values":["low","high"]}],
              "provider":{"api":"https://elsewhere.example/v1"}}}},
          "openrouter": {"api":"https://openrouter.ai/api/v1","npm":"@openrouter/ai-sdk-provider",
            "models":{"openai/gpt-5.5":{"reasoning_options":[{"type":"effort","values":["medium"]}]}}},
          "nameless": {"npm":"@ai-sdk/openai-compatible","models":{"x":{}}}
        }
    """.trimIndent()
    private val protocols = ModelProtocolCatalog.parse(protocolsJson)
    private val capabilities = OfficialModelCapabilitiesCatalog.parse("""
        {
          "deepseek/deepseek-flash": {"name":"DeepSeek Flash","modalities":{"input":["text","image"]},
            "limit":{"context":1000000,"output":384000}},
          "openai/gpt-5.5": {"name":"GPT-5.5","modalities":{"input":["text","image"]}}
        }
    """.trimIndent())
    private val catalogs = ModelAutoConfig.Catalogs(protocols, capabilities)

    private fun config(
        names: String,
        endpoint: String = "https://api.deepseek.com/chat/completions",
        provider: ApiProviderType = ApiProviderType.OPENAI_GENERIC,
        protocols: Map<String, ModelProtocolSettings> = emptyMap(),
        capabilities: Map<String, ModelMultimodalCapabilities> = emptyMap(),
        limits: Map<String, ModelLimits> = emptyMap(),
        settled: Set<String>? = emptySet(),
    ) = ModelConfigData(id = "c", name = "c", modelName = names, apiEndpoint = endpoint,
        apiProviderType = provider, apiProviderTypeId = provider.name, modelProtocolSettings = protocols,
        modelMultimodalCapabilities = capabilities, modelLimits = limits, settledCapabilityModels = settled)

    private fun plan(config: ModelConfigData, force: Boolean = false) =
        ModelAutoConfig.plan(config, getModelList(config.modelName), catalogs, force)

    @Test fun newModelGetsProtocolInputsAndLimits() {
        val plan = plan(config("deepseek-flash,my-own-model"))
        assertEquals(ModelProtocol.CHAT_REASONING, plan.protocols["deepseek-flash"]?.protocol)
        assertTrue(plan.capabilities.getValue("deepseek-flash").image)
        assertEquals(ModelLimits(1_000_000, 384_000), plan.limits["deepseek-flash"])
        assertEquals(setOf("deepseek-flash"), plan.matched)
        assertEquals(setOf("deepseek-flash"), plan.settled)
    }

    @Test fun setFieldsAreLeftAloneUnlessForced() {
        val custom = mapOf("deepseek-flash" to ModelProtocolSettings(ModelProtocol.CHAT_COMPLETIONS, "https://proxy.example/v1"))
        val mine = config("deepseek-flash", protocols = custom,
            capabilities = mapOf("deepseek-flash" to ModelMultimodalCapabilities()),
            limits = mapOf("deepseek-flash" to ModelLimits(64_000, null)), settled = setOf("deepseek-flash"))
        assertTrue(plan(mine).isEmpty)

        val forced = plan(mine, force = true)
        assertEquals(ModelProtocol.CHAT_REASONING, forced.protocols.getValue("deepseek-flash").protocol)
        // The model's own address survives a re-match.
        assertEquals("https://proxy.example/v1", forced.protocols.getValue("deepseek-flash").endpoint)
        assertTrue(forced.capabilities.getValue("deepseek-flash").image)
        assertEquals(ModelLimits(1_000_000, 384_000), forced.limits["deepseek-flash"])
    }

    @Test fun eachKindFillsItsOwnGap() {
        // Limits set by hand do not stop a missing protocol from being filled, nor the reverse.
        val plan = plan(config("deepseek-flash", limits = mapOf("deepseek-flash" to ModelLimits(64_000, null))))
        assertEquals(ModelProtocol.CHAT_REASONING, plan.protocols["deepseek-flash"]?.protocol)
        assertFalse("deepseek-flash" in plan.limits)
    }

    @Test fun inputsSwitchedOffByHandStayOff() {
        // A configuration from before inputs were tracked: its listed models count as set by hand.
        val legacy = config("deepseek-flash", capabilities = mapOf("deepseek-flash" to ModelMultimodalCapabilities()), settled = null)
        assertFalse("deepseek-flash" in plan(legacy).capabilities)
        // A model added later is filled in.
        val added = legacy.withModelNames("deepseek-flash,openai/gpt-5.5")
        assertEquals(setOf("openai/gpt-5.5"), plan(added).capabilities.keys)
    }

    @Test fun catalogWithoutLimitsWritesNoLimits() {
        val plan = plan(config("gpt-5.5", endpoint = "https://api.openai.com/v1/chat/completions"))
        assertFalse("gpt-5.5" in plan.limits)
        assertTrue("gpt-5.5" in plan.matched)
    }

    @Test fun officialAddressKeepsTheProviderProtocolAndGetsThinkingLevels() {
        val plan = plan(config("gpt-5.5", endpoint = "https://api.openai.com/v1/chat/completions"))
        val settings = plan.protocols.getValue("gpt-5.5")
        assertEquals(ModelProtocol.INHERIT, settings.protocol)
        assertEquals("", settings.endpoint)
        assertEquals(listOf("low", "high"), settings.reasoningEfforts)
    }

    @Test fun thinkingLevelsOnlyWhereRequestsSendThem() {
        val endpoint = "https://openrouter.ai/api/v1/chat/completions"
        val generic = plan(config("openai/gpt-5.5", endpoint = endpoint))
        assertEquals(listOf("medium"), generic.protocols.getValue("openai/gpt-5.5").reasoningEfforts)
        // The OpenRouter provider sends its own thinking budget, so the levels would do nothing.
        val openRouter = plan(config("openai/gpt-5.5", endpoint = endpoint, provider = ApiProviderType.OPENROUTER))
        assertTrue(openRouter.protocols.isEmpty())
        assertTrue("openai/gpt-5.5" in openRouter.matched)
    }

    @Test fun reMatchKeepsTheProtocolAModelAddressNeeds() {
        val own = mapOf("gpt-5.5" to ModelProtocolSettings(ModelProtocol.RESPONSES, "https://proxy.example/v1/responses"))
        val forced = plan(config("gpt-5.5", endpoint = "https://api.openai.com/v1", protocols = own), force = true)
        assertEquals(ModelProtocol.RESPONSES, forced.protocols.getValue("gpt-5.5").protocol)
        assertEquals("https://proxy.example/v1/responses", forced.protocols.getValue("gpt-5.5").endpoint)
    }

    @Test fun pageEditsLayOverWhatWasSavedMeanwhile() {
        val base = ModelAutoFields(emptyMap(), mapOf("a" to ModelMultimodalCapabilities()), emptyMap(), emptySet())
        val mine = base.copy(capabilities = mapOf("a" to ModelMultimodalCapabilities(audio = true)), settled = setOf("a"))
        val stored = base.copy(limits = mapOf("a" to ModelLimits(1000, 100)))
        val merged = stored.withEdits(base, mine)
        assertEquals(ModelLimits(1000, 100), merged.limits["a"])
        assertTrue(merged.capabilities.getValue("a").audio)
        assertEquals(setOf("a"), merged.settled)
    }

    @Test fun oneSwitchOrNumberChangedKeepsTheOthersSavedMeanwhile() {
        val base = ModelAutoFields(emptyMap(), mapOf("a" to ModelMultimodalCapabilities()), emptyMap(), emptySet())
        // Saved meanwhile: pictures on and a reply limit.
        val stored = base.copy(capabilities = mapOf("a" to ModelMultimodalCapabilities(image = true)),
            limits = mapOf("a" to ModelLimits(null, 8_192)))
        // On the page: audio switched on and a context limit typed.
        val mine = base.copy(capabilities = mapOf("a" to ModelMultimodalCapabilities(audio = true)),
            limits = mapOf("a" to ModelLimits(64_000, null)))
        val merged = stored.withEdits(base, mine)
        assertEquals(ModelMultimodalCapabilities(image = true, audio = true), merged.capabilities["a"])
        assertEquals(ModelLimits(64_000, 8_192), merged.limits["a"])
    }

    @Test fun suffixLookupFindsRouteQualifiedIds() {
        val catalog = OfficialModelCapabilitiesCatalog.parse("""{"vendor/model-x": {"name":"X","modalities":{"input":["text","image"]}}}""")
        assertTrue(catalog.matchEntries(listOf("proxy-vendor-model-x")).containsKey("proxy-vendor-model-x"))
        assertTrue(catalog.matchEntries(listOf("other-thing")).isEmpty())
    }

    @Test fun contextIsHeldToTheSelectedModel() {
        val config = ModelConfigData(id = "c", name = "c", modelName = "small,big", maxContextLength = 1000f,
            modelLimits = mapOf("small" to ModelLimits(contextTokens = 128 * 1024)))
        val policy = ChatContextPolicy(windowK = 400f)
        assertEquals(128f, resolveChatContext(policy, config, "small").windowK, 0f)
        assertEquals(400f, resolveChatContext(policy, config, "big").windowK, 0f)
        // Several models and none named: nothing to hold it to.
        assertEquals(400f, resolveChatContext(policy, config).windowK, 0f)
        assertEquals(128f, resolveChatContext(policy.copy(maxMode = true), config, "small").capacityK, 0f)
    }

    @Test fun replyLengthIsHeldToTheModel() {
        val config = ModelConfigData(id = "c", name = "c", modelName = "small", maxTokens = 100_000,
            modelLimits = mapOf("small" to ModelLimits(outputTokens = 8_192)))
        assertEquals(8_192, config.effectiveMaxTokens())
        assertEquals(100_000, config.copy(modelLimits = emptyMap()).effectiveMaxTokens())
    }

    @Test fun slimmedDownloadMatchesLikeTheFullOne() {
        val slim = ModelProtocolCatalog.slim(protocolsJson)
        assertFalse("nameless" in slim)
        val names = listOf("deepseek-flash", "gpt-5.5", "openai/gpt-5.5")
        for (endpoint in listOf("https://api.deepseek.com/chat/completions", "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1")) {
            assertEquals(protocols.matchAll(endpoint, names), ModelProtocolCatalog.parse(slim).matchAll(endpoint, names))
        }
    }
}
