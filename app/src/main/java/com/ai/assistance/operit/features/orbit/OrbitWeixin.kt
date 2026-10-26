package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.chat.TurnInputInbox
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.canAddSideChat
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.components.ORBIT_CARD_FENCE
import com.ai.assistance.operit.ui.features.chat.components.ORBIT_OPTIONS_FENCE
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit's WeChat channel. The user binds one WeChat ClawBot by scanning a QR code; from then on what
 * they send it lands in a side chat of its own (titled 「微信」), and whatever Orbit says there is sent
 * back to WeChat: words, pictures and files, cards as a short line. Only the bound WeChat user is
 * heard, and nothing else in the user's WeChat is reachable.
 *
 * The login token and the queues are kept encrypted outside backups ([OrbitSecretFile]). Messages
 * waiting to go in either direction are saved first, so a restart loses none of them.
 */
internal object OrbitWeixin {
    private const val TAG = "OrbitWeixin"
    private const val STORE = "weixin"
    private const val MAX_INBOX = 50
    private const val MAX_OUTBOX = 100
    private const val TEXT_LIMIT = 3_800
    private const val MAX_IN_IMAGE = 20L * 1024 * 1024
    private const val MAX_IN_FILE = 100L * 1024 * 1024
    private const val MAX_OUT_BYTES = 25L * 1024 * 1024
    private const val STALE_PAUSE_MS = 60 * 60_000L
    /**
     * A reply WeChat refused this many times in a row is set aside until the user next writes (WeChat
     * often only lets a bot answer then), so it never blocks the rest. Network trouble never counts:
     * those replies wait as long as it takes.
     */
    private const val MAX_SEND_TRIES = 8
    /** Set aside this many times, through as many messages from the user, a reply is given up. */
    private const val MAX_PARKS = 3

    data class Inbound(
        val id: String,
        val text: String,
        val files: List<AttachmentInfo>,
        /** When it was first handed to the chat; the chat is searched from here to see it landed. */
        val firstTryAt: Long = 0L,
        val lastTryAt: Long = 0L,
        val tries: Int = 0,
        /** The exact user row timestamps a new turn was asked to save it under, one per try. */
        val stamps: List<Long> = emptyList(),
        /** The exact words handed into a running turn, saved verbatim as its user row, one per try. */
        val steered: List<String> = emptyList(),
    )
    data class Outbound(
        val id: String,
        val kind: String,
        val text: String,
        val path: String,
        val name: String,
        val tries: Int = 0,
        /** Sent with every try of this item, so a retry is never a second message. */
        val clientId: String = newClientId(),
        /** Refused for now; waits for the user's next message instead of being tried again. */
        val parked: Boolean = false,
        val parks: Int = 0,
    )

