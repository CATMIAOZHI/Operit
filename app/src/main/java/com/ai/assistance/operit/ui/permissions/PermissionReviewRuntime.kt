package com.ai.assistance.operit.ui.permissions

import android.content.Context

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class PermissionReviewStatus {
    IN_PROGRESS,
    APPROVED,
    DENIED,
    TIMED_OUT,
    ABORTED,
    FAILED,
}

@Serializable
enum class PermissionReviewExactOverrideState {
    PENDING,
    IN_REVIEW,
    CONSUMED,
    EXPIRED,
}

internal fun PermissionReviewEvent.effectiveExactOverrideState(
    nowMs: Long = System.currentTimeMillis(),
): PermissionReviewExactOverrideState? =
    if (exactOverrideState == PermissionReviewExactOverrideState.PENDING &&
        exactOverrideExpiresAt?.let { expiresAt -> nowMs >= expiresAt } == true
    ) {
        PermissionReviewExactOverrideState.EXPIRED
    } else {
        exactOverrideState
    }

@Serializable
data class PermissionReviewAction(
    val targetId: String,
    val kind: String,
    val toolName: String,
    val summary: String,
    val command: String? = null,
    val cwd: String? = null,
    val paths: List<String> = emptyList(),
    val networkTarget: String? = null,
    val justification: String? = null,
    val parameters: List<ToolParameter> = emptyList(),
    val exactFingerprint: String = "",
) {
    fun fingerprint(): String {
        if (exactFingerprint.isNotBlank()) return exactFingerprint
        val canonical =
            Json.encodeToString(
                copy(
                    targetId = "",
                    summary = "",
                    parameters = parameters.sortedWith(compareBy(ToolParameter::name, ToolParameter::value)),
                )
            )
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private val PATH_PARAMETER_MARKERS =
            listOf(
                "path",
                "file",
                "directory",
                "folder",
                "cwd",
                "workdir",
                "source",
                "destination",
            )
        private val NETWORK_PARAMETER_MARKERS = listOf("url", "host", "endpoint")

        fun fromTool(
            tool: AITool,
            operationDescription: String,
            reviewContext: ToolPermissionReviewContext,
            targetId: String,
        ): PermissionReviewAction {
            val values = tool.parameters.associate { parameter -> parameter.name to parameter.value }
            val command =
                (values["command"]
                        ?: values["input"]?.takeIf {
                            tool.name.contains("terminal", ignoreCase = true)
                        })
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val cwd =
                listOf("cwd", "workdir", "working_directory")
                    .firstNotNullOfOrNull { key -> values[key]?.trim()?.takeIf(String::isNotEmpty) }
                    ?: reviewContext.workspacePath
            val paths =
                tool.parameters
                    .filter { parameter ->
                        val name = parameter.name.lowercase()
                        PATH_PARAMETER_MARKERS.any(name::contains)
                    }
                    .map(ToolParameter::value)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(32)
            val networkTarget =
                tool.parameters
                    .firstOrNull { parameter ->
                        val name = parameter.name.lowercase()
                        NETWORK_PARAMETER_MARKERS.any(name::contains)
                    }
                    ?.value
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val justification =
                listOf("justification", "reason", "description")
                    .firstNotNullOfOrNull { key -> values[key]?.trim()?.takeIf(String::isNotEmpty) }
            val kind =
                when {
                    command != null || tool.name.contains("shell", ignoreCase = true) ||
                        tool.name.contains("terminal", ignoreCase = true) -> "command"
                    tool.name.contains("mcp", ignoreCase = true) -> "mcp"
                    tool.name.contains("network", ignoreCase = true) ||
                        tool.name.contains("web", ignoreCase = true) ||
                        tool.name.contains("download", ignoreCase = true) ||
                        networkTarget != null -> "network"
                    tool.name.contains("patch", ignoreCase = true) -> "patch"
                    tool.name.contains("file", ignoreCase = true) || paths.isNotEmpty() -> "filesystem"
                    tool.name.contains("permission", ignoreCase = true) -> "permission_request"
                    else -> "tool"
                }
            val summarySource =
                command
                    ?: paths.firstOrNull()?.let { path -> "${tool.name}: $path" }
                    ?: networkTarget?.let { target -> "${tool.name}: $target" }
                    ?: operationDescription.ifBlank { tool.name }
            return PermissionReviewAction(
                targetId = targetId,
                kind = kind,
                toolName = tool.name,
                summary = summarize(summarySource),
                command = command,
                cwd = cwd,
                paths = paths,
                networkTarget = networkTarget,
                justification = justification,
                parameters = tool.parameters.map { it.copy(value = truncateMiddle(it.value, 16_000)) },
                exactFingerprint =
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            Json.encodeToString(
                                    tool.copy(
                                            parameters =
                                                tool.parameters.sortedWith(
                                                    compareBy(ToolParameter::name, ToolParameter::value)
                                                ),
                                            description = "",
                                        ) to
                                        listOf(reviewContext.workspacePath, reviewContext.workspaceEnv)
                                )
                                .toByteArray()
                        )
                        .joinToString("") { byte -> "%02x".format(byte) },
            )
        }

        private fun summarize(value: String): String =
            value.trim().replace(Regex("\\s+"), " ").let { normalized ->
                if (normalized.length <= 180) normalized else normalized.take(177) + "..."
            }

        private fun truncateMiddle(value: String, maxChars: Int): String {
            if (value.length <= maxChars) return value
            val marker = "<truncated omitted_chars=\"${value.length - maxChars}\" />"
            val remaining = (maxChars - marker.length).coerceAtLeast(0)
            val prefix = remaining / 2
            return value.take(prefix) + marker + value.takeLast(remaining - prefix)
        }
    }
}

