package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a review reads has to keep the material an earlier review already sent in place, or a
 * provider that caches prompt prefixes can never reuse it. The reviewer's window also has to stay a
 * superset of what the older count-capped window showed, because a reviewer that reads less than
 * before approves and denies differently. A review that reads a partial window must say so, since a
 * missing instruction must never look like a grant.
 */
class PermissionReviewTranscriptTest {
    private fun message(
        timestamp: Long,
        sender: String,
        content: String = "content-$timestamp",
        roleName: String = sender,
        displayMode: ChatMessageDisplayMode = ChatMessageDisplayMode.NORMAL,
    ) = PermissionReviewTranscriptMessage(
        timestamp = timestamp,
        sender = sender,
        roleName = roleName,
        content = content,
        displayMode = displayMode,
    )

    private fun entry(rendered: String, isUser: Boolean = false) =
        PermissionReviewTranscriptEntry(rendered = rendered, isUser = isUser)

    private fun render(
        entries: List<PermissionReviewTranscriptEntry>,
        maxMessages: Int = REVIEWER_MAX_TRANSCRIPT_MESSAGES,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
    ) = renderPermissionReviewTranscriptWindow(
        entries = entries,
        maxMessages = maxMessages,
        maxChars = maxChars,
    )

    /**
     * A turn the app delivered is stored as a user turn, so the row's own role name is the owner's.
     * The window is where the delivery has to show, because the reviewer is told to read a delivered
     * turn as evidence and cannot do that while the label says the owner wrote it.
     */
    @Test
    fun namesADeliveredTurnAsADelivery() {
        val entries =
            permissionReviewTranscriptEntries(
                candidates =
                    listOf(
                        message(
                            timestamp = 1,
                            sender = "user",
                            roleName = "用户",
                            displayMode = ChatMessageDisplayMode.TOOL_DELIVERED,
                        ),
                        message(
                            timestamp = 2,
                            sender = "user",
                            content = "do not push yet",
                            roleName = "用户",
                        ),
                    ),
                maxMessageChars = MAX_TRANSCRIPT_MESSAGE_CHARS,
            )

        assertEquals("[$DELIVERED_TURN_LABEL]\ncontent-1\n", entries.first().rendered)
        assertTrue(
            "the delivered row is still the turn an action answers, so the anchor can use it",
            entries.first().isUser,
        )
        assertEquals("[用户]\ndo not push yet\n", entries.last().rendered)
    }

    /**
     * Pins the reviewer's ceiling. Forty entries is well past the twelve an earlier count cap cut at,
     * so re-introducing a count cap for the reviewer fails here instead of quietly shrinking what the
     * blocking reviewer reads.
     */
    @Test
    fun keepsEveryEntryTheReviewerCeilingAllows() {
        val markers = (1..40).map { index -> "entry-${index.toString().padStart(2, '0')}" }
        val entries = markers.map { marker -> entry("[assistant]\n$marker\n") }

        val rendered = render(entries = entries, maxMessages = REVIEWER_MAX_TRANSCRIPT_MESSAGES)

        for (marker in markers) {
            assertTrue("$marker is still in the window", rendered.contains(marker))
        }
        assertFalse(rendered.contains(OMITTED_ENTRIES_NOTICE))
    }

    @Test
    fun keepsEarlierEntriesInPlaceWhenTheTurnGrows() {
        val user = entry("[user]\nbuild it\n", isUser = true)
        val firstAnswer = entry("[assistant]\nworking\n")
        val secondAnswer = entry("[assistant]\nstill working\n")

        val before = render(entries = listOf(user, firstAnswer))
        val after = render(entries = listOf(user, firstAnswer, secondAnswer))

        assertTrue(
            "a longer turn must extend the window instead of shifting it",
            after.startsWith(before),
        )
    }

    @Test
    fun reportsTheEntriesTheBudgetLeftOut() {
        val entries = listOf(entry("[user]\nfirst\n", isUser = true), entry("[assistant]\nsecond\n"))

        val rendered = render(entries = entries, maxChars = 10)

        assertTrue(rendered.contains(OMITTED_ENTRIES_NOTICE))
        assertFalse("the entry the budget dropped is not rendered", rendered.contains("second"))
    }

    @Test
    fun reportsAnOmissionWhenAMessageCountCeilingEndsTheWindow() {
        val entries =
            listOf(
                entry("[assistant]\nfirst\n"),
                entry("[assistant]\nsecond\n"),
                entry("[assistant]\nthird\n"),
            )

        val rendered = render(entries = entries, maxMessages = 2)

        assertTrue(rendered.contains(OMITTED_ENTRIES_NOTICE))
        assertTrue(rendered.indexOf("second") < rendered.indexOf("third"))
        assertFalse("the oldest entry is outside the ceiling", rendered.contains("first"))
    }

