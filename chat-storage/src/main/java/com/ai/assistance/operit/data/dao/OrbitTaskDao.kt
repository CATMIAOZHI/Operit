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

/** One message of an Orbit's chats inside a review window, with what points back to it. */
data class OrbitWindowMessage(
    val messageId: Long,
    val chatId: String,
    val sender: String,
    val content: String,
    val timestamp: Long,
    /** True when [content] is only part of the message. */
    val truncated: Boolean,
)

/** When a task's next run will really happen: its waiting event, which may have been put off. */
data class OrbitTaskDue(val taskId: String, val dueAt: Long)

/** One run of an Orbit task, with its task title, for the profile's activity list. */
data class OrbitActivityRow(
    val id: String,
    val taskId: String,
    val title: String,
    val kind: String,
    val state: String,
    val observation: String,
    val error: String,
    val startedAt: Long,
    val finishedAt: Long,
    val generation: Long,
)

/** One recorded outward action of an Orbit task, for the profile's approval list. */
data class OrbitActionRow(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val toolName: String,
    val state: String,
    val summary: String,
    val updatedAt: Long,
)

@Dao
interface OrbitTaskDao {
    @Query("SELECT r.id AS id, r.taskId AS taskId, t.title AS title, t.kind AS kind, r.state AS state, SUBSTR(r.observation,1,400) AS observation, SUBSTR(r.error,1,400) AS error, r.startedAt AS startedAt, r.finishedAt AS finishedAt, r.generation AS generation FROM orbit_runs r INNER JOIN orbit_tasks t ON t.id = r.taskId WHERE t.orbitId = :orbitId AND r.decision NOT IN ('" + OrbitRunEntity.UNCHANGED + "', '" + OrbitRunEntity.SKIPPED + "', '" + OrbitRunEntity.IN_CHAT + "') AND r.decision NOT LIKE '@in_chat:%' ORDER BY r.startedAt DESC LIMIT 200")
    fun observeActivity(orbitId: String): Flow<List<OrbitActivityRow>>
    /** A sent turn cannot be replayed; enrich only that completed run after its chat reply ends. */
    @Query("UPDATE orbit_runs SET decision=:decision WHERE id=:runId AND generation=:generation AND owner=:owner AND state='COMPLETED' AND decision='" + OrbitRunEntity.IN_CHAT + "'")
    suspend fun recordChatDecision(runId: String, generation: Long, owner: String, decision: String): Int
    @Query("SELECT a.id AS id, a.taskId AS taskId, t.title AS taskTitle, a.toolName AS toolName, a.state AS state, a.summary AS summary, a.updatedAt AS updatedAt FROM orbit_actions a INNER JOIN orbit_tasks t ON t.id = a.taskId WHERE t.orbitId = :orbitId ORDER BY a.updatedAt DESC LIMIT 200")
    fun observeOrbitActions(orbitId: String): Flow<List<OrbitActionRow>>
    @Query("SELECT * FROM orbit_notifications WHERE orbitId=:orbitId")
    suspend fun notificationPolicy(orbitId: String): OrbitNotificationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNotificationPolicy(policy: OrbitNotificationEntity)
    @Query("DELETE FROM orbit_notifications WHERE orbitId=:orbitId")
    suspend fun deleteNotificationPolicy(orbitId: String)

    /** One message for the selected due results; every member keeps its own Feed content. */
    @Transaction
    suspend fun appendDeliveryBatch(ids: List<String>, now: Long, chats: ChatDao, messages: MessageDao,
        roleName: String = ""): List<OrbitDeliveryEntity> {
        val members = validPending(ids)
        if (members.isEmpty()) return emptyList()
        val first = members.first()
        require(members.all { it.chatId == first.chatId && it.orbitId == first.orbitId })
        val saved = members.filter { it.messageId != null }
        if (saved.isNotEmpty()) return saved // Retry the committed batch before inserting another one.
        val chat = chats.getChatById(first.chatId) ?: return emptyList()
        val timestamp = maxOf(now, (chat.lastMessageAt ?: 0) + 1)
        val body = if (members.size == 1) first.body else members.joinToString("\n\n---\n\n") { "${it.title}\n\n${it.body}" }
        // Signed with Orbit's name like its other messages, so replying to it quotes a real sender.
        val id = messages.insertMessage(MessageEntity(chatId = first.chatId, sender = "ai", content = body, roleName = roleName,
            timestamp = timestamp, orderIndex = messages.getMaxOrderIndex(first.chatId)?.plus(1) ?: 0))
        chats.advanceLastMessageAt(first.chatId, timestamp)
        chats.updateChatMetadata(chat.id, chat.title, timestamp, chat.inputTokens, chat.outputTokens, chat.currentWindowSize)
        val policy = notificationPolicy(first.orbitId) ?: OrbitNotificationEntity(first.orbitId)
        saveNotificationPolicy(policy.copy(lastDeliveredAt = now))
        return members.map { it.copy(messageId = id, messageTimestamp = timestamp).also { row -> updateDelivery(row) } }
    }

