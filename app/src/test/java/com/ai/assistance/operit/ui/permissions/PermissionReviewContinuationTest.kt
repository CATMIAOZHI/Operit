package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A review may only continue an earlier reviewer run while the conversation that run holds is provably
 * the same one: the same policy, retained instructions, workspace, and model, and a history that still
 * starts with the entries that run read. Everything else has to rebuild the whole prompt, because the
 * earlier message is what carries the policy, the evidence rules, and the retained instructions.
 */
class PermissionReviewContinuationTest {
    private val retainedHash = "retained-hash"
    private val policyVersion = "policy-version"
    private val workspaceKey = "workspace-path|workspace-env"
    private val modelKey = "config#0"

    private fun message(
        timestamp: Long,
        sender: String = "user",
        content: String = "content-$timestamp",
        roleName: String = sender,
    ) = PermissionReviewTranscriptMessage(
        timestamp = timestamp,
        sender = sender,
        roleName = roleName,
        content = content,
    )

    /** The continuation a review of [historyRead] would leave behind. */
    private fun continuationFor(
        historyRead: List<PermissionReviewTranscriptMessage>,
        timingScopeId: String? = null,
        policy: String = "policy-version",
        retainedInstructionsHash: String = "retained-hash",
        workspace: String = "workspace-path|workspace-env",
        model: String = "config#0",
        reviewerTaskId: String = "task-1",
    ) = PermissionReviewContinuation(
        reviewerTaskId = reviewerTaskId,
        cursor = requireNotNull(permissionReviewCursor(historyRead, timingScopeId)) {
            "the cursor needs a history to point at"
        },
        policyVersion = policy,
        retainedInstructionsHash = retainedInstructionsHash,
        workspaceKey = workspace,
        modelKey = model,
    )

    private fun delta(
        continuation: PermissionReviewContinuation?,
        history: List<PermissionReviewTranscriptMessage>,
        timingScopeId: String? = null,
        liveAssistantContent: String? = null,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
        conversationIsIntact: Boolean = true,
    ) = permissionReviewDelta(
        continuation = continuation,
        conversationIsIntact = conversationIsIntact,
        policyVersion = policyVersion,
        retainedInstructionsHash = retainedHash,
        workspaceKey = workspaceKey,
        modelKey = modelKey,
        history = history,
        timingScopeId = timingScopeId,
        liveAssistantContent = liveAssistantContent,
        maxChars = maxChars,
    )

    @Test
    fun continuesWhenTheHistoryOnlyGrew() {
        val read = listOf(message(100), message(200))
        val history = read + message(300)

        val result = delta(continuationFor(read), history)

        assertNotNull(result)
        assertEquals("task-1", result!!.reviewerTaskId)
        assertEquals(
            "exactly the entries after the cursor, and no others",
            "[user]\ncontent-300\n",
            result.addedTranscript,
        )
    }

    @Test
    fun continuesWithNothingAddedWhenTheHistoryIsUnchanged() {
        val history = listOf(message(100), message(200))

        val result = delta(continuationFor(history), history)

        assertNotNull(result)
        assertEquals(NO_ADDED_TRANSCRIPT_TEXT, result!!.addedTranscript)
    }

    @Test
    fun rebuildsWhenNoEarlierRunIsRemembered() {
        assertNull(delta(null, listOf(message(100))))
    }

    @Test
    fun rebuildsWhenTheEarlierRunLostItsPrompt() {
        val history = listOf(message(100), message(200))

        assertNull(delta(continuationFor(history), history, conversationIsIntact = false))
    }

    @Test
    fun rebuildsWhenTheHistoryNoLongerStartsWithWhatThatRunRead() {
        val read = listOf(message(100), message(200), message(300))
        val rewritten = listOf(message(200), message(300), message(400))

        assertNull(delta(continuationFor(read), rewritten))
    }

