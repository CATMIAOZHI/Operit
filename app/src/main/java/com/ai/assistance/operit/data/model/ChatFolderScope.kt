package com.ai.assistance.operit.data.model

/** 文件夹作用域，通过稳定的字符串（非 ordinal）存储。 */
enum class ChatFolderScope(val value: String) {
    ALL("ALL"),
    FAVORITE("FAVORITE");

    companion object {
        fun fromValue(value: String): ChatFolderScope =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown ChatFolderScope: $value")
    }
}
