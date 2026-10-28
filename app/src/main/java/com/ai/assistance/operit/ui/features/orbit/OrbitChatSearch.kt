package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.sqlite.db.SimpleSQLiteQuery
import coil.compose.AsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitFavorites
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import com.ai.assistance.operit.util.ChatImageBitmapCache
import com.ai.assistance.operit.util.ChatMarkupRegex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the chat search can look for besides words, like a messaging app's "search for" shortcuts. */
internal enum class OrbitFindKind(val icon: String, val label: Int) {
    DATE("calendar", R.string.orbit_find_date),
    MEDIA("image", R.string.orbit_find_media),
    FILE("file-text", R.string.orbit_find_files),
    LINK("link", R.string.orbit_find_links),
    MUSIC("music", R.string.orbit_find_music),
    CARD("layout-grid", R.string.orbit_find_cards),
    APPROVAL("shield-check", R.string.orbit_find_approvals),
    FAVORITE("star", R.string.orbit_find_favorites),
}

/** One thing found in a chat: where it was said, and what to show for it. */
internal data class OrbitFound(
    val key: String,
    val chatId: String,
    val timestamp: Long,
    val title: String,
    val detail: String = "",
    /** A picture the user sent, kept in the app's image pool. */
    val imageId: String? = null,
    val imageSource: String? = null,
    /** A picture or file Orbit sent, in its own folder. */
    val file: File? = null,
    val video: Boolean = false,
    /** For an approval card: which approval, to show its answer. */
    val approvalId: String? = null,
    /** For a kept message: its favorite, so it can be taken off. */
    val favoriteId: String? = null,
)

/**
 * Finds pictures, files, links, audio, cards and approvals in one Orbit chat. A wide SQL pass picks
 * the messages that may hold them, newest first; pages then read those messages and take out what
 * the chat really shows (Orbit's cards come only from its tools' results).
 */
