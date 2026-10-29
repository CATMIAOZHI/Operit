package com.ai.assistance.operit.ui.features.orbit

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.pet.CreatePetDialog
import com.ai.assistance.operit.pet.PET_BUBBLE_WIDTH_DP
import com.ai.assistance.operit.pet.PetAnimation
import com.ai.assistance.operit.pet.PetAnimationFiles
import com.ai.assistance.operit.pet.PetAssets
import com.ai.assistance.operit.pet.PetCompanion
import com.ai.assistance.operit.pet.PetEdge
import com.ai.assistance.operit.pet.PetMediaType
import com.ai.assistance.operit.pet.PetPreferences
import com.ai.assistance.operit.pet.PetProfile
import com.ai.assistance.operit.pet.bubbleOccupiesSpace
import com.ai.assistance.operit.pet.displayName
import com.ai.assistance.operit.pet.dockPet
import com.ai.assistance.operit.pet.importPetAnimation
import com.ai.assistance.operit.pet.isReady
import com.ai.assistance.operit.pet.label
import com.ai.assistance.operit.pet.mimeTypes
import com.ai.assistance.operit.pet.petWidth
import com.ai.assistance.operit.pet.removePet
import com.ai.assistance.operit.ui.features.settings.components.SettingsAlertDialog
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogTextButton
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The pet companion's settings in Orbit's look: the preview first, then the pets, how the current
 * one looks and where it shows, each as one rounded group. Same settings as the app's pet section.
 */
