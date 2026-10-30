package com.ai.assistance.operit.ui.features.orbit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.features.chat.components.ORBIT_CHAT_TIME_GAP_MS
import com.ai.assistance.operit.ui.features.chat.components.OrbitChatTimeLine
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picked messages of an Orbit chat as one picture, drawn with the chat's own bubbles: Orbit's
 * picture and name on top, the messages with their time lines, and a small mark at the bottom.
 */
internal object OrbitShareImage {
    private const val TAG = "OrbitShareImage"

    /** Wide enough to stay sharp, never wider than the phone, so bubbles keep their shape. */
    private const val MAX_WIDTH = 1440

    /** About 70 MB of pixels at most; a longer selection keeps its first part. */
    private const val MAX_HEIGHT = 12_000

    private const val FOLDER = "shared_images"
    private const val PREFIX = "orbit_chat_"

    /**
     * About three times the words 12,000px can hold. Messages past this are not drawn at all, so
     * picking a whole long chat does not lay out pages the picture would cut off anyway.
     */
    private const val MAX_WORDS = 12_000
    private const val MAX_MESSAGES = 120

    /** One loader for all shares; a software canvas cannot draw hardware bitmaps. */
    @Volatile private var softwareLoader: ImageLoader? = null

    private fun loader(context: Context): ImageLoader =
        softwareLoader ?: synchronized(this) {
            softwareLoader ?: ImageLoader.Builder(context.applicationContext).allowHardware(false).build()
                .also { softwareLoader = it }
        }

    class Result(val file: File, val cut: Boolean)

