package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One message found by the search page: where it is, and its words cut to start near the match. */
internal data class OrbitSearchHit(val messageId: Long, val chatId: String, val timestamp: Long, val text: String)

/**
 * Searches what was said in an Orbit's main chat and side chats, like Muse's search: the words the
 * user typed and the words Orbit sent, never its thinking, tool calls or hidden notices.
 */
internal object OrbitTalkSearch {
    private const val PAGE = 20
    // Few at a time, so a run of very long messages is not all held at once.
    private const val BATCH = 5
    // A match found only inside tool output is skipped; this bounds how many messages one page reads.
    private const val MAX_READ_PER_PAGE = 100
    // How much text before the match a row keeps, so the match is in its first lines.
    private const val LEAD = 12
    private const val MAX_SHOWN = 240
    private val spaces = Regex("\\s+")
    // What Markdown and line breaks can put inside the words a person reads ("帮我**记**一下",
    // "帮我[记一下](…)", a heading on the next line); the first pass leaves them out on both sides.
    private val marks = setOf('*', '_', '`', '~', '[', ']', '#', '>')
    private val ignoredInSql = listOf("'*'", "'_'", "'`'", "'~'", "'['", "']'", "'#'", "'>'",
        "' '", "char(9)", "char(10)", "char(13)")

    /**
     * Ids of every message, newest first, whose stored text holds [query] once Markdown marks and
     * spaces are left out: a wide first pass, so nothing a person sees is missed. Only ids come
     * back, so all of them are kept and hidden-text matches cannot push real ones out. One scan
     * per search; typing on or leaving the page stops it inside SQLite.
     */
    suspend fun matches(context: Context, chatIds: List<String>, query: String): List<Long> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty() || chatIds.isEmpty()) return@withContext emptyList()
            val bare = trimmed.filterNot { it in marks || it.isWhitespace() }
            // A search made only of such marks (say "#") is looked for as typed.
            fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val text: String
            val patterns: List<String>
            val condition: String
            if (bare.isNotEmpty()) {
                text = ignoredInSql.fold("content") { sql, ch -> "REPLACE($sql,$ch,'')" }
                // A link's address sits between words a person reads as one ("看[文档](…)说明"), so a
                // message with a link may also hold the words in order with anything between them.
                val inOrder = bare.codePoints().toArray().joinToString("%", "%", "%") { escape(String(Character.toChars(it))) }
                patterns = listOf("%" + escape(bare) + "%", inOrder)
                condition = "($text LIKE ? ESCAPE '\\' OR (INSTR(content,'](')>0 AND $text LIKE ? ESCAPE '\\'))"
            } else {
                text = "content"
                patterns = listOf("%" + escape(trimmed) + "%")
                condition = "$text LIKE ? ESCAPE '\\'"
            }
            val sql = "SELECT messageId FROM messages WHERE chatId IN (" + chatIds.joinToString(",") { "?" } + ")" +
                " AND sender IN ('user','ai')" +
                " AND displayMode NOT IN ('HIDDEN_PLACEHOLDER','COLLABORATION_EVENT','COLLABORATION_TASK')" +
                " AND $condition ORDER BY timestamp DESC, messageId DESC"
            val args: Array<Any?> = (chatIds + patterns).toTypedArray()
            val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
            val signal = CancellationSignal()
            coroutineScope {
                // Room's own queries cannot be stopped midway; this signal can.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { signal.cancel() }
                }
                try {
                    db.query(SimpleSQLiteQuery(sql, args), signal).use { cursor ->
                        val ids = ArrayList<Long>()
                        while (cursor.moveToNext()) {
                            ensureActive()
                            ids += cursor.getLong(0)
                        }
                        ids
                    }
                } catch (e: OperationCanceledException) {
                    throw CancellationException("Search replaced")
                } finally {
                    watcher.cancel()
                }
            }
        }

    /**
     * The hits among [ids] from [from], checked against the words a person sees, and where the next
     * page starts, or null at the end. Each message is read whole, however long.
     */
    suspend fun page(context: Context, ids: List<Long>, query: String, from: Int): Pair<List<OrbitSearchHit>, Int?> =
        withContext(Dispatchers.IO) {
            val needle = query.trim().replace(spaces, " ")
            val dao = AppDatabase.getDatabase(context).chatContentDao()
            val attachmentOnly = context.getString(R.string.orbit_user_sent_attachment)
            val cardOnly = context.getString(R.string.orbit_sent_card)
            val hits = ArrayList<OrbitSearchHit>()
            var at = from
            while (at < ids.size && at - from < MAX_READ_PER_PAGE && hits.size < PAGE) {
                val batch = ids.subList(at, minOf(at + BATCH, ids.size))
                val byId = dao.getMessagesByIds(batch).associateBy { it.messageId }
                batch.forEach { id ->
                    ensureActive()
                    val row = byId[id] ?: return@forEach
                    val said = if (row.sender == "ai") OrbitSpeech.words(row.content, cardOnly)
                        else OrbitWork.userText(row.content, attachmentOnly)
                    val text = OrbitWork.plainText(said).replace(spaces, " ").trim()
                    val index = text.indexOf(needle, ignoreCase = true)
                    if (index >= 0) hits += OrbitSearchHit(row.messageId, row.chatId, row.timestamp, around(text, index))
                }
                at += batch.size
            }
            hits to at.takeIf { it < ids.size }
        }

    /** The text from a little before the match, so a match deep in a long message still shows. */
    private fun around(text: String, index: Int): String {
        var start = if (index > LEAD + 6) index - LEAD else 0
        if (start > 0 && Character.isLowSurrogate(text[start])) start--
        var end = (start + MAX_SHOWN).coerceAtMost(text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        return (if (start > 0) "…" else "") + text.substring(start, end)
    }
}

