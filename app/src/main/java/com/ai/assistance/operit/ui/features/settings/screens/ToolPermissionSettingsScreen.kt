package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.PermissionReviewPolicyStore
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPermissionSettingsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val toolHandler = remember { AIToolHandler.getInstance(context) }
    val toolPermissionSystem = remember { ToolPermissionSystem.getInstance(context) }
    val reviewPolicyStore = remember { PermissionReviewPolicyStore(context) }
    val reviewPolicySnapshot by
        reviewPolicyStore.snapshotFlow.collectAsState(
            initial =
                com.ai.assistance.operit.ui.permissions.PermissionReviewPolicySnapshot(
                    text = PermissionReviewPolicyStore.DEFAULT_POLICY,
                    version = "default",
                    customized = false,
                    managedAdditions = "",
                )
        )
    var policyDraft by remember { mutableStateOf("") }
    var policyExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val allTools = remember {
        toolHandler.getAllToolNames().filterNot {
            it == "package_proxy" || it == "proxy" || it == "search" ||
                it in PermissionReviewInternalTools.names
        }
    }
    val toolPermissions = remember { mutableStateMapOf<String, PermissionLevel>() }
    val masterSwitch = toolPermissionSystem.masterSwitchFlow.collectAsState(initial = PermissionLevel.ASK).value
    var masterSwitchInput by remember { mutableStateOf(masterSwitch) }

    LaunchedEffect(allTools) {
        allTools.forEach { toolName ->
            val override = toolPermissionSystem.getToolPermissionOverride(toolName)
            if (override != null) {
                toolPermissions[toolName] = override
            }
        }
    }

    LaunchedEffect(masterSwitch) {
        masterSwitchInput = masterSwitch
    }

    fun handlePermissionChange(toolName: String, newLevel: PermissionLevel) {
        val currentLevel = toolPermissions[toolName]
        if (currentLevel == newLevel) {
            // If the tool is already in the target level, move it back to ASK
            toolPermissions.remove(toolName)
            scope.launch {
                toolPermissionSystem.clearToolPermission(toolName)
            }
        } else {
            // Otherwise, move it to the new level
            toolPermissions[toolName] = newLevel
            scope.launch {
                toolPermissionSystem.saveToolPermission(toolName, newLevel)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.tool_permissions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.tool_permissions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.global_permission_switch),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.global_permission_switch_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CompactPermissionLevelSelector(
                        selectedLevel = masterSwitchInput,
                        onLevelSelected = { level ->
                            masterSwitchInput = level
                            scope.launch {
                                toolPermissionSystem.saveMasterSwitch(level)
                            }
                        }
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.permission_review_policy_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.permission_review_policy_description,
                            reviewPolicySnapshot.version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (!policyExpanded) {
                                policyDraft = reviewPolicySnapshot.managedAdditions
                            }
                            policyExpanded = !policyExpanded
                        }
                    ) {
                        Text(
                            stringResource(
                                if (policyExpanded) R.string.permission_review_policy_collapse
                                else R.string.permission_review_policy_edit
                            )
                        )
                    }
                    AnimatedVisibility(policyExpanded) {
                        Column {
                            Text(
                                stringResource(R.string.permission_review_policy_managed_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = policyDraft,
                                onValueChange = { policyDraft = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                                placeholder = {
                                    Text(stringResource(R.string.permission_review_policy_placeholder))
                                },
                                minLines = 5,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch { reviewPolicyStore.saveCustomPolicy(policyDraft) }
                                    }
                                ) { Text(stringResource(R.string.save)) }
                                OutlinedButton(
                                    onClick = {
                                        policyDraft = ""
                                        scope.launch { reviewPolicyStore.reset() }
                                    }
                                ) { Text(stringResource(R.string.reset)) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.tool_permission_instruction_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tool_permission_instruction_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            PermissionGroup(
                level = PermissionLevel.ALLOW,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.ALLOW }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.ALLOW) }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.WORKSPACE,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.WORKSPACE }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName ->
                    handlePermissionChange(toolName, PermissionLevel.WORKSPACE)
                }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.REVIEWER,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.REVIEWER }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName ->
                    handlePermissionChange(toolName, PermissionLevel.REVIEWER)
                }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.WORKSPACE_REVIEWER,
                allTools = allTools,
                toolsInLevel =
                    toolPermissions.filterValues {
                        it == PermissionLevel.WORKSPACE_REVIEWER
                    }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName ->
                    handlePermissionChange(toolName, PermissionLevel.WORKSPACE_REVIEWER)
                }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.ASK,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.ASK }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.ASK) }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.FORBID,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.FORBID }.keys,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.FORBID) }
            )
        }
    }
}

