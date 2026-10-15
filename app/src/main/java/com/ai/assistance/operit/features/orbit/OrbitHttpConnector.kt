package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object OrbitHttpConnector {
    const val LIST = "orbit_list_connectors"
    const val CALL = "orbit_call_connector"
    val prompts = listOf(
        ToolPrompt(name = LIST, description = "List this Orbit's user-configured HTTPS POST connections. No credentials are returned."),
        ToolPrompt(name = CALL, description = "Send one JSON request to a user-enabled connection. The host injects its credentials. Subject to normal FAST review. An uncertain outcome pauses this task; never retry it automatically.",
            parametersStructured = listOf(
                ToolParameterSchema("connector_id", description = "ID from orbit_list_connectors", required = true),
                ToolParameterSchema("revision", description = "Exact revision from orbit_list_connectors; stale authorization is refused", required = true),
                ToolParameterSchema("body", description = "JSON object, at most 20000 characters. Do not include credentials.", required = true))))
    internal fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
    /** Also blocks status-driven follow-ups (e.g. 503 Retry-After: 0), not just connection retries. */
    internal fun requestBody(body: String): RequestBody {
        val delegate = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        return object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = delegate.writeTo(sink)
        }
    }
    suspend fun reviewDescription(context: Context, tool: AITool, callerChatId: String?): String {
        val owner = OrbitMemoryScope.owner(context, callerChatId) ?: error("Not an Orbit task")
        val id = tool.parameters.firstOrNull { it.name == "connector_id" }?.value
        val item = OrbitConnectorRepository(context, owner.id).list().singleOrNull { it.id == id }
            ?: error("Connection is unavailable")
        return context.getString(com.ai.assistance.operit.R.string.orbit_connection_action, item.name, item.host, item.purpose)
    }

    suspend fun execute(context: Context, tool: AITool, scope: OrbitTaskExecution.Scope): ToolResult {
        val repository = OrbitConnectorRepository(context, scope.task.orbitId)
        if (tool.name == LIST) {
            val entries = repository.list().filter { it.enabled }.map {
                JSONObject().put("connector_id", it.id).put("revision", it.revision)
                    .put("name", it.name).put("purpose", it.purpose).put("host", it.host)
            }
            return ToolResult(tool.name, true, StringResultData(JSONArray(entries).toString()))
        }
        val args = tool.parameters.associate { it.name to it.value }
        val id = args["connector_id"].orEmpty()
        val revision = args["revision"].orEmpty()
        val body = args["body"].orEmpty()
        require(body.length <= 20_000) { "Request body is too large" }
        require(runCatching { JSONObject(body) }.isSuccess) { "Body must be a JSON object" }
        val hash = MessageDigest.getInstance("SHA-256").digest("$id\u0000$revision\u0000$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val actionId = "${scope.run.id}:http:$hash"
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val response = CompletableDeferred<Int>()
        var call: Call? = null
        var started = false
        try {
            val repeated = OrbitExecutionGate.serialized {
                repository.authorized(id, revision) { secret ->
                    OrbitTaskExecution.resolve(context, scope.binding.chatId)
                    currentCoroutineContext().ensureActive()
                    val now = System.currentTimeMillis()
                    val receipt = checkNotNull(dao.beginAction(scope.run, OrbitActionEntity(
                        actionId, scope.task.id, scope.run.id, scope.run.generation, CALL, hash, "PREPARED",
                        "${secret.description.name} (${secret.description.host})\nOperation: $actionId", now, now),
                        OrbitTaskRunner.bootId(context), now, SystemClock.elapsedRealtime())) {
                        "This task has an unresolved operation or is no longer active"
                    }
                    if (receipt.state in setOf("CONFIRMED", "VERIFIED")) return@authorized receipt.summary
                    started = true
                    val request = Request.Builder().url(secret.url)
                        .post(requestBody(body))
                        .apply { if (secret.bearer.isNotEmpty()) header("Authorization", "Bearer ${secret.bearer}") }
                        .build()
                    call = client().newCall(request)
                    OrbitConnectorRepository.register(actionId, scope.task.orbitId, id, call!!)
                    call!!.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            OrbitConnectorRepository.unregister(actionId, call)
                            response.completeExceptionally(IOException("Connection outcome is unknown"))
                        }
                        override fun onResponse(call: Call, result: Response) {
                            OrbitConnectorRepository.unregister(actionId, call)
                            result.use { response.complete(it.code) }
                        }
                    })
                    null
                }
            }
            if (repeated != null) return ToolResult(tool.name, true, StringResultData(repeated))
            val status = response.await()
            if (status in 200..299 && status != 202) {
                val summary = "Endpoint acknowledged the request (HTTP $status). This confirms reception, not completion of downstream work.\nOperation: $actionId"
                withContext(NonCancellable) { check(dao.confirmAction(actionId, scope.run.generation, summary, System.currentTimeMillis())) }
                return ToolResult(tool.name, true, StringResultData(summary))
            }
            error("Remote outcome requires verification")
        } catch (error: Throwable) {
            call?.cancel()
            call?.let { OrbitConnectorRepository.unregister(actionId, it) }
            if (started) withContext(NonCancellable) {
                dao.uncertainAction(actionId, scope.run.generation, System.currentTimeMillis())
            }
            if (error is CancellationException) throw error
            return ToolResult(tool.name, false, StringResultData(""),
                error = if (started) "Request outcome requires user verification. Do not repeat it. Operation: $actionId"
                    else "Connection is unavailable, changed, or unauthorized. Read its current list before requesting another review.",
                interruptTurn = started)
        } finally { call?.cancel() }
    }
}
