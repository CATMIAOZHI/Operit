package com.ai.assistance.operit.features.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.util.OperitPaths
import java.io.ByteArrayOutputStream
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grok Imagine with the app's Grok account login: the same OAuth token the Grok account models
 * use, so no xAI API key is needed. The token stays in the host; the package only gets file paths.
 * Requests follow xAI's own Grok Build image tools (`/images/generations`, `/images/edits`).
 */
object GrokDrawBridge {
    private val ENDPOINTS = listOf("https://api.x.ai/v1", "https://cli-chat-proxy.grok.com/v1")
    private const val DEFAULT_MODEL = "grok-imagine-image-2.0"
    private val MODEL_PATTERN = Regex("""grok-imagine-[a-z0-9.\-]+""")
    internal val ASPECT_RATIOS = setOf(
        "auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2",
        "19.5:9", "9:19.5", "20:9", "9:20",
    )
    private const val MAX_IMAGES = 5
    private const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    /** Imagine rejects larger reference images with HTTP 400. */
    private const val MAX_REF_BYTES = 400 * 1024
    private const val MAX_REF_SIDE = 768
    private const val MAX_OUTPUT_BYTES = 30 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = MAX_OUTPUT_BYTES * 4L / 3 + 64 * 1024
    private const val ERROR_BYTES = 16L * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Imagine buffers the whole image before answering, which can take minutes.
        .readTimeout(4, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun execute(context: Context, parametersJson: String): String {
        val params = JSONObject(parametersJson)
        val prompt = params.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "请提供绘图提示词 prompt" }
        require(prompt.length <= 8_000) { "绘图提示词过长（最多 8000 字）" }
        val editPath = params.optString("image_path").trim()
        val referencePaths = params.optJSONArray("referenced_image_paths")?.let { paths ->
            (0 until paths.length()).map { index ->
                val path = paths.opt(index)
                require(path is String && path.isNotBlank()) { "referenced_image_paths 里每一项都要是非空的图片路径" }
                path.trim()
            }
        } ?: run {
            require(!params.has("referenced_image_paths") || params.isNull("referenced_image_paths")) {
                "referenced_image_paths 必须是路径数组"
            }
            emptyList()
        }
        val inputPaths = (if (editPath.isNotEmpty()) listOf(editPath) else emptyList()) + referencePaths
        require(inputPaths.size <= MAX_IMAGES) { "输入图片最多 $MAX_IMAGES 张（含要修改的图）" }
        val aspectRatio = params.optString("aspect_ratio").trim().ifBlank { "auto" }
        require(aspectRatio in ASPECT_RATIOS) { "aspect_ratio 不支持 $aspectRatio，可用：${ASPECT_RATIOS.joinToString()}" }
        val model = params.optString("model").trim().ifBlank { DEFAULT_MODEL }
        require(MODEL_PATTERN.matches(model)) { "model 必须是 grok-imagine 开头的图片模型，比如 $DEFAULT_MODEL" }

        val manager = ProviderAccountManager.get(context, AccountProvider.GROK)
        require(manager.account.value != null) {
            "还没登录 Grok 账号。请先在模型设置里登录 Grok（Orbit 里可用 orbit_vault request_account_login provider=grok）"
        }
        val roots = CodexDrawBridge.inputImageRoots(context) + listOf(outputDir())
        val images = withContext(Dispatchers.IO) { inputPaths.map { loadReference(it, roots) } }

        val payload = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("n", 1)
            .put("resolution", "1k")
            .put("response_format", "b64_json")
        // One input image goes in "image" and keeps its own shape; several go in "images".
        when (images.size) {
            0 -> payload.put("aspect_ratio", aspectRatio)
            1 -> payload.put("image", JSONObject().put("url", images[0]))
            else -> payload.put("images", JSONArray().apply { images.forEach { put(JSONObject().put("url", it)) } })
                .put("aspect_ratio", aspectRatio)
        }
        val path = if (images.isEmpty()) "/images/generations" else "/images/edits"
        val token = try {
            manager.validAccount().accessToken
        } catch (error: com.ai.assistance.operit.data.api.AccountHttpException) {
            // The token server only answers 400/401 when the saved login itself was refused.
            throw IOException(
                if (error.status in setOf(400, 401)) "Grok 登录已失效，请重新登录 Grok 账号"
                else "刷新 Grok 登录时服务器出错（HTTP ${error.status}），请稍后再试，不用重新登录",
                error,
            )
        } catch (error: IOException) {
            throw IOException("连不上 Grok 登录服务器，请检查网络后稍后再试，不用重新登录", error)
        }

        // The account token is accepted on the public API; the CLI proxy is the fallback for
        // accounts whose token only works there.
        var last: ImageResponse? = null
        for (base in ENDPOINTS) {
            val response = request(base + path, token, payload, viaProxy = base != ENDPOINTS.first())
            last = response
            if (response.status in 200..299 || response.status !in setOf(401, 403, 404, 405)) break
        }
        val result = checkNotNull(last)
        if (result.status !in 200..299) throw IOException(failure(result))
        val encoded = result.imageBase64 ?: throw IOException("Grok 没有返回图片，请换个说法再试")
        currentCoroutineContext().ensureActive()
        val bytes = decode(encoded)
        val file = save(bytes, params.optString("file_name"))
        val uri = Uri.fromFile(file).toString().replace("(", "%28").replace(")", "%29")
        return JSONObject()
            .put("success", true)
            .put("file_path", file.absolutePath)
            .put("file_uri", uri)
            .put("model", model)
            .put("markdown", "![Grok 生成的图片]($uri)")
            .put("message", "图片已保存到 ${file.absolutePath}")
            .toString()
    }

