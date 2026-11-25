package com.ai.assistance.operit.ui.features.settings.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.normalizeProviderId
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogSurface
import com.ai.assistance.operit.ui.features.settings.components.SettingsDialogWindow
import kotlinx.coroutines.launch

internal data class ProviderSelectionOption(
    val id: String,
    val displayName: String,
)

/** The groups the picker lists providers under, in this order. */
private enum class ProviderGroup(@param:StringRes val title: Int) {
    ACCOUNT(R.string.provider_group_account),
    OFFICIAL(R.string.provider_group_official),
    PLATFORM(R.string.provider_group_platform),
    CUSTOM(R.string.provider_group_custom),
    PLUGIN(R.string.provider_group_plugin),
}

private fun providerGroup(id: String): ProviderGroup = when (ApiProviderType.fromProviderTypeId(id)) {
    null -> ProviderGroup.PLUGIN
    ApiProviderType.OPENAI_CODEX,
    ApiProviderType.CLAUDE_ACCOUNT,
    ApiProviderType.GROK_ACCOUNT,
    ApiProviderType.GOOGLE_ANTIGRAVITY,
    ApiProviderType.COMMAND_CODE -> ProviderGroup.ACCOUNT
    ApiProviderType.OPENROUTER,
    ApiProviderType.OPENCODE_GO,
    ApiProviderType.OPENCODE_ZEN_FREE,
    ApiProviderType.SILICONFLOW,
    ApiProviderType.IFLOW,
    ApiProviderType.FOUR_ROUTER,
    ApiProviderType.NOUS_PORTAL,
    ApiProviderType.INFINIAI,
    ApiProviderType.NVIDIA,
    ApiProviderType.OLLAMA_CLOUD,
    ApiProviderType.PPINFRA,
    ApiProviderType.NOVITA -> ProviderGroup.PLATFORM
    ApiProviderType.OPENAI_GENERIC,
    ApiProviderType.OPENAI_RESPONSES_GENERIC,
    ApiProviderType.ANTHROPIC_GENERIC,
    ApiProviderType.GEMINI_GENERIC,
    ApiProviderType.OTHER,
    ApiProviderType.LMSTUDIO,
    ApiProviderType.OLLAMA,
    ApiProviderType.OPENAI_LOCAL,
    ApiProviderType.MNN,
    ApiProviderType.LLAMA_CPP -> ProviderGroup.CUSTOM
    else -> ProviderGroup.OFFICIAL
}

/**
 * "Grok（账户登录）" reads as the name "Grok" over the note "账户登录". The note is the bracket that
 * closes the name, matched by depth so "Example (API (beta))" keeps "API (beta)" whole.
 */
private fun splitProviderName(name: String): Pair<String, String?> {
    val text = name.trim()
    val last = text.lastOrNull()
    if (last != ')' && last != '）') return text to null
    var depth = 0
    var open = -1
    for (i in text.indices.reversed()) {
        when (text[i]) {
            ')', '）' -> depth++
            '(', '（' -> if (--depth == 0) { open = i; break }
        }
    }
    if (open <= 0) return text to null
    val main = text.substring(0, open).trim()
    val note = text.substring(open + 1, text.length - 1).trim()
    return if (main.isEmpty() || note.isEmpty()) text to null else main to note
}

private sealed interface PickerEntry {
    val key: String

    data class Header(val group: ProviderGroup) : PickerEntry {
        override val key get() = "group:${group.name}"
    }

    /** [underHeader] when the row sits under its group's title, which then says what a like note would. */
    data class Provider(
        val option: ProviderSelectionOption,
        val group: ProviderGroup,
        val hidden: Boolean,
        val underHeader: Boolean,
    ) : PickerEntry {
        override val key get() = "provider:${normalizeProviderId(option.id)}"
    }

    data class HiddenToggle(val count: Int) : PickerEntry {
        override val key get() = "hidden"
    }

    data object Empty : PickerEntry {
        override val key get() = "empty"
    }
}

/** The browsing list: groups, then the hidden ones folded at the end. */
private fun browseEntries(
    providers: List<ProviderSelectionOption>,
    hidden: Set<String>,
    hiddenExpanded: Boolean,
): List<PickerEntry> {
    val (hiddenOnes, shown) = providers.partition { normalizeProviderId(it.id) in hidden }
    return buildList {
        shown.groupBy { providerGroup(it.id) }.toSortedMap().forEach { (group, options) ->
            add(PickerEntry.Header(group))
            options.forEach { add(PickerEntry.Provider(it, group, hidden = false, underHeader = true)) }
        }
        if (hiddenOnes.isNotEmpty()) {
            add(PickerEntry.HiddenToggle(hiddenOnes.size))
            if (hiddenExpanded) {
                hiddenOnes.forEach { add(PickerEntry.Provider(it, providerGroup(it.id), hidden = true, underHeader = false)) }
            }
        }
    }
}

