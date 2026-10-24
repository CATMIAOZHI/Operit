package com.ai.assistance.operit.api.chat.library

import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import org.junit.Assert.*
import org.junit.Test

class MemoryLearningPromptsTest {
    @Test fun `an orbit review sorts memory and routes lessons and people elsewhere`() {
        val orbit = buildMemoryLearningInstructions("chat", true, false, "finish", orbit = true)
        val plain = buildMemoryLearningInstructions("chat", true, false, "finish")
        assertTrue(orbit.contains("section=facts/preferences/decisions/commitments"))
        assertTrue(orbit.contains("lesson_add") && orbit.contains("people_write"))
        assertFalse(orbit.contains("Keep environment facts in memory.md"))
        assertTrue(plain.contains("Keep environment facts in memory.md"))
        assertTrue(memoryLearningActionDescription(true, false, orbit = true).contains("people_write"))
        assertFalse(memoryLearningActionDescription(true, false).contains("people_write"))
        assertFalse(memoryLearningActionDescription(false, true, orbit = true).contains("lesson_add"))
    }

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

    @Test fun `prompt states the page budget and the wrap-up rule`() {
        val instructions = buildMemoryLearningInstructions("chat", true, true, "finish")
        assertTrue(instructions.contains("at most $LEARNING_ROUND_LIMIT model rounds and $LEARNING_TOOL_CALL_LIMIT tool calls."))
        assertTrue(instructions.contains("Once within two rounds of that limit"))
        assertTrue(instructions.contains("an uncommitted page is discarded and reviewed again later"))
    }

    @Test fun `the reviewer reads the source itself and commits page by page`() {
        val instructions = buildMemoryLearningInstructions("chat", true, true, "finish")
        val tools = memoryLearningActionDescription(true, true)
        assertTrue(instructions.contains("read it yourself, page by page"))
        assertTrue(instructions.contains("Do not read everything first"))
        assertTrue(tools.contains("read_next"))
        assertTrue(tools.contains("commit"))
    }

    @Test fun `notes scope tells the reviewer to free space before adding`() {
        val instructions = buildMemoryLearningInstructions("chat", true, false, "finish")
        assertTrue(instructions.contains("remove or replace existing text first, then add"))
        assertTrue(instructions.contains("rejected"))
    }

    @Test fun `skill scope carries the authoring standard`() {
        val instructions = buildMemoryLearningInstructions("chat", false, true, "finish")
        assertTrue(instructions.contains("at most 60 characters"))
        assertTrue(instructions.contains("## Verification"))
        assertTrue(instructions.contains("Never invent flags"))
        assertTrue(instructions.contains("bidirectional Unicode control characters"))
        assertTrue(instructions.contains("not a transcript of one conversation"))
    }

    /** The prompt and the write path must quote the same numbers, or the reviewer paces itself wrongly. */
    @Test fun `skill scope states the same size limits the tools enforce`() {
        val instructions = buildMemoryLearningInstructions("chat", false, true, "finish")
        val tools = memoryLearningActionDescription(false, true)
        val creation = "${LearnedSkillRepository.MIN_SKILL_BODY_CHARS}-${LearnedSkillRepository.MAX_SKILL_BODY_CHARS}"
        assertTrue(instructions.contains(creation))
        assertTrue(tools.contains(creation))
        assertTrue(instructions.contains(LearnedSkillRepository.MAX_SKILL_FILE_CHARS.toString()))
        assertTrue(instructions.contains("must NOT include YAML frontmatter"))
        assertTrue(tools.contains("without YAML frontmatter"))
    }
}
