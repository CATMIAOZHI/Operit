package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.voice.Base64FileBody
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xiaomi MiMo speech recognition: records like [OpenAISttProvider], then sends the WAV as a
 * Base64 data URL inside a Chat Completions request and reads the transcript from the reply.
 */
class MimoSttProvider(
    context: Context,
    endpointUrl: String,
    apiKey: String,
    model: String,
) : OpenAISttProvider(
    context,
    endpointUrl.ifBlank { DEFAULT_ENDPOINT_URL },
    apiKey,
    model.ifBlank { DEFAULT_MODEL_NAME },
) {
    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://api.xiaomimimo.com/v1/chat/completions"
        const val DEFAULT_MODEL_NAME = "mimo-v2.5-asr"
        /** MiMo takes at most 10 MB of Base64, about 7.5 MB of WAV (roughly four minutes). */
        private const val MAX_WAV_BYTES = 7L * 1024L * 1024L
        /**
         * The request around the recording, split at the audio's fixed place so nothing a user
         * types (a model name, say) can be taken for it. Same JSON as [requestJson].
         */
        internal fun requestParts(model: String, language: String?): Pair<String, String> {
            val head = "{\"model\":" + JSONObject.quote(model) +
                ",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"data:audio/wav;base64,"
            val lang = JSONObject.quote(language?.takeIf { it == "zh" || it == "en" } ?: "auto")
            val tail = "\"}}]}],\"asr_options\":{\"language\":$lang},\"stream\":false}"
            return head to tail
        }

        internal fun requestJson(model: String, wavBase64: String, language: String?): JSONObject {
            val audio = JSONObject().put("type", "input_audio")
                .put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$wavBase64"))
            return JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))))
                .put("asr_options", JSONObject().put("language", language?.takeIf { it == "zh" || it == "en" } ?: "auto"))
                .put("stream", false)
        }

        internal fun transcript(body: String): String {
            val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return message.optString("content").trim()
        }
    }

    override val maxFileBytes: Long get() = MAX_WAV_BYTES

    override fun acceptsEndpoint(url: String): Boolean = url.contains("/chat/completions")

    override fun missingKeyMessage(): String = context.getString(R.string.mimo_tts_error_api_key_not_set)

    override fun badEndpointMessage(): String = context.getString(R.string.mimo_stt_error_url_invalid_path)

    // The `api-key` header must only reach the address set here, so redirects are not followed.
    override val followRedirects: Boolean get() = false

    override fun transcribeWavFile(file: File, languageCode: String?, epoch: Long): String {
        // The recording is streamed into the JSON instead of being held as one big string.
        val (head, tail) = requestParts(model, mapLanguage(languageCode))
        val body = Base64FileBody(head, file, tail, "application/json".toMediaType())
        val request = Request.Builder()
            .url(endpointUrl)
            .post(body)
            // MiMo's chat and read-aloud APIs take the key as `api-key`; Bearer is sent as well.
            .addHeader("api-key", apiKey.trim())
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .build()
        val call = httpClient.newCall(request)
        requestEpoch.attach(epoch, call)
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw IOException(context.getString(R.string.mimo_stt_error_request_failed), e)
            }
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.w("MimoSttProvider", "MiMo recognition failed (${resp.code}): ${text.take(300)}")
                    throw IOException(context.getString(when (resp.code) {
                        401, 403 -> R.string.mimo_stt_error_bad_key
                        429 -> R.string.mimo_stt_error_busy
                        else -> R.string.mimo_stt_error_failed
                    }, resp.code))
                }
                return transcript(text)
            }
        } finally {
            // Lets the finished call, and the request it holds, be freed straight away.
            requestEpoch.detach(call)
        }
    }
}
