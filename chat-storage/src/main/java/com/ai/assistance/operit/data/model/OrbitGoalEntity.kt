package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "orbit_goals", indices = [Index("orbitId")])
data class OrbitGoalEntity(
    @PrimaryKey val id: String,
    val orbitId: String,
    val title: String,
    val plan: String,
    val state: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "orbit_artifact_versions", indices = [Index("orbitId"), Index("artifactId"), Index(value = ["actionId"], unique = true)])
data class OrbitArtifactVersionEntity(
    @PrimaryKey val id: String,
    val artifactId: String,
    val orbitId: String,
    val taskId: String,
    val runId: String,
    val actionId: String,
    val name: String,
    val relativePath: String,
    val sha256: String,
    val byteCount: Long,
    val createdAt: Long,
)

@Entity(tableName = "orbit_notifications")
data class OrbitNotificationEntity(
    @PrimaryKey val orbitId: String,
    val quietEnabled: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 8 * 60,
    val intervalMinutes: Int = 15,
    val digestMinute: Int = 9 * 60,
    val lastDeliveredAt: Long = 0,
)
