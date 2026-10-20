package com.ai.assistance.operit.ui.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.launch

/**
 * The permission of an Orbit chat, or null in any other chat: fast auto-review, or full access when
 * the user picked it for Orbit. ToolPermissionSystem enforces it; the main chat's controls show it.
 */
@Composable
fun rememberOrbitLockedStop(chatId: String?): ToolPermissionStop? {
    val context = LocalContext.current
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val settings by preferences.settingsFlow.collectAsState(initial = preferences.snapshot)
    val fullAccess by remember { ToolPermissionSystem.getInstance(context).orbitFullAccessFlow }
        .collectAsState(initial = false)
    return if (settings.orbitForChat(chatId) == null) null
        else if (fullAccess) ToolPermissionStop.ALLOW else ToolPermissionStop.AUTO_REVIEW_FAST
}

/**
 * Orbit's own two-stop permission: fast auto-review on the left, full access on the right. It
 * reads and writes Orbit's setting, separate from the main app's five stops.
 */
@Composable
fun OrbitAccessSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val system = remember { ToolPermissionSystem.getInstance(context) }
    val fullAccess by system.orbitFullAccessFlow.collectAsState(initial = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var value by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(fullAccess) { fullAccess?.let { value = if (it) 1f else 0f } }
    val shown = value ?: return
    val full = shown >= 0.5f
    fun pick(next: Boolean) {
        value = if (next) 1f else 0f
        if (next != fullAccess) scope.launch { system.setOrbitFullAccess(next) }
    }
    val label = stringResource(if (full) R.string.orbit_access_full else R.string.orbit_access_review)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = shown,
            onValueChange = { value = it },
            onValueChangeFinished = { pick((value ?: 0f) >= 0.5f) },
            valueRange = 0f..1f,
            // Two choices, read out by name: a double tap switches, a swipe up/down picks a side.
            modifier = Modifier.fillMaxWidth().height(32.dp).onPreviewKeyEvent { e ->
                // Arrow keys jump straight to a side instead of moving the handle a little.
                val next = when (e.key) {
                    Key.DirectionRight, Key.DirectionUp, Key.MoveEnd, Key.PageUp -> true
                    Key.DirectionLeft, Key.DirectionDown, Key.MoveHome, Key.PageDown -> false
                    else -> return@onPreviewKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown) pick(next)
                true
            }.clearAndSetSemantics {
                stateDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(if (full) 1f else 0f, 0f..1f)
                onClick { pick(!full); true }
                // Any step up means full access, any step down means auto review.
                setProgress { target -> pick(target > (if (full) 1f else 0f) || (full && target >= 1f)); true }
            },
        )
        Text(
            text = stringResource(if (full) R.string.orbit_access_full_desc else R.string.orbit_access_review_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [rememberOrbitLockedStop] for the main chat, which is where an Orbit chat is shown. */
@Composable
fun rememberMainChatOrbitLockedStop(): ToolPermissionStop? {
    val context = LocalContext.current
    val chatIdFlow = remember {
        com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context.applicationContext)
            .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN)
            .currentChatId
    }
    val chatId by chatIdFlow.collectAsState()
    return rememberOrbitLockedStop(chatId)
}

/**
 * The whole tool permission choice as one control: five stops, the most restrictive on the left.
 *
 * The name of the stop the handle sits on is shown above it and follows the finger while dragging,
 * so the range can be read without leaving the row. [onStopPreview] reports that same stop while
 * the finger moves, so a caller showing extra text for the stop can follow the drag too; only
 * [onStopSelected] is called on release.
 */
@Composable
fun PermissionStopSlider(
    stop: ToolPermissionStop,
    onStopSelected: (ToolPermissionStop) -> Unit,
    modifier: Modifier = Modifier,
    onStopPreview: ((ToolPermissionStop) -> Unit)? = null,
    /** Shows this stop greyed out and ignores input, as in an Orbit chat. */
    lockedStop: ToolPermissionStop? = null,
) {
    if (lockedStop != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = stringResource(lockedStop.labelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = lockedStop.ordinal.toFloat(),
                onValueChange = {},
                enabled = false,
                valueRange = 0f..(ToolPermissionStop.values().size - 1).toFloat(),
                steps = (ToolPermissionStop.values().size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
            Text(
                text = stringResource(R.string.orbit_permission_locked, stringResource(lockedStop.labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var sliderValue by remember { mutableStateOf(stop.ordinal.toFloat()) }
    LaunchedEffect(stop) { sliderValue = stop.ordinal.toFloat() }
    val stopCount = ToolPermissionStop.values().size

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(ToolPermissionStop.at(sliderValue).labelRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                sliderValue = value
                onStopPreview?.invoke(ToolPermissionStop.at(value))
            },
            onValueChangeFinished = { onStopSelected(ToolPermissionStop.at(sliderValue)) },
            valueRange = 0f..(stopCount - 1).toFloat(),
            steps = (stopCount - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

internal val ToolPermissionStop.labelRes: Int
    get() =
        when (this) {
            ToolPermissionStop.FORBID -> R.string.permission_level_forbid
            ToolPermissionStop.ASK -> R.string.permission_level_ask
            ToolPermissionStop.AUTO_REVIEW_STRICT -> R.string.permission_stop_auto_review_strict
            ToolPermissionStop.AUTO_REVIEW_FAST -> R.string.permission_stop_auto_review_fast
            ToolPermissionStop.ALLOW -> R.string.permission_stop_full_access
        }

internal val ToolPermissionStop.descriptionRes: Int
    get() =
        when (this) {
            ToolPermissionStop.FORBID -> R.string.permission_stop_forbid_description
            ToolPermissionStop.ASK -> R.string.permission_stop_ask_description
            ToolPermissionStop.AUTO_REVIEW_STRICT ->
                R.string.permission_stop_auto_review_strict_description
            ToolPermissionStop.AUTO_REVIEW_FAST ->
                R.string.permission_stop_auto_review_fast_description
            ToolPermissionStop.ALLOW -> R.string.permission_stop_allow_description
        }
