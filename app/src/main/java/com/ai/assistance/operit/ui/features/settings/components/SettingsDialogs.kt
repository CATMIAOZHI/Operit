package com.ai.assistance.operit.ui.features.settings.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.ui.features.orbit.OrbitDialog
import com.ai.assistance.operit.ui.features.orbit.OrbitDialogAction
import com.ai.assistance.operit.ui.features.orbit.OrbitModalWindow

enum class SettingsDialogStyle { MATERIAL, ORBIT }

internal val LocalSettingsDialogStyle = staticCompositionLocalOf { SettingsDialogStyle.MATERIAL }
private val LocalOrbitDialogAction = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun SettingsDialogs(style: SettingsDialogStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsDialogStyle provides style, content = content)
}

/** Selection lists own their scrolling; only the window and its surface change with the host. */
@Composable
internal fun SettingsDialogWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    if (LocalSettingsDialogStyle.current == SettingsDialogStyle.ORBIT) {
        OrbitModalWindow(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth()) {
                content()
            }
        }
    } else {
        Dialog(onDismissRequest = onDismissRequest, content = content)
    }
}

@Composable
internal fun SettingsDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    color: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val orbit = LocalSettingsDialogStyle.current == SettingsDialogStyle.ORBIT
    Surface(
        modifier = modifier,
        shape = if (orbit) RoundedCornerShape(32.dp) else shape,
        color = if (orbit) MaterialTheme.colorScheme.surfaceContainerHigh else color,
        tonalElevation = if (orbit) 0.dp else tonalElevation,
        shadowElevation = if (orbit) 0.dp else shadowElevation,
        content = content,
    )
}

/** Only an embedded settings page opts in; the main app keeps its Material dialogs. */
@Composable
internal fun SettingsAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    orbitScrollable: Boolean = true,
) {
    if (LocalSettingsDialogStyle.current == SettingsDialogStyle.ORBIT) {
        OrbitDialog(
            onDismissRequest = onDismissRequest,
            title = { icon?.invoke(); title?.invoke() },
            text = text,
            confirmButton = {
                CompositionLocalProvider(LocalOrbitDialogAction provides false, content = confirmButton)
            },
            dismissButton = dismissButton?.let { button ->
                { CompositionLocalProvider(LocalOrbitDialogAction provides true, content = button) }
            },
            scrollable = orbitScrollable,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
        )
    }
}

/** These aliases also serve page buttons, which keep their original appearance outside a dialog. */
@Composable
internal fun SettingsDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val secondary = LocalOrbitDialogAction.current
    if (secondary != null) {
        OrbitDialogAction(
            onClick, modifier, enabled,
            destructive = colors.containerColor == MaterialTheme.colorScheme.error,
            secondary = secondary,
            content = content,
        )
    } else {
        Button(onClick, modifier, enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
    }
}

@Composable
internal fun SettingsDialogTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val secondary = LocalOrbitDialogAction.current
    if (secondary != null) {
        OrbitDialogAction(onClick, modifier, enabled, secondary = secondary, content = content)
    } else {
        TextButton(onClick, modifier, enabled, colors = colors, contentPadding = contentPadding, content = content)
    }
}
