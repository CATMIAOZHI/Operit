package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
import com.ai.assistance.operit.ui.features.chat.components.LocalShowHiddenChatRows
import com.ai.assistance.operit.ui.features.settings.components.isOrbitDialogStyle

/**
 * Shared chat and settings surfaces are drawn both in the main app and inside an Orbit. Inside an
 * Orbit they take Orbit's window, which follows the predictive back swipe; the main app keeps
 * Material's own surfaces unchanged.
 */
@Composable
internal fun inOrbitSurface(): Boolean =
    // Orbit's developer view draws the raw chat, but it is still inside the Orbit.
    LocalOrbitChatLook.current || LocalShowHiddenChatRows.current || isOrbitDialogStyle()

@Composable
internal fun OrbitAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    // A dialog that ignores Back has nothing to animate.
    if (inOrbitSurface() && properties.dismissOnBackPress) {
        OrbitModalWindow(onDismissRequest = onDismissRequest, properties = properties, content = content)
    } else {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
    }
}

@Composable
internal fun OrbitAwareAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (inOrbitSurface()) {
        OrbitDialog(
            onDismissRequest = onDismissRequest,
            title = { title?.invoke() },
            text = text,
            // Like Material's dialog, the text scrolls itself when it needs to; a second scroll
            // around it would be measured without a height limit.
            scrollable = false,
            // Text buttons keep their meaning; one end-aligned row reads like the Material dialog.
            confirmButton = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
        )
    }
}

@Composable
internal fun OrbitAwareDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (inOrbitSurface()) {
        OrbitDropdownMenu(expanded, onDismissRequest, modifier, offset, content)
    } else {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset, content = content)
    }
}

/**
 * Material's exposed menu lets its popup take Back itself, so it cannot follow the swipe. Inside
 * an Orbit an Orbit menu sits in the same box instead, as wide as the field it opens from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExposedDropdownMenuBoxScope.OrbitAwareExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (inOrbitSurface()) {
        OrbitDropdownMenu(expanded, onDismissRequest, modifier.exposedDropdownSize(), content = content)
    } else {
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
    }
}
