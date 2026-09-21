package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class MemoryLearningCadenceTest {
    @Test fun shortChatsDoNotTriggerAndTenthTurnDoes() {
        var state = MemoryLearningCadence()
        repeat(9) {
            val tick = state.advance(true, true, 0, 10, 10)
            assertFalse(tick.notes)
            assertFalse(tick.skills)
            state = tick.next
        }
        val tick = state.advance(true, true, 0, 10, 10)
        assertTrue(tick.notes)
        assertFalse(tick.skills)
        assertEquals(0, tick.next.turns)
    }

    @Test fun pathsAccumulateIndependentlyAndCanTriggerTogether() {
        val skillOnly = MemoryLearningCadence(3, 8).advance(true, true, 3, 10, 10)
        assertFalse(skillOnly.notes)
        assertTrue(skillOnly.skills)
        assertEquals(MemoryLearningCadence(4, 0), skillOnly.next)
        val both = MemoryLearningCadence(9, 9).advance(true, true, 1, 10, 10)
        assertTrue(both.notes && both.skills)
    }

    @Test fun disabledPathsDoNotAccumulateOrTrigger() {
        val tick = MemoryLearningCadence(9, 9).advance(false, false, 100, 10, 10)
        assertFalse(tick.notes || tick.skills)
        assertEquals(MemoryLearningCadence(), tick.next)
    }

    @Test fun pendingReviewSurvivesContinuedChatAndReloadWithoutCrossingSpaces() {
        val context = mock<Context>()
        val stores = mutableMapOf<String, SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any(), any())).thenAnswer { call ->
            stores.getOrPut(call.getArgument(0)) {
                val values = mutableMapOf<String, Any>()
                val prefs = mock<SharedPreferences>()
                val editor = mock<SharedPreferences.Editor>()
                whenever(prefs.getInt(any(), any())).thenAnswer {
                    values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1)
                }
                whenever(prefs.getBoolean(any(), any())).thenAnswer {
                    values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
                }
                whenever(prefs.edit()).thenReturn(editor)
                whenever(editor.putInt(any(), any())).thenAnswer {
                    values[it.getArgument(0)] = it.getArgument<Int>(1); editor
                }
                whenever(editor.putBoolean(any(), any())).thenAnswer {
                    values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor
                }
                whenever(editor.remove(any())).thenAnswer {
                    values.remove(it.getArgument<String>(0)); editor
                }
                prefs
            }
        }
        val settings = MemorySearchSettingsPreferences(context, "a")
        repeat(10) { settings.advanceLearningCadence("chat", 0) }
        val reloaded = MemorySearchSettingsPreferences(context, "a")
        assertTrue(reloaded.advanceLearningCadence("chat", 0).notes)
        assertFalse(reloaded.advanceLearningCadence("other-chat", 0).notes)
        assertFalse(MemorySearchSettingsPreferences(context, "b").advanceLearningCadence("chat", 0).notes)
        assertEquals(true to false, reloaded.consumePendingLearning("chat"))
        assertEquals(false to false, reloaded.consumePendingLearning("chat"))
        assertFalse(reloaded.advanceLearningCadence("chat", 0).notes)
        repeat(8) { reloaded.advanceLearningCadence("chat", 0) }
        assertTrue(reloaded.advanceLearningCadence("chat", 0).notes)
    }
}