    @Test
    fun doesNotReportAnOmissionWhenEverythingFits() {
        val entries = listOf(entry("[user]\nfirst\n", isUser = true), entry("[assistant]\nsecond\n"))

        val rendered = render(entries = entries)

        assertFalse(rendered.contains(OMITTED_ENTRIES_NOTICE))
    }

    /**
     * The window is only offered the newest candidates, so what the tail cut never reaches it and it
     * cannot report it. The reviewer prompt reads a missing notice as "nothing was left out", so a
     * long conversation whose entries all fit the budget must still say its view is partial.
     */
    @Test
    fun reportsTheHistoryTheCandidateTailCutBeforeTheWindow() {
        val history =
            (1..MAX_TRANSCRIPT_CANDIDATES + 5).map { index ->
                message(timestamp = index.toLong(), sender = "user", content = "kept-$index")
            }

        val rendered =
            buildPermissionReviewTranscript(
                history = history,
                timingScopeId = null,
                liveAssistantContent = null,
                maxMessages = REVIEWER_MAX_TRANSCRIPT_MESSAGES,
            )

        assertTrue(rendered.contains(OMITTED_ENTRIES_NOTICE))
        assertFalse(
            "the message the candidate tail cut is not rendered",
            rendered.contains("kept-1\n"),
        )
        assertTrue("the newest message is still rendered", rendered.contains("kept-29\n"))
    }

    @Test
    fun doesNotReportAnOmissionWhenTheCandidateTailHoldsTheWholeHistory() {
        val history =
            (1..MAX_TRANSCRIPT_CANDIDATES).map { index ->
                message(timestamp = index.toLong(), sender = "user", content = "kept-$index")
            }

        val rendered =
            buildPermissionReviewTranscript(
                history = history,
                timingScopeId = null,
                liveAssistantContent = null,
                maxMessages = REVIEWER_MAX_TRANSCRIPT_MESSAGES,
            )

        assertFalse(rendered.contains(OMITTED_ENTRIES_NOTICE))
        assertTrue(rendered.contains("kept-1\n"))
    }

    @Test
    fun reAddsTheLastUserEntryWhenTheWindowLostItsUserTurn() {
        val request = "r".repeat(200)
        val entries =
            listOf(
                entry("[user]\n$request\n", isUser = true),
                entry("[assistant]\n" + "x".repeat(2000) + "\n"),
            )

        val rendered = render(entries = entries, maxChars = 100)

        assertTrue(rendered.contains(OMITTED_ENTRIES_NOTICE))
        assertTrue(
            "the re-added request is marked as an anchor",
            rendered.contains(USER_ANCHOR_PREFIX),
        )
        assertTrue(
            "the request must survive a window that had to drop it",
            rendered.contains(request),
        )
    }

    @Test
    fun reportsAnEmptyWindow() {
        assertEquals(NO_TRANSCRIPT_TEXT, render(entries = emptyList()))
    }

    @Test
    fun rendersTheRoleThatNamesTheSender() {
        val entries =
            permissionReviewTranscriptEntries(
                candidates = listOf(message(1, "user", "hello")),
                maxMessageChars = MAX_TRANSCRIPT_MESSAGE_CHARS,
            )

        assertEquals(1, entries.size)
        assertEquals("[user]\nhello\n", entries.single().rendered)
        assertTrue(entries.single().isUser)
    }

    @Test
    fun fallsBackToTheSenderWhenTheRoleNameIsBlank() {
        val entries =
            permissionReviewTranscriptEntries(
                candidates = listOf(message(1, "user", "hello", roleName = "")),
                maxMessageChars = MAX_TRANSCRIPT_MESSAGE_CHARS,
            )

        assertEquals(1, entries.size)
        assertEquals("[user]\nhello\n", entries.single().rendered)
        assertTrue(entries.single().isUser)
    }

    @Test
    fun dropsContentThatIsBlankAfterSanitizing() {
        val entries =
            permissionReviewTranscriptEntries(
                candidates = listOf(message(1, "user", "   "), message(2, "ai", "answer")),
                maxMessageChars = MAX_TRANSCRIPT_MESSAGE_CHARS,
            )

        assertEquals(1, entries.size)
        assertEquals("[ai]\nanswer\n", entries.single().rendered)
        assertFalse(entries.single().isUser)
    }

    @Test
    fun skipsTheEntryALiveMessageReplaces() {
        val entries =
            permissionReviewTranscriptEntries(
                candidates = listOf(message(1, "user", "hello"), message(2, "ai", "partial")),
                maxMessageChars = MAX_TRANSCRIPT_MESSAGE_CHARS,
                skipTimestamp = 2L,
            )

        assertEquals(1, entries.size)
        assertEquals("[user]\nhello\n", entries.single().rendered)
    }
}
