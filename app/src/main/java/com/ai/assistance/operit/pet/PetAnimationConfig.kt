package com.ai.assistance.operit.pet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

@Composable
internal fun PetSettings.displayName(): String = name.ifBlank {
    stringResource(if (mediaType == PetMediaType.ATLAS) R.string.pet_builtin_name else R.string.pet_custom_name)
}

internal fun PetMediaType.label(): Int = when (this) {
    PetMediaType.ATLAS -> R.string.pet_format_codex
    PetMediaType.IMAGE -> R.string.pet_format_image
    PetMediaType.GIF -> R.string.pet_format_gif
    PetMediaType.VIDEO -> R.string.pet_format_video
}

internal fun PetMediaType.mimeTypes(): Array<String> = when (this) {
    PetMediaType.ATLAS -> arrayOf("application/zip", "application/x-zip-compressed")
    PetMediaType.IMAGE -> arrayOf("image/png", "image/webp")
    PetMediaType.GIF -> arrayOf("image/gif")
    PetMediaType.VIDEO -> arrayOf("video/mp4")
}

internal fun PetAnimation.label(): Int = when (this) {
    PetAnimation.IDLE -> R.string.pet_idle
    PetAnimation.THINKING -> R.string.pet_thinking
    PetAnimation.TOOL -> R.string.pet_tool
    PetAnimation.SUMMARIZING -> R.string.pet_summarizing
    PetAnimation.COMPLETE -> R.string.pet_complete
    PetAnimation.ERROR -> R.string.pet_error
    PetAnimation.ENDED -> R.string.pet_ended
    PetAnimation.GREETING -> R.string.pet_greeting
}

@Composable
internal fun CreatePetDialog(onDismiss: () -> Unit, onCreate: (PetSettings) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(PetMediaType.GIF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pet_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.pet_name)) },
                )
                Text(stringResource(R.string.pet_format), Modifier.padding(top = 16.dp))
                for (type in listOf(PetMediaType.GIF, PetMediaType.VIDEO, PetMediaType.IMAGE, PetMediaType.ATLAS)) {
                    Row(Modifier.fillMaxWidth().clickable { format = type }) {
                        RadioButton(selected = format == type, onClick = { format = type })
                        Text(stringResource(type.label()), Modifier.padding(top = 14.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onCreate(PetSettings(name = name.trim(), mediaType = format))
            }) { Text(stringResource(R.string.pet_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pet_cancel)) }
        },
    )
}

@Composable
internal fun PetAnimationFiles(
    settings: PetSettings, importing: Boolean,
    onChoose: (PetAnimation) -> Unit, onClear: (PetAnimation) -> Unit,
) {
    Text(
        stringResource(R.string.pet_animation_files), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        stringResource(if (settings.mediaType == PetMediaType.ATLAS) R.string.pet_codex_pack_hint else R.string.pet_animation_files_hint),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val animations = if (settings.mediaType == PetMediaType.ATLAS) listOf(PetAnimation.IDLE) else PetAnimation.entries
    for (animation in animations) {
        val artwork = settings.animations[animation]
        ListItem(
            headlineContent = {
                Text(stringResource(if (settings.mediaType == PetMediaType.ATLAS) R.string.pet_format_codex else animation.label()))
            },
            supportingContent = {
                Text(
                    artwork?.name ?: stringResource(
                        if (settings.mediaType == PetMediaType.ATLAS) R.string.pet_builtin_name
                        else if (animation == PetAnimation.IDLE) R.string.pet_idle_required
                        else R.string.pet_animation_fallback,
                    ), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row {
                    TextButton(enabled = !importing, onClick = { onChoose(animation) }) {
                        Text(stringResource(R.string.pet_choose_file))
                    }
                    if (artwork != null && (animation != PetAnimation.IDLE || settings.mediaType == PetMediaType.ATLAS)) {
                        IconButton(enabled = !importing, onClick = { onClear(animation) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.pet_clear_animation))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}
