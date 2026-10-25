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
    /**
     * Colors of the Orbit logo shown while there is no picture, as "#RRGGBB"; null uses the logo's
     * default color. The core in the middle is the user, the ring and its moons are Orbit, which
     * always circles the user. Like the picture, only Orbit changes them (orbit_set_avatar), and
     * the "from avatar" chat palette takes its color from the core (the user).
     */
    val logoUserColor: String? = null,
    val logoOrbitColor: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("palette", palette.name).put("avatarSize", avatarSize.name).put("mode", mode.name)
        .apply {
            logoUserColor?.let { put("logoUserColor", it) }
            logoOrbitColor?.let { put("logoOrbitColor", it) }
        }

    companion object {
        private val HEX = Regex("^#[0-9A-F]{6}$")
        private val SHORT_HEX = Regex("^#[0-9A-F]{3}$")

        /** "#RRGGBB" in upper case from "#RRGGBB", "#RGB" (with or without "#"), or null for anything else. */
        fun normalizeColor(raw: String?): String? {
            val value = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?.let { if (it.startsWith("#")) it else "#$it" } ?: return null
            if (SHORT_HEX.matches(value)) return "#" + value.drop(1).map { "$it$it" }.joinToString("")
            return value.takeIf { HEX.matches(it) }
        }

        fun fromJson(json: JSONObject?): OrbitAppearance = OrbitAppearance(
            palette = OrbitChatPalette.entries.firstOrNull { it.name == json?.optString("palette") } ?: OrbitChatPalette.AVATAR,
            avatarSize = OrbitAvatarSize.entries.firstOrNull { it.name == json?.optString("avatarSize") } ?: OrbitAvatarSize.LARGE,
            mode = OrbitColorMode.entries.firstOrNull { it.name == json?.optString("mode") } ?: OrbitColorMode.SYSTEM,
            logoUserColor = normalizeColor(json?.optString("logoUserColor")),
            logoOrbitColor = normalizeColor(json?.optString("logoOrbitColor")),
        )
    }
}
