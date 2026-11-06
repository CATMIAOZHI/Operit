package com.ai.assistance.operit.ui.features.orbit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.OrbitDevRecorder
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.ui.features.chat.components.TranscriptJumpHost
import com.ai.assistance.operit.ui.features.chat.components.rawChunks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Developer view tools for one chat: what was really sent, what happened unseen, and raw search. */

private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun clockOf(at: Long): String = synchronized(clock) { clock.format(Date(at)) }

/** Beyond this the clipboard may refuse the text; it is copied piece by piece instead. */
private const val MAX_COPY = 400_000

/**
 * Copies [text] for a developer. Too long for the clipboard, it says so with [tooBig] instead of
 * failing the page (one transaction can only carry so much).
 */
internal fun orbitDevCopy(context: Context, text: String, tooBig: Int = R.string.orbit_dev_request_too_big) {
    if (text.length > MAX_COPY) {
        Toast.makeText(context, tooBig, Toast.LENGTH_LONG).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("orbit-dev", text))
        Toast.makeText(context, R.string.orbit_dev_request_copied, Toast.LENGTH_SHORT).show()
    } catch (e: RuntimeException) {
        Toast.makeText(context, tooBig, Toast.LENGTH_LONG).show()
    }
}

private fun copy(context: Context, text: String) = orbitDevCopy(context, text)

/** One line of a request shown in the sheet. */
@Immutable
private sealed interface RequestLine {
    class Section(val title: String) : RequestLine
    /** The head of one message, with its raw JSON to copy. */
    class Head(val title: String, val raw: String) : RequestLine
    class Param(val key: String, val value: String) : RequestLine
    class Body(val text: String) : RequestLine
    /** One tool as declared to the model; tapping its name shows the whole declaration. */
    class Tool(val name: String, val raw: String) : RequestLine
}

private class RequestTitles(val params: String, val system: String, val messages: String, val tools: String, val toolConfig: String)

/** Top-level fields that are the conversation itself; the rest are the request's parameters. */
private val conversationKeys = setOf(
    "messages", "input", "contents", "tools", "system", "systemInstruction", "system_instruction",
    "instructions", "toolConfig", "tool_config",
)

/**
 * The request inside an account route's envelope ({ model, project, request: { contents … } }),
 * or null when [body] is the request itself.
 */
private fun envelopeInner(body: JSONObject): JSONObject? =
    body.optJSONObject("request")?.takeIf { inner -> conversationKeys.none { body.has(it) } && conversationKeys.any { inner.has(it) } }

private fun paramsOf(body: JSONObject, prefix: String): List<Pair<String, String>> =
    body.keys().asSequence().filter { it !in conversationKeys }.map { key ->
        val value = body.get(key)
        (prefix + key) to (if (value is JSONObject || value is JSONArray) value.toString().let {
            if (it.length > 600) it.take(600) + "…" else it
        } else value.toString())
    }.toList()

/** The parameters a request went with: model, thinking, temperature and the like. */
internal fun requestParams(body: JSONObject): List<Pair<String, String>> {
    val inner = envelopeInner(body) ?: return paramsOf(body, "")
    return paramsOf(body, "").filter { it.first != "request" } + paramsOf(inner, "request.")
}

/** The words in a message's content or parts, other blocks kept as JSON. */
private fun textOf(value: Any?): String? = when (value) {
    null, JSONObject.NULL -> null
    is String -> value
    is JSONArray -> (0 until value.length()).mapNotNull { textOf(value.get(it)) }.joinToString("\n\n")
    is JSONObject -> if (value.has("text") && value.opt("text") is String) value.getString("text")
        else textOf(value.opt("parts")) ?: value.toString(2)
    else -> value.toString()
}

/** Each tool declared in the request, by name, with its declaration as sent. */
private fun toolDeclarations(tools: JSONArray): List<Pair<String, JSONObject>> = (0 until tools.length()).flatMap { i ->
    val tool = tools.optJSONObject(i) ?: return@flatMap emptyList()
    (tool.optJSONArray("functionDeclarations") ?: tool.optJSONArray("function_declarations"))?.let { list ->
        return@flatMap (0 until list.length()).mapNotNull { j ->
            list.optJSONObject(j)?.let { (it.optString("name").ifBlank { "?" }) to it }
        }
    }
    val name = tool.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() }
        ?: tool.optString("name").takeIf { it.isNotBlank() }
        ?: tool.optString("type").ifBlank { "?" }
    listOf(name to tool)
}

