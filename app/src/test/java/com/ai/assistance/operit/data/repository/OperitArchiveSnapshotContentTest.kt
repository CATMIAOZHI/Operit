package com.ai.assistance.operit.data.repository

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class OperitArchiveSnapshotContentTest {
    @Test
    fun blobChunkingPreservesEmbeddedNulAndSplitUtf8() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE archive_content (id INTEGER PRIMARY KEY, content TEXT NOT NULL)")
            }
            val messageContent = "m".repeat(65_535) + "你\u0000message-tail"
            val variantContent = "v".repeat(65_534) + "🙂\u0000variant-tail"
            insertContent(connection, 1L, messageContent)
            insertContent(connection, 2L, variantContent)

            assertEquals(messageContent, readContent(connection, 1L))
            assertEquals(variantContent, readContent(connection, 2L))
        }
    }

    private fun insertContent(connection: Connection, id: Long, content: String) {
        connection.prepareStatement("INSERT INTO archive_content(id, content) VALUES (?, ?)").use {
            it.setLong(1, id)
            it.setString(2, content)
            it.executeUpdate()
        }
    }

    private fun readContent(connection: Connection, id: Long): String {
        val initial =
            connection.prepareStatement(
                "SELECT $OPERIT_ARCHIVE_CONTENT_INITIAL_PROJECTION " +
                    "FROM archive_content WHERE id = ?"
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getBytes("contentChunkBytes") to result.getLong("contentByteCount")
                }
            }

        return materializeOperitArchiveUtf8Content(
            initialChunk = initial.first,
            contentByteCount = initial.second,
            entityDescription = "archive_content id=$id",
        ) { startByte, byteCount ->
            connection.prepareStatement(
                "SELECT $OPERIT_ARCHIVE_CONTENT_CHUNK_EXPRESSION " +
                    "FROM archive_content WHERE id = ?"
            ).use { statement ->
                statement.setLong(1, startByte)
                statement.setInt(2, byteCount)
                statement.setLong(3, id)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getBytes(1) else null
                }
            }
        }
    }
}
