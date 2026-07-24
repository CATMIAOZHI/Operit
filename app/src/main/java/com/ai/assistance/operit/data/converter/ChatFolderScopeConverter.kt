package com.ai.assistance.operit.data.converter

import androidx.room.TypeConverter
import com.ai.assistance.operit.data.model.ChatFolderScope

/** Room TypeConverter，将 ChatFolderScope 与稳定字符串互转。 */
class ChatFolderScopeConverter {
    @TypeConverter
    fun fromScope(scope: ChatFolderScope): String = scope.value

    @TypeConverter
    fun toScope(value: String): ChatFolderScope = ChatFolderScope.fromValue(value)
}