private fun MutableList<RequestLine>.text(text: String) {
    rawChunks(text).forEach { add(RequestLine.Body(it)) }
}

/** The request laid out for reading: parameters, system prompt, each message, then the tools. */
private fun requestLines(body: Any?, titles: RequestTitles): List<RequestLine> {
    val lines = ArrayList<RequestLine>()
    if (body !is JSONObject) {
        lines.text(body?.toString().orEmpty())
        return lines
    }
    lines += RequestLine.Section(titles.params)
    requestParams(body).forEach { (k, v) -> lines += RequestLine.Param(k, v) }
    val conversation = envelopeInner(body) ?: body
    return conversationLines(conversation, titles, lines)
}

private fun conversationLines(body: JSONObject, titles: RequestTitles, lines: ArrayList<RequestLine>): List<RequestLine> {
    val system = listOf("system", "systemInstruction", "system_instruction", "instructions")
        .mapNotNull { key -> body.opt(key)?.takeIf { it != JSONObject.NULL } }
    if (system.isNotEmpty()) {
        lines += RequestLine.Section(titles.system)
        system.forEach { lines.text(textOf(it).orEmpty()) }
    }
    val messages = body.optJSONArray("messages") ?: body.optJSONArray("contents") ?: body.optJSONArray("input")
    if (messages != null) {
        lines += RequestLine.Section(String.format(titles.messages, messages.length()))
        for (i in 0 until messages.length()) {
            val item = messages.get(i)
            if (item !is JSONObject) {
                lines += RequestLine.Head("#${i + 1}", item.toString())
                lines.text(item.toString())
                continue
            }
            val role = item.optString("role").ifBlank { item.optString("type") }.ifBlank { "?" }
            lines += RequestLine.Head("#${i + 1} $role", item.toString(2))
            textOf(item.opt("content") ?: item.opt("parts"))?.takeIf { it.isNotEmpty() }?.let { lines.text(it) }
            item.keys().asSequence().filter { it != "role" && it != "content" && it != "parts" }.forEach { key ->
                val value = item.get(key)
                lines.text("$key: " + when (value) { is JSONObject -> value.toString(2); is JSONArray -> value.toString(2); else -> value.toString() })
            }
        }
    }
    body.optJSONArray("tools")?.let { tools ->
        val declared = toolDeclarations(tools)
        lines += RequestLine.Section(String.format(titles.tools, declared.size))
        declared.forEach { (name, declaration) -> lines += RequestLine.Tool(name, declaration.toString(2)) }
    }
    // How the model may call them (Gemini's toolConfig, tool_choice and the like live in parameters).
    listOf("toolConfig", "tool_config").mapNotNull { key -> body.opt(key)?.takeIf { it != JSONObject.NULL } }.forEach { config ->
        lines += RequestLine.Section(titles.toolConfig)
        lines.text(if (config is JSONObject) config.toString(2) else config.toString())
    }
    return lines
}

@Composable
private fun DevSheetTitle(title: String, detail: String?, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun DevEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
    )
}

@Composable
private fun DevPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clip(RoundedCornerShape(50)).selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    if (kind == OrbitDevRecorder.KIND_TOOL) R.string.orbit_dev_request_kind_tool else R.string.orbit_dev_request_kind_send,
)

/** One request read and laid out, under the file name it came from. */
private class LoadedRequest(val name: String, val record: JSONObject, val lines: List<RequestLine>)