    @Test
    fun rebuildsWhenAnEntryThatRunReadWasEditedInPlace() {
        val read = listOf(message(100, content = "before"), message(200))
        val edited = listOf(message(100, content = "after"), message(200), message(300))

        assertNull(
            "an edited prefix is not the prefix the run read",
            delta(continuationFor(read), edited),
        )
    }

    @Test
    fun rebuildsWhenTheHistoryShrank() {
        val read = listOf(message(100), message(200), message(300), message(400))
        val shorter = listOf(message(100), message(200))

        assertNull(delta(continuationFor(read), shorter))
    }

    @Test
    fun rebuildsWhenThePolicyChanged() {
        val read = listOf(message(100))

        assertNull(delta(continuationFor(read, policy = "other-policy"), read + message(200)))
    }

    @Test
    fun rebuildsWhenTheWorkspaceChanged() {
        val read = listOf(message(100))

        assertNull(
            delta(continuationFor(read, workspace = "other-path|(default)"), read + message(200))
        )
    }

    @Test
    fun rebuildsWhenTheRetainedInstructionsChanged() {
        val read = listOf(message(100))

        assertNull(
            delta(
                continuationFor(read, retainedInstructionsHash = "other-hash"),
                read + message(200),
            )
        )
    }

    @Test
    fun rebuildsWhenTheReviewerModelChanged() {
        val read = listOf(message(100))

        assertNull(delta(continuationFor(read, model = "other#7"), read + message(200)))
    }

    @Test
    fun rebuildsWhenTheAddedEntriesWouldNotFitOneBudget() {
        val read = listOf(message(100))
        val history = read + message(200, content = "x".repeat(200))

        assertNull(delta(continuationFor(read), history, maxChars = 50))
    }

    @Test
    fun sendsTheLiveAssistantEntryInsteadOfThePersistedCopy() {
        val read = listOf(message(100))
        val history = read + message(200, sender = "ai", roleName = "assistant")

        val result =
            delta(
                continuationFor(read),
                history,
                timingScopeId = "200",
                liveAssistantContent = "the longer answer",
            )

        assertNotNull(result)
        assertEquals("[assistant]\nthe longer answer\n", result!!.addedTranscript)
    }

    @Test
    fun theWorkspaceKeyIsSpelledTheWayThePromptRendersIt() {
        assertEquals(
            "a-path|an-env",
            permissionReviewWorkspaceKey(
                ToolPermissionReviewContext(workspacePath = "a-path", workspaceEnv = "an-env")
            ),
        )
        assertEquals(
            "(none)|(default)",
            permissionReviewWorkspaceKey(ToolPermissionReviewContext()),
        )
        assertNotEquals(
            permissionReviewWorkspaceKey(ToolPermissionReviewContext(workspacePath = "a-path")),
            permissionReviewWorkspaceKey(ToolPermissionReviewContext(workspacePath = "b-path")),
        )
    }

    @Test
    fun cursorIsNullForAnEmptyHistory() {
        assertNull(permissionReviewCursor(emptyList(), null))
    }

    @Test
    fun cursorIsNullWhenNothingHasSettledYet() {
        val streaming = listOf(message(200, sender = "ai", roleName = "assistant"))

        assertNull(permissionReviewCursor(streaming, "200"))
    }

    @Test
    fun theLiveAnswerStaysOutOfTheCursorSoTheTurnCanStillContinue() {
        val user = message(100)
        val firstRead = listOf(user, message(200, sender = "ai", roleName = "assistant", content = "half"))
        val continuation = continuationFor(firstRead, timingScopeId = "200")

        assertEquals(
            "the cursor stops before the answer that is still being written",
            1,
            continuation.cursor.historySize,
        )

        val laterRead =
            listOf(user, message(200, sender = "ai", roleName = "assistant", content = "the whole answer"))
        val result = delta(continuation, laterRead, timingScopeId = "200", liveAssistantContent = "the whole answer")

        assertNotNull(
            "the answer growing must not refuse the continuation",
            result,
        )
        assertEquals("[assistant]\nthe whole answer\n", result!!.addedTranscript)
    }