@Composable
internal fun ApiProviderDialog(
    onDismissRequest: () -> Unit,
    onProviderSelected: (ProviderSelectionOption) -> Unit,
    configManager: ModelConfigManager,
    selectedProviderId: String,
) {
    val context = LocalContext.current
    val providers = remember { getProviderSelectionOptions(context) }
    // Null until the hidden set is read, so a hidden provider never shows in its group for a frame.
    val hiddenIds by configManager.collapsedProviderIdsFlow.collectAsState(initial = null)
    // The dialog closes when the screen is recreated, so its state lives as long as one opening.
    var query by remember { mutableStateOf("") }

    SettingsDialogWindow(onDismissRequest = onDismissRequest) {
        SettingsDialogSurface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.select_api_provider_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_providers)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                val hidden = hiddenIds
                if (hidden == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    ProviderList(
                        providers = providers,
                        hidden = hidden,
                        query = query,
                        selectedProviderId = selectedProviderId,
                        onProviderSelected = onProviderSelected,
                        onDismissRequest = onDismissRequest,
                        configManager = configManager,
                    )
                }

                Text(
                    text = stringResource(R.string.provider_picker_hide_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Composed once the hidden set is known, so it opens already scrolled to the current provider. */
@Composable
private fun ColumnScope.ProviderList(
    providers: List<ProviderSelectionOption>,
    hidden: Set<String>,
    query: String,
    selectedProviderId: String,
    onProviderSelected: (ProviderSelectionOption) -> Unit,
    onDismissRequest: () -> Unit,
    configManager: ModelConfigManager,
) {
    val scope = rememberCoroutineScope()
    val selectedKey = normalizeProviderId(selectedProviderId)
    // The hidden section starts open only when the current provider is in it, until toggled.
    var hiddenExpanded by remember { mutableStateOf(selectedKey.isNotEmpty() && selectedKey in hidden) }
    val search = query.trim()

    val browse = remember(providers, hidden, hiddenExpanded) { browseEntries(providers, hidden, hiddenExpanded) }
    val found = remember(providers, hidden, search) {
        if (search.isEmpty()) return@remember emptyList()
        providers.filter {
            it.displayName.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true)
        }.map {
            PickerEntry.Provider(it, providerGroup(it.id), normalizeProviderId(it.id) in hidden, underHeader = false)
        }.ifEmpty { listOf(PickerEntry.Empty) }
    }

    // Browsing and searching keep their own place: a search reads from its top, clearing it goes back.
    val browseState = remember {
        val index = browse.indexOfFirst { it is PickerEntry.Provider && normalizeProviderId(it.option.id) == selectedKey }
        // Start at the selection itself, or at its group's title when it heads the group, so it is in view
        // however short the window is.
        val start = if (index > 0 && browse[index - 1] is PickerEntry.Header) index - 1 else index
        LazyListState(firstVisibleItemIndex = start.coerceAtLeast(0))
    }
    // A new search term reads from the top from its first frame.
    val searchState = remember(search) { LazyListState() }
    val searching = search.isNotEmpty()
    val entries = if (searching) found else browse
    val expandedText = stringResource(R.string.expanded)
    val collapsedText = stringResource(R.string.collapsed)

    LazyColumn(
        state = if (searching) searchState else browseState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is PickerEntry.Header -> GroupHeader(entry.group)
                is PickerEntry.Provider -> ProviderRow(
                    option = entry.option,
                    group = entry.group,
                    selected = normalizeProviderId(entry.option.id) == selectedKey,
                    hidden = entry.hidden,
                    underHeader = entry.underHeader,
                    // Tapping the current provider again only closes: picking it anew resets its models.
                    onSelect = {
                        if (normalizeProviderId(entry.option.id) == selectedKey) onDismissRequest()
                        else onProviderSelected(entry.option)
                    },
                    onToggleHidden = {
                        scope.launch { configManager.toggleProviderCollapsed(entry.option.id) }
                    },
                )
                is PickerEntry.HiddenToggle -> HiddenToggleRow(
                    count = entry.count,
                    expanded = hiddenExpanded,
                    state = if (hiddenExpanded) expandedText else collapsedText,
                    onClick = { hiddenExpanded = !hiddenExpanded },
                )
                PickerEntry.Empty -> Text(
                    text = stringResource(R.string.provider_picker_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: ProviderGroup) {
    Text(
        text = stringResource(group.title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HiddenToggleRow(count: Int, expanded: Boolean, state: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickableCompat(onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.collapsed_providers_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderRow(
    option: ProviderSelectionOption,
    group: ProviderGroup,
    selected: Boolean,
    hidden: Boolean,
    underHeader: Boolean,
    onSelect: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val (name, rawNote) = remember(option.displayName) { splitProviderName(option.displayName) }
    val groupTitle = stringResource(group.title)
    // Under the "账户登录" title a note saying "账户登录" again says nothing.
    val note = rawNote?.takeUnless { underHeader && it.equals(groupTitle, ignoreCase = true) }
    val hideLabel = stringResource(if (hidden) R.string.provider_picker_unhide else R.string.provider_picker_hide)
    val hiddenState = stringResource(R.string.provider_picker_hidden_state)
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                .combinedClickableCompat(onClick = onSelect, onLongClick = { menuOpen = true }, onLongClickLabel = hideLabel)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    if (hidden) stateDescription = hiddenState
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = if (hidden) 0.06f else 0.12f))
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = if (hidden) colors.onSurfaceVariant else colors.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (hidden) colors.onSurfaceVariant else colors.onSurface,
                )
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(hideLabel) },
                onClick = {
                    menuOpen = false
                    onToggleHidden()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
): Modifier = combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
