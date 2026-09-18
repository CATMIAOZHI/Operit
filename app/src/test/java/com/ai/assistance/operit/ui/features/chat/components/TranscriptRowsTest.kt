package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import org.junit.Assert.*
import org.junit.Test

class TranscriptRowsTest {
    @Test fun activeTurnKeepsEventsInOneCardBeforeACollapseGroupExists() {
        val messages = listOf(
            ChatMessage(timestamp = 1, sender = "user", content = "start", sentAt = 100),
            ChatMessage(timestamp = 2, sender = "ai", content = "working", sentAt = 100,
                displayMode = ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE),
            ChatMessage(timestamp = 3, sender = "user", content = "update",
                displayMode = ChatMessageDisplayMode.COLLABORATION_EVENT),
            ChatMessage(timestamp = 4, sender = "ai", content = "continue", sentAt = 100),
        )
        val rows = withCardEnds(transcriptRows(messages, ResponseProcessState(emptyMap(), { true }, {}, {})))
        assertEquals(listOf(true, true, false, false), rows.map { it.cardFirst })
        assertEquals(listOf(true, false, false, true), rows.map { it.cardLast })
    }

    @Test fun slicesOfAnUngroupedMessageShareOneCard() {
        val rows = withCardEnds(listOf(
            TranscriptRow("first", 0, ResponseMessageSection.ALL),
            TranscriptRow("second", 0, ResponseMessageSection.ALL),
            TranscriptRow("other", 1, ResponseMessageSection.ALL),
        ))
        assertEquals(listOf(true, false, true), rows.map { it.cardFirst })
        assertEquals(listOf(false, true, true), rows.map { it.cardLast })
    }

    @Test fun aReplyWrittenAsSeveralMessagesClosesItsCardOnce() {
        // Waifu mode writes one reply as several messages that share a sentAt and repeat the turn's
        // totals, so only the last of them may close the card the statistics hang off.
        val messages =
            listOf(
                message(1, "user"),
                message(2, "ai").copy(sentAt = 100),
                message(3, "ai").copy(sentAt = 100),
                message(4, "ai").copy(sentAt = 100),
            )

        val rows = withCardEnds(transcriptRows(messages, ResponseProcessState(emptyMap(), { true }, {}, {})))
        val reply = rows.drop(1)

        assertEquals(listOf(true, false, false), reply.map { it.cardFirst })
        assertEquals(listOf(false, false, true), reply.map { it.cardLast })
    }

    @Test fun selectionKeepsIdentityWhenProcessRowsAreInserted() {
        val selected = TranscriptSelection()
        fun message(timestamp: Long) = ChatMessage(timestamp = timestamp, sender = "ai", content = "")
        var before by selected.forMessages(listOf(message(20)))
        before = setOf(0)
        var after by selected.forMessages(listOf(message(10), message(11), message(20)))
        assertEquals(setOf(2), after)
        assertEquals(setOf(20L), selected.timestamps)
        after = emptySet()
        assertTrue(before.isEmpty())
    }

    @Test fun finalOnlyWindowStillHasSummaryAndFinalBody() {
        val group = ResponseProcessGroup(10, 0, 0, 50, 0)
        val state = ResponseProcessState(mapOf(0 to group), { false }, {}, {})
        val rows = transcriptRows(listOf(ChatMessage(timestamp = 20, sender = "ai", content = "final")), state)
        assertEquals(listOf("process:10", "message:20"), rows.map { it.key })
        assertEquals(ResponseMessageSection.BODY, rows.last().section)
    }

    @Test fun expansionKeepsSummaryKeyAndPagesBeforeFinal() {
        val messages = listOf(11L, 20L).map { ChatMessage(timestamp = it, sender = "ai", content = "") }
        val group = ResponseProcessGroup(10, 0, 1, 50)
        val state = ResponseProcessState(
            mapOf(0 to group, 1 to group), { true }, {}, {}, { true },
        )
        assertEquals(
            listOf("process:10", "message:11", "more:10", "message:20"),
            transcriptRows(messages, state).map { it.key },
        )
    }

    @Test fun cardsOfATurnStayWhereTheyHappenedInsideTheTurnsCard() {
        val messages =
            listOf(
                message(1, "user"),
                message(11, "ai", ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE),
                message(12, "user", ChatMessageDisplayMode.COLLABORATION_EVENT),
                message(13, "ai", ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE),
                message(14, "user", ChatMessageDisplayMode.COLLABORATION_EVENT),
                message(15, "ai"),
            )
        val group = ResponseProcessGroup(100, 1, 5, 50, 1)
        val state = ResponseProcessState((1..5).associateWith { group }, { true }, {}, {})

        val rows = withCardEnds(transcriptRows(messages, state))

        // A card is a step of the turn, so it keeps its place in the sequence of that turn.
        assertEquals(
            listOf("message:1", "process:100", "message:11", "message:12", "message:13", "message:14", "message:15"),
            rows.map { it.key },
        )
        // The whole turn still renders as one card: only its first and last rows round it off, and
        // everything between them - process, cards, the answer - sits inside that same card.
        val turn = rows.filter { it.group != null && it.section != ResponseMessageSection.HEADER }
        assertEquals(
            listOf("message:11"),
            turn.filter { it.cardFirst }.map { it.key },
        )
        assertEquals(
            listOf("message:15"),
            turn.filter { it.cardLast }.map { it.key },
        )
        // The summary row renders the identity, not the card.
        val summary = rows.single { it.key == "process:100" }
        assertTrue(summary.cardFirst && summary.cardLast)
    }

    @Test fun aFoldedProcessKeepsItsProcessAndCardsHidden() {
        val messages =
            listOf(
                message(1, "user"),
                message(11, "ai", ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE),
                message(12, "user", ChatMessageDisplayMode.COLLABORATION_EVENT),
                message(13, "ai"),
                message(14, "user", ChatMessageDisplayMode.COLLABORATION_EVENT),
            )
        val group = ResponseProcessGroup(100, 1, 3, 50, 1)
        val state =
            ResponseProcessState(
                mapOf(1 to group, 2 to group, 3 to group),
                { false }, {}, {},
            )

        val rows = transcriptRows(messages, state)

        // Nothing of the folded process shows, and the card with no reply yet keeps its own row.
        assertEquals(listOf("message:1", "process:100", "message:13", "message:14"), rows.map { it.key })
    }

    @Test fun aMessageOutsideAnyTurnIsAWholeCardOfItsOwn() {
        val messages =
            listOf(
                message(1, "user"),
                message(2, "ai"),
                message(3, "user", ChatMessageDisplayMode.COLLABORATION_EVENT),
            )

        val rows = withCardEnds(transcriptRows(messages, ResponseProcessState(emptyMap(), { true }, {}, {})))

        assertEquals(listOf("message:1", "message:2", "message:3"), rows.map { it.key })
        assertTrue(rows.all { it.cardFirst && it.cardLast })
    }

    private fun message(
        timestamp: Long,
        sender: String,
        displayMode: ChatMessageDisplayMode = ChatMessageDisplayMode.NORMAL,
    ) =
        ChatMessage(
            sender = sender,
            content = "message $timestamp",
            timestamp = timestamp,
            displayMode = displayMode,
        )
}
