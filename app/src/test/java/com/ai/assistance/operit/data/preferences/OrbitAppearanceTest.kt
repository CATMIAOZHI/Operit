package com.ai.assistance.operit.data.preferences

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitAppearanceTest {
    @Test fun oldProfileWithoutAppearanceKeepsUsableDefaults() {
        assertEquals(OrbitAppearance(), OrbitAppearance.fromJson(null))
    }

    @Test fun persistedSelectionsRoundTripIncludingHiddenAndDark() {
        val appearance = OrbitAppearance(OrbitChatPalette.INK, OrbitAvatarSize.HIDDEN, OrbitColorMode.DARK)
        assertEquals(appearance, OrbitAppearance.fromJson(JSONObject(appearance.toJson().toString())))
    }

    @Test fun unknownValuesFallBackIndividually() {
        val json = JSONObject().put("palette", "future").put("avatarSize", "SMALL").put("mode", JSONObject.NULL)
        assertEquals(OrbitAppearance(avatarSize = OrbitAvatarSize.SMALL), OrbitAppearance.fromJson(json))
    }
}
