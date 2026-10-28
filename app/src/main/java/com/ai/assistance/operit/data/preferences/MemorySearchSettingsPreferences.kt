package com.ai.assistance.operit.data.preferences

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import com.ai.assistance.operit.data.model.CloudEmbeddingConfig
import com.ai.assistance.operit.data.model.MemoryScoreMode
import com.ai.assistance.operit.data.model.MemorySearchConfig

class MemorySearchSettingsPreferences(private val context: Context, profileId: String) {
    private val profileId = profileId
    private val searchPrefs = context.applicationContext.getSharedPreferences(
        "memory_search_settings_$profileId",
        Context.MODE_PRIVATE
    )
    private val cloudPrefs = context.applicationContext.getSharedPreferences(
        "cloud_embedding_settings_$profileId",
        Context.MODE_PRIVATE
    )

    fun load(): MemorySearchConfig {
        val config = MemorySearchConfig(
            scoreMode = MemoryScoreMode.entries[searchPrefs.getInt(KEY_SCORE_MODE, MemoryScoreMode.BALANCED.ordinal)],
            keywordWeight = searchPrefs.getFloat(KEY_KEYWORD_WEIGHT, 10.0f),
            tagWeight = searchPrefs.getFloat(KEY_TAG_WEIGHT, 0.0f),
            vectorWeight = searchPrefs.getFloat(KEY_VECTOR_WEIGHT, 0.0f),
            edgeWeight = searchPrefs.getFloat(KEY_EDGE_WEIGHT, 0.4f)
        )
        return config.normalized()
    }

