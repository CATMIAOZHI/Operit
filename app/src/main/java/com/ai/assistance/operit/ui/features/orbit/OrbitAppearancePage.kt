package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OrbitAppearancePage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val preferences = remember { OrbitPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    fun change(transform: (OrbitAppearance) -> OrbitAppearance) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                preferences.updateAppearance(orbit.id, transform)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, R.string.orbit_appearance_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }
    val appearance = orbit.appearance
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { OrbitPageHeader(stringResource(R.string.orbit_appearance_title), onBack) }
        item("preview") { AppearancePreview(orbit) }
        item("palette-title") { SectionTitle(stringResource(R.string.orbit_appearance_palette)) }
        item("palette") {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(OrbitChatPalette.entries, key = { it.name }) { palette ->
                        val label = stringResource(paletteLabel(palette))
                        val selected = appearance.palette == palette
                        val tint = orbitPaletteColor(palette, LocalOrbitAvatarColor.current)
                        Surface(
                            shape = CircleShape,
                            color = tint,
                            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(52.dp).clip(CircleShape)
                                .selectable(selected, enabled = !saving, role = Role.RadioButton,
                                    onClick = { change { it.copy(palette = palette) } })
                                .semantics { contentDescription = label },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (palette == OrbitChatPalette.AVATAR) OrbitAvatar(orbit, size = 42)
                                else if (selected) Icon(orbitIcon("check"), null, Modifier.size(22.dp),
                                    tint = if (tint.luminance() > 0.5f) Color(0xFF242426) else Color.White)
                            }
                        }
                    }
                }
            }
        }
        item("size-title") { SectionTitle(stringResource(R.string.orbit_appearance_avatar_size)) }
        item("size") {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.selectableGroup()) {
                    OrbitAvatarSize.entries.forEachIndexed { index, size ->
                        AppearanceChoice(stringResource(sizeLabel(size)), appearance.avatarSize == size, !saving) {
                            change { it.copy(avatarSize = size) }
                        }
                        if (index < OrbitAvatarSize.entries.lastIndex) HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item("mode-title") { SectionTitle(stringResource(R.string.orbit_appearance_mode)) }
        item("mode") {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.selectableGroup()) {
                    OrbitColorMode.entries.forEachIndexed { index, mode ->
                        AppearanceChoice(stringResource(modeLabel(mode)), appearance.mode == mode, !saving) {
                            change { it.copy(mode = mode) }
                        }
                        if (index < OrbitColorMode.entries.lastIndex) HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(orbitIcon(if (selected) "circle-check" else "circle"), null, Modifier.size(24.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppearancePreview(orbit: OrbitProfile) {
    val bubble = LocalOrbitBubbleColor.current
    val ai = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF303034) else Color(0xFFE9E9EB)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.width(230.dp).height(280.dp),
            shape = RoundedCornerShape(32.dp), border = BorderStroke(3.dp, bubble),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (orbit.appearance.avatarSize != OrbitAvatarSize.HIDDEN) {
                    OrbitAvatar(orbit, size = (orbit.appearance.avatarSize.dp * 0.55f).toInt())
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                    Text(orbitName(orbit), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.align(Alignment.End).fillMaxWidth(0.72f).height(42.dp).clip(RoundedCornerShape(16.dp)).background(bubble))
                Box(Modifier.align(Alignment.Start).fillMaxWidth(0.87f).height(28.dp).clip(RoundedCornerShape(16.dp)).background(ai))
                Box(Modifier.align(Alignment.End).fillMaxWidth(0.65f).height(24.dp).clip(RoundedCornerShape(16.dp)).background(bubble))
                Box(Modifier.align(Alignment.Start).fillMaxWidth(0.8f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(ai))
            }
        }
    }
}

private fun paletteLabel(value: OrbitChatPalette): Int = when (value) {
    OrbitChatPalette.AVATAR -> R.string.orbit_palette_avatar
    OrbitChatPalette.SKY -> R.string.orbit_palette_sky
    OrbitChatPalette.INK -> R.string.orbit_palette_ink
    OrbitChatPalette.SAND -> R.string.orbit_palette_sand
    OrbitChatPalette.BLUE -> R.string.orbit_palette_blue
    OrbitChatPalette.LAVENDER -> R.string.orbit_palette_lavender
    OrbitChatPalette.PINK -> R.string.orbit_palette_pink
    OrbitChatPalette.PEACH -> R.string.orbit_palette_peach
    OrbitChatPalette.GREEN -> R.string.orbit_palette_green
}

private fun sizeLabel(value: OrbitAvatarSize): Int = when (value) {
    OrbitAvatarSize.SMALL -> R.string.orbit_avatar_small
    OrbitAvatarSize.MEDIUM -> R.string.orbit_avatar_medium
    OrbitAvatarSize.LARGE -> R.string.orbit_avatar_large
    OrbitAvatarSize.EXTRA_LARGE -> R.string.orbit_avatar_extra_large
    OrbitAvatarSize.HIDDEN -> R.string.orbit_avatar_hidden
}

private fun modeLabel(value: OrbitColorMode): Int = when (value) {
    OrbitColorMode.SYSTEM -> R.string.orbit_mode_system
    OrbitColorMode.LIGHT -> R.string.orbit_mode_light
    OrbitColorMode.DARK -> R.string.orbit_mode_dark
}
