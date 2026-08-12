package com.ai.assistance.operit.core.workflow

import android.content.Intent
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import java.security.MessageDigest

object WorkflowIntentSecurity {
    const val MIN_AUTH_TOKEN_LENGTH = 32
    const val MAX_AUTH_TOKEN_LENGTH = 128
    const val ACTION_TRIGGER_WORKFLOW = "com.ai.assistance.operit.TRIGGER_WORKFLOW"
    const val EXTRA_AUTH_TOKEN = "com.ai.assistance.operit.extra.WORKFLOW_AUTH_TOKEN"
    const val CONFIG_ACTION = "action"
    const val CONFIG_TASKER_COMMAND = "command"
    const val CONFIG_AUTH_TOKEN = "auth_token"

    private val tokenPattern = Regex("[A-Za-z0-9_-]{$MIN_AUTH_TOKEN_LENGTH,$MAX_AUTH_TOKEN_LENGTH}")
    private val externalTriggerTypes = setOf("intent", "tasker")

    fun isValidAuthToken(token: String?): Boolean =
        token != null && tokenPattern.matches(token)

    fun sanitizeExternalTriggerExtras(extras: Map<String, String>): Map<String, String> =
        extras.filterKeys { it != EXTRA_AUTH_TOKEN }

    fun readAuthTokenSafely(intent: Intent): String? =
        readAuthTokenSafely { intent.getStringExtra(EXTRA_AUTH_TOKEN) }

    internal fun readAuthTokenSafely(getter: () -> String?): String? =
        try {
            getter()
        } catch (_: RuntimeException) {
            null
        }

    /**
     * Initial editor config for a trigger that has not been persisted yet. Capability tokens are
     * deliberately absent here: the repository creates the durable token during save, after which
     * the user can reopen the saved node and copy the value that will actually authenticate.
     */
    fun defaultConfigForNewExternalTrigger(triggerType: String): Map<String, String> =
        when (triggerType) {
            "intent" -> mapOf(CONFIG_ACTION to ACTION_TRIGGER_WORKFLOW)
            "tasker" -> mapOf(CONFIG_TASKER_COMMAND to "start_meeting")
            else -> emptyMap()
        }

    fun normalizeExternalTriggerTokens(
        workflow: Workflow,
        replaceExistingTokens: Boolean = false,
        tokenValidator: (String?) -> Boolean = ::isValidAuthToken,
        tokenFactory: () -> String
    ): Workflow {
        var changed = false
        val normalizedNodes = workflow.nodes.map { node ->
            if (node !is TriggerNode || node.triggerType !in externalTriggerTypes) {
                return@map node
            }

            val currentToken = node.triggerConfig[CONFIG_AUTH_TOKEN]
            if (!replaceExistingTokens && tokenValidator(currentToken)) {
                return@map node
            }

            val replacement = tokenFactory()
            require(isValidAuthToken(replacement)) {
                "External trigger token must be 32 to 128 URL-safe characters"
            }
            changed = true
            node.copy(triggerConfig = node.triggerConfig + (CONFIG_AUTH_TOKEN to replacement))
        }

        return if (changed) workflow.copy(nodes = normalizedNodes) else workflow
    }

    /**
     * Normalizes a user update against the latest committed workflow. An existing trigger may
     * keep only its current committed token. A model-facing full update omits this hidden field,
     * so omission on the same trigger id and type inherits the latest committed token. A different
     * submitted token is replaced instead of accepted, so a stale editor snapshot cannot restore a
     * token that was already rotated.
     * Newly-added external triggers always receive a fresh token. This also prevents a stale
     * editor from deleting and recreating a trigger under a new id to restore a rotated token.
     */
    fun normalizeExternalTriggerTokensForUpdate(
        requestedWorkflow: Workflow,
        latestWorkflow: Workflow,
        tokenValidator: (String?) -> Boolean = ::isValidAuthToken,
        tokenFactory: () -> String
    ): Workflow {
        val latestNodesById = latestWorkflow.nodes
            .filterIsInstance<TriggerNode>()
            .associateBy(TriggerNode::id)
        var changed = false
        val normalizedNodes = requestedWorkflow.nodes.map { node ->
            if (node !is TriggerNode || node.triggerType !in externalTriggerTypes) {
                return@map node
            }

            val submittedToken = node.triggerConfig[CONFIG_AUTH_TOKEN]
            val latestNode = latestNodesById[node.id]
                ?.takeIf { it.triggerType == node.triggerType }
            val latestToken = latestNode?.triggerConfig?.get(CONFIG_AUTH_TOKEN)
            val inheritableLatestToken = latestToken?.takeIf(tokenValidator)
            if (submittedToken == null && latestNode != null && inheritableLatestToken != null) {
                changed = true
                return@map node.copy(
                    triggerConfig = node.triggerConfig +
                        (CONFIG_AUTH_TOKEN to inheritableLatestToken)
                )
            }
            val mayKeepSubmitted = latestNode != null &&
                submittedToken == latestToken &&
                tokenValidator(submittedToken)
            if (mayKeepSubmitted) return@map node

            val replacement = tokenFactory()
            require(isValidAuthToken(replacement)) {
                "External trigger token must be 32 to 128 URL-safe characters"
            }
            changed = true
            node.copy(triggerConfig = node.triggerConfig + (CONFIG_AUTH_TOKEN to replacement))
        }
        return if (changed) requestedWorkflow.copy(nodes = normalizedNodes) else requestedWorkflow
    }

    fun matches(node: TriggerNode, action: String?, suppliedToken: String?): Boolean {
        if (node.triggerType != "intent") return false
        val expectedAction = node.triggerConfig[CONFIG_ACTION]
        val expectedToken = node.triggerConfig[CONFIG_AUTH_TOKEN] ?: return false
        val actualToken = suppliedToken ?: return false
        if (expectedAction.isNullOrBlank() || action.isNullOrBlank()) return false
        if (!expectedAction.equals(action, ignoreCase = true)) return false
        if (!isValidAuthToken(expectedToken) || !isValidAuthToken(actualToken)) return false

        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            actualToken.toByteArray(Charsets.UTF_8)
        )
    }

    fun matchesTasker(node: TriggerNode, command: String?, suppliedToken: String?): Boolean {
        if (node.triggerType != "tasker") return false
        val expectedCommand = node.triggerConfig[CONFIG_TASKER_COMMAND]
        val expectedToken = node.triggerConfig[CONFIG_AUTH_TOKEN] ?: return false
        val actualToken = suppliedToken ?: return false
        if (expectedCommand.isNullOrBlank() || command.isNullOrBlank()) return false
        if (!expectedCommand.equals(command, ignoreCase = true)) return false
        if (!isValidAuthToken(expectedToken) || !isValidAuthToken(actualToken)) return false

        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            actualToken.toByteArray(Charsets.UTF_8)
        )
    }
}