    /**
     * An Orbit's own space always injects memory.md, extracts and revises skills, reads thinking and
     * applies changes; these switches exist only for ordinary spaces.
     */
    val isOrbitLocked: Boolean get() = isOrbitSpace(profileId)
    fun shouldInjectNotes(): Boolean = isOrbitLocked || searchPrefs.getBoolean("inject_memory_notes", true)
    fun observeInjectNotes(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "inject_memory_notes") trySend(shouldInjectNotes())
        }
        searchPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(shouldInjectNotes())
        awaitClose { searchPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun setInjectNotes(enabled: Boolean) {
        if (isOrbitLocked) return
        searchPrefs.edit().putBoolean("inject_memory_notes", enabled).apply()
        LearningPromptSnapshotRepository.markChanged(context, "notes-policy:$profileId")
    }
    fun shouldExtractSkills(): Boolean = isOrbitLocked || searchPrefs.getBoolean("extract_skill_drafts", true)
    /**
     * Whether the reviewer may look up a message's thinking when it needs it. Pages never include
     * thinking; the reviewer asks for it with history include_thinking=true, mainly for skill work.
     */
    fun shouldIncludeThinking(): Boolean = isOrbitLocked || searchPrefs.getBoolean("extract_include_thinking", true)
    fun setIncludeThinking(enabled: Boolean) {
        if (isOrbitLocked) return
        searchPrefs.edit().putBoolean("extract_include_thinking", enabled).apply()
    }
    /** How long a chat must stay quiet after its last reply before its new messages are reviewed. */
    fun learningDelayMinutes(): Int = searchPrefs.getInt("learning_delay_minutes", 5).coerceIn(1, 60)
    fun setLearningDelayMinutes(minutes: Int) {
        searchPrefs.edit().putInt("learning_delay_minutes", minutes.coerceIn(1, 60)).apply()
    }
    fun mayReviseLearnedSkills(): Boolean = isOrbitLocked || searchPrefs.getBoolean("revise_learned_skills", true)
    fun setReviseLearnedSkills(enabled: Boolean) {
        if (isOrbitLocked) return
        searchPrefs.edit().putBoolean("revise_learned_skills",enabled).apply()
    }
    fun mayAiReviewChanges(): Boolean = searchPrefs.getBoolean("allow_ai_memory_decisions", false)
    /** An Orbit's own memory space always applies changes without asking; it cannot be turned off. */
    val isAutoApprovalLocked: Boolean get() = isOrbitSpace(profileId)
    fun shouldAutoApproveChanges(): Boolean =
        isAutoApprovalLocked || searchPrefs.getBoolean("auto_approve_memory_changes", true)
    fun setAutoApproveChanges(enabled: Boolean) {
        if (isAutoApprovalLocked) return
        searchPrefs.edit().putBoolean("auto_approve_memory_changes", enabled).apply()
    }
    fun setAiReviewChanges(enabled: Boolean) {
        searchPrefs.edit().putBoolean("allow_ai_memory_decisions", enabled).apply()
    }

    /**
     * Dropped together with the memory space, so a future space that reuses the id does not inherit
     * stale switches.
     */
    suspend fun clear() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // commit(), not apply(): the space is already gone from the list, and a leftover file is
        // exactly how a future space reusing this id would inherit the old switches.
        val searchCleared = searchPrefs.edit().clear().commit()
        val cloudCleared = cloudPrefs.edit().clear().commit()
        if (!searchCleared || !cloudCleared) {
            com.ai.assistance.operit.util.AppLogger.w(
                "MemorySearchSettings", "Could not clear the settings of deleted memory space $profileId"
            )
        }
    }
    // Every Orbit conversation goes through memory review; only ordinary spaces can turn it off.
    fun shouldExtractNewMemory(): Boolean = isOrbitSpace(profileId) || searchPrefs.getBoolean("extract_new_memory", true)
    fun setExtractNewMemory(enabled: Boolean) {
        searchPrefs.edit().putBoolean("extract_new_memory", enabled).apply()
    }
    fun setExtractSkills(enabled: Boolean) {
        if (isOrbitLocked) return
        searchPrefs.edit().putBoolean("extract_skill_drafts", enabled).apply()
    }

    fun save(config: MemorySearchConfig) {
        val normalized = config.normalized()
        searchPrefs.edit()
            .putInt(KEY_SCORE_MODE, normalized.scoreMode.ordinal)
            .putFloat(KEY_KEYWORD_WEIGHT, normalized.keywordWeight)
            .putFloat(KEY_TAG_WEIGHT, normalized.tagWeight)
            .putFloat(KEY_VECTOR_WEIGHT, normalized.vectorWeight)
            .putFloat(KEY_EDGE_WEIGHT, normalized.edgeWeight)
            .apply()
    }

    fun reset() {
        save(MemorySearchConfig())
    }

    fun loadAutoSaveIntervalMinutes(): Int {
        return searchPrefs.getInt(KEY_AUTO_SAVE_INTERVAL_MINUTES, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES)
            .coerceIn(MIN_AUTO_SAVE_INTERVAL_MINUTES, MAX_AUTO_SAVE_INTERVAL_MINUTES)
    }

    fun saveAutoSaveIntervalMinutes(minutes: Int) {
        val normalized =
            minutes.coerceIn(MIN_AUTO_SAVE_INTERVAL_MINUTES, MAX_AUTO_SAVE_INTERVAL_MINUTES)
        searchPrefs.edit()
            .putInt(KEY_AUTO_SAVE_INTERVAL_MINUTES, normalized)
            .putLong(
                KEY_NEXT_AUTO_SAVE_RUN_AT_MS,
                System.currentTimeMillis() + normalized * 60_000L
            )
            .apply()
    }

    fun loadNextAutoSaveRunAtMs(): Long {
        return searchPrefs.getLong(KEY_NEXT_AUTO_SAVE_RUN_AT_MS, 0L)
    }

    fun saveNextAutoSaveRunAtMs(timestampMs: Long) {
        searchPrefs.edit()
            .putLong(KEY_NEXT_AUTO_SAVE_RUN_AT_MS, timestampMs.coerceAtLeast(0L))
            .apply()
    }

    fun loadCloudEmbedding(): CloudEmbeddingConfig {
        return CloudEmbeddingConfig(
            enabled = cloudPrefs.getBoolean(KEY_CLOUD_ENABLED, false),
            endpoint = cloudPrefs.getString(KEY_CLOUD_ENDPOINT, "") ?: "",
            apiKey = cloudPrefs.getString(KEY_CLOUD_API_KEY, "") ?: "",
            model = cloudPrefs.getString(KEY_CLOUD_MODEL, "") ?: ""
        ).normalized()
    }

    fun saveCloudEmbedding(config: CloudEmbeddingConfig) {
        val normalized = config.normalized()
        cloudPrefs.edit()
            .putBoolean(KEY_CLOUD_ENABLED, normalized.enabled)
            .putString(KEY_CLOUD_ENDPOINT, normalized.endpoint)
            .putString(KEY_CLOUD_API_KEY, normalized.apiKey)
            .putString(KEY_CLOUD_MODEL, normalized.model)
            .apply()
    }

    fun resetCloudEmbedding() {
        saveCloudEmbedding(CloudEmbeddingConfig())
    }

    companion object {
        private const val KEY_SCORE_MODE = "score_mode"
        private const val KEY_KEYWORD_WEIGHT = "keyword_weight"
        private const val KEY_TAG_WEIGHT = "tag_weight"
        private const val KEY_VECTOR_WEIGHT = "vector_weight"
        private const val KEY_EDGE_WEIGHT = "edge_weight"
        private const val KEY_AUTO_SAVE_INTERVAL_MINUTES = "auto_save_interval_minutes"
        private const val KEY_NEXT_AUTO_SAVE_RUN_AT_MS = "next_auto_save_run_at_ms"

        private const val KEY_CLOUD_ENABLED = "enabled"
        private const val KEY_CLOUD_ENDPOINT = "endpoint"
        private const val KEY_CLOUD_API_KEY = "api_key"
        private const val KEY_CLOUD_MODEL = "model"

        const val DEFAULT_AUTO_SAVE_INTERVAL_MINUTES = 5
        const val MIN_AUTO_SAVE_INTERVAL_MINUTES = 1
        const val MAX_AUTO_SAVE_INTERVAL_MINUTES = 30

        /** Orbit memory spaces are named `orbit_<orbitId>` (UserPreferencesManager.createOrbitMemorySpace). */
        fun isOrbitSpace(profileId: String): Boolean = profileId.startsWith("orbit_")

        /**
         * An Orbit keeps its memory inside its own folder, like Muse: `orbit/<orbitId>/memory/`
         * holds memory.md and the record of changes to it. Other spaces return null.
         */
        fun orbitMemoryDir(filesDir: java.io.File, profileId: String): java.io.File? {
            if (!isOrbitSpace(profileId)) return null
            val orbitId = profileId.removePrefix("orbit_")
            // An odd id can only come from edited or restored data; keep it on the old path rather than crash.
            if (!orbitId.matches(Regex("[a-zA-Z0-9-]+"))) return null
            return java.io.File(filesDir, "orbit/$orbitId/memory")
        }

        /** Moves a file written by an older version to its new place, once. */
        internal fun moveLegacyFile(legacy: java.io.File, target: java.io.File) {
            if (legacy == target || target.exists() || !legacy.isFile) return
            target.parentFile?.mkdirs()
            java.nio.file.Files.move(legacy.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            legacy.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
    }
}
