package com.ai.assistance.operit.services

import com.ai.assistance.operit.data.model.ChatMessage

interface ChatServiceUiBridge {
    fun updateWebServerForCurrentChat(chatId: String)
    fun resetAttachmentPanelState()
    fun clearReplyToMessage()
    fun getReplyToMessage(): ChatMessage?
    /** Shows [message] as the one being replied to again, after a send that used it did not go out. */
    fun restoreReplyToMessage(message: ChatMessage) = Unit
}

object EmptyChatServiceUiBridge : ChatServiceUiBridge {
    override fun updateWebServerForCurrentChat(chatId: String) = Unit

    override fun resetAttachmentPanelState() = Unit

    override fun clearReplyToMessage() = Unit

    override fun getReplyToMessage(): ChatMessage? = null
}
