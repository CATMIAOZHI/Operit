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
 */
internal object OrbitContextSettings {
    class State(
        val policy: ChatContextPolicy,
        /** The most the main model can read. */
        val capacityK: Float,
        /** What one turn may use now, after the full-window switch and the model's limit. */
        val windowK: Float,
        /** Where compaction starts now: the user's point, or the automatic one. */
        val compactAtK: Float,
    )

    private suspend fun mainConfig(context: Context, orbit: OrbitProfile): ModelConfigData {
        val name = orbit.name.ifBlank { context.getString(R.string.nav_orbit) }
        val ref = checkNotNull(OrbitModelPreferences(context).current(orbit.id).main) {
            context.getString(R.string.orbit_context_no_model, name)
        }
        return checkNotNull(ModelConfigManager(context).getModelConfig(ref.configId)) {
            context.getString(R.string.orbit_context_no_model, name)
        }
    }

    suspend fun state(context: Context, orbit: OrbitProfile): State {
        val config = mainConfig(context, orbit)
        val policy = ChatContextPreferences(context).resolve(orbit.chatId, config)
        val resolved = resolveChatContext(policy, config)
        return State(policy, resolved.capacityK, resolved.windowK, resolved.compactAtK)
    }

    /**
     * Writes the new setting to the main chat and every side chat at once; returns the new state.
     * Side chats are not made meanwhile, so a new one cannot keep the old setting.
     */
    suspend fun update(context: Context, orbitId: String, transform: (ChatContextPolicy) -> ChatContextPolicy): State =
        OrbitCompanion.withSideChatsSettled {
            val orbit = OrbitPreferences.getInstance(context).requireModelOwner(orbitId)
            val config = mainConfig(context, orbit)
            ChatContextPreferences(context).updateAll(orbit.chatId, orbit.talkChatIds, config) {
                transform(it).validate()
            }
            state(context, orbit)
        }

    /** A new side chat starts with the main chat's setting. */
    suspend fun inherit(context: Context, orbit: OrbitProfile, chatId: String) {
        ChatContextPreferences(context).copy(orbit.chatId, chatId)
    }
}
