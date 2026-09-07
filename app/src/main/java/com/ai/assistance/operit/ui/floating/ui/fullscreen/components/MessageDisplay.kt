package com.ai.assistance.operit.ui.floating.ui.fullscreen.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.ui.window.screen.ChatMessagesView
import com.ai.assistance.operit.ui.floating.ui.window.viewmodel.rememberFloatingChatWindowModeViewModel

/** Voice and text modes share message rendering and the reading position. */
@Composable
fun MessageDisplay(
    floatContext: FloatContext,
    speechPreviewText: String,
    showSpeechOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberFloatingChatWindowModeViewModel(floatContext)
    Box(modifier) {
        ChatMessagesView(floatContext, viewModel)
        AnimatedVisibility(
            visible = showSpeechOverlay && speechPreviewText.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    speechPreviewText,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
