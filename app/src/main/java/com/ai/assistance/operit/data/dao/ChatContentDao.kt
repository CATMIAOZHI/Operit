package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import java.io.ByteArrayOutputStream

// Read UTF-8 as BLOB chunks: SQLite text LENGTH/SUBSTR stop at embedded U+0000, while byte chunks
// preserve the complete value and still keep every returned row well below CursorWindow size.
private const val CONTENT_CHUNK_BYTE_COUNT = 65_536

private const val MESSAGE_CONTENT_ROW_QUERY =
    """
    SELECT
        messageId,
        chatId,
        sender,
        '' AS content,
        timestamp,
        orderIndex,
        roleName,
        selectedVariantIndex,
        provider,
        modelName,
        inputTokens,
        outputTokens,
        cachedInputTokens,
        sentAt,
        outputDurationMs,
        waitDurationMs,
        completedAt,
        displayMode,
        isFavorite,
        SUBSTR(CAST(content AS BLOB), 1, $CONTENT_CHUNK_BYTE_COUNT) AS contentChunkBytes,
        LENGTH(CAST(content AS BLOB)) AS contentByteCount
    FROM messages
    """

private const val MESSAGE_VARIANT_CONTENT_ROW_QUERY =
    """
    SELECT
        variantId,
        chatId,
        messageTimestamp,
        variantIndex,
        '' AS content,
        roleName,
        provider,
        modelName,
        inputTokens,
        outputTokens,
        cachedInputTokens,
        sentAt,
        outputDurationMs,
        waitDurationMs,
        completedAt,
        SUBSTR(CAST(content AS BLOB), 1, $CONTENT_CHUNK_BYTE_COUNT) AS contentChunkBytes,
        LENGTH(CAST(content AS BLOB)) AS contentByteCount
    FROM message_variants
    """

data class MessageContentRow(
    @Embedded val message: MessageEntity,
    val contentChunkBytes: ByteArray,
    val contentByteCount: Long,
)

data class MessageVariantContentRow(
    @Embedded val variant: MessageVariantEntity,
    val contentChunkBytes: ByteArray,
    val contentByteCount: Long,
)