@Composable
internal fun OrbitPetSettingsPage(onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val preferences = remember { PetPreferences.get(context) }
    val settings by preferences.settings.collectAsState()
    val enabled by preferences.enabled.collectAsState()
    val usePetEntry by preferences.usePetEntry.collectAsState()
    val pets by preferences.pets.collectAsState()
    val selectedId by preferences.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    var creatingPet by rememberSaveable { mutableStateOf(false) }
    var showFiles by rememberSaveable(selectedId) { mutableStateOf(!settings.isReady) }
    var previewAnimation by rememberSaveable(selectedId) { mutableStateOf(PetAnimation.IDLE) }
    var pendingPetId by rememberSaveable { mutableStateOf("") }
    var pendingAnimation by rememberSaveable { mutableStateOf(PetAnimation.IDLE) }
    var deletingPet by remember { mutableStateOf<PetProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var size by remember(settings.sizeDp) { mutableFloatStateOf(settings.sizeDp) }
    var opacity by remember(settings.opacity) { mutableFloatStateOf(settings.opacity) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = preferences.pets.value.firstOrNull { it.id == pendingPetId }
        if (uri != null && target != null) scope.launch {
            importing = true
            importMessage = null
            try {
                importMessage = importPetAnimation(context, preferences, target, pendingAnimation, uri)
            } finally {
                importing = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        preferences.update { it.copy(overlay = Settings.canDrawOverlays(context)) }
    }

    SettingsDialogs(SettingsDialogStyle.ORBIT) {
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") { OrbitPageHeader(stringResource(R.string.pet_title), onBack) }
            item("preview") {
                PetGroup {
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val width = petWidth(maxWidth.value, size, PET_BUBBLE_WIDTH_DP.toFloat(), settings.edge,
                            bubbleOccupiesSpace(settings.showBubble)).dp
                        val anchor = dockPet(settings.edge, 0.5f, 0.5f)
                        PetCompanion(
                            settings = settings.copy(sizeDp = size, opacity = opacity), onDrag = {}, onDragEnd = {},
                            onToggleBubble = { preferences.update { it.copy(showBubble = !it.showBubble) } },
                            anchorX = anchor.x, anchorY = anchor.y,
                            modifier = Modifier.width(width).heightIn(max = 320.dp), preview = true,
                            previewAnimation = previewAnimation,
                        )
                    }
                    LazyRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(PetAnimation.entries, key = { it.name }) { animation ->
                            FilterChip(
                                selected = previewAnimation == animation,
                                onClick = { previewAnimation = animation },
                                label = { Text(stringResource(animation.label())) },
                            )
                        }
                    }
                    if (settings.mediaType != PetMediaType.ATLAS && previewAnimation !in settings.animations) {
                        PetNote(stringResource(R.string.pet_animation_fallback), Modifier.padding(horizontal = 20.dp))
                    }
                    Spacer(Modifier.size(12.dp))
                }
            }
            item("switch") {
                PetGroup {
                    PetSwitchRow(stringResource(R.string.pet_master_switch), stringResource(R.string.pet_description), enabled) {
                        preferences.setEnabled(it)
                    }
                }
            }

            item("library-title") { SectionTitle(stringResource(R.string.pet_library_count, pets.size)) }
            item("library") {
                PetGroup(Modifier.selectableGroup()) {
                    pets.forEach { pet ->
                        PetChoiceRow(
                            pet.settings.displayName(), stringResource(pet.settings.mediaType.label()),
                            selected = pet.id == selectedId,
                        ) {
                            preferences.select(pet.id)
                            importMessage = null
                        }
                        PetDivider()
                    }
                    PetActionRow("plus", stringResource(R.string.pet_create)) { creatingPet = true }
                    if (pets.size > 1) {
                        PetDivider()
                        PetActionRow("trash-2", stringResource(R.string.pet_remove), destructive = true) {
                            deletingPet = pets.firstOrNull { it.id == selectedId }
                        }
                    }
                }
            }
            item("library-hint") { PetNote(stringResource(R.string.pet_library_hint), Modifier.padding(horizontal = 8.dp)) }

            item("current-title") { SectionTitle(settings.displayName()) }
            item("current") {
                PetGroup {
                    OutlinedTextField(
                        value = settings.name,
                        onValueChange = { name -> preferences.update { it.copy(name = name.take(80)) } },
                        label = { Text(stringResource(R.string.pet_name)) }, singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                    PetNote(
                        stringResource(R.string.pet_format_value, stringResource(settings.mediaType.label())),
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    PetDivider()
                    PetActionRow(
                        "film",
                        stringResource(if (showFiles) R.string.pet_collapse_animation_files else R.string.pet_animation_files),
                        trailing = if (showFiles) "chevron-up" else "chevron-down",
                    ) { showFiles = !showFiles }
                    if (showFiles) {
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
                            showTitle = false,
                        )
                    }
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                        PetNote(stringResource(R.string.pet_importing), Modifier.padding(horizontal = 20.dp))
                    }
                    importMessage?.let { (message, failed) ->
                        Text(
                            stringResource(message), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                }
            }

            item("look-title") { SectionTitle(stringResource(R.string.orbit_pet_section_look)) }
            item("look") {
                PetGroup {
                    PetSliderRow(stringResource(R.string.pet_size_value, (size / 80f * 100).roundToInt()),
                        size, 48f..128f, { size = it }) { preferences.update { it.copy(sizeDp = size) } }
                    PetSliderRow(stringResource(R.string.pet_transparency_value, ((1f - opacity) * 100).roundToInt()),
                        1f - opacity, 0f..0.7f, { opacity = 1f - it }) { preferences.update { it.copy(opacity = opacity) } }
                    PetDivider()
                    PetSwitchRow(stringResource(R.string.pet_animation), null, settings.animated) { on ->
                        preferences.update { it.copy(animated = on) }
                    }
                    PetDivider()
                    PetSwitchRow(stringResource(R.string.pet_drag_animation), null, settings.dragAnimation) { on ->
                        preferences.update { it.copy(dragAnimation = on) }
                    }
                    PetDivider()
                    PetSwitchRow(stringResource(R.string.pet_show_bubble), null, settings.showBubble) { on ->
                        preferences.update { it.copy(showBubble = on) }
                    }
                }
            }

            item("show-title") { SectionTitle(stringResource(R.string.orbit_pet_section_show)) }
            item("show") {
                PetGroup {
                    PetSwitchRow(stringResource(R.string.pet_in_app), null, settings.inApp) { on ->
                        preferences.update { it.copy(inApp = on) }
                    }
                    PetDivider()
                    PetSwitchRow(stringResource(R.string.pet_overlay), null, settings.overlay) { on ->
                        if (on && !Settings.canDrawOverlays(context)) {
                            permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        } else {
                            preferences.update { it.copy(overlay = on) }
                        }
                    }
                }
            }
            item("entry-title") { SectionTitle(stringResource(R.string.pet_floating_entry)) }
            item("entry") {
                PetGroup(Modifier.selectableGroup()) {
                    PetChoiceRow(stringResource(R.string.pet_entry_pet), null, usePetEntry) { preferences.setUsePetEntry(true) }
                    PetDivider()
                    PetChoiceRow(stringResource(R.string.pet_entry_legacy), null, !usePetEntry) { preferences.setUsePetEntry(false) }
                }
            }
            item("entry-hint") { PetNote(stringResource(R.string.pet_entry_hint), Modifier.padding(horizontal = 8.dp)) }
            item("dock-title") { SectionTitle(stringResource(R.string.pet_dock_edge)) }
            item("dock") {
                PetGroup(Modifier.selectableGroup()) {
                    PetEdge.entries.forEachIndexed { index, edge ->
                        PetChoiceRow(stringResource(edgeLabel(edge)), null, settings.edge == edge) {
                            preferences.update {
                                val anchor = dockPet(edge, it.x, it.y)
                                it.copy(edge = edge, x = anchor.x, y = anchor.y)
                            }
                        }
                        if (index < PetEdge.entries.lastIndex) PetDivider()
                    }
                }
            }
            item("dock-hint") { PetNote(stringResource(R.string.pet_dock_hint), Modifier.padding(horizontal = 8.dp)) }

            item("reset-title") { SectionTitle(stringResource(R.string.orbit_pet_section_reset)) }
            item("reset") {
                PetGroup {
                    PetActionRow("rotate-ccw", stringResource(R.string.pet_reset_appearance)) {
                        preferences.update { it.copy(sizeDp = 80f, opacity = 1f, showBubble = true) }
                    }
                    PetDivider()
                    PetActionRow("move", stringResource(R.string.pet_reset_position)) {
                        preferences.update { it.copy(x = 0f, y = 0.55f, edge = PetEdge.LEFT) }
                    }
                }
            }
            item("import-note") { PetNote(stringResource(R.string.pet_import_description), Modifier.padding(horizontal = 8.dp)) }
        }

        deletingPet?.let { pet ->
            SettingsAlertDialog(
                onDismissRequest = { deletingPet = null },
                title = { Text(stringResource(R.string.pet_remove)) },
                text = { Text(stringResource(R.string.pet_remove_description)) },
                confirmButton = {
                    SettingsDialogTextButton(onClick = {
                        removePet(context, preferences, pet)
                        deletingPet = null
                    }) { Text(stringResource(R.string.pet_remove)) }
                },
                dismissButton = {
                    SettingsDialogTextButton(onClick = { deletingPet = null }) { Text(stringResource(R.string.pet_cancel)) }
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
}

private fun edgeLabel(edge: PetEdge): Int = when (edge) {
    PetEdge.LEFT -> R.string.pet_edge_left
    PetEdge.RIGHT -> R.string.pet_edge_right
    PetEdge.TOP -> R.string.pet_edge_top
    PetEdge.BOTTOM -> R.string.pet_edge_bottom
}

@Composable
private fun PetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun PetDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PetNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PetSwitchRow(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) PetNote(note, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PetChoiceRow(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) PetNote(sub, Modifier.padding(top = 2.dp))
        }
        Icon(orbitIcon(if (selected) "circle-check" else "circle"), null, Modifier.size(24.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PetActionRow(
    icon: String, label: String, destructive: Boolean = false, trailing: String? = null, onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(orbitIcon(icon), null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tint)
        if (trailing != null) {
            Icon(orbitIcon(trailing), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PetSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}
