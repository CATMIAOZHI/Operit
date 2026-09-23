package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.memory.screens.MemoryLibraryPage
import androidx.compose.foundation.clickable
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.library.MemoryLearningService
import com.ai.assistance.operit.data.dao.*
import com.ai.assistance.operit.data.repository.ChatRecallRepository
import com.ai.assistance.operit.data.repository.parseRecallTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatRecallDialog(profileId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recallError = stringResource(R.string.chat_recall_error)
    val draftExtracted = stringResource(R.string.skill_draft_extracted)
    val extractionTimeout = stringResource(R.string.memory_extraction_timeout)
    val repo = remember { ChatRecallRepository(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var after by rememberSaveable { mutableStateOf("") }
    var before by rememberSaveable { mutableStateOf("") }
    var literal by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<ChatRecallHit>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<ChatRecallSession>>(emptyList()) }
    var full by remember { mutableStateOf<List<ChatRecallPart>?>(null) }
    var sourceChat by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingSession by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fullMessage by remember { mutableStateOf<ChatRecallPart?>(null) }
    var charOffset by remember { mutableStateOf(0) }
    var listState by remember { mutableStateOf(LazyListState()) }
    val backStack = remember { mutableStateListOf<() -> Unit>() }
    fun capturePage(): () -> Unit {
        val oldHits = hits; val oldSessions = sessions; val oldFull = full
        val oldSource = sourceChat; val oldBrowsing = browsingSession
        val oldOffset = offset; val oldMore = more; val oldScroll = listState
        val oldQuery = query; val oldProfile = profile; val oldRole = role
        val oldAfter = after; val oldBefore = before; val oldLiteral = literal
        val oldFilters = filters
        return {
            hits = oldHits; sessions = oldSessions; full = oldFull
            sourceChat = oldSource; browsingSession = oldBrowsing
            offset = oldOffset; more = oldMore; listState = oldScroll
            query = oldQuery; profile = oldProfile; role = oldRole
            after = oldAfter; before = oldBefore; literal = oldLiteral; filters = oldFilters
            message = null
        }
    }
    fun pushPage() {
        backStack.add(capturePage())
        listState = LazyListState()
    }
    var activeWork by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun run(block: suspend () -> Unit) {
        activeWork = scope.launch {
            busy=true; message=null
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { message=recallError }
            finally { busy=false }
        }
    }
    suspend fun load(append: Boolean) {
        val next = if(append) offset+20 else 0
        val start=parseRecallTime(after,false); val end=parseRecallTime(before,true)
        require(start<=end && role in setOf("","user","ai"))
        if(browsingSession && sourceChat!=null) {
            val page=repo.session(sourceChat!!,next,role,start,end)
            full=if(append) (full.orEmpty()+page).distinctBy { it.messageId } else page
            hits=emptyList(); sessions=emptyList(); more=page.size==20
        } else if(query.isBlank()) {
            val page=repo.browse(profile,start,end,next)
            sessions=if(append) (sessions+page).distinctBy { it.chatId } else page
            hits=emptyList(); full=null; more=page.size==20
        } else {
            val page=repo.search(query,next,role,profile,after=start,before=end,literal=literal)
            hits=if(append) (hits+page).distinctBy { it.messageId } else page
            sessions=emptyList(); full=null; more=page.size==20
        }
        offset=next
    }
    LaunchedEffect(Unit) {
        try { load(false) }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { message=recallError }
    }
    fullMessage?.let { part ->
        val partLength = part.content.codePointCount(0,part.content.length)
        MemoryLibraryPage(stringResource(R.string.memory_recall_full), profileId,
            { if (!busy) fullMessage = null }) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("${charOffset+1}–${charOffset+partLength} / ${part.totalChars}",
                    style = MaterialTheme.typography.labelMedium)
                key(charOffset) { Text(part.content,Modifier.weight(1f).verticalScroll(rememberScrollState())) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    if(charOffset>0) TextButton(onClick={ run {
                        val next=(charOffset-8000).coerceAtLeast(0)
                        val loaded=repo.message(part.messageId,next)
                        fullMessage=loaded; charOffset=next
                    } },enabled=!busy) { Text(stringResource(R.string.memory_recall_previous)) }
                    if(charOffset+partLength<part.totalChars) TextButton(onClick={ run {
                        val next=charOffset+partLength
                        val loaded=repo.message(part.messageId,next)
                        fullMessage=loaded; charOffset=next
                    } },enabled=!busy) { Text(stringResource(R.string.memory_recall_continue)) }
                }
            }
        }
        return
    }
    MemoryLibraryPage(stringResource(R.string.chat_recall_title), profileId, {
        activeWork?.cancel()
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex).invoke()
        else onDismiss()
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_recall_hint),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(query,{ query=it; more=false },enabled=!busy,singleLine=true,
                    modifier=Modifier.fillMaxWidth(),label={ Text(stringResource(R.string.chat_recall_query)) })
                Row {
                    Button(onClick={ run {
                        val restore = capturePage()
                        try {
                            browsingSession=false; sourceChat=null
                            load(false)
                            backStack.clear(); listState=LazyListState()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            restore()
                            throw e
                        }
                    } },enabled=!busy) {
                        Text(stringResource(if(query.isBlank()) R.string.memory_recall_recent else R.string.chat_recall_search))
                    }
                    TextButton(onClick={ filters=!filters }) { Text(stringResource(R.string.memory_recall_filters)) }
                    if(sourceChat!=null && !browsingSession) TextButton(onClick={ run {
                        val page=repo.session(sourceChat!!,0,role,parseRecallTime(after,false),parseRecallTime(before,true))
                        pushPage(); browsingSession=true; full=page; hits=emptyList()
                        sessions=emptyList(); offset=0; more=page.size==20
                    } },enabled=!busy) {
                        Text(stringResource(R.string.memory_recall_full))
                    }
                }
                if(filters) Column(Modifier.heightIn(max=230.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(profile,{ profile=it; more=false },label={ Text(stringResource(R.string.memory_recall_profile)) },singleLine=true,enabled=!busy)
                    OutlinedTextField(role,{ role=it; more=false },label={ Text(stringResource(R.string.memory_recall_role)) },singleLine=true,enabled=!busy)
                    OutlinedTextField(after,{ after=it; more=false },label={ Text(stringResource(R.string.memory_recall_after)) },singleLine=true,enabled=!busy)
                    OutlinedTextField(before,{ before=it; more=false },label={ Text(stringResource(R.string.memory_recall_before)) },singleLine=true,enabled=!busy)
                    Row { Text(stringResource(R.string.memory_recall_literal),Modifier.weight(1f)); Switch(literal,{ literal=it; more=false },enabled=!busy) }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Success and failure share this line, so it keeps the neutral style.
                message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(sessions.isEmpty() && hits.isEmpty() && full.isNullOrEmpty()) item { Text(stringResource(R.string.chat_recall_empty)) }
                    items(sessions,key={ "s:${it.chatId}" }) { item ->
                        Column(Modifier.fillMaxWidth().clickable(enabled=!busy) {
                            run {
                                val page=repo.session(item.chatId,0,role,parseRecallTime(after,false),parseRecallTime(before,true))
                                pushPage(); sourceChat=item.chatId; browsingSession=true
                                full=page; hits=emptyList(); sessions=emptyList(); offset=0; more=page.size==20
                            }
                        }.padding(vertical=12.dp)) {
                            Text(item.title, style=MaterialTheme.typography.titleMedium)
                            Text(DateFormat.getDateTimeInstance().format(Date(item.updatedAt)),
                                style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(top=12.dp))
                        }
                    }
                    items(hits,key={ "h:${it.messageId}" }) { hit ->
                        Card(onClick={ if(!busy) run {
                            val page=repo.context(hit.messageId,10)
                            pushPage(); sourceChat=hit.chatId; browsingSession=false; sessions=emptyList()
                            hits=page; full=null; offset=0; more=false
                        } }) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${hit.chatTitle} · ${hit.sender}",style=MaterialTheme.typography.titleSmall)
                                Text(hit.excerpt)
                                TextButton(onClick={ run { charOffset=0; fullMessage=repo.message(hit.messageId) } },enabled=!busy) {
                                    Text(stringResource(R.string.memory_recall_full))
                                }
                            }
                        }
                    }
                    items(full.orEmpty(),key={ "f:${it.messageId}" }) { part ->
                        Card {
                            Column(Modifier.padding(10.dp)) {
                                Text("${part.sender} · ${DateFormat.getDateTimeInstance().format(Date(part.timestamp))}")
                                Text(part.content)
                                if(part.containsNull || part.totalChars>part.content.codePointCount(0,part.content.length)) TextButton(onClick={
                                    run { charOffset=0; fullMessage=repo.message(part.messageId) }
                                },enabled=!busy) { Text(stringResource(R.string.memory_recall_full)) }
                            }
                        }
                    }
                    if(more) item { TextButton(onClick={ run { load(true) } },enabled=!busy) { Text(stringResource(R.string.chat_recall_more)) } }
                }
                sourceChat?.let { id ->
                    Text(stringResource(R.string.skill_draft_manual_hint),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ run {
                        try {
                            MemoryLearningService.extract(context,profileId,id)
                        } catch(e: kotlinx.coroutines.TimeoutCancellationException) {
                            // A timeout is our own limit, not a user cancellation, so report it.
                            message = extractionTimeout
                            return@run
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) {
                            // The extractor refuses with a specific, already-localized reason.
                            message = e.message?.takeIf { it.isNotBlank() } ?: recallError
                            return@run
                        }
                        message=draftExtracted
                    } },enabled=!busy) { Text(stringResource(R.string.skill_draft_extract)) }
                }
            }
        }
    }
}
