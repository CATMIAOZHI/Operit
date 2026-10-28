package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/** The first writable task capability writes only artifacts in its own host-selected directory. */
internal object OrbitTaskActionTools {
    const val READ_WEB = "orbit_read_web"
    const val WRITE = "orbit_write_artifact"
    const val WAIT = "orbit_wait_for_user"
    val names = setOf(READ_WEB, WRITE, WAIT)
    val prompts = listOf(
        ToolPrompt(name = READ_WEB, description = "Read a webpage with GET, without account credentials. Returns a bounded visible-text excerpt.",
            parametersStructured = listOf(ToolParameterSchema("url", description = "http(s) URL", required = true))),
        ToolPrompt(name = WRITE, description = "Save a text artifact in this task's own folder. Cannot write elsewhere or execute its content.",
            parametersStructured = listOf(
                ToolParameterSchema("name", description = "A filename, without directory separators, at most 80 characters", required = true),
                ToolParameterSchema("content", description = "UTF-8 text, at most 200000 characters", required = true))),
        ToolPrompt(name = WAIT, description = "Pause this task until the user supplies missing information or completes a required manual step. No busy waiting.",
            parametersStructured = listOf(
                ToolParameterSchema("reason", description = "A concise, actionable request to the user; no secrets", required = true),
                ToolParameterSchema("browser_url", description = "Optional http(s) page for the user to open and operate manually. Never include credentials.", required = false))),
    )

    suspend fun execute(context: Context, tool: AITool): ToolResult {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val scope = checkNotNull(OrbitTaskExecution.resolve(context, caller)) { "No live task owns this call" }
        val args = tool.parameters.associate { it.name to it.value }
        return when (tool.name) {
            READ_WEB -> {
                val url = args["url"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("$READ_WEB needs url (an http or https address)")
                val source = OrbitTaskRunner.readUrl(url)
                OrbitTaskExecution.resolve(context, caller)
                success(tool.name, source.take(20_000) +
                    if (source.length > 20_000) "\n[Excerpt truncated: showed the first 20000 of ${source.length} characters. " +
                        "The rest of the page cannot be read with this tool.]" else "")
            }
            WRITE -> write(context, tool.name, scope, args["name"]?.trim().orEmpty(),
                args["content"] ?: error("$WRITE needs content (the full text of the file)"))
            WAIT -> {
                check(caller == scope.binding.rootChatId) { "Tell the root agent which user input is needed" }
                val reason = args["reason"]?.trim().orEmpty()
                require(reason.isNotEmpty()) { "reason is required: say what the user needs to give or do" }
                require(reason.length <= 2000) { "reason has ${reason.length} characters; at most 2000" }
                val url = args["browser_url"].orEmpty()
                if (url.isNotEmpty()) OrbitTaskRepository.validateSource(url)
                check(AppDatabase.getDatabase(context).orbitTaskDao().waitForUser(
                    scope.run, reason, OrbitTaskRunner.bootId(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(), url)) {
                    "This run is no longer active, so it cannot wait; it was stopped or already finished"
                }
                success(tool.name, org.json.JSONObject().put("state", "WAITING_USER").put("task_id", scope.task.id)
                    .put("reason", reason).apply { if (url.isNotEmpty()) put("browser_url", url) }
                    .put("note", "The task is paused and this run ends now. It resumes when the user answers.").toString(),
                    interrupt = true)
            }
            else -> error("Unsupported task capability ${tool.name}")
        }
    }

    private suspend fun write(context: Context, tool: String, scope: OrbitTaskExecution.Scope,
        name: String, content: String): ToolResult {
        require(name.isNotEmpty()) { "name is required: a filename such as report.md" }
        require(name.length <= 80) { "name has ${name.length} characters; at most 80" }
        require(name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "name must be a plain filename without folders or control characters, such as report.md"
        }
        require(content.length <= 200_000) { "content has ${content.length} characters; at most 200000. Split it into several files" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = sha(bytes)
        val operationHash = sha("$name\u0000$digest".toByteArray())
        val id = "${scope.run.id}:$operationHash"
        val versionId = java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString()
        val relativePath = "artifacts/${scope.task.id}/$versionId/$name"
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val now = System.currentTimeMillis()
        val receipt = checkNotNull(dao.beginAction(scope.run, OrbitActionEntity(
            id, scope.task.id, scope.run.id, scope.run.generation, tool, operationHash,
            "PREPARED", "Save $name\n$relativePath", now, now,
        ), OrbitTaskRunner.bootId(context), now, SystemClock.elapsedRealtime())) {
            "An action is unfinished, or this execution is no longer active"
        }
        fun receiptJson(path: String, already: Boolean) = org.json.JSONObject().put("saved", true).put("already_saved", already)
            .put("name", name).put("version_id", versionId).put("path", path).put("chars", content.length)
            .put("lines", if (content.isEmpty()) 0 else content.count { it == '\n' } + 1).put("bytes", bytes.size)
            .put("sha256", digest)
            .apply { if (already) put("note", "The same name and content were already saved in this run; nothing was written again.") }
            .toString()
        if (receipt.state in setOf("CONFIRMED", "VERIFIED")) return success(tool, receiptJson(relativePath, already = true))
        try {
            val path = withContext(Dispatchers.IO) {
                val orbitDir = OrbitPreferences.getInstance(context).orbitDir(scope.task.orbitId).canonicalFile
                val root = File(orbitDir, "artifacts/${scope.task.id}/$versionId").canonicalFile
                require(root.toPath().startsWith(orbitDir.toPath())) { "Artifact folder is outside this Orbit" }
                check(root.mkdirs() || root.isDirectory)
                val target = File(root, name).canonicalFile
                require(target.parentFile == root) { "Artifact is outside its task folder" }
                OrbitExecutionGate.serialized {
                    OrbitTaskExecution.resolve(context, scope.binding.chatId)
                    val atomic = AtomicFile(target)
                    val output = atomic.startWrite()
                    try {
                        output.write(bytes)
                        atomic.finishWrite(output)
                    } catch (error: Throwable) {
                        atomic.failWrite(output)
                        throw error
                    }
                }
                check(sha(target.readBytes()) == digest) { "Saved artifact could not be verified" }
                target.absolutePath
            }
            withContext(NonCancellable) {
                val original = scope.task.sourceArtifactVersionId.takeIf { it.isNotEmpty() }?.let { dao.artifact(it) }
                val artifactId = original?.takeIf { it.name == name }?.artifactId
                    ?: java.util.UUID.nameUUIDFromBytes("${scope.task.id}:$name".toByteArray()).toString()
                check(dao.confirmArtifact(OrbitArtifactVersionEntity(versionId, artifactId, scope.task.orbitId,
                    scope.task.id, scope.run.id, id, name, relativePath, digest, bytes.size.toLong(),
                    System.currentTimeMillis()), scope.run.generation, "Saved $name\n$path\nSHA-256: $digest"))
            }
            return success(tool, receiptJson(relativePath, already = false))
        } catch (error: Throwable) {
            withContext(NonCancellable) { dao.uncertainAction(id, scope.run.generation, System.currentTimeMillis()) }
            if (error is CancellationException) throw error
            return ToolResult(tool, false, StringResultData(""),
                error = "The write outcome needs verification. The task is paused; do not repeat it.", interruptTurn = true)
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun success(name: String, text: String, interrupt: Boolean = false) =
        ToolResult(name, true, StringResultData(text), interruptTurn = interrupt)
}
