package com.ai.assistance.operit.core.tools.skill

import com.ai.assistance.operit.util.StorageSource
import java.io.File

data class SkillPackage(
    val name: String,
    val description: String,
    val directory: File,
    val skillFile: File,
    /**
     * Which physical store this skill was loaded from. Runtime-only metadata: never persisted
     * into user-facing JSON (SKILL.md files).
     */
    val storageSource: StorageSource = StorageSource.INTERNAL
)
