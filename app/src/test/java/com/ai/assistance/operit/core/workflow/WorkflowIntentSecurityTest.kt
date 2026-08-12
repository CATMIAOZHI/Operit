package com.ai.assistance.operit.core.workflow

import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowIntentSecurityTest {
    private val strongToken = "01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun matches_requiresActionAndStrongPerWorkflowToken() {
        val node = TriggerNode(
            triggerType = "intent",
            triggerConfig = mapOf(
                WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken
            )
        )

        assertTrue(
            WorkflowIntentSecurity.matches(
                node,
                WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                strongToken
            )
        )
        assertFalse(WorkflowIntentSecurity.matches(node, WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW, null))
        assertFalse(WorkflowIntentSecurity.matches(node, WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW, "wrong"))
        assertFalse(
            WorkflowIntentSecurity.matches(
                node,
                WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            )
        )
        assertFalse(WorkflowIntentSecurity.matches(node, "com.example.OTHER", strongToken))
    }

    @Test
    fun normalize_addsMissingTokenButPreservesExistingStrongToken() {
        val missing = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW
                    )
                )
            )
        )
        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(missing) { strongToken }
        val normalizedNode = normalized.nodes.single() as TriggerNode
        assertEquals(strongToken, normalizedNode.triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN])

        val alreadySafe = normalized.copy()
        assertSame(alreadySafe, WorkflowIntentSecurity.normalizeExternalTriggerTokens(alreadySafe) { error("unused") })
    }

    @Test
    fun normalize_canRotateUntrustedLegacyToken() {
        val attackerKnownToken = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val workflow = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to attackerKnownToken
                    )
                )
            )
        )

        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
            workflow,
            replaceExistingTokens = true
        ) { strongToken }
        val node = normalized.nodes.single() as TriggerNode
        assertEquals(strongToken, node.triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN])
    }

    @Test
    fun normalizeUpdate_neverRestoresDifferentPreviouslyIssuedToken() {
        val rotatedToken = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val freshToken = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val latest = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    id = "trigger-1",
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to rotatedToken
                    )
                )
            )
        )
        val staleUpdate = latest.copy(
            nodes = listOf(
                (latest.nodes.single() as TriggerNode).copy(
                    triggerConfig = (latest.nodes.single() as TriggerNode).triggerConfig +
                        (WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken)
                )
            )
        )

        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokensForUpdate(
            requestedWorkflow = staleUpdate,
            latestWorkflow = latest,
            tokenValidator = { WorkflowIntentSecurity.isValidAuthToken(it) },
            tokenFactory = { freshToken }
        )

        val storedToken = (normalized.nodes.single() as TriggerNode)
            .triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN]
        assertEquals(freshToken, storedToken)
    }

    @Test
    fun normalizeUpdate_inheritsHiddenTokenForSameExternalTrigger() {
        val latest = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    id = "trigger-1",
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to
                            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken,
                    ),
                ),
            ),
        )
        val modelFacingUpdate = latest.copy(
            name = "renamed",
            nodes = listOf(
                (latest.nodes.single() as TriggerNode).copy(
                    triggerConfig = (latest.nodes.single() as TriggerNode).triggerConfig -
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN,
                ),
            ),
        )

        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokensForUpdate(
            requestedWorkflow = modelFacingUpdate,
            latestWorkflow = latest,
            tokenValidator = { WorkflowIntentSecurity.isValidAuthToken(it) },
            tokenFactory = { error("same-node omission must not rotate") },
        )

        assertEquals(
            strongToken,
            (normalized.nodes.single() as TriggerNode)
                .triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN],
        )
    }

    @Test
    fun normalizeUpdate_rotatesWhenExternalTriggerTypeChanges() {
        val freshToken = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val latest = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    id = "trigger-1",
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to
                            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken,
                    ),
                ),
            ),
        )
        val changedType = latest.copy(
            nodes = listOf(
                TriggerNode(
                    id = "trigger-1",
                    triggerType = "tasker",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_TASKER_COMMAND to "start_meeting",
                    ),
                ),
            ),
        )

        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokensForUpdate(
            requestedWorkflow = changedType,
            latestWorkflow = latest,
            tokenValidator = { WorkflowIntentSecurity.isValidAuthToken(it) },
            tokenFactory = { freshToken },
        )

        assertEquals(
            freshToken,
            (normalized.nodes.single() as TriggerNode)
                .triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN],
        )
    }

    @Test
    fun normalizeUpdate_rotatesTokenWhenStaleEditorRecreatesTriggerWithNewId() {
        val freshToken = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val latest = Workflow(
            id = "workflow-1",
            nodes = listOf(
                TriggerNode(
                    id = "trigger-current",
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken
                    )
                )
            )
        )
        val staleRecreated = latest.copy(
            nodes = listOf((latest.nodes.single() as TriggerNode).copy(id = "trigger-recreated"))
        )

        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokensForUpdate(
            requestedWorkflow = staleRecreated,
            latestWorkflow = latest,
            tokenValidator = { WorkflowIntentSecurity.isValidAuthToken(it) },
            tokenFactory = { freshToken }
        )

        assertEquals(
            freshToken,
            (normalized.nodes.single() as TriggerNode)
                .triggerConfig[WorkflowIntentSecurity.CONFIG_AUTH_TOKEN]
        )
    }

    @Test
    fun newExternalTriggerDefaultsDoNotExposeAThrowawayTokenBeforeSave() {
        val intentConfig = WorkflowIntentSecurity.defaultConfigForNewExternalTrigger("intent")
        val taskerConfig = WorkflowIntentSecurity.defaultConfigForNewExternalTrigger("tasker")

        assertEquals(WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW, intentConfig[WorkflowIntentSecurity.CONFIG_ACTION])
        assertEquals("start_meeting", taskerConfig[WorkflowIntentSecurity.CONFIG_TASKER_COMMAND])
        assertFalse(intentConfig.containsKey(WorkflowIntentSecurity.CONFIG_AUTH_TOKEN))
        assertFalse(taskerConfig.containsKey(WorkflowIntentSecurity.CONFIG_AUTH_TOKEN))
    }

    @Test
    fun matchesTasker_requiresCommandAndStrongPerWorkflowToken() {
        val node = TriggerNode(
            triggerType = "tasker",
            triggerConfig = mapOf(
                WorkflowIntentSecurity.CONFIG_TASKER_COMMAND to "start_meeting",
                WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to strongToken
            )
        )

        assertTrue(WorkflowIntentSecurity.matchesTasker(node, "start_meeting", strongToken))
        assertFalse(WorkflowIntentSecurity.matchesTasker(node, "start_meeting", null))
        assertFalse(WorkflowIntentSecurity.matchesTasker(node, "other", strongToken))
    }

    @Test
    fun tokenValidation_rejectsOversizedExternalInput() {
        assertTrue(WorkflowIntentSecurity.isValidAuthToken("a".repeat(32)))
        assertTrue(WorkflowIntentSecurity.isValidAuthToken("a".repeat(128)))
        assertFalse(WorkflowIntentSecurity.isValidAuthToken("a".repeat(129)))
    }

    @Test
    fun sanitizeExternalTriggerExtras_removesCredentialAndPreservesPayload() {
        val sanitized = WorkflowIntentSecurity.sanitizeExternalTriggerExtras(
            mapOf(
                WorkflowIntentSecurity.EXTRA_AUTH_TOKEN to strongToken,
                "message" to "hello",
                "request_id" to "request-1"
            )
        )

        assertFalse(sanitized.containsKey(WorkflowIntentSecurity.EXTRA_AUTH_TOKEN))
        assertEquals("hello", sanitized["message"])
        assertEquals("request-1", sanitized["request_id"])
    }

    @Test
    fun readAuthTokenSafely_rejectsMalformedExternalExtrasWithoutPropagating() {
        val token = WorkflowIntentSecurity.readAuthTokenSafely {
            throw IllegalStateException("malformed parcel with attacker payload")
        }

        assertEquals(null, token)
    }
}
