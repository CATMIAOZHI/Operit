package com.ai.assistance.operit.data.terminal.startup

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.util.AppLogger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val sessionId: String? = null
    )

    private enum class RestartWaitResult { READY, MANUALLY_CLOSED, CANCELLED }

    private val appContext = context.applicationContext
    private val repository = TerminalStartupServiceRepository.getInstance(appContext)
    private val terminal = Terminal.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val managedSessionIds = ConcurrentHashMap<String, String>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()
    private val logJobs = ConcurrentHashMap<String, Job>()
    private val operationMutexes = ConcurrentHashMap<String, Mutex>()

    private val _statuses = MutableStateFlow<Map<String, TerminalStartupServiceStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, TerminalStartupServiceStatus>> = _statuses.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs: StateFlow<Map<String, String>> = _logs.asStateFlow()

    fun getManagedSessionId(serviceId: String): String? = managedSessionIds[serviceId]

    suspend fun startEnabledServices(
        listener: ProgressListener = object : ProgressListener {}
    ): List<TerminalStartupServiceStartResult> = coroutineScope {
        val enabled = repository.snapshot().filter { it.enabled }
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
        val generation = nextGeneration(config.id)
        return operationMutex(config.id).withLock {
            if (!isCurrentGeneration(config.id, generation)) {
                return@withLock TerminalStartupServiceStartResult(config.id, false, cancelledMessage())
            }
            stopManagedRuntime(config.id, closeSession = true, updateStoppedStatus = false)
            clearLog(config.id)
            startWithRetries(config, generation, restartAttempt = 0, listener = listener)
        }
    }

    fun startServiceAsync(config: TerminalStartupServiceConfig) {
        scope.launch { startService(config) }
    }

    suspend fun stopService(serviceId: String) {
        nextGeneration(serviceId)
        operationMutex(serviceId).withLock {
            stopManagedRuntime(serviceId, closeSession = true, updateStoppedStatus = true)
        }
    }

    fun stopServiceAsync(serviceId: String) {
        scope.launch { stopService(serviceId) }
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
                    RestartWaitResult.CANCELLED -> break
                    RestartWaitResult.MANUALLY_CLOSED -> {
                        val message = sessionClosedMessage()
                        updateStatus(
                            config,
                            TerminalStartupServiceStatus(config.id, TerminalStartupServiceState.STOPPED, message = message),
                            listener
                        )
                        return TerminalStartupServiceStartResult(config.id, false, message)
                    }
                    RestartWaitResult.READY -> Unit
                }
                managedSessionIds.remove(config.id)?.let(terminal::closeSession)
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
            if (attemptResult.manuallyClosed || !config.autoRestart || attempt >= config.maxRestartAttempts) {
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
            collectServiceLogs(config, sessionId, commandId, generation, collectorReady)
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
                    managedSessionIds.remove(config.id, sessionId)
                    terminal.closeSession(sessionId)
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
        } catch (cancelled: CancellationException) {
            createdSessionId?.let { cleanupAttemptSession(config.id, it) }
            throw cancelled
        } catch (error: Exception) {
            createdSessionId?.let { cleanupAttemptSession(config.id, it) }
            AppLogger.e(TAG, "Failed to start terminal service ${config.id}", error)
            appendLog(config.id, error.message ?: error.javaClass.simpleName)
            AttemptResult(false, false, error.message ?: startupFailureMessage())
        }
    }

    private suspend fun waitUntilReady(
        config: TerminalStartupServiceConfig,
        sessionId: String,
        generation: Long
    ): AttemptResult {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        val processOnlyReadyAt = System.currentTimeMillis() + PROCESS_START_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
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
                if (process?.isAlive == true && System.currentTimeMillis() >= processOnlyReadyAt) {
                    return AttemptResult(true, false, runningMessage(), sessionId)
                }
            } else if (isTcpReachable(config.healthCheckHost, port)) {
                return AttemptResult(true, false, tcpReadyMessage(), sessionId)
            }
            delay(HEALTH_POLL_INTERVAL_MS)
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
                    managedSessionIds.remove(config.id, sessionId)
                    updateStatus(
                        config,
                        TerminalStartupServiceStatus(
                            serviceId = config.id,
                            state = TerminalStartupServiceState.STOPPED,
                            message = sessionClosedMessage()
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
                    if (latest?.enabled == true && latest.autoRestart && restartAttempt < latest.maxRestartAttempts) {
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
                                message = if (tcpUnhealthy) tcpUnhealthyMessage() else processExitedMessage(),
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
        collectorReady: CompletableDeferred<Unit>
    ) {
        logJobs.remove(config.id)?.cancel()
        logJobs[config.id] = scope.launch {
            terminal.commandEvents
                .filter { event ->
                    event.sessionId == sessionId &&
                        event.commandId == commandId &&
                        isCurrentGeneration(config.id, generation)
                }
                .onStart { collectorReady.complete(Unit) }
                .collect { event ->
                    if (event.outputChunk.isNotBlank()) {
                        appendLog(config.id, event.outputChunk)
                    }
                }
        }
    }

    private suspend fun stopManagedRuntime(
        serviceId: String,
        closeSession: Boolean,
        updateStoppedStatus: Boolean
    ) {
        monitorJobs.remove(serviceId)?.cancel()
        logJobs.remove(serviceId)?.cancel()
        val sessionId = managedSessionIds.remove(serviceId)
        if (closeSession && sessionId != null) {
            terminal.closeSession(sessionId)
        }
        if (updateStoppedStatus) {
            _statuses.update { current ->
                current +
                    (serviceId to
                        TerminalStartupServiceStatus(
                            serviceId = serviceId,
                            state = TerminalStartupServiceState.STOPPED,
                            message = stoppedMessage()
                        ))
            }
        }
    }

    private fun updateStatus(
        config: TerminalStartupServiceConfig,
        status: TerminalStartupServiceStatus,
        listener: ProgressListener
    ) {
        _statuses.update { current -> current + (config.id to status.copy(updatedAtMs = System.currentTimeMillis())) }
        listener.onServiceStatus(config, status)
    }

    private fun appendLog(serviceId: String, message: String) {
        if (message.isBlank()) return
        _logs.update { current ->
            val existing = current[serviceId].orEmpty()
            val combined = if (existing.isBlank()) message else "$existing\n$message"
            current + (serviceId to combined.takeLast(MAX_LOG_CHARS))
        }
    }

    private fun clearLog(serviceId: String) {
        _logs.update { current -> current - serviceId }
    }

    private fun nextGeneration(serviceId: String): Long =
        generations.computeIfAbsent(serviceId) { AtomicLong() }.incrementAndGet()

    private fun isCurrentGeneration(serviceId: String, generation: Long): Boolean =
        generations[serviceId]?.get() == generation

    private fun operationMutex(serviceId: String): Mutex =
        operationMutexes.computeIfAbsent(serviceId) { Mutex() }

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
                managedSessionIds.remove(serviceId, sessionId)
                return RestartWaitResult.MANUALLY_CLOSED
            }
            val slice = minOf(remaining, RESTART_CLOSE_POLL_INTERVAL_MS)
            delay(slice)
            remaining -= slice
        }
        return if (isCurrentGeneration(serviceId, generation)) RestartWaitResult.READY
        else RestartWaitResult.CANCELLED
    }

    private fun cleanupAttemptSession(serviceId: String, sessionId: String) {
        managedSessionIds.remove(serviceId, sessionId)
        terminal.closeSession(sessionId)
    }

    private fun restartDelayMs(attempt: Int): Long =
        (BASE_RESTART_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(MAX_RESTART_DELAY_MS)

    private fun isTcpReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

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
    private fun stoppedMessage() = appContext.getString(R.string.terminal_startup_status_stopped)
    private fun processExitedMessage() = appContext.getString(R.string.terminal_startup_message_process_exited)
    private fun tcpUnhealthyMessage() = appContext.getString(R.string.terminal_startup_message_tcp_unhealthy)
    private fun restartingMessage(delayMs: Long, attempt: Int, maxAttempts: Int) =
        appContext.getString(
            R.string.terminal_startup_message_restarting,
            delayMs / 1000,
            attempt,
            maxAttempts
        )

    companion object {
        private const val TAG = "TerminalStartupManager"
        private const val PROCESS_START_GRACE_MS = 1_000L
        private const val HEALTH_POLL_INTERVAL_MS = 250L
        private const val PROCESS_MONITOR_INTERVAL_MS = 1_000L
        private const val RESTART_CLOSE_POLL_INTERVAL_MS = 100L
        private const val TCP_FAILURE_THRESHOLD = 3
        private const val TCP_CONNECT_TIMEOUT_MS = 250
        private const val BASE_RESTART_DELAY_MS = 1_000L
        private const val MAX_RESTART_DELAY_MS = 8_000L
        private const val MAX_LOG_CHARS = 200_000
        private val NO_OP_LISTENER = object : ProgressListener {}

        @Volatile
        private var instance: TerminalStartupServiceManager? = null

        fun getInstance(context: Context): TerminalStartupServiceManager =
            instance ?: synchronized(this) {
                instance ?: TerminalStartupServiceManager(context).also { instance = it }
            }
    }
}