internal object OrbitChatFind {
    private const val BATCH = 10
    private const val PAGE = 40
    private const val MAX_READ_PER_PAGE = 200
    private val audioTypes = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "amr")
    private val videoTypes = setOf("mp4", "mov", "webm", "mkv", "3gp", "avi", "m4v")
    private val urlPattern = Regex("https?://[^\\s<>\"'`)\\]）】」，。、；！？]+", RegexOption.IGNORE_CASE)
    private val uiFence = Regex("```a2ui(?:-ref)?[^\\n]*\\n")
    private const val SHOWN = " AND sender IN ('user','ai')" +
        " AND displayMode NOT IN ('HIDDEN_PLACEHOLDER','COLLABORATION_EVENT','COLLABORATION_TASK')"

    private fun condition(kind: OrbitFindKind): String = when (kind) {
        OrbitFindKind.MEDIA, OrbitFindKind.FILE, OrbitFindKind.MUSIC ->
            "(content LIKE '%<link%' OR content LIKE '%<attachment%' OR content LIKE '%orbit_card%' OR content LIKE '%![%](%')"
        OrbitFindKind.LINK -> "content LIKE '%http%'"
        OrbitFindKind.CARD -> "(content LIKE '%orbit_card%' OR content LIKE '%```a2ui%')"
        OrbitFindKind.APPROVAL -> "(content LIKE '%orbit_card%' AND content LIKE '%approval_id%')"
        OrbitFindKind.DATE, OrbitFindKind.FAVORITE -> "0"
    }

    /** Ids of the messages in [chatId] that may hold [kind], newest first. */
    suspend fun candidates(context: Context, chatId: String, kind: OrbitFindKind): List<Long> = withContext(Dispatchers.IO) {
        val sql = "SELECT messageId FROM messages WHERE chatId = ?$SHOWN AND ${condition(kind)} ORDER BY timestamp DESC, messageId DESC"
        AppDatabase.getDatabase(context).openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(chatId))).use { cursor ->
            val ids = ArrayList<Long>()
            while (cursor.moveToNext()) {
                ensureActive()
                ids += cursor.getLong(0)
            }
            ids
        }
    }

    /**
     * The time of the first message of each day [chatId] has messages on, newest day first. The
     * user's own first message is preferred: an early reply row of Orbit may hold only thinking or
     * tools, which the chat does not show, so there would be nothing to land on.
     */
    suspend fun days(context: Context, chatId: String): List<Long> = withContext(Dispatchers.IO) {
        val sql = "SELECT COALESCE(MIN(CASE WHEN sender = 'user' THEN timestamp END), MIN(timestamp)) FROM messages WHERE chatId = ?$SHOWN" +
            " GROUP BY date(timestamp / 1000, 'unixepoch', 'localtime') ORDER BY 1 DESC"
        AppDatabase.getDatabase(context).openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, arrayOf(chatId))).use { cursor ->
            val out = ArrayList<Long>()
            while (cursor.moveToNext()) out += cursor.getLong(0)
            out
        }
    }

    /** What [ids] hold of [kind] from [from] on, and where the next page starts, or null at the end. */
    suspend fun page(context: Context, orbitId: String, ids: List<Long>, kind: OrbitFindKind, from: Int): Pair<List<OrbitFound>, Int?> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).chatContentDao()
            val files = OrbitFiles.of(context, orbitId)
            val english = !context.resources.configuration.locales[0].language.startsWith("zh")
            val found = ArrayList<OrbitFound>()
            var at = from
            while (at < ids.size && at - from < MAX_READ_PER_PAGE && found.size < PAGE) {
                val batch = ids.subList(at, minOf(at + BATCH, ids.size))
                val byId = dao.getMessagesByIds(batch).associateBy { it.messageId }
                batch.forEach { id ->
                    ensureActive()
                    val row = byId[id] ?: return@forEach
                    found += extract(context, files, english, kind, row.messageId, row.chatId, row.timestamp, row.sender, row.content)
                }
                at += batch.size
            }
            found to at.takeIf { it < ids.size }
        }

    /**
     * The first message on the day of [dayTime] that the chat really shows, so opening a day lands
     * on a visible row (an Orbit reply row holding only thinking or tools shows nothing).
     */
    suspend fun firstShownOfDay(context: Context, chatId: String, dayTime: Long): Long = withContext(Dispatchers.IO) {
        val day = Calendar.getInstance().apply {
            timeInMillis = dayTime
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = day.timeInMillis
        val end = day.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val sql = "SELECT messageId FROM messages WHERE chatId = ?$SHOWN AND timestamp >= ? AND timestamp < ?" +
            " ORDER BY timestamp, messageId LIMIT 200"
        val ids = AppDatabase.getDatabase(context).openHelper.readableDatabase
            .query(SimpleSQLiteQuery(sql, arrayOf<Any>(chatId, start, end))).use { cursor ->
                val out = ArrayList<Long>()
                while (cursor.moveToNext()) out += cursor.getLong(0)
                out
            }
        val dao = AppDatabase.getDatabase(context).chatContentDao()
        ids.chunked(BATCH).forEach { batch ->
            ensureActive()
            val byId = dao.getMessagesByIds(batch).associateBy { it.messageId }
            batch.mapNotNull { byId[it] }.firstOrNull { it.sender == "user" || OrbitSpeech.of(it.content).isNotBlank() }
                ?.let { return@withContext it.timestamp }
        }
        dayTime
    }

    private fun typeOf(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun extract(
        context: Context, files: OrbitFiles, english: Boolean, kind: OrbitFindKind,
        messageId: Long, chatId: String, timestamp: Long, sender: String, content: String,
    ): List<OrbitFound> {
        val out = ArrayList<OrbitFound>()
        fun add(k: OrbitFindKind, item: (String) -> OrbitFound) {
            if (k == kind) out += item("$messageId:${out.size}")
        }
        val picture = context.getString(R.string.orbit_find_image)
        val video = context.getString(R.string.orbit_find_video)
        val audio = context.getString(R.string.orbit_find_audio)
        if (sender == "user") {
            MediaLinkParser.extractImageLinkTags(content).forEach { tag ->
                add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, picture, imageId = tag.id, imageSource = tag.sourcePath) }
            }
            MediaLinkParser.extractMediaLinkTags(content).forEach { tag ->
                when (tag.type) {
                    "video" -> add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, video, video = true) }
                    "audio" -> add(OrbitFindKind.MUSIC) { OrbitFound(it, chatId, timestamp, audio) }
                    "file" -> add(OrbitFindKind.FILE) { OrbitFound(it, chatId, timestamp, tag.fileName ?: tag.id) }
                }
            }
            (ChatMarkupRegex.attachmentDataTag.findAll(content) + ChatMarkupRegex.attachmentDataSelfClosingTag.findAll(content))
                .forEach { m ->
                    val name = m.groupValues[2]
                    val type = m.groupValues[3].lowercase()
                    // The screen the user was on and the workspace are context, not something sent.
                    if (name == "screen_content.json" || type.startsWith("application/vnd.workspace")) return@forEach
                    val ext = typeOf(name)
                    val size = m.groupValues[4].toLongOrNull()?.let { android.text.format.Formatter.formatShortFileSize(context, it) }.orEmpty()
                    when {
                        type.startsWith("image/") || ext in OrbitChatCards.imageTypes ->
                            add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name) }
                        type.startsWith("video/") || ext in videoTypes ->
                            add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name, video = true) }
                        type.startsWith("audio/") || ext in audioTypes ->
                            add(OrbitFindKind.MUSIC) { OrbitFound(it, chatId, timestamp, name, size) }
                        else -> add(OrbitFindKind.FILE) { OrbitFound(it, chatId, timestamp, name, size) }
                    }
                }
            if (kind == OrbitFindKind.LINK) {
                links(OrbitWork.userText(content, "")).forEach { url -> add(OrbitFindKind.LINK) { OrbitFound(it, chatId, timestamp, url) } }
            }
            return out
        }
        val shown = OrbitSpeech.of(content)
        val (speech, cards) = OrbitSpeech.splitCards(shown)
        // Audio, pictures and video Orbit put in its words as Markdown (a voice message is one).
        if (kind == OrbitFindKind.MEDIA || kind == OrbitFindKind.MUSIC || kind == OrbitFindKind.FILE) {
            markdownMedia.findAll(speech).forEach { m ->
                val url = m.groupValues[2]
                val path = if (url.startsWith("file://")) Uri.parse(url).path else null
                val fileName = (path ?: Uri.parse(url).path ?: url).substringAfterLast('/')
                val name = m.groupValues[1].trim().ifBlank { fileName }
                val ext = typeOf(fileName)
                when {
                    ext in audioTypes -> add(OrbitFindKind.MUSIC) { OrbitFound(it, chatId, timestamp, name) }
                    ext in videoTypes -> add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name, video = true) }
                    ext in OrbitChatCards.imageTypes ->
                        add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name, file = path?.let(::File)) }
                    path != null -> add(OrbitFindKind.FILE) { OrbitFound(it, chatId, timestamp, name) }
                }
            }
        }
        cards.forEach { card ->
            val o = runCatching { JSONObject(card) }.getOrNull() ?: return@forEach
            when (o.optString("kind")) {
                OrbitChatCards.IMAGE, OrbitChatCards.FILE -> {
                    val path = o.optString("path")
                    val name = o.optString("name").ifBlank { path.substringAfterLast('/') }
                    val ext = typeOf(name)
                    val size = o.optLong("size", -1L).takeIf { it >= 0 }
                        ?.let { android.text.format.Formatter.formatShortFileSize(context, it) }.orEmpty()
                    val isImage = o.optString("kind") == OrbitChatCards.IMAGE
                    when {
                        isImage -> add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name, file = files.chatFile(path)) }
                        ext in videoTypes -> add(OrbitFindKind.MEDIA) { OrbitFound(it, chatId, timestamp, name, video = true) }
                        ext in audioTypes -> add(OrbitFindKind.MUSIC) { OrbitFound(it, chatId, timestamp, name, size) }
                        else -> add(OrbitFindKind.FILE) { OrbitFound(it, chatId, timestamp, name, size) }
                    }
                }
                OrbitChatCards.APPROVAL -> add(OrbitFindKind.APPROVAL) {
                    OrbitFound(it, chatId, timestamp, o.optString("title").ifBlank { OrbitChatCards.summary(card, english) },
                        approvalId = o.optString("approval_id"))
                }
                else -> add(OrbitFindKind.CARD) { OrbitFound(it, chatId, timestamp, OrbitChatCards.summary(card, english)) }
            }
        }
        if (kind == OrbitFindKind.CARD) {
            val ui = context.getString(R.string.orbit_find_ui_card)
            repeat(uiFence.findAll(speech).count()) { add(OrbitFindKind.CARD) { key -> OrbitFound(key, chatId, timestamp, ui) } }
        }
        if (kind == OrbitFindKind.LINK) {
            links(OrbitSpeech.words(content, "")).forEach { url -> add(OrbitFindKind.LINK) { OrbitFound(it, chatId, timestamp, url) } }
        }
        return out
    }

    private fun links(text: String): List<String> =
        urlPattern.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct().toList()

    private val markdownMedia = Regex("!\\[([^\\]\\n]{0,200})]\\(([^)\\s]{1,2048})\\)")
}

