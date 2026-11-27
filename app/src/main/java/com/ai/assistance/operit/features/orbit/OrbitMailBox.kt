package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.util.Base64
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.IMAPResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import javax.net.SocketFactory

/**
 * Gmail and Outlook behind one set of mail actions, over IMAP and SMTP with XOAUTH2. Connections go
 * straight to Google's and Microsoft's servers; each action connects, works and closes on a worker
 * thread of its own, and cancelling it closes its sockets at once. A message is named by an opaque
 * id (account, folder, its UIDVALIDITY and the message UID), so the model never has to know which
 * service it is talking to.
 */
internal object OrbitMailBox {
    private const val MAX_LIST = 50
    private const val MAX_RECIPIENTS = 50
    private const val MAX_IDS = 50
    private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    /** At most this much of a text part is downloaded (as sent) and kept (decoded). */
    private const val MAX_RAW_BYTES = 1024 * 1024
    private const val MAX_TEXT_BYTES = 512 * 1024
    /** The original quoted under a reply; also what its send card shows. */
    const val QUOTE_CHARS = 1500
    /** Searches run on the server over this many messages at a time, newest first, and stop after [MAX_SCAN]. */
    private const val WINDOW = 5000
    private const val MAX_SCAN = 25_000
    private val ROLES = linkedMapOf(
        "\\Sent" to "sent", "\\Drafts" to "drafts", "\\Archive" to "archive", "\\All" to "all",
        "\\Trash" to "trash", "\\Junk" to "spam",
    )
    /** Common names, for a server that does not mark its folders' roles. */
    private val ROLE_NAMES = mapOf(
        "sent" to listOf("Sent Items", "Sent", "[Gmail]/Sent Mail"), "drafts" to listOf("Drafts", "[Gmail]/Drafts"),
        "archive" to listOf("Archive"), "all" to listOf("[Gmail]/All Mail"), "trash" to listOf("Deleted Items", "Trash", "[Gmail]/Trash"),
        "spam" to listOf("Junk Email", "Junk", "[Gmail]/Spam"),
    )
    private val ROLE_WORDS = ROLES.values.toSet() + "inbox"

    /** A few mail actions at a time; more wait their turn (and a cancelled one never starts). */
    private val threads = Executors.newFixedThreadPool(4) { r -> Thread(r, "orbit-mail").apply { isDaemon = true } }

    /** The sockets one action opened, so cancelling it can close them and end any blocked read at once. */
    private class Sockets {
        private val open = CopyOnWriteArrayList<Socket>()
        @Volatile private var closed = false

        fun <S : Socket> track(socket: S): S {
            open += socket
            if (closed) runCatching { socket.close() }
            return socket
        }

        fun closeAll() {
            closed = true
            open.forEach { runCatching { it.close() } }
        }

        /**
         * The plain sockets under every connection, each one tracked. JavaMail itself lays TLS over
         * them (IMAP at once, SMTP after STARTTLS) and checks the certificate and host name; closing
         * the plain socket ends a blocked read on the TLS one too.
         */
        val plain: SocketFactory = object : SocketFactory() {
            private val base = SocketFactory.getDefault()
            override fun createSocket(): Socket = track(base.createSocket())
            override fun createSocket(host: String, port: Int): Socket = track(base.createSocket(host, port))
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                track(base.createSocket(host, port, local, localPort))
            override fun createSocket(host: InetAddress, port: Int): Socket = track(base.createSocket(host, port))
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                track(base.createSocket(host, port, local, localPort))
        }
    }

