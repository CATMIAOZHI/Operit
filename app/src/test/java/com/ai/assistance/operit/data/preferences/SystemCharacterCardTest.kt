package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCharacterCardTest {

    @Test
    fun systemCardIds_includeOperitAndRainyOnly() {
        assertTrue(
            CharacterCardManager.isSystemCharacterCard(
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
            )
        )
        assertTrue(
            CharacterCardManager.isSystemCharacterCard(
                CharacterCardManager.RAINY_CHARACTER_CARD_ID
            )
        )
        assertFalse(CharacterCardManager.isSystemCharacterCard("custom-card"))
    }

    @Test
    fun systemCards_haveTheirOwnBuiltInAvatars() {
        assertEquals(
            UserPreferencesManager.DEFAULT_CHARACTER_AVATAR_URI,
            UserPreferencesManager.getBuiltInCharacterAvatarUri(
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
            )
        )
        assertEquals(
            UserPreferencesManager.RAINY_CHARACTER_AVATAR_URI,
            UserPreferencesManager.getBuiltInCharacterAvatarUri(
                CharacterCardManager.RAINY_CHARACTER_CARD_ID
            )
        )
        assertEquals(
            null,
            UserPreferencesManager.getBuiltInCharacterAvatarUri("custom-card")
        )
    }

    @Test
    fun missingSystemAvatar_isInstalledWithoutOverwritingCustomization() {
        assertTrue(shouldInstallBuiltInSystemAvatar(null))
        assertTrue(shouldInstallBuiltInSystemAvatar(""))
        assertFalse(
            shouldInstallBuiltInSystemAvatar(
                "file:///data/user/0/example/files/avatar_system_rainy_custom.png"
            )
        )
    }

    @Test
    fun oldBuiltInDescriptions_areMigratedButCustomDescriptionsArePreserved() {
        val oldDescriptions = setOf(
            "系统默认的角色卡配置",
            "System default character card configuration"
        )
        val newDescription = "Operit是一个有帮助的AI助手"

        assertEquals(
            newDescription,
            migrateBuiltInDescription(
                "系统默认的角色卡配置",
                oldDescriptions,
                newDescription
            )
        )
        assertEquals(
            newDescription,
            migrateBuiltInDescription(
                "System default character card configuration",
                oldDescriptions,
                newDescription
            )
        )
        assertEquals(
            "我的自定义描述",
            migrateBuiltInDescription(
                "我的自定义描述",
                oldDescriptions,
                newDescription
            )
        )
        assertEquals(
            null,
            migrateBuiltInDescription(null, oldDescriptions, newDescription)
        )
    }

    @Test
    fun rainyBuiltInDescription_isMigratedButCustomizationIsPreserved() {
        val oldDescriptions = setOf(
            "Rainy是一位理性又活泼的AI小猫助手喵！",
            "Rainy is a rational yet lively AI kitten assistant!",
            "系统默认的角色卡",
            "System default character card"
        )
        val newDescription = "Rainy是一只可爱的粉色小猫，这是系统默认的角色卡"

        oldDescriptions.forEach { oldDescription ->
            assertEquals(
                newDescription,
                migrateBuiltInDescription(oldDescription, oldDescriptions, newDescription)
            )
        }
        assertEquals(
            "我的 Rainy 描述",
            migrateBuiltInDescription("我的 Rainy 描述", oldDescriptions, newDescription)
        )
    }

    @Test
    fun firstInitialization_placesRainyFirst() {
        val order = resolveSystemCharacterCardOrder(
            savedOrder = emptyList(),
            existingIds = emptyList(),
            placeRainyFirst = true
        )

        assertEquals(
            listOf(
                CharacterCardManager.RAINY_CHARACTER_CARD_ID,
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
            ),
            order
        )
    }

    @Test
    fun upgrade_preservesExistingOrderAndAppendsRainy() {
        val order = resolveSystemCharacterCardOrder(
            savedOrder = listOf(
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                "custom-card"
            ),
            existingIds = listOf(
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                "custom-card"
            ),
            placeRainyFirst = false
        )

        assertEquals(
            listOf(
                CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                "custom-card",
                CharacterCardManager.RAINY_CHARACTER_CARD_ID
            ),
            order
        )
    }

    @Test
    fun rainyIsSelectedOnlyWhenListAndActiveCardAreBothMissing() {
        assertTrue(
            shouldSelectRainyAsInitialCard(
                hasCharacterCardList = false,
                activeCharacterCardId = null
            )
        )
        assertFalse(
            shouldSelectRainyAsInitialCard(
                hasCharacterCardList = true,
                activeCharacterCardId = "existing-card"
            )
        )
        assertFalse(
            shouldSelectRainyAsInitialCard(
                hasCharacterCardList = false,
                activeCharacterCardId = "legacy-card"
            )
        )
    }
}
