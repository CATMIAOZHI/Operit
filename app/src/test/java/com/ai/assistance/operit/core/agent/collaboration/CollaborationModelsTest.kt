package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.data.model.ModelConfigSummary
import org.junit.Assert.*
import org.junit.Test

class CollaborationModelsTest {
    private fun config(id: String, name: String = "Work", models: String = "alpha,beta", provider: String = "P") =
        ModelConfigSummary(id = id, name = name, modelName = models, apiProviderTypeId = provider)

    @Test fun eachModelIsSelectableIncludingNonFirstModel() {
        val configs = listOf(config("internal"))
        assertEquals(listOf("Work / alpha", "Work / beta"), CollaborationModels.choices(configs).map { it.selector })
        val chosen = CollaborationModels.resolve("Work / beta", configs)
        assertEquals("internal", chosen.configId)
        assertEquals(1, chosen.modelIndex)
    }

    @Test fun sameNameAcrossProvidersIsExplicitAndNeverPicksFirst() {
        val configs = listOf(config("a", provider = "P"), config("b", provider = "Q"))
        assertThrows(IllegalArgumentException::class.java) { CollaborationModels.resolve("Work / alpha", configs) }
        assertEquals("b", CollaborationModels.resolve("Work / alpha [Q]", configs).configId)
    }

    @Test fun identicalDisplayNamesAndRepeatedModelsRemainIndividuallySelectable() {
        val configs = listOf(config("a", models = "alpha,alpha"), config("b", models = "alpha"))
        val choices = CollaborationModels.choices(configs)
        assertEquals(3, choices.map { it.selector }.distinct().size)
        choices.forEach { assertEquals(it, CollaborationModels.resolve(it.selector, configs)) }
        assertEquals(choices.toSet(), CollaborationModels.choices(configs.reversed()).toSet())
    }

    @Test fun removedOrRenamedChoicesFailWithoutSilentlyFallingBack() {
        assertTrue(CollaborationModels.choices(listOf(config("empty", models = " , "))).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CollaborationModels.resolve("Work / beta", listOf(config("a", models = "alpha"))) }
        assertThrows(IllegalArgumentException::class.java) { CollaborationModels.resolve("internal", listOf(config("internal"))) }
    }

    @Test fun whitespaceAndBlankConfigurationNamesProduceCopyableSelectors() {
        val configs = listOf(config("a", name = " Work "), config("b", name = "   "))
        assertEquals(listOf("Work / alpha", "Work / beta", "P / alpha", "P / beta"),
            CollaborationModels.choices(configs).map { it.selector })
        CollaborationModels.choices(configs).forEach {
            assertEquals(it, CollaborationModels.resolve(it.selector.trim(), configs))
        }
        val duplicates = listOf(config("a", name = "Work"), config("b", name = " Work "))
        CollaborationModels.choices(duplicates).forEach {
            assertEquals(it, CollaborationModels.resolve(it.selector.trim(), duplicates))
        }
    }

    @Test fun catalogToolTakesNoArgumentsAndPromptContainsNoModelCatalog() {
        CollaborationArguments.validate("list_agent_models", emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            CollaborationArguments.validate("list_agent_models", listOf("model"))
        }
        for (chinese in listOf(false, true)) {
            val category = CollaborationTools.category(chinese, emptyList())
            assertEquals(1, category.tools.count { it.name == "list_agent_models" })
            val description = category.tools.single { it.name == "spawn_agent" }
                .parametersStructured.orEmpty().single { it.name == "model" }.description
            assertTrue(description.contains("list_agent_models"))
            assertFalse(description.contains("Work / alpha"))
        }
    }
}
