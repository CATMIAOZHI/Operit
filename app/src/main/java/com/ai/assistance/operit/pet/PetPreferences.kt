package com.ai.assistance.operit.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PetSettings(
    val inApp: Boolean = false,
    val overlay: Boolean = false,
    val animated: Boolean = true,
    val x: Float = 0f,
    val y: Float = 0.55f,
    val sizeDp: Float = 80f,
    val opacity: Float = 1f,
    val showBubble: Boolean = true,
    val edge: PetEdge = PetEdge.LEFT,
    val name: String = "",
    val animations: Map<PetAnimation, PetArtwork> = emptyMap(),
    val mediaType: PetMediaType = PetMediaType.ATLAS,
)

/** Small UI preferences shared by the Activity and the independent overlay host. */
class PetPreferences private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("pet_companion", Context.MODE_PRIVATE)
    private val initialSettings =
        PetSettings(
            inApp = preferences.getBoolean("in_app", false),
            overlay = preferences.getBoolean("overlay", false),
            animated = preferences.getBoolean("animated", true),
            x = preferences.getFloat("x", 0f),
            y = preferences.getFloat("y", 0.55f),
            sizeDp = preferences.getFloat("size_dp", 80f),
            opacity = preferences.getFloat("opacity", 1f),
            showBubble = preferences.getBoolean("show_bubble", true),
            edge = PetEdge.entries.firstOrNull {
                it.name == preferences.getString("dock_edge", "")
            } ?: PetEdge.LEFT,
            name = preferences.getString("asset_name", "").orEmpty(),
            animations = preferences.getString("asset_id", "")?.takeIf { it.isNotBlank() }?.let {
                mapOf(PetAnimation.IDLE to PetArtwork(it, preferences.getString("asset_name", "").orEmpty()))
            }.orEmpty(),
            mediaType = if (preferences.getBoolean("asset_atlas", true)) PetMediaType.ATLAS else PetMediaType.IMAGE,
        )
    // Existing development installs already have a selected pet. Retain its artwork and placement.
    private val mutablePets = MutableStateFlow(
        preferences.getString("pets", null)?.let(::decodePetLibrary)
            ?: listOf(PetProfile("default", initialSettings))
    )
    val pets = mutablePets.asStateFlow()
    private val mutableSelectedId = MutableStateFlow(
        preferences.getString("selected_pet", null)?.takeIf { id -> mutablePets.value.any { it.id == id } }
            ?: mutablePets.value.first().id
    )
    val selectedId = mutableSelectedId.asStateFlow()
    private val mutableSettings = MutableStateFlow(selectedSettings())
    val settings = mutableSettings.asStateFlow()

    private fun selectedSettings(): PetSettings =
        mutablePets.value.first { it.id == mutableSelectedId.value }.settings.copy(
            inApp = preferences.getBoolean("in_app", false),
            overlay = preferences.getBoolean("overlay", false),
        )

    fun update(transform: (PetSettings) -> PetSettings) {
        updatePet(mutableSelectedId.value, transform)
    }

    fun updatePet(id: String, transform: (PetSettings) -> PetSettings): Boolean {
        val current = mutablePets.value.firstOrNull { it.id == id } ?: return false
        val value = transform(current.settings.copy(
            inApp = mutableSettings.value.inApp, overlay = mutableSettings.value.overlay,
        ))
        val pets = mutablePets.value.map { if (it.id == id) it.copy(settings = value) else it }
        preferences.edit()
            .putBoolean("in_app", value.inApp)
            .putBoolean("overlay", value.overlay)
            .putString("pets", encodePetLibrary(pets))
            .putString("selected_pet", mutableSelectedId.value)
            .apply()
        mutablePets.value = pets
        mutableSettings.value = selectedSettings()
        return true
    }

    fun select(id: String) {
        if (mutablePets.value.none { it.id == id }) return
        preferences.edit().putString("selected_pet", id).apply()
        mutableSelectedId.value = id
        mutableSettings.value = selectedSettings()
    }

    fun add(settings: PetSettings) {
        val pet = PetProfile(UUID.randomUUID().toString(), settings)
        val pets = mutablePets.value + pet
        preferences.edit().putString("pets", encodePetLibrary(pets)).putString("selected_pet", pet.id).apply()
        mutablePets.value = pets
        mutableSelectedId.value = pet.id
        mutableSettings.value = selectedSettings()
    }

    fun remove(id: String): PetProfile? {
        if (mutablePets.value.size <= 1) return null
        val removed = mutablePets.value.firstOrNull { it.id == id } ?: return null
        val pets = mutablePets.value.filterNot { it.id == id }
        val selected = if (id == mutableSelectedId.value) pets.first().id else mutableSelectedId.value
        preferences.edit().putString("pets", encodePetLibrary(pets)).putString("selected_pet", selected).apply()
        mutablePets.value = pets
        mutableSelectedId.value = selected
        mutableSettings.value = selectedSettings()
        return removed
    }

    companion object {
        @Volatile private var instance: PetPreferences? = null
        fun get(context: Context): PetPreferences = instance ?: synchronized(this) {
            instance ?: PetPreferences(context.applicationContext).also { instance = it }
        }
    }
}
