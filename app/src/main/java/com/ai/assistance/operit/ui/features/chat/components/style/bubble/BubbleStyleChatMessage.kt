
package com.ai.assistance.operit.ui.features.chat.components.style.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiBackdrop
import com.ai.assistance.operit.ui.features.chat.components.ChatMessageHeightMemory
import com.ai.assistance.operit.ui.features.chat.components.style.cursor.SummaryMessageComposable
import com.ai.assistance.operit.util.stream.Stream

/**
 * A composable function that renders chat messages in a bubble chat style.
 * Delegates to specialized composables based on message type.
 */
@Composable
fun BubbleStyleChatMessage(
    message: ChatMessage,
    userMessageColor: Color,
    aiMessageColor: Color,
    userTextColor: Color,
    aiTextColor: Color,
    systemMessageColor: Color,
    systemTextColor: Color,
    userMessageLiquidGlassEnabled: Boolean = false,
    userMessageWaterGlassEnabled: Boolean = false,
    aiMessageLiquidGlassEnabled: Boolean = false,
    aiMessageWaterGlassEnabled: Boolean = false,
    userBubbleImageStyle: BubbleImageStyleConfig? = null,
    aiBubbleImageStyle: BubbleImageStyleConfig? = null,
    bubbleUserRoundedCornersEnabled: Boolean = true,
    bubbleAiRoundedCornersEnabled: Boolean = true,
    bubbleUserContentPaddingLeft: Float = 12f,
    bubbleUserContentPaddingRight: Float = 12f,
    bubbleAiContentPaddingLeft: Float = 12f,
    bubbleAiContentPaddingRight: Float = 12f,
    initialThinkingExpanded: Boolean = false,
    allowExpandedThinkingFullHeight: Boolean = false,
    expandThinkToolsGroups: Boolean = false,
    forceShowThinkingProcess: Boolean = false,
    isHidden: Boolean = false,
    heightMemory: ChatMessageHeightMemory? = null,
    onDeleteMessage: ((Int) -> Unit)? = null,
    index: Int = -1,
    enableDialogs: Boolean = true,  // 新增参数：是否启用弹窗功能，默认启用
    enableToolDetailDialogs: Boolean? = null,  // 工具详情弹窗开关，null 时跟随 enableDialogs
    onRoleAvatarLongPress: ((String) -> Unit)? = null,
    showAssistantHeader: Boolean = true,
    onEditSummary: ((ChatMessage) -> Unit)? = null,
) {
    when (if (message.displayMode.isCollaborationEvent) "ai" else message.sender) {
        "user" -> {
            com.ai.assistance.operit.ui.features.chat.components.OrbitReactedUserMessage(message.timestamp) {
            BubbleUserMessageComposable(
                message = message,
                backgroundColor = userMessageColor,
                textColor = userTextColor,
                enableLiquidGlass = userMessageLiquidGlassEnabled,
                enableWaterGlass = userMessageWaterGlassEnabled,
                bubbleImageStyle = userBubbleImageStyle,
                bubbleRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                bubbleContentPaddingLeft = bubbleUserContentPaddingLeft,
                bubbleContentPaddingRight = bubbleUserContentPaddingRight,
                enableDialogs = enableDialogs,
            )
            }
        }
        "ai" -> {
            // Cards tune their text to a solid bubble; images and glass have no single color. A nearly
            // opaque bubble (and the default one's light tonal tint) is close enough to measure against.
            val backdrop = if (aiBubbleImageStyle == null && !aiMessageLiquidGlassEnabled && !aiMessageWaterGlassEnabled &&
                aiMessageColor.alpha >= .95f) aiMessageColor.copy(alpha = 1f) else Color.Unspecified
            CompositionLocalProvider(LocalDynamicUiBackdrop provides backdrop) {
            // An Orbit reply can be several messages sent one after another (and the cards its tools
            // sent): each is its own bubble, stacked, all still this one message for long-press,
            // reply and reactions.
            val orbitLook = com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook.current
            val pieces = remember(orbitLook, message.content, message.contentStream, message.displayMode) {
                if (orbitLook && message.contentStream == null && !message.displayMode.isCollaborationEvent)
                    com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech.pieces(message.content)
                else null
            }
            val bubble: @Composable (ChatMessage, ChatMessageHeightMemory?) -> Unit = { shown, memory ->
            BubbleAiMessageComposable(
                message = shown,
                showHeader = showAssistantHeader && !message.displayMode.isCollaborationEvent,
                inlineContent = if (message.displayMode.isCollaborationEvent) {
                    { com.ai.assistance.operit.ui.features.chat.components.CollaborationMessageCard(message, horizontalPadding = 0.dp) }
                } else null,
                backgroundColor = aiMessageColor,
                textColor = aiTextColor,
                enableLiquidGlass = aiMessageLiquidGlassEnabled,
                enableWaterGlass = aiMessageWaterGlassEnabled,
                bubbleImageStyle = aiBubbleImageStyle,
                bubbleRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                bubbleContentPaddingLeft = bubbleAiContentPaddingLeft,
                bubbleContentPaddingRight = bubbleAiContentPaddingRight,
                initialThinkingExpanded = initialThinkingExpanded,
                allowExpandedThinkingFullHeight = allowExpandedThinkingFullHeight,
                expandThinkToolsGroups = expandThinkToolsGroups,
                forceShowThinkingProcess = forceShowThinkingProcess,
                isHidden = isHidden,
                heightMemory = memory,
                enableDialogs = enableDialogs,
                enableToolDetailDialogs = enableToolDetailDialogs,
                onAvatarLongPressMention = onRoleAvatarLongPress,
            )
            }
            com.ai.assistance.operit.ui.features.chat.components.OrbitReactedAiMessage(message.timestamp) {
                if (pieces == null) {
                    bubble(message, heightMemory)
                } else {
                    // Always the same stack, even for one bubble: a reply that grows a second bubble
                    // while streaming keeps the first one as it is drawn.
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (heightMemory == null || isHidden) Modifier
                            else Modifier.onSizeChanged { heightMemory.updateMeasured(message.timestamp, it.height) }
                        ),
                    ) {
                        pieces.forEachIndexed { i, piece -> key(i) { bubble(message.copy(content = piece), null) } }
                    }
                }
            }
            }
        }
        "summary" -> {
            SummaryMessageComposable(
                message = message,
                backgroundColor = systemMessageColor.copy(alpha = 0.7f),
                textColor = systemTextColor,
                onDelete = {
                    if (index != -1) {
                        onDeleteMessage?.invoke(index)
                    }
                },
                enableDialog = enableDialogs,  // 传递弹窗启用状态
                onEdit = { editedMessage ->
                    onEditSummary?.invoke(editedMessage)
                }
            )
        }
    }
}
