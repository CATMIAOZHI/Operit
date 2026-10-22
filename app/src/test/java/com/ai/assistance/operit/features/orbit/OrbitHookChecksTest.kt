package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class OrbitHookChecksTest {
    @Test fun aDryRunDoesNotAuthorizeANewScriptOrAnotherTask() {
        val values = mutableMapOf<String, String>()
        val prefs = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        val context = mock<Context>()
        whenever(context.getSharedPreferences("orbit_hook_checks", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getString(any(), isNull())).thenAnswer { values[it.getArgument<String>(0)] }
        whenever(editor.putString(any(), any())).thenAnswer {
            values[it.getArgument(0)] = it.getArgument(1)
            editor
        }
        whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
        whenever(editor.commit()).thenReturn(true)
        val source = "script:files/hooks/check.sh@${OrbitWatchSources.hash("silent 'fine' '{}'")}"
        val task = OrbitTaskEntity("task", "orbit", "chat", "Check", "Check", "WATCH", source,
            60, 0, "CHANGES", "PAUSED", 1, 0, "", "", 0, 0)
        assertFalse(OrbitHookChecks.passed(context, task))
        OrbitHookChecks.remember(context, task)
        assertTrue(OrbitHookChecks.passed(context, task))
        assertFalse(OrbitHookChecks.passed(context, task.copy(id = "other")))
        assertFalse(OrbitHookChecks.passed(context, task.copy(source = source.replace("check.sh", "new.sh"))))
        assertTrue(OrbitHookChecks.passed(context, task.copy(source = "notifications")))
        OrbitHookChecks.forget(context, task.id)
        assertFalse(OrbitHookChecks.passed(context, task))
    }
}