/**
 * The requests this chat really sent, newest first, after every change made on the way (cards
 * turned into quotes, tool results into their own roles, metadata dropped, plugins). [initial]
 * opens a given request (by file name), for a message's info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitDevRequestSheet(chatId: String, onDismiss: () -> Unit, initial: String? = null) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val changes by OrbitDevRecorder.changes.collectAsState()
    val requests by produceState<List<OrbitDevRecorder.Request>?>(null, chatId, changes) {
        value = try {
            OrbitDevRecorder.requests(context, chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    var picked by remember { mutableStateOf(initial) }
    val list = requests
    val shown = list?.let { all ->
        all.firstOrNull { it.file.name == picked } ?: all.firstOrNull()
    }
    val titles = RequestTitles(
        stringResource(R.string.orbit_dev_request_params),
        stringResource(R.string.orbit_dev_request_system),
        stringResource(R.string.orbit_dev_request_messages),
        stringResource(R.string.orbit_dev_request_tools),
        stringResource(R.string.orbit_dev_request_tool_config),
    )
    // How each request ended and where its time went: the result noted after it, before the next.
    val results by produceState<List<OrbitDevRecorder.Event>>(emptyList(), chatId, changes) {
        value = try {
            OrbitDevRecorder.events(context, chatId).filter { it.kind == OrbitDevRecorder.EVENT_RESULT }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    // Which kept requests really went out: a send noted after the request was built, before the next.
    val marks by produceState<List<OrbitDevRecorder.Mark>?>(null, chatId, changes) {
        value = try {
            OrbitDevRecorder.marks(context, chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    // The next request built after one, kept body or not: what is noted before it belongs to that one.
    fun nextBuilt(at: Long): Long? =
        marks?.filter { !it.sent && it.at > at }?.minOfOrNull { it.at }
            ?: list.orEmpty().filter { it.at > at }.minOfOrNull { it.at }
    val result = shown?.let { request ->
        val newer = nextBuilt(request.at)
        results.filter { it.at >= request.at && (newer == null || it.at < newer) }.minByOrNull { it.at }
    }
    val unsent = remember(list, marks) {
        val all = list.orEmpty()
        val kept = marks ?: return@remember emptySet<String>()
        val sends = kept.filter { it.sent }.map { it.at }
        // The next request built after each, kept body or not, bounds which sends are its own.
        val builds = kept.filterNot { it.sent }.map { it.at }.sorted()
        // Marks older than the oldest one kept were trimmed; those requests are not judged.
        val since = kept.minOfOrNull { it.at } ?: return@remember emptySet<String>()
        all.mapNotNull { request ->
            val newer = builds.firstOrNull { it > request.at }
            request.file.name.takeIf {
                request.at >= since && sends.none { at -> at >= request.at && (newer == null || at < newer) }
            }
        }.toSet()
    }
    // Read and laid out off the main thread: a request can be megabytes. The request on screen
    // stays while the next one loads, so switching does not blank the sheet.
    var loaded by remember { mutableStateOf<LoadedRequest?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(shown?.file?.name, attempt) {
        val request = shown ?: return@LaunchedEffect
        failed = null
        try {
            loaded = withContext(Dispatchers.Default) {
                val record = OrbitDevRecorder.read(request)
                LoadedRequest(request.file.name, record, requestLines(record.opt("body"), titles))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = request.file.name
        }
    }
    // Each request keeps where it was read to.
    val positions = remember { HashMap<String, LazyListState>() }
    val openByRequest = remember { HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>>() }
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            val current = loaded?.takeIf { it.name == shown?.file?.name }
            val record = current?.record
            DevSheetTitle(
                stringResource(R.string.orbit_dev_requests),
                record?.let { "${it.optString("label")} · ${android.text.format.Formatter.formatShortFileSize(context, it.optLong("bytes"))}" }
                    ?: stringResource(R.string.orbit_dev_requests_hint),
            ) {
                if (record != null) {
                    TextButton(onClick = {
                        scope.launch {
                            val text = withContext(Dispatchers.Default) {
                                record.opt("body").let { (it as? JSONObject)?.toString(2) ?: it.toString() }
                            }
                            copy(context, text)
                        }
                    }) {
                        Text(stringResource(R.string.orbit_dev_request_copy_all))
                    }
                }
            }
            if (list == null) return@Column
            if (list.isEmpty()) {
                DevEmpty(stringResource(R.string.orbit_dev_requests_none))
                return@Column
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.file.name }) { request ->
                    val notSent = if (request.file.name in unsent) " · " + stringResource(R.string.orbit_dev_request_unsent_mark) else ""
                    DevPill("${clockOf(request.at)} · ${kindLabel(request.kind)}$notSent", request == shown) {
                        picked = request.file.name
                    }
                }
            }
            if (failed != null && failed == shown?.file?.name) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text(stringResource(R.string.orbit_dev_request_failed), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.orbit_dev_request_retry)) }
                }
                return@Column
            }
            val content = loaded
            if (content == null) {
                OrbitDelayedSpinner()
                return@Column
            }
            val listState = remember(content.name) { positions.getOrPut(content.name) { LazyListState() } }
            // Which tool declarations are open, per request, so scrolling away does not close them.
            val openTools = remember(content.name) { openByRequest.getOrPut(content.name) { androidx.compose.runtime.mutableStateMapOf() } }
            val shownResult = result.takeIf { current != null }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)
                    .alpha(if (current == null) 0.5f else 1f),
            ) {
                SelectionContainer {
                    LazyColumn(state = listState, contentPadding = PaddingValues(14.dp)) {
                        // How it ended and where its time went, scrolled with the request so it never
                        // takes the body's room on a short screen.
                        if (shownResult != null) {
                            item(key = "result", contentType = "result") {
                                val (status, log) = OrbitDevRecorder.resultParts(shownResult.text)
                                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.orbit_dev_request_result) +
                                            resultLabel(status)?.let { " · " + stringResource(it) }.orEmpty(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = resultColor(status),
                                    )
                                    Text(stringResource(R.string.orbit_dev_result_log), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        // The previous request stays faded while the next loads; its copy buttons are off.
                        // Keyed by line, so the result arriving above does not move what is being read.
                        itemsIndexed(content.lines, key = { index, _ -> index }, contentType = { _, line -> line::class }) { index, line ->
                            RequestLineRow(
                                line,
                                enabled = current != null,
                                open = openTools[index] == true,
                                onToggle = { openTools[index] = openTools[index] != true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestLineRow(line: RequestLine, enabled: Boolean, open: Boolean = false, onToggle: () -> Unit = {}) {
    val context = LocalContext.current
    when (line) {
        is RequestLine.Section -> Text(
            line.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        is RequestLine.Head -> Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(line.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { copy(context, line.raw) }, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(orbitIcon("copy"), contentDescription = stringResource(R.string.copy_message), modifier = Modifier.size(16.dp))
            }
        }
        is RequestLine.Param -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(line.key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(line.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
        }
        is RequestLine.Body -> Text(line.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        is RequestLine.Tool -> {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = line.name, onClick = onToggle)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(orbitIcon(if (open) "chevron-down" else "chevron-right"), contentDescription = null,
                        modifier = Modifier.size(14.dp))
                    Text(line.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                    if (open) {
                        IconButton(onClick = { copy(context, line.raw) }, enabled = enabled, modifier = Modifier.size(28.dp)) {
                            Icon(orbitIcon("copy"), contentDescription = stringResource(R.string.copy_message), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (open) {
                    rawChunks(line.raw).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

/** What happened in this chat that its messages do not show: retries, local repairs, compaction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitDevEventsSheet(chatId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val changes by OrbitDevRecorder.changes.collectAsState()
    val events by produceState<List<OrbitDevRecorder.Event>?>(null, chatId, changes) {
        value = try {
            OrbitDevRecorder.events(context, chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            DevSheetTitle(stringResource(R.string.orbit_dev_events), stringResource(R.string.orbit_dev_events_hint))
            val list = events ?: return@Column
            if (list.isEmpty()) {
                DevEmpty(stringResource(R.string.orbit_dev_events_none))
                return@Column
            }
            SelectionContainer {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { event ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${clockOf(event.at)} · " + stringResource(when (event.kind) {
                                        OrbitDevRecorder.EVENT_RETRY -> R.string.orbit_dev_event_kind_retry
                                        OrbitDevRecorder.EVENT_REPAIR -> R.string.orbit_dev_event_kind_repair
                                        OrbitDevRecorder.EVENT_RESULT -> R.string.orbit_dev_event_kind_result
                                        else -> R.string.orbit_dev_event_kind_compact
                                    }),
                                    style = MaterialTheme.typography.labelMedium,
                                    // A result that did not end done (stopped or failed) shows like a retry.
                                    color = when (event.kind) {
                                        OrbitDevRecorder.EVENT_RETRY -> MaterialTheme.colorScheme.error
                                        OrbitDevRecorder.EVENT_RESULT -> resultColor(OrbitDevRecorder.resultParts(event.text).first)
                                        else -> MaterialTheme.colorScheme.tertiary
                                    },
                                )
                                if (event.kind == OrbitDevRecorder.EVENT_RESULT) {
                                    val (status, log) = OrbitDevRecorder.resultParts(event.text)
                                    resultLabel(status)?.let {
                                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = resultColor(status))
                                    }
                                    Text(stringResource(R.string.orbit_dev_result_log), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text(event.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How a request ended, in the screen's language. */
