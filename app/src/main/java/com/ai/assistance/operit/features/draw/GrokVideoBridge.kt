package com.ai.assistance.operit.features.draw

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ai.assistance.operit.data.api.AccountHttpException
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.GrokClientIdentity
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.util.ImageSourcePathPolicy
import com.ai.assistance.operit.util.OperitPaths
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grok Imagine video with the app's Grok account login, following the xAI async videos API the
 * Hermes agent uses: submit to `/videos/{generations|edits|extensions}`, poll `/videos/{id}` until
 * a final status, then download the MP4. The token stays in the host.
 */
object GrokVideoBridge {
    private val ENDPOINTS = listOf("https://api.x.ai/v1", "https://cli-chat-proxy.grok.com/v1")
    internal const val TEXT_MODEL = "grok-imagine-video"
    internal const val IMAGE_MODEL = "grok-imagine-video-1.5"
    private val MODEL_PATTERN = Regex("""grok-imagine-video[a-z0-9.\-]*""")
    internal val ASPECT_RATIOS = setOf("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3")
    internal val RESOLUTIONS = setOf("480p", "720p")
    private const val MAX_REFERENCES = 7
    private const val MAX_DURATION = 15
    private const val MAX_REFERENCE_DURATION = 10
    private const val MAX_EXTEND_DURATION = 10
    private const val DEFAULT_DURATION = 8
    private const val DEFAULT_EXTEND_DURATION = 6
    /** A local source video is sent inline as base64, so it has to stay small. */
    private const val MAX_SOURCE_VIDEO_BYTES = 20L * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 300L * 1024 * 1024
    private const val POLL_INTERVAL_MS = 5_000L
    private const val MAX_WAIT_MS = 10 * 60_000L
    private const val MAX_POLL_FAILURES = 3
    private const val ERROR_BYTES = 16L * 1024
    private val TERMINAL = setOf("done", "failed", "error", "expired", "cancelled")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    /** [mode] is "generate", "edit" or "extend". */
    suspend fun execute(context: Context, mode: String, parametersJson: String): String {
        val params = JSONObject(parametersJson)
        val prompt = params.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "请提供视频提示词 prompt" }
        require(prompt.length <= 8_000) { "视频提示词过长（最多 8000 字）" }
        val explicitModel = params.optString("model").trim()
        require(explicitModel.isEmpty() || MODEL_PATTERN.matches(explicitModel)) {
            "model 必须是 grok-imagine-video 开头的视频模型，比如 $TEXT_MODEL 或 $IMAGE_MODEL"
        }
        val manager = ProviderAccountManager.get(context, AccountProvider.GROK)
        require(manager.account.value != null) {
            "还没登录 Grok 账号。请先在模型设置里登录 Grok（Orbit 里可用 orbit_vault request_account_login provider=grok）"
        }
        val roots = CodexDrawBridge.inputImageRoots(context) + listOf(GrokDrawBridge.outputDir(), outputDir())
        // Build and submit in a separate call so the (possibly large base64) payload is released before polling.
        val (task, requestModel, requestDuration) = submit(manager) {
            when (mode) {
                "generate" -> generation(params, prompt, explicitModel, roots)
                "edit", "extend" -> mutation(params, prompt, explicitModel, mode, roots)
                else -> throw IllegalArgumentException("未知的视频操作：$mode")
            }
        }
        val done = poll(manager, task)
        val video = done.optJSONObject("video")
        val remote = video?.optString("url")?.trim().orEmpty()
            .ifEmpty { video?.optJSONObject("file_output")?.optString("public_url")?.trim().orEmpty() }
        require(remote.startsWith("https://")) { "Grok 视频已完成，但没有返回视频地址" }
        val file = download(remote, manager, params.optString("file_name"))
        val uri = Uri.fromFile(file).toString().replace("(", "%28").replace(")", "%29")
        val duration = video?.optInt("duration", 0)?.takeIf { it > 0 } ?: requestDuration
        val model = done.optString("model").takeIf { it.isNotBlank() } ?: requestModel
        return JSONObject()
            .put("success", true)
            .put("file_path", file.absolutePath)
            .put("file_uri", uri)
            .put("video_url", remote)
            .put("request_id", task.id)
            .put("model", model)
            .put("operation", mode)
            .put("duration_seconds", duration ?: JSONObject.NULL)
            .put("markdown", "![生成的视频]($uri)")
            .put(
                "message",
                "视频已保存到 ${file.absolutePath}。video_url 是 Grok 的临时地址，过一段时间会失效；" +
                    "要接着改或续写这段视频，优先把 file_path 传给 edit_video 或 extend_video 的 video。",
            )
            .toString()
    }

    private suspend fun submit(
        manager: ProviderAccountManager,
        build: suspend () -> VideoRequest,
    ): Triple<Submitted, String, Int?> {
        val request = build()
        val token = token(manager)
        var lastFailure: String? = null
        for (base in ENDPOINTS) {
            val (status, body) = send(post(base + "/videos/" + request.endpoint, token, request.payload, base != ENDPOINTS.first()))
            if (status in 200..299) {
                val id = runCatching { JSONObject(body).optString("request_id") }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Grok 没有返回视频任务编号，请稍后再试")
                return Triple(Submitted(base, id), request.model, request.duration)
            }
            lastFailure = failure(status, errorText(body))
            if (status !in setOf(401, 403, 404, 405)) break
        }
        throw IOException(lastFailure ?: "Grok 视频提交失败")
    }

    private class VideoRequest(val endpoint: String, val payload: JSONObject, val model: String, val duration: Int?)

    private class Submitted(val base: String, val id: String)

    private suspend fun generation(params: JSONObject, prompt: String, explicitModel: String, roots: List<File>): VideoRequest {
        val imagePath = params.optString("image_path").trim()
        val references = stringList(params, "referenced_image_paths")
        require(imagePath.isEmpty() || references.isEmpty()) {
            "image_path（让这张图动起来）和 referenced_image_paths（参考图）不能同时用，请二选一"
        }
        require(references.size <= MAX_REFERENCES) { "参考图最多 $MAX_REFERENCES 张" }
        val model = when {
            references.isNotEmpty() -> {
                require(explicitModel.isEmpty() || explicitModel == TEXT_MODEL) { "用参考图生成视频只能用 $TEXT_MODEL" }
                TEXT_MODEL
            }
            imagePath.isNotEmpty() -> explicitModel.ifEmpty { IMAGE_MODEL }
            else -> {
                require(!explicitModel.startsWith(IMAGE_MODEL)) { "$explicitModel 只能图生视频；纯文字生成请用 $TEXT_MODEL，或传 image_path" }
                explicitModel.ifEmpty { TEXT_MODEL }
            }
        }
        val maxDuration = if (references.isNotEmpty()) MAX_REFERENCE_DURATION else MAX_DURATION
        val duration = durationOf(params, DEFAULT_DURATION, maxDuration)
        val aspectRatio = params.optString("aspect_ratio").trim().ifBlank { "16:9" }
        require(aspectRatio in ASPECT_RATIOS) { "aspect_ratio 不支持 $aspectRatio，可用：${ASPECT_RATIOS.joinToString()}" }
        val resolution = params.optString("resolution").trim().lowercase().ifBlank { "720p" }
        require(resolution in RESOLUTIONS) { "resolution 只能是 480p 或 720p" }
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("duration", duration)
            .put("aspect_ratio", aspectRatio)
            .put("resolution", resolution)
        withContext(Dispatchers.IO) {
            if (imagePath.isNotEmpty()) {
                payload.put("image", JSONObject().put("url", GrokDrawBridge.loadReference(imagePath, roots)))
            }
            if (references.isNotEmpty()) {
                payload.put("reference_images", JSONArray().apply {
                    references.forEach { put(JSONObject().put("url", GrokDrawBridge.loadReference(it, roots))) }
                })
            }
        }
        return VideoRequest("generations", payload, model, duration)
    }

    private suspend fun mutation(params: JSONObject, prompt: String, explicitModel: String, mode: String, roots: List<File>): VideoRequest {
        val source = params.optString("video").trim()
        require(source.isNotEmpty()) { "请提供 video：要${if (mode == "edit") "修改" else "续写"}的视频本地路径，或上次结果里的 video_url" }
        require(!explicitModel.startsWith(IMAGE_MODEL)) { "$explicitModel 只能图生视频；修改或续写视频请用 $TEXT_MODEL" }
        val model = explicitModel.ifEmpty { TEXT_MODEL }
        val url = withContext(Dispatchers.IO) { videoInput(source, roots) }
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("video", JSONObject().put("url", url))
        return if (mode == "extend") {
            val duration = durationOf(params, DEFAULT_EXTEND_DURATION, MAX_EXTEND_DURATION)
            payload.put("duration", duration)
            VideoRequest("extensions", payload, model, duration)
        } else {
            require(!params.has("duration") || params.isNull("duration")) { "edit_video 不接受 duration，修改后的视频沿用原视频长度" }
            VideoRequest("edits", payload, model, null)
        }
    }

    private fun stringList(params: JSONObject, key: String): List<String> {
        if (!params.has(key) || params.isNull(key)) return emptyList()
        val array = params.optJSONArray(key) ?: throw IllegalArgumentException("$key 必须是路径数组")
        return (0 until array.length()).map { index ->
            val value = array.opt(index)
            require(value is String && value.isNotBlank()) { "$key 里每一项都要是非空的图片路径" }
            value.trim()
        }
    }

    private fun durationOf(params: JSONObject, default: Int, max: Int): Int {
        if (!params.has("duration") || params.isNull("duration")) return default
        val raw = params.opt("duration")
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        require(value != null && value == Math.floor(value) && value in 1.0..max.toDouble()) {
            "duration 必须是 1 到 $max 之间的整数秒"
        }
        return value.toInt()
    }

    /** A remote HTTPS video passes through; a local MP4 is sent inline. */
    private fun videoInput(source: String, roots: List<File>): String {
        if (source.startsWith("https://", ignoreCase = true)) return source
        require(!source.startsWith("http://", ignoreCase = true)) { "视频地址必须是 https" }
        val local = if (source.startsWith("file://")) Uri.parse(source).path.orEmpty() else source
        require(File(local).isAbsolute) { "video 必须是视频的绝对路径，或上次结果里的 https video_url" }
        val file = try {
            ImageSourcePathPolicy.resolve(local, roots)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("视频必须位于手机存储或 Operit 允许的目录内：$source")
        } catch (_: IOException) {
            throw IllegalArgumentException("找不到这个视频文件：$source")
        }
        require(file.isFile && file.canRead()) { "无法读取这个视频文件：$source" }
        require(file.length() in 1..MAX_SOURCE_VIDEO_BYTES) { "本地视频必须小于 20 MB：$source" }
        val bytes = file.readBytes()
        require(isMp4(bytes)) { "只支持 MP4 视频：$source" }
        return "data:video/mp4;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun isMp4(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"

    private suspend fun token(manager: ProviderAccountManager): String = try {
        manager.validAccount().accessToken
    } catch (error: AccountHttpException) {
        // The token server only answers 400/401 when the saved login itself was refused.
        throw IOException(
            if (error.status in setOf(400, 401)) "Grok 登录已失效，请重新登录 Grok 账号"
            else "刷新 Grok 登录时服务器出错（HTTP ${error.status}），请稍后再试，不用重新登录",
            error,
        )
    } catch (error: IOException) {
        throw IOException("连不上 Grok 登录服务器，请检查网络后稍后再试，不用重新登录", error)
    }

    /** Waits for the task to reach a final status; returns the finished task body. */
    private suspend fun poll(manager: ProviderAccountManager, task: Submitted): JSONObject =
        withTimeoutOrNull(MAX_WAIT_MS) { pollUntilDone(manager, task) }
            ?: throw IOException("Grok 视频 10 分钟还没生成完，已停止等待（任务编号 ${task.id}），请稍后换个更短的时长再试")

    private suspend fun pollUntilDone(manager: ProviderAccountManager, task: Submitted): JSONObject {
        var failures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val (status, body) = try {
                send(get(task.base + "/videos/" + task.id, token(manager), task.base != ENDPOINTS.first()))
            } catch (error: IOException) {
                // A phone drops the connection now and then; the task keeps running on the server.
                if (++failures >= MAX_POLL_FAILURES) {
                    throw IOException("查询 Grok 视频进度时连续出错，请稍后再试（任务编号 ${task.id}）", error)
                }
                continue
            }
            if (status !in 200..299) {
                if (status >= 500 && ++failures < MAX_POLL_FAILURES) continue
                throw IOException(failure(status, errorText(body)))
            }
            failures = 0
            val json = try { JSONObject(body) } catch (_: Exception) { throw IOException("Grok 返回的视频进度无法识别") }
            val state = json.optString("status").lowercase()
            if (state == "done") return json
            if (state in TERMINAL) {
                val reason = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                val label = when (state) {
                    "expired" -> "Grok 视频任务已过期"
                    "cancelled" -> "Grok 视频任务被取消了"
                    else -> "Grok 视频生成失败"
                }
                throw IOException(label + (reason?.let { "：${it.take(300)}" }.orEmpty()))
            }
        }
    }

    private fun outputDir() = File(OperitPaths.pluginConfigDir("video"), "grok_video/videos")

    private suspend fun download(url: String, manager: ProviderAccountManager, requestedName: String): File {
        val dir = outputDir()
        val base = requestedName.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim('.', ' ')
            .take(64)
            .ifBlank { "grok_video" }
        // xAI's own CDN may want the account token; other hosts never see it.
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        val trusted = host == "x.ai" || host.endsWith(".x.ai") || host == "grok.com" || host.endsWith(".grok.com")
        for (withToken in if (trusted) listOf(false, true) else listOf(false)) {
            val builder = Request.Builder().url(url).get()
            // Generation can outlast the token sent with the task, so ask for a current one.
            if (withToken) builder.header("Authorization", "Bearer ${token(manager)}")
            val result = withCancel(builder.build()) { response ->
                if (response.code in setOf(401, 403) && !withToken && trusted) return@withCancel null
                if (!response.isSuccessful) throw IOException("下载 Grok 视频失败（HTTP ${response.code}），可以稍后再试")
                val body = response.body ?: throw IOException("Grok 视频下载内容为空")
                if (body.contentLength() > MAX_OUTPUT_BYTES) throw IOException("Grok 视频超过 300 MB，已放弃下载")
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建视频目录")
                val temporary = File.createTempFile(".grok_video_", ".tmp", dir)
                try {
                    var total = 0L
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_OUTPUT_BYTES) throw IOException("Grok 视频超过 300 MB，已放弃下载")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val head = ByteArray(12)
                    val headSize = temporary.inputStream().use { it.read(head) }
                    if (headSize < 12 || !isMp4(head)) throw IOException("Grok 返回的不是 MP4 视频")
                    val file = File(dir, "${base}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.mp4")
                    if (!temporary.renameTo(file)) throw IOException("无法保存生成的视频")
                    file
                } finally {
                    temporary.delete()
                }
            }
            if (result != null) return result
        }
        throw IOException("下载 Grok 视频被拒绝，可以稍后再试")
    }

    private fun post(url: String, token: String, payload: JSONObject, viaProxy: Boolean): Request =
        authorized(Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType())), token, viaProxy)
            .header("x-idempotency-key", UUID.randomUUID().toString())
            .build()

    private fun get(url: String, token: String, viaProxy: Boolean): Request =
        authorized(Request.Builder().url(url).get(), token, viaProxy).build()

    private fun authorized(builder: Request.Builder, token: String, viaProxy: Boolean): Request.Builder {
        builder.header("Accept", "application/json").header("x-grok-session-id", UUID.randomUUID().toString())
        GrokClientIdentity.headers(viaProxy).forEach { (key, value) -> builder.header(key, value) }
        return builder.header("Authorization", "Bearer $token")
    }

    /** Status and a bounded body; the body of a success is a small JSON status. */
    private suspend fun send(request: Request): Pair<Int, String> = withCancel(request) { response ->
        val source = response.body?.source() ?: throw IOException("Grok 返回空响应")
        source.request(ERROR_BYTES)
        response.code to source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BYTES))
    }

    /** Runs [request], cancelling the HTTP call with the tool call. */
    private suspend fun <T> withCancel(request: Request, block: (okhttp3.Response) -> T): T {
        val call = client.newCall(request)
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        return try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Grok 视频请求超时，请稍后重试", error)
        } finally {
            watcher.cancel()
        }
    }

    private fun failure(status: Int, detail: String?): String {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        val hint = when (status) {
            401 -> "Grok 登录已失效，请重新登录 Grok 账号"
            403 -> "这个 Grok 账号没有视频生成权限（需要 SuperGrok 或 X Premium+ 等付费订阅）"
            429 -> "Grok 视频额度用完或请求太频繁，请稍后再试"
            400, 422 -> "Grok 拒绝了这次请求，可能是提示词、参数或输入的图片视频不符合要求"
            else -> "Grok 视频请求失败"
        }
        return "$hint（HTTP $status）$suffix"
    }

    /** The server's own message, short and with no token in it. */
    private fun errorText(body: String): String {
        val message = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message")
        } catch (_: Exception) { body }
        return message.replace(Regex("""Bearer\s+\S+"""), "Bearer ***").take(300)
    }
}