    /** The reports of [ids] still worth telling; a report of a replaced or cancelled task is dropped. */
    @Transaction
    suspend fun validPending(ids: List<String>): List<OrbitDeliveryEntity> =
        ids.distinct().mapNotNull { delivery(it) }.filter {
            val task = task(it.taskId)
            val valid = task != null && task.version == it.taskVersion &&
                task.state in setOf("ACTIVE", "DONE", "WAITING_USER", "VERIFY", "PAUSED")
            if (!valid && it.state == "PENDING") updateDelivery(it.copy(state = "CANCELLED"))
            // A draft that ran out while it waited for a quiet chat is moot and never told.
            val now = System.currentTimeMillis()
            val expired = it.expiresAt in 1..now
            if (valid && expired && it.state == "PENDING") updateDelivery(it.copy(state = "CANCELLED",
                verdict = OrbitDeliveryEntity.SKIP, verdictReason = "moot", verdictNote = OrbitDeliveryEntity.EXPIRED,
                decidedAt = now))
            valid && !expired && it.state == "PENDING"
        }
    @Query("SELECT * FROM orbit_goals WHERE orbitId=:orbitId ORDER BY updatedAt DESC")
    fun observeGoals(orbitId: String): Flow<List<OrbitGoalEntity>>
    @Query("SELECT * FROM orbit_goals WHERE orbitId=:orbitId ORDER BY updatedAt DESC")
    suspend fun goals(orbitId: String): List<OrbitGoalEntity>
    @Query("SELECT * FROM orbit_goals WHERE id=:id")
    suspend fun goal(id: String): OrbitGoalEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGoal(goal: OrbitGoalEntity)
    @Update
    suspend fun updateGoal(goal: OrbitGoalEntity)
    @Transaction
    suspend fun saveGoal(updated: OrbitGoalEntity, expectedVersion: Long): Boolean {
        val current = goal(updated.id) ?: return false
        if (current.version != expectedVersion || current.orbitId != updated.orbitId) return false
        updateGoal(updated.copy(version = expectedVersion + 1))
        if (updated.state != "ACTIVE") tasks(updated.orbitId).filter {
            it.goalId == updated.id && it.state !in setOf("CANCELLED", "DONE")
        }.forEach {
            val state = if (updated.state == "CANCELLED") "CANCELLED" else "PAUSED"
            if (it.state != "WAITING_USER" || state == "CANCELLED")
                replaceDefinition(it.copy(state = state, updatedAt = updated.updatedAt), it.version)
        }
        return true
    }
    @Query("DELETE FROM orbit_goals WHERE orbitId=:orbitId")
    suspend fun deleteGoals(orbitId: String)

