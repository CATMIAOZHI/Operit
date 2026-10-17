package com.ai.assistance.operit.ui.features.packages.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.skill.SkillPackage
import com.ai.assistance.operit.data.preferences.SkillVisibilityPreferences
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.rememberSettingsCardColor

/** Where a skill came from, shown as one folder each on the all-skills page. */
internal enum class SkillSource(val icon: String, val title: Int, val description: Int) {
    LEARNED("sparkles", R.string.skill_folder_learned, R.string.skill_folder_learned_desc),
    USER("user-round", R.string.skill_folder_user, R.string.skill_folder_user_desc),
    BUILT_IN("package", R.string.skill_folder_builtin, R.string.skill_folder_builtin_desc),
}

private fun lucideIcon(name: String): ImageVector = CardIcons.get("lucide:$name") ?: Icons.Filled.Build

/**
 * The all-skills list split into folders by where each skill came from: learned from chats, added
 * by the user, or shipped with the app. Folders start closed so a long list stays readable;
 * searching opens every folder that has a match.
 */
@Composable
internal fun SkillSourceFolders(
    skills: List<SkillPackage>,
    learnedNames: Set<String>,
    builtInNames: Set<String>,
    searching: Boolean,
    visibility: SkillVisibilityPreferences,
    onOpen: (SkillPackage) -> Unit,
) {
    val cardColor = rememberSettingsCardColor()
    val grouped = remember(skills, learnedNames, builtInNames) {
        skills.groupBy { skill ->
            when (skill.name) {
                in learnedNames -> SkillSource.LEARNED
                in builtInNames -> SkillSource.BUILT_IN
                else -> SkillSource.USER
            }
        }.mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
    }
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 120.dp),
    ) {
        SkillSource.values().forEach { source ->
            val list = grouped[source].orEmpty()
            item(key = source.name) {
                val expanded = (searching && list.isNotEmpty()) || source.name in open
                SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    FolderHeader(source, list.size, expanded) {
                        if (list.isNotEmpty()) open = if (source.name in open) open - source.name else open + source.name
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            list.forEach { skill ->
                                SettingsRowDivider()
                                SkillRow(skill, source, visibility) { onOpen(skill) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(source: SkillSource, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "folder-arrow")
    val state = stringResource(if (expanded) R.string.skill_folder_expanded else R.string.skill_folder_collapsed)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = count > 0, onClick = onToggle)
            .semantics { if (count > 0) stateDescription = state }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(icon = lucideIcon(if (expanded) "folder-open" else "folder"), size = 40)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(source.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(source.description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(lucideIcon("chevron-right"), null, Modifier.size(20.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SkillRow(skill: SkillPackage, source: SkillSource, visibility: SkillVisibilityPreferences, onClick: () -> Unit) {
    var visibleToAi by remember(skill.name) { mutableStateOf(visibility.isSkillVisibleToAi(skill.name)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(lucideIcon(source.icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(skill.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (skill.description.isNotBlank()) {
                Text(skill.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        val switchLabel = stringResource(R.string.skill_visible_to_ai, skill.name)
        Switch(
            modifier = Modifier.semantics { contentDescription = switchLabel },
            checked = visibleToAi,
            onCheckedChange = { checked ->
                visibleToAi = checked
                visibility.setSkillVisibleToAi(skill.name, checked)
            },
        )
    }
}
