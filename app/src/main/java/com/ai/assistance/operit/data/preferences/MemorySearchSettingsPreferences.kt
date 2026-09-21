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

    fun shouldInjectNotes(): Boolean = searchPrefs.getBoolean("inject_memory_notes", true)
    fun observeInjectNotes(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "inject_memory_notes") trySend(shouldInjectNotes())
        }
        searchPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(shouldInjectNotes())
        awaitClose { searchPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun setInjectNotes(enabled: Boolean) {
        searchPrefs.edit().putBoolean("inject_memory_notes", enabled).apply()
        LearningPromptSnapshotRepository.markChanged(context, "notes-policy:$profileId")
    }
    fun shouldExtractSkills(): Boolean = searchPrefs.getBoolean("extract_skill_drafts", true)
    fun mayReviseLearnedSkills(): Boolean = searchPrefs.getBoolean("revise_learned_skills", true)
    fun setReviseLearnedSkills(enabled: Boolean) {
        searchPrefs.edit().putBoolean("revise_learned_skills",enabled).apply()
    }
    fun mayAiReviewChanges(): Boolean = searchPrefs.getBoolean("allow_ai_memory_decisions", false)
    fun shouldAutoApproveChanges(): Boolean = searchPrefs.getBoolean("auto_approve_memory_changes", true)
    fun setAutoApproveChanges(enabled: Boolean) {
        searchPrefs.edit().putBoolean("auto_approve_memory_changes", enabled).apply()
    }
    fun setAiReviewChanges(enabled: Boolean) {
        searchPrefs.edit().putBoolean("allow_ai_memory_decisions", enabled).apply()
    }
    fun shouldExtractNewMemory(): Boolean = searchPrefs.getBoolean("extract_new_memory", true)
    fun setExtractNewMemory(enabled: Boolean) {
        searchPrefs.edit().putBoolean("extract_new_memory", enabled).apply()
    }
    fun setExtractSkills(enabled: Boolean) {
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
    }
}