@Serializable
data class PermissionReviewEvent(
    val id: String,
    val parentChatId: String,
    val timingScopeId: String?,
    val invocationIndex: Int,
    val batchPosition: Int,
    val batchSize: Int,
    val action: PermissionReviewAction,
    val actionFingerprint: String,
    val status: PermissionReviewStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val riskLevel: PermissionReviewRiskLevel? = null,
    val userAuthorization: PermissionReviewAuthorization? = null,
    val rationale: String? = null,
    val failureKind: PermissionReviewFailureKind? = null,
    val attemptCount: Int = 0,
    val reviewerTaskId: String? = null,
    val exactOverrideRecorded: Boolean = false,
    val exactOverrideState: PermissionReviewExactOverrideState? = null,
    val exactOverrideExpiresAt: Long? = null,
    val exactOverrideApplied: Boolean = false,
    val resolutionSource: String? = null,
)

object PermissionReviewEventRepository {
    private const val TAG = "PermissionReviewEvents"
    private const val MAX_EVENTS = 200
    private const val PREFERENCES_NAME = "permission_review_events"
    private const val EVENTS_KEY = "events_v1"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var applicationContext: Context? = null
    private val _events = MutableStateFlow<List<PermissionReviewEvent>>(emptyList())
    val events: StateFlow<List<PermissionReviewEvent>> = _events.asStateFlow()

    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(lock) {
            if (applicationContext != null) return
            val appContext = context.applicationContext
            applicationContext = appContext
            val stored =
                runCatching {
                        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .getString(EVENTS_KEY, null)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "Failed to read stored permission review events", error)
                    }
                    .getOrNull()
            if (!stored.isNullOrBlank()) {
                val decoded =
                    runCatching { json.decodeFromString<List<PermissionReviewEvent>>(stored) }
                        .onFailure { error ->
                            AppLogger.e(
                                TAG,
                                "Stored permission review events are invalid; preserving raw data",
                                error,
                            )
                        }
                        .getOrNull()
                if (decoded != null) {
                    _events.value =
                        decoded
                        .map { event ->
                            if (event.status == PermissionReviewStatus.IN_PROGRESS) {
                                event.copy(
                                    status = PermissionReviewStatus.ABORTED,
                                    completedAt = System.currentTimeMillis(),
                                    rationale = null,
                                    exactOverrideRecorded = false,
                                    exactOverrideState = null,
                                    exactOverrideExpiresAt = null,
                                    exactOverrideApplied = false,
                                )
                            } else {
                                event.copy(
                                    exactOverrideRecorded = false,
                                    exactOverrideState = null,
                                    exactOverrideExpiresAt = null,
                                    exactOverrideApplied = false,
                                )
                            }
                        }
                        .takeLast(MAX_EVENTS)
                    // Rewrite immediately so older builds cannot leave sensitive summaries or
                    // stale in-progress/override flags on disk until the next review event.
                    persistLocked()
                }
            }
        }
    }

    fun publish(event: PermissionReviewEvent) {
        synchronized(lock) {
            val retained = _events.value.filterNot { existing -> existing.id == event.id }
            _events.value = (retained + event).takeLast(MAX_EVENTS)
            persistLocked()
        }
    }

    fun update(id: String, transform: (PermissionReviewEvent) -> PermissionReviewEvent) {
        synchronized(lock) {
            _events.value = _events.value.map { event -> if (event.id == id) transform(event) else event }
            persistLocked()
        }
    }

    fun findById(id: String): PermissionReviewEvent? =
        _events.value.firstOrNull { event -> event.id == id }

    fun findForInvocation(
        parentChatId: String,
        timingScopeId: String?,
        invocationIndex: Int,
    ): PermissionReviewEvent? =
        _events.value.lastOrNull { event ->
            event.parentChatId == parentChatId &&
                event.timingScopeId == timingScopeId &&
                event.invocationIndex == invocationIndex
        }

    fun recentDenials(parentChatId: String): kotlinx.coroutines.flow.Flow<List<PermissionReviewEvent>> =
        events.map { allEvents ->
            allEvents
                .asReversed()
                .filter { event ->
                    event.parentChatId == parentChatId && event.status == PermissionReviewStatus.DENIED
                }
                .distinctBy(PermissionReviewEvent::actionFingerprint)
                .take(10)
        }

    fun approveExactActionOnce(reviewId: String): Boolean {
        val event = _events.value.firstOrNull { it.id == reviewId } ?: return false
        if (event.status != PermissionReviewStatus.DENIED) return false
        val expiresAt = PermissionReviewExactOverrideStore.record(
            parentChatId = event.parentChatId,
            actionFingerprint = event.actionFingerprint,
            originalReviewId = event.id,
        )
        update(reviewId) {
            current ->
            current.copy(
                exactOverrideRecorded = true,
                exactOverrideState = PermissionReviewExactOverrideState.PENDING,
                exactOverrideExpiresAt = expiresAt,
            )
        }
        return true
    }

    private fun persistLocked() {
        val context = applicationContext ?: return
        val sanitized =
            _events.value.map { event ->
                event.copy(
                    action =
                        event.action.copy(
                            command = null,
                            cwd = null,
                            paths = emptyList(),
                            networkTarget = null,
                            justification = null,
                            parameters = emptyList(),
                            summary = "${event.action.toolName} (${event.action.kind})",
                        ),
                    rationale = null,
                )
            }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(EVENTS_KEY, json.encodeToString(sanitized))
            .apply()
    }
}

