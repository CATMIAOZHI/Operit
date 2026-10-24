package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R

/** Orbit's compact modal card. Long content gives way before the actions or the keyboard. */
@Composable
internal fun OrbitDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    dismissEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    OrbitModalWindow(
        onDismissRequest = onDismissRequest,
        dismissEnabled = dismissEnabled,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            // With an IME or large text, keeping two buttons fixed could leave no room for the field.
            val scrollWholeDialog = scrollable && maxHeight < 360.dp * LocalDensity.current.fontScale
            val contentScroll = rememberScrollState()
            Surface(
                modifier = Modifier.fillMaxWidth().semantics { paneTitle = title },
                shape = RoundedCornerShape(32.dp),
                color = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
            ) {
                Column(
                    Modifier.padding(24.dp)
                        .then(if (scrollWholeDialog) Modifier.verticalScroll(contentScroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // File names can be long too; the title must not push the actions off-screen.
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (scrollWholeDialog) Modifier else Modifier.weight(1f, fill = false))
                            .then(if (scrollable && !scrollWholeDialog) Modifier.verticalScroll(contentScroll) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (text != null) {
                            Column(
                                Modifier.fillMaxWidth().then(
                                    if (scrollable) Modifier else Modifier.weight(1f, fill = false),
                                ),
                            ) {
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                                }
                            }
                        }
                    }
                    if (confirmButton != null || dismissButton != null) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            confirmButton?.invoke()
                            dismissButton?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/** Full-width pill; using the button's content color also keeps disabled actions legible. */
@Composable
internal fun OrbitDialogAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                destructive -> colors.error
                secondary -> colors.surface
                else -> colors.onSurface
            },
            contentColor = when {
                destructive -> colors.onError
                secondary -> colors.onSurface
                else -> colors.surface
            },
        ),
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center)) {
            content()
        }
    }
}

@Composable
internal fun OrbitInfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = { Text(message) },
        confirmButton = {
            OrbitDialogAction(onClick = onDismiss) { Text(stringResource(R.string.orbit_dialog_got_it)) }
        },
    )
}

/** Used by shared pages only when they belong to an Orbit. */
@Composable
internal fun OrbitConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmLabel: String = stringResource(R.string.confirm),
    dismissLabel: String = stringResource(R.string.cancel),
    destructive: Boolean = false,
) {
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = message?.let { { Text(it) } },
        confirmButton = {
            OrbitDialogAction(onClick = onConfirm, destructive = destructive) { Text(confirmLabel) }
        },
        dismissButton = {
            OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(dismissLabel) }
        },
    )
}

/** The same rounded surface for compact action and selection menus throughout Orbit. */
@Composable
internal fun OrbitDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = rememberOrbitBackMotion()
    LaunchedEffect(expanded) { if (expanded) motion.reset() }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyUp) onDismissRequest()
                true
            } else false
        },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = android.os.Build.VERSION.SDK_INT < 34,
        ),
        offset = offset,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        content = {
            OrbitPopupBack(motion, onDismissRequest, animateWindow = true)
            content()
        },
    )
}
