package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.pricing.ModelPricingCatalogRepository
import com.ai.assistance.operit.data.pricing.PricingCatalogSource
import com.ai.assistance.operit.data.pricing.PricingCatalogState
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsGroupMemberInfo
import com.ai.assistance.operit.data.stats.LegacyPriceSettings
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import com.ai.assistance.operit.data.stats.TokenStatsSettingsManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class ManagementTab { GROUPS, PRICING }
private enum class PricingFilter { ALL, MISSING, CUSTOM }

private data class PricingEditor(
    val existing: TokenStatPriceOverrideEntity?,
    val draft: TokenStatsPriceOverrideDraft,
)

@Composable
fun TokenStatsManagementScreen(initialPricingTab: Boolean = false) {
    val context = LocalContext.current
    val viewModel: TokenStatsManagementViewModel =
        viewModel(factory = TokenStatsManagementViewModel.Factory(context))
    val state by viewModel.state.collectAsState()
    val catalogState by ModelPricingCatalogRepository.status.collectAsState()
    var selectedTab by rememberSaveable {
        mutableStateOf(if (initialPricingTab) ManagementTab.PRICING else ManagementTab.GROUPS)
    }

    TokenStatsColorsProvider {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == ManagementTab.GROUPS,
                    onClick = { selectedTab = ManagementTab.GROUPS },
                    text = { Text(stringResource(R.string.token_stats_management_groups_tab)) },
                )
                Tab(
                    selected = selectedTab == ManagementTab.PRICING,
                    onClick = { selectedTab = ManagementTab.PRICING },
                    text = { Text(stringResource(R.string.token_stats_management_pricing_tab)) },
                )
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                state.loading && state.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                selectedTab == ManagementTab.GROUPS -> GroupManagementTab(
                    groups = state.groups,
                    configs = state.configs,
                    pricingModels = state.pricingModels,
                    overrides = state.overrides,
                    onRename = viewModel::renameGroup,
                    onCreate = viewModel::createGroup,
                    onMove = viewModel::moveToGroup,
                    onRestore = viewModel::restoreDefaultGroup,
                    onSavePrice = viewModel::savePriceOverride,
                )
                else -> PricingManagementTab(
                    models = state.pricingModels,
                    overrides = state.overrides,
                    catalogState = catalogState,
                    onSave = viewModel::savePriceOverride,
                    onResetConfig = viewModel::resetPrice,
                    onRestoreBuiltIn = viewModel::restoreBuiltInPrice,
                    onRefreshCatalog = { ModelPricingCatalogRepository.refresh(force = true) },
                )
            }
        }
    }
}

