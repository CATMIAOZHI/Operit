package com.ai.assistance.operit.data.dao

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.db.appendOrbitDelivery
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.model.OrbitGoalEntity
import com.ai.assistance.operit.data.model.OrbitActionEntity
import com.ai.assistance.operit.data.model.OrbitArtifactVersionEntity
import com.ai.assistance.operit.data.model.OrbitRunEntity
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class OrbitTaskDaoTest {
    private lateinit var dir: File
    private lateinit var database: AppDatabase
    private val dao get() = database.orbitTaskDao()
    private fun open(): AppDatabase {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(dir)
        whenever(context.getDatabasePath(any())).thenAnswer { File(dir, it.getArgument<String>(0)) }
        return Room.databaseBuilder(context, AppDatabase::class.java, "orbit-test")
            .setDriver(JdbcSQLiteDriver()).addMigrations(AppDatabase.MIGRATION_35_36, AppDatabase.MIGRATION_36_37,
                AppDatabase.MIGRATION_37_38, AppDatabase.MIGRATION_38_39, AppDatabase.MIGRATION_39_40, AppDatabase.MIGRATION_40_41, AppDatabase.MIGRATION_41_42, AppDatabase.MIGRATION_42_43, AppDatabase.MIGRATION_43_44)
            .allowMainThreadQueries().build()
    }
    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("orbit-task-test").toFile()
        database = open()
    }
    @After fun tearDown() { database.close(); dir.deleteRecursively() }
    private fun definition(id: String = "task", kind: String = "URL_CHANGE") =
        OrbitTaskEntity(id, "orbit", "chat", "Test", "Watch for changes", kind,
            "https://example.com", 60, 540, "CHANGES", "ACTIVE", 1, 1000, "", "", 1, 1)
    private suspend fun claim(id: String = "task", owner: String = "worker", boot: String = "boot",
        now: Long = 1000, elapsed: Long = 10) =
        dao.claim(checkNotNull(dao.enqueueDue(id, now)), owner, boot, now, elapsed, 100)

    @Test fun `geofence never runs by clock and one shot finishes after a real event`() = runBlocking {
        dao.insertTask(definition(kind = "GEOFENCE").copy(scheduleMode = "ONCE", nextRunAt = 0))
        assertNull(dao.enqueueDue("task", 1000))
        assertFalse(dao.enqueueGeofence("task", 2, "crossing", 1000))
        assertTrue(dao.enqueueGeofence("task", 1, "crossing", 1000))
        assertFalse(dao.enqueueGeofence("task", 1, "crossing", 1000))
        val token = checkNotNull(claim())
        assertFalse(token.eventId.contains(":manual:"))
        assertTrue(dao.complete(token, "Arrived", "", "", 0, "boot", 1010, 20))
        assertEquals("DONE", dao.task("task")?.state)
        assertFalse(dao.enqueueGeofence("task", 1, "next-crossing", 2000))
    }

    @Test fun `repeating geofence stays armed but waits for another crossing`() = runBlocking {
        dao.insertTask(definition(kind = "GEOFENCE").copy(nextRunAt = 0))
        assertTrue(dao.enqueueGeofence("task", 1, "first", 1000))
        val token = checkNotNull(claim())
        assertTrue(dao.complete(token, "Left", "", "", 0, "boot", 1010, 20))
        assertEquals("ACTIVE", dao.task("task")?.state)
        assertNull(dao.enqueueDue("task", 2000))
        assertFalse(dao.enqueueGeofence("task", 1, "first", 2000))
        assertTrue(dao.enqueueGeofence("task", 1, "second", 2000))
    }

    @Test fun `an interval wait longer than one interval is cut back and nothing else changes`() = runBlocking {
        val hour = 60 * 60_000L
        dao.insertTask(definition().copy(nextRunAt = 1000 + 10 * hour))
        dao.insertTask(definition("daily").copy(scheduleMode = "DAILY", nextRunAt = 1000 + 10 * hour))
        dao.insertTask(definition("paused").copy(state = "PAUSED", nextRunAt = 1000 + 10 * hour))
        val version = dao.task("task")?.version
        assertEquals(1, dao.capIntervalWait("task", 1000))
        assertEquals(1000 + hour, dao.task("task")?.nextRunAt)
        assertEquals(version, dao.task("task")?.version)
        assertEquals(0, dao.capIntervalWait("task", 1000))
        assertEquals(0, dao.capIntervalWait("daily", 1000))
        assertEquals(0, dao.capIntervalWait("paused", 1000))
    }

    @Test fun `repeating geofence retains crossings while an earlier event waits`() = runBlocking {
        dao.insertTask(definition(kind = "GEOFENCE").copy(nextRunAt = 0))
        assertTrue(dao.enqueueGeofence("task", 1, "first", 1000))
        assertTrue(dao.enqueueGeofence("task", 1, "second", 2000))
        assertFalse(dao.enqueueGeofence("task", 1, "second", 2000))
        val first = checkNotNull(claim())
        assertTrue(dao.complete(first, "Arrived", "", "", 0, "boot", 1010, 20))
        assertEquals("task:1:geofence:second", dao.pendingEvent("task", 1)?.id)
    }

    @Test fun `chat decision enriches only the matching completed run and stays out of duplicate activity`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val decision = """@in_chat:{"outcome":"skip","reason":"already handled"}"""
        assertEquals(0, dao.recordChatDecision(token.id, token.generation, token.owner, decision))
        assertTrue(dao.complete(token, "in chat", "", "", 2000, "boot", 1010, 20, OrbitRunEntity.IN_CHAT))
        assertEquals(0, dao.recordChatDecision(token.id, token.generation + 1, token.owner, decision))
        assertEquals(0, dao.recordChatDecision(token.id, token.generation, "stale", decision))
        assertEquals(1, dao.recordChatDecision(token.id, token.generation, token.owner, decision))
        assertEquals(decision, dao.run(token.id)?.decision)
        assertEquals(0, dao.recordChatDecision(token.id, token.generation, token.owner, "overwritten"))
        assertTrue(dao.observeActivity("orbit").first().isEmpty())
    }

    @Test fun `a silent hook cannot wake a model through its follow-on tasks`() = runBlocking {
        dao.insertTask(definition("source", kind = "WATCH"))
        dao.insertTask(definition("follower", kind = "ASSIST").copy(scheduleMode = "EVENT", triggerTaskId = "source"))
        val token = checkNotNull(claim("source"))
        assertTrue(dao.complete(token, "nothing new", "", "", 2000, "boot", 1010, 20,
            decision = "stayed quiet", triggerFollowers = false))
        assertEquals("COMPLETED", dao.run(token.id)?.state)
        assertNull(dao.enqueueDue("follower", 1011))
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
    }

    @Test fun `registered task reply excludes a later user conversation`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id = "chat", title = "Orbit"))
        listOf(10L to "before", 20L to "task intermediate", 30L to "task final", 40L to "later reply")
            .forEachIndexed { index, (timestamp, content) ->
                database.messageDao().insertMessage(MessageEntity(chatId = "chat", sender = "ai",
                    content = content, timestamp = timestamp, orderIndex = index))
            }
        assertEquals(listOf("task intermediate", "task final"), dao.aiSaidThrough("chat", 10, 30))
        assertTrue(dao.aiSaidThrough("chat", 10, 10).isEmpty())
    }

    @Test fun `lost general tool turn pauses with one report instead of replaying after reboot`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val first = checkNotNull(claim())
        dao.insertBinding(com.ai.assistance.operit.data.model.OrbitTaskChatEntity("hidden", first.id, first.generation, "hidden"))
        assertNull(claim(owner = "replacement", boot = "new-boot", now = 2000))
        assertEquals("PAUSED", dao.task("task")!!.state)
        assertEquals("INTERRUPTED", dao.run(first.id)!!.state)
        assertEquals(1, dao.pendingDeliveries("orbit").size)
        assertFalse(dao.interrupt(first, 2001, "duplicate"))
        assertEquals(1, dao.pendingDeliveries("orbit").size)
        assertEquals("hidden", dao.bindings(first.id, first.generation).single().chatId)
    }

    @Test fun `interrupted turn rejects stale owner and cannot complete as success`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val first = checkNotNull(claim())
        assertFalse(dao.interrupt(first.copy(owner = "stale"), 1010, "wrong"))
        assertTrue(dao.interrupt(first, 1010, "interrupted"))
        assertFalse(dao.complete(first, "done", "done", "", 2000, "boot", 1011, 20))
        assertNull(dao.enqueueDue("task", 1012))
    }

    @Test fun `a run stopped for used up allowances before its turn waits and runs again`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST").copy(nextRunAt = 5000))
        assertTrue(dao.enqueueManual("task", "request", 1000))
        val first = checkNotNull(claim())
        assertTrue(dao.stopForQuota(first, 3000, 9000, 1010, "allowance"))
        assertEquals("ACTIVE", dao.task("task")!!.state)
        assertEquals("INTERRUPTED", dao.run(first.id)!!.state)
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
        // The retry keeps its manual key and waits for its time; a stale stop does nothing more.
        val retry = checkNotNull(dao.enqueueDue("task", 1011))
        assertTrue(retry.id.contains(":manual:") && retry.dueAt == 3000L)
        assertFalse(dao.stopForQuota(first, 4000, 9000, 1012, "again"))
        val second = checkNotNull(dao.claim(retry, "worker", "boot", 3000, 20, 100))
        assertTrue(dao.stopForQuota(second, 6000, 9000, 3010, "allowance"))
        // Retried again, the key does not grow.
        assertEquals(1, Regex(":quota-retry:").findAll(checkNotNull(dao.enqueueDue("task", 3011)).id).count())
    }

    @Test fun `a started turn cut short by allowances is never replayed`() = runBlocking {
        // A repeating task gives up only this run and goes on at its next time.
        dao.insertTask(definition(kind = "ASSIST"))
        val first = checkNotNull(claim())
        dao.insertBinding(com.ai.assistance.operit.data.model.OrbitTaskChatEntity("hidden", first.id, first.generation, "hidden"))
        assertTrue(dao.stopForQuota(first, 3000, 9000, 1010, "allowance"))
        assertEquals("ACTIVE", dao.task("task")!!.state)
        assertEquals(9000L, dao.task("task")!!.nextRunAt)
        assertNull(dao.enqueueDue("task", 1011))
        assertEquals(9000L, checkNotNull(dao.enqueueDue("task", 9000)).dueAt)
        // A one-time task is left for the user to check instead.
        dao.insertTask(definition(id = "once", kind = "ASSIST").copy(scheduleMode = "ONCE"))
        val once = checkNotNull(claim(id = "once"))
        dao.insertBinding(com.ai.assistance.operit.data.model.OrbitTaskChatEntity("hidden-once", once.id, once.generation, "hidden-once"))
        assertFalse(dao.stopForQuota(once, 3000, 9000, 1010, "allowance"))
        assertTrue(dao.interrupt(once, 1011, "interrupted"))
        assertEquals("PAUSED", dao.task("once")!!.state)
    }

    @Test fun `a run with an unconfirmed effect is not retried for the allowance`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val action = com.ai.assistance.operit.data.model.OrbitActionEntity(
            "write", "task", token.id, token.generation, "orbit_write_artifact", "digest",
            "PREPARED", "Save report", 1001, 1001)
        assertEquals("STARTED", dao.beginAction(token, action, "boot", 1001, 11)?.state)
        assertFalse(dao.stopForQuota(token, 3000, 9000, 1002, "allowance"))
        assertTrue(dao.interrupt(token, 1003, "interrupted"))
        assertEquals("VERIFY", dao.task("task")!!.state)
    }

    @Test fun `unreceipted write after reboot requires explicit verification before new generation`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val action = com.ai.assistance.operit.data.model.OrbitActionEntity(
            "write", "task", token.id, token.generation, "orbit_write_artifact", "digest",
            "PREPARED", "Save report", 1001, 1001)
        assertEquals("STARTED", dao.beginAction(token, action, "boot", 1001, 11)?.state)
        assertNull(dao.beginAction(token, action, "boot", 1002, 12))
        assertFalse(dao.complete(token, "done", "done", "", 2000, "boot", 1002, 12))
        database.close()
        database = open()
        assertNull(claim(boot = "new-boot", now = 1003, elapsed = 1))
        assertEquals("VERIFY", dao.task("task")?.state)
        assertEquals("UNKNOWN", dao.action("write")?.state)
        assertFalse(dao.replaceDefinition(checkNotNull(dao.task("task")).copy(state = "ACTIVE"), 1))
        assertTrue(dao.resolveAction("task", "write", false, 1004))
        assertTrue(dao.replaceDefinition(checkNotNull(dao.task("task")).copy(state = "ACTIVE"), 1))
        assertNotNull(claim(boot = "new-boot", now = 1005, elapsed = 3))
    }

    @Test fun `pause fences write start but a known late result can still be recorded`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val action = com.ai.assistance.operit.data.model.OrbitActionEntity(
            "write", "task", token.id, token.generation, "orbit_write_artifact", "digest",
            "PREPARED", "Save report", 1001, 1001)
        assertNotNull(dao.beginAction(token, action, "boot", 1001, 11))
        assertTrue(dao.replaceDefinition(definition(kind = "ASSIST").copy(state = "PAUSED"), 1))
        assertNull(dao.beginAction(token, action.copy(id = "second"), "boot", 1002, 12))
        assertTrue(dao.confirmAction("write", token.generation, "Saved and verified", 1003))
        assertEquals("PAUSED", dao.task("task")?.state)
        assertTrue(dao.unresolvedActions("task").isEmpty())
    }

    @Test fun `waiting survives restart and produces one request without rerunning`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        assertTrue(dao.waitForUser(token, "Choose a source", "boot", 1001, 11))
        assertFalse(dao.waitForUser(token, "duplicate", "boot", 1002, 12))
        database.close()
        database = open()
        assertEquals("WAITING_USER", dao.task("task")?.state)
        assertNull(dao.enqueueDue("task", 9000))
        assertEquals("Choose a source", dao.latestRun("task")?.error)
        assertEquals(1, dao.pendingDeliveries("orbit").size)
        assertFalse(dao.replaceDefinition(definition(kind = "ASSIST").copy(title = "stale edit"), 1))
        assertTrue(dao.resumeWithInput("task", 1, "Use this source", "new snapshot", 1003))
        assertFalse(dao.resumeWithInput("task", 1, "double tap", "", 1004))
        assertEquals("Use this source", dao.input("task")?.userNote)
        assertEquals(2L, dao.input("task")?.taskVersion)
        assertNotNull(dao.enqueueDue("task", 1003))
    }

    @Test fun `waiting while child finishes reconciles unknown receipt without losing user request`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val action = com.ai.assistance.operit.data.model.OrbitActionEntity(
            "write", "task", token.id, token.generation, "orbit_write_artifact", "digest",
            "PREPARED", "Save report", 1001, 1001)
        dao.beginAction(token, action, "boot", 1001, 11)
        assertTrue(dao.waitForUser(token, "Log in", "boot", 1002, 12, "https://example.com"))
        assertEquals("VERIFY", dao.task("task")?.state)
        assertEquals("UNKNOWN", dao.action("write")?.state)
        assertTrue(dao.confirmAction("write", token.generation, "Verified saved file", 1003))
        assertEquals("WAITING_USER", dao.task("task")?.state)
        assertEquals("https://example.com", dao.input("task")?.requestedUrl)
    }

    @Test fun `repeated events and racing workers give one execution owner`() = runBlocking {
        dao.insertTask(definition())
        val event = checkNotNull(dao.enqueueDue("task", 1000))
        assertEquals(event.id, dao.enqueueDue("task", 1001)?.id)
        val tokens = (1..8).map { index -> async(Dispatchers.IO) {
            dao.claim(event, "worker$index", "boot", 1000, 10, 100)
        } }.awaitAll().filterNotNull()
        assertEquals(1, tokens.size)
        assertTrue(dao.renew(tokens.single(), "boot", 1050, 60, 100))
        assertNull(dao.claim(event, "late", "boot", 1101, 111, 100))
    }

    @Test fun `reboot recovery retains source but rejects stale results`() = runBlocking {
        dao.insertTask(definition())
        val old = checkNotNull(claim())
        assertTrue(dao.checkpoint(old, "bounded evidence", "sha", "boot", 1001, 11))
        database.close()
        database = open()
        val recovered = checkNotNull(claim(owner = "new", boot = "next-boot", now = 1002, elapsed = 1))
        assertEquals(2L, recovered.generation)
        assertEquals("bounded evidence", recovered.sourceText)
        assertTrue(recovered.hasSource)
        assertFalse(dao.complete(old, "stale", "stale message", "", 5000, "boot", 1003, 12))
        assertTrue(dao.complete(recovered, "new", "one message", "", 5000, "next-boot", 1003, 2))
        assertEquals(1, dao.pendingDeliveries("orbit").size)
        assertFalse(dao.complete(recovered, "new", "duplicate", "", 5000, "next-boot", 1004, 3))
        assertNull(dao.enqueueDue("task", 4000))
    }

    @Test fun `reclaimed watch run reads its phone source again`() = runBlocking {
        dao.insertTask(definition(kind = "WATCH"))
        val old = checkNotNull(claim())
        assertTrue(dao.checkpoint(old, "old notifications", "sha", "boot", 1001, 11))
        val recovered = checkNotNull(claim(owner = "new", boot = "next-boot", now = 1002, elapsed = 1))
        assertEquals(2L, recovered.generation)
        assertFalse(recovered.hasSource)
        assertEquals("", recovered.sourceText)
    }

    @Test fun `skipped checks neither relay nor reset failure dedupe`() = runBlocking {
        dao.insertTask(definition("source", kind = "WATCH"))
        dao.insertTask(definition("follower").copy(scheduleMode = "EVENT", triggerTaskId = "source"))
        val skipped = checkNotNull(claim("source"))
        assertTrue(dao.complete(skipped, "", "", "", 1500, "boot", 1001, 11, OrbitRunEntity.SKIPPED))
        assertNull(dao.enqueueDue("follower", 1001))
        val stuck = "Notification service is stuck"
        val first = checkNotNull(claim("source", now = 1500, elapsed = 20))
        assertTrue(dao.complete(first, "", "failed", stuck, 2000, "boot", 1501, 21))
        val again = checkNotNull(claim("source", now = 2000, elapsed = 30))
        assertTrue(dao.complete(again, "", "", "", 2500, "boot", 2001, 31, OrbitRunEntity.SKIPPED))
        val third = checkNotNull(claim("source", now = 2500, elapsed = 40))
        assertTrue(dao.complete(third, "", "failed", stuck, 3000, "boot", 2501, 41))
        assertEquals(1, dao.pendingDeliveries("orbit").size)
    }

    @Test fun `a failure that only differs after the colon is told once`() = runBlocking {
        dao.insertTask(definition(kind = "WATCH"))
        val first = checkNotNull(claim())
        assertTrue(dao.complete(first, "", "failed", "The check script failed: sh: line 1", 1500, "boot", 1001, 11))
        val second = checkNotNull(claim(now = 1500, elapsed = 20))
        assertTrue(dao.complete(second, "", "failed", "The check script failed: sh: line 2", 3000, "boot", 1501, 21))
        assertEquals(1, dao.pendingDeliveries("orbit").size)
    }

    @Test fun `expired lease can be reclaimed on same boot`() = runBlocking {
        dao.insertTask(definition())
        val first = checkNotNull(claim())
        val second = checkNotNull(claim(owner = "next", now = 1101, elapsed = 111))
        assertEquals(first.generation + 1, second.generation)
        assertFalse(dao.checkpoint(first, "stale", "stale", "boot", 1102, 112))
        assertTrue(dao.checkpoint(second, "current", "new", "boot", 1102, 112))
    }

    @Test fun `pause cancels pending work and late completion and resume creates new generation`() = runBlocking {
        dao.insertTask(definition())
        val token = checkNotNull(claim())
        assertTrue(dao.replaceDefinition(definition().copy(state = "PAUSED", updatedAt = 1001), 1))
        assertNull(dao.enqueueDue("task", 2000))
        assertFalse(dao.complete(token, "", "late", "", 3000, "boot", 1002, 12))
        assertFalse(dao.replaceDefinition(definition().copy(state = "ACTIVE"), 1))
        val paused = checkNotNull(dao.task("task"))
        assertTrue(dao.replaceDefinition(paused.copy(state = "ACTIVE"), paused.version))
        assertEquals(3L, claim(now = 2000, elapsed = 1000)?.taskVersion)
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
    }

    @Test fun `edit invalidates result awaiting delivery`() = runBlocking {
        dao.insertTask(definition())
        val token = checkNotNull(claim())
        assertTrue(dao.complete(token, "done", "message", "", 5000, "boot", 1001, 11))
        assertTrue(dao.replaceDefinition(checkNotNull(dao.task("task")).copy(instruction = "changed"), 1))
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
        assertEquals("CANCELLED", dao.delivery(token.id)?.state)
    }

    @Test fun `delivery retry and user deletion never create another message`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id = "chat", title = "Orbit"))
        dao.insertTask(definition(kind = "ONCE"))
        val token = checkNotNull(claim())
        assertTrue(dao.complete(token, "done", "message", "", 5000, "boot", 1001, 11))
        assertEquals("DONE", dao.task("task")?.state)
        val first = checkNotNull(database.appendOrbitDelivery(token.id, 2000))
        database.close()
        database = open()
        assertEquals(first.messageId, database.appendOrbitDelivery(token.id, 3000)?.messageId)
        assertEquals(1, database.chatContentDao().getMessagesForChat("chat").size)
        database.messageDao().deleteAllMessagesForChat("chat")
        assertEquals(first.messageId, database.appendOrbitDelivery(token.id, 4000)?.messageId)
        assertTrue(database.chatContentDao().getMessagesForChat("chat").isEmpty())
    }

    @Test fun `manual request key remains idempotent after completion`() = runBlocking {
        dao.insertTask(definition().copy(nextRunAt = 5000))
        assertTrue(dao.enqueueManual("task", "request", 1000))
        assertFalse(dao.enqueueManual("task", "request", 1000))
        // A second "run now" while the first still waits queues nothing more.
        assertFalse(dao.enqueueManual("task", "another", 1000))
        val token = checkNotNull(claim())
        assertTrue(dao.complete(token, "result", "", "", 5000, "boot", 1001, 11))
        assertFalse(dao.enqueueManual("task", "request", 1002))
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
    }

    @Test fun `repeated failure is quiet but changed or recovered result can notify`() = runBlocking {
        dao.insertTask(definition())
        var token = checkNotNull(claim())
        assertTrue(dao.complete(token, "", "failed", "offline", 2000, "boot", 1001, 11))
        token = checkNotNull(claim(now = 2000, elapsed = 1000))
        assertTrue(dao.complete(token, "", "failed again", "offline", 3000, "boot", 2001, 1001))
        assertEquals(1, dao.pendingDeliveries("orbit").size)
        token = checkNotNull(claim(now = 3000, elapsed = 2000))
        assertTrue(dao.complete(token, "recovered", "new result", "", 4000, "boot", 3001, 2001))
        assertEquals(2, dao.pendingDeliveries("orbit").size)
    }

    @Test fun `deleting an Orbit removes only its tasks and execution records`() = runBlocking {
        dao.insertTask(definition())
        dao.insertTask(definition("other").copy(orbitId = "other-orbit"))
        val token = checkNotNull(claim())
        assertTrue(dao.complete(token, "", "report", "", 2000, "boot", 1001, 11))
        dao.removeOrbit("orbit")
        assertNull(dao.run(token.id))
        assertNull(dao.delivery(token.id))
        assertTrue(dao.tasks("orbit").isEmpty())
        assertNotNull(dao.task("other"))
    }

    @Test fun `one shot tasks finish once and completion events are durable without polling`() = runBlocking {
        dao.insertTask(definition("source").copy(scheduleMode = "ONCE"))
        dao.insertTask(definition("follower").copy(scheduleMode = "EVENT", triggerTaskId = "source"))
        assertNull(dao.enqueueDue("follower", 9000))
        val source = checkNotNull(claim("source"))
        assertTrue(dao.complete(source, "new information", "report", "", 2000, "boot", 1001, 11))
        assertFalse(dao.complete(source, "duplicate", "report", "", 2000, "boot", 1002, 12))
        assertEquals("DONE", dao.task("source")?.state)
        assertNull(dao.enqueueDue("source", 9000))
        val late = definition("late").copy(scheduleMode = "EVENT", triggerTaskId = "source")
        dao.insertSubscribedTask(late, 1002)
        dao.insertSubscribedTask(late, 1003)
        assertEquals(source.id, dao.enqueueDue("late", 1003)?.sourceRunId)
        database.close()
        database = open()
        val event = checkNotNull(dao.enqueueDue("follower", 1002))
        assertEquals(source.id, event.sourceRunId)
        val follower = checkNotNull(dao.claim(event, "worker", "boot", 1002, 12, 100))
        assertTrue(dao.waitForUser(follower, "Choose", "boot", 1003, 13))
        assertTrue(dao.resumeWithInput("follower", 1, "Continue", "", 1004))
        assertEquals(source.id, dao.enqueueDue("follower", 1004)?.sourceRunId)
    }

    @Test fun `goal pause fences execution and waiting input until goal resumes`() = runBlocking {
        val goal = OrbitGoalEntity("goal", "orbit", "Plan", "Steps", "ACTIVE", 1, 1, 1)
        dao.insertGoal(goal)
        dao.insertTask(definition().copy(goalId = goal.id))
        val token = checkNotNull(claim())
        assertTrue(dao.waitForUser(token, "Choose", "boot", 1001, 11))
        assertTrue(dao.saveGoal(goal.copy(state = "PAUSED"), 1))
        assertFalse(dao.resumeWithInput("task", 1, "Continue", "", 1002))
        assertTrue(dao.saveGoal(checkNotNull(dao.goal(goal.id)).copy(state = "ACTIVE"), 2))
        assertTrue(dao.resumeWithInput("task", 1, "Continue", "", 1003))
        val resumed = checkNotNull(claim(now = 1003, elapsed = 13))
        assertTrue(dao.saveGoal(checkNotNull(dao.goal(goal.id)).copy(state = "PAUSED"), 3))
        assertNull(dao.currentLease(resumed, "boot", 1004, 14))
        assertEquals("PAUSED", dao.task("task")?.state)
    }

    @Test fun `batched delivery has one message and retry retains every receipt`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id = "chat", title = "Orbit"))
        listOf("a", "b").forEach { id ->
            dao.insertTask(definition(id))
            val token = checkNotNull(claim(id))
            assertTrue(dao.complete(token, "", "report $id", "", 2000, "boot", 1001, 11))
        }
        val ids = dao.pendingDeliveries("orbit").map { it.id }
        val saved = dao.appendDeliveryBatch(ids, 1002, database.chatDao(), database.messageDao())
        assertEquals(2, saved.size)
        assertEquals(1, saved.map { it.messageId }.distinct().size)
        database.close()
        database = open()
        assertEquals(saved, dao.appendDeliveryBatch(ids, 1003, database.chatDao(), database.messageDao()))
        assertEquals(1, database.chatContentDao().getMessagesForChat("chat").size)
        assertEquals(1002L, dao.notificationPolicy("orbit")?.lastDeliveredAt)
        dao.markBatchDelivered("orbit", checkNotNull(saved.first().messageId))
        assertTrue(dao.pendingDeliveries("orbit").isEmpty())
    }

    @Test fun `hidden manual checkin failure is terminal while user one shot remains resumable`() = runBlocking {
        dao.insertTask(definition("manual", "ONCE"))
        dao.insertTask(definition("user", "ASSIST").copy(scheduleMode = "ONCE"))
        listOf("manual", "user").forEach { id ->
            val token = checkNotNull(claim(id))
            assertTrue(dao.complete(token, "", "Failed", "offline", 2000, "boot", 1001, 11))
        }
        assertEquals("DONE", dao.task("manual")?.state)
        assertEquals("PAUSED", dao.task("user")?.state)
    }

    @Test fun `artifact confirmation indexes immutable version idempotently with its action receipt`() = runBlocking {
        dao.insertTask(definition(kind = "ASSIST"))
        val token = checkNotNull(claim())
        val action = OrbitActionEntity("write", "task", token.id, token.generation,
            "orbit_write_artifact", "digest", "PREPARED", "Save report", 1001, 1001)
        assertNotNull(dao.beginAction(token, action, "boot", 1001, 11))
        val version = OrbitArtifactVersionEntity("version", "artifact", "orbit", "task", token.id, action.id,
            "report.md", "artifacts/task/version/report.md", "digest", 12, 1002)
        assertTrue(dao.confirmArtifact(version, token.generation, "Saved"))
        assertTrue(dao.confirmArtifact(version, token.generation, "Saved"))
        assertEquals(listOf(version), dao.artifacts("orbit", 100, 0))
        assertEquals(1, dao.artifactCount("orbit"))
        assertEquals(emptyList<OrbitArtifactVersionEntity>(), dao.artifacts("orbit", 100, 1))
        assertEquals("CONFIRMED", dao.action("write")?.state)
        val interrupted = action.copy(id = "interrupted", operationHash = "another")
        assertNotNull(dao.beginAction(token, interrupted, "boot", 1003, 13))
        dao.uncertainAction(interrupted.id, token.generation, 1004)
        val recovered = version.copy(id = "recovered", actionId = interrupted.id, createdAt = 1005)
        assertTrue(dao.verifyArtifact(recovered, 1005))
        assertEquals("VERIFIED", dao.action(interrupted.id)?.state)
        assertEquals(recovered, dao.artifact(recovered.id))
        dao.removeOrbit("orbit")
        assertNull(dao.artifact("version"))
    }

    @Test fun `version 35 migration preserves existing messages and creates validated task schema`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id = "chat", title = "existing"))
        database.messageDao().insertMessage(MessageEntity(chatId = "chat", sender = "user", content = "keep me", orderIndex = 0))
        database.close()
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "orbit-test").absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                listOf("orbit_deliveries", "orbit_runs", "orbit_events", "orbit_tasks",
                    "orbit_actions", "orbit_task_chats", "orbit_task_inputs",
                    "orbit_goals", "orbit_artifact_versions", "orbit_notifications").forEach {
                    statement.execute("DROP TABLE $it")
                }
                statement.execute("PRAGMA user_version=35")
            }
        }
        database = open()
        assertEquals("keep me", database.chatContentDao().getMessagesForChat("chat").single().content)
        dao.insertTask(definition())
        assertNotNull(claim())
    }
}