data class PermissionReviewExactOverride(
    val originalReviewId: String,
    val approvedAt: Long,
)

object PermissionReviewExactOverrideStore {
    private const val TTL_MS = 5 * 60 * 1000L
    private val approvals = ConcurrentHashMap<String, PermissionReviewExactOverride>()
    private val reservations = ConcurrentHashMap<String, Pair<String, PermissionReviewExactOverride>>()

    private fun key(parentChatId: String, actionFingerprint: String): String =
        "$parentChatId:$actionFingerprint"

    fun record(
        parentChatId: String,
        actionFingerprint: String,
        originalReviewId: String,
    ): Long {
        val approvedAt = System.currentTimeMillis()
        approvals[key(parentChatId, actionFingerprint)] =
            PermissionReviewExactOverride(originalReviewId, approvedAt)
        return approvedAt + TTL_MS
    }

    fun reserve(
        parentChatId: String,
        actionFingerprint: String,
        reviewId: String,
    ): PermissionReviewExactOverride? {
        val approvalKey = key(parentChatId, actionFingerprint)
        val approval = approvals.remove(approvalKey) ?: return null
        if (System.currentTimeMillis() - approval.approvedAt > TTL_MS) {
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(
                    exactOverrideRecorded = false,
                    exactOverrideState = PermissionReviewExactOverrideState.EXPIRED,
                    exactOverrideExpiresAt = null,
                )
            }
            return null
        }
        reservations[reviewId] = approvalKey to approval
        PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
            event.copy(exactOverrideState = PermissionReviewExactOverrideState.IN_REVIEW)
        }
        return approval
    }

    fun commit(reviewId: String) {
        val (_, approval) = reservations.remove(reviewId) ?: return
        PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
            event.copy(
                exactOverrideRecorded = false,
                exactOverrideState = PermissionReviewExactOverrideState.CONSUMED,
                exactOverrideExpiresAt = null,
            )
        }
    }

    fun release(reviewId: String) {
        val (approvalKey, approval) = reservations.remove(reviewId) ?: return
        if (System.currentTimeMillis() - approval.approvedAt <= TTL_MS) {
            approvals.putIfAbsent(approvalKey, approval)
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(exactOverrideState = PermissionReviewExactOverrideState.PENDING)
            }
        } else {
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(
                    exactOverrideRecorded = false,
                    exactOverrideState = PermissionReviewExactOverrideState.EXPIRED,
                    exactOverrideExpiresAt = null,
                )
            }
        }
    }
}

