package com.ai.assistance.operit.data.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStatsCleanupSnapshotGateTest {

    @Test
    fun `snapshot waits until cleanup outbox sequence leaves barrier`() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val snapshotEntered = CompletableDeferred<Unit>()

        val cleanup = async {
            TokenStatsResetCoordinator.withCleanupSnapshotAccess {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            }
        }
        cleanupEntered.await()
        val snapshot = async {
            TokenStatsResetCoordinator.withCleanupSnapshotAccess {
                snapshotEntered.complete(Unit)
            }
        }

        assertNull(withTimeoutOrNull(100L) { snapshotEntered.await() })
        releaseCleanup.complete(Unit)
        cleanup.await()
        snapshot.await()
        assertTrue(snapshotEntered.isCompleted)
    }
}