/** Reads message text in bounded rows so a single large message cannot overflow CursorWindow. */
@Dao
abstract class ChatContentDao {
    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp ASC")
    protected abstract suspend fun queryMessagesForChat(chatId: String): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp >= :startTimestampInclusive ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatFromTimestampAsc(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp >= :startTimestampInclusive" +
            " AND timestamp <= :endTimestampInclusive ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatWindowAsc(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<MessageContentRow>

    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit")
    protected abstract suspend fun queryMessagesForChatAsc(
        chatId: String,
        limit: Int,
    ): List<MessageContentRow>

    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    protected abstract suspend fun queryMessagesForChatDesc(
        chatId: String,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset"
    )
    protected abstract suspend fun queryMessagesForChatAscRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset"
    )
    protected abstract suspend fun queryMessagesForChatDescRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp > :afterTimestampExclusive" +
            " ORDER BY timestamp ASC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatAfterTimestampExclusiveAsc(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId" +
            " AND (:afterTimestampExclusive IS NULL OR timestamp > :afterTimestampExclusive)" +
            " AND (:beforeTimestampExclusive IS NULL OR timestamp < :beforeTimestampExclusive)" +
            " AND (:upToTimestampInclusive IS NULL OR timestamp <= :upToTimestampInclusive)" +
            " ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatInRangeAsc(
        chatId: String,
        afterTimestampExclusive: Long?,
        beforeTimestampExclusive: Long?,
        upToTimestampInclusive: Long?,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp <= :maxTimestamp" +
            " ORDER BY timestamp DESC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatBeforeTimestampDesc(
        chatId: String,
        maxTimestamp: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp < :beforeTimestampExclusive" +
            " ORDER BY timestamp DESC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatBeforeTimestampExclusiveDesc(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp = :timestamp LIMIT 1"
    )
    protected abstract suspend fun queryMessageByTimestamp(
        chatId: String,
        timestamp: Long,
    ): MessageContentRow?

    @Query(
        "SELECT SUBSTR(CAST(content AS BLOB), :startByte, :byteCount)" +
            " FROM messages WHERE messageId = :messageId"
    )
    protected abstract suspend fun queryMessageContentChunk(
        messageId: Long,
        startByte: Long,
        byteCount: Int,
    ): ByteArray?

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY messageTimestamp ASC, variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForChat(chatId: String): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND messageTimestamp IN (:messageTimestamps)" +
            " ORDER BY messageTimestamp ASC, variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForMessages(
        chatId: String,
        messageTimestamps: List<Long>,
    ): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND messageTimestamp = :messageTimestamp" +
            " ORDER BY variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForMessage(
        chatId: String,
        messageTimestamp: Long,
    ): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND messageTimestamp = :messageTimestamp" +
            " AND variantIndex = :variantIndex LIMIT 1"
    )
    protected abstract suspend fun queryVariantForMessage(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ): MessageVariantContentRow?

    @Query(
        "SELECT SUBSTR(CAST(content AS BLOB), :startByte, :byteCount)" +
            " FROM message_variants WHERE variantId = :variantId"
    )
    protected abstract suspend fun queryMessageVariantContentChunk(
        variantId: Long,
        startByte: Long,
        byteCount: Int,
    ): ByteArray?

    @Transaction
    open suspend fun getMessagesForChat(chatId: String): List<MessageEntity> =
        materializeMessages(queryMessagesForChat(chatId))

    @Transaction
    open suspend fun getMessagesForChatFromTimestampAsc(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<MessageEntity> =
        materializeMessages(queryMessagesForChatFromTimestampAsc(chatId, startTimestampInclusive))

    @Transaction
    open suspend fun getMessagesForChatWindowAsc(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatWindowAsc(chatId, startTimestampInclusive, endTimestampInclusive)
        )

    @Transaction
    open suspend fun getMessagesForChatAsc(chatId: String, limit: Int): List<MessageEntity> =
        materializeMessages(queryMessagesForChatAsc(chatId, limit))

    @Transaction
    open suspend fun getMessagesForChatDesc(chatId: String, limit: Int): List<MessageEntity> =
        materializeMessages(queryMessagesForChatDesc(chatId, limit))

    @Transaction
    open suspend fun getMessagesForChatAscRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> = materializeMessages(queryMessagesForChatAscRange(chatId, offset, limit))

    @Transaction
    open suspend fun getMessagesForChatDescRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> = materializeMessages(queryMessagesForChatDescRange(chatId, offset, limit))

    @Transaction
    open suspend fun getMessagesForChatAfterTimestampExclusiveAsc(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatAfterTimestampExclusiveAsc(chatId, afterTimestampExclusive, limit)
        )

    @Transaction
    open suspend fun getMessagesForChatInRangeAsc(
        chatId: String,
        afterTimestampExclusive: Long?,
        beforeTimestampExclusive: Long?,
        upToTimestampInclusive: Long?,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatInRangeAsc(
                chatId,
                afterTimestampExclusive,
                beforeTimestampExclusive,
                upToTimestampInclusive,
            )
        )

    @Transaction
    open suspend fun getMessagesForChatBeforeTimestampDesc(
        chatId: String,
        maxTimestamp: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(queryMessagesForChatBeforeTimestampDesc(chatId, maxTimestamp, limit))

    @Transaction
    open suspend fun getMessagesForChatBeforeTimestampExclusiveDesc(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatBeforeTimestampExclusiveDesc(chatId, beforeTimestampExclusive, limit)
        )

    @Transaction
    open suspend fun getMessageByTimestamp(chatId: String, timestamp: Long): MessageEntity? =
        queryMessageByTimestamp(chatId, timestamp)?.let { materializeMessage(it) }

    @Transaction
    open suspend fun getVariantsForChat(chatId: String): List<MessageVariantEntity> =
        materializeVariants(queryVariantsForChat(chatId))

    @Transaction
    open suspend fun getVariantsForMessages(
        chatId: String,
        messageTimestamps: List<Long>,
    ): List<MessageVariantEntity> =
        materializeVariants(queryVariantsForMessages(chatId, messageTimestamps))

    @Transaction
    open suspend fun getVariantsForMessage(
        chatId: String,
        messageTimestamp: Long,
    ): List<MessageVariantEntity> =
        materializeVariants(queryVariantsForMessage(chatId, messageTimestamp))

    @Transaction
    open suspend fun getVariantForMessage(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ): MessageVariantEntity? =
        queryVariantForMessage(chatId, messageTimestamp, variantIndex)?.let {
            materializeVariant(it)
        }

    private suspend fun materializeMessages(rows: List<MessageContentRow>): List<MessageEntity> =
        rows.map { materializeMessage(it) }

    private suspend fun materializeMessage(row: MessageContentRow): MessageEntity {
        val messageId = row.message.messageId
        val content = materializeUtf8Content(
            initialChunk = row.contentChunkBytes,
            contentByteCount = row.contentByteCount,
            missingMessage = "Message disappeared while reading content: messageId=$messageId",
            truncatedMessage = "Message content ended before its recorded length: messageId=$messageId",
        ) { startByte ->
            queryMessageContentChunk(messageId, startByte, CONTENT_CHUNK_BYTE_COUNT)
        }
        return row.message.copy(content = content)
    }

    private suspend fun materializeVariants(
        rows: List<MessageVariantContentRow>
    ): List<MessageVariantEntity> = rows.map { materializeVariant(it) }

    private suspend fun materializeVariant(row: MessageVariantContentRow): MessageVariantEntity {
        val variantId = row.variant.variantId
        val content = materializeUtf8Content(
            initialChunk = row.contentChunkBytes,
            contentByteCount = row.contentByteCount,
            missingMessage = "Message variant disappeared while reading content: variantId=$variantId",
            truncatedMessage = "Message variant content ended before its recorded length: variantId=$variantId",
        ) { startByte ->
            queryMessageVariantContentChunk(variantId, startByte, CONTENT_CHUNK_BYTE_COUNT)
        }
        return row.variant.copy(content = content)
    }

    private suspend fun materializeUtf8Content(
        initialChunk: ByteArray,
        contentByteCount: Long,
        missingMessage: String,
        truncatedMessage: String,
        readChunk: suspend (startByte: Long) -> ByteArray?,
    ): String {
        if (contentByteCount <= initialChunk.size.toLong()) {
            return initialChunk.toString(Charsets.UTF_8)
        }

        val content = ByteArrayOutputStream(initialChunk.size * 2)
        content.write(initialChunk)
        var startByte = initialChunk.size.toLong() + 1L
        while (startByte <= contentByteCount) {
            val chunk = checkNotNull(readChunk(startByte)) { missingMessage }
            check(chunk.isNotEmpty()) { truncatedMessage }
            content.write(chunk)
            startByte += chunk.size
        }
        check(content.size().toLong() == contentByteCount) { truncatedMessage }
        return content.toByteArray().toString(Charsets.UTF_8)
    }
}
