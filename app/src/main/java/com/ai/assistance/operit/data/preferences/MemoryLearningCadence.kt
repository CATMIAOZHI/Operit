package com.ai.assistance.operit.data.preferences

internal data class MemoryLearningCadence(
    val turns: Int = 0,
    val iterations: Int = 0
) {
    fun advance(notesEnabled: Boolean, skillsEnabled: Boolean, toolIterations: Int,
                memoryInterval: Int, skillInterval: Int): MemoryLearningTick {
        val nextTurns = if (notesEnabled) turns.coerceAtMost(memoryInterval) + 1 else 0
        val nextIterations = if (skillsEnabled)
            iterations.coerceAtMost(skillInterval) + toolIterations.coerceIn(0, skillInterval) else 0
        val notes = notesEnabled && nextTurns >= memoryInterval
        val skills = skillsEnabled && nextIterations >= skillInterval
        return MemoryLearningTick(
            MemoryLearningCadence(if (notes) 0 else nextTurns, if (skills) 0 else nextIterations),
            notes, skills
        )
    }
}

internal data class MemoryLearningTick(
    val next: MemoryLearningCadence,
    val notes: Boolean,
    val skills: Boolean
)
