package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitTaskToolSurfaceTest {
    @Test fun rootControlsAppendToExistingToolsWithoutGivingChildrenFinishAuthority() {
        val root = OrbitTaskExecution.promptsForBinding(true).map { it.name }
        val child = OrbitTaskExecution.promptsForBinding(false).map { it.name }
        assertTrue(OrbitTaskRunner.FINISH in root)
        assertTrue(OrbitTaskActionTools.WAIT in root)
        assertFalse(OrbitTaskRunner.FINISH in child)
        assertFalse(OrbitTaskActionTools.WAIT in child)
        assertTrue(OrbitTaskExecution.isTaskCapability(OrbitTaskRunner.FINISH))
        assertFalse(OrbitTaskExecution.isTaskCapability("execute_shell"))
    }
}