    private fun failure(response: ImageResponse): String {
        val detail = response.error?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        val hint = when (response.status) {
            401 -> "Grok 登录已失效，请重新登录 Grok 账号"
            403 -> "这个 Grok 账号没有生图权限（Grok 生图需要 SuperGrok 等付费订阅，免费版和 X Basic 不行）"
            429 -> "Grok 生图额度用完或请求太频繁，请稍后再试"
            400 -> "Grok 拒绝了这次请求，可能是提示词或参考图不符合要求"
            else -> "Grok 生图失败"
        }
        return "$hint（HTTP ${response.status}）$detail"
    }

    private fun outputDir() = File(OperitPaths.pluginConfigDir("draw"), "grok_draw/draws")

    private suspend fun save(bytes: ByteArray, requestedName: String): File = withContext(Dispatchers.IO) {
        val dir = outputDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建绘图目录")
        val base = requestedName.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim('.', ' ')
            .take(64)
            .ifBlank { "grok_image" }
        val file = File(dir, "${base}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${extension(bytes)}")
        val temporary = File.createTempFile(".grok_draw_", ".tmp", dir)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) throw IOException("无法保存生成的图片")
        } finally {
            temporary.delete()
        }
        file
    }

    /** A reference image as a data URL, shrunk to what Imagine accepts. */
    private fun loadReference(path: String, roots: List<File>): String {
        val local = if (path.startsWith("file://")) Uri.parse(path).path.orEmpty() else path
        val file = CodexDrawBridge.resolveScriptImagePath(local, roots)
        require(file.length() in 1..MAX_INPUT_BYTES.toLong()) { "参考图必须小于 20 MB：$path" }
        val raw = file.readBytes()
        val kind = runCatching { extension(raw) }.getOrNull()
        if (raw.size <= MAX_REF_BYTES && (kind == "png" || kind == "jpg")) {
            return dataUrl(raw, if (kind == "png") "image/png" else "image/jpeg")
        }
        return dataUrl(shrink(raw, path), "image/jpeg")
    }

    internal fun shrink(raw: ByteArray, label: String): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "读不出这张参考图：$label（支持 PNG、JPEG、WebP）" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_REF_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("读不出这张参考图：$label")
        val scale = minOf(1f, MAX_REF_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            for (quality in listOf(80, 65, 50, 35)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_REF_BYTES) return out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        throw IllegalArgumentException("参考图压缩后仍然太大：$label")
    }

    private fun dataUrl(bytes: ByteArray, mime: String) = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

    private fun decode(value: String): ByteArray {
        require(value.length <= MAX_OUTPUT_BYTES * 4 / 3 + 16) { "生成图片超过 30 MB" }
        val bytes = try {
            Base64.decode(value.substringAfter("base64,", value), Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw IOException("Grok 返回的图片编码无效")
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_OUTPUT_BYTES) { "生成图片大小无效" }
        extension(bytes)
        return bytes
    }

    private fun extension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        ) -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> throw IOException("Grok 返回的不是受支持的图片格式")
    }

    private data class ImageResponse(val status: Int, val imageBase64: String?, val error: String?)

    private suspend fun request(url: String, token: String, payload: JSONObject, viaProxy: Boolean): ImageResponse {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("x-grok-session-id", UUID.randomUUID().toString())
        ProviderAccountManager.GROK_HEADERS.forEach { (key, value) ->
            // The token-auth headers are only for the CLI proxy.
            if (viaProxy || key.lowercase() !in setOf("x-xai-token-auth", "x-authenticateresponse")) builder.header(key, value)
        }
        builder.header("Authorization", "Bearer $token")
        val call = client.newCall(builder.build())
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("Grok 返回空响应")
                    if (!response.isSuccessful) {
                        source.request(ERROR_BYTES)
                        val text = source.buffer.readUtf8(minOf(source.buffer.size, ERROR_BYTES))
                        return@use ImageResponse(response.code, null, errorText(text))
                    }
                    // A 30 MB image is about 40 MB of base64; anything far beyond that is not an image.
                    if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Grok 返回的内容过大")
                    val text = source.readUtf8()
                    val first = try { JSONObject(text).optJSONArray("data")?.optJSONObject(0) } catch (_: Exception) { null }
                    ImageResponse(response.code, first?.optString("b64_json")?.takeIf { it.isNotBlank() }, null)
                }
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Grok 生图请求超时，请稍后重试", error)
        } finally {
            watcher.cancel()
        }
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
