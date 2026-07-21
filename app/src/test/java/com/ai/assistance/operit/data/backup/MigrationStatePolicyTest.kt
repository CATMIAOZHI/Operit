package com.ai.assistance.operit.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationStatePolicyTest {
    @Test
    fun onlyIdleAndCompletedAllowMainDataAccess() {
        MigrationStateStore.State.entries.forEach { state ->
            val expected =
                state == MigrationStateStore.State.IDLE ||
                    state == MigrationStateStore.State.COMPLETED
            assertEquals(state.name, expected, state.allowsMainDataAccess())
        }
    }

    @Test
    fun pendingAndPreparingAreSafelyCancellable() {
        MigrationStateStore.State.entries.forEach { state ->
            val expected =
                state == MigrationStateStore.State.PENDING ||
                    state == MigrationStateStore.State.PREPARING
            if (expected) {
                assertTrue(state.name, MigrationStatePolicy.isSafelyCancellable(state))
            } else {
                assertFalse(state.name, MigrationStatePolicy.isSafelyCancellable(state))
            }
        }
    }

    @Test
    fun failureBeforeReplacementReturnsToIdle() {
        assertEquals(
            MigrationStateStore.State.IDLE,
            MigrationStatePolicy.stateAfterFailure(
                replacementStarted = false,
                processRestartRequired = false
            )
        )
    }

    @Test
    fun failureAfterStoresCloseRemainsBlockedUntilRestart() {
        assertEquals(
            MigrationStateStore.State.PREPARING,
            MigrationStatePolicy.stateAfterFailure(
                replacementStarted = false,
                processRestartRequired = true
            )
        )
    }

    @Test
    fun failureAfterReplacementStartsRequiresRecovery() {
        assertEquals(
            MigrationStateStore.State.FAILED,
            MigrationStatePolicy.stateAfterFailure(
                replacementStarted = true,
                processRestartRequired = true
            )
        )
    }

    @Test
    fun startupResetsPreparingButRecoversReplacingAndFailed() {
        assertEquals(
            MigrationStatePolicy.StartupAction.RESET_PREPARING,
            MigrationStatePolicy.startupAction(MigrationStateStore.State.PREPARING)
        )
        assertEquals(
            MigrationStatePolicy.StartupAction.SHOW_RECOVERY,
            MigrationStatePolicy.startupAction(MigrationStateStore.State.REPLACING)
        )
        assertEquals(
            MigrationStatePolicy.StartupAction.SHOW_RECOVERY,
            MigrationStatePolicy.startupAction(MigrationStateStore.State.FAILED)
        )
        assertEquals(
            MigrationStatePolicy.StartupAction.SHOW_RECOVERY,
            MigrationStatePolicy.startupAction(MigrationStateStore.State.NEEDS_RECOVERY)
        )
    }

    @Test
    fun activityRecreationKeepsProcessOwnedMigrationInProgress() {
        assertEquals(
            MigrationStatePolicy.StartupAction.SHOW_IN_PROGRESS,
            MigrationStatePolicy.startupAction(
                MigrationStateStore.State.PENDING,
                migrationRunningInProcess = true
            )
        )
        assertEquals(
            MigrationStatePolicy.StartupAction.SHOW_IN_PROGRESS,
            MigrationStatePolicy.startupAction(
                MigrationStateStore.State.PREPARING,
                migrationRunningInProcess = true
            )
        )
    }
}
