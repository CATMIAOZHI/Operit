package com.ai.assistance.operit.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Puts back messages saved with a time far in the future.
 *
 * A bubble drawn for a message still being sent once had a time near 2^62 ms, and every message
 * made after it in the same run took a later one, so they were saved that way. Messages sort by
 * time and are referred to by it, so such a chat lists new messages above them and its time lines
 * read year 146 million. Each broken row gets a real time back, after the chat's last sound row
 * and in its old order; references in the database move with it in the same transaction, which
 * also records old -> new in a table. After the commit that table is copied to a file, so stores
 * outside the database translate what they saved ([fix]).
 */
object MessageTimeRepair {
    private const val TAG = "MessageTimeRepair"
    private const val MAX_AHEAD_MS = 24L * 60 * 60 * 1000
    /**
     * Times from this on (the year 33658) can only be the 2^62 placeholder fallout; a time a clock
     * that ran a few days fast left behind stays as it is. Also the digits the references below match.
     */
    private const val BROKEN_FROM = 1_000_000_000_000_000L
    private const val TABLE = "message_time_repair"
    private const val MAP_FILE = "message_time_repair.map"
    /** Larger texts are left as they are rather than read whole into memory. */
    private const val MAX_TEXT = 512 * 1024
    // Not after a minus sign: Long.MIN_VALUE / 2, a marker some stores use, has the same digits.
    private val bigNumber = Regex("""(?<![0-9-])[0-9]{16,19}(?![0-9])""")
    // Only the app's own reply tags, never a timestamp= the user pasted.
    private val replyTime = Regex("""(<reply_to\b[^>]*?\btimestamp\s*=\s*["'])([0-9]{16,19})(["'])""")
    // The wake-up anchor of a run handed to a chat: {"chat": …, "at": <message time>}.
    private val decisionAt = Regex("""("at"\s*:\s*)([0-9]{16,19})(?![0-9])""")
    private val decisionChat = Regex(""""chat"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private class Repairs(val byChat: Map<String, Map<Long, Long>>) {
        /** Old -> new over all chats; a time copied into two chats almost always moved the same way. */
        val any: Map<Long, Long> = HashMap<Long, Long>().also { all ->
            byChat.values.forEach { chat -> chat.forEach { (old, new) -> all.putIfAbsent(old, new) } }
        }
    }

    @Volatile private var mapFile: File? = null
    @Volatile private var loaded: Repairs? = null
    @Volatile private var opened = false

    fun limit(now: Long = System.currentTimeMillis()): Long = now + MAX_AHEAD_MS

    internal fun attach(filesDir: File) {
        mapFile = File(filesDir, MAP_FILE)
    }

    /**
     * Opens the database once, which repairs it, so a store read right after starts from the
     * repaired times. Off the main thread.
     */
    fun ensure(context: Context) {
        if (opened) return
        // Never opens the database on the main thread; such a read uses what is known.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        runCatching { AppDatabase.getDatabase(context).openHelper.writableDatabase }
            .onFailure { Log.w(TAG, "Unable to open the database for the repair", it) }
        opened = true
    }

    /** [text] read from a store of [context], with repaired message times written as they are now. */
    fun fix(context: Context, text: String): String {
        ensure(context)
        return fix(text)
    }

    private fun repairs(): Repairs = loaded ?: synchronized(this) {
        // Before the database is first opened the file is not known yet; read it then.
        val known = mapFile ?: return@synchronized Repairs(emptyMap())
        loaded ?: Repairs(known.takeIf { it.isFile }?.let { file ->
            runCatching {
                val chats = HashMap<String, HashMap<Long, Long>>()
                file.readLines().forEach { line ->
                    val parts = line.trim().split(' ')
                    val old = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                    val new = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
                    chats.getOrPut(parts[0]) { HashMap() }[old] = new
                }
                chats as Map<String, Map<Long, Long>>
            }.getOrElse { Log.w(TAG, "Unable to read the repair map", it); emptyMap() }
        } ?: emptyMap()).also { loaded = it }
    }

    /** [time] as the message's time now. */
    fun fix(time: Long): Long = repairs().any[time] ?: time

    /** [time], a message of [chatId], as its time now. */
    fun fix(chatId: String, time: Long): Long = repairs().byChat[chatId]?.get(time) ?: fix(time)

    /** The time a message of [chatId] had before its repair, or null when it was not repaired. */
    fun original(chatId: String, time: Long): Long? =
        repairs().byChat[chatId]?.entries?.firstOrNull { it.value == time }?.key
            // A branch copied from a repaired chat after the repair has none of its own.
            ?: repairs().byChat.values.firstNotNullOfOrNull { chat -> chat.entries.firstOrNull { it.value == time }?.key }

    /** [text] with every repaired message time in it written as the time now. */
    fun fix(text: String): String {
        val map = repairs().any
        if (map.isEmpty() || !bigNumber.containsMatchIn(text)) return text
        return bigNumber.replace(text) { match -> match.value.toLongOrNull()?.let(map::get)?.toString() ?: match.value }
    }

    private class Row(val id: Long, val chatId: String, val time: Long, val sender: String, val sentAt: Long, val completedAt: Long)

    /** Runs when the database opens, before anything reads it. Does nothing when no row is broken. */
    internal fun repair(db: SupportSQLiteDatabase) {
        val limit = limit()
        var moved = 0
        // Exclusive: another process (the data recovery screen) opening the same database waits.
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (chatId TEXT NOT NULL, oldTime INTEGER NOT NULL, " +
                "newTime INTEGER NOT NULL, PRIMARY KEY(chatId, oldTime))")
            val broken = mutableListOf<Row>()
            db.query("SELECT messageId, chatId, timestamp, sender, sentAt, completedAt FROM messages " +
                "WHERE timestamp >= ? ORDER BY chatId, timestamp, messageId", arrayOf<Any>(BROKEN_FROM)).use { c ->
                while (c.moveToNext()) broken += Row(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4), c.getLong(5))
            }
            val brokenChats = mutableSetOf<String>()
            db.query("SELECT id FROM chats WHERE lastMessageAt >= ? OR updatedAt >= ?",
                arrayOf<Any>(BROKEN_FROM, BROKEN_FROM)).use { c ->
                while (c.moveToNext()) brokenChats += c.getString(0)
            }
            val all = HashMap<Long, Long>()
            val byChat = HashMap<String, Map<Long, Long>>()
            for ((chatId, rows) in broken.groupBy { it.chatId }) {
                var previous = db.query("SELECT MAX(timestamp) FROM messages WHERE chatId = ? AND timestamp < ?",
                    arrayOf<Any>(chatId, BROKEN_FROM)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
                // A chat made after the fault has no sound message; its messages start when it was made.
                if (previous == 0L) {
                    previous = db.query("SELECT createdAt FROM chats WHERE id = ? AND createdAt > 0 AND createdAt < ?",
                        arrayOf<Any>(chatId, BROKEN_FROM)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
                }
                // Rows sharing a time (an old duplicate) keep sharing one: reading them depends on it.
                val chatMoved = LinkedHashMap<Long, Long>()
                for (row in rows) {
                    val time = chatMoved[row.time] ?: run {
                        // A summary's completedAt is the last message it covers, never a time it was made.
                        val real = listOf(row.completedAt.takeIf { row.sender != "summary" }, row.sentAt)
                            .firstOrNull { it != null && it in 1..limit }
                        // A message copied into another chat (a branch) moves the same way when it can.
                        (all[row.time]?.takeIf { it > previous } ?: maxOf(previous + 1, real ?: (previous + 1)))
                            .also { chatMoved[row.time] = it; previous = it }
                    }
                    db.update("messages", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("timestamp", time) },
                        "messageId = ? AND timestamp = ?", arrayOf<Any>(row.id, row.time))
                }
                for ((old, new) in chatMoved) {
                    all.putIfAbsent(old, new)
                    val args = arrayOf<Any>(chatId, old)
                    db.update("message_variants", SQLiteDatabase.CONFLICT_ABORT,
                        ContentValues().apply { put("messageTimestamp", new) }, "chatId = ? AND messageTimestamp = ?", args)
                    db.update("orbit_deliveries", SQLiteDatabase.CONFLICT_ABORT,
                        ContentValues().apply { put("messageTimestamp", new) }, "chatId = ? AND messageTimestamp = ?", args)
                    // The summary that covers this message points at it.
                    db.update("messages", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("completedAt", new) },
                        "chatId = ? AND sender = 'summary' AND completedAt = ?", args)
                    db.insert(TABLE, SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                        put("chatId", chatId); put("oldTime", old); put("newTime", new)
                    })
                }
                rewriteReplies(db, "messages", "messageId", chatId, chatMoved)
                rewriteReplies(db, "message_variants", "variantId", chatId, chatMoved)
                byChat[chatId] = chatMoved
                brokenChats += chatId
                moved += chatMoved.size
            }
            if (all.isNotEmpty()) {
                val updates = mutableListOf<Pair<String, String>>()
                db.query("SELECT id, decision FROM orbit_runs WHERE decision != '' AND length(decision) <= $MAX_TEXT").use { c ->
                    while (c.moveToNext()) {
                        val text = c.getString(1) ?: continue
                        // The anchor is a message of the chat named beside it.
                        val chat = decisionChat.find(text)?.groupValues?.get(1)
                        val moves = chat?.let(byChat::get) ?: all
                        val fixed = decisionAt.replace(text) { m ->
                            m.groupValues[2].toLongOrNull()?.let(moves::get)?.let { m.groupValues[1] + it } ?: m.value
                        }
                        if (fixed != text) updates += c.getString(0) to fixed
                    }
                }
                updates.forEach { (id, text) ->
                    db.update("orbit_runs", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("decision", text) },
                        "id = ?", arrayOf<Any>(id))
                }
            }
            for (chatId in brokenChats) {
                db.execSQL("UPDATE chats SET lastMessageAt = (SELECT MAX(timestamp) FROM messages WHERE chatId = ?) WHERE id = ?",
                    arrayOf<Any>(chatId, chatId))
                // Deliveries stamped the chat's update time with the broken message time too.
                db.execSQL("UPDATE chats SET updatedAt = COALESCE(lastMessageAt, createdAt) WHERE id = ? AND updatedAt >= ?",
                    arrayOf<Any>(chatId, BROKEN_FROM))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (moved > 0) Log.w(TAG, "Repaired $moved message times")
        // Only what is committed is published; also brings a file lost or left behind back in line.
        export(db)
    }

    /** `<reply_to … timestamp="…">` references in the texts of [chatId] that name a moved message. */
    private fun rewriteReplies(db: SupportSQLiteDatabase, table: String, idColumn: String, chatId: String, moved: Map<Long, Long>) {
        if (moved.isEmpty()) return
        val updates = mutableListOf<Pair<Long, String>>()
        db.query("SELECT $idColumn, content FROM $table WHERE chatId = ? AND instr(content, '<reply_to') > 0 " +
            "AND length(content) <= $MAX_TEXT", arrayOf<Any>(chatId)).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(1) ?: continue
                val fixed = replyTime.replace(text) { m ->
                    val new = m.groupValues[2].toLongOrNull()?.let(moved::get) ?: return@replace m.value
                    m.groupValues[1] + new + m.groupValues[3]
                }
                if (fixed != text) updates += c.getLong(0) to fixed
            }
        }
        updates.forEach { (id, text) ->
            db.update(table, SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("content", text) },
                "$idColumn = ?", arrayOf<Any>(id))
        }
    }

    private fun export(db: SupportSQLiteDatabase) {
        val file = mapFile ?: return
        val lines = mutableListOf<String>()
        val chats = HashMap<String, HashMap<Long, Long>>()
        runCatching {
            db.query("SELECT chatId, oldTime, newTime FROM $TABLE ORDER BY chatId, oldTime").use { c ->
                while (c.moveToNext()) {
                    lines += "${c.getString(0)} ${c.getLong(1)} ${c.getLong(2)}"
                    chats.getOrPut(c.getString(0)) { HashMap() }[c.getLong(1)] = c.getLong(2)
                }
            }
        }.onFailure { return }
        if (lines.isEmpty()) return
        val text = lines.joinToString("\n", postfix = "\n")
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) {
            synchronized(this) { if (loaded == null) loaded = Repairs(chats) }
            return
        }
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) error("Unable to replace ${file.name}")
        }.onFailure { Log.w(TAG, "Unable to save the repair map", it) }
        synchronized(this) { loaded = Repairs(chats) }
    }
}
