package com.ai.assistance.operit.features.orbit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders a user pause/switch against the final local message/notification side effect. */
object OrbitExecutionGate {
    private val mutex = Mutex()
    suspend fun <T> serialized(action: suspend () -> T): T = mutex.withLock { action() }
}
