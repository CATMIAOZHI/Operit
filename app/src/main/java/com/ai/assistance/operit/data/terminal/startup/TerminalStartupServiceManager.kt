package com.ai.assistance.operit.data.terminal.startup

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.terminal.TerminalSessionCleanupPendingCancellationException
import com.ai.assistance.operit.terminal.TerminalSessionCleanupPendingException
import com.ai.assistance.operit.terminal.TerminalSessionCloseOutcome
import com.ai.assistance.operit.util.AppLogger
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PROCESS_START_GRACE_MS = 1_000L
private const val HEALTH_POLL_INTERVAL_MS = 250L
private const val DELETED_SERVICE_OPERATION = Long.MIN_VALUE

internal fun <T> SharedFlow<T>.signalCollectorSubscription(
    collectorReady: CompletableDeferred<Unit>,
): Flow<T> = onSubscription { collectorReady.complete(Unit) }

private val TCP_PROBE_EXECUTOR = ThreadPoolExecutor(
    0,
    Int.MAX_VALUE,
    30L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
    ThreadFactory { runnable ->
        Thread(runnable, "terminal-startup-tcp-probe").apply { isDaemon = true }
    },
)
private val ACTIVE_TCP_PROBES = ConcurrentHashMap<String, Future<Boolean>>()

internal fun hasActiveTcpProbe(probeKey: String): Boolean =
    ACTIVE_TCP_PROBES.containsKey(probeKey)

internal fun runBlockingProbeWithTimeout(
    probeKey: String,
    timeoutMs: Long,
    probe: () -> Boolean,
): Boolean? {
    if (timeoutMs <= 0L) return null
    val created = object : FutureTask<Boolean>(probe) {
        override fun done() {
            ACTIVE_TCP_PROBES.remove(probeKey, this)
        }
    }
    val future = ACTIVE_TCP_PROBES.putIfAbsent(probeKey, created) ?: created.also {
        try {
            TCP_PROBE_EXECUTOR.execute(it)
        } catch (_: RejectedExecutionException) {
            ACTIVE_TCP_PROBES.remove(probeKey, it)
            return null
        }
    }
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        // The completion hook handles callers that timed out before the underlying DNS/connect
        // work returned. This removal closes the already-completed fast path as well.
        if (future.isDone) ACTIVE_TCP_PROBES.remove(probeKey, future)
    }
}

internal class DetachableLogForwarder(
    target: (String) -> Unit
) {
    private val lock = Any()
    private var target: ((String) -> Unit)? = target
    private val pending = StringBuilder()

    fun emit(message: String) {
        if (message.isBlank()) return
        synchronized(lock) {
            if (target == null) return
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(message)
        }
    }

    fun flush() {
        synchronized(lock) {
            if (pending.isEmpty()) return
            val message = pending.toString()
            pending.setLength(0)
            target?.invoke(message)
        }
    }

    fun detach() {
        synchronized(lock) {
            if (pending.isNotEmpty()) {
                val message = pending.toString()
                pending.setLength(0)
                target?.invoke(message)
            }
            target = null
        }
    }
}

internal fun incrementalStartupLogChunk(outputChunk: String, isCompleted: Boolean): String? =
    outputChunk.takeIf { !isCompleted && it.isNotBlank() }

internal fun processOnlyStartupReadyDelayMs(startupTimeoutMs: Long): Long =
    minOf(PROCESS_START_GRACE_MS, (startupTimeoutMs - HEALTH_POLL_INTERVAL_MS).coerceAtLeast(0L))

internal fun startupPollDelayMs(remainingMs: Long): Long =
    remainingMs.coerceIn(0L, HEALTH_POLL_INTERVAL_MS)

internal fun shouldRestartTerminalService(
    enabled: Boolean,
    autoRestart: Boolean,
    restartAttempt: Int,
    maxRestartAttempts: Int,
): Boolean = enabled && autoRestart && restartAttempt < maxRestartAttempts

internal fun shouldPreemptRuntimeBeforePersisting(enabled: Boolean): Boolean = !enabled

internal fun isRuntimeOperationCurrent(
    currentOperation: Long?,
    currentGeneration: Long?,
    operation: Long,
): Boolean = currentOperation == operation && currentGeneration == operation

internal class BoundedLogBuffer(private val maxChars: Int) {
    private val chunks = ArrayDeque<String>()
    private var charCount = 0

    fun append(message: String) {
        if (message.isBlank()) return
        val chunk = if (chunks.isEmpty()) message else "\n$message"
        chunks.addLast(chunk)
        charCount += chunk.length
        trimToLimit()
    }

    fun snapshot(): String = chunks.joinToString(separator = "")

    private fun trimToLimit() {
        while (charCount > maxChars && chunks.isNotEmpty()) {
            val excess = charCount - maxChars
            val first = chunks.removeFirst()
            if (first.length > excess) {
                val retained = first.substring(excess)
                chunks.addFirst(retained)
                charCount -= excess
                return
            }
            charCount -= first.length
        }
    }
}

class TerminalStartupServiceManager private constructor(context: Context) {
    interface ProgressListener {
        fun onServiceStarting(config: TerminalStartupServiceConfig, index: Int, total: Int) {}
        fun onServiceStatus(config: TerminalStartupServiceConfig, status: TerminalStartupServiceStatus) {}
        fun onServiceLog(config: TerminalStartupServiceConfig, message: String) {}
    }

