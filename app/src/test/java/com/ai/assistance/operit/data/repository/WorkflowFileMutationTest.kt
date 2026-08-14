package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.data.model.ExecutionStatus
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowFileMutationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }

    private val testWriter: (File, String) -> Unit = { file, content -> file.writeText(content) }
    private val testReader: (File) -> String = { file -> file.readText() }

    @Test
    fun executionStatusAndTokenRotation_areSerializedWithoutRestoringOldToken() {
        val file = temporaryFolder.newFile("workflow-1.json")
        val lock = Any()
        val oldToken = "old_token_012345678901234567890123"
        val rotatedToken = "new_token_012345678901234567890123"
        val initial = workflowWithToken(oldToken)
        file.writeText(json.encodeToString(initial))

        val statusReadLatest = CountDownLatch(1)
        val allowStatusWrite = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statusUpdate = executor.submit<Workflow?> {
                mutateWorkflowFileAtomically(
                    file = file,
                    workflowId = initial.id,
                    json = json,
                    lock = lock,
                    reader = testReader,
                    writer = testWriter
                ) { latest ->
                    statusReadLatest.countDown()
                    check(allowStatusWrite.await(5, TimeUnit.SECONDS))
                    applyWorkflowExecutionStatus(latest, ExecutionStatus.RUNNING, 1234L)
                }
            }

            assertTrue(statusReadLatest.await(5, TimeUnit.SECONDS))
            val requestedRotation = initial.copy(
                name = "edited while running",
                nodes = listOf(intentTrigger(rotatedToken)),
                lastExecutionStatus = null,
                lastExecutionTime = null
            )
            val workflowUpdate = executor.submit<Workflow> {
                updateWorkflowFileAtomically(
                    file = file,
                    workflowId = initial.id,
                    requestedWorkflow = requestedRotation,
                    json = json,
                    lock = lock,
                    reader = testReader,
                    writer = testWriter
                )
            }

            Thread.sleep(50)
            assertFalse("workflow update must wait for the in-flight status mutation", workflowUpdate.isDone)
            allowStatusWrite.countDown()
            statusUpdate.get(5, TimeUnit.SECONDS)
            workflowUpdate.get(5, TimeUnit.SECONDS)

            val stored = decodeWorkflowContentSafely(json, file.readText(), initial.id)
            val storedTrigger = stored.nodes.single() as TriggerNode
            assertEquals(rotatedToken, storedTrigger.triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN])
            assertEquals("edited while running", stored.name)
            assertEquals(ExecutionStatus.RUNNING, stored.lastExecutionStatus)
            assertEquals(1234L, stored.lastExecutionTime)
        } finally {
            allowStatusWrite.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun executionStatistics_patchOnlyRuntimeFieldsOnLatestWorkflow() {
        val token = "signed_token_0123456789012345678901"
        val latest = workflowWithToken(token).copy(
            name = "latest edited name",
            totalExecutions = 7,
            successfulExecutions = 4,
            failedExecutions = 3
        )

        val updated = applyWorkflowExecutionStatistics(
            latest,
            ExecutionStatus.SUCCESS,
            executionTime = 99L
        )

        assertEquals("latest edited name", updated.name)
        assertEquals(token, (updated.nodes.single() as TriggerNode)
            .triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN])
        assertEquals(8, updated.totalExecutions)
        assertEquals(5, updated.successfulExecutions)
        assertEquals(3, updated.failedExecutions)
        assertEquals(ExecutionStatus.SUCCESS, updated.lastExecutionStatus)
        assertEquals(99L, updated.lastExecutionTime)
    }

    @Test
    fun executionMetadataWriteDoesNotRecreateADeletedPrivateDefinition() {
        val deletedFile = File(temporaryFolder.root, "deleted.json")
        var writerCalled = false

        val result = mutateWorkflowFileAtomically(
            file = deletedFile,
            workflowId = "deleted",
            json = json,
            lock = Any(),
            reader = { error("missing private file must not be read") },
            writer = { _, _ -> writerCalled = true },
            transform = { error("missing private file must not be patched") },
        )

        assertEquals(null, result)
        assertFalse(writerCalled)
        assertFalse(deletedFile.exists())
    }

    @Test
    fun legacyEditPublishesOnlyTheFinalDisabledPrivateDefinition() {
        val internalFile = File(temporaryFolder.root, "promoted.json")
        val legacy = Workflow(id = "promoted", name = "legacy", enabled = true)
        val requested = legacy.copy(name = "edited", enabled = false)
        val visibleWrites = mutableListOf<Workflow>()

        val updated = updateEffectiveWorkflowFileAtomically(
            internalFile = internalFile,
            workflowId = legacy.id,
            fallbackWorkflow = legacy,
            json = json,
            lock = Any(),
            reader = { error("private definition does not exist yet") },
            writer = { file, content ->
                val visible = decodeWorkflowContentSafely(json, content, legacy.id)
                visibleWrites += visible
                file.writeText(content)
            },
            transform = { _, promotingLegacy ->
                assertTrue(promotingLegacy)
                requested
            },
        )

        assertEquals(listOf(requested), visibleWrites)
        assertEquals(requested, updated)
        assertFalse(decodeWorkflowContentSafely(json, internalFile.readText(), legacy.id).enabled)
    }

    @Test
    fun privateOnlyMutationCannotUseAnAbsentLegacyFallback() {
        val internalFile = File(temporaryFolder.root, "private-only.json")
        var writerCalled = false

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            updateEffectiveWorkflowFileAtomically(
                internalFile = internalFile,
                workflowId = "private-only",
                fallbackWorkflow = null,
                json = json,
                lock = Any(),
                writer = { _, _ -> writerCalled = true },
                transform = { latest, _ -> latest },
            )
        }
        assertFalse(writerCalled)
        assertFalse(internalFile.exists())
    }

    private fun workflowWithToken(token: String): Workflow = Workflow(
        id = "workflow-1",
        name = "initial",
        nodes = listOf(intentTrigger(token))
    )

    private fun intentTrigger(token: String): TriggerNode = TriggerNode(
        id = "trigger-1",
        triggerType = "intent",
        triggerConfig = mapOf(
            WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
            WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to token
        )
    )
}
