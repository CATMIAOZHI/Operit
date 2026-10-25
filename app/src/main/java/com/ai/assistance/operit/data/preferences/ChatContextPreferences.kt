package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatContextPolicy
import com.ai.assistance.operit.data.model.ModelConfigData
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.chatContextDataStore by preferencesDataStore(name = "chat_context_defaults")

class ChatContextPreferences(context: Context) {
    private val store = context.applicationContext.chatContextDataStore
    private val database = AppDatabase.getDatabase(context.applicationContext)
    private val dao = database.chatDao()
    private val defaultsKey = stringPreferencesKey("policy")
    val defaults = store.data.map { preferences ->
        preferences[defaultsKey]?.let(ChatContextPolicy::decode) ?: ChatContextPolicy()
    }.distinctUntilChanged()

    suspend fun saveDefaults(policy: ChatContextPolicy) {
        store.edit { it[defaultsKey] = policy.encode() }
    }

    fun observe(chatId: String) = dao.observeContextPolicy(chatId)

    suspend fun resolve(chatId: String?, config: ModelConfigData): ChatContextPolicy {
        if (chatId == null) return defaults.first()
        return database.withTransaction {
            val chat = requireNotNull(dao.getChatById(chatId)) { "Conversation no longer exists" }
            chat.contextPolicyJson?.let(ChatContextPolicy::decode)
                ?: ChatContextPolicy.fromLegacy(config).also {
                    dao.updateContextPolicy(chatId, it.encode())
                }
        }
    }

    suspend fun update(chatId: String, config: ModelConfigData, transform: (ChatContextPolicy) -> ChatContextPolicy) {
        database.withTransaction {
            val chat = requireNotNull(dao.getChatById(chatId)) { "Conversation no longer exists" }
            val current = chat.contextPolicyJson?.let(ChatContextPolicy::decode)
                ?: ChatContextPolicy.fromLegacy(config)
            dao.updateContextPolicy(chatId, transform(current).encode())
        }
    }

    /** Gives [toChatId] the same setting as [fromChatId]; nothing happens while that one has none. */
    suspend fun copy(fromChatId: String, toChatId: String) {
        database.withTransaction {
            val policy = dao.getChatById(fromChatId)?.contextPolicyJson ?: return@withTransaction
            ChatContextPolicy.decode(policy)
            dao.updateContextPolicy(toChatId, policy)
        }
    }

    /**
     * Gives every chat in [chatIds] the result of [transform] on [sourceChatId]'s setting, in one
     * transaction: either all of them change or none. Chats that no longer exist are skipped.
     */
    suspend fun updateAll(
        sourceChatId: String,
        chatIds: Collection<String>,
        config: ModelConfigData,
        transform: (ChatContextPolicy) -> ChatContextPolicy,
    ) {
        database.withTransaction {
            val source = requireNotNull(dao.getChatById(sourceChatId)) { "Conversation no longer exists" }
            val current = source.contextPolicyJson?.let(ChatContextPolicy::decode)
                ?: ChatContextPolicy.fromLegacy(config)
            val encoded = transform(current).encode()
            (chatIds + sourceChatId).toSet().forEach { chatId ->
                if (chatId == sourceChatId || dao.getChatById(chatId) != null) dao.updateContextPolicy(chatId, encoded)
            }
        }
    }
}