    private fun newClientId() = "operit-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    data class State(
        val token: String,
        val baseUrl: String,
        val botId: String,
        val userId: String,
        val chatId: String?,
        val buf: String = "",
        val contextToken: String = "",
        /** Newest chat row already looked at for replies. */
        val cursor: Long = 0L,
        val connectedAt: Long = System.currentTimeMillis(),
        val lastInboundAt: Long = 0L,
        val lastSentAt: Long = 0L,
        val lastError: String = "",
        /** The login stopped working; polling waits until then, and the user is told to reconnect. */
        val staleSince: Long = 0L,
        val inbox: List<Inbound> = emptyList(),
        val outbox: List<Outbound> = emptyList(),
    )

    data class Status(
        val orbitId: String,
        val connected: Boolean,
        val chatId: String? = null,
        /** The last poll reached WeChat. */
        val online: Boolean = false,
        val loginExpired: Boolean = false,
        val lastError: String = "",
        val lastInboundAt: Long = 0L,
        val lastSentAt: Long = 0L,
        val waitingToSend: Int = 0,
        val waitingToDeliver: Int = 0,
        val connectedAt: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val runnerLock = Mutex()
    private var cache: Pair<String, State?>? = null
    private var runner: Job? = null
    private var runnerOrbit: String? = null
    @Volatile private var online = false
    private val inboxWake = Channel<Unit>(Channel.CONFLATED)
    private val outboxWake = Channel<Unit>(Channel.CONFLATED)
    private val chatIdFlow = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow<Status?>(null)
    /** The running Orbit's WeChat link; null until read. */
    val status: StateFlow<Status?> = _status
    private var watching: Job? = null

    private fun store(context: Context, orbitId: String) = OrbitSecretFile.forOrbit(context, orbitId, STORE)

    private fun english(context: Context) = LocaleUtils.usesEnglishPrompt(context)

    /** What WeChat messages start with in the chat, so Orbit and the user both see where they came from. */
    fun prefix(english: Boolean) = if (english) "[WeChat] " else "[微信] "

    // ---- saved state ----

    private fun decode(text: String): State {
        val o = JSONObject(text)
        fun files(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map {
            AttachmentInfo(it.optString("path"), it.optString("name"), it.optString("mime"), it.optLong("size"))
        }
        val inbox = o.optJSONArray("inbox").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) } }
            .map {
                val stamps = it.optJSONArray("stamps").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.optLong(i) } }
                val steered = it.optJSONArray("steered").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.optString(i) } }
                Inbound(it.optString("id"), it.optString("text"), files(it.optJSONArray("files")),
                    it.optLong("first_try_at"), it.optLong("last_try_at"), it.optInt("tries"), stamps, steered)
            }
        val outbox = o.optJSONArray("outbox").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) } }
            .map {
                Outbound(it.optString("id"), it.optString("kind"), it.optString("text"), it.optString("path"),
                    it.optString("name"), it.optInt("tries"), it.optString("client_id").ifBlank { newClientId() },
                    it.optBoolean("parked"), it.optInt("parks"))
            }
        return State(
            token = o.getString("token"), baseUrl = o.optString("base_url").ifBlank { OrbitWeixinApi.DEFAULT_BASE_URL },
            botId = o.optString("bot_id"), userId = o.getString("user_id"),
            chatId = o.optString("chat_id").takeIf { it.isNotBlank() },
            buf = o.optString("buf"), contextToken = o.optString("context_token"), cursor = o.optLong("cursor"),
            connectedAt = o.optLong("connected_at"), lastInboundAt = o.optLong("last_inbound_at"),
            lastSentAt = o.optLong("last_sent_at"), lastError = o.optString("last_error"),
            staleSince = o.optLong("stale_since"), inbox = inbox, outbox = outbox,
        )
    }

    private fun encode(s: State): String = JSONObject()
        .put("token", s.token).put("base_url", s.baseUrl).put("bot_id", s.botId).put("user_id", s.userId)
        .put("chat_id", s.chatId.orEmpty()).put("buf", s.buf).put("context_token", s.contextToken).put("cursor", s.cursor)
        .put("connected_at", s.connectedAt).put("last_inbound_at", s.lastInboundAt).put("last_sent_at", s.lastSentAt)
        .put("last_error", s.lastError).put("stale_since", s.staleSince)
        .put("inbox", JSONArray(s.inbox.map { i ->
            JSONObject().put("id", i.id).put("text", i.text).put("files", JSONArray(i.files.map {
                JSONObject().put("path", it.filePath).put("name", it.fileName).put("mime", it.mimeType).put("size", it.fileSize)
            })).put("first_try_at", i.firstTryAt).put("last_try_at", i.lastTryAt).put("tries", i.tries)
                .put("stamps", JSONArray(i.stamps)).put("steered", JSONArray(i.steered))
        }))
        .put("outbox", JSONArray(s.outbox.map {
            JSONObject().put("id", it.id).put("kind", it.kind).put("text", it.text).put("path", it.path).put("name", it.name)
                .put("tries", it.tries).put("client_id", it.clientId).put("parked", it.parked).put("parks", it.parks)
        }))
        .toString()

    private fun readLocked(context: Context, orbitId: String): State? {
        cache?.takeIf { it.first == orbitId }?.let { return it.second }
        val state = runCatching { store(context, orbitId).read()?.let(::decode) }
            .onFailure { AppLogger.w(TAG, "WeChat link could not be read: ${it.message}") }.getOrNull()
        cache = orbitId to state
        return state
    }

    suspend fun load(context: Context, orbitId: String): State? = withContext(Dispatchers.IO) {
        lock.withLock { readLocked(context.applicationContext, orbitId) }
    }

    /** Changes the saved state; null from [change] (or no state) leaves it as it is. */
    private suspend fun update(context: Context, orbitId: String, change: (State) -> State?): State? = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = readLocked(context, orbitId) ?: return@withLock null
            val next = change(now) ?: return@withLock now
            if (next != now) {
                store(context, orbitId).write(encode(next))
                cache = orbitId to next
            }
            next
        }.also { publish(orbitId, it) }
    }

    private fun publish(orbitId: String, s: State?) {
        _status.value = if (s == null) Status(orbitId, connected = false) else Status(
            orbitId = orbitId, connected = true, chatId = s.chatId, online = online && s.staleSince == 0L,
            loginExpired = s.staleSince > 0L, lastError = s.lastError, lastInboundAt = s.lastInboundAt,
            lastSentAt = s.lastSentAt, waitingToSend = s.outbox.size, waitingToDeliver = s.inbox.size,
            connectedAt = s.connectedAt,
        )
        chatIdFlow.value = s?.chatId
    }

    // ---- starting and stopping ----

    /** Called once at app start: keeps the running Orbit's link polling while that Orbit runs. */
    fun restore(context: Context) {
        val app = context.applicationContext
        if (watching != null) return
        watching = scope.launch {
            OrbitPreferences.getInstance(app).settingsFlow
                .map { s -> s.active?.takeUnless { it.deleting }?.id }
                .distinctUntilChanged()
                .collectLatest { sync(app, it) }
        }
    }

    /** Starts or stops polling so that exactly the running Orbit's link polls. */
    suspend fun sync(context: Context, activeOrbitId: String? = null) {
        val app = context.applicationContext
        val active = activeOrbitId ?: OrbitPreferences.getInstance(app).current().active?.takeUnless { it.deleting }?.id
        runnerLock.withLock {
            val state = active?.let { load(app, it) }
            if (active != null) publish(active, state)
            if (active == null || state == null) {
                stopLocked(app)
                if (active == null) _status.value = null
                return
            }
            if (runner?.isActive == true && runnerOrbit == active) return
            stopLocked(app)
            runnerOrbit = active
            runner = scope.launch { run(app, active) }
        }
    }

    private suspend fun stopLocked(context: Context) {
        val job = runner ?: return
        runner = null
        runnerOrbit = null
        job.cancel()
        job.join()
        online = false
        AIForegroundService.releaseForOrbitChannels(context)
    }

    private suspend fun run(context: Context, orbitId: String) = kotlinx.coroutines.coroutineScope {
        AppLogger.i(TAG, "WeChat channel started")
        launch { keepAlive(context) }
        launch { poll(context, orbitId) }
        launch { deliverLoop(context, orbitId) }
        launch { replyLoop(context, orbitId) }
        inboxWake.trySend(Unit)
        outboxWake.trySend(Unit)
    }

    /** Holds the foreground service while linked, retrying while Android refuses to start it. */
    private suspend fun keepAlive(context: Context) {
        try {
            while (true) {
                if (AIForegroundService.holdForOrbitChannels(context)) {
                    delay(10 * 60_000L)
                } else {
                    delay(5 * 60_000L)
                }
            }
        } finally {
            AIForegroundService.releaseForOrbitChannels(context)
        }
    }

    // ---- connecting ----

    private fun safeBase(url: String): String? {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        if (parsed.scheme != "https" || !(host == "qq.com" || host.endsWith(".qq.com"))) return null
        return "https://$host" + (if (parsed.port > 0) ":${parsed.port}" else "")
    }

    /** A WeChat login of the QR flow, step by step, for the connect sheet. */
    class Login(private val context: Context, private val orbitId: String) {
        sealed interface Step {
            data object Loading : Step
            data class Qr(val content: String, val scanned: Boolean) : Step
            data class Code(val wrong: Boolean) : Step
            data object Done : Step
            data class Failed(val reason: Reason) : Step
        }
        enum class Reason { EXPIRED, BLOCKED, ALREADY_BOUND, TIMEOUT, TOO_MANY_CHATS, NETWORK }

        private val _step = MutableStateFlow<Step>(Step.Loading)
        val step: StateFlow<Step> = _step
        private val codes = Channel<String>(Channel.CONFLATED)

        fun submitCode(code: String) { codes.trySend(code.trim()) }

        suspend fun run() {
            try {
                _step.value = runSteps()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "WeChat login failed: ${e.message}")
                _step.value = Step.Failed(if (e is IllegalStateException && e.message == TOO_MANY) Reason.TOO_MANY_CHATS else Reason.NETWORK)
            }
        }

        private suspend fun runSteps(): Step {
            var qr = OrbitWeixinApi.qrCode()
            _step.value = Step.Qr(qr.url, false)
            var base = OrbitWeixinApi.DEFAULT_BASE_URL
            var code: String? = null
            var refreshes = 0
            val deadline = System.currentTimeMillis() + 8 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                val status = OrbitWeixinApi.qrStatus(base, qr.qrcode, code)
                when (status.status) {
                    "scaned" -> { code = null; _step.value = Step.Qr(qr.url, true) }
                    "need_verifycode" -> {
                        _step.value = Step.Code(wrong = code != null)
                        code = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) { codes.receive() }
                            ?: return Step.Failed(Reason.TIMEOUT)
                        _step.value = Step.Qr(qr.url, true)
                        continue
                    }
                    "expired", "verify_code_blocked" -> {
                        if (++refreshes > 3) return Step.Failed(if (status.status == "expired") Reason.EXPIRED else Reason.BLOCKED)
                        qr = OrbitWeixinApi.qrCode()
                        base = OrbitWeixinApi.DEFAULT_BASE_URL
                        code = null
                        _step.value = Step.Qr(qr.url, false)
                    }
                    "binded_redirect" -> return Step.Failed(Reason.ALREADY_BOUND)
                    "scaned_but_redirect" -> safeBase("https://" + status.redirectHost)?.let { base = it }
                    "confirmed" -> {
                        if (status.token.isBlank() || status.userId.isBlank()) return Step.Failed(Reason.NETWORK)
                        // Once WeChat confirmed, closing the sheet must not leave a half-saved link.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            connect(context, orbitId, status.token, safeBase(status.baseUrl) ?: base, status.botId, status.userId)
                        }
                        return Step.Done
                    }
                }
                delay(1_000)
            }
            return Step.Failed(Reason.TIMEOUT)
        }
    }

    private const val TOO_MANY = "too_many_side_chats"

    /** The side chat WeChat talks in: the saved one while it exists, otherwise a new 「微信」 chat. */
    private suspend fun ensureChat(context: Context, orbitId: String, saved: String?): String {
        val orbit = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
            ?: error("Orbit no longer exists")
        if (saved != null && orbit.sideChats.any { it.chatId == saved }) return saved
        if (!orbit.canAddSideChat) throw IllegalStateException(TOO_MANY)
        return OrbitCompanion.createSideChat(context, orbitId, if (english(context)) "WeChat" else "微信")
    }

    private suspend fun latestTimestamp(context: Context, chatId: String): Long =
        ChatHistoryManager.getInstance(context).loadOlderChatMessages(chatId, Long.MAX_VALUE, 1).maxOfOrNull { it.timestamp } ?: 0L

    private suspend fun connect(context: Context, orbitId: String, token: String, base: String, botId: String, userId: String) {
        val app = context.applicationContext
        // Another WeChat user gets a chat of their own: a turn still running in the old one would
        // otherwise send its answer for the first user to the new one.
        val before = load(app, orbitId)
        val chatId = ensureChat(app, orbitId, before?.chatId?.takeIf { before.userId == userId })
        val state = runnerLock.withLock {
            // The old polling stops first, so nothing it still receives can mix into the new link.
            stopLocked(app)
            val previous = load(app, orbitId)
            val sameUser = previous != null && previous.userId == userId
            val sameBot = sameUser && previous?.botId == botId
            // Another WeChat user's waiting messages never reach this link: their replies would go
            // to the new user. Their pictures stay only if they were already handed to the chat.
            if (previous != null && !sameUser) {
                previous.inbox.filter { it.firstTryAt == 0L }.flatMap { it.files }
                    .forEach { runCatching { OrbitUploads.discard(app, it.filePath) } }
            }
            // The same WeChat user keeps what was waiting to go out; for anyone else only what Orbit
            // says from now on goes to WeChat, never the chat's older history.
            val cursor = if (sameUser && previous?.chatId == chatId) previous.cursor
                else latestTimestamp(app, chatId)
            val state = State(
                token = token, baseUrl = base, botId = botId, userId = userId, chatId = chatId, cursor = cursor,
                buf = if (sameBot) previous?.buf.orEmpty() else "",
                contextToken = if (sameBot) previous?.contextToken.orEmpty() else "",
                inbox = if (sameUser) previous?.inbox.orEmpty() else emptyList(),
                outbox = if (sameUser) previous?.outbox.orEmpty().map { it.copy(tries = 0, parked = false) } else emptyList(),
            )
            withContext(Dispatchers.IO) {
                lock.withLock {
                    store(app, orbitId).write(encode(state))
                    cache = orbitId to state
                }
            }
            state
        }
        publish(orbitId, state)
        sync(app)
    }

    /** Unlinks WeChat. The side chat and its history stay; nothing more is sent or received. */
    suspend fun disconnect(context: Context, orbitId: String) {
        val app = context.applicationContext
        val state = runnerLock.withLock {
            if (runnerOrbit == orbitId) stopLocked(app)
            // Read after polling stopped, so this is the final word on what was still waiting.
            val last = load(app, orbitId)
            withContext(Dispatchers.IO) {
                lock.withLock {
                    store(app, orbitId).delete()
                    cache = orbitId to null
                }
            }
            last
        }
        // Only files of messages never handed to the chat go; anything handed over may already be
        // part of the chat's history and its Library.
        state?.inbox?.filter { it.firstTryAt == 0L }?.flatMap { it.files }
            ?.forEach { runCatching { OrbitUploads.discard(app, it.filePath) } }
        publish(orbitId, null)
        if (state != null) scope.launch { OrbitWeixinApi.notify(state.baseUrl, state.token, start = false) }
    }

    /** The WeChat side chat was deleted; the next message from WeChat opens a new one. */
    suspend fun onSideChatDeleted(context: Context, orbitId: String, chatId: String) {
        update(context.applicationContext, orbitId) { if (it.chatId == chatId) it.copy(chatId = null, inbox = it.inbox) else null }
    }

    /**
     * The side chat WeChat is linked to, or null when WeChat is not connected. It is found by chat
     * id, so renaming the chat keeps the link; it cannot be deleted until WeChat is disconnected.
     */
    suspend fun boundChatId(context: Context, orbitId: String): String? =
        load(context.applicationContext, orbitId)?.chatId

    /** Thrown when deleting the side chat WeChat is linked to. */
    class ChatBoundException : IllegalStateException("This side chat is linked to WeChat; disconnect WeChat in settings first")

    /** The Orbit was deleted (its stores are already gone): stop and drop what is held in memory. */
    suspend fun forget(context: Context, orbitId: String) {
        val app = context.applicationContext
        runnerLock.withLock { if (runnerOrbit == orbitId) stopLocked(app) }
        withContext(Dispatchers.IO) { lock.withLock { if (cache?.first == orbitId) cache = null } }
        if (_status.value?.orbitId == orbitId) _status.value = null
    }

    /** Reads [orbitId]'s link into [status], for its settings page. */
    suspend fun refreshStatus(context: Context, orbitId: String) {
        publish(orbitId, load(context.applicationContext, orbitId))
    }

    // ---- receiving ----

    private suspend fun poll(context: Context, orbitId: String) {
        var timeout = OrbitWeixinApi.LONG_POLL_MS
        var failures = 0
        load(context, orbitId)?.let { OrbitWeixinApi.notify(it.baseUrl, it.token, start = true) }
        while (true) {
            val s = load(context, orbitId) ?: return
            if (s.staleSince > 0L) {
                val left = s.staleSince + STALE_PAUSE_MS - System.currentTimeMillis()
                if (left > 0) { delay(minOf(left, 60_000L)); continue }
            }
            // Too much still waiting to reach the chat: hold off, so the WeChat cursor only moves
            // past messages that are safely kept.
            if (s.inbox.size >= MAX_INBOX) { delay(5_000L); continue }
            try {
                val updates = OrbitWeixinApi.updates(s.baseUrl, s.token, s.buf, timeout)
                updates.nextTimeoutMs?.let { timeout = it.coerceIn(5_000L, 60_000L) }
                failures = 0
                online = true
                val mine = updates.messages.filter {
                    it.optInt("message_type") == OrbitWeixinApi.MESSAGE_FROM_USER && it.optString("from_user_id") == s.userId
                }
                val english = english(context)
                val items = mine.mapNotNull { runCatching { inbound(context, orbitId, it, english) }.getOrNull() }
                val context0 = mine.lastOrNull { it.optString("context_token").isNotBlank() }?.optString("context_token")
                update(context, orbitId) { st ->
                    st.copy(
                        buf = updates.buf ?: st.buf,
                        contextToken = context0 ?: st.contextToken,
                        inbox = st.inbox + items.filter { new -> st.inbox.none { it.id == new.id } },
                        lastInboundAt = if (items.isNotEmpty()) System.currentTimeMillis() else st.lastInboundAt,
                        staleSince = 0L,
                        lastError = if (st.lastError.startsWith(NET)) "" else st.lastError,
                        // The user just wrote, so WeChat lets the bot answer again: refused replies get fresh tries.
                        outbox = if (context0 != null) st.outbox.map { it.copy(tries = 0, parked = false) } else st.outbox,
                    )
                } ?: return
                if (items.isNotEmpty()) inboxWake.trySend(Unit)
                // A fresh context token can carry replies that could not go out before.
                if (context0 != null) outboxWake.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OrbitWeixinApi.ApiException) {
                online = false
                if (e.code == OrbitWeixinApi.STALE_TOKEN) {
                    // Decided under the state lock, so a reply failing at the same moment cannot tell it twice.
                    var first = false
                    update(context, orbitId) {
                        first = it.staleSince == 0L
                        it.copy(staleSince = System.currentTimeMillis(), lastError = EXPIRED)
                    }
                    if (first) tellExpired(context, orbitId)
                    continue
                }
                failures++
                update(context, orbitId) { it.copy(lastError = NET + (e.message ?: "")) }
                delay(if (failures >= 3) 30_000L else 2_000L)
            } catch (e: Exception) {
                online = false
                failures++
                update(context, orbitId) { it.copy(lastError = NET + e.javaClass.simpleName) }
                delay(if (failures >= 3) 30_000L else 2_000L)
            }
        }
    }

    private const val NET = "network: "
    const val EXPIRED = "login_expired"

    /** Hidden note to Orbit in its main chat; the user does not see it. Waits for a quiet chat in the background. */
    private fun tellOrbit(context: Context, orbitId: String, chatId: String?, zh: String, en: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val orbit = OrbitPreferences.getInstance(app).current().orbits
                    .firstOrNull { it.id == orbitId && !it.deleting && it.chatId.isNotBlank() } ?: return@launch
                val target = chatId?.takeIf { it in orbit.talkChatIds } ?: orbit.chatId
                val note = if (english(app)) en else zh
                val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    if (OrbitWake.tell(app, orbit, target, note, left, left, announce = false) != OrbitWake.Told.BUSY) break
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not tell Orbit about WeChat: ${e.message}")
            }
        }
    }

    private fun tellExpired(context: Context, orbitId: String) = tellOrbit(context, orbitId, null,
        "[orbit-weixin] 微信连接过期了\n这是 App 发给你的说明，不是用户说的，用户也看不到。微信那边的登录过期了：用户在微信里发的消息你收不到，" +
            "你的回复也发不过去，要重新扫码才能恢复。找合适的时机用一句话告诉用户，并调用 orbit_channel action=open_page 放一张卡片，" +
            "让他点一下去重新连接。他以前说过不用提醒这个的话，就调用 orbit_nothing_to_do 保持安静。",
        "[orbit-weixin] The WeChat link expired\nThis is a note from the app, not from the user; they cannot see it. The WeChat " +
            "login expired: you no longer get what the user writes there, and your replies cannot reach them until they scan " +
            "the code again. When it fits, tell them in one line and call orbit_channel action=open_page to put a card in the " +
            "chat that takes them to reconnect. If they said before they don't want reminders about this, call orbit_nothing_to_do to stay quiet.")

    private fun tellUndelivered(context: Context, orbitId: String, item: Outbound) {
        val what = when (item.kind) {
            "text" -> item.text.replace(Regex("\\s+"), " ").take(120)
            else -> item.name.take(80)
        }
        tellOrbit(context, orbitId, null,
            "[orbit-weixin] 有一条微信回复没发出去\n这是 App 发给你的说明，不是用户说的，用户也看不到。微信多次拒收，已放弃发送：「$what」。" +
                "用户在微信里看不到它，在 App 的聊天里能看到。用户下次在微信里找你时，如果这件事还要紧，就简短再说一次；现在不用提，调用 orbit_nothing_to_do 就好。",
            "[orbit-weixin] A WeChat reply was not delivered\nThis is a note from the app, not from the user; they cannot see it. " +
                "WeChat refused it several times, so it was dropped: \"$what\". The user cannot see it in WeChat, only in the app's " +
                "chat. When they next write to you in WeChat and it still matters, say it again briefly; for now call orbit_nothing_to_do.")
    }

    private fun mimeOf(file: File): String {
        val bytes = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(bytes) } }.getOrDefault(0)
        return when {
            n > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            n > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            n > 11 && String(bytes, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun mimeForName(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** One WeChat message as the chat message it becomes; pictures and files go to the Orbit's Library. */
    private suspend fun inbound(context: Context, orbitId: String, msg: JSONObject, english: Boolean): Inbound? {
        val parts = mutableListOf<String>()
        val files = mutableListOf<AttachmentInfo>()
        val items = msg.optJSONArray("item_list") ?: JSONArray()
        suspend fun keep(name: String, mime: String, temp: File) {
            val file = withContext(Dispatchers.IO) { temp.inputStream().use { OrbitUploads.save(context, orbitId, name, it) } } ?: return
            files += AttachmentInfo(file.absolutePath, file.name, mime, file.length())
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            item.optJSONObject("ref_msg")?.let { ref ->
                val quoted = ref.optJSONObject("message_item")?.optJSONObject("text_item")?.optString("text")
                    ?.takeIf { it.isNotBlank() } ?: ref.optString("title")
                if (quoted.isNotBlank()) parts += (if (english) "> Quoting: " else "> 引用：") + quoted.take(300).replace("\n", " ")
            }
            val stamp = System.currentTimeMillis()
            when (item.optInt("type")) {
                OrbitWeixinApi.ITEM_TEXT -> item.optJSONObject("text_item")?.optString("text")?.takeIf { it.isNotBlank() }?.let { parts += it }
                OrbitWeixinApi.ITEM_VOICE -> {
                    val said = item.optJSONObject("voice_item")?.optString("text").orEmpty()
                    parts += if (said.isNotBlank()) (if (english) "(voice) " else "（语音）") + said
                    else if (english) "[A voice message that could not be transcribed]" else "[一条语音，没能转成文字]"
                }
                OrbitWeixinApi.ITEM_IMAGE, OrbitWeixinApi.ITEM_FILE, OrbitWeixinApi.ITEM_VIDEO -> {
                    val type = item.optInt("type")
                    val holder = item.optJSONObject(when (type) {
                        OrbitWeixinApi.ITEM_IMAGE -> "image_item"
                        OrbitWeixinApi.ITEM_FILE -> "file_item"
                        else -> "video_item"
                    }) ?: continue
                    val media = holder.optJSONObject("media") ?: continue
                    val label = when (type) {
                        OrbitWeixinApi.ITEM_IMAGE -> if (english) "picture" else "图片"
                        OrbitWeixinApi.ITEM_FILE -> if (english) "file" else "文件"
                        else -> if (english) "video" else "视频"
                    }
                    val temp = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "weixin-in").apply { mkdirs() }.let { File.createTempFile("in-", ".part", it) }
                    }
                    try {
                        OrbitWeixinApi.download(holder, media, if (type == OrbitWeixinApi.ITEM_IMAGE) MAX_IN_IMAGE else MAX_IN_FILE, temp)
                        when (type) {
                            OrbitWeixinApi.ITEM_IMAGE -> {
                                val mime = withContext(Dispatchers.IO) { mimeOf(temp) }
                                keep("weixin-$stamp.${mime.substringAfter('/').replace("jpeg", "jpg")}", mime, temp)
                            }
                            OrbitWeixinApi.ITEM_FILE -> {
                                val name = holder.optString("file_name").ifBlank { "weixin-$stamp" }
                                keep(name, mimeForName(name), temp)
                            }
                            else -> keep("weixin-$stamp.mp4", "video/mp4", temp)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "WeChat $label not downloaded: ${e.message}")
                        parts += if (english) "[A $label that could not be downloaded]" else "[一个没能下载的$label]"
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { temp.delete() }
                    }
                }
            }
        }
        val words = parts.joinToString("\n").trim()
        if (words.isEmpty() && files.isEmpty()) return null
        val id = msg.optString("message_id").ifBlank { msg.optString("seq") }.ifBlank { System.nanoTime().toString() }
        return Inbound(id, prefix(english) + words.ifEmpty { if (english) "(sent ${files.size} file(s))" else "（发来${files.size}个文件）" }, files)
    }

    private fun cores(context: Context) = ChatRuntimeHolder.getInstance(context).let {
        listOf(it.getCore(ChatRuntimeSlot.MAIN), it.getCore(ChatRuntimeSlot.FLOATING))
    }

    private fun quietNow(context: Context, chatId: String) = cores(context).all {
        chatId !in it.activeStreamingChatIds.value && !it.isSummarizing.value && !it.isSendTriggeredSummarizing.value
    }

    private fun quietFlow(context: Context, chatId: String) = cores(context).map { c ->
        combine(c.activeStreamingChatIds, c.isSummarizing, c.isSendTriggeredSummarizing) { active, a, b -> chatId !in active && !a && !b }
    }.let { (a, b) -> combine(a, b) { x, y -> x && y } }

    private suspend fun deliverLoop(context: Context, orbitId: String) {
        // The last look into the chat for a handed-over message, so waiting never turns into scanning.
        var checkedId: String? = null
        var checkedAt = 0L
        while (true) {
            val s = load(context, orbitId) ?: return
            val next = s.inbox.firstOrNull()
            if (next == null) { inboxWake.receive(); continue }
            val chatId = try {
                ensureChat(context, orbitId, s.chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(context, orbitId) { it.copy(lastError = if (e.message == TOO_MANY) TOO_MANY else "chat: ${e.message}") }
                delay(60_000L)
                continue
            }
            if (chatId != s.chatId) {
                val cursor = latestTimestamp(context, chatId)
                update(context, orbitId) { it.copy(chatId = chatId, cursor = cursor) }
            }
            if (next.firstTryAt > 0L) {
                // Handed over before (maybe before a restart): only the chat itself says whether it
                // arrived. Once it has, it leaves at once, so the next one can still join a running turn.
                val now = System.currentTimeMillis()
                val quiet = quietNow(context, chatId)
                val retryAt = next.lastTryAt + retryDelay(next.tries)
                // Looked at first, every few seconds while the chat works, and once more right before
                // handing it over again; never in between.
                val due = checkedId != next.id || (!quiet && now - checkedAt >= 3_000L) || (quiet && now >= retryAt)
                if (due) {
                    checkedId = next.id
                    checkedAt = now
                    if (landed(context, chatId, next)) { dequeue(context, orbitId, next.id); continue }
                }
                // While the chat works it may still be on its way: hand it over again only once quiet.
                if (!quiet) { withTimeoutOrNull(3_000L) { inboxWake.receive() }; continue }
                if (now < retryAt) { withTimeoutOrNull(minOf(retryAt - now, 30_000L)) { inboxWake.receive() }; continue }
            }
            if (!deliver(context, orbitId, chatId, next)) withTimeoutOrNull(1_500L) { inboxWake.receive() }
        }
    }

    /**
     * Saved before handing over, so a restart in between checks the chat instead of sending twice:
     * when, and the exact row this try will be saved as ([stamp] for a new turn, [steer] for words
     * taken into a running one).
     */
    private suspend fun markTry(context: Context, orbitId: String, item: Inbound, stamp: Long?, steer: String?) {
        val now = System.currentTimeMillis()
        update(context, orbitId) { st ->
            st.copy(inbox = st.inbox.map {
                if (it.id != item.id) it
                else it.copy(
                    firstTryAt = if (it.firstTryAt > 0L) it.firstTryAt else now, lastTryAt = now,
                    tries = if (it.firstTryAt > 0L) it.tries + 1 else it.tries,
                    stamps = (it.stamps + listOfNotNull(stamp)).takeLast(20),
                    steered = (it.steered + listOfNotNull(steer)).takeLast(20),
                )
            })
        }
    }

    /** The try was not taken at all, so nothing was handed over: back to how it was. */
    private suspend fun undoTry(context: Context, orbitId: String, item: Inbound) {
        update(context, orbitId) { st ->
            st.copy(inbox = st.inbox.map {
                if (it.id == item.id) it.copy(firstTryAt = item.firstTryAt, lastTryAt = item.lastTryAt, tries = item.tries,
                    stamps = item.stamps, steered = item.steered) else it
            })
        }
    }

    private suspend fun dequeue(context: Context, orbitId: String, id: String) {
        update(context, orbitId) { st -> st.copy(inbox = st.inbox.filterNot { it.id == id }) }
    }

    /** Waits a little longer each time a message handed to the chat did not show up in it. */
    private fun retryDelay(tries: Int): Long = minOf(3_000L shl tries.coerceAtMost(8), 10 * 60_000L)

    /**
     * The message is saved in the chat: the user row of one of its tries, found by the timestamp a
     * new turn was told to save it under, or by the exact words taken into a running turn (those are
     * stored as given). Something the user types in the app meanwhile never counts.
     */
    private suspend fun landed(context: Context, chatId: String, item: Inbound): Boolean {
        if (item.stamps.isEmpty() && item.steered.isEmpty()) return false
        // A stamp is taken a moment before the try is marked, so it can be the earlier of the two.
        var after = minOf(item.firstTryAt, item.stamps.minOrNull() ?: Long.MAX_VALUE) - 1
        val history = ChatHistoryManager.getInstance(context)
        while (true) {
            val rows = history.loadChatMessagesAscAfter(chatId, after, 200)
            if (rows.isEmpty()) return false
            if (rows.any { it.sender == "user" && (it.timestamp in item.stamps || it.content in item.steered) }) return true
            if (rows.size < 200) return false
            after = rows.maxOf { it.timestamp }
        }
    }

    /**
     * Puts one WeChat message into the chat: a new turn when the chat is quiet, or into Orbit's
     * current turn while it is still working, the way a message typed then would go.
     * True when it was handed over; whether it is saved is checked in the chat afterwards.
     */
    private suspend fun deliver(context: Context, orbitId: String, chatId: String, item: Inbound): Boolean {
        val core = ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
        if (quietNow(context, chatId)) {
            val stamp = com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator.next()
            markTry(context, orbitId, item, stamp = stamp, steer = null)
            val taken = withContext(Dispatchers.Main.immediate) {
                core.trySendUserMessageToExistingChat(
                    chatId = chatId, messageText = item.text, attachments = item.files,
                    turnOptions = ChatTurnOptions(useComposerReply = false, userMessageTimestamp = stamp),
                )
            }
            if (!taken) undoTry(context, orbitId, item)
            return taken
        }
        val delegate = core.getMessageProcessingDelegate()
        val turn = delegate.steeringTurnId(chatId) ?: return false
        val prepared = try {
            delegate.prepareSteeringInput(chatId, turn, item.text, item.files) ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val landed = CompletableDeferred<Boolean>()
        markTry(context, orbitId, item, stamp = null, steer = prepared)
        val accepted = delegate.trySteerMessage(chatId, turn, TurnInputInbox.Input(
            text = prepared, consumed = { landed.complete(true) }, returned = { landed.complete(false) },
        ))
        if (!accepted) { undoTry(context, orbitId, item); return false }
        // Taken or handed back, the chat is checked once it settles: a returned message that did
        // not land is handed over again then.
        withTimeoutOrNull(30 * 60_000L) { landed.await() }
        return true
    }

    // ---- replying ----

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun replyLoop(context: Context, orbitId: String) = kotlinx.coroutines.coroutineScope {
        // Every time the WeChat chat settles, what Orbit said there since the last look goes out.
        launch {
            chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(false) else quietFlow(context, id) }
                .distinctUntilChanged()
                .collectLatest { quiet -> if (quiet) { delay(1_500L); outboxWake.trySend(Unit) } }
        }
        var waitMs = 60_000L
        while (true) {
            withTimeoutOrNull(waitMs) { outboxWake.receive() }
            val s = load(context, orbitId) ?: return@coroutineScope
            collectReplies(context, orbitId, s)
            waitMs = sendWaiting(context, orbitId)
        }
    }

    /** Moves Orbit's new words in the WeChat chat into the outbox, once the chat is quiet. */
    private suspend fun collectReplies(context: Context, orbitId: String, s: State) {
        val chatId = s.chatId ?: return
        // A full outbox stops here: the cursor stays, and the rest is picked up once replies go out.
        if (s.outbox.size >= MAX_OUTBOX) return
        if (!quietNow(context, chatId)) return
        val rows = ChatHistoryManager.getInstance(context).loadChatMessagesAscAfter(chatId, s.cursor, 300)
        if (rows.isEmpty() || !quietNow(context, chatId)) return
        val newCursor = rows.maxOf { it.timestamp }
        val out = outboundOf(context, orbitId, rows)
        update(context, orbitId) { st ->
            if (st.cursor != s.cursor || st.chatId != chatId) null
            else st.copy(cursor = newCursor, outbox = st.outbox + out)
        }
        if (rows.size == 300) outboxWake.trySend(Unit)
    }

    private val optionsFence = Regex("```$ORBIT_OPTIONS_FENCE\\n([a-z0-9]{10})\\n```")
    private val imageLink = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val link = Regex("(?<!!)\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

    /** Orbit's words as plain WeChat text: reply options as a numbered list, links spelled out. */
    private fun plain(text: String, english: Boolean): String {
        var t = text
        if (t.contains(ORBIT_OPTIONS_FENCE)) {
            t = t.replace(optionsFence) { m ->
                val set = OrbitOptions.sets.value[m.groupValues[1]] ?: return@replace ""
                (if (english) "You can reply:\n" else "可以直接回复：\n") +
                    set.options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("\n")
            }
        }
        t = t.replace(imageLink, "").replace(link) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun chunks(text: String): List<String> {
        if (text.length <= TEXT_LIMIT) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > TEXT_LIMIT) {
            val cut = rest.lastIndexOf('\n', TEXT_LIMIT).takeIf { it > TEXT_LIMIT / 2 } ?: TEXT_LIMIT
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }

    private fun outboundOf(context: Context, orbitId: String, rows: List<ChatMessage>): List<Outbound> {
        val english = english(context)
        OrbitOptions.load(context)
        val out = mutableListOf<Outbound>()
        fun text(t: String, key: String) = chunks(t).forEachIndexed { i, c -> out += Outbound("$key-$i", "text", c, "", "") }
        val inApp = if (english) " (open Operit to see it)" else "（请在 Operit 里查看）"
        OrbitSpeech.history(rows).filter { it.sender == "ai" && !OrbitSpeech.hidden(it) }.forEach { row ->
            OrbitSpeech.pieceList(row.content, row.timestamp, english).forEachIndexed { n, piece ->
                val key = "${row.timestamp}-$n"
                if (piece.summary == null) {
                    plain(piece.content, english).takeIf { it.isNotEmpty() }?.let { text(it, key) }
                    return@forEachIndexed
                }
                val card = piece.content.removePrefix("```$ORBIT_CARD_FENCE\n").removeSuffix("\n```")
                    .takeIf { piece.content.startsWith("```$ORBIT_CARD_FENCE\n") }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                val kind = card?.optString("kind")
                val path = card?.optString("path").orEmpty()
                val file = if ((kind == OrbitChatCards.IMAGE || kind == OrbitChatCards.FILE) &&
                    card?.optString("orbit_id") == orbitId && path.startsWith("files/")) {
                    runCatching { OrbitFiles.of(context, orbitId).fileFor(path).takeIf { it.isFile } }.getOrNull()
                } else null
                if (file != null) {
                    val name = card?.optString("name").orEmpty().ifBlank { file.name }
                    out += Outbound(key, if (kind == OrbitChatCards.IMAGE) "image" else "file", "", file.absolutePath, name)
                } else {
                    text(piece.summary + inApp, key)
                }
            }
        }
        return out
    }

    /** Failures in a row that never reached a WeChat answer; only paces retries. */
    @Volatile private var networkFailures = 0

    /** WeChat itself answered no (not the network, not an expired login). */
    private fun refused(e: Exception): Boolean {
        val code = (e as? OrbitWeixinApi.ApiException)?.code ?: return false
        return code != OrbitWeixinApi.STALE_TOKEN && code != 429 && code !in 500..599
    }

    /** Sends what waits, oldest first; gives how long to wait before trying again. */
    private suspend fun sendWaiting(context: Context, orbitId: String): Long {
        while (true) {
            val s = load(context, orbitId) ?: return 60_000L
            val item = s.outbox.firstOrNull { !it.parked } ?: return 60_000L
            if (s.staleSince > 0L) return 5 * 60_000L
            try {
                sendOne(context, s, item)
                networkFailures = 0
                update(context, orbitId) { st ->
                    st.copy(outbox = st.outbox.filterNot { it.id == item.id }, lastSentAt = System.currentTimeMillis(),
                        lastError = if (st.lastError.startsWith(SEND)) "" else st.lastError)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stale = e is OrbitWeixinApi.ApiException && e.code == OrbitWeixinApi.STALE_TOKEN
                val refused = refused(e)
                val tries = if (refused) item.tries + 1 else item.tries
                val setAside = refused && tries >= MAX_SEND_TRIES
                val giveUp = setAside && item.parks + 1 >= MAX_PARKS
                var firstStale = false
                update(context, orbitId) { st ->
                    firstStale = stale && st.staleSince == 0L
                    st.copy(
                        outbox = if (giveUp) st.outbox.filterNot { it.id == item.id }
                            else st.outbox.map {
                                if (it.id != item.id) it
                                else if (setAside) it.copy(tries = 0, parked = true, parks = it.parks + 1)
                                else it.copy(tries = tries)
                            },
                        lastError = if (stale) EXPIRED else SEND + (e.message ?: e.javaClass.simpleName),
                        staleSince = if (stale) System.currentTimeMillis() else st.staleSince,
                    )
                }
                AppLogger.w(TAG, "WeChat reply not sent (refused $tries): ${e.message}")
                if (giveUp) tellUndelivered(context, orbitId, item)
                else if (firstStale) tellExpired(context, orbitId)
                if (setAside) continue
                if (stale) return 5 * 60_000L
                if (refused) return minOf(60_000L shl (tries - 1).coerceAtMost(5), 30 * 60_000L)
                // The network: keep it and slow down, however long that takes.
                val n = ++networkFailures
                return minOf(30_000L shl (n - 1).coerceAtMost(6), 30 * 60_000L)
            }
        }
    }

    private const val SEND = "send: "

    private suspend fun sendOne(context: Context, s: State, item: Outbound) {
        val api = OrbitWeixinApi
        when (item.kind) {
            "text" -> api.send(s.baseUrl, s.token, s.userId, s.contextToken, api.textItem(item.text), item.clientId)
            else -> {
                val file = File(item.path)
                val english = english(context)
                val missingOrLarge = withContext(Dispatchers.IO) { !file.isFile || file.length() > MAX_OUT_BYTES }
                if (missingOrLarge) {
                    val note = if (english) "[${item.name}: too large to send here; open Operit to see it]"
                        else "[${item.name}：文件太大，请在 Operit 里查看]"
                    api.send(s.baseUrl, s.token, s.userId, s.contextToken, api.textItem(note), item.clientId)
                    return
                }
                val uploaded = api.uploadItem(s.baseUrl, s.token, s.userId, file,
                    if (item.kind == "image") OrbitWeixinApi.UPLOAD_IMAGE else OrbitWeixinApi.UPLOAD_FILE, item.name)
                api.send(s.baseUrl, s.token, s.userId, s.contextToken, uploaded, item.clientId)
            }
        }
    }

    /** For Orbit's tool: the link as Orbit should understand it. */
    suspend fun describe(context: Context, orbitId: String, callerChatId: String?): JSONObject {
        val s = load(context, orbitId) ?: return JSONObject().put("channel", "weixin").put("connected", false)
            .put("note", "WeChat is not linked. The user links it in Orbit settings → Message channels → WeChat by scanning a QR code; Orbit cannot link it.")
        return JSONObject()
            .put("channel", "weixin")
            .put("connected", true)
            .put("chat_id", s.chatId ?: JSONObject.NULL)
            .put("is_current_chat", s.chatId != null && s.chatId == callerChatId)
            .put("online", online && s.staleSince == 0L)
            .put("login_expired", s.staleSince > 0L)
            .put("waiting_to_send", s.outbox.size)
            .put("waiting_to_deliver", s.inbox.size)
            .put("last_message_from_user", if (s.lastInboundAt > 0) java.time.Instant.ofEpochMilli(s.lastInboundAt).toString() else JSONObject.NULL)
            .put("last_sent", if (s.lastSentAt > 0) java.time.Instant.ofEpochMilli(s.lastSentAt).toString() else JSONObject.NULL)
            .put("last_error", s.lastError.ifEmpty { JSONObject.NULL })
    }
}
