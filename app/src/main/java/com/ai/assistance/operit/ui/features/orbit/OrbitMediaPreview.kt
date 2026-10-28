package com.ai.assistance.operit.ui.features.orbit

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.components.attachments.VideoAttachmentPlayer
import java.io.File

/** Pictures, videos and sounds shown inside the app, with share and "open in another app" at the top. */
internal object OrbitMediaPreview {
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")
    private val PLAYABLE = setOf("mp4", "webm", "mkv", "mov", "3gp", "m4v", "mp3", "wav", "m4a", "ogg", "aac", "flac", "opus")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String) = ext(name) in IMAGE

    /** True when the file opens in one of Orbit's own viewers (picture, player or plain text). */
    fun canPreview(name: String) = ext(name) in IMAGE || ext(name) in PLAYABLE || OrbitTextPreview.canShow(name)

    @Composable
    fun Screen(file: File, onDismiss: () -> Unit) {
        when {
            isImage(file.name) -> OrbitImageViewer(file, file.name, onDismiss)
            ext(file.name) in PLAYABLE -> Player(file, onDismiss)
            else -> OrbitTextPreview.Screen(file, onDismiss)
        }
    }

    @Composable
    private fun Player(file: File, onDismiss: () -> Unit) {
        val context = LocalContext.current
        OrbitModalWindow(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoAttachmentPlayer(
                    uri = Uri.fromFile(file),
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 76.dp),
                    autoPlay = true,
                )
                OrbitViewerButton(Icons.Default.Close, stringResource(R.string.common_close),
                    Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp), onDismiss)
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                    OrbitViewerButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) {
                        shareOrbitFile(context) { file }
                    }
                    Spacer(Modifier.width(10.dp))
                    OrbitViewerButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file }
                    }
                }
            }
        }
    }
}

/** A round see-through button on a dark full-screen viewer. */
@Composable
internal fun OrbitViewerButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), modifier = modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(22.dp), tint = Color.White)
        }
    }
}
