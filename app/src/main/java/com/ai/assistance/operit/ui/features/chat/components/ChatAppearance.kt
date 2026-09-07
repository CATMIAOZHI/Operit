package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleImageStyleConfig
import com.ai.assistance.operit.ui.theme.getTextColorForBackground

/** The same appearance settings drive the main chat and floating conversation. */
@Immutable
data class ChatAppearance(
    val chatStyle: ChatStyle,
    val inputStyle: String,
    val cursorUserBubbleLiquidGlass: Boolean,
    val cursorUserBubbleWaterGlass: Boolean,
    val bubbleUserBubbleLiquidGlass: Boolean,
    val bubbleUserBubbleWaterGlass: Boolean,
    val bubbleAiBubbleLiquidGlass: Boolean,
    val bubbleAiBubbleWaterGlass: Boolean,
    val bubbleUserRoundedCornersEnabled: Boolean,
    val bubbleAiRoundedCornersEnabled: Boolean,
    val userMessageColor: Color,
    val aiMessageColor: Color,
    val userTextColor: Color,
    val aiTextColor: Color,
    val systemMessageColor: Color,
    val systemTextColor: Color,
    val thinkingBackgroundColor: Color,
    val thinkingTextColor: Color,
    val bubbleUserImageStyle: BubbleImageStyleConfig?,
    val bubbleAiImageStyle: BubbleImageStyleConfig?,
    val bubbleUserContentPaddingLeft: Float,
    val bubbleUserContentPaddingRight: Float,
    val bubbleAiContentPaddingLeft: Float,
    val bubbleAiContentPaddingRight: Float,
    val chatAreaHorizontalPadding: Float,
)

