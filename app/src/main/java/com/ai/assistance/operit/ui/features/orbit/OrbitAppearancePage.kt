package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        item("logo-title") { SectionTitle(stringResource(R.string.orbit_appearance_logo)) }
        item("logo") {
            LogoColors(orbit, enabled = !saving,
                onUser = { hex -> change { it.copy(logoUserColor = hex) } },
                onOrbit = { hex -> change { it.copy(logoOrbitColor = hex) } })
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

/** Colors offered for the logo; each one reads on the picture's white background. */
private val logoSwatches = listOf("#D94F70", "#E07A1F", "#C99A06", "#2E9E6A", "#1E88C9", "#5B5BD6", "#8E44AD", "#1C1B1F")

/**
 * The two colors of Orbit's logo: the core in the middle is the user, the ring around it is Orbit,
 * which always circles the user. Each can follow the theme or take its own color.
 */
@Composable
private fun LogoColors(orbit: OrbitProfile, enabled: Boolean, onUser: (String?) -> Unit, onOrbit: (String?) -> Unit) {
    val appearance = orbit.appearance
    val name = orbitName(orbit)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                    OrbitLogoAvatar(
                        activity = com.ai.assistance.operit.pet.PetActivity.IDLE,
                        animate = false,
                        modifier = Modifier.fillMaxSize().padding(11.dp),
                        userColor = orbitLogoColor(appearance.logoUserColor),
                        orbitColor = orbitLogoColor(appearance.logoOrbitColor),
                    )
                }
                Text(stringResource(R.string.orbit_appearance_logo_hint, name), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LogoColorRow(stringResource(R.string.orbit_appearance_logo_user), appearance.logoUserColor, enabled, onUser)
            LogoColorRow(stringResource(R.string.orbit_appearance_logo_orbit, name), appearance.logoOrbitColor, enabled, onOrbit)
        }
    }
}

@Composable
private fun LogoColorRow(label: String, current: String?, enabled: Boolean, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyLarge)
        LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            item("theme") {
                val selected = current == null
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.height(40.dp).clip(RoundedCornerShape(50))
                        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(null) }),
                ) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.orbit_appearance_logo_theme), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            items(logoSwatches, key = { it }) { hex ->
                val selected = current == hex
                val tint = orbitLogoColor(hex) ?: Color.Gray
                Surface(
                    shape = CircleShape,
                    color = tint,
                    border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(hex) })
                        .semantics { contentDescription = hex },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) Icon(orbitIcon("check"), null, Modifier.size(20.dp),
                            tint = if (tint.luminance() > 0.22f) Color(0xFF242426) else Color.White)
                    }
                }
            }
            // A color Orbit picked that is not among the swatches still shows as chosen.
            if (current != null && current !in logoSwatches) {
                item("custom") {
                    val tint = orbitLogoColor(current) ?: Color.Gray
                    Surface(shape = CircleShape, color = tint,
                        border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .selectable(true, enabled = enabled, role = Role.RadioButton, onClick = {})
                            .semantics { contentDescription = current }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(orbitIcon("check"), null, Modifier.size(20.dp),
                                tint = if (tint.luminance() > 0.22f) Color(0xFF242426) else Color.White)
                        }
                    }
                }
            }
        }
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
