package com.ai.assistance.operit.pet

import org.json.JSONArray
import org.json.JSONObject

enum class PetMediaType { ATLAS, IMAGE, GIF, VIDEO }

enum class PetAnimation { IDLE, THINKING, TOOL, SUMMARIZING, COMPLETE, ERROR, ENDED, GREETING }
data class PetArtwork(val id: String, val name: String)
data class PetProfile(val id: String, val settings: PetSettings)

internal fun PetSettings.artwork(animation: PetAnimation): PetArtwork? =
    animations[animation] ?: animations[PetAnimation.IDLE]

internal val PetSettings.isReady: Boolean
    get() = mediaType == PetMediaType.ATLAS || animations.containsKey(PetAnimation.IDLE)

internal fun PetActivity.animation(): PetAnimation = when (this) {
    PetActivity.IDLE -> PetAnimation.IDLE
    PetActivity.THINKING -> PetAnimation.THINKING
    PetActivity.TOOL -> PetAnimation.TOOL
    PetActivity.SUMMARIZING -> PetAnimation.SUMMARIZING
    PetActivity.COMPLETE -> PetAnimation.COMPLETE
    PetActivity.ERROR -> PetAnimation.ERROR
    PetActivity.ENDED -> PetAnimation.ENDED
}

internal fun encodePetLibrary(pets: List<PetProfile>): String = JSONArray().apply {
    pets.forEach { pet ->
        put(JSONObject().apply {
            put("id", pet.id)
            with(pet.settings) {
                put("animated", animated)
                put("x", x); put("y", y); put("size", sizeDp); put("opacity", opacity)
                put("bubble", showBubble); put("edge", edge.name)
                put("name", name); put("mediaType", mediaType.name)
                put("animations", JSONObject().apply {
                    animations.forEach { (animation, artwork) ->
                        put(animation.name, JSONObject().put("id", artwork.id).put("name", artwork.name))
                    }
                })
            }
        })
    }
}.toString()

internal fun decodePetLibrary(json: String): List<PetProfile> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val pet = array.getJSONObject(index)
        PetProfile(
            pet.getString("id"),
            PetSettings(
                animated = pet.getBoolean("animated"),
                x = pet.getDouble("x").toFloat(), y = pet.getDouble("y").toFloat(),
                sizeDp = pet.getDouble("size").toFloat(), opacity = pet.getDouble("opacity").toFloat(),
                showBubble = pet.getBoolean("bubble"), edge = PetEdge.valueOf(pet.getString("edge")),
                name = pet.optString("name", pet.optString("assetName")),
                animations = pet.optJSONObject("animations")?.let { animations ->
                    PetAnimation.entries.mapNotNull { animation ->
                        animations.optJSONObject(animation.name)?.let {
                            animation to PetArtwork(it.getString("id"), it.getString("name"))
                        }
                    }.toMap()
                } ?: pet.optString("assetId").takeIf { it.isNotBlank() }?.let {
                    mapOf(PetAnimation.IDLE to PetArtwork(it, pet.optString("assetName")))
                }.orEmpty(),
                mediaType = PetMediaType.valueOf(pet.getString("mediaType")),
            ),
        )
    }
}
