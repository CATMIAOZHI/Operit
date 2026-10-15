package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** No raw arguments, credentials or tool output: the receipt records only host-approved facts. */
@Entity(tableName = "orbit_actions", indices = [Index("taskId"), Index("runId")])
data class OrbitActionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val runId: String,
    val generation: Long,
    val toolName: String,
    /** A digest of the canonical operation; identical retries in one run share this identity. */
    val operationHash: String,
    /** PREPARED, STARTED, CONFIRMED, UNKNOWN, VERIFIED, RETRY_ALLOWED. */
    val state: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Host identity follows delegated chats; model parameters cannot choose a different run. */
@Entity(tableName = "orbit_task_chats", indices = [Index("runId")])
data class OrbitTaskChatEntity(
    @PrimaryKey val chatId: String,
    val runId: String,
    val generation: Long,
    val rootChatId: String,
)

/** User input is a checkpoint, not a message fabricated by a background model. */
@Entity(tableName = "orbit_task_inputs")
data class OrbitTaskInputEntity(
    @PrimaryKey val taskId: String,
    val taskVersion: Long,
    val requestedUrl: String = "",
    val sessionId: String = "",
    val userNote: String = "",
    val pageObservation: String = "",
)
