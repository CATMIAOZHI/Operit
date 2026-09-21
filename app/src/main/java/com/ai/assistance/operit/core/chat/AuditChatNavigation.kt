package com.ai.assistance.operit.core.chat

/** Temporary native audit navigation; never persist the hidden child as the user's current chat. */
object AuditChatNavigation {
    private data class ReturnPoint(val childId: String, val returnChatId: String?)
    private var pending: ReturnPoint? = null

    @Synchronized
    fun rememberReturnChat(auditChildChatId: String, returnChatId: String?) {
        val child = auditChildChatId.trim().takeIf { it.isNotEmpty() } ?: return
        pending = ReturnPoint(child, returnChatId?.trim()?.takeIf { it.isNotEmpty() && it != child })
    }

    @Synchronized
    fun takeReturnChat(auditChildChatId: String): String? {
        val point = pending?.takeIf { it.childId == auditChildChatId.trim() } ?: return null
        pending = null
        return point.returnChatId
    }

    @Synchronized
    fun hasPendingReturnFor(auditChildChatId: String?): Boolean =
        pending?.let { it.childId == auditChildChatId?.trim() } == true

    @Synchronized
    fun carryReturnChat(fromAuditChildChatId: String, toAuditChildChatId: String) {
        val point = pending?.takeIf { it.childId == fromAuditChildChatId.trim() } ?: return
        val child = toAuditChildChatId.trim().takeIf { it.isNotEmpty() } ?: return
        pending = point.copy(childId = child)
    }
}
