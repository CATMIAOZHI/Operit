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
        check(scope.task.kind == "ASSIST") { "This task is read-only" }
        val args = tool.parameters.associate { it.name to it.value }
        return when (tool.name) {
            READ_WEB -> {
                val source = OrbitTaskRunner.readUrl(args["url"] ?: error("Missing url"))
                OrbitTaskExecution.resolve(context, caller)
                success(tool.name, source.take(20_000) + if (source.length > 20_000) "\n[Excerpt truncated]" else "")
            }
            WRITE -> write(context, tool.name, scope, args["name"].orEmpty(), args["content"] ?: error("Missing content"))
            WAIT -> {
                check(caller == scope.binding.rootChatId) { "Tell the root agent which user input is needed" }
                val reason = args["reason"]?.trim().orEmpty()
                require(reason.length in 1..2000)
                val url = args["browser_url"].orEmpty()
                if (url.isNotEmpty()) OrbitTaskRepository.validateSource(url)
                check(AppDatabase.getDatabase(context).orbitTaskDao().waitForUser(
                    scope.run, reason, OrbitTaskRunner.bootId(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(), url))
                success(tool.name, "Waiting for the user", interrupt = true)
            }
            else -> error("Unsupported task capability")
        }
    }

    private suspend fun write(context: Context, tool: String, scope: OrbitTaskExecution.Scope,
        name: String, content: String): ToolResult {
        require(name.length in 1..80 && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Use a filename without a directory" }
        require(content.length <= 200_000)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = sha(bytes)
        val operationHash = sha("$name\u0000$digest".toByteArray())
        val id = "${scope.run.id}:$operationHash"
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val now = System.currentTimeMillis()
        val receipt = checkNotNull(dao.beginAction(scope.run, OrbitActionEntity(
            id, scope.task.id, scope.run.id, scope.run.generation, tool, operationHash,
            "PREPARED", "Save $name", now, now,
        ), OrbitTaskRunner.bootId(context), now, SystemClock.elapsedRealtime())) {
            "An action is unfinished, or this execution is no longer active"
        }
        if (receipt.state in setOf("CONFIRMED", "VERIFIED")) return success(tool, receipt.summary)
        try {
            val path = withContext(Dispatchers.IO) {
                val orbitDir = OrbitPreferences.getInstance(context).orbitDir(scope.task.orbitId).canonicalFile
                val root = File(orbitDir, "artifacts/${scope.task.id}").canonicalFile
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
                check(dao.confirmAction(id, scope.run.generation, "Saved $name\n$path\nSHA-256: $digest",
                    System.currentTimeMillis()))
            }
            return success(tool, "Saved $name\n$path")
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
