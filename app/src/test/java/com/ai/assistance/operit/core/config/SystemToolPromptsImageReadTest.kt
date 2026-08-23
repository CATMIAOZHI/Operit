package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemToolPromptsImageReadTest {
    @Test
    fun visionModelOnlyNeedsToCallReadFile() {
        val tool =
            readFileTool(
                SystemToolPrompts.getAIAllCategoriesEn(
                    chatModelHasDirectImage = true,
                ),
            )

        assertEquals(listOf("path", "environment"), tool.parametersStructured.orEmpty().map { it.name })
        assertTrue(tool.description.contains("only when this tool is called"))
        assertTrue(tool.description.contains("current vision-capable model"))
    }

    @Test
    fun textModelCanProvideIntentToConfiguredRecognitionModel() {
        val tool =
            readFileTool(
                SystemToolPrompts.getAIAllCategoriesEn(
                    hasBackendImageRecognition = true,
                    chatModelHasDirectImage = false,
                ),
            )

        assertEquals(listOf("path", "environment", "intent"), tool.parametersStructured.orEmpty().map { it.name })
        assertTrue(tool.description.contains("configured image-recognition model"))
        assertFalse(tool.parametersStructured.orEmpty().any { it.name == "direct_image" })
    }

    @Test
    fun chinesePromptDescribesTheSameOnDemandVisionRoute() {
        val tool =
            readFileTool(
                SystemToolPrompts.getAIAllCategoriesCn(
                    chatModelHasDirectImage = true,
                ),
            )

        assertTrue(tool.description.contains("仅在调用此工具时加载"))
        assertFalse(tool.parametersStructured.orEmpty().any { it.name == "direct_image" })
    }

    private fun readFileTool(categories: List<SystemToolPromptCategory>): ToolPrompt =
        categories.flatMap { it.tools }.single { it.name == "read_file" }
}