    @Query("SELECT * FROM orbit_deliveries WHERE orbitId=:orbitId AND state != 'CANCELLED' ORDER BY createdAt DESC LIMIT 200")
    fun observeFeed(orbitId: String): Flow<List<OrbitDeliveryEntity>>
    @Query("SELECT * FROM orbit_artifact_versions WHERE orbitId=:orbitId ORDER BY createdAt DESC LIMIT 300")
    fun observeArtifacts(orbitId: String): Flow<List<OrbitArtifactVersionEntity>>
    @Query("SELECT * FROM orbit_artifact_versions WHERE id=:id")
    suspend fun artifact(id: String): OrbitArtifactVersionEntity?
    @Query("SELECT * FROM orbit_artifact_versions WHERE orbitId=:orbitId ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun artifacts(orbitId: String, limit: Int, offset: Int): List<OrbitArtifactVersionEntity>
    @Query("SELECT COUNT(*) FROM orbit_artifact_versions WHERE orbitId=:orbitId")
    suspend fun artifactCount(orbitId: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtifact(artifact: OrbitArtifactVersionEntity)
    @Query("DELETE FROM orbit_artifact_versions WHERE orbitId=:orbitId")
    suspend fun deleteArtifacts(orbitId: String)

    @Transaction
    suspend fun confirmArtifact(artifact: OrbitArtifactVersionEntity, generation: Long, summary: String): Boolean {
        val receipt = action(artifact.actionId) ?: return false
        val task = task(receipt.taskId) ?: return false
        require(task.orbitId == artifact.orbitId && task.id == artifact.taskId && receipt.runId == artifact.runId)
        val saved = artifact(artifact.id)
        if (saved != null) return saved == artifact && receipt.generation == generation &&
            receipt.state == "CONFIRMED"
        if (!confirmAction(receipt.id, generation, summary, artifact.createdAt)) return false
        insertArtifact(artifact)
        return true
    }

    @Transaction
    suspend fun verifyArtifact(artifact: OrbitArtifactVersionEntity, now: Long): Boolean {
        val receipt = action(artifact.actionId) ?: return false
        val task = task(receipt.taskId) ?: return false
        require(task.orbitId == artifact.orbitId && task.id == artifact.taskId && receipt.runId == artifact.runId)
        if (!resolveAction(task.id, receipt.id, true, now)) return false
        insertArtifact(artifact)
        return true
    }

    @Query("SELECT * FROM orbit_events WHERE id=:id")
    suspend fun event(id: String): OrbitEventEntity?
    @Query("SELECT * FROM orbit_tasks WHERE triggerTaskId=:taskId AND scheduleMode='EVENT' AND state='ACTIVE'")
    suspend fun subscribers(taskId: String): List<OrbitTaskEntity>

    @Query("SELECT * FROM orbit_task_inputs WHERE taskId=:taskId")
    suspend fun input(taskId: String): OrbitTaskInputEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveInput(input: OrbitTaskInputEntity)

    @Query("DELETE FROM orbit_task_inputs WHERE taskId IN (SELECT id FROM orbit_tasks WHERE orbitId=:orbitId)")
    suspend fun deleteOrbitInputs(orbitId: String)

    @Transaction
    suspend fun resumeWithInput(taskId: String, version: Long, note: String, observation: String, now: Long): Boolean {
        val task = task(taskId)?.takeIf { it.state == "WAITING_USER" && it.version == version } ?: return false
        if (task.goalId.isNotEmpty() && goal(task.goalId)?.state != "ACTIVE") return false
        if (unresolvedActions(taskId).isNotEmpty()) return false
        val sourceRunId = latestRun(taskId)?.let { event(it.eventId)?.sourceRunId }.orEmpty()
        updateTask(task.copy(state = "ACTIVE", nextRunAt = now, updatedAt = now, version = version + 1))
        cancelDeliveries(taskId)
        deleteTaskEvents(taskId)
        saveInput(OrbitTaskInputEntity(taskId, version + 1, userNote = note, pageObservation = observation))
        insertEvent(OrbitEventEntity("$taskId:${version + 1}:user-input", taskId, version + 1, now, now, sourceRunId))
        return true
    }

    @Query("SELECT * FROM orbit_task_chats WHERE chatId=:chatId")
    suspend fun binding(chatId: String): OrbitTaskChatEntity?

    @Query("SELECT * FROM orbit_task_chats WHERE runId=:runId AND generation=:generation")
    suspend fun bindings(runId: String, generation: Long): List<OrbitTaskChatEntity>

    @Query("SELECT * FROM orbit_actions WHERE taskId=:taskId ORDER BY updatedAt DESC LIMIT 50")
    fun observeActions(taskId: String): Flow<List<OrbitActionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(binding: OrbitTaskChatEntity)

    @Query("DELETE FROM orbit_task_chats WHERE runId IN (SELECT id FROM orbit_runs WHERE taskId IN (SELECT id FROM orbit_tasks WHERE orbitId=:orbitId))")
    suspend fun deleteOrbitBindings(orbitId: String)

    @Query("DELETE FROM orbit_task_chats WHERE chatId=:chatId")
    suspend fun deleteBinding(chatId: String)

    @Query("SELECT * FROM orbit_actions WHERE id=:id")
    suspend fun action(id: String): OrbitActionEntity?

    @Query("SELECT * FROM orbit_actions WHERE taskId=:taskId ORDER BY updatedAt DESC LIMIT 50")
    suspend fun actions(taskId: String): List<OrbitActionEntity>

    @Query("SELECT * FROM orbit_actions WHERE taskId=:taskId AND state IN ('STARTED','UNKNOWN') ORDER BY createdAt")
    suspend fun unresolvedActions(taskId: String): List<OrbitActionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAction(action: OrbitActionEntity)

    @Update
    suspend fun updateAction(action: OrbitActionEntity)

    @Query("UPDATE orbit_actions SET state='UNKNOWN', updatedAt=:now WHERE taskId=:taskId AND state='STARTED'")
    suspend fun markUnreceiptedUnknown(taskId: String, now: Long)

    @Query("DELETE FROM orbit_actions WHERE taskId IN (SELECT id FROM orbit_tasks WHERE orbitId=:orbitId)")
    suspend fun deleteOrbitActions(orbitId: String)

    /** Persist the start before invoking anything with effects. A completed identity never repeats. */
    @Transaction
    suspend fun beginAction(token: OrbitRunEntity, proposed: OrbitActionEntity,
        bootId: String, now: Long, elapsed: Long): OrbitActionEntity? {
        if (currentLease(token, bootId, now, elapsed) == null) return null
        require(proposed.runId == token.id && proposed.taskId == token.taskId && proposed.generation == token.generation)
        val previous = action(proposed.id)
        if (previous != null) return previous.takeIf { it.state in setOf("CONFIRMED", "VERIFIED") }
        if (unresolvedActions(token.taskId).isNotEmpty()) return null
        return proposed.copy(state = "STARTED", createdAt = now, updatedAt = now).also { insertAction(it) }
    }

    /** Record a late successful fact even after a pause; it does not authorize further work. */
    @Transaction
    suspend fun confirmAction(id: String, generation: Long, summary: String, now: Long): Boolean {
        val current = action(id) ?: return false
        if (current.generation != generation || current.state !in setOf("STARTED", "UNKNOWN")) return false
        updateAction(current.copy(state = "CONFIRMED", summary = summary.take(2000), updatedAt = now))
        restoreWaitingAfterVerification(current.taskId, now)
        return true
    }

    @Transaction
    suspend fun uncertainAction(id: String, generation: Long, now: Long) {
        val current = action(id) ?: return
        if (current.generation != generation || current.state != "STARTED") return
        updateAction(current.copy(state = "UNKNOWN", updatedAt = now))
        val task = task(current.taskId) ?: return
        if (task.state == "ACTIVE") updateTask(task.copy(state = "VERIFY", updatedAt = now))
        run(current.runId)?.takeIf { it.state == "RUNNING" }?.let {
            updateRun(it.copy(state = "UNKNOWN", finishedAt = now))
        }
    }

    /** Only a user-facing host action calls this; it is deliberately absent from model tools. */
    @Transaction
    suspend fun resolveAction(taskId: String, id: String, completed: Boolean, now: Long): Boolean {
        val current = action(id) ?: return false
        if (current.taskId != taskId || current.state != "UNKNOWN") return false
        updateAction(current.copy(state = if (completed) "VERIFIED" else "RETRY_ALLOWED", updatedAt = now))
        restoreWaitingAfterVerification(taskId, now)
        return true
    }

    @Transaction
    suspend fun restoreWaitingAfterVerification(taskId: String, now: Long) {
        val task = task(taskId)
        if (task?.state == "VERIFY" && input(taskId)?.taskVersion == task.version &&
            latestRun(taskId)?.state == "WAITING_USER" && unresolvedActions(taskId).isEmpty()) {
            updateTask(task.copy(state = "WAITING_USER", updatedAt = now))
        }
    }
    @Transaction
    suspend fun waitForUser(token: OrbitRunEntity, reason: String, bootId: String, now: Long, elapsed: Long,
        browserUrl: String = ""): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        val task = checkNotNull(task(token.taskId))
        updateRun(current.copy(state = "WAITING_USER", error = reason.take(2000), finishedAt = now, sourceText = ""))
        markUnreceiptedUnknown(task.id, now)
        updateTask(task.copy(state = if (unresolvedActions(task.id).isEmpty()) "WAITING_USER" else "VERIFY", updatedAt = now))
        saveInput(OrbitTaskInputEntity(task.id, task.version, requestedUrl = browserUrl))
        insertDelivery(OrbitDeliveryEntity(
            id = current.id, runId = current.id, taskId = task.id, taskVersion = task.version,
            orbitId = task.orbitId, chatId = task.chatId, title = task.title, body = reason.take(2000),
            createdAt = now, state = "PENDING", messageId = null, messageTimestamp = null, notificationSent = false,
        ))
        return true
    }

    @Query("SELECT sender, CASE WHEN sender='ai' THEN SUBSTR(content,-3000) ELSE SUBSTR(content,1,3000) END AS content FROM messages WHERE chatId=:chatId AND sender IN ('user','ai') AND displayMode='NORMAL' ORDER BY orderIndex DESC LIMIT 12")
    suspend fun recentSource(chatId: String): List<OrbitSourceMessage>
    @Query("SELECT * FROM orbit_runs WHERE taskId=:taskId AND id!=:exclude ORDER BY startedAt DESC LIMIT 1")
    suspend fun previousRun(taskId: String, exclude: String): OrbitRunEntity?
    @Query("SELECT sender, SUBSTR(content,-12000) AS content FROM messages WHERE chatId=:chatId ORDER BY orderIndex DESC LIMIT 20")
    suspend fun executionSource(chatId: String): List<OrbitSourceMessage>
    @Query("SELECT COUNT(*) FROM messages WHERE chatId=:chatId AND sender='user'")
    suspend fun userMessageCount(chatId: String): Int

    /**
     * The alignment review reads its window in order: the messages of [chatIds] in (since, until]
     * after the position ([afterTs], [afterId]), oldest first. A message over 256 KB comes back empty
     * and marked truncated, so one huge row cannot overflow the cursor; read it with [reviewMessageBytes].
     */
    @Query("SELECT messageId, chatId, sender, CASE WHEN LENGTH(CAST(content AS BLOB)) > 262144 THEN '' ELSE content END AS content, timestamp, LENGTH(CAST(content AS BLOB)) > 262144 AS truncated FROM messages WHERE chatId IN (:chatIds) AND sender IN ('user','ai') AND displayMode='NORMAL' AND timestamp > :since AND timestamp <= :until AND timestamp >= :afterTs AND (timestamp > :afterTs OR (timestamp = :afterTs AND messageId > :afterId)) ORDER BY timestamp, messageId LIMIT :limit")
    suspend fun reviewMessages(chatIds: List<String>, since: Long, until: Long, afterTs: Long, afterId: Long, limit: Int): List<OrbitWindowMessage>

    @Query("SELECT messageId, chatId, sender, CASE WHEN LENGTH(CAST(content AS BLOB)) > 262144 THEN '' ELSE content END AS content, timestamp, LENGTH(CAST(content AS BLOB)) > 262144 AS truncated FROM messages WHERE messageId = :messageId")
    suspend fun reviewMessage(messageId: Long): OrbitWindowMessage?

    /** 32 KB of a message's text from [byteOffset], for messages too large to read in one row. */
    @Query("SELECT SUBSTR(CAST(content AS BLOB), :byteOffset + 1, 32768) FROM messages WHERE messageId = :messageId")
    suspend fun reviewMessageBytes(messageId: Long, byteOffset: Long): ByteArray?

    @Query("SELECT COUNT(*) FROM messages WHERE chatId IN (:chatIds) AND sender IN (:senders) AND displayMode='NORMAL' AND timestamp > :since AND timestamp <= :until")
    suspend fun reviewCount(chatIds: List<String>, since: Long, until: Long, senders: List<String>): Int

    /** How many messages of the window come up to and including ([ts], [id]). */
    @Query("SELECT COUNT(*) FROM messages WHERE chatId IN (:chatIds) AND sender IN ('user','ai') AND displayMode='NORMAL' AND timestamp > :since AND timestamp <= :until AND (timestamp < :ts OR (timestamp = :ts AND messageId <= :id))")
    suspend fun reviewCountThrough(chatIds: List<String>, since: Long, until: Long, ts: Long, id: Long): Int

    /** The last run that ended well and really ran (not skipped); a review window starts where it began. */
    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId AND state = 'COMPLETED' AND decision != '" +
        OrbitRunEntity.SKIPPED + "' ORDER BY finishedAt DESC LIMIT 1")
    suspend fun lastCompletedRun(taskId: String): OrbitRunEntity?
    @Query("SELECT * FROM orbit_tasks WHERE orbitId = :orbitId ORDER BY createdAt DESC")
    fun observeTasks(orbitId: String): Flow<List<OrbitTaskEntity>>

