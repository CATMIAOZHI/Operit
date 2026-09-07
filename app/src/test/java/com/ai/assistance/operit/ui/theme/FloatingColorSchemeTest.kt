package com.ai.assistance.operit.ui.theme

import com.ai.assistance.operit.data.model.toComposeColorScheme
import com.ai.assistance.operit.data.model.toSerializable
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingColorSchemeTest {
    @Test fun persistedWindowThemesKeepOpaqueMenuSurfacesInBothModes() {
        for (dark in listOf(false, true)) {
            val theme = rainyBaseColorScheme(dark)
            // Service intents and stored JSON carry the original subset of Material color roles.
            val restored = theme.toSerializable().toComposeColorScheme()
            assertEquals(theme.primary, restored.primary)
            assertEquals(theme.onSurface, restored.onSurface)
            assertEquals(theme.surfaceContainerHigh, restored.surfaceContainerHigh)
            assertEquals(1f, restored.surfaceContainerHigh.alpha, 0f)
            assertEquals(1f, restored.surfaceContainer.alpha, 0f)
        }
    }
}