@Composable
private fun PermissionGroup(
    level: PermissionLevel,
    allTools: List<String>,
    toolsInLevel: Set<String>,
    toolHandler: AIToolHandler,
    onToolToggled: (String) -> Unit
) {
    var showToolSelector by remember { mutableStateOf(false) }

    val (title, description, color) = when (level) {
        PermissionLevel.ALLOW -> Triple(
            stringResource(R.string.permission_level_allow),
            stringResource(R.string.permission_level_allow_description),
            MaterialTheme.colorScheme.primary
        )
        PermissionLevel.WORKSPACE -> Triple(
            stringResource(R.string.permission_level_workspace),
            stringResource(R.string.permission_level_workspace_description),
            MaterialTheme.colorScheme.tertiary
        )
        PermissionLevel.WORKSPACE_REVIEWER -> Triple(
            stringResource(R.string.permission_level_workspace_reviewer),
            stringResource(R.string.permission_level_workspace_reviewer_description),
            MaterialTheme.colorScheme.tertiary
        )
        PermissionLevel.REVIEWER -> Triple(
            stringResource(R.string.permission_level_reviewer),
            stringResource(R.string.permission_level_reviewer_description),
            MaterialTheme.colorScheme.secondary
        )
        PermissionLevel.FORBID -> Triple(
            stringResource(R.string.permission_level_forbid),
            stringResource(R.string.permission_level_forbid_description),
            MaterialTheme.colorScheme.error
        )
        PermissionLevel.ASK -> Triple(
            stringResource(R.string.permission_level_ask),
            stringResource(R.string.permission_level_ask_description),
            MaterialTheme.colorScheme.secondary
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showToolSelector = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_tool), tint = color)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (toolsInLevel.isNotEmpty()) {
                toolsInLevel.forEach { toolName ->
                    ToolChip(toolName = toolName, onRemove = { onToolToggled(toolName) })
                }
            } else {
                Text(
                    stringResource(R.string.no_tools_in_group),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showToolSelector) {
        ToolSelectorDialog(
            allTools = allTools,
            toolsInLevel = toolsInLevel,
            toolHandler = toolHandler,
            onDismiss = { showToolSelector = false },
            onToolToggled = onToolToggled
        )
    }
}

@Composable
private fun ToolChip(toolName: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            toolName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.remove_tool),
            modifier = Modifier
                .size(18.dp)
                .clickable { onRemove() },
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun ToolSelectorDialog(
    allTools: List<String>,
    toolsInLevel: Set<String>,
    toolHandler: AIToolHandler,
    onDismiss: () -> Unit,
    onToolToggled: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val descriptions = remember(allTools) {
        allTools.associateWith { toolHandler.getToolDescription(it) }
    }
    val filteredTools = allTools.filter { it.contains(searchQuery, ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    stringResource(R.string.select_tools),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_tools)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredTools) { toolName ->
                        val isSelected = toolsInLevel.contains(toolName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToolToggled(toolName) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToolToggled(toolName) }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = toolName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = descriptions[toolName] ?: toolName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactPermissionLevelSelector(
    selectedLevel: PermissionLevel,
    onLevelSelected: (PermissionLevel) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PermissionLevel.values().forEach { level ->
            val isSelected = selectedLevel == level
            val (containerColor, textColor) = when {
                isSelected -> Pair(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                else -> Pair(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .clickable { onLevelSelected(level) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when (level) {
                        PermissionLevel.ALLOW -> stringResource(R.string.permission_level_allow)
                        PermissionLevel.WORKSPACE -> stringResource(R.string.permission_level_workspace)
                        PermissionLevel.WORKSPACE_REVIEWER ->
                            stringResource(R.string.permission_level_workspace_reviewer)
                        PermissionLevel.REVIEWER -> stringResource(R.string.permission_level_reviewer)
                        PermissionLevel.ASK -> stringResource(R.string.permission_level_ask)
                        PermissionLevel.FORBID -> stringResource(R.string.forbid)
                    },
                    color = textColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