    private data class AttemptResult(
        val success: Boolean,
        val manuallyClosed: Boolean,
        val message: String,
        val sessionId: String? = null,
        val retryBlocked: Boolean = false,
    )

    private data class PurgedServiceJobs(
        val intentJob: Job?,
        val monitorJob: Job?,
        val logJob: Job?,
        val logForwarder: DetachableLogForwarder?,
    )

    private enum class RestartWaitResult { READY, MANUALLY_CLOSED, TERMINATION_TIMEOUT, CANCELLED }

    private val appContext = context.applicationContext
    private val repository = TerminalStartupServiceRepository.getInstance(appContext)
    private val terminal = Terminal.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceStateLifecycleLock = Any()
    private val deletedServiceIds = ConcurrentHashMap.newKeySet<String>()
    private val deletedServiceMutex = Mutex()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val operationSequences = ConcurrentHashMap<String, AtomicLong>()
    private val intentJobs = ConcurrentHashMap<String, Job>()
    private val managedSessionIds = ConcurrentHashMap<String, String>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val logJobs = ConcurrentHashMap<String, Job>()
    private val operationMutexes = ConcurrentHashMap<String, Mutex>()
    private val logBuffers = ConcurrentHashMap<String, BoundedLogBuffer>()
    private val logFlushScheduled = ConcurrentHashMap<String, AtomicBoolean>()
    private val logForwarders = ConcurrentHashMap<String, DetachableLogForwarder>()

    private val _statuses = MutableStateFlow<Map<String, TerminalStartupServiceStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, TerminalStartupServiceStatus>> = _statuses.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs: StateFlow<Map<String, String>> = _logs.asStateFlow()

    fun getManagedSessionId(serviceId: String): String? = managedSessionIds[serviceId]

    suspend fun startEnabledServices(
        listener: ProgressListener = object : ProgressListener {}
    ): List<TerminalStartupServiceStartResult> =
        startEnabledServices(
            services = repository.snapshot().filter { it.enabled },
            listener = listener,
        )

    /**
     * Starts the given enabled services. Callers that already discovered the enabled list
     * (e.g. app-boot orchestration that also builds progress items and the timeout budget)
     * must pass the same snapshot so execution matches what was displayed.
     */
    suspend fun startEnabledServices(
        services: List<TerminalStartupServiceConfig>,
        listener: ProgressListener = object : ProgressListener {}
    ): List<TerminalStartupServiceStartResult> = coroutineScope {
        val enabled = services.filter { it.enabled }
        enabled.mapIndexed { index, config ->
            async {
                listener.onServiceStarting(config, index + 1, enabled.size)
                try {
                    startService(config, listener)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val message = error.message ?: startupFailureMessage()
                    updateStatus(
                        config,
                        TerminalStartupServiceStatus(
                            serviceId = config.id,
                            state = TerminalStartupServiceState.FAILED,
                            message = message
                        ),
                        listener
                    )
                    TerminalStartupServiceStartResult(config.id, false, message)
                }
            }
        }.awaitAll()
    }

    suspend fun startService(
        config: TerminalStartupServiceConfig,
        listener: ProgressListener = object : ProgressListener {}
    ): TerminalStartupServiceStartResult {
        val operation = reserveOperation(config.id)
        cancelPendingToggle(config.id)
        activateRuntimeGeneration(config.id, operation)
        return startServiceWithGeneration(config, listener, operation)
    }

    private suspend fun startServiceWithGeneration(
        config: TerminalStartupServiceConfig,
        listener: ProgressListener,
        generation: Long,
    ): TerminalStartupServiceStartResult {
        return operationMutex(config.id).withLock {
            if (!isRuntimeOperationCurrent(
                    currentOperation = operationSequences[config.id]?.get(),
                    currentGeneration = generations[config.id]?.get(),
                    operation = generation,
                )
            ) {
                return@withLock TerminalStartupServiceStartResult(config.id, false, cancelledMessage())
            }
            if (!stopManagedRuntime(config.id, closeSession = true, updateStoppedStatus = false)) {
                val message = previousProcessTerminationTimeoutMessage()
                updateStatus(
                    config,
                    TerminalStartupServiceStatus(
                        serviceId = config.id,
                        state = TerminalStartupServiceState.FAILED,
                        message = message,
                    ),
                    listener,
                )
                return@withLock TerminalStartupServiceStartResult(config.id, false, message)
            }
            clearLog(config.id)
            startWithRetries(config, generation, restartAttempt = 0, listener = listener)
        }
    }

