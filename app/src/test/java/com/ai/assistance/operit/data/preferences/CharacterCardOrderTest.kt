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

    @Test
    fun reorderVisibleCards_preservesCollapsedCardSlots() {
        val result = mergeReorderedVisibleCharacterCardIds(
            currentOrder = listOf("card-a", "collapsed-b", "card-c", "collapsed-d"),
            reorderedVisibleIds = listOf("card-c", "card-a"),
            collapsedIds = setOf("collapsed-b", "collapsed-d")
        )

        assertEquals(
            listOf("card-c", "collapsed-b", "card-a", "collapsed-d"),
            result
        )
    }

    @Test
    fun backupImport_restoresPayloadOrderWhenCardsAlreadyExist() {
        val result = restoreImportedCharacterCardOrder(
            currentOrder = listOf("default_character", "system_rainy", "custom"),
            importedOrder = listOf("custom", "system_rainy", "default_character"),
            cardIds = setOf("default_character", "system_rainy", "custom")
        )

        assertEquals(
            listOf("custom", "system_rainy", "default_character"),
            result
        )
    }

    @Test
    fun collapseToggle_addsAndRemovesCharacterCardId() {
        val collapsed = toggleCollapsedCharacterCardId(emptySet(), "card-a")
        assertEquals(setOf("card-a"), collapsed)

        val expanded = toggleCollapsedCharacterCardId(collapsed, "card-a")
        assertEquals(emptySet<String>(), expanded)
    }

    @Test
    fun collapseToggle_ignoresBlankCharacterCardId() {
        assertEquals(
            setOf("card-a"),
            toggleCollapsedCharacterCardId(setOf("card-a"), "")
        )
    }
}
