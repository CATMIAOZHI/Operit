package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterCardOrderTest {

    @Test
    fun reconcile_preservesSavedOrderAndAppendsNewCards() {
        val result = reconcileCharacterCardOrder(
            savedOrder = listOf("card-b", "card-a"),
            cardIds = listOf("card-a", "card-b", "card-c")
        )

        assertEquals(listOf("card-b", "card-a", "card-c"), result)
    }

    @Test
    fun reconcile_removesDeletedAndDuplicateIds() {
        val result = reconcileCharacterCardOrder(
            savedOrder = listOf("deleted", "card-b", "card-b", "card-a"),
            cardIds = listOf("card-a", "card-b")
        )

        assertEquals(listOf("card-b", "card-a"), result)
    }

    @Test
    fun reconcile_usesObservedOrderForLegacyData() {
        val result = reconcileCharacterCardOrder(
            savedOrder = emptyList(),
            cardIds = listOf("default_character", "card-a", "card-b")
        )

        assertEquals(listOf("default_character", "card-a", "card-b"), result)
    }

    @Test
    fun upsert_appendsBatchImportCardsInPayloadOrder() {
        val cardIds = mutableListOf("default_character")
        var order = emptyList<String>()

        listOf("import-b", "import-a").forEach { importedId ->
            order = reconcileCharacterCardOrderAfterUpsert(order, cardIds, importedId)
            cardIds += importedId
        }

        assertEquals(
            listOf("default_character", "import-b", "import-a"),
            order
        )
    }
}
