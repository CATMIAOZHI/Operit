package com.ai.assistance.operit.pet

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PetSettingsSection() {
    val context = LocalContext.current
    val preferences = remember { PetPreferences.get(context) }
    val settings by preferences.settings.collectAsState()
    val pets by preferences.pets.collectAsState()
    val selectedId by preferences.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    var choosingPet by remember { mutableStateOf(false) }
    var creatingPet by rememberSaveable { mutableStateOf(false) }
    var editingAnimations by rememberSaveable(selectedId) { mutableStateOf(!settings.isReady) }
    var pendingPetId by rememberSaveable { mutableStateOf("") }
    var pendingAnimation by rememberSaveable { mutableStateOf(PetAnimation.IDLE) }
    var deletingPet by remember { mutableStateOf<PetProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<Int?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    var size by remember(settings.sizeDp) { mutableFloatStateOf(settings.sizeDp) }
    var opacity by remember(settings.opacity) { mutableFloatStateOf(settings.opacity) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = preferences.pets.value.firstOrNull { it.id == pendingPetId }
        val animation = pendingAnimation
        if (uri != null && target != null) scope.launch {
            importing = true
            importMessage = null
            try {
                val asset = PetAssets.importPet(context.applicationContext, uri, target.settings.mediaType)
                val previous = target.settings.animations[animation]
                val saved = preferences.updatePet(target.id) {
                    it.copy(animations = it.animations + (animation to PetArtwork(asset.id, asset.name)))
                }
                if (!saved) {
                    PetAssets.deleteAfterRemoval(context.applicationContext, asset.id)
                } else if (previous != null) {
                    PetAssets.deleteAfterRemoval(context.applicationContext, previous.id)
                }
                importFailed = !saved
                importMessage = if (saved) R.string.pet_import_success else R.string.pet_import_target_removed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                importFailed = true
                importMessage = when ((error as? PetImportException)?.reason) {
                    PetImportException.Reason.TOO_LARGE -> R.string.pet_import_too_large
                    PetImportException.Reason.INVALID_PACK -> R.string.pet_import_invalid_pack
                    PetImportException.Reason.INVALID_IMAGE -> R.string.pet_import_invalid_image
                    PetImportException.Reason.INVALID_VIDEO -> R.string.pet_import_invalid_video
                    PetImportException.Reason.FORMAT_MISMATCH -> R.string.pet_format_mismatch
                    null -> R.string.pet_import_failed
                }
            } finally {
                importing = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        preferences.update { it.copy(overlay = Settings.canDrawOverlays(context)) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(stringResource(R.string.pet_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pet_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        Text(stringResource(R.string.pet_library_count, pets.size), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { choosingPet = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.pet_library_item, pets.indexOfFirst { it.id == selectedId } + 1,
                        settings.displayName(),
                    ),
                    Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = choosingPet, onDismissRequest = { choosingPet = false }) {
                pets.forEachIndexed { index, pet ->
                    DropdownMenuItem(
                        text = { Text(stringResource(
                            R.string.pet_library_item, index + 1,
                            pet.settings.displayName(),
                        )) },
                        onClick = { preferences.select(pet.id); choosingPet = false; importMessage = null },
                    )
                }
            }
        }
        Row {
            TextButton(onClick = { creatingPet = true }) {
                Text(stringResource(R.string.pet_create))
            }
            TextButton(
                enabled = pets.size > 1,
                onClick = { deletingPet = pets.first { it.id == selectedId } },
            ) { Text(stringResource(R.string.pet_remove)) }
        }
        Text(
            stringResource(R.string.pet_library_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = settings.name, onValueChange = { name -> preferences.update { it.copy(name = name.take(80)) } },
            label = { Text(stringResource(R.string.pet_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.pet_format_value, stringResource(settings.mediaType.label())),
            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { editingAnimations = !editingAnimations }) {
            Text(stringResource(if (editingAnimations) R.string.pet_collapse_animation_files else R.string.pet_animation_files))
        }
        if (editingAnimations) {
            PetAnimationFiles(
                settings, importing,
                onChoose = { animation ->
                    pendingPetId = selectedId
                    pendingAnimation = animation
                    document.launch(settings.mediaType.mimeTypes())
                },
                onClear = { animation ->
                    val previous = settings.animations[animation]
                    preferences.update { it.copy(animations = it.animations - animation) }
                    previous?.let { PetAssets.deleteAfterRemoval(context.applicationContext, it.id) }
                },
            )
        }
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text(stringResource(R.string.pet_importing), style = MaterialTheme.typography.bodySmall)
        }
        importMessage?.let {
            Text(
                stringResource(it), Modifier.padding(vertical = 8.dp),
                color = if (importFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.pet_preview), style = MaterialTheme.typography.labelMedium)
                BoxWithConstraints(
                    Modifier.fillMaxWidth().heightIn(min = 152.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val previewSettings = settings.copy(sizeDp = size, opacity = opacity)
                    val width = petWidth(maxWidth.value, size, PET_BUBBLE_WIDTH_DP.toFloat(), settings.edge, settings.showBubble).dp
                    val anchor = dockPet(settings.edge, 0.5f, 0.5f)
                    PetCompanion(
                        settings = previewSettings, onDrag = {}, onDragEnd = {},
                        onToggleBubble = { preferences.update { it.copy(showBubble = !it.showBubble) } },
                        anchorX = anchor.x, anchorY = anchor.y,
                        modifier = Modifier.width(width).heightIn(max = 320.dp), preview = true,
                    )
                }
            }
        }
        Text(stringResource(R.string.pet_size_value, (size / 80f * 100).roundToInt()))
        Slider(
            value = size, onValueChange = { size = it }, valueRange = 48f..128f,
            onValueChangeFinished = { preferences.update { it.copy(sizeDp = size) } },
        )
        Text(stringResource(R.string.pet_transparency_value, ((1f - opacity) * 100).roundToInt()))
        Slider(
            value = 1f - opacity, onValueChange = { opacity = 1f - it }, valueRange = 0f..0.7f,
            onValueChangeFinished = { preferences.update { it.copy(opacity = opacity) } },
        )
        PetToggle(stringResource(R.string.pet_in_app), settings.inApp) {
            preferences.update { value -> value.copy(inApp = it) }
        }
        PetToggle(stringResource(R.string.pet_overlay), settings.overlay) { enabled ->
            if (enabled && !Settings.canDrawOverlays(context)) {
                permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            } else {
                preferences.update { it.copy(overlay = enabled) }
            }
        }
        PetToggle(stringResource(R.string.pet_animation), settings.animated) {
            preferences.update { value -> value.copy(animated = it) }
        }
        PetToggle(stringResource(R.string.pet_show_bubble), settings.showBubble) {
            preferences.update { value -> value.copy(showBubble = it) }
        }
        Column {
            Text(stringResource(R.string.pet_dock_edge), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (edge in PetEdge.entries) {
                    FilterChip(
                        selected = settings.edge == edge,
                        onClick = {
                            preferences.update {
                                val anchor = dockPet(edge, it.x, it.y)
                                it.copy(edge = edge, x = anchor.x, y = anchor.y)
                            }
                        },
                        label = {
                            Text(stringResource(when (edge) {
                                PetEdge.LEFT -> R.string.pet_edge_left
                                PetEdge.RIGHT -> R.string.pet_edge_right
                                PetEdge.TOP -> R.string.pet_edge_top
                                PetEdge.BOTTOM -> R.string.pet_edge_bottom
                            }))
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.pet_dock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            stringResource(R.string.pet_import_description),
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            preferences.update { it.copy(sizeDp = 80f, opacity = 1f, showBubble = true) }
        }) {
            Text(stringResource(R.string.pet_reset_appearance))
        }
        TextButton(onClick = { preferences.update { it.copy(x = 0f, y = 0.55f, edge = PetEdge.LEFT) } }) {
            Text(stringResource(R.string.pet_reset_position))
        }
    }
    deletingPet?.let { pet ->
        AlertDialog(
            onDismissRequest = { deletingPet = null },
            title = { Text(stringResource(R.string.pet_remove)) },
            text = { Text(stringResource(R.string.pet_remove_description)) },
            confirmButton = {
                TextButton(onClick = {
                    val removed = preferences.remove(pet.id)
                    deletingPet = null
                    if (removed != null) {
                        removed.settings.animations.values.map { it.id }.distinct().forEach {
                            PetAssets.deleteAfterRemoval(context.applicationContext, it)
                        }
                    }
                }) { Text(stringResource(R.string.pet_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingPet = null }) { Text(stringResource(R.string.pet_cancel)) }
            },
        )
    }
    if (creatingPet) {
        CreatePetDialog(
            onDismiss = { creatingPet = false },
            onCreate = {
                preferences.add(it)
                creatingPet = false
                importMessage = null
            },
        )
    }
}

@Composable
private fun PetToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
