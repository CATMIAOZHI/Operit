package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chat_recall_fts")
data class ChatRecallIndex(val content: String)