    @Test
    fun everySettledEntryIsStillInTheCursor() {
        val history = listOf(message(100), message(200))

        assertEquals("no turn under review means nothing is volatile", 2, permissionReviewStableHistorySize(history, null))
        assertEquals(
            "an answer from another turn is settled",
            2,
            permissionReviewStableHistorySize(history, "300"),
        )
        assertEquals(
            "the user turn is settled even while its answer is written",
            1,
            permissionReviewStableHistorySize(
                listOf(message(100), message(200, sender = "ai", roleName = "assistant")),
                "200",
            ),
        )
    }

    @Test
    fun continuesWithoutLiveContentWhileTheAnswerIsStillBeingWritten() {
        val read = listOf(message(100), message(200, sender = "ai", roleName = "assistant", content = "half"))
        val continuation = continuationFor(read, timingScopeId = "200")
        val laterRead =
            listOf(message(100), message(200, sender = "ai", roleName = "assistant", content = "the whole answer"))

        val result = delta(continuation, laterRead, timingScopeId = "200")

        assertNotNull(result)
        assertEquals("[assistant]\nthe whole answer\n", result!!.addedTranscript)
    }

    @Test
    fun aMessageBehindTheRunningAnswerForcesTheFullPrompt() {
        val history =
            listOf(
                message(100),
                message(200, sender = "ai", roleName = "assistant", content = "half"),
                message(300),
            )
        val continuation = continuationFor(history, timingScopeId = "200")

        assertEquals(
            "the answer is only left out while it is the last entry",
            3,
            continuation.cursor.historySize,
        )

        val rewritten =
            listOf(
                message(100),
                message(200, sender = "ai", roleName = "assistant", content = "the whole answer"),
                message(300),
            )
        assertNull(delta(continuation, rewritten, timingScopeId = "200"))
    }

    @Test
    fun cursorPinsTheWholePrefixItRead() {
        val history = listOf(message(100), message(200))

        assertEquals(
            PermissionReviewCursor(
                historySize = 2,
                prefixHash = permissionReviewHistoryHash(history, 2),
            ),
            permissionReviewCursor(history, null),
        )
        assertNotEquals(
            "a longer prefix is a different prefix",
            permissionReviewHistoryHash(history, 1),
            permissionReviewHistoryHash(history, 2),
        )
    }

    @Test
    fun thePrefixHashCoversEveryFieldOfEveryEntry() {
        val base = listOf(message(100, content = "content"), message(200))
        val baseHash = permissionReviewHistoryHash(base, 2)

        assertNotEquals(
            "an edited body is a different prefix",
            baseHash,
            permissionReviewHistoryHash(listOf(message(100, content = "other"), message(200)), 2),
        )
        assertNotEquals(
            "a moved timestamp is a different prefix",
            baseHash,
            permissionReviewHistoryHash(listOf(message(101, content = "content"), message(200)), 2),
        )
        assertNotEquals(
            "a renamed sender is a different prefix",
            baseHash,
            permissionReviewHistoryHash(
                listOf(
                    message(100, content = "content", sender = "ai", roleName = "assistant"),
                    message(200),
                ),
                2,
            ),
        )
        assertEquals(
            "the same entries hash the same",
            baseHash,
            permissionReviewHistoryHash(listOf(message(100, content = "content"), message(200)), 2),
        )
    }

