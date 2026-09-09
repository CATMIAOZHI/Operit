package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.core.chat.TurnInputInbox
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationWaitDeliveryTest {
    private fun pending(inbox: TurnInputInbox) = when (inbox.pendingInputKind()) {
        TurnInputInbox.PendingInputKind.USER -> CollaborationWaitOutcome.STEERED
        TurnInputInbox.PendingInputKind.AGENT -> CollaborationWaitOutcome.MAILBOX
        null -> null
    }

    @Test(timeout = 5_000) fun storageArrivalCannotWakeBeforeBodyReachesExecutionInbox() = runBlocking {
        val inbox = TurnInputInbox()
        val reconciliationStarted = CompletableDeferred<Unit>()
        val allowDelivery = CompletableDeferred<Unit>()
        var acknowledged = 0
        val stored = TurnInputInbox.Input(
            "actual message body", agentPath = "/root/child", consumed = { acknowledged++ },
        )
        val waiter = async {
            awaitCollaborationInput(2_000, {
                reconciliationStarted.complete(Unit)
                allowDelivery.await()
                inbox.offer(stored)
            }, { pending(inbox) })
        }
        reconciliationStarted.await()
        // The stored message already exists, but history reconciliation is still blocked.
        assertFalse(waiter.isCompleted)
        assertFalse(inbox.hasPending())
        allowDelivery.complete(Unit)
        assertEquals(CollaborationWaitOutcome.MAILBOX, waiter.await())
        assertEquals(0, acknowledged)
        assertEquals(listOf(stored), inbox.drain())
        inbox.acknowledge()
        assertEquals(1, acknowledged)
    }

    @Test(timeout = 5_000) fun reconciledOrRejectedMessagesDoNotProduceFalseWakeups() = runBlocking {
        val inbox = TurnInputInbox()
        // Recovery found the stored message was already committed: no input is offered.
        assertEquals(CollaborationWaitOutcome.TIMED_OUT,
            awaitCollaborationInput(20, {}, { pending(inbox) }))
        inbox.seal()
        var acknowledged = false
        val stored = TurnInputInbox.Input("still queued", agentPath = "/root/child",
            consumed = { acknowledged = true })
        assertEquals(CollaborationWaitOutcome.TIMED_OUT,
            awaitCollaborationInput(20, { assertFalse(inbox.offer(stored)) }, { pending(inbox) }))
        assertFalse(acknowledged)
    }

    @Test(timeout = 5_000) fun userInputHasPriorityBeforeAndAfterDeliveryWithoutConsumingEither() = runBlocking {
        for (alreadyPending in listOf(true, false)) {
            val inbox = TurnInputInbox()
            fun offerBoth() {
                inbox.offer(TurnInputInbox.Input("peer body", agentPath = "/root/peer"))
                inbox.offer(TurnInputInbox.Input("user correction"))
            }
            if (alreadyPending) offerBoth()
            var flushed = false
            val result = awaitCollaborationInput(2_000, {
                flushed = true
                offerBoth()
            }, { pending(inbox) })
            assertEquals(CollaborationWaitOutcome.STEERED, result)
            assertEquals(!alreadyPending, flushed)
            assertEquals(listOf("peer body", "user correction"), inbox.drain().map { it.text })
        }
    }

    @Test(timeout = 5_000) fun cancellationPropagatesRatherThanReportingAnArrival() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val waiter = async {
            awaitCollaborationInput(2_000, {
                started.complete(Unit)
                awaitCancellation()
            }, { null })
        }
        started.await()
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
    }
}
