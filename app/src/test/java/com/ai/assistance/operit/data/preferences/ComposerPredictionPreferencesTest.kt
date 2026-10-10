package com.ai.assistance.operit.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ComposerPredictionPreferencesTest {
    @Test
    fun `defaults on preserves saved off and reset restores on`() = runBlocking {
        // Use a fresh real DataStore, matching the preference tests' existing isolation pattern.
        val facade = Class.forName("com.ai.assistance.operit.data.preferences.DisplayPreferencesManagerKt")
        val delegateField = facade.getDeclaredField("displayPreferencesDataStore\$delegate")
            .apply { isAccessible = true }
        val delegate = delegateField.get(null)
        delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
            .set(delegate, null)

        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(kotlin.io.path.createTempDirectory("composer-preferences").toFile())
        val constructor = DisplayPreferencesManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
        val preferences = constructor.newInstance(context)

        assertTrue(preferences.composerPredictionsEnabled.first())
        preferences.saveDisplaySettings(composerPredictionsEnabled = false)
        assertFalse(preferences.composerPredictionsEnabled.first())
        assertFalse(constructor.newInstance(context).composerPredictionsEnabled.first())
        preferences.saveDisplaySettings(showFpsCounter = true)
        assertFalse(preferences.composerPredictionsEnabled.first())
        preferences.resetDisplaySettings()
        assertTrue(preferences.composerPredictionsEnabled.first())
    }
}