/**
 * Search inside one Orbit chat, like a messaging app's chat search: type to find words, or pick
 * what to look for (a day, pictures and videos, files, links, audio, cards, approvals, kept
 * messages). Tapping a result opens the chat on that message.
 */
@Composable
internal fun OrbitChatSearchPage(
    orbit: OrbitProfile,
    chatId: String,
    active: Boolean,
    onOpen: (chatId: String, timestamp: Long) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember(chatId) { mutableStateOf("") }
    var kind by remember(chatId) { mutableStateOf<OrbitFindKind?>(null) }
    BackHandler(enabled = active && kind != null) { kind = null }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active, kind) {
        if (active && kind == null) {
            // The field is composed with the page; wait a frame so it can take focus.
            delay(60)
            runCatching { focus.requestFocus() }
        } else {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = kind
                if (current == null) {
                    OrbitCircleButton(orbitIcon("x"), stringResource(R.string.orbit_search_close), onClose)
                    SearchField(query, { query = it.take(200) }, focus, Modifier.weight(1f))
                } else {
                    OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), { kind = null })
                    Text(
                        stringResource(current.label), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val current = kind
                when {
                    current == OrbitFindKind.DATE -> DayPicker(chatId) { onOpen(chatId, it) }
                    current == OrbitFindKind.FAVORITE -> FavoriteList(orbit, onOpen)
                    current != null -> FoundList(orbit, chatId, current, onOpen)
                    query.isBlank() -> KindGrid { kind = it }
                    else -> WordResults(orbit, chatId, query.trim(), onOpen)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, focus: FocusRequester, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(R.string.orbit_chat_search_open)
    val clearLabel = stringResource(R.string.orbit_search_clear)
    BasicTextField(
        value = query, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.height(48.dp).focusRequester(focus).semantics { contentDescription = label },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(orbitIcon("search"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(label, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = clearLabel) { onChange("") }
                            .semantics { contentDescription = clearLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(orbitIcon("circle-x"), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** "Search for": the shortcuts, four to a row. */
@Composable
private fun KindGrid(onPick: (OrbitFindKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            stringResource(R.string.orbit_find_title), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
        OrbitFindKind.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                row.forEach { k ->
                    val label = stringResource(k.label)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { onPick(k) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(orbitIcon(k.icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Typed words found in this chat, read a page at a time like the menu's search. */
@Composable
private fun WordResults(orbit: OrbitProfile, chatId: String, needle: String, onOpen: (String, Long) -> Unit) {
    val context = LocalContext.current
    var hits by remember(chatId) { mutableStateOf<List<OrbitSearchHit>>(emptyList()) }
    var ids by remember(chatId) { mutableStateOf<List<Long>>(emptyList()) }
    var next by remember(chatId) { mutableStateOf<Int?>(null) }
    var searched by remember(chatId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(needle, chatId) {
        delay(250)
        loading = true
        try {
            val found = OrbitTalkSearch.matches(context, listOf(chatId), needle)
            val (first, more) = OrbitTalkSearch.page(context, found, needle, 0)
            hits = first; ids = found; next = more; failed = false; searched = needle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hits = emptyList(); ids = emptyList(); next = null; failed = true; searched = needle
        } finally {
            loading = false
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(searched) { if (searched.isNotEmpty()) listState.scrollToItem(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val wantMore by nearEnd(listState)
    LaunchedEffect(wantMore || hits.isEmpty(), next, searched, loading) {
        val from = next ?: return@LaunchedEffect
        if (!(wantMore || hits.isEmpty()) || loading || searched.isEmpty()) return@LaunchedEffect
        val forQuery = searched
        loadingMore = true
        try {
            val (found, more) = OrbitTalkSearch.page(context, ids, forQuery, from)
            if (searched == forQuery) {
                val seen = hits.mapTo(HashSet()) { it.messageId }
                hits = hits + found.filter { it.messageId !in seen }
                next = more
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (searched == forQuery) next = null
        } finally {
            loadingMore = false
        }
    }
    when {
        (loading || searched != needle) && hits.isEmpty() || (hits.isEmpty() && next != null) -> Loading()
        failed -> SearchNote(stringResource(R.string.orbit_search_failed))
        hits.isEmpty() -> SearchNote(stringResource(R.string.orbit_search_empty))
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(hits, key = { _, hit -> hit.messageId }) { i, hit ->
                val label = remember(hit.timestamp) { searchDayLabel(context, hit.timestamp) }
                SearchRow(hit = hit, query = searched, label = label, divider = i > 0, onClick = { onOpen(hit.chatId, hit.timestamp) })
            }
            if (loadingMore) item(key = "more") { Loading(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun nearEnd(state: LazyListState) = remember(state) {
    derivedStateOf {
        val info = state.layoutInfo
        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 6
    }
}

@Composable
private fun nearEnd(state: LazyGridState) = remember(state) {
    derivedStateOf {
        val info = state.layoutInfo
        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 12
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** Pictures and videos as a grid, everything else as rows; read a page at a time as it scrolls. */
@Composable
private fun FoundList(orbit: OrbitProfile, chatId: String, kind: OrbitFindKind, onOpen: (String, Long) -> Unit) {
    val context = LocalContext.current
    var found by remember(kind) { mutableStateOf<List<OrbitFound>>(emptyList()) }
    var ids by remember(kind) { mutableStateOf<List<Long>>(emptyList()) }
    var next by remember(kind) { mutableStateOf<Int?>(null) }
    var ready by remember(kind) { mutableStateOf(false) }
    var failed by remember(kind) { mutableStateOf(false) }
    var loadingMore by remember(kind) { mutableStateOf(false) }
    LaunchedEffect(kind, chatId) {
        try {
            val list = OrbitChatFind.candidates(context, chatId, kind)
            val (first, more) = OrbitChatFind.page(context, orbit.id, list, kind, 0)
            ids = list; found = first; next = more
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            ready = true
        }
    }
    // An approval card shows its answer as the approval list has it now.
    val approvalsVersion by OrbitApprovals.version.collectAsState()
    val approvals by produceState<Map<String, OrbitApprovals.Approval>>(emptyMap(), kind, approvalsVersion) {
        if (kind == OrbitFindKind.APPROVAL) {
            value = runCatching { OrbitApprovals.list(context, orbit.id).associateBy { it.id } }.getOrDefault(emptyMap())
        }
    }
    suspend fun more() {
        val from = next ?: return
        loadingMore = true
        try {
            val (page, after) = OrbitChatFind.page(context, orbit.id, ids, kind, from)
            found = found + page
            next = after
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            next = null
        } finally {
            loadingMore = false
        }
    }
    if (!ready) { Loading(); return }
    if (failed) { SearchNote(stringResource(R.string.orbit_search_failed)); return }
    if (kind == OrbitFindKind.MEDIA) {
        val gridState = rememberLazyGridState()
        val wantMore by nearEnd(gridState)
        LaunchedEffect(wantMore || found.isEmpty(), next) {
            if ((wantMore || found.isEmpty()) && next != null && !loadingMore) more()
        }
        if (found.isEmpty()) {
            if (next != null || loadingMore) Loading() else SearchNote(stringResource(R.string.orbit_find_empty))
            return
        }
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 2.dp), state = gridState,
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(found, key = { it.key }) { item -> MediaTile(item) { onOpen(item.chatId, item.timestamp) } }
        }
        return
    }
    val listState = rememberLazyListState()
    val wantMore by nearEnd(listState)
    LaunchedEffect(wantMore || found.isEmpty(), next) {
        if ((wantMore || found.isEmpty()) && next != null && !loadingMore) more()
    }
    if (found.isEmpty()) {
        if (next != null || loadingMore) Loading() else SearchNote(stringResource(R.string.orbit_find_empty))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(found, key = { _, it -> it.key }) { i, item ->
            val day = remember(item.timestamp) { searchDayLabel(context, item.timestamp) }
            val answer = item.approvalId?.let { approvals[it] }?.let { approvalState(it) }
            val detail = listOfNotNull(day, item.detail.takeIf { it.isNotEmpty() }, answer?.let { stringResource(it) }).joinToString(" · ")
            FoundRow(kind.icon, item.title, detail, divider = i > 0, onClick = { onOpen(item.chatId, item.timestamp) })
        }
        if (loadingMore) item(key = "more") { Loading(Modifier.padding(16.dp)) }
    }
}

private fun approvalState(approval: OrbitApprovals.Approval): Int? = when (approval.state) {
    OrbitApprovals.PENDING -> R.string.orbit_approval_state_pending
    OrbitApprovals.APPROVED -> R.string.orbit_approval_state_approved
    OrbitApprovals.DENIED -> R.string.orbit_approval_state_denied
    else -> null
}

/** Longest side in pixels of a picture decoded for a four-across grid tile. */
private const val TILE_SIDE = 360

@Composable
private fun MediaTile(item: OrbitFound, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClickLabel = item.title, onClick = onClick)
            .semantics { contentDescription = item.title },
        contentAlignment = Alignment.Center,
    ) {
        when {
            item.file != null && !item.video -> AsyncImage(
                model = item.file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            item.imageId != null -> {
                val id = item.imageId
                // Tiles are small: decode a small copy, never the full picture.
                val bitmap by produceState<Bitmap?>(ChatImageBitmapCache.get("thumb:$TILE_SIDE:$id"), id) {
                    if (value == null) value = withContext(Dispatchers.IO) {
                        ChatImageBitmapCache.loadThumbnail(id, item.imageSource, TILE_SIDE)
                    }
                }
                bitmap?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Icon(orbitIcon("image"), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Icon(orbitIcon(if (item.video) "play" else "image"), null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FoundRow(
    icon: String, title: String, detail: String, divider: Boolean, onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (divider) {
            HorizontalDivider(
                Modifier.padding(start = 76.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(orbitIcon(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.invoke()
        }
    }
}

/** Everything kept from this Orbit's chats, newest first; each can be taken off again. */
@Composable
private fun FavoriteList(orbit: OrbitProfile, onOpen: (String, Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by OrbitFavorites.version.collectAsState()
    val kept by produceState<List<OrbitFavorites.Favorite>?>(null, orbit.id, version) {
        value = runCatching { OrbitFavorites.list(context, orbit.id) }.getOrDefault(emptyList())
    }
    val mainTitle = stringResource(R.string.orbit_chats_main)
    val titles = remember(orbit.chatId, orbit.sideChats, mainTitle) {
        mapOf(orbit.chatId to mainTitle) + orbit.sideChats.associate { it.chatId to it.title }
    }
    val list = kept ?: run { Loading(); return }
    if (list.isEmpty()) {
        SearchNote(stringResource(R.string.orbit_find_favorites_empty))
        return
    }
    val me = stringResource(R.string.orbit_transcript_me)
    val name = orbitName(orbit)
    val removeLabel = stringResource(R.string.orbit_find_unfavorite)
    var open by remember { mutableStateOf<OrbitFavorites.Favorite?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, it -> it.id }) { i, fav ->
            val day = remember(fav.timestamp) { searchDayLabel(context, fav.timestamp) }
            // A chat deleted since keeps its favorite; it just cannot be opened any more.
            val chatTitle = titles[fav.chatId]
            val detail = listOfNotNull(if (fav.sender == "ai") name else me, day, chatTitle).joinToString(" · ")
            FoundRow(
                "star", fav.text, detail, divider = i > 0,
                onClick = { open = fav },
                trailing = {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = removeLabel) {
                                scope.launch { runCatching { OrbitFavorites.remove(context, orbit.id, fav.id) } }
                            }
                            .semantics { contentDescription = removeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(orbitIcon("star-off"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
    // A kept message is read in full here; the chat it came from opens from the dialog if it still exists.
    open?.let { fav ->
        val chatTitle = titles[fav.chatId]
        OrbitDialog(
            onDismissRequest = { open = null },
            title = listOfNotNull(if (fav.sender == "ai") name else me, searchDayLabel(context, fav.timestamp)).joinToString(" · "),
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(fav.text) } },
            confirmButton = if (chatTitle != null) {
                {
                    OrbitDialogAction(onClick = { open = null; onOpen(fav.chatId, fav.timestamp) }) {
                        Text(stringResource(R.string.orbit_find_open_message))
                    }
                }
            } else null,
            dismissButton = {
                OrbitDialogAction(onClick = { open = null }, secondary = true) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/** A calendar of the months this chat has messages in; a day with messages opens the chat there. */
@Composable
private fun DayPicker(chatId: String, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resolved on tap: the first message of that day the chat really shows.
    val openDay: (Long) -> Unit = { time ->
        scope.launch { onOpen(runCatching { OrbitChatFind.firstShownOfDay(context, chatId, time) }.getOrDefault(time)) }
    }
    val days by produceState<List<Long>?>(null, chatId) {
        value = runCatching { OrbitChatFind.days(context, chatId) }.getOrDefault(emptyList())
    }
    val list = days ?: run { Loading(); return }
    if (list.isEmpty()) {
        SearchNote(stringResource(R.string.orbit_find_empty))
        return
    }
    // Month (year * 12 + month) -> day of month -> the first message that day.
    val months = remember(list) {
        val cal = Calendar.getInstance()
        list.groupBy {
            cal.timeInMillis = it
            cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
        }.mapValues { (_, times) ->
            times.associateBy { cal.timeInMillis = it; cal.get(Calendar.DAY_OF_MONTH) }
        }.toSortedMap(compareByDescending { it })
    }
    val locale = Locale.getDefault()
    val monthFormat = remember(locale) {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
    }
    val weekdays = remember(locale) {
        val first = Calendar.getInstance().firstDayOfWeek
        val format = SimpleDateFormat("EEEEE", locale)
        (0 until 7).map { i ->
            Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1) }.let { format.format(it.time) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(months.keys.toList(), key = { it }) { month ->
            val cal = remember(month) {
                Calendar.getInstance().apply { clear(); set(month / 12, month % 12, 1) }
            }
            val firstDay = cal.firstDayOfWeek
            val lead = (cal.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7
            val count = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val active = months[month].orEmpty()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    monthFormat.format(cal.time), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                ((0 until lead).map { 0 } + (1..count).toList()).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        week.forEach { day ->
                            val time = active[day]
                            Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                if (day > 0) {
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape)
                                            .then(
                                                if (time != null) Modifier
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                                    .clickable(role = Role.Button) { openDay(time) }
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            day.toString(), style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (time != null) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (time != null) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                        )
                                    }
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
