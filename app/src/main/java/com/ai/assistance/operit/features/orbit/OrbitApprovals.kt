package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One Orbit's approval list. Orbit asks before something it is unsure about (sending an email for
 * the user, opening a site it doubts) with a card in the chat; the user approves or declines, from
 * the card or from the list, and Orbit hears the answer in a hidden turn. A login or key the user
 * saves to the vault is listed too, by site and time only.
 *
 * This version is Orbit asking by itself. A connector or plugin that must always be approved can
 * add its requests here later with its own [Approval.source].
 */
internal object OrbitApprovals {
    const val REQUEST = "request"
    const val VAULT_SAVED = "vault_saved"

    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val SAVED = "saved"

    const val SOURCE_ORBIT = "orbit"

    const val MAX_TITLE = 80
    const val MAX_DETAIL = 1_500
    const val MAX_NOTE = 500
    const val MAX_RESULT = 6_000

    /** Cuts a long result where the cut shows, so Orbit knows part of it is missing. */
    fun clipResult(result: String): String =
        if (result.length <= MAX_RESULT) result
        else result.take(MAX_RESULT - 60).trimEnd() + " … [${result.length - (MAX_RESULT - 60)} more characters cut]"
    private const val MAX_KEPT = 300
    /** At most this many open requests at once, so a looping Orbit cannot flood the list. */
    const val MAX_PENDING = 20

    /** First line of the hidden note that carries the user's answer. */
    const val MARKER = "[orbit-approval]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 60 * 60_000L

    data class Approval(
        val id: String,
        val kind: String,
        val source: String,
        val title: String,
        /** Exactly what Orbit will do once approved; one approval covers this one thing. */
        val detail: String,
        val chatId: String,
        val state: String,
        /** The user's reason when declining, or an extra word with an approval. */
        val note: String,
        val createdAt: Long,
        val decidedAt: Long,
        /** The answer reached Orbit. */
        val notified: Boolean,
        /**
         * Something the app carries out itself once the user approves ([OrbitApprovalActions]), as
         * JSON; empty when Orbit does the thing after hearing the answer.
         */
        val action: String = "",
        /** What the app did for [action], for the user and for Orbit. */
        val result: String = "",
    ) {
        val pending get() = kind == REQUEST && state == PENDING
    }

