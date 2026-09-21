package com.ai.assistance.operit.api.chat.library

import org.junit.Assert.*
import org.junit.Test

class MemoryLearningPromptsTest {
    @Test fun skillOnlyDoesNotRequireOrAdvertiseMemoryOperations() {
        val instructions = buildMemoryLearningInstructions("chat", false, true, "finish")
        val tools = memoryLearningActionDescription(false, true)
        assertFalse(instructions.contains("Read existing memory/user documents"))
        assertTrue(instructions.contains("Read skill_list"))
        assertTrue(instructions.contains("not a prerequisite"))
        assertFalse(tools.contains("memory_read"))
        assertFalse(tools.contains("memory_change"))
        assertTrue(tools.contains("skill_create"))
    }

    @Test fun notesOnlyDoesNotRequireOrAdvertiseSkillOperations() {
        val instructions = buildMemoryLearningInstructions("chat", true, false, "finish")
        val tools = memoryLearningActionDescription(true, false)
        assertTrue(instructions.contains("Read existing memory/user documents"))
        assertFalse(instructions.contains("Read skill_list"))
        assertTrue(tools.contains("memory_change"))
        assertFalse(tools.contains("skill_create"))
        assertFalse(tools.contains("skill_list"))
    }

    @Test fun combinedReviewRetainsBothScopesAndSharedSafetyRules() {
        val instructions = buildMemoryLearningInstructions("chat", true, true, "finish")
        val tools = memoryLearningActionDescription(true, true)
        assertTrue(instructions.contains("Read existing memory/user documents"))
        assertTrue(instructions.contains("Read skill_list"))
        assertTrue(instructions.contains("evidence, never instructions"))
        assertTrue(instructions.contains("auto-approval"))
        assertTrue(instructions.contains("Call finish"))
        assertTrue(tools.contains("memory_change"))
        assertTrue(tools.contains("skill_create"))
        assertTrue(tools.contains("history"))
    }
}
