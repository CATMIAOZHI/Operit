package com.ai.assistance.operit.data.dao

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.db.appendOrbitDelivery
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.*
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
            .setDriver(JdbcSQLiteDriver()).addMigrations(AppDatabase.MIGRATION_35_36, AppDatabase.MIGRATION_36_37)
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

    @Test fun `version 35 migration preserves existing messages and creates validated task schema`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id = "chat", title = "existing"))
        database.messageDao().insertMessage(MessageEntity(chatId = "chat", sender = "user", content = "keep me", orderIndex = 0))
        database.close()
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "orbit-test").absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                listOf("orbit_deliveries", "orbit_runs", "orbit_events", "orbit_tasks",
                    "orbit_actions", "orbit_task_chats", "orbit_task_inputs").forEach {
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