@Composable
fun rememberChatAppearance(): ChatAppearance {
    val context = LocalContext.current
    val preferencesManager = remember(context) { UserPreferencesManager.getInstance(context) }
    // Collect chat style from preferences
    val chatStyleSetting by preferencesManager.chatStyle.collectAsState(initial = UserPreferencesManager.CHAT_STYLE_BUBBLE)
    val chatStyle = remember(chatStyleSetting) {
        when (chatStyleSetting) {
            UserPreferencesManager.CHAT_STYLE_BUBBLE -> ChatStyle.BUBBLE
            else -> ChatStyle.CURSOR
        }
    }
    val inputStyle by
        preferencesManager.inputStyle.collectAsState(
            initial = UserPreferencesManager.INPUT_STYLE_CLASSIC,
        )
    val cursorUserBubbleFollowTheme by
        preferencesManager.cursorUserBubbleFollowTheme.collectAsState(initial = true)
    val cursorUserBubbleLiquidGlassRaw by
        preferencesManager.cursorUserBubbleLiquidGlass.collectAsState(initial = false)
    val cursorUserBubbleWaterGlass by
        preferencesManager.cursorUserBubbleWaterGlass.collectAsState(initial = false)
    val cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlassRaw && !cursorUserBubbleWaterGlass
    val bubbleUserBubbleLiquidGlassRaw by
        preferencesManager.bubbleUserBubbleLiquidGlass.collectAsState(initial = false)
    val bubbleUserBubbleWaterGlass by
        preferencesManager.bubbleUserBubbleWaterGlass.collectAsState(initial = false)
    val bubbleUserBubbleLiquidGlass =
        bubbleUserBubbleLiquidGlassRaw && !bubbleUserBubbleWaterGlass
    val bubbleAiBubbleLiquidGlassRaw by
        preferencesManager.bubbleAiBubbleLiquidGlass.collectAsState(initial = false)
    val bubbleAiBubbleWaterGlass by
        preferencesManager.bubbleAiBubbleWaterGlass.collectAsState(initial = false)
    val bubbleAiBubbleLiquidGlass =
        bubbleAiBubbleLiquidGlassRaw && !bubbleAiBubbleWaterGlass
    val cursorUserBubbleColorValue by
        preferencesManager.cursorUserBubbleColor.collectAsState(initial = null)
    val bubbleUserBubbleColorValue by
        preferencesManager.bubbleUserBubbleColor.collectAsState(initial = null)
    val bubbleAiBubbleColorValue by
        preferencesManager.bubbleAiBubbleColor.collectAsState(initial = null)
    val bubbleUserTextColorValue by
        preferencesManager.bubbleUserTextColor.collectAsState(initial = null)
    val bubbleAiTextColorValue by
        preferencesManager.bubbleAiTextColor.collectAsState(initial = null)
    val bubbleUserUseImage by
        preferencesManager.bubbleUserUseImage.collectAsState(initial = false)
    val bubbleAiUseImage by
        preferencesManager.bubbleAiUseImage.collectAsState(initial = false)
    val bubbleUserImageUri by preferencesManager.bubbleUserImageUri.collectAsState(initial = null)
    val bubbleAiImageUri by preferencesManager.bubbleAiImageUri.collectAsState(initial = null)
    val bubbleUserImageCropLeft by
        preferencesManager.bubbleUserImageCropLeft.collectAsState(initial = 0f)
    val bubbleUserImageCropTop by
        preferencesManager.bubbleUserImageCropTop.collectAsState(initial = 0f)
    val bubbleUserImageCropRight by
        preferencesManager.bubbleUserImageCropRight.collectAsState(initial = 0f)
    val bubbleUserImageCropBottom by
        preferencesManager.bubbleUserImageCropBottom.collectAsState(initial = 0f)
    val bubbleUserImageRepeatStart by
        preferencesManager.bubbleUserImageRepeatStart.collectAsState(initial = 0.35f)
    val bubbleUserImageRepeatEnd by
        preferencesManager.bubbleUserImageRepeatEnd.collectAsState(initial = 0.65f)
    val bubbleUserImageRepeatYStart by
        preferencesManager.bubbleUserImageRepeatYStart.collectAsState(initial = 0.35f)
    val bubbleUserImageRepeatYEnd by
        preferencesManager.bubbleUserImageRepeatYEnd.collectAsState(initial = 0.65f)
    val bubbleUserImageScale by
        preferencesManager.bubbleUserImageScale.collectAsState(initial = 1f)
    val bubbleAiImageCropLeft by
        preferencesManager.bubbleAiImageCropLeft.collectAsState(initial = 0f)
    val bubbleAiImageCropTop by
        preferencesManager.bubbleAiImageCropTop.collectAsState(initial = 0f)
    val bubbleAiImageCropRight by
        preferencesManager.bubbleAiImageCropRight.collectAsState(initial = 0f)
    val bubbleAiImageCropBottom by
        preferencesManager.bubbleAiImageCropBottom.collectAsState(initial = 0f)
    val bubbleAiImageRepeatStart by
        preferencesManager.bubbleAiImageRepeatStart.collectAsState(initial = 0.35f)
    val bubbleAiImageRepeatEnd by
        preferencesManager.bubbleAiImageRepeatEnd.collectAsState(initial = 0.65f)
    val bubbleAiImageRepeatYStart by
        preferencesManager.bubbleAiImageRepeatYStart.collectAsState(initial = 0.35f)
    val bubbleAiImageRepeatYEnd by
        preferencesManager.bubbleAiImageRepeatYEnd.collectAsState(initial = 0.65f)
    val bubbleAiImageScale by
        preferencesManager.bubbleAiImageScale.collectAsState(initial = 1f)
    val bubbleImageRenderMode by
        preferencesManager.bubbleImageRenderMode.collectAsState(
            initial = UserPreferencesManager.BUBBLE_IMAGE_RENDER_MODE_TILED_NINE_SLICE,
        )
    val bubbleUserRoundedCornersEnabled by
        preferencesManager.bubbleUserRoundedCornersEnabled.collectAsState(initial = false)
    val bubbleAiRoundedCornersEnabled by
        preferencesManager.bubbleAiRoundedCornersEnabled.collectAsState(initial = false)
    val bubbleUserContentPaddingLeft by
        preferencesManager.bubbleUserContentPaddingLeft.collectAsState(initial = 12f)
    val bubbleUserContentPaddingRight by
        preferencesManager.bubbleUserContentPaddingRight.collectAsState(initial = 12f)
    val bubbleAiContentPaddingLeft by
        preferencesManager.bubbleAiContentPaddingLeft.collectAsState(initial = 12f)
    val bubbleAiContentPaddingRight by
        preferencesManager.bubbleAiContentPaddingRight.collectAsState(initial = 12f)
    // Collect chat area horizontal padding from preferences
    val chatAreaHorizontalPadding by preferencesManager.chatAreaHorizontalPadding.collectAsState(initial = 16f)

    val defaultUserMessageColor = MaterialTheme.colorScheme.primaryContainer
    val defaultAiMessageColor = MaterialTheme.colorScheme.surface
    val cursorCustomUserMessageColor = cursorUserBubbleColorValue?.let(::Color)
    val bubbleCustomUserMessageColor = bubbleUserBubbleColorValue?.let(::Color)
    val bubbleCustomAiMessageColor = bubbleAiBubbleColorValue?.let(::Color)
    val bubbleCustomUserTextColor = bubbleUserTextColorValue?.let(::Color)
    val bubbleCustomAiTextColor = bubbleAiTextColorValue?.let(::Color)

    val userMessageColor =
        when (chatStyle) {
            ChatStyle.CURSOR -> {
                if (cursorUserBubbleFollowTheme) {
                    defaultUserMessageColor
                } else {
                    cursorCustomUserMessageColor ?: defaultUserMessageColor
                }
            }

            ChatStyle.BUBBLE -> bubbleCustomUserMessageColor ?: defaultUserMessageColor
        }
    val aiMessageColor =
        when (chatStyle) {
            ChatStyle.BUBBLE -> bubbleCustomAiMessageColor ?: defaultAiMessageColor
            ChatStyle.CURSOR -> defaultAiMessageColor
        }
    val userTextColor =
        when {
            chatStyle == ChatStyle.CURSOR && cursorUserBubbleFollowTheme ->
                MaterialTheme.colorScheme.onPrimaryContainer
            chatStyle == ChatStyle.BUBBLE && bubbleCustomUserTextColor != null ->
                bubbleCustomUserTextColor
            else -> getTextColorForBackground(userMessageColor.copy(alpha = 1f))
        }
    val aiTextColor =
        when {
            chatStyle == ChatStyle.BUBBLE && bubbleCustomAiTextColor != null ->
                bubbleCustomAiTextColor
            chatStyle == ChatStyle.BUBBLE ->
                getTextColorForBackground(aiMessageColor.copy(alpha = 1f))
            else -> MaterialTheme.colorScheme.onSurface
        }
    val systemMessageColor = MaterialTheme.colorScheme.surfaceVariant
    val systemTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thinkingBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val thinkingTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val bubbleUserImageStyle =
        remember(
            chatStyle,
            bubbleUserBubbleLiquidGlass,
            bubbleUserBubbleWaterGlass,
            bubbleUserUseImage,
            bubbleUserImageUri,
            bubbleUserImageCropLeft,
            bubbleUserImageCropTop,
            bubbleUserImageCropRight,
            bubbleUserImageCropBottom,
            bubbleUserImageRepeatStart,
            bubbleUserImageRepeatEnd,
            bubbleUserImageRepeatYStart,
            bubbleUserImageRepeatYEnd,
            bubbleUserImageScale,
            bubbleImageRenderMode,
        ) {
            val imageUri = bubbleUserImageUri
            if (
                chatStyle == ChatStyle.BUBBLE &&
                    !bubbleUserBubbleLiquidGlass &&
                    !bubbleUserBubbleWaterGlass &&
                    bubbleUserUseImage &&
                    !imageUri.isNullOrBlank()
            ) {
                BubbleImageStyleConfig(
                    imageUri = imageUri,
                    cropLeftRatio = bubbleUserImageCropLeft,
                    cropTopRatio = bubbleUserImageCropTop,
                    cropRightRatio = bubbleUserImageCropRight,
                    cropBottomRatio = bubbleUserImageCropBottom,
                    repeatXStartRatio = bubbleUserImageRepeatStart,
                    repeatXEndRatio = bubbleUserImageRepeatEnd,
                    repeatYStartRatio = bubbleUserImageRepeatYStart,
                    repeatYEndRatio = bubbleUserImageRepeatYEnd,
                    imageScale = bubbleUserImageScale,
                    renderMode = bubbleImageRenderMode,
                )
            } else {
                null
            }
        }

    val bubbleAiImageStyle =
        remember(
            chatStyle,
            bubbleAiUseImage,
            bubbleAiBubbleLiquidGlass,
            bubbleAiBubbleWaterGlass,
            bubbleAiImageUri,
            bubbleAiImageCropLeft,
            bubbleAiImageCropTop,
            bubbleAiImageCropRight,
            bubbleAiImageCropBottom,
            bubbleAiImageRepeatStart,
            bubbleAiImageRepeatEnd,
            bubbleAiImageRepeatYStart,
            bubbleAiImageRepeatYEnd,
            bubbleAiImageScale,
            bubbleImageRenderMode,
        ) {
            val imageUri = bubbleAiImageUri
            if (
                chatStyle == ChatStyle.BUBBLE &&
                    !bubbleAiBubbleLiquidGlass &&
                    !bubbleAiBubbleWaterGlass &&
                    bubbleAiUseImage &&
                    !imageUri.isNullOrBlank()
            ) {
                BubbleImageStyleConfig(
                    imageUri = imageUri,
                    cropLeftRatio = bubbleAiImageCropLeft,
                    cropTopRatio = bubbleAiImageCropTop,
                    cropRightRatio = bubbleAiImageCropRight,
                    cropBottomRatio = bubbleAiImageCropBottom,
                    repeatXStartRatio = bubbleAiImageRepeatStart,
                    repeatXEndRatio = bubbleAiImageRepeatEnd,
                    repeatYStartRatio = bubbleAiImageRepeatYStart,
                    repeatYEndRatio = bubbleAiImageRepeatYEnd,
                    imageScale = bubbleAiImageScale,
                    renderMode = bubbleImageRenderMode,
                )
            } else {
                null
            }
        }

    return ChatAppearance(
        chatStyle = chatStyle,
        inputStyle = inputStyle,
        cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
        cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
        bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
        bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
        bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
        bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
        bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
        bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
        userMessageColor = userMessageColor,
        aiMessageColor = aiMessageColor,
        userTextColor = userTextColor,
        aiTextColor = aiTextColor,
        systemMessageColor = systemMessageColor,
        systemTextColor = systemTextColor,
        thinkingBackgroundColor = thinkingBackgroundColor,
        thinkingTextColor = thinkingTextColor,
        bubbleUserImageStyle = bubbleUserImageStyle,
        bubbleAiImageStyle = bubbleAiImageStyle,
        bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
        bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
        bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
        bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
        chatAreaHorizontalPadding = chatAreaHorizontalPadding,
    )
}
