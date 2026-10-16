package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.compositionLocalOf

/**
 * True inside an Orbit chat. Orbit keeps its own clean look: messages show no avatars, names or
 * model/config line, whatever the user chose for regular chats.
 */
val LocalOrbitChatLook = compositionLocalOf { false }
