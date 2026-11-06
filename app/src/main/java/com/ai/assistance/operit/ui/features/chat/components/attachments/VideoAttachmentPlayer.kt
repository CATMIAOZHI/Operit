package com.ai.assistance.operit.ui.features.chat.components.attachments

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.ai.assistance.operit.R

@Composable
fun VideoAttachmentPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false
) {
    val context = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = autoPlay
    }

    DisposableEffect(player) {
        onDispose {
            try {
                player.stop()
                player.clearMediaItems()
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            StyledPlayerView(ctx).apply {
                this.player = player
                setErrorMessageProvider {
                    android.util.Pair.create(0, context.getString(R.string.video_attachment_playback_failed))
                }
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = modifier
    )
}