    @Test
    fun theContinuationPromptEndsWithTheAction() {
        val action = """{"tool":"terminal","kind":"command"}"""

        val prompt =
            buildReviewContinuationPrompt(
                reviewId = "review-1",
                policyVersion = "v3",
                batchPosition = 2,
                batchSize = 3,
                exactOverrideReviewId = null,
                addedTranscript = "[user]\nhello\n",
                actionJson = action,
            )

        assertTrue(prompt.endsWith(action))
        assertTrue(prompt.contains("PARENT TRANSCRIPT ENTRIES ADDED SINCE YOUR LAST ASSESSMENT:"))
        assertTrue(prompt.contains("[user]\nhello\n"))
        assertTrue(prompt.contains("review_id=review-1"))
        assertTrue(prompt.contains("batch_item=2/3"))
        assertTrue(prompt.contains("exact_one_time_user_override=none"))
        assertTrue(prompt.contains("Continue the same review conversation"))
        assertTrue(prompt.contains(PermissionReviewSubmissionTool.NAME))
        assertFalse(
            "the continuation must not re-send the retained-instructions block",
            prompt.contains("RETAINED USER INSTRUCTIONS"),
        )
        assertFalse(
            "the marker is what tells a full prompt from a delta, so a delta must never carry it",
            prompt.contains(REVIEW_PROMPT_MARKER),
        )
    }

    @Test
    fun theContinuationPromptNamesAnExactOverride() {
        val prompt =
            buildReviewContinuationPrompt(
                reviewId = "review-1",
                policyVersion = "v3",
                batchPosition = 1,
                batchSize = 1,
                exactOverrideReviewId = "review-0",
                addedTranscript = NO_ADDED_TRANSCRIPT_TEXT,
                actionJson = "{}",
            )

        assertTrue(prompt.contains("exact_one_time_user_override=review-0"))
    }

    @Test
    fun oneChatKeepsOneLockSoItsReviewsAreSerialized() {
        assertSame(
            PermissionReviewContinuationStore.chatLock("continuation-lock-a"),
            PermissionReviewContinuationStore.chatLock("continuation-lock-a"),
        )
        assertNotSame(
            PermissionReviewContinuationStore.chatLock("continuation-lock-a"),
            PermissionReviewContinuationStore.chatLock("continuation-lock-b"),
        )
    }

    @Test
    fun aHeldLockIsNeverHandedToAnotherReview() {
        val held = "continuation-held-lock"
        val lock = PermissionReviewContinuationStore.chatLock(held)
        runBlocking { lock.lock() }
        try {
            for (index in 0..PermissionReviewContinuationStore.MAX_TRACKED_CHATS) {
                PermissionReviewContinuationStore.chatLock("continuation-pressure-$index")
            }

            assertSame(
                "a lock another review holds must not be dropped and recreated",
                lock,
                PermissionReviewContinuationStore.chatLock(held),
            )
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun theStoreRemembersOneContinuationPerChat() {
        val chat = "continuation-store-chat"
        try {
            PermissionReviewContinuationStore.record(
                chat,
                continuationFor(listOf(message(100)), reviewerTaskId = "task-1"),
            )
            PermissionReviewContinuationStore.record(
                chat,
                continuationFor(
                    listOf(message(100), message(200)),
                    reviewerTaskId = "task-2",
                ),
            )

            val stored = PermissionReviewContinuationStore.get(chat)
            assertEquals("task-2", stored?.reviewerTaskId)
            assertEquals(2, stored?.cursor?.historySize)

            PermissionReviewContinuationStore.forget(chat)
            assertNull(PermissionReviewContinuationStore.get(chat))
        } finally {
            PermissionReviewContinuationStore.forget(chat)
        }
    }

    @Test
    fun theStoreThrowsTheOldestChatAwayInsteadOfGrowing() {
        val prefix = "continuation-bounded-"
        try {
            for (index in 0..PermissionReviewContinuationStore.MAX_TRACKED_CHATS) {
                PermissionReviewContinuationStore.record(
                    "$prefix$index",
                    continuationFor(listOf(message(100))),
                )
            }

            assertNull(
                "the chat recorded first is the one that had to go",
                PermissionReviewContinuationStore.get("$prefix" + "0"),
            )
            assertNotNull(
                PermissionReviewContinuationStore.get(
                    "$prefix${PermissionReviewContinuationStore.MAX_TRACKED_CHATS}"
                )
            )
        } finally {
            for (index in 0..PermissionReviewContinuationStore.MAX_TRACKED_CHATS) {
                PermissionReviewContinuationStore.forget("$prefix$index")
            }
        }
    }
}