    fun setServiceEnabledAsync(
        config: TerminalStartupServiceConfig,
        enabled: Boolean,
        onFailure: (Throwable) -> Unit = {},
    ) {
        val updated = config.copy(enabled = enabled)
        val intentSequence = reserveOperation(config.id)
        if (shouldPreemptRuntimeBeforePersisting(enabled)) {
            // Stop readiness/retry work before waiting behind its long-held service mutex. If the
            // persistence write fails, the recovery path below re-applies the stored config using
            // this same operation token.
            activateRuntimeGeneration(config.id, intentSequence)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operationMutex(config.id).withLock {
                    if (!isCurrentOperation(config.id, intentSequence)) return@withLock
                    repository.upsert(updated)
                    if (!isCurrentOperation(config.id, intentSequence)) return@withLock
                    applyPersistedConfigLocked(updated, intentSequence)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isCurrentOperation(config.id, intentSequence)) {
                    runCatching {
                        operationMutex(config.id).withLock {
                            if (!isCurrentOperation(config.id, intentSequence)) return@withLock
                            repository.getById(config.id)?.let {
                                applyPersistedConfigLocked(it, intentSequence)
                            }
                        }
                    }.onFailure { recoveryError ->
                        AppLogger.e(TAG, "Failed to restore terminal service ${config.id} after persistence error", recoveryError)
                    }
                }
                onFailure(error)
            }
        }
        val previousJob = synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(config.id)) return@synchronized job
            intentJobs.put(config.id, job)
        }
        if (previousJob === job) {
            job.cancel()
            return
        }
        previousJob?.cancel()
        job.invokeOnCompletion { intentJobs.remove(config.id, job) }
        job.start()
    }

    private suspend fun applyPersistedConfigLocked(
        config: TerminalStartupServiceConfig,
        generation: Long,
    ) {
        if (!activateRuntimeGenerationIfCurrentOperation(config.id, generation)) return
        if (config.enabled) {
            if (!stopManagedRuntime(config.id, closeSession = true, updateStoppedStatus = false)) {
                throw IllegalStateException(previousProcessTerminationTimeoutMessage())
            }
            clearLog(config.id)
            startWithRetries(config, generation, restartAttempt = 0, listener = NO_OP_LISTENER)
        } else {
            if (!stopManagedRuntime(config.id, closeSession = true, updateStoppedStatus = true)) {
                throw IllegalStateException(previousProcessTerminationTimeoutMessage())
            }
        }
    }

    fun startServiceAsync(config: TerminalStartupServiceConfig) {
        val generation = reserveOperation(config.id)
        cancelPendingToggle(config.id)
        activateRuntimeGeneration(config.id, generation)
        scope.launch { startServiceWithGeneration(config, NO_OP_LISTENER, generation) }
    }

    suspend fun stopService(serviceId: String): Boolean {
        val operation = reserveOperation(serviceId)
        cancelPendingToggle(serviceId)
        activateRuntimeGeneration(serviceId, operation)
        return stopServiceWithGeneration(serviceId, operation)
    }

    internal suspend fun stopServiceForDeletion(serviceId: String): TerminalStartupRuntimeStopResult {
        val operation = reserveOperation(serviceId)
        cancelPendingToggle(serviceId)
        activateRuntimeGeneration(serviceId, operation)
        return operationMutex(serviceId).withLock {
            if (!isCurrentGeneration(serviceId, operation)) {
                return@withLock TerminalStartupRuntimeStopResult(
                    terminated = false,
                    wasActive = false,
                    operation = operation,
                )
            }
            val state = _statuses.value[serviceId]?.state
            val wasActive = managedSessionIds.containsKey(serviceId) ||
                state == TerminalStartupServiceState.STARTING ||
                state == TerminalStartupServiceState.RUNNING ||
                state == TerminalStartupServiceState.RESTARTING
            TerminalStartupRuntimeStopResult(
                terminated = stopManagedRuntime(
                    serviceId,
                    closeSession = true,
                    updateStoppedStatus = true,
                ),
                wasActive = wasActive,
                operation = operation,
            )
        }
    }

    internal suspend fun restoreServiceAfterFailedDeletion(
        config: TerminalStartupServiceConfig,
        operation: Long,
    ): Boolean =
        // Reuse the deletion token. Any newer stop/toggle intent advances the generation and
        // makes this recovery a no-op instead of letting an old deletion failure restart it.
        startServiceWithGeneration(config, NO_OP_LISTENER, operation).success

    internal suspend fun deletePersistedForOperation(
        serviceId: String,
        operation: Long,
        deletePersisted: suspend () -> Unit,
    ): Boolean {
        val mutex = operationMutex(serviceId)
        return mutex.withLock {
            if (!isRuntimeOperationCurrent(
                    currentOperation = operationSequences[serviceId]?.get(),
                    currentGeneration = generations[serviceId]?.get(),
                    operation = operation,
                )
            ) {
                return@withLock false
            }
            deletePersisted()
            // Linearize a successful deletion after any action that raced with its disk commit.
            val tombstoneOperation = reserveOperation(serviceId)
            activateRuntimeGeneration(serviceId, tombstoneOperation)
            purgeDeletedServiceState(serviceId, mutex)
            true
        }
    }

    private suspend fun stopServiceWithGeneration(serviceId: String, generation: Long): Boolean {
        return operationMutex(serviceId).withLock {
            if (!isCurrentGeneration(serviceId, generation)) return@withLock false
            stopManagedRuntime(serviceId, closeSession = true, updateStoppedStatus = true)
        }
    }

    fun stopServiceAsync(serviceId: String) {
        val generation = reserveOperation(serviceId)
        cancelPendingToggle(serviceId)
        activateRuntimeGeneration(serviceId, generation)
        scope.launch { stopServiceWithGeneration(serviceId, generation) }
    }

    private suspend fun startWithRetries(
        config: TerminalStartupServiceConfig,
        generation: Long,
        restartAttempt: Int,
        listener: ProgressListener
    ): TerminalStartupServiceStartResult {
        var attempt = restartAttempt
        var lastMessage = ""
        while (isCurrentGeneration(config.id, generation)) {
            if (attempt > 0) {
                val delayMs = restartDelayMs(attempt)
                updateStatus(
                    config,
                    TerminalStartupServiceStatus(
                        serviceId = config.id,
                        state = TerminalStartupServiceState.RESTARTING,
                        message = restartingMessage(delayMs, attempt, config.maxRestartAttempts),
                        restartAttempt = attempt
                    ),
                    listener
                )
                when (waitForRestartDelay(config.id, generation, delayMs)) {
                    RestartWaitResult.CANCELLED -> {
                        // A newer start/stop/toggle preempted this restart. Finalize the item for
                        // the app-boot listener so it does not remain LOADING/RESTARTING.
                        val message = cancelledMessage()
                        updateStatus(
                            config,
                            TerminalStartupServiceStatus(
                                config.id,
                                TerminalStartupServiceState.FAILED,
                                message = message,
                            ),
                            listener,
                        )
                        break
                    }
                    RestartWaitResult.MANUALLY_CLOSED -> {
                        val message = sessionClosedMessage()
                        updateStatus(
                            config,
                            TerminalStartupServiceStatus(config.id, TerminalStartupServiceState.STOPPED, message = message),
                            listener
                        )
                        return TerminalStartupServiceStartResult(config.id, false, message)
                    }
                    RestartWaitResult.TERMINATION_TIMEOUT -> {
                        val message = previousProcessTerminationTimeoutMessage()
                        updateStatus(
                            config,
                            TerminalStartupServiceStatus(
                                config.id,
                                TerminalStartupServiceState.FAILED,
                                message = message,
                            ),
                            listener,
                        )
                        return TerminalStartupServiceStartResult(config.id, false, message)
                    }
                    RestartWaitResult.READY -> Unit
                }
                val previousSessionId = managedSessionIds[config.id]
                if (previousSessionId != null) {
                    when (
                        terminal.closeSessionWithOutcomeAndAwait(
                            previousSessionId,
                            PROCESS_TERMINATION_TIMEOUT_MS,
                        )
                    ) {
                        TerminalSessionCloseOutcome.CLOSED -> {
                            managedSessionIds.remove(config.id, previousSessionId)
                        }
                        TerminalSessionCloseOutcome.ALREADY_CLOSED -> {
                            managedSessionIds.remove(config.id, previousSessionId)
                            val message = sessionClosedMessage()
                            updateStatus(
                                config,
                                TerminalStartupServiceStatus(
                                    serviceId = config.id,
                                    state = TerminalStartupServiceState.STOPPED,
                                    message = message,
                                    restartAttempt = attempt,
                                ),
                                listener,
                            )
                            return TerminalStartupServiceStartResult(config.id, false, message)
                        }
                        TerminalSessionCloseOutcome.TERMINATION_TIMEOUT -> {
                            val message = previousProcessTerminationTimeoutMessage()
                            updateStatus(
                                config,
                                TerminalStartupServiceStatus(
                                    serviceId = config.id,
                                    state = TerminalStartupServiceState.FAILED,
                                    message = message,
                                    restartAttempt = attempt,
                                ),
                                listener,
                            )
                            return TerminalStartupServiceStartResult(config.id, false, message)
                        }
                    }
                }
            }

            val attemptResult = startSingleAttempt(config, generation, attempt, listener)
            lastMessage = attemptResult.message
            if (attemptResult.success) {
                monitorRunningService(config, generation, attempt)
                return TerminalStartupServiceStartResult(
                    serviceId = config.id,
                    success = true,
                    message = attemptResult.message,
                    sessionId = attemptResult.sessionId
                )
            }
            if (
                attemptResult.manuallyClosed ||
                    attemptResult.retryBlocked ||
                    !config.autoRestart ||
                    attempt >= config.maxRestartAttempts
            ) {
                val state =
                    if (attemptResult.manuallyClosed) TerminalStartupServiceState.STOPPED
                    else TerminalStartupServiceState.FAILED
                updateStatus(
                    config,
                    TerminalStartupServiceStatus(
                        serviceId = config.id,
                        state = state,
                        message = lastMessage,
                        restartAttempt = attempt
                    ),
                    listener
                )
                return TerminalStartupServiceStartResult(config.id, false, lastMessage)
            }
            attempt++
        }
        return TerminalStartupServiceStartResult(config.id, false, cancelledMessage())
    }

    private suspend fun startSingleAttempt(
        config: TerminalStartupServiceConfig,
        generation: Long,
        restartAttempt: Int,
        listener: ProgressListener
    ): AttemptResult {
        val logForwarder = DetachableLogForwarder { message ->
            listener.onServiceLog(config, message)
        }
        logForwarders[config.id] = logForwarder
        updateStatus(
            config,
            TerminalStartupServiceStatus(
                serviceId = config.id,
                state =
                    if (restartAttempt == 0) TerminalStartupServiceState.STARTING
                    else TerminalStartupServiceState.RESTARTING,
                message = preparingMessage(),
                restartAttempt = restartAttempt
            ),
            listener
        )

        var createdSessionId: String? = null
        return try {
            val launcher = repository.writeLauncher(config)
            if (!terminal.initialize()) {
                return AttemptResult(false, false, environmentFailureMessage())
            }
            if (!isCurrentGeneration(config.id, generation)) {
                return AttemptResult(false, true, cancelledMessage())
            }

            val sessionId = terminal.createLocalBackgroundSession(sessionTitle(config.name))
            createdSessionId = sessionId
            managedSessionIds[config.id] = sessionId
            val commandId = UUID.randomUUID().toString()
            val collectorReady = CompletableDeferred<Unit>()
            collectServiceLogs(config, sessionId, commandId, generation, collectorReady, logForwarder)
            collectorReady.await()
            terminal.launchCommand(
                sessionId = sessionId,
                command = "exec /bin/bash ${shellQuote(launcher.absolutePath)}",
                commandId = commandId
            )

            val readiness = waitUntilReady(config, sessionId, generation)
            if (!readiness.success) {
                val isStillManaged = managedSessionIds[config.id] == sessionId
                if (isStillManaged) {
                    // The manager owns this failed attempt. If termination is confirmed, drop the
                    // tracked ID so the restart backoff does not treat the disappearance as a
                    // user-initiated close. If termination times out, keep the ID tracked (the
                    // old process may still be alive) and block retries instead of leaking it.
                    val terminated =
                        terminal.closeSessionAndAwait(sessionId, PROCESS_TERMINATION_TIMEOUT_MS)
                    if (terminated) {
                        managedSessionIds.remove(config.id, sessionId)
                    } else {
                        val errorMessage = previousProcessTerminationTimeoutMessage()
                        appendLog(config.id, errorMessage)
                        logForwarder.emit(errorMessage)
                        return AttemptResult(
                            success = false,
                            manuallyClosed = false,
                            message = errorMessage,
                            sessionId = sessionId,
                            retryBlocked = true,
                        )
                    }
                }
                createdSessionId = null
                return readiness.copy(manuallyClosed = !isStillManaged || readiness.manuallyClosed)
            }

            val message =
                config.healthCheckPort?.let { port ->
                    runningOnMessage(config.healthCheckHost, port)
                } ?: runningMessage()
            updateStatus(
                config,
                TerminalStartupServiceStatus(
                    serviceId = config.id,
                    state = TerminalStartupServiceState.RUNNING,
                    sessionId = sessionId,
                    message = message,
                    restartAttempt = restartAttempt
                ),
                listener
            )
            createdSessionId = null
            AttemptResult(true, false, message, sessionId)
        } catch (cancelled: TerminalSessionCleanupPendingCancellationException) {
            managedSessionIds[config.id] = cancelled.sessionId
            throw cancelled
        } catch (cancelled: CancellationException) {
            createdSessionId?.let { cleanupAttemptSession(config.id, it) }
            throw cancelled
        } catch (error: TerminalSessionCleanupPendingException) {
            managedSessionIds[config.id] = error.sessionId
            AppLogger.e(TAG, "Terminal session cleanup is still pending for ${config.id}", error)
            val errorMessage = previousProcessTerminationTimeoutMessage()
            appendLog(config.id, errorMessage)
            logForwarder.emit(errorMessage)
            AttemptResult(
                success = false,
                manuallyClosed = false,
                message = errorMessage,
                sessionId = error.sessionId,
                retryBlocked = true,
            )
        } catch (error: Exception) {
            createdSessionId?.let { cleanupAttemptSession(config.id, it) }
            AppLogger.e(TAG, "Failed to start terminal service ${config.id}", error)
            val errorMessage = localizeStartupError(error)
            appendLog(config.id, errorMessage)
            logForwarder.emit(errorMessage)
            AttemptResult(false, false, errorMessage)
        } finally {
            // The command log collector can outlive app startup. Drop its UI listener after this
            // attempt finishes so it cannot retain PluginLoadingState or an Activity scope.
            logForwarder.detach()
            logForwarders.remove(config.id, logForwarder)
        }
    }

    private suspend fun waitUntilReady(
        config: TerminalStartupServiceConfig,
        sessionId: String,
        generation: Long
    ): AttemptResult {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + config.startupTimeoutMs
        val processOnlyReadyAt = startedAt + processOnlyStartupReadyDelayMs(config.startupTimeoutMs)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isCurrentGeneration(config.id, generation)) {
                return AttemptResult(false, true, cancelledMessage(), sessionId)
            }
            val session = terminal.terminalState.value.sessions.firstOrNull { it.id == sessionId }
                ?: return AttemptResult(false, true, sessionClosedMessage(), sessionId)
            val process = session.terminalSession?.process
            if (process != null && !process.isAlive) {
                return AttemptResult(false, false, processExitedDuringStartupMessage(), sessionId)
            }
            val port = config.healthCheckPort
            if (port == null) {
                if (process?.isAlive == true && SystemClock.elapsedRealtime() >= processOnlyReadyAt) {
                    return AttemptResult(true, false, runningMessage(), sessionId)
                }
            } else {
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (
                    remainingMs > 0L &&
                    isTcpReachable(
                        config.healthCheckHost,
                        port,
                        minOf(remainingMs, TCP_CONNECT_TIMEOUT_MS.toLong()).toInt(),
                    )
                ) {
                    return AttemptResult(true, false, tcpReadyMessage(), sessionId)
                }
            }
            val pollDelayMs = startupPollDelayMs(deadline - SystemClock.elapsedRealtime())
            if (pollDelayMs > 0L) delay(pollDelayMs)
        }
        return AttemptResult(
            false,
            false,
            config.healthCheckPort?.let {
                tcpTimeoutMessage(config.healthCheckHost, it)
            } ?: processTimeoutMessage(),
            sessionId
        )
    }

    private fun monitorRunningService(
        config: TerminalStartupServiceConfig,
        generation: Long,
        restartAttempt: Int
    ) {
        monitorJobs.remove(config.id)?.cancel()
        monitorJobs[config.id] = scope.launch {
            var tcpFailureCount = 0
            while (isActive && isCurrentGeneration(config.id, generation)) {
                val sessionId = managedSessionIds[config.id] ?: return@launch
                val session = terminal.terminalState.value.sessions.firstOrNull { it.id == sessionId }
                if (session == null) {
                    val terminated =
                        terminal.closeSessionAndAwait(sessionId, PROCESS_TERMINATION_TIMEOUT_MS)
                    if (terminated) managedSessionIds.remove(config.id, sessionId)
                    // A newer start/stop/toggle may have advanced the generation while awaiting
                    // cleanup. Do not publish a stale status after it updated the service.
                    if (!isCurrentGeneration(config.id, generation)) return@launch
                    updateStatus(
                        config,
                        TerminalStartupServiceStatus(
                            serviceId = config.id,
                            state =
                                if (terminated) TerminalStartupServiceState.STOPPED
                                else TerminalStartupServiceState.FAILED,
                            message =
                                if (terminated) sessionClosedMessage()
                                else previousProcessTerminationTimeoutMessage()
                        ),
                        NO_OP_LISTENER
                    )
                    return@launch
                }
                val process = session.terminalSession?.process
                val processExited = process != null && !process.isAlive
                val tcpUnhealthy = config.healthCheckPort?.let { port ->
                    if (isTcpReachable(config.healthCheckHost, port)) {
                        tcpFailureCount = 0
                        false
                    } else {
                        tcpFailureCount++
                        tcpFailureCount >= TCP_FAILURE_THRESHOLD
                    }
                } ?: false
                if (!isCurrentGeneration(config.id, generation)) return@launch
                if (processExited || tcpUnhealthy) {
                    if (tcpUnhealthy && process?.isAlive == true) process.destroy()
                    val latest = repository.getById(config.id)
                    if (
                        latest != null &&
                        shouldRestartTerminalService(
                            enabled = latest.enabled,
                            autoRestart = latest.autoRestart,
                            restartAttempt = restartAttempt,
                            maxRestartAttempts = latest.maxRestartAttempts,
                        )
                    ) {
                        appendLog(config.id, if (tcpUnhealthy) tcpUnhealthyMessage() else processExitedMessage())
                        monitorJobs.remove(config.id)
                        scope.launch {
                            operationMutex(config.id).withLock {
                                if (isCurrentGeneration(config.id, generation)) {
                                    startWithRetries(latest, generation, restartAttempt + 1, NO_OP_LISTENER)
                                }
                            }
                        }
                    } else {
                        updateStatus(
                            config,
                            TerminalStartupServiceStatus(
                                serviceId = config.id,
                                state = TerminalStartupServiceState.FAILED,
                                message =
                                    if (tcpUnhealthy) tcpUnhealthyNoRestartMessage()
                                    else processExitedNoRestartMessage(),
                                restartAttempt = restartAttempt
                            ),
                            NO_OP_LISTENER
                        )
                    }
                    return@launch
                }
                delay(PROCESS_MONITOR_INTERVAL_MS)
            }
        }
    }

    private fun collectServiceLogs(
        config: TerminalStartupServiceConfig,
        sessionId: String,
        commandId: String,
        generation: Long,
        collectorReady: CompletableDeferred<Unit>,
        logForwarder: DetachableLogForwarder
    ) {
        logJobs.remove(config.id)?.cancel()
        logJobs[config.id] = scope.launch {
            terminal.commandEvents
                .signalCollectorSubscription(collectorReady)
                .filter { event ->
                    event.sessionId == sessionId &&
                        event.commandId == commandId &&
                        isCurrentGeneration(config.id, generation)
                }
                // Completion events contain the full final output snapshot, not an increment.
                // Stop at that event so logs are neither duplicated nor collected forever.
                .takeWhile { event -> !event.isCompleted }
                .collect { event ->
                    incrementalStartupLogChunk(event.outputChunk, event.isCompleted)?.let { chunk ->
                        appendLog(config.id, chunk, publishImmediately = false)
                        logForwarder.emit(chunk)
                    }
                }
            publishLog(config.id)
        }
    }

    private suspend fun stopManagedRuntime(
        serviceId: String,
        closeSession: Boolean,
        updateStoppedStatus: Boolean
    ): Boolean {
        monitorJobs.remove(serviceId)?.cancel()
        logJobs.remove(serviceId)?.cancel()
        val sessionId = managedSessionIds[serviceId]
        val terminated =
            !closeSession || sessionId == null ||
                terminal.closeSessionAndAwait(sessionId, PROCESS_TERMINATION_TIMEOUT_MS)
        if (terminated && sessionId != null) managedSessionIds.remove(serviceId, sessionId)
        if (updateStoppedStatus) {
            synchronized(serviceStateLifecycleLock) {
                if (!deletedServiceIds.contains(serviceId)) {
                    _statuses.update { current ->
                        current +
                            (serviceId to
                                TerminalStartupServiceStatus(
                                    serviceId = serviceId,
                                    state =
                                        if (terminated) TerminalStartupServiceState.STOPPED
                                        else TerminalStartupServiceState.FAILED,
                                    message =
                                        if (terminated) stoppedMessage()
                                        else previousProcessTerminationTimeoutMessage()
                                ))
                    }
                }
            }
        }
        return terminated
    }

    private fun updateStatus(
        config: TerminalStartupServiceConfig,
        status: TerminalStartupServiceStatus,
        listener: ProgressListener
    ) {
        synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(config.id)) return
            _statuses.update { current ->
                current + (config.id to status.copy(updatedAtMs = System.currentTimeMillis()))
            }
        }
        listener.onServiceStatus(config, status)
    }

    private fun appendLog(serviceId: String, message: String, publishImmediately: Boolean = true) {
        if (message.isBlank()) return
        val buffer = synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(serviceId)) return
            logBuffers.computeIfAbsent(serviceId) { BoundedLogBuffer(MAX_LOG_CHARS) }
        }
        synchronized(buffer) { buffer.append(message) }
        if (publishImmediately) publishLog(serviceId) else scheduleLogPublish(serviceId)
    }

    private fun scheduleLogPublish(serviceId: String) {
        val scheduled = synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(serviceId)) return
            logFlushScheduled.computeIfAbsent(serviceId) { AtomicBoolean() }
        }
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(LOG_PUBLISH_INTERVAL_MS)
            scheduled.set(false)
            publishLog(serviceId)
            logForwarders[serviceId]?.flush()
        }
    }

    private fun publishLog(serviceId: String) {
        synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(serviceId)) return
            val buffer = logBuffers[serviceId] ?: return
            val snapshot = synchronized(buffer) { buffer.snapshot() }
            _logs.update { current ->
                if (current[serviceId] == snapshot) current else current + (serviceId to snapshot)
            }
        }
    }

    private fun clearLog(serviceId: String) {
        logBuffers.remove(serviceId)
        logFlushScheduled.remove(serviceId)
        _logs.update { current -> current - serviceId }
    }

    private fun purgeDeletedServiceState(serviceId: String, mutex: Mutex) {
        val purgedJobs = synchronized(serviceStateLifecycleLock) {
            // UUIDs are never reused. Retaining only this small tombstone prevents stale callers
            // that raced with deletion from rebuilding the cleared per-service maps.
            deletedServiceIds.add(serviceId)
            val jobs = PurgedServiceJobs(
                intentJob = intentJobs.remove(serviceId),
                monitorJob = monitorJobs.remove(serviceId),
                logJob = logJobs.remove(serviceId),
                logForwarder = logForwarders.remove(serviceId),
            )
            managedSessionIds.remove(serviceId)
            logBuffers.remove(serviceId)
            logFlushScheduled.remove(serviceId)
            generations.remove(serviceId)
            operationSequences.remove(serviceId)
            operationMutexes.remove(serviceId, mutex)
            jobs
        }
        purgedJobs.intentJob?.cancel()
        purgedJobs.monitorJob?.cancel()
        purgedJobs.logJob?.cancel()
        purgedJobs.logForwarder?.detach()
        _statuses.update { current -> current - serviceId }
        _logs.update { current -> current - serviceId }
    }

    private fun reserveOperation(serviceId: String): Long = synchronized(serviceStateLifecycleLock) {
        if (deletedServiceIds.contains(serviceId)) return@synchronized DELETED_SERVICE_OPERATION
        operationSequences.computeIfAbsent(serviceId) { AtomicLong() }.incrementAndGet()
    }

    private fun isCurrentOperation(serviceId: String, sequence: Long): Boolean =
        !deletedServiceIds.contains(serviceId) &&
            operationSequences[serviceId]?.get() == sequence

    private fun activateRuntimeGeneration(serviceId: String, generation: Long) {
        synchronized(serviceStateLifecycleLock) {
            if (deletedServiceIds.contains(serviceId)) return
            generations.computeIfAbsent(serviceId) { AtomicLong() }
                .updateAndGet { current -> maxOf(current, generation) }
        }
    }

    private fun activateRuntimeGenerationIfCurrentOperation(
        serviceId: String,
        generation: Long,
    ): Boolean {
        return synchronized(serviceStateLifecycleLock) {
            if (!isCurrentOperation(serviceId, generation)) return@synchronized false
            val activated = generations.computeIfAbsent(serviceId) { AtomicLong() }
                .updateAndGet { current -> maxOf(current, generation) }
            activated == generation && isCurrentOperation(serviceId, generation)
        }
    }

    private fun cancelPendingToggle(serviceId: String) {
        val job = synchronized(serviceStateLifecycleLock) { intentJobs.remove(serviceId) }
        job?.cancel()
    }

    private fun isCurrentGeneration(serviceId: String, generation: Long): Boolean =
        !deletedServiceIds.contains(serviceId) && generations[serviceId]?.get() == generation

    private fun operationMutex(serviceId: String): Mutex = synchronized(serviceStateLifecycleLock) {
        if (deletedServiceIds.contains(serviceId)) return@synchronized deletedServiceMutex
        operationMutexes.computeIfAbsent(serviceId) { Mutex() }
    }

    private suspend fun waitForRestartDelay(
        serviceId: String,
        generation: Long,
        delayMs: Long
    ): RestartWaitResult {
        var remaining = delayMs
        while (remaining > 0) {
            if (!isCurrentGeneration(serviceId, generation)) return RestartWaitResult.CANCELLED
            val sessionId = managedSessionIds[serviceId]
            if (sessionId != null && terminal.terminalState.value.sessions.none { it.id == sessionId }) {
                val terminated =
                    terminal.closeSessionAndAwait(sessionId, PROCESS_TERMINATION_TIMEOUT_MS)
                if (terminated) {
                    managedSessionIds.remove(serviceId, sessionId)
                }
                return if (terminated) RestartWaitResult.MANUALLY_CLOSED
                else RestartWaitResult.TERMINATION_TIMEOUT
            }
            val slice = minOf(remaining, RESTART_CLOSE_POLL_INTERVAL_MS)
            delay(slice)
            remaining -= slice
        }
        return if (isCurrentGeneration(serviceId, generation)) RestartWaitResult.READY
        else RestartWaitResult.CANCELLED
    }

    private suspend fun cleanupAttemptSession(serviceId: String, sessionId: String) {
        if (terminal.closeSessionAndAwait(sessionId, PROCESS_TERMINATION_TIMEOUT_MS)) {
            managedSessionIds.remove(serviceId, sessionId)
        }
    }

    private fun restartDelayMs(attempt: Int): Long =
        (BASE_RESTART_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(MAX_RESTART_DELAY_MS)

    private fun isTcpReachable(
        host: String,
        port: Int,
        timeoutMs: Int = TCP_CONNECT_TIMEOUT_MS,
    ): Boolean =
        runBlockingProbeWithTimeout("$host:$port", timeoutMs.toLong()) {
            // The shared endpoint probe always gets its full work budget. Individual startup and
            // monitor callers only bound how long they wait for that same Future.
            val deadline = SystemClock.elapsedRealtime() + TCP_CONNECT_TIMEOUT_MS
            InetAddress.getAllByName(host).any { address ->
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    false
                } else {
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(
                                InetSocketAddress(address, port),
                                remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            )
                        }
                        true
                    }.getOrDefault(false)
                }
            }
        } ?: false

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun sessionTitle(name: String) = appContext.getString(R.string.terminal_startup_session_title, name)
    private fun preparingMessage() = appContext.getString(R.string.terminal_startup_message_preparing)
    private fun environmentFailureMessage() = appContext.getString(R.string.terminal_startup_message_environment_failed)
    private fun cancelledMessage() = appContext.getString(R.string.terminal_startup_message_cancelled)
    private fun sessionClosedMessage() = appContext.getString(R.string.terminal_startup_message_session_closed)
    private fun processExitedDuringStartupMessage() = appContext.getString(R.string.terminal_startup_message_exited_during_startup)
    private fun runningMessage() = appContext.getString(R.string.terminal_startup_message_running)
    private fun runningOnMessage(host: String, port: Int) =
        appContext.getString(R.string.terminal_startup_message_running_on, host, port)
    private fun tcpReadyMessage() = appContext.getString(R.string.terminal_startup_message_tcp_ready)
    private fun tcpTimeoutMessage(host: String, port: Int) =
        appContext.getString(R.string.terminal_startup_message_tcp_timeout, host, port)
    private fun processTimeoutMessage() = appContext.getString(R.string.terminal_startup_message_process_timeout)
    private fun startupFailureMessage() = appContext.getString(R.string.terminal_startup_message_failed)
    private fun localizeStartupError(error: Exception): String =
        when (error.message) {
            SESSION_INITIALIZATION_TIMEOUT_MESSAGE ->
                appContext.getString(R.string.terminal_startup_message_session_init_timeout)
            else -> error.message ?: startupFailureMessage()
        }
    private fun previousProcessTerminationTimeoutMessage() =
        appContext.getString(R.string.terminal_startup_message_previous_process_timeout)
    private fun stoppedMessage() = appContext.getString(R.string.terminal_startup_status_stopped)
    private fun processExitedMessage() = appContext.getString(R.string.terminal_startup_message_process_exited)
    private fun tcpUnhealthyMessage() = appContext.getString(R.string.terminal_startup_message_tcp_unhealthy)
    private fun processExitedNoRestartMessage() =
        appContext.getString(R.string.terminal_startup_message_process_exited_no_restart)
    private fun tcpUnhealthyNoRestartMessage() =
        appContext.getString(R.string.terminal_startup_message_tcp_unhealthy_no_restart)
    private fun restartingMessage(delayMs: Long, attempt: Int, maxAttempts: Int) =
        appContext.getString(
            R.string.terminal_startup_message_restarting,
            delayMs / 1000,
            attempt,
            maxAttempts
        )

    companion object {
        private const val TAG = "TerminalStartupManager"
        private const val PROCESS_MONITOR_INTERVAL_MS = 1_000L
        private const val RESTART_CLOSE_POLL_INTERVAL_MS = 100L
        private const val TCP_FAILURE_THRESHOLD = 3
        private const val TCP_CONNECT_TIMEOUT_MS = 250
        private const val BASE_RESTART_DELAY_MS = 1_000L
        private const val MAX_RESTART_DELAY_MS = 8_000L
        private const val MAX_LOG_CHARS = 200_000
        private const val PROCESS_TERMINATION_TIMEOUT_MS = 3_000L
        private const val LOG_PUBLISH_INTERVAL_MS = 250L
        private const val SESSION_INITIALIZATION_TIMEOUT_MESSAGE = "Session initialization timeout"
        private val NO_OP_LISTENER = object : ProgressListener {}

        @Volatile
        private var instance: TerminalStartupServiceManager? = null

        fun getInstance(context: Context): TerminalStartupServiceManager =
            instance ?: synchronized(this) {
                instance ?: TerminalStartupServiceManager(context).also { instance = it }
            }
    }
}