private fun resultLabel(status: String?): Int? = when (status) {
    OrbitDevRecorder.RESULT_OK -> R.string.orbit_dev_result_ok
    OrbitDevRecorder.RESULT_FAILED -> R.string.orbit_dev_result_failed
    OrbitDevRecorder.RESULT_CANCELLED -> R.string.orbit_dev_result_cancelled
    else -> null
}

/** A request that did not end done (stopped or failed) shows like a retry. */
@Composable
private fun resultColor(status: String?) =
    if (status == null || status == OrbitDevRecorder.RESULT_OK) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.error

/** One message the raw search found: where it is and the stored text around the match. */
@Immutable
private class RawHit(val timestamp: Long, val sender: String, val mode: String, val text: String)

private const val RAW_SEARCH_LIMIT = 100

/** What a search for [needle] found, so results never pass for those of a newer query. */
private class RawResult(val needle: String, val result: Result<List<RawHit>>)

/**
 * Messages in [chatId] whose stored text holds [query]: thinking, tool calls and results, hidden
 * rows and tags included, newest first. Typing on or leaving stops the scan inside SQLite.
 */
private suspend fun rawSearch(context: Context, chatId: String, query: String): List<RawHit> = withContext(Dispatchers.IO) {
    val sql = "SELECT timestamp, sender, displayMode, " +
        "SUBSTR(content, MAX(1, INSTR(LOWER(content), LOWER(?)) - 40), 220) FROM messages " +
        "WHERE chatId = ? AND INSTR(LOWER(content), LOWER(?)) > 0 ORDER BY timestamp DESC LIMIT $RAW_SEARCH_LIMIT"
    val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
    val signal = CancellationSignal()
    coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            db.query(SimpleSQLiteQuery(sql, arrayOf<Any?>(query, chatId, query)), signal).use { cursor ->
                val hits = ArrayList<RawHit>()
                while (cursor.moveToNext()) {
                    ensureActive()
                    hits += RawHit(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                        cursor.getString(3).orEmpty().replace('\n', ' '))
                }
                hits
            }
        } catch (e: OperationCanceledException) {
            throw CancellationException("Search replaced")
        } finally {
            watcher.cancel()
        }
    }
}

