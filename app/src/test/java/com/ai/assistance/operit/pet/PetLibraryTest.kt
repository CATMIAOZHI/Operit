package com.ai.assistance.operit.pet

import org.junit.Assert.*
import org.junit.Test

class PetLibraryTest {
    @Test fun allMediaTypesRetainIndependentAppearanceAndDockAcrossRestart() {
        val pets = PetMediaType.entries.mapIndexed { index, type ->
            PetProfile("pet-$index", PetSettings(
                name = "宠物 $index", mediaType = type,
                animations = PetAnimation.entries.associateWith { PetArtwork("asset-$index-${it.name}", "${it.name}.file") },
                sizeDp = 48f + index * 20f, opacity = 0.3f + index * 0.1f,
                edge = PetEdge.entries[index], x = index * 0.2f, y = index * 0.3f,
                animated = index % 2 == 0, showBubble = index % 2 != 0,
            ))
        }
        assertEquals(pets, decodePetLibrary(encodePetLibrary(pets)))
    }

    @Test fun identicalArtworkNamesDoNotMergeDifferentPets() {
        val original = listOf(
            PetProfile("first", PetSettings(name = "same")),
            PetProfile("second", PetSettings(name = "same", sizeDp = 120f)),
        )
        val restored = decodePetLibrary(encodePetLibrary(original))
        assertEquals(listOf("first", "second"), restored.map { it.id })
        assertEquals(80f, restored[0].settings.sizeDp, 0f)
        assertEquals(120f, restored[1].settings.sizeDp, 0f)
    }
    @Test fun previousSingleArtworkBecomesRestingAnimationWithoutLosingSelectionIdentity() {
        val json = """[{"id":"existing-gif","animated":true,"x":0.4,"y":1,"size":80,"opacity":1,"bubble":true,"edge":"BOTTOM","assetId":"existing-file","assetName":"Existing pet","mediaType":"GIF"}]"""
        val pet = decodePetLibrary(json).single()
        assertEquals("existing-gif", pet.id)
        assertEquals("Existing pet", pet.settings.name)
        assertEquals(PetArtwork("existing-file", "Existing pet"), pet.settings.animations[PetAnimation.IDLE])
        assertEquals(PetEdge.BOTTOM, pet.settings.edge)
        assertTrue(pet.settings.isReady)
    }

    @Test fun unsetStatesUseRestingFileWhileConfiguredStatesKeepTheirOwnArtwork() {
        val resting = PetArtwork("rest", "rest.gif")
        val thinking = PetArtwork("think", "think.gif")
        val empty = PetSettings(mediaType = PetMediaType.GIF)
        assertFalse(empty.isReady)
        val configured = empty.copy(animations = mapOf(PetAnimation.IDLE to resting, PetAnimation.THINKING to thinking))
        assertTrue(configured.isReady)
        assertEquals(thinking, configured.artwork(PetActivity.THINKING.animation()))
        assertEquals(resting, configured.artwork(PetActivity.TOOL.animation()))
        assertEquals(resting, configured.artwork(PetActivity.ERROR.animation()))
        assertEquals(resting, configured.copy(animations = configured.animations - PetAnimation.THINKING).artwork(PetAnimation.THINKING))
        assertTrue(PetSettings().isReady)
    }
}
