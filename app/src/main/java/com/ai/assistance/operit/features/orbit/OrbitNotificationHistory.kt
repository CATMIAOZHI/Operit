package com.ai.assistance.operit.features.orbit

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.service.notification.StatusBarNotification
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The phone's notification history for Orbit. While notification reading is on and an Orbit
 * exists, every notification other apps post is kept here for [KEEP_DAYS] days, so Orbit can look
 * back by time, app or words even after the user cleared the tray. Ongoing ones (music, downloads,
 * running services) and group summaries are skipped; an update that repeats the same text is kept once.
 */
internal object OrbitNotificationHistory {
    const val KEEP_DAYS = 30
    const val MAX_HOURS = KEEP_DAYS * 24
    /** Characters kept per notification; longer text is cut and marked with its original length. */
    const val MAX_STORED_CHARS = 4000
    private const val MAX_ROWS = 50_000
    private const val PRUNE_EVERY = 500
    private const val PRUNE_INTERVAL_MS = 3_600_000L
    /** Waiting writes; when a burst fills it, the oldest waiting ones are dropped. */
    private const val MAX_PENDING = 1_000
    private const val DB_NAME = "orbit_notification_history.db"
    private const val TABLE = "history"

    /** [chars] is the text's original length; more than body.length means only the first [MAX_STORED_CHARS] were kept. */
    data class Item(val id: Long, val time: Long, val pkg: String, val app: String, val title: String, val body: String, val chars: Int) {
        val storedCut get() = chars > body.length
    }
    data class Page(val items: List<Item>, val more: Boolean)
    data class AppCount(val pkg: String, val app: String, val count: Int, val latest: Long)

    /** Writes happen one at a time here, off the listener's thread. */
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(MAX_PENDING),
        { Thread(it, "orbit-notification-history").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())

    /** Held while a write checks the Orbit and inserts, and while the history is cleared, so nothing lands after a clear. */
    private val writeLock = Any()
    /** Guards only [lastText]; never held across disk work. */
    private val textLock = Any()

    @Volatile
    private var helper: Helper? = null

