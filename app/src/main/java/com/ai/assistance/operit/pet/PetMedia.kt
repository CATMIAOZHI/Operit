package com.ai.assistance.operit.pet

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.repeatCount
import coil.request.onAnimationEnd
import com.ai.assistance.operit.R
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private class GifCompletion { var enabled = false }

@Composable
internal fun PetGif(
    assetId: String, animate: Boolean, loop: Boolean, restartKey: Int,
    onFinished: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current.applicationContext
    val finished by rememberUpdatedState(onFinished)
    val completion = remember(assetId, loop, restartKey) { GifCompletion() }
    val drawable by produceState<Result<Drawable>?>(null, assetId, loop, restartKey) {
        value = null
        value = try {
            val request = ImageRequest.Builder(context)
                .data(PetAssets.assetFile(context, assetId))
                .size(1024).allowHardware(false).crossfade(false).repeatCount(if (loop) -1 else 0)
                .onAnimationEnd {
                    if (!loop && completion.enabled) {
                        completion.enabled = false
                        finished()
                    }
                }
                // Each host owns its animation clock; pausing the preview must not pause the pet.
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                .build()
            when (val result = context.imageLoader.execute(request)) {
                is SuccessResult -> Result.success(result.drawable)
                else -> Result.failure(IllegalArgumentException("Unable to decode pet animation"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val image = drawable?.getOrNull()
    DisposableEffect(image, animate, completion) {
        val animation = image as? Animatable
        completion.enabled = animate
        if (animate) animation?.start() else animation?.stop()
        onDispose {
            // API 26/27 MovieDrawable.stop() also fires onAnimationEnd.
            // Pausing or disposing an old request must not complete a newer greeting.
            completion.enabled = false
            animation?.stop()
        }
    }
    LaunchedEffect(image, animate, completion) {
        // A valid single-frame GIF decodes to a static drawable with no end callback.
        if (image != null && image !is Animatable && animate && !loop) {
            delay(1500)
            if (completion.enabled) {
                completion.enabled = false
                finished()
            }
        }
    }
    Box(modifier) {
        if (drawable?.isFailure == true) {
            PetMediaError(Modifier.fillMaxSize())
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { if (it.drawable !== image) it.setImageDrawable(image) },
            )
        }
    }
}

@Composable
internal fun PetVideo(
    assetId: String, animate: Boolean, loop: Boolean, restartKey: Int,
    onFinished: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    val finished by rememberUpdatedState(onFinished)
    val looping by rememberUpdatedState(loop)
    var failed by remember(assetId) { mutableStateOf(false) }
    val exoPlayer = remember(assetId) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !looping) finished()
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(PetAssets.assetFile(context, assetId))))
        exoPlayer.prepare()
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    LaunchedEffect(exoPlayer, loop, restartKey) {
        exoPlayer.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (restartKey != 0) exoPlayer.seekTo(0)
    }
    LaunchedEffect(exoPlayer, animate) { exoPlayer.playWhenReady = animate }
    Box(modifier) {
        if (failed) {
            PetMediaError(Modifier.fillMaxSize())
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    (LayoutInflater.from(it).inflate(R.layout.pet_video_view, null) as StyledPlayerView)
                        .apply { this.player = exoPlayer }
                },
                update = { if (it.player !== exoPlayer) it.player = exoPlayer },
            )
        }
    }
}

@Composable
private fun PetMediaError(modifier: Modifier) {
    Icon(Icons.Default.BrokenImage, stringResource(R.string.pet_asset_unavailable), modifier)
}