data class PermissionReviewCircuitBreakerResult(
    val interruptTurn: Boolean,
    val consecutiveDenials: Int,
    val recentDenials: Int,
)

object PermissionReviewCircuitBreaker {
    const val INTERRUPT_MARKER = "[automatic-review-turn-interrupted]"
    private const val MAX_TURNS = 64
    private const val MAX_CONSECUTIVE_DENIALS = 3
    private const val MAX_RECENT_DENIALS = 10
    private const val WINDOW_SIZE = 50

    private data class TurnState(
        var consecutiveDenials: Int = 0,
        val recent: ArrayDeque<Boolean> = ArrayDeque(),
        var interrupted: Boolean = false,
    )

    private val states = object : LinkedHashMap<String, TurnState>(MAX_TURNS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TurnState>?): Boolean =
            size > MAX_TURNS
    }

    @Synchronized
    fun isInterrupted(parentChatId: String, turnScopeId: String?): Boolean =
        states["$parentChatId:${turnScopeId ?: "unknown"}"]?.interrupted == true

    @Synchronized
    fun recordDenial(parentChatId: String, turnScopeId: String?): PermissionReviewCircuitBreakerResult {
        val key = "$parentChatId:${turnScopeId ?: "unknown"}"
        val state = states.getOrPut(key) { TurnState() }
        state.consecutiveDenials += 1
        state.recent.addLast(true)
        while (state.recent.size > WINDOW_SIZE) state.recent.removeFirst()
        val recentDenials = state.recent.count { denied -> denied }
        val interrupt =
            !state.interrupted &&
                (state.consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || recentDenials >= MAX_RECENT_DENIALS)
        if (interrupt) state.interrupted = true
        return PermissionReviewCircuitBreakerResult(
            interruptTurn = interrupt,
            consecutiveDenials = state.consecutiveDenials,
            recentDenials = recentDenials,
        )
    }

    @Synchronized
    fun recordNonDenial(parentChatId: String, turnScopeId: String?) {
        val key = "$parentChatId:${turnScopeId ?: "unknown"}"
        val state = states.getOrPut(key) { TurnState() }
        state.consecutiveDenials = 0
        state.recent.addLast(false)
        while (state.recent.size > WINDOW_SIZE) state.recent.removeFirst()
    }
}

data class PermissionReviewInspectionScope(
    val reviewId: String,
    val workspaceRoot: File?,
    val allowedTargets: List<File>,
    val remainingCalls: AtomicInteger = AtomicInteger(12),
    val localInspectionEnabled: Boolean = true,
)

object PermissionReviewInspectionRegistry {
    private const val MAX_TEXT_CHARS = 8_000
    private const val MAX_DIRECTORY_ENTRIES = 80
    private val scopes = ConcurrentHashMap<String, PermissionReviewInspectionScope>()

    private fun isLocalEnvironment(workspaceEnv: String?): Boolean =
        workspaceEnv.isNullOrBlank() || workspaceEnv.equals("android", ignoreCase = true)

    fun newReviewId(): String = UUID.randomUUID().toString()

    fun register(
        reviewId: String,
        workspacePath: String?,
        workspaceEnv: String?,
        action: PermissionReviewAction,
    ) {
        // Virtual environments (repo:*, linux) use paths that must not be interpreted as local
        // Android files. A repo workspace commonly uses "/" as its virtual root, which would
        // otherwise resolve to the device root and let the reviewer read arbitrary app files.
        val localInspectionEnabled = isLocalEnvironment(workspaceEnv)
        val workspace =
            if (localInspectionEnabled) {
                workspacePath?.takeIf(String::isNotBlank)?.let(::File)?.canonicalOrNull()
            } else {
                null
            }
        val targets =
            if (localInspectionEnabled) {
                action.paths
                    .mapNotNull { path -> resolvePath(path, workspace)?.canonicalOrNull() }
                    .distinctBy(File::getPath)
            } else {
                emptyList()
            }
        scopes[reviewId] =
            PermissionReviewInspectionScope(
                reviewId,
                workspace,
                targets,
                localInspectionEnabled = localInspectionEnabled,
            )
    }

    fun unregister(reviewId: String) {
        scopes.remove(reviewId)
    }

