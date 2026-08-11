package com.ai.assistance.operit.core.workflow

import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAuthTokenManagerTest {
    @Test
    fun codec_acceptsOnlyTokensSignedForCurrentInstallation() {
        val codec = WorkflowAuthTokenCodec(ByteArray(32) { 7 }) { size ->
            ByteArray(size) { index -> index.toByte() }
        }
        val otherInstallation = WorkflowAuthTokenCodec(ByteArray(32) { 8 })
        val token = codec.newToken()
        val replacement = if (token.last() == 'A') 'B' else 'A'
        val tampered = token.dropLast(1) + replacement

        assertTrue(WorkflowIntentSecurity.isValidAuthToken(token))
        assertTrue(codec.isAuthentic(token))
        assertFalse(codec.isAuthentic(tampered))
        assertFalse(otherInstallation.isAuthentic(token))
    }

    @Test
    fun signingSecretIsStableOnlyWithinTheNoBackupStore() {
        var persisted: ByteArray? = null
        var generated = 0
        val first = loadOrCreateWorkflowSigningSecret(
            readExisting = { persisted },
            writeNew = { persisted = it.copyOf() },
            randomBytes = { size -> ByteArray(size) { 9 }.also { generated++ } },
        )
        val second = loadOrCreateWorkflowSigningSecret(
            readExisting = { persisted },
            writeNew = { error("must not rewrite a valid secret") },
        )

        assertArrayEquals(first, second)
        assertEquals(1, generated)
        val noBackupRoot = File("no-backup-root")
        assertEquals(noBackupRoot, workflowSigningSecretFile(noBackupRoot).parentFile)
    }

    @Test
    fun legacyBackupEligibleSecretFileIsExcludedFromBackupAndTransfer() {
        listOf(
            File("src/main/res/xml/backup_rules.xml"),
            File("src/main/res/xml/data_extraction_rules.xml"),
        ).forEach { rulesFile ->
            assertTrue("Missing backup rules: ${rulesFile.absolutePath}", rulesFile.isFile)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
            val excludes = document.getElementsByTagName("exclude")
            val hasLegacySecretExclusion = (0 until excludes.length).any { index ->
                val attributes = excludes.item(index).attributes
                attributes.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                    attributes.getNamedItem("path")?.nodeValue == "workflow_auth_tokens.xml"
            }
            assertTrue("Legacy signing secret must be excluded in ${rulesFile.name}", hasLegacySecretExclusion)
        }
    }

    @Test
    fun restoredDefinitionTokenIsRejectedByNewInstallationAndRotatedBeforeUse() {
        val oldInstallation = WorkflowAuthTokenCodec(ByteArray(32) { 1 })
        val newInstallation = WorkflowAuthTokenCodec(ByteArray(32) { 2 })
        val oldToken = oldInstallation.newToken()
        val workflow = Workflow(
            id = "restored",
            nodes = listOf(
                TriggerNode(
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to
                            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to oldToken,
                    ),
                )
            ),
        )

        assertFalse(newInstallation.isAuthentic(oldToken))
        val normalized = WorkflowIntentSecurity.normalizeExternalTriggerTokens(
            workflow = workflow,
            tokenValidator = newInstallation::isAuthentic,
            tokenFactory = newInstallation::newToken,
        )
        val replacement = (normalized.nodes.single() as TriggerNode)
            .triggerConfig.getValue(WorkflowIntentSecurity.CONFIG_AUTH_TOKEN)
        assertTrue(newInstallation.isAuthentic(replacement))
        assertFalse(oldInstallation.isAuthentic(replacement))
    }
}