/**
 * The menu's full-screen search, laid out like Muse's: the title at the top, the results as rows of
 * matched words with the day and the chat under them, and settings, the search box and close at the
 * bottom. Tapping a result opens its chat on that message.
 */
@Composable
internal fun OrbitSearchPage(
    orbit: OrbitProfile,
    active: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenHit: (OrbitSearchHit) -> Unit,
    onOpenSettings: () -> Unit,
    onLeaveOrbit: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val chatIds = orbit.talkChatIds
    val mainTitle = stringResource(R.string.orbit_chats_main)
    val titles = remember(orbit.chatId, orbit.sideChats, mainTitle) {
        mapOf(orbit.chatId to mainTitle) + orbit.sideChats.associate { it.chatId to it.title }
    }
    var hits by remember { mutableStateOf<List<OrbitSearchHit>>(emptyList()) }
    // Every message that may match the shown words; pages are read from it in order.
    var matchIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var next by remember { mutableStateOf<Int?>(null) }
    // The words the shown results are for; the box may already hold newer ones.
    var searched by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // A later page did not load; it is kept where it was, to be tried again by tapping.
    var moreFailed by remember { mutableStateOf(false) }
    val needle = query.trim()
    LaunchedEffect(needle, chatIds) {
        if (needle.isEmpty()) {
            hits = emptyList(); matchIds = emptyList(); next = null; searched = ""
            failed = false; moreFailed = false; loading = false
            return@LaunchedEffect
        }
        // Waits for a pause in typing; the last results stay until the new ones are in.
        delay(250)
        loading = true
        try {
            val ids = OrbitTalkSearch.matches(context, chatIds, needle)
            val (found, more) = OrbitTalkSearch.page(context, ids, needle, 0)
            hits = found; matchIds = ids; next = more; failed = false; moreFailed = false; searched = needle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hits = emptyList(); matchIds = emptyList(); next = null; failed = true; moreFailed = false; searched = needle
        } finally {
            loading = false
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(searched) { if (searched.isNotEmpty()) listState.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }
    }
    // Reads on near the end of the list, and straight away while nothing was found yet.
    val wantMore = nearEnd || hits.isEmpty()
    LaunchedEffect(wantMore, next, searched, loading, moreFailed) {
        val from = next ?: return@LaunchedEffect
        if (!wantMore || loading || moreFailed || searched.isEmpty()) return@LaunchedEffect
        val forQuery = searched
        val ids = matchIds
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
            if (searched == forQuery) moreFailed = true
        } finally {
            loadingMore = false
        }
    }

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (active) {
            // The field is composed with the page; wait a frame so it can take focus.
            delay(60)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.orbit_search_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                OrbitBackToOperitPill(onLeaveOrbit)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val showing = searched.isNotEmpty() && needle.isNotEmpty()
                val stillLooking = showing && hits.isEmpty() && next != null && !moreFailed
                when {
                    needle.isEmpty() -> SearchNote(stringResource(R.string.orbit_search_hint))
                    (loading && (!showing || hits.isEmpty())) || stillLooking -> CircularProgressIndicator(
                        Modifier.align(Alignment.TopCenter).padding(top = 32.dp).size(24.dp), strokeWidth = 2.dp,
                    )
                    !showing -> Unit
                    failed -> SearchNote(stringResource(R.string.orbit_search_failed))
                    hits.isEmpty() && moreFailed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SearchNote(stringResource(R.string.orbit_search_more_failed))
                        RetryButton { moreFailed = false }
                    }
                    hits.isEmpty() -> SearchNote(stringResource(R.string.orbit_search_empty))
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(hits, key = { _, hit -> hit.messageId }) { i, hit ->
                            val chatTitle = titles[hit.chatId] ?: mainTitle
                            val label = remember(hit.timestamp, chatTitle) { searchDayLabel(context, hit.timestamp) + " · " + chatTitle }
                            SearchRow(
                                hit = hit, query = searched,
                                label = label,
                                divider = i > 0,
                                onClick = { onOpenHit(hit) },
                            )
                        }
                        if (loadingMore) item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (moreFailed) item(key = "retry") {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.orbit_search_more_failed), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                RetryButton { moreFailed = false }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OrbitMenuRoundAction("settings", stringResource(R.string.settings), onOpenSettings)
                val searchLabel = stringResource(R.string.orbit_chats_search_label)
                val clearLabel = stringResource(R.string.orbit_search_clear)
                BasicTextField(
                    value = query, onValueChange = { onQueryChange(it.take(200)) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f).height(52.dp).focusRequester(focus)
                        .semantics { contentDescription = searchLabel },
                    decorationBox = { field ->
                        Row(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .padding(start = 16.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(orbitIcon("search"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(stringResource(R.string.search), style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                field()
                            }
                            if (query.isNotEmpty()) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .clickable(role = Role.Button, onClickLabel = clearLabel) { onQueryChange("") }
                                        .semantics { contentDescription = clearLabel },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(orbitIcon("circle-x"), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                )
                OrbitMenuRoundAction("x", stringResource(R.string.orbit_search_close), onClose)
            }
        }
    }
}

@Composable
internal fun SearchNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
    )
}

/** A small pill that asks for a page that did not load to be read again. */
@Composable
internal fun RetryButton(onClick: () -> Unit) {
    Text(
        stringResource(R.string.orbit_search_retry), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
internal fun SearchRow(hit: OrbitSearchHit, query: String, label: String, divider: Boolean, onClick: () -> Unit) {
    val strong = MaterialTheme.colorScheme.onSurface
    val text = remember(hit.text, query, strong) {
        highlighted(hit.text, query, SpanStyle(fontWeight = FontWeight.Bold, color = strong))
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (divider) {
            HorizontalDivider(
                Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(
                text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [text] with every place it holds [query] in [style]. */
private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        addStyle(style, at, at + query.length)
        from = at + query.length
    }
}

/** "Today", "Yesterday", the month and day this year, or the full date for older years. */
internal fun searchDayLabel(context: Context, timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, then)) return context.getString(R.string.orbit_search_today)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, then)) return context.getString(R.string.orbit_search_yesterday)
    val locale = Locale.getDefault()
    val skeleton = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "MMMd" else "yMMMd"
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        .format(Date(timestamp))
}