    /** Stops a cancelled action before its next step (its sockets are already closed). */
    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
    }

    /** Runs blocking mail work on a worker thread; cancelling returns at once and closes its sockets. */
    private suspend fun <T> offThread(block: (Sockets) -> T): T = suspendCancellableCoroutine { continuation ->
        val sockets = Sockets()
        val future = threads.submit {
            val result = try { runCatching { block(sockets) } } finally { sockets.closeAll() }
            continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation {
            future.cancel(true)
            sockets.closeAll()
        }
    }

    private fun imapHost(provider: OrbitMailProvider) =
        if (provider == OrbitMailProvider.GMAIL) "imap.gmail.com" else "outlook.office365.com"

    private fun smtpHost(provider: OrbitMailProvider, consumer: Boolean) = when {
        provider == OrbitMailProvider.GMAIL -> "smtp.gmail.com"
        consumer -> "smtp-mail.outlook.com"
        else -> "smtp.office365.com"
    }

    private fun session(email: String, sockets: Sockets?): Session = Session.getInstance(Properties().apply {
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.auth.mechanisms", "XOAUTH2")
        put("mail.imaps.connectiontimeout", "20000")
        put("mail.imaps.timeout", "45000")
        put("mail.imaps.writetimeout", "45000")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
        put("mail.smtp.ssl.checkserveridentity", "true")
        put("mail.smtp.auth", "true")
        put("mail.smtp.auth.mechanisms", "XOAUTH2")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "45000")
        put("mail.smtp.writetimeout", "45000")
        sockets?.let {
            put("mail.imaps.socketFactory", it.plain)
            put("mail.imaps.socketFactory.fallback", "false")
            put("mail.smtp.socketFactory", it.plain)
            put("mail.smtp.socketFactory.fallback", "false")
        }
        // A reply to all leaves out the account's own address.
        put("mail.alternates", email)
    })

    /** Signs in once over IMAP and SMTP with a fresh token, so a wrong account never looks connected. */
    suspend fun verify(provider: OrbitMailProvider, email: String, accessToken: String, consumer: Boolean) = offThread { sockets ->
        val session = session(email, sockets)
        val store = session.getStore("imaps")
        try {
            store.connect(imapHost(provider), 993, email, accessToken)
        } catch (e: AuthenticationFailedException) {
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.IMAP_REFUSED, email)
        } finally {
            runCatching { store.close() }
        }
        ensureActive()
        val transport = session.getTransport("smtp")
        try {
            transport.connect(smtpHost(provider, consumer), 587, email, accessToken)
        } catch (e: AuthenticationFailedException) {
            throw OrbitMailAuthError(OrbitMailAuthError.Kind.SMTP_REFUSED, email)
        } finally {
            runCatching { transport.close() }
        }
    }

    /** One try at a mail action: done, or the server refused the token. */
    private sealed interface Attempt<out T> {
        class Done<T>(val value: T) : Attempt<T>
        object Refused : Attempt<Nothing>
    }

    /** Runs [block] on a connected mailbox. A refused token is refreshed once before giving up. */
    private suspend fun <T> imap(context: Context, orbitId: String, account: OrbitMailAccount,
        block: (IMAPStore, Session) -> T): T {
        var login = OrbitMailAccounts.login(context, orbitId, account.id)
        repeat(2) { attempt ->
            val token = login.accessToken
            val result = offThread<Attempt<T>> { sockets ->
                val session = session(account.email, sockets)
                val store = session.getStore("imaps") as IMAPStore
                try {
                    try {
                        store.connect(imapHost(account.provider), 993, account.email, token)
                    } catch (e: AuthenticationFailedException) {
                        return@offThread Attempt.Refused
                    }
                    ensureActive()
                    Attempt.Done(block(store, session))
                } finally {
                    runCatching { store.close() }
                }
            }
            if (result is Attempt.Done) return result.value
            if (attempt == 0) login = OrbitMailAccounts.login(context, orbitId, account.id, refused = token)
        }
        throw IllegalStateException("${account.email} refused the sign-in. IMAP may be turned off for this mailbox; " +
            "otherwise ask the user to connect it again in Orbit settings → Connectors")
    }

    private suspend fun smtp(context: Context, orbitId: String, account: OrbitMailAccount, composed: Composed) {
        var login = OrbitMailAccounts.login(context, orbitId, account.id)
        repeat(2) { attempt ->
            val token = login.accessToken
            val sent = offThread { sockets ->
                val session = session(account.email, sockets)
                val transport = session.getTransport("smtp")
                try {
                    transport.connect(smtpHost(account.provider, account.consumer), 587, account.email, token)
                    val message = mime(session, account, composed)
                    ensureActive()
                    transport.sendMessage(message, message.allRecipients)
                    true
                } catch (e: AuthenticationFailedException) {
                    false
                } finally {
                    runCatching { transport.close() }
                }
            }
            if (sent) return
            if (attempt == 0) login = OrbitMailAccounts.login(context, orbitId, account.id, refused = token)
        }
        throw IllegalStateException("${account.email} may not send mail (SMTP sign-in was refused); nothing was sent")
    }

    // ---- ids and folders ----

    private class Ref(val folder: String, val validity: Long, val uid: Long)

    private fun idOf(account: OrbitMailAccount, folder: IMAPFolder, uid: Long): String =
        Base64.encodeToString("${account.id}:${folder.uidValidity}:$uid:${folder.fullName}".toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun refOf(account: OrbitMailAccount, id: String): Ref {
        val text = runCatching {
            String(Base64.decode(id.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }.getOrNull()
        val parts = text?.split(':', limit = 4)?.takeIf { it.size == 4 }
            ?: throw IllegalArgumentException("Unknown message id; use an id from list, search or read")
        require(parts[0] == account.id) { "That id belongs to another mailbox; pass the account it came from" }
        val validity = parts[1].toLongOrNull()
        val uid = parts[2].toLongOrNull()
        require(validity != null && uid != null && parts[3].isNotEmpty()) { "Unknown message id; use an id from list, search or read" }
        return Ref(parts[3], validity, uid)
    }

    /** The folder named exactly [name] (a server may otherwise match a similar name), or null. */
    private fun exact(store: IMAPStore, name: String): IMAPFolder? {
        if (name.equals("INBOX", true)) return store.getFolder("INBOX") as IMAPFolder
        val folder = store.getFolder(name) as IMAPFolder
        return folder.takeIf { runCatching { it.exists() }.getOrDefault(false) && it.fullName == name }
    }

    private fun open(store: IMAPStore, ref: Ref, mode: Int): IMAPFolder {
        val folder = exact(store, ref.folder) ?: throw IllegalStateException("The folder of that message is gone; list or search again")
        folder.open(mode)
        check(folder.uidValidity == ref.validity) { "The mailbox changed since that id was given; list or search again" }
        return folder
    }

    private fun message(folder: IMAPFolder, ref: Ref): Message =
        folder.getMessageByUID(ref.uid) ?: throw IllegalStateException("That message is no longer there")

    private fun allFolders(store: IMAPStore): List<IMAPFolder> = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()

    /** The folders that have a role (sent, drafts, archive, all, trash, spam, inbox), from one folder list. */
    private fun roles(store: IMAPStore, all: List<IMAPFolder>): Map<String, IMAPFolder> {
        val found = LinkedHashMap<String, IMAPFolder>()
        all.forEach { folder ->
            val attributes = runCatching { folder.attributes.toList() }.getOrDefault(emptyList())
            ROLES.forEach { (flag, role) -> if (attributes.any { it.equals(flag, true) } && role !in found) found[role] = folder }
        }
        ROLE_NAMES.forEach { (role, names) ->
            if (role !in found) all.firstOrNull { f -> names.any { it.equals(f.fullName, true) } }?.let { found[role] = it }
        }
        found["inbox"] = store.getFolder("INBOX") as IMAPFolder
        return found
    }

    /** A folder by its exact name first, then by role (sent, drafts…); only a role needs the folder list. */
    private fun folderNamed(store: IMAPStore, name: String?, fallback: String): IMAPFolder {
        val wanted = name?.trim().orEmpty().ifEmpty { fallback }
        exact(store, wanted)?.let { return it }
        if (wanted.lowercase() in ROLE_WORDS) role(store, wanted.lowercase())?.let { return it }
        throw IllegalArgumentException("No folder \"$wanted\"; see action=folders")
    }

    private fun role(store: IMAPStore, role: String): IMAPFolder? = roles(store, allFolders(store))[role]

    // ---- reading ----

    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun addresses(list: Array<javax.mail.Address>?): String =
        list.orEmpty().joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }

    private fun summary(account: OrbitMailAccount, folder: IMAPFolder, message: Message): JSONObject = JSONObject()
        .put("id", idOf(account, folder, folder.getUID(message)))
        .put("from", addresses(message.from))
        .put("subject", message.subject.orEmpty())
        .put("date", message.sentDate?.let { dateFormat.format(it) } ?: message.receivedDate?.let { dateFormat.format(it) }.orEmpty())
        .put("unread", !message.isSet(Flags.Flag.SEEN))
        .put("has_attachments", runCatching { message.isMimeType("multipart/mixed") }.getOrDefault(false))

    suspend fun folders(context: Context, orbitId: String, account: OrbitMailAccount): JSONArray =
        imap(context, orbitId, account) { store, _ ->
            val all = allFolders(store)
            val roles = roles(store, all).entries.associate { (role, folder) -> folder.fullName to role }
            JSONArray().apply {
                all.filter { f -> runCatching { f.type and Folder.HOLDS_MESSAGES != 0 }.getOrDefault(false) }
                    .forEach { f -> put(JSONObject().put("name", f.fullName).apply { roles[f.fullName]?.let { put("role", it) } }) }
            }
        }

    /**
     * UIDs a search found, newest first, and the UID to go on below (next page's before). The paging
     * point is a bare UID: should the server ever renumber the folder (a new UIDVALIDITY, very rare), a
     * later page may mix in or miss mail. Single messages are addressed with their UIDVALIDITY and stay exact.
     */
    private class Found(val uids: List<Long>, val nextBefore: Long?)

    /** Runs one UID SEARCH; a server that cannot run it says so. */
    private fun uidSearch(folder: IMAPFolder, args: Argument): List<Long> {
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { protocol ->
            val responses = protocol.command("UID SEARCH", args)
            val last = responses.last()
            val found = ArrayList<Long>()
            if (last.isOK) responses.forEach { response ->
                if (response is IMAPResponse && response.keyEquals("SEARCH")) {
                    while (true) {
                        val n = response.readLong()
                        if (n < 0) break
                        found += n
                    }
                }
            }
            protocol.notifyResponseHandlers(responses)
            if (!last.isOK) throw IllegalStateException("The mail server could not run this search")
            found
        } as List<Long>
    }

    /**
     * UID of the message at [seq]. Mail deleted meanwhile keeps its place in JavaMail's view until
     * the folder is opened again, so a gone one throws [MessageRemovedException] (see [fresh]).
     */
    private fun uidAt(folder: IMAPFolder, seq: Int): Long = uidOf(folder, folder.getMessage(seq))

    /** UID of [message]; a deleted one (or one the server gave no UID for) throws [MessageRemovedException]. */
    private fun uidOf(folder: IMAPFolder, message: Message): Long {
        val uid = if (message.isExpunged) -1L else folder.getUID(message)
        if (uid <= 0) throw javax.mail.MessageRemovedException("Message ${message.messageNumber} is gone")
        return uid
    }

    /**
     * Opens [folder] read-only and runs [block]. Should mail be deleted under it mid-way, the folder
     * is opened afresh (a view without the gaps) and [block] runs once more from the start.
     */
    private fun <T> fresh(folder: IMAPFolder, block: () -> T): T {
        folder.open(Folder.READ_ONLY)
        try {
            return block()
        } catch (e: javax.mail.MessageRemovedException) {
            ensureActive()
            runCatching { folder.close(false) }
            folder.open(Folder.READ_ONLY)
            return try { block() } catch (again: javax.mail.MessageRemovedException) {
                throw IllegalStateException("Mail in this folder is changing right now; try again in a moment")
            }
        }
    }

    /**
     * Sequence number of the newest message with a UID below [before] (all messages when null, 0 when
     * none). UIDs grow with sequence numbers, so when the paging message itself is gone the spot is
     * found by halving: a few short fetches even in a huge folder.
     */
    private fun endBelow(folder: IMAPFolder, before: Long?): Int {
        val count = folder.messageCount
        if (before == null) return count
        if (before <= 1) return 0
        folder.getMessageByUID(before)?.takeIf { !it.isExpunged }?.messageNumber?.let { return it - 1 }
        var low = 1
        var high = count
        var best = 0
        while (low <= high) {
            ensureActive()
            val middle = (low + high) ushr 1
            val uid = uidAt(folder, middle)
            if (uid < before) {
                best = middle
                low = middle + 1
            } else high = middle - 1
        }
        return best
    }

    /** The newest [take] messages older than UID [before], with no search: just the last few in the folder. */
    private fun latest(folder: IMAPFolder, before: Long?, take: Int): Found {
        val end = endBelow(folder, before)
        if (end < 1) return Found(emptyList(), null)
        val start = maxOf(1, end - take + 1)
        val messages = folder.getMessages(start, end)
        folder.fetch(messages, javax.mail.FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        val uids = messages.map { uidOf(folder, it) }.filter { before == null || it < before }.sortedDescending()
        return Found(uids, if (start > 1) uids.lastOrNull()?.takeIf { it > 1 } else null)
    }

    /**
     * Searches [folder] on the server for messages older than UID [before], newest first, over
     * [WINDOW] messages at a time, until [take] are found or [MAX_SCAN] were looked at. Results and
     * the paging point are UIDs, so mail deleted meanwhile cannot shift them.
     */
    private fun windowed(folder: IMAPFolder, before: Long?, take: Int, utf8: Boolean, criteria: (Argument) -> Unit): Found {
        // Only messages older than the paging point are looked at, so the next one is always older still.
        var end = endBelow(folder, before)
        // Each window is searched as a UID range read from the server just before, so mail deleted
        // meanwhile can neither shift a window nor skip one; [lowest] is the oldest UID looked at.
        var upper: Long? = before?.minus(1)
        var lowest: Long? = null
        val found = LinkedHashSet<Long>()
        var scanned = 0
        while (end >= 1 && found.size < take && scanned < MAX_SCAN) {
            ensureActive()
            val start = maxOf(1, end - WINDOW + 1)
            val low = minOf(uidAt(folder, start), upper ?: Long.MAX_VALUE)
            val args = Argument()
            if (utf8) {
                args.writeAtom("CHARSET")
                args.writeAtom("UTF-8")
            }
            args.writeAtom("UID")
            args.writeAtom("$low:${upper ?: "*"}")
            criteria(args)
            uidSearch(folder, args).sortedDescending().forEach { if (found.size < take) found += it }
            scanned += end - start + 1
            lowest = low
            if (low <= 1) {
                end = 0
                break
            }
            upper = low - 1
            end = endBelow(folder, low)
        }
        val list = found.toList()
        val nextBefore = when {
            list.size >= take -> list.last()
            // Stopped by the scan limit: go on below the oldest message looked at.
            end >= 1 -> lowest
            else -> null
        }?.takeIf { it > 1 && (before == null || it < before) }
        return Found(list, nextBefore)
    }

    private fun page(account: OrbitMailAccount, folder: IMAPFolder, found: Found): JSONObject {
        val messages = if (found.uids.isEmpty()) emptyList()
            else folder.getMessagesByUID(found.uids.toLongArray()).map {
                // Deleted between finding and reading: the whole page is read again ([fresh]).
                if (it == null || it.isExpunged) throw javax.mail.MessageRemovedException("A message on this page is gone")
                it
            }
        if (messages.isNotEmpty()) folder.fetch(messages.toTypedArray(), javax.mail.FetchProfile().apply {
            add(javax.mail.FetchProfile.Item.ENVELOPE)
            add(javax.mail.FetchProfile.Item.FLAGS)
            add(javax.mail.FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
        })
        return JSONObject().put("account", account.email).put("folder", folder.fullName)
            .put("messages", JSONArray().apply { messages.forEach { put(summary(account, folder, it)) } })
            .apply { found.nextBefore?.let { put("next_before", it) } }
    }

    /** Newest messages of a folder; [before] continues from a previous page's next_before. */
    suspend fun list(context: Context, orbitId: String, account: OrbitMailAccount, folderName: String?, limit: Int,
        unreadOnly: Boolean, before: Long?): JSONObject = imap(context, orbitId, account) { store, _ ->
        val folder = folderNamed(store, folderName, "inbox")
        val take = limit.coerceIn(1, MAX_LIST)
        fresh(folder) {
            page(account, folder, if (!unreadOnly) latest(folder, before, take)
                else windowed(folder, before, take, utf8 = false) { it.writeAtom("UNSEEN") })
        }
    }

    /**
     * Gmail understands its own search words (from:, has:attachment, newer_than:7d…) and searches all
     * mail by default; Outlook matches [query] in the subject, sender or text of [folderName].
     */
    suspend fun search(context: Context, orbitId: String, account: OrbitMailAccount, query: String, folderName: String?,
        limit: Int, unreadOnly: Boolean, before: Long?): JSONObject = imap(context, orbitId, account) { store, _ ->
        val gmail = account.provider == OrbitMailProvider.GMAIL
        val folder = folderNamed(store, folderName, if (gmail) "all" else "inbox")
        val take = limit.coerceIn(1, MAX_LIST)
        fresh(folder) {
            val found = if (gmail) windowed(folder, before, take, utf8 = true) {
                it.writeAtom("X-GM-RAW")
                it.writeString(if (unreadOnly) "$query is:unread" else query, "UTF-8")
            } else {
                val utf8 = query.any { it.code > 127 }
                windowed(folder, before, take, utf8) { args ->
                    if (unreadOnly) args.writeAtom("UNSEEN")
                    args.writeAtom("OR")
                    args.writeAtom("OR")
                    listOf("SUBJECT", "FROM", "BODY").forEach { key ->
                        args.writeAtom(key)
                        if (utf8) args.writeString(query, "UTF-8") else args.writeString(query)
                    }
                }
            }
            page(account, folder, found)
        }
    }

    /** Some text, and whether more of it was left out. */
    private class Text(val value: String, val truncated: Boolean)

    /** A message's parts in a fixed order: the text, and the attachments numbered from 1. */
    private class Parts(var plain: Text? = null, var html: Text? = null, val attachments: MutableList<Part> = ArrayList())

    /** Walks the MIME tree; with [withText] the text parts are downloaded too (within the limits). */
    private fun walk(part: Part, into: Parts, withText: Boolean) {
        val disposition = runCatching { part.disposition }.getOrNull()
        val named = runCatching { part.fileName }.getOrNull() != null
        when {
            part.isMimeType("multipart/*") -> {
                // A broken part leaves the rest of the message readable.
                val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
                val count = runCatching { multipart.count }.getOrDefault(0)
                for (i in 0 until count) runCatching { multipart.getBodyPart(i) }.getOrNull()?.let { walk(it, into, withText) }
            }
            Part.ATTACHMENT.equals(disposition, true) || named || part.isMimeType("message/rfc822") -> into.attachments += part
            !withText -> Unit
            part.isMimeType("text/plain") && into.plain == null -> into.plain = readText(part)
            part.isMimeType("text/html") && into.html == null -> into.html = readText(part)
        }
    }

    /** Ends after [limit] bytes, noting whether there was more. */
    private class Limited(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var count = 0
        var cut = false
            private set

        override fun read(): Int {
            if (count >= limit) { cut = cut || super.read() >= 0; return -1 }
            return super.read().also { if (it >= 0) count++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= limit) { cut = cut || super.read() >= 0; return -1 }
            return super.read(b, off, minOf(len, limit - count)).also { if (it > 0) count += it }
        }
    }

    /**
     * The text of a part. At most [MAX_RAW_BYTES] are downloaded as sent (before base64 or
     * quoted-printable is undone) and [MAX_TEXT_BYTES] kept; null when it cannot be read.
     */
    private fun readText(part: Part): Text? = runCatching {
        val charset = runCatching { ContentType(part.contentType).getParameter("charset") }.getOrNull()
            ?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() } ?: Charsets.UTF_8
        val raw = when (part) {
            is MimeBodyPart -> Limited(part.rawInputStream, MAX_RAW_BYTES) to part.encoding
            is MimeMessage -> Limited(part.rawInputStream, MAX_RAW_BYTES) to part.encoding
            else -> null
        }
        val input = raw?.let { (stream, encoding) -> MimeUtility.decode(stream, encoding ?: "7bit") } ?: part.inputStream
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var more = false
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val room = MAX_TEXT_BYTES - out.size()
                if (n > room) { out.write(buffer, 0, room); more = true; break }
                out.write(buffer, 0, n)
            }
            Text(String(out.toByteArray(), charset), more || raw?.first?.cut == true)
        }
    }.getOrNull()

    private fun parts(message: Message, withText: Boolean = true) = Parts().also { walk(message, it, withText) }

    private fun text(parts: Parts): Text {
        parts.plain?.takeIf { it.value.isNotBlank() }?.let { return it }
        // A cut-off plain part that kept only blank lines still says it was cut.
        val plainCut = parts.plain?.truncated == true
        val html = parts.html ?: return Text("", plainCut)
        val doc = Jsoup.parse(html.value)
        doc.select("style,script,head").remove()
        doc.select("br").append("\\n")
        doc.select("p,div,li,tr,h1,h2,h3,h4,h5,h6").prepend("\\n")
        return Text(doc.text().replace("\\n", "\n").replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim(),
            html.truncated)
    }

    private fun fileName(part: Part, index: Int): String =
        runCatching { part.fileName?.let { MimeUtility.decodeText(it) } }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (part.isMimeType("message/rfc822")) "message-$index.eml" else "attachment-$index"

    /** One message: who, when, its text (a window of it) and its attachments. Reading does not mark it read. */
    suspend fun read(context: Context, orbitId: String, account: OrbitMailAccount, id: String, startChar: Int,
        maxChars: Int): JSONObject {
        val ref = refOf(account, id)
        return imap(context, orbitId, account) { store, _ ->
            val folder = open(store, ref, Folder.READ_ONLY)
            val message = message(folder, ref)
            val parts = parts(message)
            val text = text(parts)
            val body = text.value
            val from = (startChar - 1).coerceIn(0, body.length)
            val to = (from + maxChars).coerceAtMost(body.length)
            JSONObject().put("account", account.email).put("id", id).put("folder", folder.fullName)
                .put("from", addresses(message.from)).put("to", addresses(message.getRecipients(Message.RecipientType.TO)))
                .apply { addresses(message.getRecipients(Message.RecipientType.CC)).takeIf { it.isNotEmpty() }?.let { put("cc", it) } }
                .put("date", message.sentDate?.let { dateFormat.format(it) }.orEmpty())
                .put("subject", message.subject.orEmpty())
                .put("unread", !message.isSet(Flags.Flag.SEEN))
                .put("body", body.substring(from, to))
                .put("body_chars", body.length)
                .apply { if (to < body.length) put("next_start_char", to + 1) }
                .apply { if (text.truncated) put("body_truncated", "The mail is longer than what can be read here; the rest is only in the user's mail app") }
                .put("attachments", JSONArray().apply {
                    parts.attachments.forEachIndexed { i, part ->
                        put(JSONObject().put("index", i + 1).put("name", fileName(part, i + 1))
                            .put("type", part.contentType.substringBefore(';').trim().lowercase())
                            .apply { if (part.size > 0) put("size", part.size) })
                    }
                })
        }
    }

    /** Saves attachment [index] of a message into the Orbit's folder (mail/…); returns where. */
    suspend fun saveAttachment(context: Context, orbitId: String, account: OrbitMailAccount, id: String, index: Int): JSONObject {
        val root = OrbitPreferences.getInstance(context).orbitDir(orbitId)
        val ref = refOf(account, id)
        return imap(context, orbitId, account) { store, _ ->
            val folder = open(store, ref, Folder.READ_ONLY)
            val attachments = parts(message(folder, ref), withText = false).attachments
            val part = attachments.getOrNull(index - 1) ?: throw IllegalArgumentException(
                "No attachment $index; this message has ${attachments.size}")
            val name = fileName(part, index).replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120)
                .trim('.', ' ').ifEmpty { "attachment-$index" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(root, "mail/$stamp-${java.util.UUID.randomUUID().toString().take(4)}")
            check(dir.mkdirs() || dir.isDirectory) { "Could not make a folder for the attachment" }
            val file = File(dir, name)
            var written = 0L
            try {
                part.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            check(written <= MAX_ATTACHMENT_BYTES) { "The attachment is larger than 25 MB and was not saved" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                file.delete()
                dir.delete()
                throw e
            }
            JSONObject().put("saved", true).put("path", file.absolutePath).put("name", name).put("size", written)
                .put("type", part.contentType.substringBefore(';').trim().lowercase())
        }
    }

    // ---- changing ----

    private fun refs(account: OrbitMailAccount, ids: List<String>): Map<String, List<Ref>> {
        require(ids.isNotEmpty()) { "Give id (one or more, separated by commas)" }
        require(ids.size <= MAX_IDS) { "At most $MAX_IDS messages at once" }
        return ids.map { refOf(account, it) }.groupBy { "${it.validity}:${it.folder}" }
    }

    /** Marks messages read or unread. */
    suspend fun mark(context: Context, orbitId: String, account: OrbitMailAccount, ids: List<String>, read: Boolean): Int {
        val groups = refs(account, ids)
        return imap(context, orbitId, account) { store, _ ->
            var changed = 0
            groups.values.forEach { group ->
                ensureActive()
                val folder = open(store, group.first(), Folder.READ_WRITE)
                try {
                    val messages = folder.getMessagesByUID(group.map { it.uid }.toLongArray()).filterNotNull()
                    if (messages.isNotEmpty()) folder.setFlags(messages.toTypedArray(), Flags(Flags.Flag.SEEN), read)
                    changed += messages.size
                } finally {
                    runCatching { folder.close(false) }
                }
            }
            changed
        }
    }

    /** Archives messages: out of the inbox, still in the mailbox. */
    suspend fun archive(context: Context, orbitId: String, account: OrbitMailAccount, ids: List<String>): Int {
        val groups = refs(account, ids)
        return imap(context, orbitId, account) { store, _ ->
            var changed = 0
            val target = if (account.provider == OrbitMailProvider.GMAIL) null
                else role(store, "archive") ?: throw IllegalStateException("This mailbox has no Archive folder")
            groups.values.forEach { group ->
                ensureActive()
                val folder = open(store, group.first(), Folder.READ_WRITE)
                try {
                    val messages = folder.getMessagesByUID(group.map { it.uid }.toLongArray()).filterNotNull()
                    if (messages.isEmpty()) return@forEach
                    if (target == null) {
                        // Gmail: archiving is taking the Inbox label off; the message stays in All Mail.
                        val set = messages.joinToString(",") { folder.getUID(it).toString() }
                        folder.doCommand { protocol ->
                            val responses = protocol.command("UID STORE $set -X-GM-LABELS (\\Inbox)", null)
                            protocol.notifyResponseHandlers(responses)
                            protocol.handleResult(responses.last())
                            null
                        }
                    } else {
                        check(folder.fullName != target.fullName) { "Those messages are already archived" }
                        folder.moveMessages(messages.toTypedArray(), target)
                    }
                    changed += messages.size
                } finally {
                    runCatching { folder.close(false) }
                }
            }
            changed
        }
    }

    // ---- writing ----

    /** What the model asked to write; checked before anything connects. */
    class Draft(val to: String?, val cc: String?, val bcc: String?, val subject: String?, val body: String)

    /**
     * A message ready to go, exactly as its send card shows it: the final recipients (one address
     * each), subject and text, and for a reply the original quoted under it and the thread headers.
     */
    class Composed(
        val to: List<String>, val cc: List<String>, val bcc: List<String>, val subject: String, val body: String,
        val quote: String?, val inReplyTo: String?, val references: String?, val answerId: String?,
    ) {
        val recipients: Int get() = to.size + cc.size + bcc.size
    }

    /** One full name@domain address, its display name kept on one line and encoded for mail headers. */
    private fun cleanAddress(address: InternetAddress): InternetAddress {
        val email = address.address.orEmpty()
        require(OrbitMailAuth.isAddress(email)) { "Not a full email address: $email" }
        val name = address.personal?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        return InternetAddress(email, name, "UTF-8")
    }

    private fun clean(address: InternetAddress): String = cleanAddress(address).toUnicodeString()

    /** Exactly one address in [text]. */
    fun single(text: String): InternetAddress {
        val parsed = try {
            InternetAddress.parse(text, true)
        } catch (e: javax.mail.internet.AddressException) {
            throw IllegalArgumentException("Not a valid address: $text")
        }
        require(parsed.size == 1) { "Not a single address: $text" }
        return cleanAddress(parsed[0])
    }

    /** Addresses in [text], each a full name@domain address; throws on anything else. */
    fun addressList(text: String?): List<String> {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val parsed = try {
            InternetAddress.parse(value.replace(';', ','), true)
        } catch (e: javax.mail.internet.AddressException) {
            throw IllegalArgumentException("Not a valid address list: ${e.ref ?: value}")
        }
        return parsed.map(::clean)
    }

    private fun checkRecipients(composed: Composed, needsRecipient: Boolean): Composed {
        // Every address, typed or taken from the original, is one full address.
        (composed.to + composed.cc + composed.bcc).forEach(::single)
        require(composed.recipients <= MAX_RECIPIENTS) { "At most $MAX_RECIPIENTS recipients" }
        require(!needsRecipient || composed.recipients > 0) { "Give at least one recipient (to)" }
        return composed
    }

    /** A new message. */
    fun composeNew(draft: Draft, needsRecipient: Boolean): Composed = checkRecipients(Composed(
        addressList(draft.to), addressList(draft.cc), addressList(draft.bcc), draft.subject.orEmpty(), draft.body,
        null, null, null, null,
    ), needsRecipient)

    /** The original's text, quoted under the reply. */
    private fun quoted(original: Message, english: Boolean): String {
        val text = text(parts(original)).value.take(QUOTE_CHARS)
        val who = addresses(original.from)
        val whenSent = original.sentDate?.let { dateFormat.format(it) }.orEmpty()
        val head = if (english) "On $whenSent, $who wrote:" else "$whenSent，$who 写道："
        return head + "\n" + text.lines().joinToString("\n") { "> $it" }
    }

    /**
     * A reply to message [id]: to its reply address or sender (or everyone with [all]) unless
     * [draft] names the recipients, "Re:" its subject unless one is given, with the original quoted.
     */
    suspend fun composeReply(context: Context, orbitId: String, account: OrbitMailAccount, id: String, all: Boolean,
        draft: Draft, english: Boolean, needsRecipient: Boolean): Composed {
        val ref = refOf(account, id)
        val to = addressList(draft.to)
        val cc = addressList(draft.cc)
        val bcc = addressList(draft.bcc)
        val composed = imap(context, orbitId, account) { store, _ ->
            val folder = open(store, ref, Folder.READ_ONLY)
            val original = message(folder, ref)
            // reply() works out who to answer (Reply-To, everyone for reply-all, minus this account),
            // "Re:" and the thread headers.
            val reply = original.reply(all) as MimeMessage
            fun own(list: Array<javax.mail.Address>?) = list.orEmpty().filterIsInstance<InternetAddress>()
                .filterNot { it.address.orEmpty().equals(account.email, true) }
                .mapNotNull { runCatching { clean(it) }.getOrNull() }
            Composed(
                to = to.ifEmpty { own(reply.getRecipients(Message.RecipientType.TO)) },
                cc = if (draft.cc.isNullOrBlank()) own(reply.getRecipients(Message.RecipientType.CC)) else cc,
                bcc = bcc,
                subject = draft.subject?.takeIf { it.isNotBlank() } ?: reply.subject.orEmpty(),
                body = draft.body,
                quote = quoted(original, english),
                inReplyTo = reply.getHeader("In-Reply-To", null),
                references = reply.getHeader("References", " "),
                answerId = id,
            )
        }
        return checkRecipients(composed, needsRecipient)
    }

    private fun mime(session: Session, account: OrbitMailAccount, composed: Composed): MimeMessage =
        MimeMessage(session).apply {
            setFrom(InternetAddress(account.email))
            if (composed.to.isNotEmpty()) setRecipients(Message.RecipientType.TO, composed.to.map(::single).toTypedArray())
            if (composed.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, composed.cc.map(::single).toTypedArray())
            if (composed.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, composed.bcc.map(::single).toTypedArray())
            setSubject(composed.subject, "UTF-8")
            setText(composed.body + (composed.quote?.let { "\n\n" + it } ?: ""), "UTF-8")
            composed.inReplyTo?.let { setHeader("In-Reply-To", it.replace(Regex("[\\r\\n]+"), " ")) }
            composed.references?.let { setHeader("References", it.replace(Regex("[\\r\\n]+"), " ")) }
            sentDate = Date()
            saveChanges()
        }

    /** Sends [composed] as it is; a reply then marks its original answered. */
    suspend fun send(context: Context, orbitId: String, account: OrbitMailAccount, composed: Composed) {
        checkRecipients(composed, needsRecipient = true)
        smtp(context, orbitId, account, composed)
        composed.answerId?.let { id ->
            runCatching {
                val ref = refOf(account, id)
                imap(context, orbitId, account) { store, _ ->
                    val folder = open(store, ref, Folder.READ_WRITE)
                    try { message(folder, ref).setFlag(Flags.Flag.ANSWERED, true) } finally { runCatching { folder.close(false) } }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    /** Saves [composed] in the mailbox's Drafts; nothing is sent. */
    suspend fun saveDraft(context: Context, orbitId: String, account: OrbitMailAccount, composed: Composed): JSONObject =
        imap(context, orbitId, account) { store, session ->
            val drafts = role(store, "drafts") ?: throw IllegalStateException("This mailbox has no Drafts folder")
            val message = mime(session, account, composed)
            message.setFlag(Flags.Flag.DRAFT, true)
            message.setFlag(Flags.Flag.SEEN, true)
            ensureActive()
            drafts.appendMessages(arrayOf(message))
            JSONObject().put("saved", true).put("folder", drafts.fullName)
                .put("note", "Saved as a draft in ${account.email}; nothing was sent. The user can open it in their mail app.")
        }
}
