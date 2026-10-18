package com.ai.assistance.operit.ui.features.packages.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.skill.SkillPackage
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider

/** Where a skill came from, shown as one folder each on the all-skills page. */
internal enum class SkillSource(val icon: String, val title: Int, val description: Int) {
    LEARNED("sparkles", R.string.skill_folder_learned, R.string.skill_folder_learned_desc),
    USER("user-round", R.string.skill_folder_user, R.string.skill_folder_user_desc),
    BUILT_IN("package", R.string.skill_folder_builtin, R.string.skill_folder_builtin_desc),
    /** Learned skills put away after weeks without use; hidden from the AI until switched on. */
    ARCHIVED("archive", R.string.skill_folder_archived, R.string.skill_folder_archived_desc),
}

private fun lucideIcon(name: String): ImageVector = CardIcons.get("lucide:$name") ?: Icons.Filled.Build

private val FolderCorner = 20.dp
private val WholeCard = RoundedCornerShape(FolderCorner)
private val TopOfCard = RoundedCornerShape(topStart = FolderCorner, topEnd = FolderCorner)
private val BottomOfCard = RoundedCornerShape(bottomStart = FolderCorner, bottomEnd = FolderCorner)

/**
 * The all-skills list split into folders by where each skill came from: learned from chats, added
 * by the user, or shipped with the app. Folders start closed so a long list stays readable;
 * searching opens every folder that has a match. Learned skills that were archived for not being
 * used sit in their own folder; switching one on brings it back. Empty folders are left out.
 *
 * Each folder header and each skill row is its own list item, drawn as one card by their shapes,
 * so opening a folder of hundreds of skills only lays out the rows on screen.
 */
@Composable
internal fun SkillSourceFolders(
    skills: List<SkillPackage>,
    learnedNames: Set<String>,
    archivedNames: Set<String>,
    builtInNames: Set<String>,
    searching: Boolean,
    /** Each skill's saved switch, read off the main thread by the caller. */
    visibleToAi: Map<String, Boolean>,
    onVisibleChange: (String, Boolean) -> Unit,
    onOpen: (SkillPackage) -> Unit,
    onRestore: (String) -> Unit,
    /** A skill was switched back on for the AI; its idle clock should start fresh. */
    onShown: (String) -> Unit = {},
) {
    // Orbit's pages use quiet gray cards and plain icons instead of the theme color.
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val grouped = remember(skills, learnedNames, archivedNames, builtInNames) {
        skills.groupBy { skill ->
            when (skill.name) {
                in archivedNames -> if (skill.name in learnedNames) SkillSource.ARCHIVED else SkillSource.LEARNED
                in learnedNames -> SkillSource.LEARNED
                in builtInNames -> SkillSource.BUILT_IN
                else -> SkillSource.USER
            }
        }.mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
    }
    val folders = remember(grouped) { SkillSource.values().filter { grouped[it].orEmpty().isNotEmpty() } }
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
    ) {
        folders.forEachIndexed { index, source ->
            val list = grouped[source].orEmpty()
            val expanded = searching || source.name in open
            item(key = "folder-${source.name}", contentType = "folder") {
                FolderHeader(
                    source, list.size, expanded,
                    color = cardColor,
                    shape = if (expanded) TopOfCard else WholeCard,
                    modifier = Modifier.animateItem(placementSpec = tween(220))
                        .padding(top = if (index == 0) 0.dp else 12.dp),
                ) {
                    open = if (source.name in open) open - source.name else open + source.name
                }
            }
            if (expanded) {
                val lastName = list.last().name
                items(list, key = { "skill-${source.name}-${it.name}" }, contentType = { "skill" }) { skill ->
                    val last = skill.name == lastName
                    Column(
                        Modifier.animateItem(placementSpec = tween(220)).fillMaxWidth()
                            .background(cardColor, if (last) BottomOfCard else RectangleShape)
                            .padding(start = 8.dp, end = 8.dp, bottom = if (last) 6.dp else 0.dp),
                    ) {
                        SettingsRowDivider()
                        SkillRow(skill, source, visibleToAi[skill.name] ?: true, { onVisibleChange(skill.name, it) }, onRestore = { onRestore(skill.name) },
                            onShown = { onShown(skill.name) }) { onOpen(skill) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(
    source: SkillSource,
    count: Int,
    expanded: Boolean,
    color: Color,
    shape: Shape,
    modifier: Modifier,
    onToggle: () -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "folder-arrow")
    val state = stringResource(if (expanded) R.string.skill_folder_expanded else R.string.skill_folder_collapsed)
    val countLabel = pluralStringResource(R.plurals.skill_folder_count, count, count)
    Box(modifier.fillMaxWidth().background(color, shape).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(lucideIcon(if (expanded) "folder-open" else "folder"), null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(source.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(source.description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
                    .clearAndSetSemantics { contentDescription = countLabel }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Icon(lucideIcon("chevron-right"), null, Modifier.size(20.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillPackage,
    source: SkillSource,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onShown: () -> Unit,
    onClick: () -> Unit,
) {
    val archived = source == SkillSource.ARCHIVED
    // An archived skill is off for the AI by definition; switching it on asks for a restore, and
    // the row moves to its folder once that is saved.
    val visibleToAi = !archived && visible

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
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
        val switchLabel = stringResource(
            if (archived) R.string.skill_restore_switch else R.string.skill_visible_to_ai, skill.name)
        Switch(
            modifier = Modifier.semantics { contentDescription = switchLabel },
            checked = visibleToAi,
            onCheckedChange = { checked ->
                if (archived) {
                    if (checked) onRestore()
                } else {
                    onVisibleChange(checked)
                    if (checked) onShown()
                }
            },
        )
    }
}
