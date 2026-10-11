package com.ai.assistance.operit.ui.common.dynamicui

import androidx.a2ui.compose.runtime.*
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneOffset
import com.ai.assistance.operit.R

internal val LocalCardInteractive = compositionLocalOf { false }
internal val LocalCardInputError = compositionLocalOf<() -> Unit> { {} }
internal val LocalCardIconWarning = compositionLocalOf<() -> Unit> { {} }
internal val LocalCardReadOnly = compositionLocalOf { false }
internal val LocalCardBrowse = compositionLocalOf { false }
internal val LocalCardGridCell = compositionLocalOf { false }

internal fun cardNumberLabel(value: Float): String =
    if (!value.isFinite()) "—" else java.math.BigDecimal(value.toString()).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun updateCardField(onError: () -> Unit, update: () -> Unit) {
    try { update() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { onError() }
}

/** Official protocol and Material components, plus small Operit catalog extensions. */
internal val operitDynamicUiCatalog by lazy {
    A2uiCatalog(
        catalogId = DynamicUiProtocol.CATALOG,
        components = with(MaterialA2uiBasicCatalogV1Defaults) {
            listOf(text, CardIcon, row, column, list, card, divider, CardButton, CardCheckBox) +
                listOf(
                    "Heading", "Display", "Badge", "Progress", "Code", "Table", "Switch", "RadioGroup",
                    "Select", "ChoicePicker", "DatePicker", "TextArea", "TextField", "Slider",
                    "Grid", "Form", "Expandable",
                ).map(::OperitCardComponent) + cardAdvancedNames.map(::AdvancedCardComponent)
        },
        functions = CardFunctions.functions,
        isInline = true,
    )
}

private class OperitCardComponent(override val name: String) : A2uiComponent {
    private val text = A2uiProperty.dynamicString("text")
    private val label = A2uiProperty.dynamicString("label")
    private val value = A2uiProperty.dynamicString("value")
    private val checked = A2uiProperty.dynamicBoolean("checked")
    private val number = A2uiProperty.dynamicNumber("number")
    private val options = A2uiProperty.dynamicStringList("options")
    private val rows = A2uiProperty.dynamicValue("rows")
    private val columns = A2uiProperty.dynamicStringList("columns")
    private val children = A2uiProperty.childList("children")
    private val minimum = A2uiProperty.dynamicNumber("min")
    private val maximum = A2uiProperty.dynamicNumber("max")
    private val stepCount = A2uiProperty.dynamicNumber("steps")
    private val columnCount = A2uiProperty.dynamicNumber("columnCount")
    private val columnSpans = A2uiProperty.dynamicValue("columnSpans")
    private val expandedProperty = A2uiProperty.dynamicBoolean("expanded")
    private val inputType = A2uiProperty.dynamicString("inputType")
    override val description = "Operit native $name. Local field changes do not send model requests."
    override val properties = listOf(text, label, value, checked, number, options, rows, columns, children, minimum, maximum, stepCount, columnCount, columnSpans, expandedProperty, inputType)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val title = properties.bind(label).orEmpty()
        val interactive = LocalCardInteractive.current
        val inputError = LocalCardInputError.current
        when (name) {
            "Heading" -> Text(properties.bind(text).orEmpty(), modifier, style = MaterialTheme.typography.titleMedium)
            "Display" -> Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title.isNotBlank()) Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    // Keep long results readable at large font scales without truncating digits.
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.CenterEnd) {
                        Text(properties.bind(text).orEmpty(), maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            "Badge" -> Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(properties.bind(text).orEmpty(), Modifier.padding(10.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            "Code" -> Text(
                properties.bind(text).orEmpty(), modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
            "Progress" -> {
                val progress = (properties.bind(number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            "TextField", "TextArea" -> {
                val current = properties.bind(value).orEmpty()
                val update = properties.bindUpdater(value)
                if (LocalCardReadOnly.current) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current.ifBlank { stringResource(R.string.dynamic_ui_empty_value) }, style = MaterialTheme.typography.bodyMedium)
                } else OutlinedTextField(
                    value = current, onValueChange = { next ->
                        if (next.length <= 8192) updateCardField(inputError) { update?.invoke(next) }
                    },
                    readOnly = update == null || !interactive, enabled = interactive, label = { Text(title) },
                    modifier = modifier.fillMaxWidth(), singleLine = name == "TextField",
                    keyboardOptions = KeyboardOptions(keyboardType = if (properties.bind(inputType) == "number") KeyboardType.Decimal else KeyboardType.Text),
                    minLines = if (name == "TextArea") 3 else 1, maxLines = 8,
                )
            }
            "Switch" -> {
                val current = properties.bind(checked) ?: false
                val update = properties.bindUpdater(checked)
                Row(modifier.fillMaxWidth().toggleable(
                    current, enabled = update != null && interactive, role = Role.Switch,
                    onValueChange = { next -> updateCardField(inputError) { update?.invoke(next) } },
                ), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    Switch(current, onCheckedChange = null, enabled = update != null && interactive)
                }
            }
            "RadioGroup", "Select", "ChoicePicker" -> {
                val current = properties.bind(value).orEmpty()
                val update = properties.bindUpdater(value)
                val choices = properties.bind(options).orEmpty().take(32)
                var expanded by remember { mutableStateOf(false) }
                Column(modifier) {
                    if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.labelLarge)
                    if (LocalCardReadOnly.current) Text(current.ifBlank { stringResource(R.string.dynamic_ui_empty_value) })
                    else if (name == "Select") OutlinedButton(onClick = { expanded = !expanded }, enabled = update != null && interactive, modifier = Modifier.fillMaxWidth()) {
                        Text(current.ifBlank { stringResource(R.string.dynamic_ui_select) }, Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    if (!LocalCardReadOnly.current && (name != "Select" || expanded)) choices.forEach { choice ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = current == choice, enabled = update != null && interactive, role = Role.RadioButton,
                                onClick = { updateCardField(inputError) { update?.invoke(choice) }; expanded = false },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(current == choice, onClick = null, enabled = update != null && interactive)
                            Text(choice, Modifier.weight(1f).padding(vertical = 8.dp))
                        }
                    }
                }
            }
            "Slider" -> {
                val min = properties.bind(minimum)?.toFloat() ?: 0f
                val max = properties.bind(maximum)?.toFloat() ?: 100f
                val current = properties.bind(number)?.toFloat() ?: min
                val update = properties.bindUpdater(number)
                Column(modifier) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text(cardNumberLabel(current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (current.isFinite() && min.isFinite() && max.isFinite() && max > min) {
                        Slider(current.coerceIn(min, max), { next -> updateCardField(inputError) { update?.invoke(next) } }, enabled = update != null && interactive, valueRange = min..max,
                            steps = (properties.bind(stepCount)?.toInt() ?: 0).coerceIn(0, 999))
                    } else Text(stringResource(R.string.dynamic_ui_bad_number), color = MaterialTheme.colorScheme.error)
                }
            }
            "DatePicker" -> {
                val current = properties.bind(value).orEmpty()
                val update = properties.bindUpdater(value)
                var expanded by remember { mutableStateOf(false) }
                Column(modifier) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    if (LocalCardReadOnly.current) Text(current.ifBlank { stringResource(R.string.dynamic_ui_empty_value) })
                    else OutlinedButton(onClick = { expanded = !expanded }, enabled = update != null && interactive, modifier = Modifier.fillMaxWidth()) {
                        Text(current.ifBlank { stringResource(R.string.dynamic_ui_select_date) }, Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    if (expanded && interactive) {
                        val initial = remember(current) {
                            runCatching {
                                LocalDate.parse(current).takeIf { it.year in 1900..2100 }
                                    ?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                            }.getOrNull()
                        }
                        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            DatePicker(state, modifier = Modifier.width(328.dp), showModeToggle = false)
                        }
                        LaunchedEffect(state.selectedDateMillis) {
                            state.selectedDateMillis?.let {
                                updateCardField(inputError) {
                                    update?.invoke(java.time.Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                                }
                            }
                        }
                    }
                }
            }
            "Table" -> {
                val headers = properties.bind(columns).orEmpty()
                val data = properties.bind(rows) as? List<*> ?: emptyList<Any>()
                Column(modifier.horizontalScroll(rememberScrollState())) {
                    Row { headers.forEach { Text(it, Modifier.width(140.dp).padding(8.dp), style = MaterialTheme.typography.labelLarge) } }
                    HorizontalDivider()
                    data.take(32).forEach { row ->
                        Row { (row as? List<*>).orEmpty().take(8).forEach { Text(it?.toString().orEmpty(), Modifier.width(140.dp).padding(8.dp)) } }
                    }
                }
            }
            else -> {
                val refs = properties.bindChildReferences(children).orEmpty()
                val boundExpanded = properties.bind(expandedProperty)
                val updateExpanded = properties.bindUpdater(expandedProperty)
                val readOnly = LocalCardReadOnly.current
                var localExpanded by rememberSaveable(readOnly, boundExpanded) { mutableStateOf(boundExpanded ?: false) }
                val expanded = if (readOnly) localExpanded else boundExpanded ?: localExpanded
                val child: @Composable (Int, Boolean) -> Unit = { index, gridCell ->
                    val ref = refs[index]
                    key(ref.id, ref.baseDataPath) {
                        when (val state = observeA2uiComponentState(ref)) {
                            is A2uiComponentState.Success ->
                                CompositionLocalProvider(LocalCardGridCell provides (gridCell && state.component.type == "Button")) {
                                    A2uiComponent(state.component)
                                }
                            is A2uiComponentState.Error -> Text("⚠", color = MaterialTheme.colorScheme.error)
                            A2uiComponentState.Loading -> CircularProgressIndicator(Modifier.size(16.dp))
                        }
                    }
                }
                Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (name == "Expandable") TextButton(onClick = {
                        if (updateExpanded != null && !readOnly) updateCardField(inputError) { updateExpanded(!expanded) } else localExpanded = !expanded
                    }, enabled = LocalCardBrowse.current) { Text(title) }
                    else if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
                    if (name != "Expandable" || expanded) {
                        if (name == "Grid") BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val explicitCount = properties.bind(columnCount)?.toInt()?.coerceIn(1, 4)
                            val count = explicitCount ?: if (maxWidth < 340.dp) 1 else 2
                            val spans = (properties.bind(columnSpans) as? List<*>)?.map { (it as Number).toInt() }
                                ?: List(refs.size) { 1 }
                            val gridRows = cardGridRows(count, spans)
                            val width = if (explicitCount != null) maxOf(maxWidth, (count * 56 + (count - 1) * 8).dp) else maxWidth
                            Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                gridRows.forEach { cells ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val cellWidth = (width - 8.dp * (count - 1)) / count
                                        cells.forEach { cell ->
                                            Box(Modifier.width(cellWidth * cell.span + 8.dp * (cell.span - 1))) {
                                                child(cell.index, true)
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        } else refs.indices.forEach { child(it, false) }
                    }
                }
            }
        }
    }
}

/** Keep the official schemas while disabling input during incremental replay. */
private object CardButton : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String, variant: A2uiBasicCatalogV1.Button.Variant,
        action: Map<String, Any?>, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>, modifier: Modifier,
    ) {
        val child = observeA2uiComponentState(childId)
        val event = action["event"] as? Map<*, *>
        if (LocalCardReadOnly.current && event?.get("name") == "local_action") return
        val enabled = LocalCardBrowse.current && child is A2uiComponentState.Success && checks.all { it.condition }
        val content: @Composable RowScope.() -> Unit = {
            when (child) {
                is A2uiComponentState.Success -> A2uiComponent(child.component)
                else -> Text("…")
            }
        }
        Column(modifier) {
            val buttonModifier = if (LocalCardGridCell.current) Modifier.fillMaxWidth().heightIn(min = 48.dp) else Modifier
            val buttonPadding = if (LocalCardGridCell.current) PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                else if (variant == A2uiBasicCatalogV1.Button.Variant.Borderless) ButtonDefaults.TextButtonContentPadding
                else ButtonDefaults.ContentPadding
            when (variant) {
                A2uiBasicCatalogV1.Button.Variant.Primary -> Button({ dispatchAction(action) }, modifier = buttonModifier, contentPadding = buttonPadding, enabled = enabled, content = content)
                A2uiBasicCatalogV1.Button.Variant.Secondary -> OutlinedButton({ dispatchAction(action) }, modifier = buttonModifier, contentPadding = buttonPadding, enabled = enabled, content = content)
                A2uiBasicCatalogV1.Button.Variant.Borderless -> TextButton({ dispatchAction(action) }, modifier = buttonModifier, contentPadding = buttonPadding, enabled = enabled, content = content)
            }
            checks.firstOrNull { !it.condition }?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private object CardCheckBox : A2uiBasicCatalogV1.CheckBox {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String, value: Boolean, onValueChange: (Boolean) -> Unit, enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>, modifier: Modifier,
    ) {
        val inputError = LocalCardInputError.current
        val canEdit = enabled && LocalCardInteractive.current
        Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = canEdit, role = Role.Checkbox,
            onValueChange = { next -> updateCardField(inputError) { onValueChange(next) } }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(value, onCheckedChange = null, enabled = canEdit)
            Text(label, Modifier.weight(1f).padding(vertical = 8.dp))
        }
    }
}

/** Decorative mistakes must not remove unrelated form fields or disable their actions. */
private object CardIcon : A2uiBasicCatalogV1.Icon {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        source: A2uiBasicCatalogV1.Icon.Source,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        if (source !is A2uiBasicCatalogV1.Icon.BuiltIn) {
            val warn = LocalCardIconWarning.current
            LaunchedEffect(source) { warn() }
            Icon(Icons.Outlined.HelpOutline, stringResource(R.string.dynamic_ui_icon_missing), modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else with(MaterialA2uiBasicCatalogV1Defaults.icon) {
            TypedContent(source, accessibility, modifier)
        }
    }
}
