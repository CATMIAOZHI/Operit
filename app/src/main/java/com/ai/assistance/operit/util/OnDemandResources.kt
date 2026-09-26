package com.ai.assistance.operit.util

import android.content.Context
import com.ai.assistance.operit.R
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResourceDownloadState(
    val id: String,
    val name: String,
    val downloaded: Long,
    val total: Long,
    val error: String? = null
)

/** Fixed, checksummed feature resources; no downloads run merely from loading this object. */
object OnDemandResources {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutableDownloads = MutableStateFlow<Map<String, ResourceDownloadState>>(emptyMap())
    val downloads = mutableDownloads.asStateFlow()

    const val SPEECH_DIRECTORY = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
    private const val SPEECH_BASE = "https://huggingface.co/csukuangfj/$SPEECH_DIRECTORY/resolve/05945efc40afe4b572542f01104ca5c413a9f6e1/"
    private val speech = listOf(
        speech("decoder_jit_trace-pnnx.ncnn.bin", 6412296, "dc4df2d8e1ddee1b90ac72a2de982eb1d320ee6c9a70e1dee4d23d9acfc8b978"),
        speech("decoder_jit_trace-pnnx.ncnn.param", 439, "cb88f5894978fd3e85369d2f8ea55621809fceb2b5158243fb0cd025eb4f1aaf"),
        speech("encoder_jit_trace-pnnx.ncnn.bin", 127364056, "4ed65f05b78c0106d3d176018ab01e26a15c200604490d3d49b08cc75a122dd0"),
        speech("encoder_jit_trace-pnnx.ncnn.param", 161888, "97ad0954fb2cb4730f87a7eb66401b024f756752ece246e4b2063f870ebf3e18"),
        speech("joiner_jit_trace-pnnx.ncnn.bin", 7350724, "0e6c4370017394de5d74128756233d2e4451209e63ac2abd525da3b089e8bee1"),
        speech("joiner_jit_trace-pnnx.ncnn.param", 490, "46c339f3869136c2f6d9d9d6983a6cbc2bfbcd0e3dab0f76ae25e9477f00a360"),
        speech("tokens.txt", 56317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3")
    )
    private fun speech(name: String, bytes: Long, sha: String) =
        DownloadResource(name, SPEECH_BASE + name, bytes, sha)

    private val ubuntu = DownloadResource(
        "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        "https://raw.githubusercontent.com/CATMIAOZHI/OperitTerminalCore/cd5d53cb2c99b7913d01c7919e1d93bb55ca76c4/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        64133552,
        "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
    )

    suspend fun ensureSpeech(context: Context): File {
        val directory = File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY)
        speech.forEach { ensure(context, it, File(directory, it.id), R.string.resource_offline_speech) }
        return directory
    }

    suspend fun ensureUbuntu(context: Context, target: File) {
        ensure(context, ubuntu, target, R.string.resource_terminal)
    }

    suspend fun exportTemplate(context: Context, android: Boolean): File {
        val resource = if (android) DownloadResource(
            "android.apk",
            "https://github.com/CATMIAOZHI/OperitResources/releases/download/templates-v1/android.apk",
            48139093,
            "c56b23a841a736e028aeec8bee6b48a086b668143ce103095042e622750b86b4"
        ) else DownloadResource(
            "windows.zip",
            "https://github.com/CATMIAOZHI/OperitResources/releases/download/templates-v1/windows.zip",
            11412717,
            "9c2d3e3b5e862334e24f4008572f239c380a73f23c5940054bf3a5142ca46888"
        )
        val target = File(context.filesDir, "downloaded_resources/templates-v1/${resource.id}")
        ensure(context, resource, target, if (android) R.string.resource_android_export else R.string.resource_windows_export)
        return target
    }

    fun cancel(id: String) { jobs[id]?.cancel() }
    fun dismiss(id: String) { mutableDownloads.update { it - id } }

    private suspend fun ensure(context: Context, resource: DownloadResource, target: File, label: Int) {
        locks.getOrPut(resource.id) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                // A process kill can leave a partial download even if this retry is offline.
                File(target.parentFile, "${target.name}.part").delete()
                if (VerifiedResourceStore.isValid(target, resource)) return@withContext
                coroutineScope {
                    val job = currentCoroutineContext().job
                    jobs[resource.id] = job
                    val state = ResourceDownloadState(resource.id, context.getString(label), 0, resource.bytes)
                    mutableDownloads.update { it + (resource.id to state) }
                    val call = client.newCall(Request.Builder().url(resource.url).build())
                    // Cancelling must also unblock a synchronous socket read.
                    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty response")
                            var lastUpdate = 0L
                            body.byteStream().use { input ->
                                VerifiedResourceStore.write(target, resource, input) { count ->
                                    val now = System.nanoTime()
                                    if (count == resource.bytes || now - lastUpdate > 200_000_000L) {
                                        lastUpdate = now
                                        mutableDownloads.update { it + (resource.id to state.copy(downloaded = count)) }
                                    }
                                }
                            }
                        }
                        mutableDownloads.update { it - resource.id }
                    } catch (e: Exception) {
                        if (e is CancellationException || !job.isActive) {
                            mutableDownloads.update { it - resource.id }
                            currentCoroutineContext().ensureActive()
                            throw e
                        }
                        mutableDownloads.update {
                            it + (resource.id to state.copy(error = context.getString(R.string.resource_download_failed, e.message ?: "")))
                        }
                        throw e
                    } finally {
                        canceller.cancel()
                        jobs.remove(resource.id, job)
                    }
                }
            }
        }
    }
}