    fun inspect(reviewId: String, operation: String, requestedPath: String?): String {
        val scope = scopes[reviewId] ?: return "Inspection rejected: review is not active."
        if (!scope.localInspectionEnabled) {
            return "Inspection rejected: local file inspection is not available for this workspace environment."
        }
        if (scope.remainingCalls.getAndDecrement() <= 0) {
            return "Inspection rejected: per-review inspection limit reached."
        }
        return when (operation.trim().lowercase()) {
            "path_metadata" -> inspectPath(scope, requestedPath, includeText = false)
            "read_text" -> inspectPath(scope, requestedPath, includeText = true)
            "git_context" -> inspectGitContext(scope)
            else -> "Inspection rejected: unsupported operation '$operation'."
        }
    }

    private fun inspectPath(
        scope: PermissionReviewInspectionScope,
        requestedPath: String?,
        includeText: Boolean,
    ): String {
        val raw = requestedPath?.trim()?.takeIf(String::isNotEmpty)
            ?: return "Inspection rejected: path is required."
        val file = resolvePath(raw, scope.workspaceRoot)?.canonicalOrNull()
            ?: return "Inspection rejected: path could not be resolved."
        if (!isAllowed(file, scope)) {
            return "Inspection rejected: path is outside the active workspace and reviewed targets."
        }
        val metadata = buildString {
            appendLine("path=${file.path}")
            appendLine("exists=${file.exists()}")
            appendLine("is_file=${file.isFile}")
            appendLine("is_directory=${file.isDirectory}")
            if (file.exists()) appendLine("size_bytes=${file.length()}")
            if (file.isDirectory) {
                val children = file.listFiles().orEmpty().sortedBy(File::getName).take(MAX_DIRECTORY_ENTRIES)
                appendLine("entries_shown=${children.size}")
                children.forEach { child -> appendLine("- ${child.name}${if (child.isDirectory) "/" else ""}") }
            }
        }
        if (!includeText || !file.isFile) return metadata.trimEnd()
        if (isSensitivePath(file)) {
            return metadata + "content_redacted=true\nreason=sensitive credential-like path"
        }
        val text = runCatching { readBoundedText(file) }
            .getOrElse { error -> return metadata + "read_error=${error.message}" }
        return metadata + "\ntext_preview:\n" + text
    }

    private fun inspectGitContext(scope: PermissionReviewInspectionScope): String {
        val workspace = scope.workspaceRoot ?: return "Git inspection unavailable: no local workspace."
        val gitDir = generateSequence(workspace) { it.parentFile }
            .map { candidate -> File(candidate, ".git") }
            .firstOrNull(File::exists)
            ?: return "Git inspection: no .git directory found."
        val head = runCatching { readBoundedText(File(gitDir, "HEAD")).trim() }.getOrNull()
        return buildString {
            appendLine("git_dir=${gitDir.path}")
            appendLine("head=${head ?: "unknown"}")
            append("config_redacted=true")
        }.trimEnd()
    }

    private fun isAllowed(file: File, scope: PermissionReviewInspectionScope): Boolean {
        val path = file.path
        if (scope.workspaceRoot?.let { root -> path == root.path || path.startsWith(root.path + File.separator) } == true) {
            return true
        }
        return scope.allowedTargets.any { target ->
            path == target.path || path.startsWith(target.path + File.separator)
        }
    }

    private fun resolvePath(path: String, workspace: File?): File? {
        val direct = File(path)
        return when {
            direct.isAbsolute -> direct
            workspace != null -> File(workspace, path)
            else -> null
        }
    }

    private fun isSensitivePath(file: File): Boolean {
        val name = file.name.lowercase()
        val path = file.path.replace('\\', '/').lowercase()
        return name == ".env" || name.startsWith(".env.") ||
            name in
                setOf(
                    "id_rsa",
                    "id_ed25519",
                    "shadow",
                    "keystore",
                    "credentials",
                    ".git-credentials",
                    ".npmrc",
                    "local.properties",
                    "key.properties",
                    "gradle.properties",
                    "google-services.json",
                ) ||
            name.endsWith(".jks") || name.endsWith(".p12") || name.endsWith(".pfx") ||
            name.endsWith(".pem") || name.endsWith(".key") ||
            path.contains("/.ssh/") || path.contains("/.aws/") ||
            path.contains("/.config/gcloud/")
    }

    private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    private fun readBoundedText(file: File): String =
        file.bufferedReader().use { reader ->
            val buffer = CharArray(MAX_TEXT_CHARS)
            val count = reader.read(buffer, 0, buffer.size).coerceAtLeast(0)
            String(buffer, 0, count)
        }
}