    /** The first of [messages] that fit in a picture, and whether any were left out. */
    suspend fun fitting(context: Context, messages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> =
        withContext(Dispatchers.Default) {
            var words = 0
            var cut = false
            val kept = ArrayList<ChatMessage>()
            for (message in messages) {
                if (kept.size >= MAX_MESSAGES || (kept.isNotEmpty() && words >= MAX_WORDS)) break
                // One very long message is shortened too, at a line break, so it is not laid out in full.
                val shown = if (message.content.length > MAX_WORDS) {
                    cut = true
                    val end = message.content.lastIndexOf('\n', MAX_WORDS).takeIf { it > MAX_WORDS / 2 } ?: MAX_WORDS
                    message.copy(content = message.content.substring(0, end))
                } else message
                words += OrbitMessageActions.shownText(context, shown).length
                kept += shown
            }
            kept to (cut || kept.size < messages.size)
        }

    /**
     * Draws [content] off screen and saves it as a PNG. [parent] carries the chat's theme and
     * Orbit's settings into the picture, so it looks like the chat on screen.
     */
    suspend fun render(context: Context, parent: CompositionContext, content: @Composable () -> Unit): Result {
        val activity = context.findActivity() ?: error("No activity")
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val width = activity.resources.displayMetrics.widthPixels.coerceIn(1, MAX_WIDTH)
        val loader = loader(activity)
        // Freed however this ends: drawn and saved, cancelled, or failed half way.
        var bitmap: Bitmap? = null
        try {
            val cut = withContext(Dispatchers.Main) {
                val compose = ComposeView(activity).apply {
                    setParentCompositionContext(parent)
                    setContent { CompositionLocalProvider(LocalImageLoader provides loader) { content() } }
                }
                val holder = ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(compose, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // Off screen, but still attached and drawn, so pictures loading late redraw.
                    translationX = (activity.resources.displayMetrics.widthPixels + width).toFloat()
                }
                root.addView(holder, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                try {
                    // Time for layout and for pictures in the messages to load.
                    delay(600)
                    val full = compose.height
                    check(full > 0) { "Nothing was drawn" }
                    val picture = Bitmap.createBitmap(width, full.coerceAtMost(MAX_HEIGHT), Bitmap.Config.ARGB_8888)
                    bitmap = picture
                    compose.draw(Canvas(picture))
                    full > MAX_HEIGHT
                } finally {
                    root.removeView(holder)
                }
            }
            val picture = bitmap ?: error("Nothing was drawn")
            return withContext(Dispatchers.IO) {
                val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
                // Pictures from earlier shares are cleared once other apps have had time to read them.
                val old = System.currentTimeMillis() - 60 * 60 * 1000L
                folder.listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < old }?.forEach { it.delete() }
                val file = File(folder, "$PREFIX${System.currentTimeMillis()}.png")
                file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Result(file, cut)
            }
        } finally {
            bitmap?.recycle()
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun failed(context: Context, e: Throwable) {
        AppLogger.e(TAG, "Could not make the chat picture", e)
        Toast.makeText(context, R.string.orbit_share_image_failed, Toast.LENGTH_SHORT).show()
    }
}

/** What the picture shows: Orbit on top, then the messages the way the chat draws them. */
@Composable
private fun OrbitSharePicture(
    host: OrbitMessageHost,
    messages: List<ChatMessage>,
    horizontalPadding: Dp,
    row: @Composable (ChatMessage) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.background).padding(top = 24.dp, bottom = 16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitAvatar(host.orbit(), 64, petVisible = false)
            Spacer(Modifier.height(8.dp))
            Text(host.orbitName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
            var previous: Long? = null
            for (message in messages) {
                val gap = previous?.let { message.timestamp - it >= ORBIT_CHAT_TIME_GAP_MS } != false
                if (gap) OrbitChatTimeLine(message.timestamp)
                previous = message.timestamp
                CompositionLocalProvider(
                    com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner provides
                        message.takeIf { it.sender == "ai" }?.let {
                            com.ai.assistance.operit.ui.common.dynamicui.DynamicUiOwner(
                                host.chatId, it.timestamp, it.selectedVariantIndex, it.content,
                            )
                        },
                ) { row(message.copy(contentStream = null)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_launcher_simple_foreground), null, Modifier.size(24.dp))
            Text("Operit Ry", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * Sharing picked messages as a picture. Returns what starts it; while it runs and after, a
 * full-screen preview shows the picture with share, open in another app and save.
 */
@Composable
internal fun rememberOrbitPictureShare(
    host: OrbitMessageHost,
    horizontalPadding: Dp,
    row: @Composable (ChatMessage) -> Unit,
): (List<ChatMessage>) -> Unit {
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val scope = rememberCoroutineScope()
    val currentRow by rememberUpdatedState(row)
    val currentHost by rememberUpdatedState(host)
    val currentPadding by rememberUpdatedState(horizontalPadding)
    var open by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    val job = remember { arrayOfNulls<Job>(1) }

    if (open) {
        OrbitSharePreview(file) {
            job[0]?.cancel()
            open = false
            file = null
        }
    }
    return remember {
        { messages ->
            job[0]?.cancel()
            file = null
            open = true
            val shown = currentHost
            val padding = currentPadding
            job[0] = scope.launch {
                try {
                    val (kept, left) = OrbitShareImage.fitting(context, messages)
                    val result = OrbitShareImage.render(context, parent) {
                        OrbitSharePicture(shown, kept, padding) { currentRow(it) }
                    }
                    file = result.file
                    if (left || result.cut) Toast.makeText(context, R.string.orbit_share_image_cut, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    open = false
                    OrbitShareImage.failed(context, e)
                }
            }
        }
    }
}

/** The picture full screen; a tall one scrolls. Share and open at the top, save at the bottom. */
@Composable
private fun OrbitSharePreview(file: File?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    OrbitModalWindow(onDismissRequest = onDismiss, dismissEnabled = !saving) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (file == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.orbit_share_image_making), color = Color.White.copy(alpha = 0.8f))
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .statusBarsPadding().navigationBarsPadding()
                        .padding(top = 76.dp, bottom = 84.dp),
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = stringResource(R.string.orbit_share_image_title),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                    OrbitViewerButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) { shareOrbitFile(context) { file } }
                    Spacer(Modifier.width(10.dp))
                    OrbitViewerButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file }
                    }
                }
                Surface(
                    onClick = {
                        saving = true
                        scope.launch {
                            val ok = saveToGallery(context, file)
                            saving = false
                            Toast.makeText(context, if (ok) R.string.orbit_chat_image_saved else R.string.orbit_chat_image_save_failed,
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !saving,
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).heightIn(min = 44.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(orbitIcon("download"), null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (saving) R.string.orbit_chat_image_saving else R.string.orbit_chat_image_save_short),
                            style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
            OrbitViewerButton(
                Icons.Default.Close, stringResource(R.string.common_close),
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
                enabled = !saving,
                onClick = onDismiss,
            )
        }
    }
}
