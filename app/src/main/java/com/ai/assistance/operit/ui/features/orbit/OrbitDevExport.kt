package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.chat.OrbitDevRecorder
import com.ai.assistance.operit.data.db.AppDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The chat on the developer view's screen as one zip for another developer: every stored row with
 * all its fields (thinking, tools, hidden rows, display modes) as JSON lines, and what the developer
 * view kept for it (requests sent, events, counts).
 */
internal object OrbitDevExport {
    private const val DIR = "orbit_dev_export"
    /**
     * A row's text is read as UTF-8 bytes in pieces this long and written out as it comes, so a huge
     * tool result never fills a cursor window or sits in memory whole. Bytes, because SQLite's text
     * LENGTH/SUBSTR stop at an embedded U+0000.
     */
    // About 1MB: well inside a cursor window, and few enough reads that SQLite, which reads the whole
    // value again for each piece, does not go over a huge row many times.
    private const val PIECE = 1_000_000
    /** A reply still being written can change under the export; it is read again this many times. */
    private const val ATTEMPTS = 3

    /** A row changed while it was being read, so the file would mix two versions of it. */
    private class Changed : Exception()

    suspend fun export(context: Context, chatId: String): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, DIR).apply {
            // Only the newest export is kept; it is shared right away.
            deleteRecursively()
            mkdirs()
        }
        val safe = chatId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
        var attempt = 0
        while (true) {
            val file = File(folder, "orbit-chat-$safe-${System.currentTimeMillis()}.zip")
            try {
                ZipOutputStream(file.outputStream().buffered()).use { zip ->
                    // The rows as stored, then the other versions of replies (regenerated ones): the
                    // chat shows a reply's selected version, which may live only there.
                    writeTable(db, zip, "messages.jsonl", "messages", "messageId", "timestamp, orderIndex", chatId)
                    writeTable(db, zip, "message_variants.jsonl", "message_variants", "variantId", "messageTimestamp, variantIndex", chatId)
                    OrbitDevRecorder.copyTo(context, chatId, zip, "developer_view")
                }
                return@withContext file
            } catch (e: Changed) {
                file.delete()
                // Still changing after a few tries: fail rather than share a mixed copy.
                if (++attempt >= ATTEMPTS) throw IllegalStateException("chat kept changing during export")
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    /** Every row of [table] in [chatId], all fields, one JSON object per line, its text streamed in last. */
    private suspend fun writeTable(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        zip: ZipOutputStream,
        entry: String,
        table: String,
        idColumn: String,
        orderBy: String,
        chatId: String,
    ) {
        val columns = db.query("PRAGMA table_info($table)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        if (columns.isEmpty()) return
        // Every field but the text itself, which is read apart in pieces.
        val select = columns.joinToString(", ") {
            if (it == "content") "LENGTH(CAST(content AS BLOB)) AS content_bytes" else "`$it`"
        }
        zip.putNextEntry(ZipEntry(entry))
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        db.query("SELECT $select FROM $table WHERE chatId = ? ORDER BY $orderBy", arrayOf<Any?>(chatId)).use { cursor ->
            while (cursor.moveToNext()) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val row = JSONObject()
                var id = -1L
                var bytes = 0L
                for (i in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(i)
                    when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(i))
                        android.database.Cursor.FIELD_TYPE_BLOB -> row.put(name, "[blob ${cursor.getBlob(i).size} bytes]")
                        else -> row.put(name, cursor.getString(i))
                    }
                    if (name == idColumn) id = cursor.getLong(i)
                    if (name == "content_bytes") bytes = cursor.getLong(i)
                }
                // The text goes last and is streamed in, so the line is never built whole.
                val head = row.toString()
                writer.write(head, 0, head.length - 1)
                if (row.length() > 0) writer.write(",")
                writer.write("\"content\":")
                writeContent(db, table, idColumn, id, bytes, writer)
                writer.write("}\n")
            }
        }
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Writes a row's text as a JSON string, decoding its UTF-8 bytes piece by piece. Throws [Changed]
     * when the text read is not the [bytes] long it was when the row was read, or is no longer.
     */
    private suspend fun writeContent(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        idColumn: String,
        id: Long,
        bytes: Long,
        writer: java.io.Writer,
    ) {
        writer.write("\"")
        var read = 0L
        var pieces = 0
        val crc = java.util.zip.CRC32()
        if (id >= 0 && bytes > 0) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            // Small and sized to the row: decoding fills it again as often as a piece needs.
            val chars = java.nio.CharBuffer.allocate(minOf(bytes, 32_768L).toInt() + 8)
            var carry = ByteArray(0)
            var from = 1L
            while (from <= bytes) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val piece = db.query(
                    "SELECT SUBSTR(CAST(content AS BLOB), ?, ?) FROM $table WHERE $idColumn = ?",
                    arrayOf<Any?>(from, minOf(PIECE.toLong(), bytes - from + 1), id),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null } ?: break
                if (piece.isEmpty()) break
                crc.update(piece)
                pieces++
                val input = java.nio.ByteBuffer.wrap(if (carry.isEmpty()) piece else carry + piece)
                decodeInto(decoder, input, chars, endOfInput = false, writer)
                // A character split across two pieces waits for the rest of its bytes.
                carry = ByteArray(input.remaining()).also { input.get(it) }
                from += piece.size
                read += piece.size
            }
            decodeInto(decoder, java.nio.ByteBuffer.wrap(carry), chars, endOfInput = true, writer)
            chars.clear()
            decoder.flush(chars)
            chars.flip()
            escapeInto(chars, writer)
        }
        writer.write("\"")
        if (id >= 0) {
            val now = db.query("SELECT LENGTH(CAST(content AS BLOB)) FROM $table WHERE $idColumn = ?", arrayOf<Any?>(id))
                .use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
            if (read != bytes || now != bytes) throw Changed()
            // Read in more than one go: the text could have been rewritten to the same length in
            // between, so it is read once more and must match what was written.
            if (pieces > 1 && checksum(db, table, idColumn, id, bytes) != crc.value) throw Changed()
        }
    }

    /** The CRC32 of a row's text as it is now, read in the same pieces. */
    private suspend fun checksum(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        idColumn: String,
        id: Long,
        bytes: Long,
    ): Long {
        val crc = java.util.zip.CRC32()
        var from = 1L
        while (from <= bytes) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val piece = db.query(
                "SELECT SUBSTR(CAST(content AS BLOB), ?, ?) FROM $table WHERE $idColumn = ?",
                arrayOf<Any?>(from, minOf(PIECE.toLong(), bytes - from + 1), id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null } ?: break
            if (piece.isEmpty()) break
            crc.update(piece)
            from += piece.size
        }
        return crc.value
    }

    private fun decodeInto(
        decoder: java.nio.charset.CharsetDecoder,
        input: java.nio.ByteBuffer,
        chars: java.nio.CharBuffer,
        endOfInput: Boolean,
        writer: java.io.Writer,
    ) {
        while (true) {
            chars.clear()
            val result = decoder.decode(input, chars, endOfInput)
            chars.flip()
            escapeInto(chars, writer)
            if (!result.isOverflow) return
        }
    }

    private fun escapeInto(chars: java.nio.CharBuffer, writer: java.io.Writer) {
        while (chars.hasRemaining()) {
            when (val c = chars.get()) {
                '"' -> writer.write("\\\"")
                '\\' -> writer.write("\\\\")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                '\t' -> writer.write("\\t")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    writer.write("\\u")
                    writer.write(Integer.toHexString(c.code).padStart(4, '0'))
                } else {
                    writer.write(c.code)
                }
            }
        }
    }
}
