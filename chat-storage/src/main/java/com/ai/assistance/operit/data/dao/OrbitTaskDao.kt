package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ai.assistance.operit.data.model.*
import kotlinx.coroutines.flow.Flow

data class OrbitSourceMessage(val sender: String, val content: String)

@Dao
interface OrbitTaskDao {
    @Query("SELECT sender, CASE WHEN sender='ai' THEN SUBSTR(content,-3000) ELSE SUBSTR(content,1,3000) END AS content FROM messages WHERE chatId=:chatId AND sender IN ('user','ai') AND displayMode='NORMAL' ORDER BY orderIndex DESC LIMIT 12")
    suspend fun recentSource(chatId: String): List<OrbitSourceMessage>
    @Query("SELECT * FROM orbit_tasks WHERE orbitId = :orbitId ORDER BY createdAt DESC")
    fun observeTasks(orbitId: String): Flow<List<OrbitTaskEntity>>

    @Query("SELECT * FROM orbit_tasks WHERE orbitId = :orbitId ORDER BY createdAt DESC")
    suspend fun tasks(orbitId: String): List<OrbitTaskEntity>

    @Query("SELECT * FROM orbit_tasks WHERE id = :id")
    suspend fun task(id: String): OrbitTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: OrbitTaskEntity): Long

    @Update
    suspend fun updateTask(task: OrbitTaskEntity)

    @Query("SELECT * FROM orbit_events WHERE taskId = :taskId AND taskVersion = :version ORDER BY dueAt LIMIT 1")
    suspend fun pendingEvent(taskId: String, version: Long): OrbitEventEntity?

    @Query("SELECT * FROM orbit_runs WHERE eventId = :eventId")
    suspend fun eventRun(eventId: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE id = :id")
    suspend fun run(id: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestRun(taskId: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId AND state IN ('COMPLETED','FAILED') ORDER BY finishedAt DESC LIMIT 1")
    suspend fun lastFinishedRun(taskId: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestRun(taskId: String): Flow<OrbitRunEntity?>

    @Query("SELECT * FROM orbit_deliveries WHERE orbitId = :orbitId AND state = 'PENDING' ORDER BY createdAt")
    suspend fun pendingDeliveries(orbitId: String): List<OrbitDeliveryEntity>

    @Query("SELECT * FROM orbit_deliveries WHERE id = :id")
    suspend fun delivery(id: String): OrbitDeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: OrbitEventEntity): Long

    @Insert
    suspend fun insertRun(run: OrbitRunEntity)

    @Update
    suspend fun updateRun(run: OrbitRunEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: OrbitDeliveryEntity): Long

    @Update
    suspend fun updateDelivery(delivery: OrbitDeliveryEntity)

    /** The application supplies DAOs from the same database and holds its chat lock. */
    @Transaction
    suspend fun appendDelivery(deliveryId: String, now: Long, chats: ChatDao, messages: MessageDao): OrbitDeliveryEntity? {
        val delivery = delivery(deliveryId) ?: return null
        if (delivery.state != "PENDING") return null
        val task = task(delivery.taskId)
        val chat = chats.getChatById(delivery.chatId)
        if (task == null || task.version != delivery.taskVersion ||
            task.state !in setOf("ACTIVE", "DONE") || chat == null) {
            updateDelivery(delivery.copy(state = "CANCELLED"))
            return null
        }
        if (delivery.messageId != null) return delivery
        val timestamp = maxOf(now, (chat.lastMessageAt ?: 0L) + 1)
        val order = messages.getMaxOrderIndex(delivery.chatId)?.plus(1) ?: 0
        val id = messages.insertMessage(MessageEntity(
            chatId = delivery.chatId, sender = "ai", content = delivery.body,
            timestamp = timestamp, orderIndex = order,
        ))
        chats.advanceLastMessageAt(delivery.chatId, timestamp)
        chats.updateChatMetadata(chat.id, chat.title, timestamp,
            chat.inputTokens, chat.outputTokens, chat.currentWindowSize)
        return delivery.copy(messageId = id, messageTimestamp = timestamp).also { updateDelivery(it) }
    }

    @Query("DELETE FROM orbit_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("DELETE FROM orbit_events WHERE taskId = :taskId")
    suspend fun deleteTaskEvents(taskId: String)

    @Query("UPDATE orbit_runs SET state = 'CANCELLED', finishedAt = :now WHERE taskId = :taskId AND state = 'RUNNING'")
    suspend fun cancelRuns(taskId: String, now: Long)

    @Query("UPDATE orbit_deliveries SET state = 'CANCELLED' WHERE taskId = :taskId AND state = 'PENDING'")
    suspend fun cancelDeliveries(taskId: String)

    @Query("DELETE FROM orbit_deliveries WHERE orbitId = :orbitId")
    suspend fun deleteOrbitDeliveries(orbitId: String)

    @Query("DELETE FROM orbit_runs WHERE taskId IN (SELECT id FROM orbit_tasks WHERE orbitId = :orbitId)")
    suspend fun deleteOrbitRuns(orbitId: String)

    @Query("DELETE FROM orbit_events WHERE taskId IN (SELECT id FROM orbit_tasks WHERE orbitId = :orbitId)")
    suspend fun deleteOrbitEvents(orbitId: String)

    @Query("DELETE FROM orbit_tasks WHERE orbitId = :orbitId")
    suspend fun deleteOrbitTasks(orbitId: String)

    @Transaction
    suspend fun removeOrbit(orbitId: String) {
        deleteOrbitDeliveries(orbitId)
        deleteOrbitRuns(orbitId)
        deleteOrbitEvents(orbitId)
        deleteOrbitTasks(orbitId)
    }

    /** Pausing and edits fence out old workers and discard their unposted notifications. */
    @Transaction
    suspend fun replaceDefinition(updated: OrbitTaskEntity, expectedVersion: Long): Boolean {
        val current = task(updated.id) ?: return false
        if (current.version != expectedVersion) return false
        require(updated.orbitId == current.orbitId)
        updateTask(updated.copy(version = current.version + 1))
        cancelRuns(current.id, updated.updatedAt)
        cancelDeliveries(current.id)
        deleteTaskEvents(current.id)
        return true
    }

    /** A WorkManager wake-up is only a hint. The durable task/event decides whether work is due. */
    @Transaction
    suspend fun enqueueDue(taskId: String, now: Long): OrbitEventEntity? {
        val current = task(taskId)?.takeIf { it.state == "ACTIVE" } ?: return null
        pendingEvent(current.id, current.version)?.let { return it }
        if (current.nextRunAt > now) return null
        val event = OrbitEventEntity("${current.id}:${current.version}:${current.nextRunAt}",
            current.id, current.version, current.nextRunAt, now)
        insertEvent(event)
        return event
    }

    @Transaction
    suspend fun enqueueManual(taskId: String, requestId: String, now: Long): Boolean {
        val current = task(taskId)?.takeIf { it.state == "ACTIVE" } ?: return false
        val eventId = "${current.id}:${current.version}:manual:$requestId"
        // The completed run retains this key after the pending event is removed.
        if (eventRun(eventId) != null) return false
        return insertEvent(OrbitEventEntity(eventId, current.id, current.version, now, now)) != -1L
    }

    @Transaction
    suspend fun claim(
        event: OrbitEventEntity, owner: String, bootId: String,
        now: Long, elapsed: Long, leaseMs: Long,
    ): OrbitRunEntity? {
        val task = task(event.taskId)?.takeIf { it.state == "ACTIVE" && it.version == event.taskVersion }
            ?: return null
        if (pendingEvent(task.id, task.version)?.id != event.id) return null
        val old = eventRun(event.id)
        if (old != null && (old.state != "RUNNING" ||
                (old.bootId == bootId && old.leaseUntilElapsed > elapsed && old.leaseUntilWall > now))) return null
        val run = if (old == null) OrbitRunEntity(
            id = event.id, eventId = event.id, taskId = task.id, taskVersion = task.version,
            state = "RUNNING", owner = owner, generation = 1, bootId = bootId,
            leaseUntilElapsed = elapsed + leaseMs, leaseUntilWall = now + leaseMs,
            sourceText = "", fingerprint = "", hasSource = false, observation = "", error = "",
            startedAt = now, finishedAt = 0,
        ) else old.copy(owner = owner, generation = old.generation + 1, bootId = bootId,
            leaseUntilElapsed = elapsed + leaseMs, leaseUntilWall = now + leaseMs)
        if (old == null) insertRun(run) else updateRun(run)
        return run
    }

    @Transaction
    suspend fun currentLease(token: OrbitRunEntity, bootId: String, now: Long, elapsed: Long): OrbitRunEntity? {
        val current = run(token.id) ?: return null
        val task = task(current.taskId) ?: return null
        return current.takeIf {
            task.state == "ACTIVE" && task.version == token.taskVersion && it.state == "RUNNING" &&
                it.owner == token.owner && it.generation == token.generation && it.bootId == bootId &&
                it.leaseUntilElapsed > elapsed && it.leaseUntilWall > now
        }
    }

    @Transaction
    suspend fun renew(token: OrbitRunEntity, bootId: String, now: Long, elapsed: Long, leaseMs: Long): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        updateRun(current.copy(leaseUntilElapsed = elapsed + leaseMs, leaseUntilWall = now + leaseMs))
        return true
    }

    @Transaction
    suspend fun checkpoint(token: OrbitRunEntity, text: String, fingerprint: String,
        bootId: String, now: Long, elapsed: Long): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        updateRun(current.copy(sourceText = text, fingerprint = fingerprint, hasSource = true))
        return true
    }

    /** Result + next schedule + delivery form one commit. Recovery never reruns a submitted result. */
    @Transaction
    suspend fun complete(token: OrbitRunEntity, observation: String, message: String, error: String,
        nextRunAt: Long, bootId: String, now: Long, elapsed: Long): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        val task = checkNotNull(task(current.taskId))
        val repeatFailure = error.isNotEmpty() && lastFinishedRun(task.id)?.error == error
        updateRun(current.copy(state = if (error.isEmpty()) "COMPLETED" else "FAILED", sourceText = "",
            observation = observation, error = error, finishedAt = now))
        updateTask(task.copy(nextRunAt = nextRunAt, updatedAt = now,
            state = if (task.kind == "ONCE") "DONE" else task.state,
            lastObservation = if (error.isEmpty()) observation else task.lastObservation,
            lastFingerprint = if (error.isEmpty()) current.fingerprint else task.lastFingerprint))
        if (message.isNotBlank() && (!repeatFailure || task.notifyPolicy == "ALWAYS")) insertDelivery(OrbitDeliveryEntity(
            id = current.id, runId = current.id, taskId = task.id, taskVersion = task.version,
            orbitId = task.orbitId, chatId = task.chatId, title = task.title, body = message,
            createdAt = now, state = "PENDING", messageId = null, messageTimestamp = null, notificationSent = false,
        ))
        deleteEvent(current.eventId)
        return true
    }
}