/** Searches the stored text of the chat on screen; a result moves the transcript to its message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitDevSearchSheet(chatId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // null: nothing searched yet; a failure shows as such instead of "no results".
    var hits by remember { mutableStateOf<RawResult?>(null) }
    val needle = query.trim()
    val listState = rememberLazyListState()
    LaunchedEffect(needle, chatId) {
        if (needle.isEmpty()) {
            hits = null
            return@LaunchedEffect
        }
        delay(250)
        val result = try {
            Result.success(rawSearch(context, chatId, needle))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        hits = RawResult(needle, result)
        listState.scrollToItem(0)
    }
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            DevSheetTitle(stringResource(R.string.orbit_dev_raw_search), null)
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SearchPill(query, { query = it }, placeholder = stringResource(R.string.orbit_dev_search_hint))
            }
            val shownResult = hits ?: return@Column
            // Still the results of the previous words while the new search runs: shown faded, not tappable.
            val stale = shownResult.needle != needle
            val found = shownResult.result.getOrNull()
            when {
                stale && (found == null || found.isEmpty()) -> OrbitDelayedSpinner()
                found == null -> DevEmpty(stringResource(R.string.orbit_dev_search_failed))
                found.isEmpty() -> DevEmpty(stringResource(R.string.orbit_dev_search_none))
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.alpha(if (stale) 0.5f else 1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                ) {
                    if (found.size >= RAW_SEARCH_LIMIT) item(key = "limit") {
                        Text(stringResource(R.string.orbit_dev_search_more, RAW_SEARCH_LIMIT),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    items(found, key = { it.timestamp }) { hit ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !stale, role = Role.Button) {
                                    // The sheet goes down first, then the chat moves to the message.
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        onDismiss()
                                        TranscriptJumpHost.requestMessage(chatId, hit.timestamp)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("${clockOf(hit.timestamp)} · ${hit.sender} · ${hit.mode.lowercase()}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(hit.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
