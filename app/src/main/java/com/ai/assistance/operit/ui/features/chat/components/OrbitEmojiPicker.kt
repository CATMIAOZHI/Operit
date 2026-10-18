package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One emoji in the picker and the words that find it (Chinese and English names and keywords). */
data class OrbitEmoji(val emoji: String, val words: String)

/** A section of the picker, like "Smileys & emotion". */
data class OrbitEmojiGroup(val id: String, val items: List<OrbitEmoji>)

/**
 * Every emoji, read from the bundled list (tools/emoji/gen_orbit_emoji.py) the first time the
 * picker opens. Emoji this phone's font cannot draw are left out, so no empty boxes show.
 */
object OrbitEmojiCatalog {
    private const val TAG = "OrbitEmojiCatalog"
    private const val ASSET = "orbit_emoji/emoji.json"
    @Volatile private var cached: List<OrbitEmojiGroup>? = null

    suspend fun load(context: Context): List<OrbitEmojiGroup> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            cached ?: try {
                val text = context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                val paint = android.graphics.Paint()
                val array = JSONArray(text)
                (0 until array.length()).map { i ->
                    val group = array.getJSONObject(i)
                    val items = group.getJSONArray("e")
                    OrbitEmojiGroup(
                        id = group.getString("g"),
                        items = (0 until items.length()).mapNotNull { j ->
                            val entry = items.getJSONArray(j)
                            val emoji = entry.getString(0)
                            if (paint.hasGlyph(emoji)) OrbitEmoji(emoji, entry.optString(1)) else null
                        },
                    )
                }.filter { it.items.isNotEmpty() }.also { cached = it }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not read the emoji list: ${e.message}")
                emptyList()
            }
        }
    }
}

/**
 * Which emoji the user reacts with and how often, so the long-press menu leads with their own
 * favourites. Shared by every Orbit; kept on the phone only.
 */
object OrbitEmojiUsage {
    private const val PREFS = "orbit_emoji_usage"
    private const val KEY = "usage"
    private const val KEEP = 64

    /** The tapbacks offered before the user has used any. */
    val defaults = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    private data class Use(val count: Int, val last: Long)

    private val state = MutableStateFlow<Map<String, Use>?>(null)
    private val rankedState = MutableStateFlow<Map<String, Int>>(emptyMap())

