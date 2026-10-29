package com.ai.assistance.operit.ui.floating.ui.window.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.services.floating.FloatingOrbit
import com.ai.assistance.operit.ui.features.chat.components.ChatAppearance
import com.ai.assistance.operit.ui.features.chat.components.ChatStyle
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitName
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitActivity
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitBubbleColor
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitCardsInApp
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitOptionSend
import com.ai.assistance.operit.ui.features.orbit.OrbitAppearanceTheme
import com.ai.assistance.operit.ui.features.orbit.orbitName
import com.ai.assistance.operit.ui.features.orbit.rememberOrbitActivity
import com.ai.assistance.operit.ui.floating.FloatContext

/** The Orbit whose chat [chatId] is, following renames and new colors; null for a normal chat. */
@Composable
internal fun rememberFloatingOrbit(chatId: String?): OrbitProfile? {
    val context = LocalContext.current
    val preferences = remember(context) { OrbitPreferences.getInstance(context.applicationContext) }
    // The settings the app already read, so an Orbit window never shows a normal chat for a frame.
    val settings by preferences.settingsFlow.collectAsState(initial = preferences.loadedSnapshot())
    return FloatingOrbit.owner(settings, chatId)
}

/**
 * The window showing Orbit's chat looks and behaves like Orbit's page: its colors, its name, reply
 * options sent with a tap, and cards that need the app opening Orbit there.
 */
@Composable
internal fun FloatingOrbitScope(floatContext: FloatContext, orbit: OrbitProfile, chatId: String, content: @Composable () -> Unit) {
    val service = floatContext.chatService
    OrbitAppearanceTheme(orbit, updateAppAppearance = false) {
        CompositionLocalProvider(
            LocalOrbitChatLook provides true,
            LocalOrbitName provides orbitName(orbit),
            LocalOrbitActivity provides rememberOrbitActivity(orbit, chatId),
            LocalOrbitOptionSend provides remember(service) {
                { optionChatId: String, text: String, waiting: () -> Boolean, onLost: () -> Unit ->
                    service?.sendOrbitOption(optionChatId, text, waiting, onLost) == true
                }
            },
            LocalOrbitCardsInApp provides remember(service, chatId) {
                {
                    service?.let { FloatingOrbit.openInApp(it, chatId) }
                    floatContext.onModeChange(com.ai.assistance.operit.ui.floating.FloatingMode.BALL)
                }
            },
            content = content,
        )
    }
}

/** Orbit's bubbles in the window: the same as on its page, whatever style normal chats use. */
@Composable
internal fun ChatAppearance.forOrbit(): ChatAppearance {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val user = LocalOrbitBubbleColor.current
    return copy(
        chatStyle = ChatStyle.BUBBLE,
        cursorUserBubbleLiquidGlass = false, cursorUserBubbleWaterGlass = false,
        bubbleUserBubbleLiquidGlass = false, bubbleUserBubbleWaterGlass = false,
        bubbleAiBubbleLiquidGlass = false, bubbleAiBubbleWaterGlass = false,
        bubbleUserRoundedCornersEnabled = true, bubbleAiRoundedCornersEnabled = true,
        userMessageColor = user,
        aiMessageColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFE9E9EB),
        userTextColor = if (user.luminance() > 0.5f) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
        aiTextColor = if (dark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E),
        bubbleUserImageStyle = null, bubbleAiImageStyle = null,
        chatAreaHorizontalPadding = 12f,
    )
}
