package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in live validation. The caller supplies a newly created, isolated validation chat.
 * Uses a disposable model configuration; never changes the user's existing configuration.
 * Run by installing APKs with -r and am instrument, never a connected Gradle lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class CollaborationCompactionLiveAndroidTest {
    @Test fun checkpointContinuesSameAgentAndRetainsPostCheckpointAnswer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("v2LiveValidation") == "true")
        val rootId = requireNotNull(args.getString("v2ValidationChatId"))
        val modelId = requireNotNull(args.getString("v2ValidationModelId"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Instrumentation starts Application.onCreate, but not the Activity/service startup
        // gate that initializes permission preferences and the tool runtime.
        val core = withContext(Dispatchers.Main) {
            val initialized = OperitApplication.initializeMainApplication(context)
            check(initialized == OperitApplication.MainApplicationInitResult.Initialized) {
                "Application initialization did not permit live validation"
            }
            ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
        }
        AgentProfileRepository.instance.initialize(context)
        requireNotNull(core.getChatMetadata(rootId))
        require(core.getChatHistoryDelegate().getChatHistory(rootId).isEmpty()) {
            "Live validation requires a new empty isolated root chat"
        }
        val models = ModelConfigManager(context)
        val original = requireNotNull(models.getModelConfig(modelId))
        val temporaryId = models.createConfig("V2 validation only - compaction")
        val coordinator = CollaborationCoordinator.getInstance(context)
        var child: CollaborationAgent? = null
        try {
            models.saveModelConfig(original.copy(
                id = temporaryId, name = "V2 validation only - compaction",
                summaryTokenThreshold = 0.000001f,
            ))
            child = coordinator.spawn(
                rootId, "checkpoint", "Choose a random 12-character alphanumeric token yourself. " +
                    "Reply only FIRST_COMPLETE followed by that token, without any tools.",
                "general", AgentFork.None, temporaryId, 0, null,
            )
            val childId = child.chatId
            suspend fun terminal(previousResults: Set<String>): Pair<CollaborationAgent, AgentMessage> = withTimeout(180_000) {
                while (true) {
                    val current = coordinator.list(rootId, null).single { it.chatId == childId }
                    val result = coordinator.caller(rootId).messages.firstOrNull {
                        it.sender == current.path && it.kind == AgentMessageKind.FINAL_ANSWER &&
                            it.id !in previousResults
                    }
                    if (result != null && current.status == CollaborationStatus.COMPLETED) {
                        return@withTimeout current to result
                    }
                    check(current.status !in setOf(CollaborationStatus.FAILED, CollaborationStatus.INTERRUPTED)) {
                        current.lastError ?: "Agent ended without a final answer"
                    }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            val (first, firstResult) = terminal(emptySet())
            assertEquals(first.lastError, CollaborationStatus.COMPLETED, first.status)
            val nonce = requireNotNull(Regex("\\bFIRST_COMPLETE\\s*([A-Za-z0-9]{12})\\b")
                .find(firstResult.text)) { "Expected a fresh generated token: ${firstResult.text}" }.groupValues[1]
            assertFalse("Generated token must not already be in the checkpoint",
                first.inheritedHistory.any { it.content.contains(nonce) })
            assertNotNull("Initial request must compact in the same turn", first.historyCutoff)
            val firstTranscript = core.getChatHistoryDelegate().getChatHistory(first.chatId)
            assertTrue("The final answer must survive after the checkpoint cutoff",
                firstTranscript.any {
                    it.timestamp > requireNotNull(first.historyCutoff) && it.content.contains("FIRST_COMPLETE")
                })
            // Disable forced compaction only on the disposable configuration for the next turn.
            models.saveModelConfig(original.copy(
                id = temporaryId, name = "V2 validation only - compaction",
            ))
            val previousResults = coordinator.caller(rootId).messages.map { it.id }.toSet()
            coordinator.send(rootId, first.path,
                "Without tools, repeat verbatim the response you gave last time, including your " +
                    "random token, then append SECOND_COMPLETE.", true)
            val (second, result) = terminal(previousResults)
            assertEquals(first.chatId, second.chatId)
            assertEquals(first.runId, second.runId)
            assertEquals(second.lastError, CollaborationStatus.COMPLETED, second.status)
            assertTrue(result.text, result.text.contains(nonce))
            assertTrue(result.text, result.text.contains("FIRST_COMPLETE"))
            assertTrue(result.text, result.text.contains("SECOND_COMPLETE"))
        } finally {
            try {
                child?.let { coordinator.interrupt(rootId, it.path) }
            } finally {
                models.deleteConfig(temporaryId)
            }
        }
    }
}