    /** The last text kept per notification key, so an update that changes nothing is not kept again. Under [textLock]. */
    private val lastText = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }
    /**
     * Order of listener events. A removal or reconnect updates [lastText] at once on the listener
     * thread (so a full queue cannot drop it) and notes its number; a write queued before it then
     * keeps its row but no longer changes [lastText]. Under [textLock].
     */
    private var eventSeq = 0L
    private var resetSeq = 0L
    private val removedSeq = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        // A forgotten removal raises the common floor instead, so a write queued before it still counts as older.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            if (size <= 500) return false
            if (eldest != null) resetSeq = maxOf(resetSeq, eldest.value)
            return true
        }
    }
    private var insertsSincePrune = PRUNE_EVERY
    /** Raised by [clear]; a write queued before the clear is dropped instead of bringing old notifications back. */
    @Volatile
    private var clearGen = 0L
    @Volatile
    private var lastPruneAt = 0L
    private val labels = ConcurrentHashMap<String, String>()

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                    "app TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, chars INTEGER NOT NULL, nkey TEXT NOT NULL, " +
                    "UNIQUE(nkey, time))"
            )
            db.execSQL("CREATE INDEX history_time ON $TABLE(time)")
            db.execSQL("CREATE INDEX history_pkg_time ON $TABLE(pkg, time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase

    /** Called by the notification listener for every posted or updated notification. */
    fun record(context: Context, sbn: StatusBarNotification) {
        val app = context.applicationContext
        val pkg = sbn.packageName.orEmpty()
        val notification = sbn.notification ?: return
        if (pkg.isEmpty() || pkg == app.packageName) return
        val flags = notification.flags
        if (sbn.isOngoing || flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0 || flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val (title, body, chars) = extract(notification)
        if (title.isBlank() && body.isBlank()) return
        val key = sbn.key ?: "$pkg:${sbn.id}:${sbn.tag.orEmpty()}"
        val time = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val seq = synchronized(textLock) { ++eventSeq }
        val gen = clearGen
        writer.execute {
            try {
                val label = label(app, pkg)
                synchronized(writeLock) {
                    if (gen != clearGen || !hasOrbit(app)) return@execute
                    val content = "$title\u0000$body\u0000$chars"
                    val repeat = synchronized(textLock) {
                        val stale = seq < resetSeq || seq < (removedSeq[key] ?: 0L)
                        (lastText[key] == content).also { if (!stale) lastText[key] = content }
                    }
                    if (repeat) return@execute
                    val values = ContentValues().apply {
                        put("time", time)
                        put("pkg", pkg)
                        put("app", label)
                        put("title", title)
                        put("body", body)
                        put("chars", chars)
                        put("nkey", key)
                    }
                    val db = db(app)
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    val now = System.currentTimeMillis()
                    if (++insertsSincePrune >= PRUNE_EVERY || now - lastPruneAt >= PRUNE_INTERVAL_MS) {
                        insertsSincePrune = 0
                        lastPruneAt = now
                        prune(db)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("OrbitNotificationHistory", "Could not keep a notification: ${e.message}")
            }
        }
    }

    /** A removed notification may come back with the same text later; that one is new again. */
    fun removed(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        synchronized(textLock) {
            lastText.remove(key)
            removedSeq[key] = ++eventSeq
        }
    }

    /** The listener (re)connected: removals while it was away were missed, so the last-text memory starts over. */
    fun listenerConnected() {
        synchronized(textLock) {
            lastText.clear()
            removedSeq.clear()
            resetSeq = ++eventSeq
        }
    }

    /**
     * Deletes the whole history, for when the Orbit is deleted; it returns once the rows are gone.
     * Call it after the Orbit is marked deleting, so no write starts again. When the rows cannot be
     * deleted it throws, so the deletion stops and the user can retry it.
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(writeLock) {
            clearGen++
            synchronized(textLock) { lastText.clear() }
            db(app).delete(TABLE, null, null)
        }
    }

    /** Reads also keep the 30 days, so expired rows go even when nothing new arrives (at most once an hour). */
    private fun maintain(context: Context) {
        if (System.currentTimeMillis() - lastPruneAt < PRUNE_INTERVAL_MS) return
        val app = context.applicationContext
        writer.execute {
            try {
                synchronized(writeLock) {
                    val now = System.currentTimeMillis()
                    if (now - lastPruneAt < PRUNE_INTERVAL_MS) return@execute
                    lastPruneAt = now
                    insertsSincePrune = 0
                    prune(db(app))
                }
            } catch (e: Exception) {
                AppLogger.w("OrbitNotificationHistory", "Could not remove expired notifications: ${e.message}")
            }
        }
    }

    private fun hasOrbit(app: Context): Boolean {
        // Read fresh, not the snapshot: a deletion that just began must stop writes before the clear.
        return runBlocking { OrbitPreferences.getInstance(app).current() }.orbits.any { !it.deleting }
    }

    private fun label(app: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: pkg
    }

    private fun cutoff() = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L

    private fun prune(db: SQLiteDatabase) {
        db.delete(TABLE, "time < ?", arrayOf(cutoff().toString()))
        val count = android.database.DatabaseUtils.queryNumEntries(db, TABLE)
        if (count > MAX_ROWS) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE id IN (SELECT id FROM $TABLE ORDER BY time ASC, id ASC LIMIT ?)",
                arrayOf<Any>(count - MAX_ROWS)
            )
        }
    }

    private fun extract(notification: Notification): Triple<String, String, Int> {
        val extras = notification.extras
        // Characters dropped before joining, so the original length (and the cut mark) stays honest.
        var dropped = 0
        fun cut(text: CharSequence, max: Int): String {
            if (text.length > max) dropped += text.length - max
            return text.subSequence(0, minOf(text.length, max)).toString().trim()
        }
        fun text(key: String) = extras?.getCharSequence(key)?.let { cut(it, 20_000) }.orEmpty()
        val title = text(Notification.EXTRA_TITLE).ifEmpty { text(Notification.EXTRA_CONVERSATION_TITLE) }
            .ifEmpty { text(Notification.EXTRA_TITLE_BIG) }.take(300)
        dropped = 0
        val parts = ArrayList<String>(4)
        val main = text(Notification.EXTRA_BIG_TEXT).ifEmpty { text(Notification.EXTRA_TEXT) }
        if (main.isNotEmpty()) parts += main
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
        lines.drop(50).forEach { dropped += (it?.length ?: 0) + 1 }
        lines.take(50).forEach { line ->
            line?.let { cut(it, 2_000) }?.takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        val body = parts.distinct().joinToString("\n").trim()
            .ifEmpty { notification.tickerText?.toString()?.trim().orEmpty() }
        return Triple(title, body.take(MAX_STORED_CHARS), body.length + dropped)
    }

    private fun like(text: String) = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** A package name matches exactly (using the package index); anything else matches words in the app's name. */
    private fun appClause(app: String, args: MutableList<String>): String {
        if (PACKAGE.matches(app)) {
            args += app
            return "pkg = ?"
        }
        args += like(app)
        return "app LIKE ? ESCAPE '\\'"
    }

    /** Newest first, from [since]; [before] is the (time, id) of the last item already shown. */
    fun search(context: Context, since: Long, app: String?, query: String?, limit: Int, before: Pair<Long, Long>?): Page {
        maintain(context)
        val clauses = mutableListOf("time >= ?")
        val args = mutableListOf(maxOf(since, cutoff()).toString())
        if (!app.isNullOrBlank()) clauses += appClause(app, args)
        if (!query.isNullOrBlank()) {
            val pattern = like(query)
            clauses += "(title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\')"
            args += pattern; args += pattern; args += pattern
        }
        if (before != null) {
            clauses += "(time < ? OR (time = ? AND id < ?))"
            args += before.first.toString(); args += before.first.toString(); args += before.second.toString()
        }
        val items = mutableListOf<Item>()
        var more = false
        db(context).query(
            TABLE, COLUMNS,
            clauses.joinToString(" AND "), args.toTypedArray(), null, null, "time DESC, id DESC", (limit + 1).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (items.size >= limit) { more = true; break }
                items += item(cursor)
            }
        }
        return Page(items, more)
    }

    private val COLUMNS = arrayOf("id", "time", "pkg", "app", "title", "body", "chars")

    private fun item(cursor: android.database.Cursor) = Item(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
        cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getInt(6))

    /** Apps that posted since [since], most notifications first. */
    fun apps(context: Context, since: Long, limit: Int): List<AppCount> {
        maintain(context)
        val out = mutableListOf<AppCount>()
        db(context).rawQuery(
            "SELECT pkg, MAX(app), COUNT(*), MAX(time) FROM $TABLE WHERE time >= ? GROUP BY pkg " +
                "ORDER BY COUNT(*) DESC, MAX(time) DESC LIMIT ?",
            arrayOf(maxOf(since, cutoff()).toString(), limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out += AppCount(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3))
        }
        return out
    }

    fun read(context: Context, id: Long): Item? =
        db(context).query(TABLE, COLUMNS, "id = ? AND time >= ?", arrayOf(id.toString(), cutoff().toString()),
            null, null, null).use { cursor ->
            if (cursor.moveToFirst()) item(cursor) else null
        }

    /** When the oldest kept notification arrived, or null while nothing is kept. */
    fun oldest(context: Context): Long? =
        db(context).rawQuery("SELECT MIN(time) FROM $TABLE WHERE time >= ?", arrayOf(cutoff().toString())).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
}
