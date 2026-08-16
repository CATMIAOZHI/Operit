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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun terminalStartupServiceDirectory(noBackupFilesDir: File): File =
    File(noBackupFilesDir, "operit/terminal-startup-services")

internal fun decodePersistedTerminalStartupServiceId(rawValue: Any?): String {
    require(rawValue is String && rawValue.isNotBlank()) {
        "Invalid terminal startup service ID"
    }
    val parsed = runCatching { UUID.fromString(rawValue) }.getOrNull()
    require(parsed != null && parsed.toString().equals(rawValue, ignoreCase = true)) {
        "Invalid terminal startup service ID"
    }
    return rawValue
}

internal fun decodeUniquePersistedTerminalStartupServiceId(
    rawValue: Any?,
    normalizedIds: MutableSet<String>,
): String {
    val id = decodePersistedTerminalStartupServiceId(rawValue)
    require(normalizedIds.add(UUID.fromString(id).toString())) {
        "Duplicate terminal startup service ID"
    }
    return id
}

internal fun decodePersistedEnvironmentValue(rawValue: Any?): String {
    require(rawValue is String) { "Invalid terminal startup environment value" }
    return rawValue
}

internal fun decodePersistedHealthCheckPort(rawValue: Any?): Int? {
    if (rawValue == null) return null
    val number = rawValue as? Number
        ?: throw IllegalArgumentException("Invalid terminal startup health-check port")
    val doubleValue = number.toDouble()
    val longValue = number.toLong()
    require(
        doubleValue.isFinite() &&
            doubleValue == longValue.toDouble() &&
            longValue in 1L..65535L
    ) { "Invalid terminal startup health-check port" }
    return longValue.toInt()
}

internal suspend fun stopRuntimeThenDeletePersisted(
    stopRuntime: suspend () -> Boolean,
    deletePersisted: suspend () -> Unit,
    restoreRuntime: suspend () -> Boolean,
    terminationFailure: () -> Throwable,
) = withContext(NonCancellable) {
    if (!stopRuntime()) throw terminationFailure()
    try {
        deletePersisted()
    } catch (error: Throwable) {
        if (!restoreRuntime()) {
            throw TerminalStartupRuntimeRestoreException(error)
        }
        throw error
    }
}

internal class TerminalStartupRuntimeRestoreException(cause: Throwable) :
    IllegalStateException("Failed to restore terminal startup service runtime", cause)

internal suspend fun persistAndPublish(
    persist: suspend () -> Unit,
    publish: () -> Unit,
) = withContext(NonCancellable) {
    persist()
    publish()
}

internal suspend fun <T> useStagedFile(
    stagedFile: File,
    block: suspend (File) -> T,
): T = try {
    block(stagedFile)
} finally {
    stagedFile.delete()
}

internal suspend fun replaceStagedFileAndPersist(
    stagedFile: File,
    targetFile: File,
    persist: suspend () -> Unit,
) {
    require(stagedFile.parentFile?.canonicalFile == targetFile.parentFile?.canonicalFile)
    val backupFile = File(
        requireNotNull(targetFile.parentFile),
        ".${targetFile.name}.${UUID.randomUUID()}.backup",
    )
    var targetBackedUp = false
    var stagedInstalled = false
    try {
        if (targetFile.exists()) {
            check(targetFile.renameTo(backupFile)) { "Failed to back up ${targetFile.name}" }
            targetBackedUp = true
        }
        check(stagedFile.renameTo(targetFile)) { "Failed to install ${targetFile.name}" }
        stagedInstalled = true
        persist()
        if (targetBackedUp) {
            backupFile.delete()
        }
    } catch (error: Throwable) {
        if (stagedInstalled && targetFile.exists() && !targetFile.delete()) {
            error.addSuppressed(IllegalStateException("Failed to remove replacement ${targetFile.name}"))
        }
        if (targetBackedUp && backupFile.exists() && !backupFile.renameTo(targetFile)) {
            error.addSuppressed(IllegalStateException("Failed to restore ${targetFile.name}"))
        }
        throw error
    } finally {
        stagedFile.delete()
    }
}

internal suspend fun replaceStagedFilePersistAndPublish(
    stagedFile: File,
    targetFile: File,
    persist: suspend () -> Unit,
    publish: () -> Unit,
) = withContext(NonCancellable) {
    replaceStagedFileAndPersist(stagedFile, targetFile, persist)
    publish()
}

class TerminalStartupServiceRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    // Commands and environment values are executable configuration. Keep them outside raw
    // snapshots so an imported archive cannot install commands that run on the next startup.
    private val baseDirectory = terminalStartupServiceDirectory(appContext.noBackupFilesDir)
    private val scriptsDirectory = File(baseDirectory, "scripts")
    private val launchersDirectory = File(baseDirectory, "launchers")
    private val configFile = AtomicFile(File(baseDirectory, CONFIG_FILE_NAME))
    private val writeMutex = Mutex()

    private val initialLoad = loadServices()
    private val loadFailure = initialLoad.exceptionOrNull()
    private val _services = MutableStateFlow(initialLoad.getOrDefault(emptyList()))
    val services: StateFlow<List<TerminalStartupServiceConfig>> = _services.asStateFlow()

    fun snapshot(): List<TerminalStartupServiceConfig> {
        ensureConfigLoaded()
        return _services.value
    }

    fun loadErrorMessage(): String? =
        if (loadFailure == null) null
        else appContext.getString(R.string.terminal_startup_error_load_config)

    fun getById(serviceId: String): TerminalStartupServiceConfig? {
        ensureConfigLoaded()
        decodePersistedTerminalStartupServiceId(serviceId)
        return _services.value.firstOrNull { it.id == serviceId }
    }

    suspend fun upsert(config: TerminalStartupServiceConfig) {
        decodePersistedTerminalStartupServiceId(config.id)
        writeMutex.withLock {
            ensureConfigLoaded()
            val updated = _services.value.toMutableList()
            val index = updated.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                updated[index] = config
            } else {
                updated.add(config)
            }
            persistAndPublish(
                persist = { persistServices(updated) },
                publish = { _services.value = updated },
            )
        }
    }

    suspend fun delete(serviceId: String) {
        decodePersistedTerminalStartupServiceId(serviceId)
        writeMutex.withLock {
            ensureConfigLoaded()
            val updated = _services.value.filterNot { it.id == serviceId }
            withContext(NonCancellable) {
                persistServices(updated)
                _services.value = updated
                withContext(Dispatchers.IO) {
                    scriptFile(serviceId).delete()
                    launcherFile(serviceId).delete()
                }
            }
        }
    }

    suspend fun upsertWithImportedScript(
        config: TerminalStartupServiceConfig,
        sourceUri: Uri,
        displayName: String?
    ): TerminalStartupServiceConfig = withContext(Dispatchers.IO) {
        ensureConfigLoaded()
        decodePersistedTerminalStartupServiceId(config.id)
        ensureDirectories()
        val stagedFile = File.createTempFile("${config.id}.", ".pending", scriptsDirectory)
        useStagedFile(stagedFile) {
            ensureConfigLoaded()
            val input =
                appContext.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalArgumentException(
                        appContext.getString(R.string.terminal_startup_error_open_script)
                    )
            input.use { source -> stagedFile.outputStream().use(source::copyTo) }
            currentCoroutineContext().ensureActive()
            val target = scriptFile(config.id)
            val saved = config.copy(
                scriptPath = target.absolutePath,
                scriptDisplayName = displayName?.takeIf { it.isNotBlank() } ?: target.name,
            )
            writeMutex.withLock {
                // Once the file/config commit begins, finish the rollback-or-publish sequence even
                // if the editor's Compose scope is cancelled.
                ensureConfigLoaded()
                val updated = _services.value.toMutableList()
                val index = updated.indexOfFirst { it.id == saved.id }
                if (index >= 0) updated[index] = saved else updated.add(saved)
                replaceStagedFilePersistAndPublish(
                    stagedFile = stagedFile,
                    targetFile = target,
                    persist = {
                        persistServices(updated)
                    },
                    publish = {
                        _services.value = updated
                    },
                )
            }
            runCatching { Os.chmod(target.absolutePath, PRIVATE_EXECUTABLE_MODE) }
                .onFailure { AppLogger.w(TAG, "Failed to mark imported script executable", it) }
            saved
        }
    }

    suspend fun writeLauncher(config: TerminalStartupServiceConfig): File =
        withContext(Dispatchers.IO) {
            ensureConfigLoaded()
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

    private fun loadServices(): Result<List<TerminalStartupServiceConfig>> =
        runCatching {
            ensureDirectories()
            if (!configFile.baseFile.exists()) {
                emptyList()
            } else {
                configFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                    decodeServices(reader.readText())
                }
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "Failed to load terminal startup services", error)
        }

    private fun ensureConfigLoaded() {
        loadFailure?.let { error ->
            throw IllegalStateException(
                requireNotNull(loadErrorMessage()),
                error
            )
        }
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
        require(text.isNotBlank()) { "Empty terminal startup service configuration" }
        val root = JSONObject(text)
        require(root.optInt("version", -1) == CONFIG_VERSION) {
            "Unsupported terminal startup service configuration version"
        }
        val services = root.optJSONArray("services")
            ?: throw IllegalArgumentException("Missing terminal startup service list")
        val normalizedIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until services.length()) {
                val item = services.getJSONObject(index)
                val id = decodeUniquePersistedTerminalStartupServiceId(
                    if (!item.has("id") || item.isNull("id")) null else item.get("id"),
                    normalizedIds,
                )
                val environmentObject =
                    if (!item.has("environment") || item.isNull("environment")) {
                        JSONObject()
                    } else {
                        item.getJSONObject("environment")
                    }
                val environment = buildMap {
                    environmentObject.keys().forEach { key ->
                        put(key, decodePersistedEnvironmentValue(environmentObject.get(key)))
                    }
                }
                add(
                    TerminalStartupServiceConfig(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        launchMode =
                            TerminalStartupLaunchMode.valueOf(item.getString("launchMode")),
                        command = item.optString("command"),
                        scriptPath =
                            if (item.isNull("scriptPath")) null
                            else item.optString("scriptPath").takeIf { it.isNotBlank() },
                        scriptDisplayName =
                            if (item.isNull("scriptDisplayName")) null
                            else item.optString("scriptDisplayName").takeIf { it.isNotBlank() },
                        workingDirectory = item.optString("workingDirectory"),
                        environment = environment,
                        enabled = decodePersistedEnabled(
                            if (!item.has("enabled") || item.isNull("enabled")) null
                            else item.get("enabled")
                        ),
                        healthCheckHost = item.optString("healthCheckHost", "127.0.0.1"),
                        healthCheckPort = decodePersistedHealthCheckPort(
                            if (!item.has("healthCheckPort") || item.isNull("healthCheckPort")) null
                            else item.get("healthCheckPort")
                        ),
                        startupTimeoutMs =
                            decodePersistedStartupTimeoutMs(
                                if (!item.has("startupTimeoutMs") || item.isNull("startupTimeoutMs")) null
                                else item.get("startupTimeoutMs")
                            ),
                        autoRestart = decodePersistedAutoRestart(
                            if (!item.has("autoRestart") || item.isNull("autoRestart")) null
                            else item.get("autoRestart")
                        ),
                        maxRestartAttempts =
                            decodePersistedMaxRestartAttempts(
                                if (!item.has("maxRestartAttempts") ||
                                    item.isNull("maxRestartAttempts")
                                ) {
                                    null
                                } else {
                                    item.get("maxRestartAttempts")
                                }
                            )
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

    private fun scriptFile(serviceId: String): File =
        File(scriptsDirectory, "${decodePersistedTerminalStartupServiceId(serviceId)}.sh")

    private fun launcherFile(serviceId: String): File =
        File(launchersDirectory, "${decodePersistedTerminalStartupServiceId(serviceId)}.sh")

    companion object {
        private const val TAG = "TerminalStartupRepo"
        private const val CONFIG_FILE_NAME = "services.json"
        private const val CONFIG_VERSION = 1
        private const val PRIVATE_EXECUTABLE_MODE = 448 // 0700
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

        @Volatile
        private var instance: TerminalStartupServiceRepository? = null

        fun getInstance(context: Context): TerminalStartupServiceRepository =
            instance ?: synchronized(this) {
                instance ?: TerminalStartupServiceRepository(context).also { instance = it }
            }
    }
}

internal fun decodePersistedStartupTimeoutMs(rawValue: Any?): Long {
    if (rawValue == null) return TerminalStartupServiceConfig.DEFAULT_STARTUP_TIMEOUT_MS
    require(rawValue is Number) { "Invalid terminal startup timeout" }
    val asDouble = rawValue.toDouble()
    require(asDouble.isFinite() && asDouble % 1.0 == 0.0) { "Invalid terminal startup timeout" }
    val value = rawValue.toLong()
    require(value.toDouble() == asDouble && value in 1_000L..300_000L) {
        "Invalid terminal startup timeout"
    }
    return value
}

internal fun decodePersistedEnabled(rawValue: Any?): Boolean {
    if (rawValue == null) return true
    require(rawValue is Boolean) { "Invalid terminal startup enabled flag" }
    return rawValue
}

internal fun decodePersistedAutoRestart(rawValue: Any?): Boolean {
    if (rawValue == null) return true
    require(rawValue is Boolean) { "Invalid terminal startup auto-restart flag" }
    return rawValue
}

internal fun decodePersistedMaxRestartAttempts(rawValue: Any?): Int {
    if (rawValue == null) return TerminalStartupServiceConfig.DEFAULT_MAX_RESTART_ATTEMPTS
    require(rawValue is Number) { "Invalid terminal startup restart limit" }
    val asDouble = rawValue.toDouble()
    val value = rawValue.toLong()
    require(
        asDouble.isFinite() &&
            asDouble == value.toDouble() &&
            value in 0L..TerminalStartupServiceConfig.DEFAULT_MAX_RESTART_ATTEMPTS.toLong()
    ) { "Invalid terminal startup restart limit" }
    return value.toInt()
}
