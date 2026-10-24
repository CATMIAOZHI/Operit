package com.ai.assistance.operit.data.preferences

import org.json.JSONObject

enum class OrbitChatPalette { AVATAR, SKY, INK, SAND, BLUE, LAVENDER, PINK, PEACH, GREEN }
enum class OrbitAvatarSize(val dp: Int) { SMALL(64), MEDIUM(84), LARGE(108), EXTRA_LARGE(132), HIDDEN(0) }
enum class OrbitColorMode { SYSTEM, LIGHT, DARK }

/** Per-companion appearance, independent of the main application's theme preferences. */
data class OrbitAppearance(
    val palette: OrbitChatPalette = OrbitChatPalette.AVATAR,
    val avatarSize: OrbitAvatarSize = OrbitAvatarSize.LARGE,
    val mode: OrbitColorMode = OrbitColorMode.SYSTEM,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("palette", palette.name).put("avatarSize", avatarSize.name).put("mode", mode.name)

    companion object {
        fun fromJson(json: JSONObject?): OrbitAppearance = OrbitAppearance(
            palette = OrbitChatPalette.entries.firstOrNull { it.name == json?.optString("palette") } ?: OrbitChatPalette.AVATAR,
            avatarSize = OrbitAvatarSize.entries.firstOrNull { it.name == json?.optString("avatarSize") } ?: OrbitAvatarSize.LARGE,
            mode = OrbitColorMode.entries.firstOrNull { it.name == json?.optString("mode") } ?: OrbitColorMode.SYSTEM,
        )
    }
}
