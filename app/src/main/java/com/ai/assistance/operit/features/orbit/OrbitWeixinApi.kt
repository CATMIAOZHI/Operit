package com.ai.assistance.operit.features.orbit

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * The WeChat ClawBot (iLink) protocol, as Tencent publishes it in openclaw-weixin (MIT): QR login,
 * the getupdates long poll, sendmessage, and the encrypted CDN for pictures and files. Plain HTTP
 * and JSON; no other part of WeChat is reachable through it.
 */
internal object OrbitWeixinApi {
    const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
    /** The protocol version this client follows (openclaw-weixin 2.4.10). */
    private const val CHANNEL_VERSION = "2.4.10"
    private const val CLIENT_VERSION = (2 shl 16) or (4 shl 8) or 10
    private const val BOT_TYPE = "3"
    const val LONG_POLL_MS = 35_000L
    /** getupdates/sendmessage answer with this when the login no longer works. */
    const val STALE_TOKEN = -14

    const val ITEM_TEXT = 1
    const val ITEM_IMAGE = 2
    const val ITEM_VOICE = 3
    const val ITEM_FILE = 4
    const val ITEM_VIDEO = 5
    const val MESSAGE_FROM_USER = 1
    private const val MESSAGE_FROM_BOT = 2
    private const val STATE_FINISH = 2
    const val UPLOAD_IMAGE = 1
    const val UPLOAD_FILE = 3