    @Query("SELECT * FROM orbit_tasks WHERE orbitId = :orbitId ORDER BY createdAt DESC")
    suspend fun tasks(orbitId: String): List<OrbitTaskEntity>

    /** Live tasks with one exact title and schedule mode, e.g. the old daily feed task to retire. */
    @Query(
        "SELECT * FROM orbit_tasks WHERE orbitId = :orbitId AND kind = :kind AND scheduleMode = :scheduleMode " +
            "AND title = :title AND state NOT IN ('CANCELLED', 'DONE')",
    )
    suspend fun liveTasksTitled(orbitId: String, kind: String, scheduleMode: String, title: String): List<OrbitTaskEntity>

    @Query("SELECT * FROM orbit_tasks WHERE id = :id")
    suspend fun task(id: String): OrbitTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: OrbitTaskEntity): Long

    /** Subscribe and catch an already completed one-shot in the same transaction as completion. */
    @Transaction
    suspend fun insertSubscribedTask(task: OrbitTaskEntity, now: Long) {
        val source = if (task.scheduleMode == "EVENT") task(task.triggerTaskId) else null
        if (task.scheduleMode == "EVENT") require(source != null && source.orbitId == task.orbitId &&
            source.id != task.id && source.state != "CANCELLED")
        if (insertTask(task) == -1L) return
        if (source?.state == "DONE") {
            val completed = lastFinishedRun(source.id)?.takeIf { it.state == "COMPLETED" }
                ?: error("The predecessor did not complete successfully")
            insertEvent(OrbitEventEntity("${task.id}:${task.version}:completed:${completed.id}",
                task.id, task.version, now, now, completed.id))
        }
    }

    @Update
    suspend fun updateTask(task: OrbitTaskEntity)

    @Query("SELECT * FROM orbit_events WHERE taskId = :taskId AND taskVersion = :version ORDER BY dueAt LIMIT 1")
    suspend fun pendingEvent(taskId: String, version: Long): OrbitEventEntity?

    @Query("SELECT COUNT(*) FROM orbit_events WHERE taskId = :taskId AND taskVersion = :version AND id LIKE '%:manual:%'")
    suspend fun pendingManualCount(taskId: String, version: Long): Int

    @Query("SELECT e.taskId AS taskId, MIN(e.dueAt) AS dueAt FROM orbit_events e JOIN orbit_tasks t " +
        "ON t.id = e.taskId AND t.version = e.taskVersion WHERE t.orbitId = :orbitId GROUP BY e.taskId")
    fun observeTaskDues(orbitId: String): Flow<List<OrbitTaskDue>>

    @Query("SELECT * FROM orbit_runs WHERE eventId = :eventId")
    suspend fun eventRun(eventId: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId AND state = 'RUNNING' AND eventId != :eventId")
    suspend fun otherRunningRuns(taskId: String, eventId: String): List<OrbitRunEntity>

    @Query("SELECT * FROM orbit_runs WHERE id = :id")
    suspend fun run(id: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestRun(taskId: String): OrbitRunEntity?

    @Query("SELECT COUNT(*) FROM orbit_runs WHERE taskId = :taskId AND state = 'RUNNING'")
    suspend fun runningCount(taskId: String): Int

    @Query("UPDATE orbit_events SET dueAt = :dueAt WHERE id = :id")
    suspend fun setEventDue(id: String, dueAt: Long)

    @Query("DELETE FROM orbit_runs WHERE id = :id")
    suspend fun deleteRun(id: String)

    /**
     * Puts a claimed run back before anything happened (Orbit's chat was busy, or a quiet time
     * holds the wake): the run is forgotten and its event waits until [retryAt]. The task stays
     * active, so a busy chat never pauses it.
     */
    @Transaction
    suspend fun defer(token: OrbitRunEntity, retryAt: Long, bootId: String, now: Long, elapsed: Long): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        if (bindings(current.id, current.generation).isNotEmpty()) return false
        deleteRun(current.id)
        setEventDue(current.eventId, retryAt)
        return true
    }

    /** Orbit spoke on its own; the notification interval counts from now. */
    @Transaction
    suspend fun touchDelivered(orbitId: String, now: Long) {
        val policy = notificationPolicy(orbitId) ?: OrbitNotificationEntity(orbitId)
        saveNotificationPolicy(policy.copy(lastDeliveredAt = now))
    }

    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId AND sender = 'ai'")
    suspend fun latestAiTimestamp(chatId: String): Long?

    /** Orbit's messages in [chatId] written after [after], oldest first; one reply can be several. */
    @Query("SELECT content FROM messages WHERE chatId = :chatId AND sender = 'ai' AND timestamp > :after ORDER BY timestamp ASC")
    suspend fun aiSaidAfter(chatId: String, after: Long): List<String>

    /** Bound a registered turn by its terminal assistant timestamp, excluding later chat turns. */
    @Query("SELECT content FROM messages WHERE chatId = :chatId AND sender = 'ai' AND timestamp > :after AND timestamp <= :through ORDER BY timestamp ASC")
    suspend fun aiSaidThrough(chatId: String, after: Long, through: Long): List<String>

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId AND state IN ('COMPLETED','FAILED') ORDER BY finishedAt DESC LIMIT 1")
    suspend fun lastFinishedRun(taskId: String): OrbitRunEntity?

    /** The last finished run that actually checked something; a skipped check is not a baseline. */
    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId AND decision != '" + OrbitRunEntity.SKIPPED +
        "' AND state IN ('COMPLETED','FAILED') ORDER BY finishedAt DESC LIMIT 1")
    suspend fun lastRealRun(taskId: String): OrbitRunEntity?

    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestRun(taskId: String): Flow<OrbitRunEntity?>

    /** A task's runs, newest first, for its execution history. */
    @Query("SELECT * FROM orbit_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRuns(taskId: String, limit: Int): Flow<List<OrbitRunEntity>>

    @Query("SELECT * FROM orbit_runs WHERE id = :id AND taskId = :taskId")
    suspend fun runOfTask(taskId: String, id: String): OrbitRunEntity?

    /**
     * Each task's newest run in one Orbit, so the task list shows how each last went. Driven by the
     * tasks, so each task's runs are looked at once through the taskId index.
     */
    @Query("SELECT r.* FROM orbit_tasks t JOIN orbit_runs r ON r.id = " +
        "(SELECT r2.id FROM orbit_runs r2 WHERE r2.taskId = t.id ORDER BY r2.startedAt DESC LIMIT 1) " +
        "WHERE t.orbitId = :orbitId")
    fun observeLatestRuns(orbitId: String): Flow<List<OrbitRunEntity>>

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

    /** Reports of tasks made in a side chat go back to the main chat once that side chat is gone. */
    @Query("UPDATE orbit_tasks SET chatId = :to WHERE orbitId = :orbitId AND chatId = :from")
    suspend fun moveTaskChat(orbitId: String, from: String, to: String)

    /**
     * Swaps a built-in routine's stored steps for the current ones. The version stays, so a run in
     * progress is not cancelled; it already runs the current steps.
     */
    @Query("UPDATE orbit_tasks SET instruction = :instruction WHERE id = :id AND instruction = :old")
    suspend fun refreshInstruction(id: String, old: String, instruction: String): Int

    /**
     * Pulls a waiting interval task's next turn back to at most one interval from [now]. The version
     * stays, so a run in progress is not cancelled; other schedules are left alone.
     */
    @Query(
        "UPDATE orbit_tasks SET nextRunAt = :now + MAX(intervalMinutes, 15) * 60000 WHERE id = :id AND " +
            "state = 'ACTIVE' AND scheduleMode = 'INTERVAL' AND nextRunAt > :now + MAX(intervalMinutes, 15) * 60000"
    )
    suspend fun capIntervalWait(id: String, now: Long): Int

    /** Drops a retired phrase of a built-in routine's steps from the chats its past runs used. */
    @Query(
        "UPDATE messages SET content = REPLACE(content, :phrase, '') WHERE content LIKE '%' || :phrase || '%' AND " +
            "chatId IN (SELECT chatId FROM orbit_task_chats WHERE runId IN (SELECT id FROM orbit_runs WHERE taskId = :taskId))"
    )
    suspend fun dropRunPhrase(taskId: String, phrase: String): Int

    @Query("UPDATE orbit_deliveries SET chatId = :to WHERE orbitId = :orbitId AND chatId = :from AND state = 'PENDING'")
    suspend fun movePendingDeliveries(orbitId: String, from: String, to: String)

    @Query("UPDATE orbit_deliveries SET state='SENT', notificationSent=1 WHERE orbitId=:orbitId AND messageId=:messageId AND state='PENDING'")
    suspend fun markBatchDelivered(orbitId: String, messageId: Long)

    /** Reports Orbit was told about in its chat; Orbit's own reply is the message. */
    @Query("UPDATE orbit_deliveries SET state='SENT', notificationSent=1 WHERE id IN (:ids) AND state='PENDING'")
    suspend fun markHandedToOrbit(ids: List<String>)

    /** Drafts that ran out before Orbit heard of them are never told; the reason is kept. */
    @Query("UPDATE orbit_deliveries SET state='CANCELLED', verdict='" + OrbitDeliveryEntity.SKIP + "', verdictReason='moot', " +
        "verdictNote='" + OrbitDeliveryEntity.EXPIRED + "', decidedAt=:now WHERE orbitId=:orbitId AND state='PENDING' " +
        "AND expiresAt > 0 AND expiresAt <= :now")
    suspend fun expirePending(orbitId: String, now: Long)

    /** Items Orbit has already told the user, newest first, to spot a repeat of the same matter. */
    @Query("SELECT * FROM orbit_deliveries WHERE orbitId=:orbitId AND verdict='" + OrbitDeliveryEntity.SEND + "' " +
        "AND decidedAt > :since ORDER BY decidedAt DESC LIMIT :limit")
    suspend fun toldSince(orbitId: String, since: Long, limit: Int): List<OrbitDeliveryEntity>

    /** Orbit's calls on handed items, newest first: what it told and what it left out, and why. */
    @Query("SELECT * FROM orbit_deliveries WHERE orbitId=:orbitId AND verdict != '' ORDER BY decidedAt DESC LIMIT :limit")
    suspend fun decidedItems(orbitId: String, limit: Int): List<OrbitDeliveryEntity>

    /** Orbit's call on one handed item; it can still replace a default "not registered" skip. */
    @Query("UPDATE orbit_deliveries SET verdict=:verdict, verdictReason=:reason, verdictNote=:note, " +
        "presentation=:presentation, decidedAt=:now " +
        "WHERE id=:id AND orbitId=:orbitId AND chatId=:chatId AND state='SENT' " +
        "AND (verdict='' OR verdictReason='" + OrbitDeliveryEntity.UNREGISTERED + "')")
    suspend fun decide(id: String, orbitId: String, chatId: String, verdict: String, reason: String, note: String,
        presentation: String, now: Long): Int

    /** What the user did with an item Orbit told them; a later answer replaces an earlier one. */
    @Query("UPDATE orbit_deliveries SET userResponse=:response, userResponseNote=:note, respondedAt=:now " +
        "WHERE id=:id AND orbitId=:orbitId AND verdict='" + OrbitDeliveryEntity.SEND + "'")
    suspend fun respond(id: String, orbitId: String, response: String, note: String, now: Long): Int

    /** Items Orbit spoke about without a call count as not sent. */
    @Query("UPDATE orbit_deliveries SET verdict='" + OrbitDeliveryEntity.SKIP + "', verdictReason='" +
        OrbitDeliveryEntity.UNREGISTERED + "', decidedAt=:now WHERE id IN (:ids) AND verdict=''")
    suspend fun markUnregistered(ids: List<String>, now: Long)

    @Query("SELECT * FROM orbit_deliveries WHERE runId = :runId")
    fun observeDeliveryOfRun(runId: String): Flow<OrbitDeliveryEntity?>

    /** The application supplies DAOs from the same database and holds its chat lock. */
    @Transaction
    suspend fun appendDelivery(deliveryId: String, now: Long, chats: ChatDao, messages: MessageDao): OrbitDeliveryEntity? {
        val delivery = delivery(deliveryId) ?: return null
        if (delivery.state != "PENDING") return null
        val task = task(delivery.taskId)
        val chat = chats.getChatById(delivery.chatId)
        if (task == null || task.version != delivery.taskVersion ||
            task.state !in setOf("ACTIVE", "DONE", "WAITING_USER", "VERIFY", "PAUSED") || chat == null) {
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
        deleteNotificationPolicy(orbitId)
        deleteGoals(orbitId)
        deleteArtifacts(orbitId)
        deleteOrbitInputs(orbitId)
        deleteOrbitBindings(orbitId)
        deleteOrbitActions(orbitId)
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
        val pendingUserRequest = current.state == "WAITING_USER" ||
            (current.state == "VERIFY" && latestRun(current.id)?.state == "WAITING_USER")
        if (pendingUserRequest && updated.state != "CANCELLED") return false
        if (updated.state == "ACTIVE" && unresolvedActions(current.id).isNotEmpty()) return false
        markUnreceiptedUnknown(current.id, updated.updatedAt)
        updateTask(updated.copy(version = current.version + 1))
        cancelRuns(current.id, updated.updatedAt)
        cancelDeliveries(current.id)
        deleteTaskEvents(current.id)
        return true
    }

    /** [replaceDefinition], but only while no run of the task is in progress. */
    @Transaction
    suspend fun replaceDefinitionIfIdle(updated: OrbitTaskEntity, expectedVersion: Long): Boolean {
        if (runningCount(updated.id) > 0) return false
        return replaceDefinition(updated, expectedVersion)
    }

    /** A WorkManager wake-up is only a hint. The durable task/event decides whether work is due. */
    @Transaction
    suspend fun enqueueDue(taskId: String, now: Long): OrbitEventEntity? {
        val current = task(taskId)?.takeIf { it.state == "ACTIVE" } ?: return null
        pendingEvent(current.id, current.version)?.let { return it }
        if (current.scheduleMode == "EVENT" || current.kind == "GEOFENCE") return null
        if (current.nextRunAt > now) return null
        val event = OrbitEventEntity("${current.id}:${current.version}:${current.nextRunAt}",
            current.id, current.version, current.nextRunAt, now)
        insertEvent(event)
        return event
    }

    @Transaction
    suspend fun enqueueManual(taskId: String, requestId: String, now: Long): Boolean {
        val current = task(taskId)?.takeIf { it.state == "ACTIVE" } ?: return false
        // A "run now" still waiting is enough; pressing again does not queue a second one.
        if (pendingManualCount(current.id, current.version) > 0) return false
        val eventId = "${current.id}:${current.version}:manual:$requestId"
        // The completed run retains this key after the pending event is removed.
        if (eventRun(eventId) != null) return false
        return insertEvent(OrbitEventEntity(eventId, current.id, current.version, now, now)) != -1L
    }

    /** A stale boundary callback must never run an edited or resumed task definition. */
    @Transaction
    suspend fun enqueueGeofence(taskId: String, version: Long, requestId: String, now: Long): Boolean {
        val current = task(taskId) ?: return false
        if (current.kind != "GEOFENCE" || current.version != version || current.state != "ACTIVE") return false
        if (current.goalId.isNotEmpty() && goal(current.goalId)?.state != "ACTIVE") return false
        if (current.scheduleMode == "ONCE" && pendingEvent(taskId, version) != null) return false
        // These are automatic events, so they obey quiet hours like time-based reminders.
        // The manual prefix would bypass that policy.
        val eventId = "$taskId:$version:geofence:$requestId"
        if (eventRun(eventId) != null) return false
        return insertEvent(OrbitEventEntity(eventId, taskId, version, now, now)) != -1L
    }

    @Transaction
    suspend fun claim(
        event: OrbitEventEntity, owner: String, bootId: String,
        now: Long, elapsed: Long, leaseMs: Long,
        interruptedMessage: String = "Execution interrupted. Check previous results before continuing.",
    ): OrbitRunEntity? {
        val task = task(event.taskId)?.takeIf { it.state == "ACTIVE" && it.version == event.taskVersion }
            ?: return null
        if (pendingEvent(task.id, task.version)?.id != event.id) return null
        if (task.goalId.isNotEmpty() && goal(task.goalId)?.state != "ACTIVE") return null
        // Short and long tasks run in separate lanes; after an edit moves a task across the
        // boundary, its new version must wait until the old run still holding a live lease ends.
        if (otherRunningRuns(task.id, event.id).any {
                it.bootId == bootId && it.leaseUntilElapsed > elapsed && it.leaseUntilWall > now
            }) return null
        val old = eventRun(event.id)
        if (old != null && (old.state != "RUNNING" ||
                (old.bootId == bootId && old.leaseUntilElapsed > elapsed && old.leaseUntilWall > now))) return null
        // A dead worker may have acted externally before it could save its receipt. Reclaiming
        // its lease is not evidence that repeating the action is safe.
        val unresolved = unresolvedActions(task.id)
        if (unresolved.isNotEmpty()) {
            unresolved.filter { it.state == "STARTED" }.forEach {
                updateAction(it.copy(state = "UNKNOWN", updatedAt = now))
            }
            updateTask(task.copy(state = "VERIFY", updatedAt = now))
            old?.let { updateRun(it.copy(state = "UNKNOWN", finishedAt = now)) }
            return null
        }
        if (old != null && bindings(old.id, old.generation).isNotEmpty()) {
            interrupt(old, now, interruptedMessage)
            return null
        }
        val run = if (old == null) OrbitRunEntity(
            id = event.id, eventId = event.id, taskId = task.id, taskVersion = task.version,
            state = "RUNNING", owner = owner, generation = 1, bootId = bootId,
            leaseUntilElapsed = elapsed + leaseMs, leaseUntilWall = now + leaseMs,
            sourceText = "", fingerprint = "", hasSource = false, observation = "", error = "",
            startedAt = now, finishedAt = 0,
        ) else old.copy(owner = owner, generation = old.generation + 1, bootId = bootId,
            leaseUntilElapsed = elapsed + leaseMs, leaseUntilWall = now + leaseMs).let {
            // No model turn started yet. Phone sources (WATCH) go stale quickly, so read them again.
            if (task.kind == "WATCH") it.copy(sourceText = "", fingerprint = "", hasSource = false) else it
        }
        if (old == null) insertRun(run) else updateRun(run)
        return run
    }

    /** A started model may have used any ordinary tool; never replay it after losing ownership. */
    @Transaction
    suspend fun interrupt(token: OrbitRunEntity, now: Long, message: String): Boolean {
        val current = run(token.id) ?: return false
        val task = task(token.taskId) ?: return false
        if (current.state != "RUNNING" || current.owner != token.owner || current.generation != token.generation ||
            task.version != token.taskVersion || task.state != "ACTIVE") return false
        markUnreceiptedUnknown(task.id, now)
        updateTask(task.copy(state = if (unresolvedActions(task.id).isEmpty()) "PAUSED" else "VERIFY", updatedAt = now))
        updateRun(current.copy(state = "INTERRUPTED", error = message, finishedAt = now))
        insertDelivery(OrbitDeliveryEntity(current.id, current.id, task.id, task.version, task.orbitId,
            task.chatId, task.title, message, now, "PENDING", null, null, false))
        return true
    }

    @Transaction
    suspend fun currentLease(token: OrbitRunEntity, bootId: String, now: Long, elapsed: Long): OrbitRunEntity? {
        val current = run(token.id) ?: return null
        val task = task(current.taskId) ?: return null
        if (task.goalId.isNotEmpty() && goal(task.goalId)?.state != "ACTIVE") return null
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
        nextRunAt: Long, bootId: String, now: Long, elapsed: Long, decision: String = "",
        opportunityKey: String = "", expiresAt: Long = 0, priority: String = "regular", evidence: String = "",
        triggerFollowers: Boolean = true): Boolean {
        val current = currentLease(token, bootId, now, elapsed) ?: return false
        if (unresolvedActions(token.taskId).isNotEmpty()) return false
        val task = checkNotNull(task(current.taskId))
        // The same kind of failure is told once; a changing detail after the colon (such as stderr) does not re-alert.
        fun kind(text: String) = text.substringBefore('：').substringBefore(": ").take(48)
        val repeatFailure = error.isNotEmpty() && lastRealRun(task.id)?.error?.let(::kind) == kind(error)
        updateRun(current.copy(state = if (error.isEmpty()) "COMPLETED" else "FAILED", sourceText = "",
            observation = observation, error = error, finishedAt = now, decision = decision.take(1000)))
        updateTask(task.copy(nextRunAt = nextRunAt, updatedAt = now,
            state = when {
                task.kind == "ONCE" -> "DONE" // Internal manual check-ins have no resume UI.
                task.scheduleMode == "ONCE" -> if (error.isEmpty()) "DONE" else "PAUSED"
                else -> task.state
            },
            // Handed to Orbit in the chat, the run notes only that; what Orbit last noted stays.
            lastObservation = if (error.isEmpty() && decision != OrbitRunEntity.IN_CHAT) observation else task.lastObservation,
            // A run that never read its source (a skipped check) keeps the last known fingerprint.
            lastFingerprint = if (error.isEmpty() && current.hasSource) current.fingerprint else task.lastFingerprint))
        if (message.isNotBlank() && (!repeatFailure || task.notifyPolicy == "ALWAYS")) insertDelivery(OrbitDeliveryEntity(
            id = current.id, runId = current.id, taskId = task.id, taskVersion = task.version,
            orbitId = task.orbitId, chatId = task.chatId, title = task.title, body = message,
            createdAt = now, state = "PENDING", messageId = null, messageTimestamp = null, notificationSent = false,
            opportunityKey = opportunityKey, expiresAt = expiresAt, priority = priority, evidence = evidence,
        ))
        deleteEvent(current.eventId)
        // A check skipped because its source was not ready is not a success that relays to EVENT tasks.
        if (triggerFollowers && error.isEmpty() && decision != OrbitRunEntity.SKIPPED) subscribers(task.id).filter { it.orbitId == task.orbitId }.forEach { subscriber ->
            insertEvent(OrbitEventEntity(
                "${subscriber.id}:${subscriber.version}:completed:${current.id}",
                subscriber.id, subscriber.version, now, now, current.id))
        }
        return true
    }
}