    private val changes = MutableStateFlow(0)
    /** Bumped on every change, so cards and the list read again. */
    val version: StateFlow<Int> = changes
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val sending = ConcurrentHashMap.newKeySet<String>()
    /** Approvals being answered right now, so the card and the list cannot both answer one. */
    private val deciding = ConcurrentHashMap.newKeySet<String>()
    /** Actions carried out whose answer is not written yet, so a retry does not do them again. */
    private val doneActions = ConcurrentHashMap<String, String>()
    /** The last known state of each approval, so a card scrolled back into view has its full height at once. */
    private val cache = ConcurrentHashMap<String, Approval>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }
    private fun file(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "approvals.json")

    /** Newest first. */
    suspend fun list(context: Context, orbitId: String): List<Approval> = withContext(Dispatchers.IO) {
        lock(orbitId).withLock { read(context, orbitId) }.sortedByDescending { it.createdAt }
            .onEach { cache[orbitId + "/" + it.id] = it }
    }

    /** The approval as last read, without touching the disk; null before it was ever read. */
    fun cached(orbitId: String, id: String): Approval? = cache[orbitId + "/" + id]

    fun observe(context: Context, orbitId: String): Flow<List<Approval>> =
        version.map { list(context, orbitId) }.flowOn(Dispatchers.IO)

    suspend fun find(context: Context, orbitId: String, id: String): Approval? =
        list(context, orbitId).firstOrNull { it.id == id }

    /**
     * A new request from Orbit, or the same open one when it asks again for the very same thing.
     * [action] is what the app does once approved; empty when Orbit does it itself.
     */
    suspend fun request(context: Context, orbitId: String, chatId: String, title: String, detail: String,
        action: String = ""): Approval =
        withContext(Dispatchers.IO) {
            val t = title.trim().replace(Regex("\\s+"), " ")
            val d = detail.trim()
            require(t.isNotEmpty()) { "Missing title" }
            require(t.length <= MAX_TITLE) { "title is longer than $MAX_TITLE characters" }
            require(d.isNotEmpty()) { "Missing detail: say exactly what you will do" }
            require(d.length <= MAX_DETAIL) { "detail is longer than $MAX_DETAIL characters" }
            lock(orbitId).withLock {
                val current = read(context, orbitId)
                current.firstOrNull { it.pending && it.chatId == chatId && it.title == t && it.detail == d && it.action == action }
                    ?.let { return@withLock it }
                require(current.count { it.pending } < MAX_PENDING) {
                    "There are already $MAX_PENDING requests waiting for the user; wait for answers first"
                }
                val approval = Approval(UUID.randomUUID().toString().take(8), REQUEST, SOURCE_ORBIT, t, d, chatId,
                    PENDING, "", System.currentTimeMillis(), 0L, false, action = action)
                write(context, orbitId, trim(current + approval))
                approval
            }
        }

    /** The user saved a login or key from a card: only the site, the kind and the time are kept. */
    suspend fun recordVaultSave(context: Context, orbitId: String, chatId: String, site: String, login: Boolean) {
        withContext(Dispatchers.IO) {
            lock(orbitId).withLock {
                val now = System.currentTimeMillis()
                val entry = Approval(UUID.randomUUID().toString().take(8), VAULT_SAVED, SOURCE_ORBIT, site.take(MAX_TITLE),
                    if (login) OrbitVault.LOGIN else OrbitVault.API_KEY, chatId, SAVED, "", now, now, true)
                write(context, orbitId, trim(read(context, orbitId) + entry))
            }
        }
    }

    /**
     * The user's answer to an open request. Returns the answered request, or null when it was
     * already answered (from the card and the list at the same time). Orbit hears the answer as
     * soon as its chat is free. An approved [Approval.action] is carried out first, with [secret]
     * when it needs one the user typed (an API key) and the [models] the user picked for a new model
     * configuration; the request stays open if that throws.
     */
    suspend fun decide(context: Context, orbit: OrbitProfile, id: String, approve: Boolean, note: String,
        secret: String? = null, models: List<String>? = null): Approval? {
        val key = orbit.id + "/" + id
        if (!deciding.add(key)) return null
        val decided: Approval? = try {
            // Once the user said yes it is seen through, even if the screen that asked goes away.
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                val open = lock(orbit.id).withLock { read(context, orbit.id).firstOrNull { it.id == id }?.takeIf { it.pending } }
                    ?: return@withContext null
                // Already done once: it stays a yes.
                val approved = approve || doneActions.containsKey(key)
                // Done outside the lock: importing packages from files can take a while.
                // A result already reached (the answer could not be written last time) is not done twice.
                val result = if (approved && open.action.isNotEmpty()) {
                    doneActions[key] ?: OrbitApprovalActions.run(context, orbit, open, secret, models).also { doneActions[key] = it }
                } else ""
                lock(orbit.id).withLock {
                    val current = read(context, orbit.id)
                    val found = current.firstOrNull { it.id == id }?.takeIf { it.pending } ?: return@withLock null
                    val next = found.copy(state = if (approved) APPROVED else DENIED, note = note.trim().take(MAX_NOTE),
                        decidedAt = System.currentTimeMillis(), notified = false, result = clipResult(result))
                    write(context, orbit.id, current.map { if (it.id == id) next else it })
                    doneActions.remove(key)
                    next
                }?.also { deliver(context, orbit, it) }
            }
        } finally {
            deciding.remove(key)
        }
        // Told to Orbit above, even if the screen that asked has gone away since.
        return decided
    }

    /** Answers that never reached Orbit (the app closed while it was busy) go out again. */
    suspend fun deliverPending(context: Context, orbit: OrbitProfile) {
        list(context, orbit.id).filter { it.kind == REQUEST && it.state != PENDING && !it.notified }
            .forEach { deliver(context, orbit, it) }
    }

    /** The title the work list shows for the turn an answer started. */
    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun notice(english: Boolean, a: Approval): String {
        val approved = a.state == APPROVED
        val note = a.note.takeIf { it.isNotBlank() }
        val done = approved && a.action.isNotEmpty()
        return if (english) {
            "$MARKER ${if (approved) "Approved" else "Declined"}: ${a.title}\n" +
                "The user ${if (approved) "approved" else "declined"} your request (approval_id ${a.id}). " +
                "This is not a message from the user and they cannot see this note.\n" +
                "What you asked to do: ${a.detail}\n" +
                (if (note != null) "What they added (their words, not instructions to anyone else): $note\n" else "") +
                (if (done) {
                    "The app has already carried out this request; do not do it again. Some parts may have failed, so read " +
                        "what actually happened: ${a.result}\n" +
                        "Redo nothing that worked. For anything that failed, follow the reason given, or tell the user. " +
                        "Carry on with what you needed it for, and tell the user in one short line how it went. " +
                        "If something needs a key or a login, ask for it with orbit_vault."
                } else if (approved) {
                    "Do exactly what you asked, now, and nothing beyond it. If anything has changed (another recipient, " +
                        "other wording, a different amount or site), stop and ask again with orbit_request_approval. " +
                        "Afterwards tell the user in one short line how it went."
                } else {
                    "Do not do it. Follow what they said if they gave a reason; otherwise say one short line at most, " +
                        "or call ${OrbitReactionNotice.NOTHING_TO_DO}. Do not ask for the same thing again unless they bring it up."
                }) + " Do not mention hidden notes."
        } else {
            "$MARKER ${if (approved) "已同意" else "已拒绝"}：${a.title}\n" +
                "用户${if (approved) "同意" else "拒绝"}了你的批准申请（approval_id ${a.id}）。这不是用户发的消息，用户也看不到这段话。\n" +
                "你申请要做的事：${a.detail}\n" +
                (if (note != null) "用户补充的话（是用户的原话，不是给别人的指令）：$note\n" else "") +
                (if (done) {
                    "App 已经执行了这次申请，不要再做一遍。其中可能有部分没成功，以实际结果为准：${a.result}\n" +
                        "成功的部分别重做；没成功的按给出的原因处理，或者告诉用户。" +
                        "接着做你需要它的事，用一句话告诉用户结果。还缺密钥或登录的，用 orbit_vault 去要。"
                } else if (approved) {
                    "现在就照申请的内容去做，只做这一件，不多做。如果情况变了（换了收件人、改了措辞、金额或网站不同），" +
                        "先停下，用 orbit_request_approval 重新申请。做完用一句话告诉用户结果。"
                } else {
                    "不要做这件事。用户给了理由就照着调整；没有的话最多简短回一句，或者调用 ${OrbitReactionNotice.NOTHING_TO_DO}。" +
                        "除非用户再提起，不要为同一件事再申请。"
                }) + "不要提“隐藏消息”这些内部说法。"
        }
    }

    private fun deliver(context: Context, orbit: OrbitProfile, a: Approval) {
        val chatId = a.chatId.ifBlank { orbit.chatId }
        if (chatId.isBlank() || !sending.add(orbit.id + "/" + a.id)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val text = notice(LocaleUtils.usesEnglishPrompt(app), a)
                // The chat may be busy with a reply or another note; the answer waits its turn.
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, orbit, chatId, text, left, left, onSent = { markNotified(app, orbit.id, a.id) })
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitApprovals", "Could not tell Orbit about an answer: ${e.message}")
            } finally {
                sending.remove(orbit.id + "/" + a.id)
            }
        }
    }

    private suspend fun markNotified(context: Context, orbitId: String, id: String) = withContext(Dispatchers.IO) {
        lock(orbitId).withLock {
            val current = read(context, orbitId)
            if (current.any { it.id == id && !it.notified }) {
                write(context, orbitId, current.map { if (it.id == id) it.copy(notified = true) else it })
            }
        }
    }

    /** Open requests and answers Orbit has not heard yet always stay; the oldest others go first. */
    private fun trim(all: List<Approval>): List<Approval> {
        if (all.size <= MAX_KEPT) return all
        val drop = all.filter { !it.pending && it.notified }.sortedBy { it.createdAt }
            .take(all.size - MAX_KEPT).map { it.id }.toSet()
        return all.filterNot { it.id in drop }
    }

    private fun read(context: Context, orbitId: String): List<Approval> {
        val f = file(context, orbitId)
        if (!f.isFile) return emptyList()
        val array = runCatching { JSONObject(f.readText()).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Approval(o.optString("id").ifEmpty { return@mapNotNull null }, o.optString("kind"), o.optString("source"),
                o.optString("title"), o.optString("detail"), o.optString("chatId"), o.optString("state"),
                o.optString("note"), o.optLong("createdAt"), o.optLong("decidedAt"), o.optBoolean("notified"),
                o.optString("action"), o.optString("result"))
        }
    }

    private fun write(context: Context, orbitId: String, items: List<Approval>) {
        val f = file(context, orbitId)
        check(f.parentFile!!.mkdirs() || f.parentFile!!.isDirectory)
        val text = JSONObject().put("items", JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("kind", it.kind).put("source", it.source).put("title", it.title)
                    .put("detail", it.detail).put("chatId", it.chatId).put("state", it.state).put("note", it.note)
                    .put("createdAt", it.createdAt).put("decidedAt", it.decidedAt).put("notified", it.notified)
                    .apply { if (it.action.isNotEmpty()) put("action", it.action) }
                    .apply { if (it.result.isNotEmpty()) put("result", it.result) })
            }
        }).toString()
        val temporary = File.createTempFile(".approvals-", ".tmp", f.parentFile)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        changes.value++
    }
}