    private fun read(context: Context): Map<String, Use> {
        state.value?.let { return it }
        val json = runCatching {
            JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val map = json.keys().asSequence().associateWith { key ->
            val pair = json.optJSONArray(key)
            Use(pair?.optInt(0) ?: 0, pair?.optLong(1) ?: 0L)
        }.filterValues { it.count > 0 }
        state.value = map
        publish(map)
        return map
    }

    /** Emoji -> times used; most used first, newest first when two are used as often. */
    @Synchronized
    fun observe(context: Context): StateFlow<Map<String, Int>> {
        read(context)
        return rankedState.asStateFlow()
    }

    private fun publish(map: Map<String, Use>) {
        rankedState.value = map.entries
            .sortedWith(compareByDescending<Map.Entry<String, Use>> { it.value.count }.thenByDescending { it.value.last })
            .associate { it.key to it.value.count }
    }

    @Synchronized
    fun record(context: Context, emoji: String) {
        val now = System.currentTimeMillis()
        val current = read(context).toMutableMap()
        val old = current[emoji]
        current[emoji] = Use((old?.count ?: 0) + 1, now)
        val kept = current.entries
            .sortedWith(compareByDescending<Map.Entry<String, Use>> { it.value.count }.thenByDescending { it.value.last })
            .take(KEEP).associate { it.key to it.value }
        state.value = kept
        publish(kept)
        val json = JSONObject()
        kept.forEach { (key, use) -> json.put(key, JSONArray().put(use.count).put(use.last)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    /** [count] tapbacks for the menu: the user's most used, then the defaults to fill the row. */
    fun quick(ranked: Map<String, Int>, count: Int): List<String> =
        (ranked.keys.toList() + defaults).distinct().take(count)
}

private val groupIcons = mapOf(
    "smileys" to "😀", "people" to "👋", "animals" to "🐶", "food" to "🍔", "travel" to "✈️",
    "activities" to "⚽", "objects" to "💡", "symbols" to "❤️", "flags" to "🏁",
)

@Composable
private fun groupTitle(id: String): String = stringResource(
    when (id) {
        "smileys" -> R.string.orbit_emoji_group_smileys
        "people" -> R.string.orbit_emoji_group_people
        "animals" -> R.string.orbit_emoji_group_animals
        "food" -> R.string.orbit_emoji_group_food
        "travel" -> R.string.orbit_emoji_group_travel
        "activities" -> R.string.orbit_emoji_group_activities
        "objects" -> R.string.orbit_emoji_group_objects
        "symbols" -> R.string.orbit_emoji_group_symbols
        else -> R.string.orbit_emoji_group_flags
    }
)

/** The "+" at the end of the tapback row; opens the sheet with every emoji. */
@Composable
fun OrbitMoreTapbacksButton(onClick: () -> Unit) {
    val label = stringResource(R.string.orbit_emoji_more)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

private sealed interface PickerRow {
    data class Header(val key: String, val title: String) : PickerRow
    data class Cell(val key: String, val emoji: String) : PickerRow
}

/**
 * Every emoji in a sheet, like Muse: search at the top, a row of section buttons that jump to
 * each section, the user's frequent ones first, then all sections. Picking one closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitEmojiPickerSheet(selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempt by remember { mutableStateOf(0) }
    // An empty list means the list could not be read; a retry reads it again.
    val groups by produceState<List<OrbitEmojiGroup>?>(null, attempt) { value = null; value = OrbitEmojiCatalog.load(context) }
    val ranked by remember { OrbitEmojiUsage.observe(context) }.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    // A new search starts at its first result.
    androidx.compose.runtime.LaunchedEffect(query) { if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0) }
    val scope = rememberCoroutineScope()
    val frequentTitle = stringResource(R.string.orbit_emoji_frequent)
    // One paint and one set of words for every cell: each cell only draws, it measures no text.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val emojiPaint = remember(density) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 28.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
        }
        EmojiInk(paint, -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f)
    }
    val resources = context.resources
    val selectedHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val titles = groups.orEmpty().associate { it.id to groupTitle(it.id) }
    val rows: List<PickerRow> = remember(groups, ranked, query, titles) {
        val all = groups.orEmpty()
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isNotEmpty()) {
            all.asSequence().flatMap { it.items.asSequence() }
                .filter { item -> terms.all { term -> item.words.contains(term) || item.emoji.contains(term) } }
                .distinctBy { it.emoji }
                .map<OrbitEmoji, PickerRow> { PickerRow.Cell("s:${it.emoji}", it.emoji) }.toList()
        } else buildList<PickerRow> {
            val frequent = ranked.keys.take(16)
            if (frequent.isNotEmpty()) {
                add(PickerRow.Header("h:frequent", frequentTitle))
                frequent.forEach { add(PickerRow.Cell("f:$it", it)) }
            }
            all.forEach { group ->
                add(PickerRow.Header("h:${group.id}", titles[group.id].orEmpty()))
                group.items.forEach { add(PickerRow.Cell("${group.id}:${it.emoji}", it.emoji)) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 16.dp)) {
            val searchLabel = stringResource(R.string.orbit_emoji_search)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = searchLabel },
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(searchLabel, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            field()
                        }
                    }
                },
            )
            // Section buttons, hidden while searching: one tap jumps to that section. They scroll
            // sideways, so each keeps a full-size touch area on a narrow screen.
            if (query.isBlank() && !groups.isNullOrEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp)) {
                    groups.orEmpty().forEach { group ->
                        val title = titles[group.id].orEmpty()
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = title) {
                                    val index = rows.indexOfFirst { it is PickerRow.Header && it.key == "h:${group.id}" }
                                    if (index >= 0) scope.launch { gridState.scrollToItem(index) }
                                }
                                .semantics { contentDescription = title },
                        ) {
                            Text(groupIcons[group.id] ?: "•", fontSize = 20.sp)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            when {
                groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups!!.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.orbit_emoji_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                }
                rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
                    Text(stringResource(R.string.orbit_emoji_no_result), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(48.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        rows,
                        key = { row -> when (row) { is PickerRow.Header -> row.key; is PickerRow.Cell -> row.key } },
                        span = { row -> GridItemSpan(if (row is PickerRow.Header) maxLineSpan else 1) },
                        contentType = { row -> row::class },
                    ) { row ->
                        when (row) {
                            is PickerRow.Header -> Text(
                                row.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
                                    .semantics { heading() },
                            )
                            is PickerRow.Cell -> EmojiCell(row.emoji, row.emoji == selected, emojiPaint, selectedHighlight, resources) {
                                onPick(row.emoji)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The paint every emoji cell draws with, and how far to move the baseline to center a glyph. */
private class EmojiInk(val paint: android.graphics.Paint, val centerShift: Float)

/**
 * One emoji, drawn straight onto the cell with the system's text drawing. A Text per cell laid
 * out a paragraph for every emoji that scrolled into view, which dropped frames in a list of a
 * few thousand. The words for screen readers are only built when one asks for them.
 */
@Composable
private fun EmojiCell(
    emoji: String,
    isSelected: Boolean,
    ink: EmojiInk,
    highlight: Color,
    resources: android.content.res.Resources,
    onClick: () -> Unit,
) {
    Box(
        // The whole cell takes the tap; the highlight is a slightly smaller circle inside it.
        modifier = Modifier.aspectRatio(1f).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = resources.getString(R.string.orbit_react_with, emoji)
                if (isSelected) stateDescription = resources.getString(R.string.orbit_reaction_selected)
                this.selected = isSelected
            }
            .drawBehind {
                if (isSelected) drawCircle(highlight, radius = size.minDimension / 2f - 2.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(emoji, size.width / 2f, size.height / 2f + ink.centerShift, ink.paint)
            },
    )
}
