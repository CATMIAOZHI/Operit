package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatContextPolicy
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.resolveChatContext
import com.ai.assistance.operit.data.preferences.ChatContextPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile

/**
 * Orbit's context compaction. The main chat holds the setting, and every side chat gets the same
 * one, so the user sets it once. Sizes are in K tokens and measured against Orbit's main model.
 * Only the budget is a choice: Orbit never uses the model's full window, always compacts on its
 * own, and the app picks where compaction starts.
 */
internal object OrbitContextSettings {
    class State(
        val policy: ChatContextPolicy,
        /** The most the main model can read. */
        val capacityK: Float,
        /** What one turn may use now, after the model's limit. */
        val windowK: Float,
        /** Where compaction starts now, picked by the app. */
        val compactAtK: Float,
        /** The main model's name, to suggest a budget that suits it. */
        val modelName: String,
    )

    /** The budget suggested for DeepSeek Flash models. */
    const val DEEPSEEK_FLASH_BUDGET_K = 400

    fun isDeepSeekFlash(modelName: String): Boolean =
        modelName.lowercase().let { "deepseek" in it && "flash" in it }

    /** The only setting Orbit keeps from [policy] is its budget; the rest is fixed. */
    fun fixed(policy: ChatContextPolicy): ChatContextPolicy =
        policy.copy(maxMode = false, autoCompact = true, compactAtK = null, compactEveryUserTurns = null)

    private suspend fun main(context: Context, orbit: OrbitProfile): Pair<ModelConfigData, String> {
        val name = orbit.name.ifBlank { context.getString(R.string.nav_orbit) }
        val ref = checkNotNull(OrbitModelPreferences(context).current(orbit.id).main) {
            context.getString(R.string.orbit_context_no_model, name)
        }
        val config = checkNotNull(ModelConfigManager(context).getModelConfig(ref.configId)) {
            context.getString(R.string.orbit_context_no_model, name)
        }
        return config to ref.modelName
    }

    /**
     * The current setting. A setting saved before these rules (or by an older build) is fixed
     * here and written back to the main chat and every side chat, so every turn runs by them.
     */
    suspend fun state(context: Context, orbit: OrbitProfile): State {
        val (config, modelName) = main(context, orbit)
        val preferences = ChatContextPreferences(context)
        var policy = preferences.resolve(orbit.chatId, config)
        if (policy != fixed(policy)) {
            preferences.updateAll(orbit.chatId, orbit.talkChatIds, config) { fixed(it).validate() }
            policy = preferences.resolve(orbit.chatId, config)
        }
        val resolved = resolveChatContext(policy, config)
        return State(policy, resolved.capacityK, resolved.windowK, resolved.compactAtK, modelName)
    }

    /**
     * Writes the new budget to the main chat and every side chat at once; returns the new state.
     * Side chats are not made meanwhile, so a new one cannot keep the old setting.
     */
    suspend fun setBudget(context: Context, orbitId: String, windowK: Float): State =
        OrbitCompanion.withSideChatsSettled {
            val orbit = OrbitPreferences.getInstance(context).requireModelOwner(orbitId)
            val (config, _) = main(context, orbit)
            ChatContextPreferences(context).updateAll(orbit.chatId, orbit.talkChatIds, config) {
                fixed(it.copy(windowK = windowK)).validate()
            }
            state(context, orbit)
        }

    /** Applies the fixed rules to an existing Orbit's chats; quietly does nothing without a main model. */
    suspend fun enforce(context: Context, orbit: OrbitProfile) {
        try { state(context, orbit) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
    }

    /** A new side chat starts with the main chat's setting, already under the fixed rules. */
    suspend fun inherit(context: Context, orbit: OrbitProfile, chatId: String) {
        enforce(context, orbit)
        ChatContextPreferences(context).copy(orbit.chatId, chatId)
    }
}
