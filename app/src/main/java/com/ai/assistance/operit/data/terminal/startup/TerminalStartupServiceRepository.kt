package com.ai.assistance.operit.data.terminal.startup

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.AtomicFile
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TerminalStartupServiceRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val baseDirectory = File(appContext.filesDir, "operit/terminal-startup-services")
    private val scriptsDirectory = File(baseDirectory, "scripts")
    private val launchersDirectory = File(baseDirectory, "launchers")
    private val configFile = AtomicFile(File(baseDirectory, CONFIG_FILE_NAME))
    private val writeMutex = Mutex()

    private val _services = MutableStateFlow(loadServices())
    val services: StateFlow<List<TerminalStartupServiceConfig>> = _services.asStateFlow()

    fun snapshot(): List<TerminalStartupServiceConfig> = _services.value

    fun getById(serviceId: String): TerminalStartupServiceConfig? =
        _services.value.firstOrNull { it.id == serviceId }

    suspend fun upsert(config: TerminalStartupServiceConfig) {
        writeMutex.withLock {
            val updated = _services.value.toMutableList()
            val index = updated.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                updated[index] = config
            } else {
                updated.add(config)
            }
            persistServices(updated)
            _services.value = updated
        }
    }

    suspend fun delete(serviceId: String) {
        writeMutex.withLock {
            val updated = _services.value.filterNot { it.id == serviceId }
            persistServices(updated)
            _services.value = updated
            withContext(Dispatchers.IO) {
                scriptFile(serviceId).delete()
                launcherFile(serviceId).delete()
            }
        }
    }

    suspend fun importScript(
        serviceId: String,
        sourceUri: Uri,
        displayName: String?
    ): Pair<String, String> =
        withContext(Dispatchers.IO) {
            ensureDirectories()
            val target = scriptFile(serviceId)
            val input =
                appContext.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalArgumentException(appContext.getString(R.string.terminal_startup_error_open_script))
            val atomicTarget = AtomicFile(target)
            val output = atomicTarget.startWrite()
            try {
                input.use { source -> source.copyTo(output) }
                output.flush()
                atomicTarget.finishWrite(output)
            } catch (error: Exception) {
                atomicTarget.failWrite(output)
                throw error
            }
            runCatching { Os.chmod(target.absolutePath, PRIVATE_EXECUTABLE_MODE) }
                .onFailure { AppLogger.w(TAG, "Failed to mark imported script executable", it) }
            target.absolutePath to (displayName?.takeIf { it.isNotBlank() } ?: target.name)
        }

    suspend fun writeLauncher(config: TerminalStartupServiceConfig): File =
        withContext(Dispatchers.IO) {
            ensureDirectories()
            val launcher = launcherFile(config.id)
            val atomicLauncher = AtomicFile(launcher)
            val output = atomicLauncher.startWrite()
            try {
                output.write(buildLauncherContent(config).toByteArray(Charsets.UTF_8))
                output.flush()
                atomicLauncher.finishWrite(output)
            } catch (error: Exception) {
                atomicLauncher.failWrite(output)
                throw error
            }
            runCatching { Os.chmod(launcher.absolutePath, PRIVATE_EXECUTABLE_MODE) }
                .onFailure { AppLogger.w(TAG, "Failed to mark launcher executable", it) }
            launcher
        }

    fun newServiceId(): String = UUID.randomUUID().toString()

    private fun buildLauncherContent(config: TerminalStartupServiceConfig): String {
        validateEnvironment(config.environment)
        val lines = mutableListOf("#!/bin/bash")
        if (config.workingDirectory.isNotBlank()) {
            lines += "cd -- ${shellQuote(config.workingDirectory)} || exit 1"
        }
        config.environment.forEach { (key, value) ->
            lines += "export $key=${shellQuote(value)}"
        }
        when (config.launchMode) {
            TerminalStartupLaunchMode.COMMAND -> {
                require(config.command.isNotBlank()) { appContext.getString(R.string.terminal_startup_error_command) }
                lines += "exec /bin/bash -lc ${shellQuote(config.command)}"
            }

            TerminalStartupLaunchMode.SCRIPT -> {
                val path = config.scriptPath?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.terminal_startup_error_script))
                lines += "exec /bin/bash ${shellQuote(path)}"
            }
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun validateEnvironment(environment: Map<String, String>) {
        environment.keys.forEach { key ->
            require(ENV_KEY_REGEX.matches(key)) {
                appContext.getString(R.string.terminal_startup_error_environment_key, key)
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun loadServices(): List<TerminalStartupServiceConfig> {
        return runCatching {
            ensureDirectories()
            if (!configFile.baseFile.exists()) return emptyList()
            configFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                decodeServices(reader.readText())
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to load terminal startup services", error)
        }.getOrDefault(emptyList())
    }

    private suspend fun persistServices(services: List<TerminalStartupServiceConfig>) {
        withContext(Dispatchers.IO) {
            ensureDirectories()
            val output = configFile.startWrite()
            try {
                output.write(encodeServices(services).toByteArray(Charsets.UTF_8))
                output.flush()
                configFile.finishWrite(output)
            } catch (error: Exception) {
                configFile.failWrite(output)
                throw error
            }
        }
    }

    private fun encodeServices(services: List<TerminalStartupServiceConfig>): String =
        JSONObject().apply {
            put("version", CONFIG_VERSION)
            put(
                "services",
                JSONArray().apply {
                    services.forEach { config ->
                        put(
                            JSONObject().apply {
                                put("id", config.id)
                                put("name", config.name)
                                put("launchMode", config.launchMode.name)
                                put("command", config.command)
                                put("scriptPath", config.scriptPath ?: JSONObject.NULL)
                                put("scriptDisplayName", config.scriptDisplayName ?: JSONObject.NULL)
                                put("workingDirectory", config.workingDirectory)
                                put(
                                    "environment",
                                    JSONObject().apply {
                                        config.environment.forEach { (key, value) -> put(key, value) }
                                    }
                                )
                                put("enabled", config.enabled)
                                put("healthCheckHost", config.healthCheckHost)
                                put("healthCheckPort", config.healthCheckPort ?: JSONObject.NULL)
                                put("startupTimeoutMs", config.startupTimeoutMs)
                                put("autoRestart", config.autoRestart)
                                put("maxRestartAttempts", config.maxRestartAttempts)
                            }
                        )
                    }
                }
            )
        }.toString(2)

    private fun decodeServices(text: String): List<TerminalStartupServiceConfig> {
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        val services = root.optJSONArray("services") ?: return emptyList()
        return buildList {
            for (index in 0 until services.length()) {
                val item = services.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val environmentObject = item.optJSONObject("environment") ?: JSONObject()
                val environment = buildMap {
                    environmentObject.keys().forEach { key -> put(key, environmentObject.optString(key)) }
                }
                add(
                    TerminalStartupServiceConfig(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        launchMode =
                            runCatching {
                                TerminalStartupLaunchMode.valueOf(item.optString("launchMode"))
                            }.getOrDefault(TerminalStartupLaunchMode.COMMAND),
                        command = item.optString("command"),
                        scriptPath =
                            if (item.isNull("scriptPath")) null
                            else item.optString("scriptPath").takeIf { it.isNotBlank() },
                        scriptDisplayName =
                            if (item.isNull("scriptDisplayName")) null
                            else item.optString("scriptDisplayName").takeIf { it.isNotBlank() },
                        workingDirectory = item.optString("workingDirectory"),
                        environment = environment,
                        enabled = item.optBoolean("enabled", true),
                        healthCheckHost = item.optString("healthCheckHost", "127.0.0.1"),
                        healthCheckPort =
                            if (item.isNull("healthCheckPort")) null
                            else item.optInt("healthCheckPort").takeIf { it in 1..65535 },
                        startupTimeoutMs =
                            item.optLong(
                                "startupTimeoutMs",
                                TerminalStartupServiceConfig.DEFAULT_STARTUP_TIMEOUT_MS
                            ).coerceIn(MIN_STARTUP_TIMEOUT_MS, MAX_STARTUP_TIMEOUT_MS),
                        autoRestart = item.optBoolean("autoRestart", true),
                        maxRestartAttempts =
                            item.optInt(
                                "maxRestartAttempts",
                                TerminalStartupServiceConfig.DEFAULT_MAX_RESTART_ATTEMPTS
                            ).coerceIn(0, TerminalStartupServiceConfig.DEFAULT_MAX_RESTART_ATTEMPTS)
                    )
                )
            }
        }
    }

    private fun ensureDirectories() {
        check(baseDirectory.exists() || baseDirectory.mkdirs()) {
            appContext.getString(R.string.terminal_startup_error_create_directory)
        }
        check(scriptsDirectory.exists() || scriptsDirectory.mkdirs()) {
            appContext.getString(R.string.terminal_startup_error_create_script_directory)
        }
        check(launchersDirectory.exists() || launchersDirectory.mkdirs()) {
            appContext.getString(R.string.terminal_startup_error_create_launcher_directory)
        }
    }

    private fun scriptFile(serviceId: String): File = File(scriptsDirectory, "$serviceId.sh")

    private fun launcherFile(serviceId: String): File = File(launchersDirectory, "$serviceId.sh")

    companion object {
        private const val TAG = "TerminalStartupRepo"
        private const val CONFIG_FILE_NAME = "services.json"
        private const val CONFIG_VERSION = 1
        private const val PRIVATE_EXECUTABLE_MODE = 448 // 0700
        private const val MIN_STARTUP_TIMEOUT_MS = 1_000L
        private const val MAX_STARTUP_TIMEOUT_MS = 300_000L
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

        @Volatile
        private var instance: TerminalStartupServiceRepository? = null

        fun getInstance(context: Context): TerminalStartupServiceRepository =
            instance ?: synchronized(this) {
                instance ?: TerminalStartupServiceRepository(context).also { instance = it }
            }
    }
}
