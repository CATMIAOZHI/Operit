package com.ai.assistance.operit.ui.features.packages.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.skill.SkillPackage
import com.ai.assistance.operit.ui.common.displays.MarkdownTextComposable
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons

/*
 * The skill sheets in the look of the settings and Orbit pages: a bottom sheet with quiet gray
 * 20dp cards, round icons, and one dark button for the main action.
 */

private val SheetCard = RoundedCornerShape(20.dp)

private fun sheetIcon(name: String): ImageVector = CardIcons.get("lucide:$name") ?: Icons.Filled.Build

@Composable
private fun sheetCardColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

@Composable
private fun SheetCardBox(
    modifier: Modifier = Modifier,
    color: Color = sheetCardColor(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = SheetCard, color = color, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun SheetRow(icon: String, text: String, trailing: String = "chevron-right", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(sheetIcon(icon), null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(sheetIcon(trailing), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** Dark for the main action, red for a delete, gray otherwise. */
@Composable
private fun SheetButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val (red, onRed) = com.ai.assistance.operit.ui.common.destructiveButtonColors()
    val color = when {
        destructive -> red
        primary -> MaterialTheme.colorScheme.onSurface
        else -> sheetCardColor()
    }
    val content = when {
        destructive -> onRed
        primary -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick, enabled = enabled && !busy, shape = CircleShape, color = color, contentColor = content,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One skill: what it does, its files, its instructions and its history. Deleting asks once more
 * in place, since it removes the files for good. [showLocation] is false for an Orbit, whose
 * folder path means nothing to the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillDetailSheet(
    skill: SkillPackage,
    detail: SkillDetailDialogData?,
    isLoading: Boolean,
    showLocation: Boolean,
    loadError: String?,
    deleting: Boolean,
    onHistory: (() -> Unit)?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    orbitStyle: Boolean = false,
) {
    var showInstructions by remember(skill.name) { mutableStateOf(false) }
    var confirmDelete by remember(skill.name) { mutableStateOf(false) }
    // A stable check reading the latest value, so the sheet is not rebuilt when deleting starts.
    val busy by rememberUpdatedState(deleting)
    // Keeps the sheet's own back gesture working when it opens from Orbit's pages.
    val sheetBack = com.ai.assistance.operit.ui.features.orbit.rememberOrbitSheetBack()
    sheetBack.Provide {
    SkillModalSheet(
        orbitStyle = orbitStyle,
        dismissEnabled = !deleting,
        onDismissRequest = { if (!deleting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = remember { { _: SheetValue -> !busy } }),
    ) {
        if (!orbitStyle) sheetBack.forward()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(sheetCardColor()), contentAlignment = Alignment.Center) {
                    Icon(sheetIcon("puzzle"), null, Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (skill.description.isNotBlank()) Text(skill.description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loadError != null) {
                SheetError(loadError)
                return@Column
            }
            if (isLoading || detail == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
                return@Column
            }
            SheetCardBox(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                SheetRow("file-text", stringResource(R.string.skill_detail_instructions),
                    trailing = if (showInstructions) "chevron-up" else "chevron-down") { showInstructions = !showInstructions }
                if (showInstructions) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // A long SKILL.md scrolls inside its own box, so the sheet keeps its height and
                    // the rest of the page stays reachable.
                    Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 8.dp)) {
                        MarkdownTextComposable(text = detail.skillContent, textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                onHistory?.let {
                    HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SheetRow("history", stringResource(R.string.orbit_skill_history), onClick = it)
                }
            }
            SheetLabel(stringResource(R.string.skill_detail_files))
            SheetCardBox {
                Text(stringResource(R.string.skillmgr_detail_counts, detail.fileCount, detail.folderCount),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail.directoryPreview.isNotBlank()) Surface(shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(detail.directoryPreview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp))
                }
                if (detail.hiddenEntryCount > 0) Text(stringResource(R.string.skillmgr_detail_more_items, detail.hiddenEntryCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showLocation) {
                SheetLabel(stringResource(R.string.skill_detail_location))
                SheetCardBox {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(detail.directoryPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.skill_detail_entry_file, detail.skillFilePath),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!confirmDelete) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.skillmgr_delete), color = com.ai.assistance.operit.ui.common.readableErrorColor())
                }
            } else {
                SheetCardBox(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.skill_delete_confirm_title, skill.name),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.skill_delete_confirm_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SheetButton(stringResource(R.string.cancel), Modifier.weight(1f), enabled = !deleting) { confirmDelete = false }
                        SheetButton(stringResource(R.string.skillmgr_delete), Modifier.weight(1f), destructive = true,
                            busy = deleting, onClick = onDelete)
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillModalSheet(
    orbitStyle: Boolean,
    dismissEnabled: Boolean,
    onDismissRequest: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (orbitStyle) {
        com.ai.assistance.operit.ui.features.orbit.OrbitModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            dismissEnabled = dismissEnabled,
            content = content,
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, content = content)
    }
}

/** A problem shown inside the sheet, so it is not hidden behind it. */
@Composable
private fun SheetError(message: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(sheetIcon("circle-alert"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** Adding a skill from a GitHub repository, a zip package, or typed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillImportSheet(
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    repoUrl: String,
    onRepoUrlChange: (String) -> Unit,
    zipFileName: String,
    onPickZip: () -> Unit,
    skillId: String,
    onSkillIdChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
    attachments: List<String>,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    importing: Boolean,
    error: String?,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    orbitStyle: Boolean = false,
) {
    val fieldShape = RoundedCornerShape(14.dp)
    val busy by rememberUpdatedState(importing)
    SkillModalSheet(
        orbitStyle = orbitStyle,
        dismissEnabled = !importing,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = remember { { _: SheetValue -> !busy } }),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.skill_import_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            // Segmented picker: the selected source is a white pill on the gray track.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(sheetCardColor()).padding(4.dp).selectableGroup()) {
                listOf(R.string.import_from_repo, R.string.import_from_zip, R.string.import_from_direct).forEachIndexed { index, label ->
                    val chosen = index == tabIndex
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (chosen) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable(enabled = !importing, role = Role.Tab) { onTabChange(index) }
                            .semantics { selected = chosen }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when (tabIndex) {
                0 -> {
                    Text(stringResource(R.string.skill_import_hint_repo), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = repoUrl, onValueChange = onRepoUrlChange, shape = fieldShape,
                        label = { Text(stringResource(R.string.repo_link)) },
                        placeholder = { Text("https://github.com/username/repo") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !importing,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                1 -> {
                    Text(stringResource(R.string.skill_import_hint_zip), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SheetCardBox(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        if (zipFileName.isBlank()) {
                            SheetRow("folder-open", stringResource(R.string.skill_import_pick_zip)) { if (!importing) onPickZip() }
                        } else {
                            SheetRow("file-archive", zipFileName, trailing = "refresh-cw") { if (!importing) onPickZip() }
                        }
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = skillId, onValueChange = onSkillIdChange, shape = fieldShape,
                        label = { Text(stringResource(R.string.skillmgr_direct_skill_id)) },
                        placeholder = { Text(stringResource(R.string.skillmgr_direct_skill_id_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !importing,
                    )
                    OutlinedTextField(
                        value = description, onValueChange = onDescriptionChange, shape = fieldShape,
                        label = { Text(stringResource(R.string.skillmgr_direct_description)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !importing,
                    )
                    OutlinedTextField(
                        value = content, onValueChange = onContentChange, shape = fieldShape,
                        label = { Text(stringResource(R.string.skillmgr_direct_content)) },
                        placeholder = { Text(stringResource(R.string.skillmgr_direct_content_hint)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 10, enabled = !importing,
                    )
                    SheetCardBox(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        SheetRow("paperclip", stringResource(R.string.add_attachment) + " · " +
                            stringResource(R.string.attachments_count, attachments.size), trailing = "plus") {
                            if (!importing) onAddAttachment()
                        }
                        attachments.forEachIndexed { index, name ->
                            HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(Modifier.fillMaxWidth().padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(enabled = !importing, onClick = { onRemoveAttachment(index) }) {
                                    Icon(sheetIcon("x"), stringResource(R.string.remove_attachment), Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.skillmgr_direct_files_saved_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { SheetError(it) }
            SheetButton(stringResource(R.string.import_action), primary = true, busy = importing, onClick = onImport)
        }
    }
}