    private val json = "application/json".toMediaType()
    private val octets = "application/octet-stream".toMediaType()
    private val random = SecureRandom()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(LONG_POLL_MS + 15_000, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    class ApiException(message: String, val code: Int? = null) : IOException(message)

    data class Qr(val qrcode: String, val url: String)
    data class QrStatus(
        val status: String,
        val token: String = "",
        val botId: String = "",
        val userId: String = "",
        val baseUrl: String = "",
        val redirectHost: String = "",
    )

    data class Updates(val messages: List<JSONObject>, val buf: String?, val nextTimeoutMs: Long?)

    private fun baseInfo() = JSONObject().put("channel_version", CHANNEL_VERSION).put("bot_agent", "Operit")

    private fun randomUin(): String {
        val n = random.nextInt().toLong() and 0xffffffffL
        return android.util.Base64.encodeToString(n.toString().toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun Request.Builder.common() = header("iLink-App-Id", "bot")
        .header("iLink-App-ClientVersion", CLIENT_VERSION.toString())

    private fun url(base: String, endpoint: String) = (base.trimEnd('/') + "/" + endpoint).toHttpUrl()

    /**
     * Runs the call and [block] over its response on an IO thread. Cancelling the coroutine cancels
     * the call at any point, also while the body is still being read.
     */
    private suspend fun <T> Call.fetch(block: (Response) -> T): T = coroutineScope {
        val call = this@fetch
        // Started in place, so the cancel hook is in place before execute() can block.
        val watcher = launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } finally {
            watcher.cancel()
        }
    }

    private fun timed(ms: Long): OkHttpClient = client.newBuilder().callTimeout(ms, TimeUnit.MILLISECONDS).build()

    private suspend fun post(base: String, endpoint: String, token: String?, body: JSONObject, timeoutMs: Long): JSONObject {
        body.put("base_info", baseInfo())
        val request = Request.Builder().url(url(base, endpoint)).common()
            .header("AuthorizationType", "ilink_bot_token")
            .header("X-WECHAT-UIN", randomUin())
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .post(body.toString().toRequestBody(json))
            .build()
        return timed(timeoutMs).newCall(request).fetch { read(it, endpoint) }
    }

    private fun read(response: Response, label: String): JSONObject {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException("$label HTTP ${response.code}", response.code)
        return runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrElse { throw ApiException("$label returned no JSON") }
    }

    /** Non-zero ret/errcode as an error; [STALE_TOKEN] in either field wins, so expiry is never missed. */
    private fun check(o: JSONObject, label: String): JSONObject {
        val ret = o.optInt("ret", 0)
        val err = o.optInt("errcode", 0)
        if (ret != 0 || err != 0) {
            val code = when {
                ret == STALE_TOKEN || err == STALE_TOKEN -> STALE_TOKEN
                ret != 0 -> ret
                else -> err
            }
            throw ApiException("$label failed ($code) ${o.optString("errmsg").take(120)}".trim(), code)
        }
        return o
    }

    suspend fun qrCode(): Qr {
        val o = post(DEFAULT_BASE_URL, "ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE", null,
            JSONObject().put("local_token_list", JSONArray()), 15_000)
        val qrcode = o.optString("qrcode")
        val content = o.optString("qrcode_img_content")
        if (qrcode.isBlank() || content.isBlank()) throw ApiException("No QR code returned")
        return Qr(qrcode, content)
    }

    /** One long poll of the QR status; a timeout or a network error reads as "wait". */
    suspend fun qrStatus(base: String, qrcode: String, verifyCode: String?): QrStatus {
        val target = url(base, "ilink/bot/get_qrcode_status").newBuilder()
            .addQueryParameter("qrcode", qrcode)
            .apply { if (!verifyCode.isNullOrBlank()) addQueryParameter("verify_code", verifyCode) }
            .build()
        val request = Request.Builder().url(target).common().get().build()
        val o = try {
            timed(LONG_POLL_MS + 5_000).newCall(request).fetch { read(it, "get_qrcode_status") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: IOException) {
            return QrStatus("wait")
        }
        return QrStatus(
            status = o.optString("status").ifBlank { "wait" },
            token = o.optString("bot_token"),
            botId = o.optString("ilink_bot_id"),
            userId = o.optString("ilink_user_id"),
            baseUrl = o.optString("baseurl"),
            redirectHost = o.optString("redirect_host"),
        )
    }

    /** Waits up to [timeoutMs] for new messages. A client-side timeout is an empty answer. */
    suspend fun updates(base: String, token: String, buf: String, timeoutMs: Long): Updates {
        val o = try {
            post(base, "ilink/bot/getupdates", token, JSONObject().put("get_updates_buf", buf), timeoutMs + 5_000)
        } catch (e: java.io.InterruptedIOException) {
            return Updates(emptyList(), null, null)
        }
        check(o, "getupdates")
        val msgs = o.optJSONArray("msgs")
        val list = (0 until (msgs?.length() ?: 0)).mapNotNull { msgs?.optJSONObject(it) }
        return Updates(list, o.optString("get_updates_buf").takeIf { it.isNotEmpty() },
            o.optLong("longpolling_timeout_ms", 0).takeIf { it > 0 })
    }

    /**
     * Sends one item to [to]. [item] is a ready item_list entry. [clientId] stays the same for every
     * try of one message, so a retry after a lost answer is the same message to the server.
     */
    suspend fun send(base: String, token: String, to: String, contextToken: String?, item: JSONObject, clientId: String) {
        val msg = JSONObject()
            .put("from_user_id", "")
            .put("to_user_id", to)
            .put("client_id", clientId)
            .put("message_type", MESSAGE_FROM_BOT)
            .put("message_state", STATE_FINISH)
            .put("item_list", JSONArray().put(item))
            .apply { if (!contextToken.isNullOrBlank()) put("context_token", contextToken) }
        check(post(base, "ilink/bot/sendmessage", token, JSONObject().put("msg", msg), 20_000), "sendmessage")
    }

    fun textItem(text: String): JSONObject =
        JSONObject().put("type", ITEM_TEXT).put("text_item", JSONObject().put("text", text))

    /** Lets the server know this client starts or stops polling; failures do not matter. */
    suspend fun notify(base: String, token: String, start: Boolean) {
        runCatching { post(base, if (start) "ilink/bot/msg/notifystart" else "ilink/bot/msg/notifystop", token, JSONObject(), 10_000) }
    }

    // ---- media ----

    private fun cipher(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES")) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** [file] encrypted on the fly while it is sent; never held in memory as a whole. */
    private class EncryptedFileBody(private val file: File, private val key: ByteArray, private val size: Long) : RequestBody() {
        override fun contentType(): MediaType = octets
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            CipherInputStream(file.inputStream().buffered(), cipher(Cipher.ENCRYPT_MODE, key)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            }
        }
    }

    /**
     * Uploads [file] for [to] and gives the item to send: a picture or a file named [name].
     */
    suspend fun uploadItem(base: String, token: String, to: String, file: File, mediaType: Int, name: String): JSONObject {
        val key = ByteArray(16).also(random::nextBytes)
        val filekey = hex(ByteArray(16).also(random::nextBytes))
        val rawSize = file.length()
        // PKCS7 always pads: up to the next whole block, one full block when already aligned.
        val cipherSize = (rawSize / 16 + 1) * 16
        val md5 = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            hex(digest.digest())
        }
        val o = check(post(base, "ilink/bot/getuploadurl", token, JSONObject()
            .put("filekey", filekey).put("media_type", mediaType).put("to_user_id", to)
            .put("rawsize", rawSize).put("rawfilemd5", md5).put("filesize", cipherSize)
            .put("no_need_thumb", true).put("aeskey", hex(key)), 20_000), "getuploadurl")
        val full = o.optString("upload_full_url").trim()
        val param = o.optString("upload_param")
        if (full.isEmpty() && param.isEmpty()) throw ApiException("getuploadurl returned no upload address")
        val target = full.ifEmpty {
            "$CDN_BASE_URL/upload".toHttpUrl().newBuilder()
                .addQueryParameter("encrypted_query_param", param).addQueryParameter("filekey", filekey).build().toString()
        }
        var downloadParam: String? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                val request = Request.Builder().url(target).post(EncryptedFileBody(file, key, cipherSize)).build()
                timed(120_000).newCall(request).fetch { r ->
                    if (r.code in 400..499) throw ApiException("Upload refused (${r.code})", r.code)
                    if (r.code != 200) throw IOException("Upload failed (${r.code})")
                    downloadParam = r.header("x-encrypted-param") ?: throw IOException("Upload returned no download key")
                }
                break
            } catch (e: ApiException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        val download = downloadParam ?: throw (lastError ?: IOException("Upload failed"))
        val media = JSONObject().put("encrypt_query_param", download)
            .put("aes_key", android.util.Base64.encodeToString(hex(key).toByteArray(), android.util.Base64.NO_WRAP))
            .put("encrypt_type", 1)
        return if (mediaType == UPLOAD_IMAGE) {
            JSONObject().put("type", ITEM_IMAGE).put("image_item", JSONObject().put("media", media).put("mid_size", cipherSize))
        } else {
            JSONObject().put("type", ITEM_FILE).put("file_item",
                JSONObject().put("media", media).put("file_name", name).put("len", rawSize.toString()))
        }
    }

    /** The AES key of an inbound media item: raw 16 bytes, or 32 hex characters, base64-encoded. */
    private fun keyOf(item: JSONObject, media: JSONObject): ByteArray? {
        item.optString("aeskey").takeIf { it.length == 32 }?.let { return runCatching { unhex(it) }.getOrNull() }
        val raw = media.optString("aes_key").takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size == 16) return bytes
        val text = String(bytes)
        return if (text.length == 32 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) unhex(text) else null
    }

    /**
     * Downloads one inbound picture, file or video of up to [maxBytes] into [dest], decrypting while
     * it streams. [dest] is removed again when anything goes wrong.
     */
    suspend fun download(item: JSONObject, media: JSONObject, maxBytes: Long, dest: File) {
        val full = media.optString("full_url").trim()
        val param = media.optString("encrypt_query_param")
        val target = full.ifEmpty {
            if (param.isEmpty()) throw ApiException("The message has no download address")
            "$CDN_BASE_URL/download".toHttpUrl().newBuilder().addQueryParameter("encrypted_query_param", param).build().toString()
        }
        val key = keyOf(item, media)
        try {
            timed(120_000).newCall(Request.Builder().url(target).get().build()).fetch { r ->
                if (!r.isSuccessful) throw ApiException("Download failed (${r.code})", r.code)
                val body = r.body ?: throw IOException("Download returned nothing")
                if (body.contentLength() > maxBytes + 16) throw ApiException("The file is too large")
                val counted = LimitedInput(body.byteStream(), maxBytes + 16)
                val plain: InputStream = if (key == null) counted else CipherInputStream(counted, cipher(Cipher.DECRYPT_MODE, key))
                plain.use { input -> dest.outputStream().buffered().use { input.copyTo(it, 64 * 1024) } }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    /** Stops reading past [limit] bytes, so an oversized download fails instead of filling the disk. */
    private class LimitedInput(private val input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
        private var total = 0L
        private fun count(n: Int): Int {
            if (n > 0) {
                total += n
                if (total > limit) throw ApiException("The file is too large")
            }
            return n
        }
        override fun read(): Int = input.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(input.read(b, off, len))
    }
}
