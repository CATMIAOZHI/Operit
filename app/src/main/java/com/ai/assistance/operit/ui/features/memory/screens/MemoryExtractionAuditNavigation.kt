package com.ai.assistance.operit.ui.features.memory.screens

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.chat.AuditChatNavigation
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.MemoryExtractionLog
import com.ai.assistance.operit.data.repository.MemoryExtractionAuditRepository
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway
import com.ai.assistance.operit.ui.main.navigation.RouteEntrySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same native, temporary, read-only subagent view used by Reading Companion. */
internal suspend fun openMemoryExtractionConversation(context: Context, log: MemoryExtractionLog) {
    val delegate = ChatRuntimeHolder.getInstance(context.applicationContext)
        .getCore(ChatRuntimeSlot.MAIN).getChatHistoryDelegate()
    val previousId = delegate.currentChatId.value
    val (childId, returnId) = withContext(Dispatchers.IO) {
        val run = MemoryExtractionAuditRepository(context).resolve(log)
            ?: error(context.getString(R.string.memory_audit_unavailable))
        val dao = AppDatabase.getDatabase(context).chatDao()
        val safeReturn = previousId?.let { dao.getChatById(it) }?.takeIf { !it.isHidden }?.id
            ?: dao.getChatById(log.sourceChatId)?.takeIf { !it.isHidden }?.id
        run.childChatId to safeReturn
    }
    withContext(Dispatchers.Main.immediate) {
        AuditChatNavigation.rememberReturnChat(childId, returnId)
        delegate.switchChat(childId, syncToGlobal = false, scrollToBottom = false)
        AppRouterGateway.navigate("native.ai_chat", source = RouteEntrySource.DEFAULT)
    }
}
