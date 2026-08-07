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
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsGroupMemberInfo
import com.ai.assistance.operit.data.stats.LegacyPriceSettings
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import com.ai.assistance.operit.data.stats.TokenStatsSettingsManager

private enum class ManagementTab { GROUPS, PRICING }

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
                    onRename = viewModel::renameGroup,
                    onCreate = viewModel::createGroup,
                    onMove = viewModel::moveToGroup,
                    onRestore = viewModel::restoreDefaultGroup,
                )
                else -> PricingManagementTab(
                    models = state.pricingModels,
                    overrides = state.overrides,
                    onSave = viewModel::savePriceOverride,
                    onResetConfig = viewModel::resetPrice,
                    onRestoreBuiltIn = viewModel::restoreBuiltInPrice,
                )
            }
        }
    }
}

@Composable
private fun GroupManagementTab(
    groups: List<TokenStatsGroupModelInfo>,
    configs: List<TokenStatsConfigOption>,
    onRename: (String, String) -> Unit,
    onCreate: (String, List<String>) -> Unit,
    onMove: (List<String>, String) -> Unit,
    onRestore: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var renameTarget by remember { mutableStateOf<TokenStatsGroupModelInfo?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<TokenStatsGroupModelInfo?>(null) }
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
}

@Composable
private fun GroupCard(
    group: TokenStatsGroupModelInfo,
    configNames: Map<String, String>,
    selectedIds: Set<String>,
    onToggleMember: (String) -> Unit,
    onRename: () -> Unit,
    onRestore: () -> Unit,
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
    }
}

@Composable
private fun PricingManagementTab(
    models: List<TokenStatsPricingModelOption>,
    overrides: List<TokenStatPriceOverrideEntity>,
    onSave: (TokenStatPriceOverrideEntity?, TokenStatsPriceOverrideDraft) -> Unit,
    onResetConfig: (TokenStatPriceOverrideEntity) -> Unit,
    onRestoreBuiltIn: (TokenStatPriceOverrideEntity?, String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<PricingEditor?>(null) }
    var resetConfigTarget by remember { mutableStateOf<TokenStatPriceOverrideEntity?>(null) }
    var restoreBuiltInTarget by remember {
        mutableStateOf<Pair<TokenStatPriceOverrideEntity?, String?>?>(null)
    }
    val providers = remember(models) { models.map { it.provider }.distinct().sorted() }
    val visible = remember(models, query, selectedProvider) {
        models.filter { option ->
            (selectedProvider == null || option.provider == selectedProvider) &&
                (query.isBlank() || option.model.contains(query, true) ||
                    option.provider.contains(query, true) ||
                    option.configs.any { it.name.contains(query, true) })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
    val providerDraft = providerOverride?.toDraft()
        ?: option.legacyPricing?.let { legacyDraft(option.provider, option.model, it) }
        ?: builtinDraft(option.provider, option.model)
    TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.model, fontWeight = FontWeight.Bold)
                    Text(option.provider, style = MaterialTheme.typography.bodySmall, color = TokenStatsCardMuted)
                }
                Text(
                    stringResource(
                        when {
                            providerOverride != null -> R.string.token_stats_pricing_source_override
                            option.legacyPricing != null -> R.string.token_stats_pricing_source_legacy
                            else -> R.string.token_stats_pricing_source_builtin
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalTokenStatsColors.current.chartAccent,
                )
                IconButton(onClick = { onEdit(providerOverride, providerDraft) }) {
                    Icon(Icons.Default.Edit, stringResource(R.string.token_stats_pricing_edit))
                }
            }
            Text(
                priceSummary(providerDraft),
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
            )
            if (providerOverride != null || option.legacyPricing != null) {
                TextButton(onClick = {
                    onRestoreBuiltIn(providerOverride, option.legacyProviderModel)
                }) {
                    Text(stringResource(R.string.token_stats_pricing_restore_builtin))
                }
            }
            option.configs.forEach { config ->
                val configOverride = overrides.firstOrNull {
                    it.scope == PriceOverrideScope.CONFIG.name &&
                        it.provider.equals(option.provider, true) &&
                        it.model.equals(option.model, true) && it.configId == config.id
                }
                val draft = configOverride?.toDraft() ?: providerDraft.copy(
                    scope = PriceOverrideScope.CONFIG,
                    configId = config.id,
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(config.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(
                                if (configOverride == null) R.string.token_stats_pricing_inherits_model
                                else R.string.token_stats_pricing_source_config
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = TokenStatsCardMuted,
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
                        Icon(Icons.Default.Edit, stringResource(R.string.token_stats_pricing_edit))
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
        inputPricePerMillion = defaults.inputPricePerMillion,
        cachedInputPricePerMillion = defaults.cachedInputPricePerMillion,
        outputPricePerMillion = defaults.outputPricePerMillion,
        pricePerRequest = defaults.pricePerRequest,
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
        inputPricePerMillion = legacy.inputPricePerMillion ?: defaults.inputPricePerMillion,
        cachedInputPricePerMillion = legacy.cachedInputPricePerMillion
            ?: defaults.cachedInputPricePerMillion,
        outputPricePerMillion = legacy.outputPricePerMillion ?: defaults.outputPricePerMillion,
        pricePerRequest = legacy.pricePerRequest ?: defaults.pricePerRequest,
    )
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
