package com.ai.assistance.operit.data.terminal.startup

enum class TerminalStartupLaunchMode {
    COMMAND,
    SCRIPT
}

data class TerminalStartupServiceConfig(
    val id: String,
    val name: String,
    val launchMode: TerminalStartupLaunchMode = TerminalStartupLaunchMode.COMMAND,
    val command: String = "",
    val scriptPath: String? = null,
    val scriptDisplayName: String? = null,
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val healthCheckHost: String = "127.0.0.1",
    val healthCheckPort: Int? = null,
    val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    val autoRestart: Boolean = true,
    val maxRestartAttempts: Int = DEFAULT_MAX_RESTART_ATTEMPTS
) {
    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_RESTART_ATTEMPTS = 3
    }
}

enum class TerminalStartupServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    RESTARTING,
    FAILED
}

data class TerminalStartupServiceStatus(
    val serviceId: String,
    val state: TerminalStartupServiceState = TerminalStartupServiceState.STOPPED,
    val sessionId: String? = null,
    val message: String = "",
    val restartAttempt: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class TerminalStartupServiceStartResult(
    val serviceId: String,
    val success: Boolean,
    val message: String,
    val sessionId: String? = null
)

internal data class TerminalStartupRuntimeStopResult(
    val terminated: Boolean,
    val wasActive: Boolean,
    val operation: Long,
)
