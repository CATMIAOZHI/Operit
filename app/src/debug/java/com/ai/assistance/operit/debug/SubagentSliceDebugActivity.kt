package com.ai.assistance.operit.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.SubagentSliceCoordinator
import com.ai.assistance.operit.util.AppLogger
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Debug-only adb entry used to exercise the Phase-0 Subagent vertical slice on a real app runtime.
 */
class SubagentSliceDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val title =
            intent.getStringExtra(EXTRA_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_TITLE
        val cancelAfterMs = intent.getLongExtra(EXTRA_CANCEL_AFTER_MS, 0L)
        val core =
            ChatRuntimeHolder.getInstance(applicationContext)
                .getCore(ChatRuntimeSlot.MAIN)
        val parentChatId =
            intent.getStringExtra(EXTRA_PARENT_CHAT_ID)
                ?.takeIf { it.isNotBlank() }
                ?: core.currentChatId.value

        if (prompt.isBlank() || parentChatId.isNullOrBlank()) {
            AppLogger.e(
                TAG,
                "SUBAGENT_SLICE_FAILED missing prompt or parent chat: parentChatId=$parentChatId",
            )
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val result =
                    if (cancelAfterMs > 0L) {
                        withTimeout(cancelAfterMs) {
                            SubagentSliceCoordinator(applicationContext)
                                .runExplore(
                                    parentChatId = parentChatId,
                                    title = title,
                                    prompt = prompt,
                                )
                        }
                    } else {
                        SubagentSliceCoordinator(applicationContext)
                            .runExplore(
                                parentChatId = parentChatId,
                                title = title,
                                prompt = prompt,
                            )
                    }
                val (run, outcome) = result
                AppLogger.i(
                    TAG,
                    "SUBAGENT_SLICE_COMPLETED taskId=${run.taskId} " +
                        "parentChatId=${run.parentChatId} childChatId=${run.childChatId} " +
                        "resultLength=${outcome.finalAssistantText.length} " +
                        "resultSha256=${outcome.finalAssistantText.sha256()}",
                )
            } catch (error: CancellationException) {
                AppLogger.i(
                    TAG,
                    "SUBAGENT_SLICE_CANCELLED cancelAfterMs=$cancelAfterMs",
                )
            } catch (error: Exception) {
                AppLogger.e(TAG, "SUBAGENT_SLICE_FAILED", error)
            }
            finish()
        }
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private const val TAG = "SubagentSliceDebug"
        private const val EXTRA_PARENT_CHAT_ID = "parent_chat_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_CANCEL_AFTER_MS = "cancel_after_ms"
        private const val DEFAULT_TITLE = "[Subagent Slice] Explore verification"
    }
}
