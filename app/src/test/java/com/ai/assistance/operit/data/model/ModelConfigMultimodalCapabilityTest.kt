package com.ai.assistance.operit.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigMultimodalCapabilityTest {
    @Test
    fun legacyConfigWithoutPerModelCapabilities_inheritsAllProviderLevelSettings() {
        val config =
            Json.decodeFromString<ModelConfigData>(
                """{"id":"legacy","name":"Legacy","modelName":"first,second","enableDirectImageProcessing":true,"enableDirectAudioProcessing":false,"enableDirectVideoProcessing":true}"""
            )

        assertTrue(config.modelMultimodalCapabilities.isEmpty())
        assertEquals(
            ModelMultimodalCapabilities(image = true, audio = false, video = true),
            config.multimodalCapabilitiesForModel(0),
        )
        assertEquals(
            ModelMultimodalCapabilities(image = true, audio = false, video = true),
            config.multimodalCapabilitiesForModel(1),
        )
    }

    @Test
    fun perModelCapabilities_allowEachMediaTypeToVaryIndependently() {
        val config =
            ModelConfigData(
                id = "mixed",
                name = "Mixed provider",
                modelName = "vision-only,audio-video",
                modelMultimodalCapabilities =
                    mapOf(
                        "vision-only" to ModelMultimodalCapabilities(image = true),
                        "audio-video" to
                            ModelMultimodalCapabilities(audio = true, video = true),
                    ),
            )

        assertEquals(
            ModelMultimodalCapabilities(image = true),
            config.multimodalCapabilitiesForModel(0),
        )
        assertEquals(
            ModelMultimodalCapabilities(audio = true, video = true),
            config.multimodalCapabilitiesForModel(1),
        )
    }

    @Test
    fun selectedModelConfig_exposesOnlyItsEffectiveCapabilities() {
        val config =
            ModelConfigData(
                id = "mixed",
                name = "Mixed provider",
                modelName = "text-only,multimodal",
                enableDirectImageProcessing = true,
                enableDirectAudioProcessing = true,
                enableDirectVideoProcessing = true,
                modelMultimodalCapabilities =
                    mapOf(
                        "text-only" to ModelMultimodalCapabilities(),
                        "multimodal" to
                            ModelMultimodalCapabilities(
                                image = true,
                                audio = true,
                                video = false,
                            ),
                    ),
            )

        val textConfig = config.forSelectedModel(0)
        val multimodalConfig = config.forSelectedModel(1)

        assertEquals("text-only", textConfig.modelName)
        assertFalse(textConfig.enableDirectImageProcessing)
        assertFalse(textConfig.enableDirectAudioProcessing)
        assertFalse(textConfig.enableDirectVideoProcessing)
        assertEquals("multimodal", multimodalConfig.modelName)
        assertTrue(multimodalConfig.enableDirectImageProcessing)
        assertTrue(multimodalConfig.enableDirectAudioProcessing)
        assertFalse(multimodalConfig.enableDirectVideoProcessing)
    }

    @Test
    fun invalidModelIndex_usesFirstModelsCapabilities() {
        val config =
            ModelConfigData(
                id = "mixed",
                name = "Mixed provider",
                modelName = "text-only,multimodal",
                modelMultimodalCapabilities =
                    mapOf("text-only" to ModelMultimodalCapabilities(audio = true)),
            )

        assertEquals(
            ModelMultimodalCapabilities(audio = true),
            config.multimodalCapabilitiesForModel(99),
        )
    }

    @Test
    fun missingModelDefaultsEveryCapabilityToDisabledAfterPerModelConfigExists() {
        val config =
            ModelConfigData(
                id = "mixed",
                name = "Mixed provider",
                modelName = "known,new-model",
                enableDirectImageProcessing = true,
                enableDirectAudioProcessing = true,
                enableDirectVideoProcessing = true,
                modelMultimodalCapabilities =
                    mapOf("known" to ModelMultimodalCapabilities(image = true)),
            )

        assertEquals(ModelMultimodalCapabilities(), config.multimodalCapabilitiesForModel(1))
    }

    @Test
    fun updatingModelList_materializesLegacyCapabilitiesAndDisablesNewModels() {
        val legacyConfig =
            ModelConfigData(
                id = "legacy",
                name = "Legacy provider",
                modelName = "existing",
                enableDirectImageProcessing = true,
                enableDirectAudioProcessing = false,
                enableDirectVideoProcessing = true,
            )

        val updated = legacyConfig.withModelNames("existing,new-model")

        assertEquals(
            mapOf(
                "existing" to
                    ModelMultimodalCapabilities(image = true, audio = false, video = true),
                "new-model" to ModelMultimodalCapabilities(),
            ),
            updated.modelMultimodalCapabilities,
        )
    }

    @Test
    fun updatingModelList_keepsMissingPartialEntryDisabled() {
        val partialConfig =
            ModelConfigData(
                id = "partial",
                name = "Partial provider",
                modelName = "known,missing",
                enableDirectImageProcessing = true,
                enableDirectAudioProcessing = true,
                enableDirectVideoProcessing = true,
                modelMultimodalCapabilities =
                    mapOf("known" to ModelMultimodalCapabilities(video = true)),
            )

        val updated = partialConfig.withModelNames("known,missing,new-model")

        assertEquals(
            mapOf(
                "known" to ModelMultimodalCapabilities(video = true),
                "missing" to ModelMultimodalCapabilities(),
                "new-model" to ModelMultimodalCapabilities(),
            ),
            updated.modelMultimodalCapabilities,
        )
    }

    @Test
    fun legacyGlobalUpdatesChangeOnlyTheirCapabilityAcrossConfiguredModels() {
        val config =
            ModelConfigData(
                id = "mixed",
                name = "Mixed provider",
                modelName = "first,second",
                modelMultimodalCapabilities =
                    mapOf(
                        "first" to ModelMultimodalCapabilities(image = true, audio = false),
                        "second" to ModelMultimodalCapabilities(image = false, audio = true),
                    ),
            )

        val updated = config.withDirectVideoProcessingForAllModels(true)

        assertEquals(
            ModelMultimodalCapabilities(image = true, audio = false, video = true),
            updated.multimodalCapabilitiesForModel(0),
        )
        assertEquals(
            ModelMultimodalCapabilities(image = false, audio = true, video = true),
            updated.multimodalCapabilitiesForModel(1),
        )
    }

    @Test
    fun sharedGroupMessage_usesUnionOfMemberCapabilities() {
        val union =
            listOf(
                ModelMultimodalCapabilities(image = true),
                ModelMultimodalCapabilities(audio = true),
                ModelMultimodalCapabilities(video = true),
            ).unionMultimodalCapabilities()

        assertEquals(
            ModelMultimodalCapabilities(image = true, audio = true, video = true),
            union,
        )
    }
}
