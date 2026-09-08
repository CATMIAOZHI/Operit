package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.google.gson.JsonParser

object SystemToolPromptsInternal {
    val internalToolCategoriesEn: List<SystemToolPromptCategory> by lazy { loadCategories("en") }
    val internalToolCategoriesCn: List<SystemToolPromptCategory> by lazy { loadCategories("zh") }

    private fun loadCategories(language: String): List<SystemToolPromptCategory> {
        val resource = "/operit/prompts/internal-tools-$language.json"
        val stream = checkNotNull(SystemToolPromptsInternal::class.java.getResourceAsStream(resource)) {
            "Missing bundled tool prompts: $resource"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            JsonParser.parseReader(reader).asJsonArray.map { categoryElement ->
                val category = categoryElement.asJsonObject
                SystemToolPromptCategory(
                    categoryName = category["categoryName"].asString,
                    categoryHeader = category["categoryHeader"].asString,
                    categoryFooter = category["categoryFooter"].asString,
                    tools = category["tools"].asJsonArray.map { toolElement ->
                        val tool = toolElement.asJsonObject
                        ToolPrompt(
                            name = tool["name"].asString,
                            description = tool["description"].asString,
                            parameters = tool["parameters"].asString,
                            details = tool["details"].asString,
                            notes = tool["notes"].asString,
                            parametersStructured = tool["parametersStructured"]
                                .takeUnless { it.isJsonNull }?.asJsonArray?.map { parameterElement ->
                                    val parameter = parameterElement.asJsonObject
                                    ToolParameterSchema(
                                        name = parameter["name"].asString,
                                        type = parameter["type"].asString,
                                        description = parameter["description"].asString,
                                        required = parameter["required"].asBoolean,
                                        default = parameter["default"].takeUnless { it.isJsonNull }?.asString,
                                    )
                                },
                        )
                    },
                )
            }
        }
    }
}
