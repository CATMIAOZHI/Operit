package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionalReasoningAIServiceTest {
    @Test
    fun legacyFunctionMappingDefaultsToTheSharedFiveLevelDefault() {
        val mapping =
            Json.decodeFromString<FunctionConfigMapping>(
                """{"configId":"summary","modelIndex":2}""",
            )

        assertEquals("summary", mapping.configId)
        assertEquals(2, mapping.modelIndex)
        assertEquals(ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL, mapping.thinkingQualityLevel)
    }

    @Test
    fun functionalLevelAlwaysClampsIntoTheFiveLevelRange() {
        assertEquals(1, normalizeFunctionalThinkingQualityLevel(0))
        assertEquals(3, normalizeFunctionalThinkingQualityLevel(3))
        assertEquals(5, normalizeFunctionalThinkingQualityLevel(9))
    }

    @Test
    fun gpt56MaxIsSentAsMax() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.OPENAI,
                modelName = "gpt-5.6-sol",
                modelParameters = emptyList(),
                thinkingQualityLevel = 5,
            )

        assertTrue(request.enableThinking)
        assertEquals("max", request.parameterValue("reasoning_effort"))
    }

    @Test
    fun gpt51FiveLevelIntentFallsBackToItsHighestAcceptedEffort() {
        listOf("gpt-5.1", "gpt-5.1-2025-11-13").forEach { modelName ->
            val request =
                buildFunctionalReasoningRequest(
                    providerType = ApiProviderType.OPENAI_RESPONSES,
                    modelName = modelName,
                    modelParameters = emptyList(),
                    thinkingQualityLevel = 5,
                )

            assertEquals("high", request.responsesEffort())
        }
    }

    @Test
    fun fixedGpt5ProUsesHighForEverySliderPosition() {
        listOf(1, 5).forEach { level ->
            val request =
                buildFunctionalReasoningRequest(
                    providerType = ApiProviderType.OPENAI_RESPONSES,
                    modelName = "gpt-5-pro",
                    modelParameters = emptyList(),
                    thinkingQualityLevel = level,
                )

            assertEquals("high", request.responsesEffort())
        }
    }

    @Test
    fun olderOpenAiMaxFallsBackToXHigh() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.OPENAI_RESPONSES,
                modelName = "gpt-5.4",
                modelParameters = emptyList(),
                thinkingQualityLevel = 5,
            )

        assertEquals("xhigh", request.responsesEffort())
    }

    @Test
    fun gpt54MiniMaxFallsBackToXHigh() {
        listOf("gpt-5.4-mini", "gpt-5.4-mini-2026-03-17").forEach { modelName ->
            val request =
                buildFunctionalReasoningRequest(
                    providerType = ApiProviderType.OPENAI_RESPONSES,
                    modelName = modelName,
                    modelParameters = emptyList(),
                    thinkingQualityLevel = 5,
                )

            assertTrue(request.enableThinking)
            assertEquals("xhigh", request.responsesEffort())
        }
    }

    @Test
    fun gpt53CodexMaxFallsBackToXHighAndPreservesResponsesReasoningFields() {
        listOf("gpt-5.3-codex", "gpt-5.3-codex-2026-08-01").forEach { modelName ->
            val chatRequest =
                buildFunctionalReasoningRequest(
                    providerType = ApiProviderType.OPENAI,
                    modelName = modelName,
                    modelParameters = emptyList(),
                    thinkingQualityLevel = 5,
                )
            val responsesRequest =
                buildFunctionalReasoningRequest(
                    providerType = ApiProviderType.OPENAI_RESPONSES,
                    modelName = modelName,
                    modelParameters =
                        listOf(
                            stringParameter(
                                "reasoning",
                                "{\"context\":\"all_turns\",\"summary\":\"detailed\"}",
                            )
                        ),
                    thinkingQualityLevel = 5,
                )
            val reasoning = JSONObject(responsesRequest.parameterValue("reasoning") as String)

            assertTrue(chatRequest.enableThinking)
            assertEquals("xhigh", chatRequest.parameterValue("reasoning_effort"))
            assertTrue(responsesRequest.enableThinking)
            assertEquals("xhigh", reasoning.getString("effort"))
            assertEquals("all_turns", reasoning.getString("context"))
            assertEquals("detailed", reasoning.getString("summary"))
        }
    }

    @Test
    fun nonReasoningOpenAiModelsDoNotReceiveReasoningControls() {
        listOf(ApiProviderType.OPENAI, ApiProviderType.OPENAI_RESPONSES).forEach { providerType ->
            val request =
                buildFunctionalReasoningRequest(
                    providerType = providerType,
                    modelName = "gpt-4.1",
                    modelParameters = listOf(stringParameter("reasoning_effort", "max")),
                    thinkingQualityLevel = 5,
                )

            assertFalse(request.enableThinking)
            assertTrue(
                request.modelParameters.none {
                    it.apiName == "reasoning_effort" || it.apiName == "reasoning"
                }
            )
            val consumed = consumeAutomaticReasoningSuppression(request.modelParameters)
            assertTrue(consumed.suppressAutomaticReasoning)
            assertTrue(consumed.modelParameters.isEmpty())
            assertFalse(supportsOpenAiReasoningEffortModel("gpt-4.1"))
        }
    }

    @Test
    fun customOpenAiTransportsDoNotReceiveInferredReasoningControls() {
        listOf(ApiProviderType.OPENAI_GENERIC, ApiProviderType.OPENAI_RESPONSES_GENERIC)
            .forEach { providerType ->
                val request =
                    buildFunctionalReasoningRequest(
                        providerType = providerType,
                        modelName = "gpt-5.4-mini",
                        modelParameters = emptyList(),
                        thinkingQualityLevel = 5,
                    )

                assertFalse(request.enableThinking)
                val consumed = consumeAutomaticReasoningSuppression(request.modelParameters)
                assertTrue(consumed.suppressAutomaticReasoning)
                assertTrue(consumed.modelParameters.isEmpty())
            }
    }

    @Test
    fun responsesEffortPreservesModeAndContext() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.OPENAI_RESPONSES,
                modelName = "gpt-5.6-terra",
                modelParameters =
                    listOf(
                        stringParameter(
                            "reasoning",
                            "{\"mode\":\"pro\",\"context\":\"all_turns\",\"effort\":\"low\"}",
                        )
                    ),
                thinkingQualityLevel = 5,
            )
        val reasoning = JSONObject(request.parameterValue("reasoning") as String)

        assertEquals("pro", reasoning.getString("mode"))
        assertEquals("all_turns", reasoning.getString("context"))
        assertEquals("max", reasoning.getString("effort"))
    }

    @Test
    fun deepSeekAdaptsAllFiveLevelsToItsOfficialEffortValues() {
        val expectedByLevel = mapOf(1 to "low", 2 to "high", 3 to "high", 4 to "max", 5 to "max")

        expectedByLevel.forEach { (level, expectedEffort) ->
            listOf("deepseek-v4-flash", "deepseek-v4-pro").forEach { modelName ->
                val request =
                    buildFunctionalReasoningRequest(
                        providerType = ApiProviderType.DEEPSEEK,
                        modelName = modelName,
                        modelParameters = emptyList(),
                        thinkingQualityLevel = level,
                    )

                assertEquals(expectedEffort, request.parameterValue("reasoning_effort"))
            }
        }
        assertEquals("high", DeepseekProvider.normalizeDeepseekEffort("medium"))
        assertEquals("max", DeepseekProvider.normalizeDeepseekEffort("xhigh"))
    }

    @Test
    fun unknownDeepSeekModelsUseProviderDefaultWithoutAnEffortOverride() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.DEEPSEEK,
                modelName = "future-deepseek-model",
                modelParameters = emptyList(),
                thinkingQualityLevel = 5,
            )

        assertTrue(request.enableThinking)
        assertTrue(request.modelParameters.none { it.apiName == "reasoning_effort" })
        val consumed = consumeAutomaticReasoningSuppression(request.modelParameters)
        assertTrue(consumed.suppressAutomaticReasoning)
        assertTrue(consumed.modelParameters.isEmpty())
    }

    @Test
    fun nvidiaAdaptsTheFiveLevelIntentToItsHighestAcceptedEffort() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.NVIDIA,
                modelName = "openai/gpt-oss-120b",
                modelParameters = emptyList(),
                thinkingQualityLevel = 5,
            )

        assertEquals("high", request.parameterValue("reasoning_effort"))
    }

    @Test
    fun nvidiaNonGptOssModelsDoNotReceiveReasoningEffort() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.NVIDIA,
                modelName = "nvidia/llama-3.3-nemotron-super-49b-v1.5",
                modelParameters = listOf(stringParameter("reasoning_effort", "max")),
                thinkingQualityLevel = 5,
            )

        assertTrue(request.enableThinking)
        assertTrue(request.modelParameters.none { it.apiName == "reasoning_effort" })
    }

    @Test
    fun openRouterReceivesTheSelectedEffort() {
        val request =
            buildFunctionalReasoningRequest(
                providerType = ApiProviderType.OPENROUTER,
                modelName = "openrouter/auto",
                modelParameters = emptyList(),
                thinkingQualityLevel = 5,
            )
        val reasoning = JSONObject(request.parameterValue("reasoning") as String)

        assertEquals("max", reasoning.getString("effort"))
    }

    @Test
    fun siliconFlowMapsAllFiveLevelsToBudgets() {
        val low =
            buildFunctionalReasoningRequest(
                ApiProviderType.SILICONFLOW,
                "Qwen/Qwen3-32B",
                emptyList(),
                1,
            )
        val max =
            buildFunctionalReasoningRequest(
                ApiProviderType.SILICONFLOW,
                "Qwen/Qwen3-32B",
                emptyList(),
                5,
            )

        assertEquals(1_024, low.parameterValue("thinking_budget"))
        assertEquals(32_768, max.parameterValue("thinking_budget"))
        assertEquals(true, max.parameterValue("enable_thinking"))
    }

    @Test
    fun unsupportedSiliconFlowModelsDoNotReceiveThinkingControls() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.SILICONFLOW,
                "meta-llama/llama-3.3-70b-instruct",
                emptyList(),
                5,
            )

        assertFalse(request.enableThinking)
        assertTrue(
            request.modelParameters.none {
                it.apiName == "enable_thinking" || it.apiName == "thinking_budget"
            }
        )
    }

    @Test
    fun tokenBudgetIsCappedBelowMaxTokens() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.SILICONFLOW,
                "Qwen/Qwen3-32B",
                listOf(intParameter("max_tokens", 8_000)),
                5,
            )

        assertEquals(7_999, request.parameterValue("thinking_budget"))
    }

    @Test
    fun aliyunFiveLevelsProduceDistinctThinkingBudgets() {
        val low =
            buildFunctionalReasoningRequest(
                ApiProviderType.ALIYUN,
                "qwen-plus",
                emptyList(),
                1,
            )
        val max =
            buildFunctionalReasoningRequest(
                ApiProviderType.ALIYUN,
                "qwen-plus",
                emptyList(),
                5,
            )

        assertTrue(max.enableThinking)
        assertEquals(true, max.parameterValue("enable_thinking"))
        assertEquals(1_024, low.parameterValue("thinking_budget"))
        assertEquals(32_768, max.parameterValue("thinking_budget"))
    }

    @Test
    fun unsupportedAliyunVisionAndUnknownModelsDoNotReceiveThinkingControls() {
        listOf(
                "qwen-vl-plus",
                "qwen-max",
                "qwen-max-2025-01-25",
                "qwen-plus-character",
                "qwen-plus-character-ja",
                "qwen-flash-character",
                "qwen3-coder-plus",
                "qwen3-vl-plus",
                "qwen3.5-omni-plus",
                "qwen3-max-2025-09-23",
                "qwen3-max-2099-01-01",
                "qwen3.7-flash-us",
                "qwen3.7-plus-preview",
                "qwen3.7-plus-2099-01-01",
                "qwen-plus-2025-04-27",
                "qwen-flash-2025-07-27",
                "future-aliyun-model",
            )
            .forEach { modelName ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ALIYUN,
                    modelName,
                    emptyList(),
                    5,
                )

            assertFalse(request.enableThinking)
            assertTrue(
                request.modelParameters.none {
                    it.apiName == "enable_thinking" ||
                        it.apiName == "thinking_budget" ||
                        it.apiName == "reasoning_effort"
                }
            )
        }
    }

    @Test
    fun supportedAliyunQwenAliasesAndSnapshotsReceiveThinkingBudgets() {
        listOf(
                "qwen-plus",
                "qwen-plus-latest",
                "qwen-plus-2025-04-28",
                "qwen-flash",
                "qwen-flash-2025-07-28",
                "qwen-turbo",
                "qwen-turbo-2025-02-11",
                "qwen3-max",
                "qwen3.5-plus-2026-02-15",
                "qwen3.6-flash-2026-04-16",
                "qwen3.7-max-us",
                "qwen3.8-max",
                "qwen3-32b",
                "qwq-plus",
            )
            .forEach { modelName ->
                val request =
                    buildFunctionalReasoningRequest(
                        ApiProviderType.ALIYUN,
                        modelName,
                        emptyList(),
                        5,
                    )

                assertTrue(modelName, request.enableThinking)
                assertEquals(modelName, true, request.parameterValue("enable_thinking"))
                assertEquals(modelName, 32_768, request.parameterValue("thinking_budget"))
            }
    }

    @Test
    fun aliyunQwenRegularAndPreviewUseTheirDocumentedControlPaths() {
        val qwenRegular =
            buildFunctionalReasoningRequest(
                ApiProviderType.ALIYUN,
                "qwen3.8-max",
                emptyList(),
                5,
            )
        val qwenPreview =
            buildFunctionalReasoningRequest(
                ApiProviderType.ALIYUN,
                "qwen3.8-max-preview",
                emptyList(),
                5,
            )

        assertEquals(32_768, qwenRegular.parameterValue("thinking_budget"))
        assertTrue(qwenRegular.modelParameters.none { it.apiName == "reasoning_effort" })
        assertEquals("max", qwenPreview.parameterValue("reasoning_effort"))
        assertTrue(qwenPreview.modelParameters.none { it.apiName == "thinking_budget" })
    }

    @Test
    fun aliyunGlmMaxFallsBackOnlyWhereRequired() {
        val expectedByModel =
            mapOf(
                "glm-5" to "xhigh",
                "glm-5.1" to "xhigh",
                "glm-5.2" to "max",
            )

        expectedByModel.forEach { (modelName, expectedEffort) ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ALIYUN,
                    modelName,
                    emptyList(),
                    5,
                )
            assertEquals(expectedEffort, request.parameterValue("reasoning_effort"))
        }
    }

    @Test
    fun adaptiveClaudeReceivesTheSelectedEffort() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-6",
                emptyList(),
                5,
            )
        val outputConfig = JSONObject(request.parameterValue("output_config") as String)

        assertEquals("max", outputConfig.getString("effort"))
    }

    @Test
    fun adaptiveClaudeWithoutXHighSupportFallsBackToMax() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-6",
                emptyList(),
                4,
            )
        val outputConfig = JSONObject(request.parameterValue("output_config") as String)

        assertEquals("max", outputConfig.getString("effort"))
    }

    @Test
    fun opus5SupportsAllFiveAdaptiveEffortLevels() {
        val expectedEfforts = listOf("low", "medium", "high", "xhigh", "max")

        listOf("claude-opus-5", "claude-opus-5-0-20260801").forEach { modelName ->
            expectedEfforts.forEachIndexed { index, expectedEffort ->
                val request =
                    buildFunctionalReasoningRequest(
                        ApiProviderType.ANTHROPIC,
                        modelName,
                        emptyList(),
                        index + 1,
                    )
                val thinking = JSONObject(request.parameterValue("thinking") as String)
                val outputConfig = JSONObject(request.parameterValue("output_config") as String)

                assertTrue(request.enableThinking)
                assertEquals("adaptive", thinking.getString("type"))
                assertEquals(expectedEffort, outputConfig.getString("effort"))
            }
        }
    }

    @Test
    fun adaptiveClaudeEffortPreservesOtherOutputConfigFields() {
        val originalOutputConfig =
            stringParameter(
                "output_config",
                "{\"format\":{\"type\":\"json_schema\"},\"task_budget\":42,\"effort\":\"low\"}",
            )
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-6",
                listOf(originalOutputConfig),
                5,
            )
        val outputConfig = JSONObject(request.parameterValue("output_config") as String)

        assertEquals("json_schema", outputConfig.getJSONObject("format").getString("type"))
        assertEquals(42, outputConfig.getInt("task_budget"))
        assertEquals("max", outputConfig.getString("effort"))
    }

    @Test
    fun adaptiveClaudePreservesUnrelatedThinkingFields() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-6",
                listOf(
                    stringParameter(
                        "thinking",
                        "{\"type\":\"enabled\",\"budget_tokens\":2048,\"display\":\"summarized\"}",
                    )
                ),
                5,
            )
        val thinking = JSONObject(request.parameterValue("thinking") as String)

        assertEquals("adaptive", thinking.getString("type"))
        assertEquals("summarized", thinking.getString("display"))
        assertFalse(thinking.has("budget_tokens"))
    }

    @Test
    fun mythosPreviewUsesAdaptiveThinkingAndFallsBackFromXHighToMax() {
        listOf(4, 5).forEach { level ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ANTHROPIC,
                    "claude-mythos-preview",
                    listOf(
                        stringParameter(
                            "thinking",
                            "{\"type\":\"enabled\",\"display\":\"summarized\"}",
                        ),
                        stringParameter(
                            "output_config",
                            "{\"format\":{\"type\":\"json_schema\"},\"task_budget\":42}",
                        ),
                        intParameter("temperature", 0),
                        intParameter("top_p", 1),
                        intParameter("top_k", 50),
                    ),
                    level,
                )
            val thinking = JSONObject(request.parameterValue("thinking") as String)
            val outputConfig = JSONObject(request.parameterValue("output_config") as String)

            assertTrue(request.enableThinking)
            assertEquals("adaptive", thinking.getString("type"))
            assertEquals("summarized", thinking.getString("display"))
            assertEquals("max", outputConfig.getString("effort"))
            assertEquals("json_schema", outputConfig.getJSONObject("format").getString("type"))
            assertEquals(42, outputConfig.getInt("task_budget"))
            assertTrue(
                request.modelParameters.none {
                    it.apiName == "temperature" ||
                        it.apiName == "top_p" ||
                        it.apiName == "top_k"
                }
            )
        }
    }

    @Test
    fun manualClaudeReceivesAFiveLevelBudget() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-5",
                emptyList(),
                4,
            )

        val thinking = JSONObject(request.parameterValue("thinking") as String)

        assertEquals("enabled", thinking.getString("type"))
        assertEquals(16_384, thinking.getInt("budget_tokens"))
    }

    @Test
    fun manualClaudePreservesOutputConfigAndUnrelatedThinkingFields() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-sonnet-4-5",
                listOf(
                    stringParameter(
                        "thinking",
                        "{\"type\":\"adaptive\",\"display\":\"summarized\"}",
                    ),
                    stringParameter(
                        "output_config",
                        "{\"format\":{\"type\":\"json_schema\"},\"task_budget\":42}",
                    ),
                    intParameter("temperature", 0),
                    intParameter("top_p", 1),
                    intParameter("top_k", 50),
                ),
                4,
            )
        val thinking = JSONObject(request.parameterValue("thinking") as String)
        val outputConfig = JSONObject(request.parameterValue("output_config") as String)

        assertEquals("enabled", thinking.getString("type"))
        assertEquals("summarized", thinking.getString("display"))
        assertEquals(16_384, thinking.getInt("budget_tokens"))
        assertEquals("json_schema", outputConfig.getJSONObject("format").getString("type"))
        assertEquals(42, outputConfig.getInt("task_budget"))
        assertTrue(
            request.modelParameters.none {
                it.apiName == "temperature" ||
                    it.apiName == "top_p" ||
                    it.apiName == "top_k"
            }
        )
    }

    @Test
    fun opus45OverridesEffortWithoutDroppingOtherOutputConfigFields() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.ANTHROPIC,
                "claude-opus-4-5",
                listOf(
                    stringParameter(
                        "output_config",
                        "{\"format\":{\"type\":\"json_schema\"},\"task_budget\":42,\"effort\":\"low\"}",
                    )
                ),
                5,
            )
        val outputConfig = JSONObject(request.parameterValue("output_config") as String)

        assertEquals("json_schema", outputConfig.getJSONObject("format").getString("type"))
        assertEquals(42, outputConfig.getInt("task_budget"))
        assertEquals("high", outputConfig.getString("effort"))
    }

    @Test
    fun manualClaudeBudgetUsesTheSameEffectiveMaxTokensAsTheFinalPayload() {
        val expectedByModel =
            mapOf(
                "claude-sonnet-4-5" to (64_000 to 32_768),
                "claude-opus-4-5" to (64_000 to 32_768),
                "claude-haiku-4-5" to (64_000 to 32_768),
            )

        expectedByModel.forEach { (modelName, expected) ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ANTHROPIC,
                    modelName,
                    listOf(intParameter("max_tokens", 4_096, isEnabled = false)),
                    5,
                )
            val finalRequestJson = JSONObject()
            val finalMaxTokens =
                applyClaudeEffectiveMaxTokensParameter(
                    requestJson = finalRequestJson,
                    providerType = ApiProviderType.ANTHROPIC,
                    modelName = modelName,
                    modelParameters = request.modelParameters,
                )
            assertTrue(
                applyCallerSuppliedClaudeThinkingParameters(
                    finalRequestJson,
                    request.modelParameters,
                )
            )
            val finalBudget =
                finalRequestJson.getJSONObject("thinking").getInt("budget_tokens")

            assertEquals(expected.first, finalMaxTokens)
            assertEquals(expected.first, finalRequestJson.getInt("max_tokens"))
            assertEquals(expected.second, finalBudget)
            assertTrue(finalBudget < finalMaxTokens)
        }
    }

    @Test
    fun currentAdaptiveClaudeModelsUseTheirFullOfficialOutputLimit() {
        listOf(
                "claude-fable-5",
                "claude-mythos-5",
                "claude-mythos-preview",
                "claude-opus-5",
                "claude-opus-4-6",
                "claude-opus-4-7",
                "claude-opus-4-8",
                "claude-sonnet-5",
                "claude-sonnet-4-6",
            )
            .forEach { modelName ->
                val request =
                    buildFunctionalReasoningRequest(
                        ApiProviderType.ANTHROPIC,
                        modelName,
                        listOf(intParameter("max_tokens", 4_096, isEnabled = false)),
                        5,
                    )
                val finalRequestJson = JSONObject()
                val finalMaxTokens =
                    applyClaudeEffectiveMaxTokensParameter(
                        requestJson = finalRequestJson,
                        providerType = ApiProviderType.ANTHROPIC,
                        modelName = modelName,
                        modelParameters = request.modelParameters,
                    )
                assertTrue(
                    applyCallerSuppliedClaudeThinkingParameters(
                        finalRequestJson,
                        request.modelParameters,
                    )
                )

                assertEquals(128_000, finalMaxTokens)
                assertEquals(128_000, finalRequestJson.getInt("max_tokens"))
                assertEquals("adaptive", finalRequestJson.getJSONObject("thinking").getString("type"))
            }
    }

    @Test
    fun legacyAndUnknownClaudeModelsDoNotReceiveInferredThinkingControls() {
        listOf(
                "claude-3-haiku-20240307",
                "future-claude-model",
                "claude-sonnet-4-99",
                "claude-opus-9-0",
                "claude-fable-6-0",
            )
            .forEach { modelName ->
            val temperature = intParameter("temperature", 0)
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ANTHROPIC_GENERIC,
                    modelName,
                    listOf(temperature),
                    5,
                )

            assertFalse(request.enableThinking)
            assertEquals(
                temperature,
                request.modelParameters.single { it.apiName == "temperature" },
            )
            assertTrue(
                request.modelParameters.none {
                    it.apiName == "thinking" || it.apiName == "budget_tokens"
                }
            )
        }
    }

    @Test
    fun manualClaudeDisablesThinkingWhenMaxTokensCannotFitAValidBudget() {
        listOf(512, 1_024).forEach { maxTokens ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.ANTHROPIC,
                    "claude-sonnet-4-5",
                    listOf(intParameter("max_tokens", maxTokens)),
                    5,
                )

            assertFalse(request.enableThinking)
            assertTrue(request.modelParameters.none { it.apiName == "budget_tokens" })
            assertTrue(request.modelParameters.none { it.apiName == "thinking" })
        }
    }

    @Test
    fun malformedClaudeObjectsAreIgnoredWithoutCrashing() {
        val requestJson = JSONObject()
        val hasThinking =
            applyCallerSuppliedClaudeThinkingParameters(
                requestJson,
                listOf(
                    stringParameter("thinking", "not-json"),
                    stringParameter("output_config", "{"),
                ),
            )

        assertFalse(hasThinking)
        assertFalse(requestJson.has("thinking"))
        assertFalse(requestJson.has("output_config"))
    }

    @Test
    fun gemini25MapsTheFiveLevelsToBudgets() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.GOOGLE,
                "gemini-2.5-pro",
                emptyList(),
                5,
            )
        val thinkingConfig = buildGeminiThinkingConfig(request.enableThinking, request.modelParameters)

        assertEquals(32_768, thinkingConfig?.getInt("thinkingBudget"))
    }

    @Test
    fun gemini3MaxFallsBackToItsHighestThinkingLevel() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.GOOGLE,
                "gemini-3.1-pro-preview",
                emptyList(),
                5,
            )
        val thinkingConfig = buildGeminiThinkingConfig(request.enableThinking, request.modelParameters)

        assertEquals("high", thinkingConfig?.getString("thinkingLevel"))
    }

    @Test
    fun gemini3ProMapsMediumSliderPositionToAnAcceptedLevel() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.GOOGLE,
                "gemini-3-pro-preview",
                emptyList(),
                3,
            )
        val thinkingConfig = buildGeminiThinkingConfig(request.enableThinking, request.modelParameters)

        assertEquals("high", thinkingConfig?.getString("thinkingLevel"))
    }

    @Test
    fun gemini31ImageModelsMapAllFiveLevelsToMinimalOrHigh() {
        val expectedByLevel =
            mapOf(1 to "minimal", 2 to "high", 3 to "high", 4 to "high", 5 to "high")

        listOf("gemini-3.1-flash-image", "gemini-3.1-flash-lite-image")
            .forEach { modelName ->
                expectedByLevel.forEach { (level, expectedThinkingLevel) ->
                    val request =
                        buildFunctionalReasoningRequest(
                            ApiProviderType.GOOGLE,
                            modelName,
                            emptyList(),
                            level,
                        )
                    val thinkingConfig =
                        buildGeminiThinkingConfig(
                            request.enableThinking,
                            request.modelParameters,
                        )

                    assertEquals(
                        expectedThinkingLevel,
                        thinkingConfig?.getString("thinkingLevel"),
                    )
                }
            }
    }

    @Test
    fun unsupportedGeminiModelsFallBackWithoutThinkingConfig() {
        listOf(
                "gemini-1.5-pro",
                "gemini-2.0-flash",
                "gemini-2.5-flash-image",
                "gemini-2.5-flash-image-preview",
                "gemini-3-pro-image-preview",
                "gemini-3.2-flash-image",
                "gemini-3.1-flash-lite-image-preview",
                "gemini-3.5-flash-preview",
                "gemini-3.5-flash-lite-preview",
                "gemini-3.6-flash-preview",
                "future-gemini-model",
            )
            .forEach { modelName ->
                val request =
                    buildFunctionalReasoningRequest(
                        ApiProviderType.GEMINI_GENERIC,
                        modelName,
                        listOf(stringParameter("thinking_level", "high")),
                        5,
                    )

                assertFalse(request.enableThinking)
                assertTrue(request.modelParameters.none { it.apiName.startsWith("thinking_") })
                assertEquals(
                    null,
                    buildGeminiThinkingConfig(request.enableThinking, request.modelParameters),
                )
            }
    }

    @Test
    fun stableGemini31FlashLiteMapsTheFiveLevelsToSupportedValues() {
        val expectedByLevel =
            mapOf(1 to "minimal", 2 to "low", 3 to "medium", 4 to "high", 5 to "high")

        expectedByLevel.forEach { (level, expectedThinkingLevel) ->
            val request =
                buildFunctionalReasoningRequest(
                    ApiProviderType.GOOGLE,
                    "gemini-3.1-flash-lite",
                    emptyList(),
                    level,
                )
            val thinkingConfig =
                buildGeminiThinkingConfig(request.enableThinking, request.modelParameters)

            assertEquals(expectedThinkingLevel, thinkingConfig?.getString("thinkingLevel"))
        }
    }

    @Test
    fun openRouterEffortPreservesExcludeAndRemovesConflictingTokenBudget() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.OPENROUTER,
                "openrouter/auto",
                listOf(
                    stringParameter(
                        "reasoning",
                        "{\"effort\":\"low\",\"max_tokens\":2048,\"exclude\":true}",
                    )
                ),
                5,
            )
        val reasoning = JSONObject(request.parameterValue("reasoning") as String)

        assertEquals("max", reasoning.getString("effort"))
        assertTrue(reasoning.getBoolean("exclude"))
        assertFalse(reasoning.has("max_tokens"))
    }

    @Test
    fun functionalChoiceOverridesAnExplicitModelReasoningParameter() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.OPENAI,
                "gpt-5.6-luna",
                listOf(stringParameter("reasoning_effort", "low")),
                5,
            )

        assertEquals(1, request.modelParameters.count { it.apiName == "reasoning_effort" })
        assertEquals("max", request.parameterValue("reasoning_effort"))
    }

    @Test
    fun openAiCompatibleFallbackUsesProviderDefaultWithoutUnknownRequestFields() {
        val temperature = intParameter("temperature", 1)
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.OPENAI_LOCAL,
                "local-model",
                listOf(temperature),
                5,
            )

        assertTrue(request.enableThinking)
        val consumed = consumeAutomaticReasoningSuppression(request.modelParameters)
        assertFalse(consumed.suppressAutomaticReasoning)
        assertEquals(temperature, consumed.modelParameters.single { it.apiName == "temperature" })
        assertTrue(request.modelParameters.none { it.apiName == "reasoning_effort" })
    }

    @Test
    fun automaticReasoningSuppressionIsInternalAndConsumedBeforeSerialization() {
        val request =
            buildFunctionalReasoningRequest(
                ApiProviderType.OPENAI_GENERIC,
                "custom-model",
                listOf(intParameter("temperature", 1)),
                5,
            )
        val consumed = consumeAutomaticReasoningSuppression(request.modelParameters)

        assertTrue(consumed.suppressAutomaticReasoning)
        assertEquals(listOf("temperature"), consumed.modelParameters.map { it.apiName })
        assertTrue(
            consumed.modelParameters.none {
                it.apiName == SUPPRESS_AUTOMATIC_REASONING_API_NAME
            }
        )
        assertTrue(supportsAutomaticOpenAiChatReasoning(ApiProviderType.OPENAI))
        assertTrue(supportsAutomaticOpenAiChatReasoning(ApiProviderType.OPENAI_GENERIC))
    }

    @Test
    fun localEnginesUseSafeThinkingToggleFallbackWithoutUnknownRequestFields() {
        listOf(ApiProviderType.MNN, ApiProviderType.LLAMA_CPP).forEach { providerType ->
            val request =
                buildFunctionalReasoningRequest(
                    providerType,
                    "local-model",
                    emptyList(),
                    5,
                )

            assertTrue(request.enableThinking)
            assertTrue(request.modelParameters.isEmpty())
        }
    }

    private fun FunctionalReasoningRequest.parameterValue(apiName: String): Any? =
        modelParameters.single { it.apiName == apiName }.currentValue

    private fun FunctionalReasoningRequest.responsesEffort(): String =
        JSONObject(parameterValue("reasoning") as String).getString("effort")

    private fun stringParameter(apiName: String, value: String): ModelParameter<String> =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.STRING,
        )

    private fun intParameter(
        apiName: String,
        value: Int,
        isEnabled: Boolean = true,
    ): ModelParameter<Int> =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = isEnabled,
            valueType = ParameterValueType.INT,
        )
}
