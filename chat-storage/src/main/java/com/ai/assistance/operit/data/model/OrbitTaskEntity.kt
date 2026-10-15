package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Orbit IDs live in preferences; deletion is coordinated by the application, not a cross-store FK. */
@Entity(tableName = "orbit_tasks", indices = [Index("orbitId")])
data class OrbitTaskEntity(
    @PrimaryKey val id: String,
    val orbitId: String,
    val chatId: String,
    val title: String,
    val instruction: String,
    /** URL_CHANGE, DAILY or ONCE. URL_CHANGE fetches an http(s) document using GET. */
    val kind: String,
    val source: String,
    val intervalMinutes: Int,
    val dailyMinute: Int,
    /** CHANGES or ALWAYS. */
    val notifyPolicy: String,
    /** ACTIVE, PAUSED, CANCELLED, DONE. */
    val state: String,
    val version: Long,
    val nextRunAt: Long,
    val lastObservation: String,
    val lastFingerprint: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "orbit_events", indices = [Index("taskId")])
data class OrbitEventEntity(
    /** Stable key: task + definition version + scheduled time, or explicit request id. */
    @PrimaryKey val id: String,
    val taskId: String,
    val taskVersion: Long,
    val dueAt: Long,
    val createdAt: Long,
)

@Entity(tableName = "orbit_runs", indices = [Index(value = ["eventId"], unique = true), Index("taskId")])
data class OrbitRunEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val taskId: String,
    val taskVersion: Long,
    /** RUNNING, COMPLETED, FAILED, CANCELLED. */
    val state: String,
    val owner: String,
    val generation: Long,
    /** Both elapsed time and wall time are used; a reboot invalidates the previous elapsed clock. */
    val bootId: String,
    val leaseUntilElapsed: Long,
    val leaseUntilWall: Long,
    /** Empty until a bounded source read was persisted. */
    val sourceText: String,
    val fingerprint: String,
    val hasSource: Boolean,
    val observation: String,
    val error: String,
    val startedAt: Long,
    val finishedAt: Long,
)

@Entity(tableName = "orbit_deliveries", indices = [Index(value = ["runId"], unique = true), Index("taskId")])
data class OrbitDeliveryEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val taskId: String,
    val taskVersion: Long,
    val orbitId: String,
    val chatId: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    /** PENDING, SENT, CANCELLED; messageId survives user deletion, so a message is never recreated. */
    val state: String,
    val messageId: Long?,
    val messageTimestamp: Long?,
    val notificationSent: Boolean,
)