@Composable
private fun GroupManagementTab(
    groups: List<TokenStatsGroupModelInfo>,
    configs: List<TokenStatsConfigOption>,
    pricingModels: List<TokenStatsPricingModelOption>,
    overrides: List<TokenStatPriceOverrideEntity>,
    onRename: (String, String) -> Unit,
    onCreate: (String, List<String>) -> Unit,
    onMove: (List<String>, String) -> Unit,
    onRestore: (String) -> Unit,
    onSavePrice: (TokenStatPriceOverrideEntity?, TokenStatsPriceOverrideDraft) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var renameTarget by remember { mutableStateOf<TokenStatsGroupModelInfo?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<TokenStatsGroupModelInfo?>(null) }
    var pricingEditor by remember { mutableStateOf<PricingEditor?>(null) }
    val configNames = remember(configs) { configs.associate { it.id to it.name } }
    val normalizedQuery = query.trim().lowercase()
    val visibleGroups = remember(groups, normalizedQuery, configNames) {
        if (normalizedQuery.isEmpty()) groups else groups.filter { group ->
            group.displayName.contains(normalizedQuery, ignoreCase = true) ||
                group.members.any { member ->
                    member.model.contains(normalizedQuery, ignoreCase = true) ||
                        member.provider.contains(normalizedQuery, ignoreCase = true) ||
                        configNames[member.configId].orEmpty().contains(normalizedQuery, ignoreCase = true)
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.token_stats_group_search_hint),
        )
        Text(
            text = stringResource(R.string.token_stats_group_display_only_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TokenStatsCardMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visibleGroups, key = { it.displayModelId }) { group ->
                GroupCard(
                    group = group,
                    configNames = configNames,
                    selectedIds = selectedIds,
                    onToggleMember = { id ->
                        selectedIds = selectedIds.toMutableSet().apply {
                            if (!add(id)) remove(id)
                        }
                    },
                    onRename = { renameTarget = group },
                    onRestore = { restoreTarget = group },
                    onEditPricing = { member ->
                        pricingEditor = pricingEditorForMember(member, pricingModels, overrides)
                    },
                )
            }
        }
        if (selectedIds.isNotEmpty()) {
            TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.token_stats_group_selected_count, selectedIds.size),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.token_stats_group_new_short))
                    }
                    Button(onClick = { showMove = true }) {
                        Text(stringResource(R.string.token_stats_group_move))
                    }
                }
            }
        }
    }

    renameTarget?.let { group ->
        TextInputDialog(
            title = stringResource(R.string.token_stats_group_rename),
            initialValue = group.displayName,
            confirmLabel = stringResource(R.string.settings_save),
            onConfirm = { onRename(group.displayModelId, it) },
            onDismiss = { renameTarget = null },
        )
    }
    if (showCreate) {
        TextInputDialog(
            title = stringResource(R.string.token_stats_group_new),
            initialValue = "",
            confirmLabel = stringResource(R.string.token_stats_group_create),
            onConfirm = {
                onCreate(it, selectedIds.toList())
                selectedIds = emptySet()
            },
            onDismiss = { showCreate = false },
        )
    }
    if (showMove) {
        ChoiceDialog(
            title = stringResource(R.string.token_stats_group_move_title),
            groups = groups,
            onSelect = {
                onMove(selectedIds.toList(), it.displayModelId)
                selectedIds = emptySet()
                showMove = false
            },
            onDismiss = { showMove = false },
        )
    }
    restoreTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.token_stats_group_restore)) },
            text = { Text(stringResource(R.string.token_stats_group_restore_message, group.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    onRestore(group.displayModelId)
                    restoreTarget = null
                }) { Text(stringResource(R.string.token_stats_group_restore_confirm_short)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    pricingEditor?.let { target ->
        PriceOverrideDialog(
            existing = target.existing,
            initialDraft = if (target.existing == null) target.draft else null,
            onSave = { onSavePrice(target.existing, it) },
            onDelete = null,
            onDismiss = { pricingEditor = null },
        )
    }
}

@Composable
private fun GroupCard(
    group: TokenStatsGroupModelInfo,
    configNames: Map<String, String>,
    selectedIds: Set<String>,
    onToggleMember: (String) -> Unit,
    onRename: () -> Unit,
    onRestore: () -> Unit,
    onEditPricing: (TokenStatsGroupMemberInfo) -> Unit,
) {
    var expanded by rememberSaveable(group.displayModelId) { mutableStateOf(false) }
    val custom = group.displayModelId.startsWith(TokenStatsSettingsManager.CUSTOM_GROUP_ID_PREFIX)
    TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(
                            if (custom) R.string.token_stats_group_custom_summary
                            else R.string.token_stats_group_default_summary,
                            group.members.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, stringResource(R.string.token_stats_group_rename))
                }
                IconButton(onClick = onRestore) {
                    Icon(Icons.Default.Restore, stringResource(R.string.token_stats_group_restore))
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (group.members.isEmpty()) {
                    Text(
                        stringResource(R.string.token_stats_group_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                    )
                }
                group.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        configName = configNames[member.configId],
                        checked = member.identityId in selectedIds,
                        onToggle = { onToggleMember(member.identityId) },
                        onEditPricing = { onEditPricing(member) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: TokenStatsGroupMemberInfo,
    configName: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onEditPricing: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(member.model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = buildString {
                    append(member.provider)
                    if (member.configId.isNotEmpty()) {
                        append(" · ")
                        append(configName ?: stringResource(R.string.token_stats_config_deleted))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEditPricing) {
            Icon(
                Icons.Default.AttachMoney,
                stringResource(R.string.token_stats_group_edit_pricing, member.model),
            )
        }
    }
}

@Composable
private fun PricingManagementTab(
    models: List<TokenStatsPricingModelOption>,
    overrides: List<TokenStatPriceOverrideEntity>,
    catalogState: PricingCatalogState?,
    onSave: (TokenStatPriceOverrideEntity?, TokenStatsPriceOverrideDraft) -> Unit,
    onResetConfig: (TokenStatPriceOverrideEntity) -> Unit,
    onRestoreBuiltIn: (TokenStatPriceOverrideEntity?, String?) -> Unit,
    onRefreshCatalog: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFilter by rememberSaveable { mutableStateOf(PricingFilter.ALL) }
    var editor by remember { mutableStateOf<PricingEditor?>(null) }
    var resetConfigTarget by remember { mutableStateOf<TokenStatPriceOverrideEntity?>(null) }
    var restoreBuiltInTarget by remember {
        mutableStateOf<Pair<TokenStatPriceOverrideEntity?, String?>?>(null)
    }
    val providers = remember(models) { models.map { it.provider }.distinct().sorted() }
    val visible = remember(
        models,
        overrides,
        query,
        selectedProvider,
        selectedFilter,
        catalogState?.revision,
    ) {
        models.filter { option ->
            (selectedProvider == null || option.provider == selectedProvider) &&
                (query.isBlank() || option.model.contains(query, true) ||
                    option.provider.contains(query, true) ||
                    option.configs.any {
                        it.name.contains(query, true) || it.endpoint.contains(query, true)
                    }) &&
                when (selectedFilter) {
                    PricingFilter.ALL -> true
                    PricingFilter.MISSING -> !priceDraftIsComplete(providerDraftFor(option, overrides))
                    PricingFilter.CUSTOM -> option.legacyPricing != null || overrides.any {
                        it.provider.equals(option.provider, true) && it.model.equals(option.model, true)
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PricingCatalogStatusCard(catalogState, onRefreshCatalog)
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.token_stats_pricing_search_hint),
        )
        ProviderDropdown(
            providers = providers,
            selected = selectedProvider,
            onSelect = { selectedProvider = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PricingFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            stringResource(
                                when (filter) {
                                    PricingFilter.ALL -> R.string.token_stats_pricing_filter_all
                                    PricingFilter.MISSING -> R.string.token_stats_pricing_filter_missing
                                    PricingFilter.CUSTOM -> R.string.token_stats_pricing_filter_custom
                                }
                            )
                        )
                    },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visible, key = { "${it.provider}:${it.model}" }) { option ->
                PricingModelCard(
                    option = option,
                    overrides = overrides,
                    onEdit = { existing, draft -> editor = PricingEditor(existing, draft) },
                    onResetConfig = { resetConfigTarget = it },
                    onRestoreBuiltIn = { override, legacyKey ->
                        restoreBuiltInTarget = override to legacyKey
                    },
                )
            }
        }
    }

    editor?.let { target ->
        PriceOverrideDialog(
            existing = target.existing,
            initialDraft = if (target.existing == null) target.draft else null,
            onSave = { onSave(target.existing, it) },
            onDelete = null,
            onDismiss = { editor = null },
        )
    }
    restoreBuiltInTarget?.let { (override, legacyKey) ->
        AlertDialog(
            onDismissRequest = { restoreBuiltInTarget = null },
            title = { Text(stringResource(R.string.token_stats_pricing_restore_builtin)) },
            text = { Text(stringResource(R.string.token_stats_pricing_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onRestoreBuiltIn(override, legacyKey)
                    restoreBuiltInTarget = null
                }) { Text(stringResource(R.string.token_stats_pricing_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreBuiltInTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    resetConfigTarget?.let { override ->
        AlertDialog(
            onDismissRequest = { resetConfigTarget = null },
            title = { Text(stringResource(R.string.token_stats_pricing_restore_model)) },
            text = { Text(stringResource(R.string.token_stats_pricing_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetConfig(override)
                    resetConfigTarget = null
                }) { Text(stringResource(R.string.token_stats_pricing_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfigTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun PricingModelCard(
    option: TokenStatsPricingModelOption,
    overrides: List<TokenStatPriceOverrideEntity>,
    onEdit: (TokenStatPriceOverrideEntity?, TokenStatsPriceOverrideDraft) -> Unit,
    onResetConfig: (TokenStatPriceOverrideEntity) -> Unit,
    onRestoreBuiltIn: (TokenStatPriceOverrideEntity?, String?) -> Unit,
) {
    val providerOverride = overrides.firstOrNull {
        it.scope == PriceOverrideScope.PROVIDER_MODEL.name &&
            it.provider.equals(option.provider, true) && it.model.equals(option.model, true)
    }
    val providerDraft = providerDraftFor(option, overrides)
    val providerSourceRes = when {
        providerOverride != null -> R.string.token_stats_pricing_source_override
        option.legacyPricing != null -> R.string.token_stats_pricing_source_legacy
        else -> R.string.token_stats_pricing_source_builtin
    }
    TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.model, fontWeight = FontWeight.Bold)
                    Text(option.provider, style = MaterialTheme.typography.bodySmall, color = TokenStatsCardMuted)
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.token_stats_pricing_model_default),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(providerSourceRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalTokenStatsColors.current.chartAccent,
                    )
                    Text(
                        priceSummary(providerDraft),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                    )
                }
                IconButton(onClick = { onEdit(providerOverride, providerDraft) }) {
                    Icon(Icons.Default.Edit, stringResource(R.string.token_stats_pricing_edit))
                }
            }
            if (providerOverride != null || option.legacyPricing != null) {
                TextButton(onClick = {
                    onRestoreBuiltIn(providerOverride, option.legacyProviderModel)
                }) {
                    Text(stringResource(R.string.token_stats_pricing_restore_builtin))
                }
            }

            if (option.configs.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.token_stats_pricing_config_prices),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.token_stats_pricing_config_prices_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = TokenStatsCardMuted,
                )
                option.configs.forEach { config ->
                    val configOverride = overrides.firstOrNull {
                        it.scope == PriceOverrideScope.CONFIG.name &&
                            it.provider.equals(option.provider, true) &&
                            it.model.equals(option.model, true) && it.configId == config.id
                    }
                    val configTarget = providerDraft.copy(
                        scope = PriceOverrideScope.CONFIG,
                        configId = config.id,
                    )
                    val draft = configOverride?.toDraft()?.let {
                        mergePricingDraft(configTarget, it)
                    } ?: configTarget
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (config.available) config.name
                                    else stringResource(R.string.token_stats_config_deleted_with_id, config.id),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (config.endpoint.isNotBlank()) {
                                    Text(
                                        config.endpoint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TokenStatsCardMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    stringResource(
                                        if (configOverride == null) R.string.token_stats_pricing_inherits_model
                                        else R.string.token_stats_pricing_source_config
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalTokenStatsColors.current.chartAccent,
                                )
                                Text(
                                    priceSummary(draft),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TokenStatsCardMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (configOverride != null) {
                                IconButton(onClick = { onResetConfig(configOverride) }) {
                                    Icon(Icons.Default.Restore, stringResource(R.string.token_stats_pricing_restore_model))
                                }
                            }
                            IconButton(onClick = { onEdit(configOverride, draft) }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.token_stats_pricing_edit_config, config.name))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderDropdown(
    providers: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(selected ?: stringResource(R.string.token_stats_filter_all_providers)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.token_stats_filter_all_providers)) },
                onClick = { onSelect(null); expanded = false },
            )
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider) },
                    onClick = { onSelect(provider); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()); onDismiss() },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    groups: List<TokenStatsGroupModelInfo>,
    onSelect: (TokenStatsGroupModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(groups, key = { it.displayModelId }) { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(group) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.displayName, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.token_stats_group_members, group.members.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = TokenStatsCardMuted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

private fun builtinDraft(provider: String, model: String): TokenStatsPriceOverrideDraft {
    val defaults = DefaultModelPricingCollect.getDefaultPricing("$provider:$model")
    return TokenStatsPriceOverrideDraft(
        scope = PriceOverrideScope.PROVIDER_MODEL,
        provider = provider,
        model = model,
        configId = null,
        billingMode = defaults.billingMode,
        currency = defaults.currency,
        inputPricePerMillion = defaults.inputPricePerMillion.takeIf { defaults.hasInputPrice },
        cachedInputPricePerMillion = defaults.cachedInputPricePerMillion
            .takeIf { defaults.hasCachedInputPrice },
        cacheWritePricePerMillion = defaults.cacheWritePricePerMillion,
        outputPricePerMillion = defaults.outputPricePerMillion.takeIf { defaults.hasOutputPrice },
        pricePerRequest = defaults.pricePerRequest.takeIf { defaults.hasPricePerRequest },
    )
}

private fun pricingEditorForMember(
    member: TokenStatsGroupMemberInfo,
    pricingModels: List<TokenStatsPricingModelOption>,
    overrides: List<TokenStatPriceOverrideEntity>,
): PricingEditor {
    val option = pricingModels.firstOrNull {
        it.provider.equals(member.provider, true) && it.model.equals(member.model, true)
    }
    val providerOverride = overrides.firstOrNull {
        it.scope == PriceOverrideScope.PROVIDER_MODEL.name &&
            it.provider.equals(member.provider, true) && it.model.equals(member.model, true)
    }
    val inherited = option?.let { providerDraftFor(it, overrides) }
        ?: providerOverride?.toDraft()
        ?: builtinDraft(member.provider, member.model)
    val scope = if (member.configId.isBlank()) {
        PriceOverrideScope.PROVIDER_MODEL
    } else {
        PriceOverrideScope.CONFIG
    }
    val existing = overrides.firstOrNull {
        it.scope == scope.name &&
            it.provider.equals(member.provider, true) &&
            it.model.equals(member.model, true) &&
            (scope == PriceOverrideScope.PROVIDER_MODEL || it.configId == member.configId)
    }
    return PricingEditor(
        existing = existing,
        draft = existing?.toDraft() ?: inherited.copy(
            scope = scope,
            configId = member.configId.ifBlank { null },
        ),
    )
}

private fun legacyDraft(
    provider: String,
    model: String,
    legacy: LegacyPriceSettings,
): TokenStatsPriceOverrideDraft {
    val defaults = DefaultModelPricingCollect.getDefaultPricing("$provider:$model")
    val billingMode = legacy.billingMode ?: defaults.billingMode
    return TokenStatsPriceOverrideDraft(
        scope = PriceOverrideScope.PROVIDER_MODEL,
        provider = provider,
        model = model,
        configId = null,
        billingMode = billingMode,
        currency = defaults.currency,
        inputPricePerMillion = legacy.inputPricePerMillion
            ?: defaults.inputPricePerMillion.takeIf { defaults.hasInputPrice },
        cachedInputPricePerMillion = legacy.cachedInputPricePerMillion
            ?: defaults.cachedInputPricePerMillion.takeIf { defaults.hasCachedInputPrice },
        cacheWritePricePerMillion = defaults.cacheWritePricePerMillion,
        outputPricePerMillion = legacy.outputPricePerMillion
            ?: defaults.outputPricePerMillion.takeIf { defaults.hasOutputPrice },
        pricePerRequest = legacy.pricePerRequest
            ?: defaults.pricePerRequest.takeIf { defaults.hasPricePerRequest },
    )
}

private fun providerDraftFor(
    option: TokenStatsPricingModelOption,
    overrides: List<TokenStatPriceOverrideEntity>,
): TokenStatsPriceOverrideDraft {
    val base = option.legacyPricing?.let { legacyDraft(option.provider, option.model, it) }
        ?: builtinDraft(option.provider, option.model)
    val override = overrides.firstOrNull {
        it.scope == PriceOverrideScope.PROVIDER_MODEL.name &&
            it.provider.equals(option.provider, true) && it.model.equals(option.model, true)
    } ?: return base
    return mergePricingDraft(base, override.toDraft())
}

internal fun mergePricingDraft(
    base: TokenStatsPriceOverrideDraft,
    overlay: TokenStatsPriceOverrideDraft,
): TokenStatsPriceOverrideDraft {
    if (base.billingMode != overlay.billingMode) return overlay
    return if (overlay.billingMode == BillingMode.COUNT) {
        overlay.copy(pricePerRequest = overlay.pricePerRequest ?: base.pricePerRequest)
    } else {
        overlay.copy(
            inputPricePerMillion = overlay.inputPricePerMillion ?: base.inputPricePerMillion,
            cachedInputPricePerMillion = overlay.cachedInputPricePerMillion
                ?: base.cachedInputPricePerMillion,
            cacheWritePricePerMillion = overlay.cacheWritePricePerMillion
                ?: base.cacheWritePricePerMillion,
            outputPricePerMillion = overlay.outputPricePerMillion ?: base.outputPricePerMillion,
        )
    }
}

@Composable
private fun PricingCatalogStatusCard(
    state: PricingCatalogState?,
    onRefresh: () -> Unit,
) {
    TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.token_stats_pricing_catalog_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            if (state == null) {
                                R.string.token_stats_pricing_catalog_loading
                            } else if (state.source == PricingCatalogSource.CACHED_REMOTE) {
                                R.string.token_stats_pricing_catalog_remote
                            } else {
                                R.string.token_stats_pricing_catalog_bundled
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalTokenStatsColors.current.chartAccent,
                    )
                }
                if (state == null || state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.token_stats_pricing_catalog_refresh))
                    }
                }
            }
            state?.let { loaded ->
                Text(
                    text = stringResource(
                        R.string.token_stats_pricing_catalog_revision,
                        loaded.revision,
                        loaded.generatedAt.substringBefore('T'),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TokenStatsCardMuted,
                )
                Text(
                    text = loaded.lastCheckedAt?.let {
                        stringResource(
                            R.string.token_stats_pricing_catalog_checked,
                            formatCatalogCheckedAt(it),
                        )
                    } ?: stringResource(R.string.token_stats_pricing_catalog_not_checked),
                    style = MaterialTheme.typography.labelSmall,
                    color = TokenStatsCardMuted,
                )
                loaded.lastError?.let { error ->
                    Text(
                        text = stringResource(R.string.token_stats_pricing_catalog_refresh_failed, error),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatCatalogCheckedAt(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun priceDraftIsComplete(draft: TokenStatsPriceOverrideDraft): Boolean =
    if (draft.billingMode == BillingMode.COUNT) {
        draft.pricePerRequest != null
    } else {
        draft.inputPricePerMillion != null &&
            (draft.cachedInputPricePerMillion ?: draft.inputPricePerMillion) != null &&
            draft.outputPricePerMillion != null
    }

private fun TokenStatPriceOverrideEntity.toDraft() = TokenStatsPriceOverrideDraft(
    scope = PriceOverrideScope.fromNameOrNull(scope) ?: PriceOverrideScope.PROVIDER_MODEL,
    provider = provider,
    model = model,
    configId = configId.ifBlank { null },
    billingMode = BillingMode.fromString(billingMode),
    currency = if (pricingCurrency.equals("CNY", true)) PricingCurrency.CNY else PricingCurrency.USD,
    inputPricePerMillion = inputPricePerMillion,
    cachedInputPricePerMillion = cachedInputPricePerMillion,
    cacheWritePricePerMillion = cacheWritePricePerMillion,
    outputPricePerMillion = outputPricePerMillion,
    pricePerRequest = pricePerRequest,
)

@Composable
private fun priceSummary(draft: TokenStatsPriceOverrideDraft): String {
    if (draft.billingMode == BillingMode.COUNT) {
        return draft.pricePerRequest?.let {
            "${stringResource(R.string.settings_billing_mode_count)} ${formatPricePerRequest(it, draft.currency)}"
        } ?: "-"
    }
    return listOfNotNull(
        draft.inputPricePerMillion?.let {
            "${stringResource(R.string.token_stats_token_uncached)} ${formatPricePerMillion(it, draft.currency)}"
        },
        draft.cachedInputPricePerMillion?.let {
            "${stringResource(R.string.token_stats_token_cached)} ${formatPricePerMillion(it, draft.currency)}"
        },
        draft.outputPricePerMillion?.let {
            "${stringResource(R.string.token_stats_token_output)} ${formatPricePerMillion(it, draft.currency)}"
        },
    ).joinToString(" · ").ifEmpty { "-" }
}
