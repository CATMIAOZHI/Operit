package com.ai.assistance.operit.util

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

object HttpMultiPartDownloader {
    /**
     * Stops one download from another thread. Besides marking it stopped (checked between
     * chunks), it closes every open connection, so a request stuck connecting or reading ends
     * at once instead of at its 15–30 second timeout.
     */
    class Cancellation {
        @Volatile
        var isCancelled: Boolean = false
            private set
        private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        private val children = java.util.concurrent.ConcurrentHashMap.newKeySet<Cancellation>()

        fun cancel() {
            isCancelled = true
            connections.forEach { runCatching { it.disconnect() } }
            children.forEach { it.cancel() }
        }

        /** A cancellation that also stops when this one does, but can be stopped on its own. */
        internal fun child(): Cancellation {
            val child = Cancellation()
            children.add(child)
            if (isCancelled) child.cancel()
            return child
        }

        internal fun register(connection: HttpURLConnection) {
            connections.add(connection)
            // Stopped while this one was being opened: close it right away.
            if (isCancelled) runCatching { connection.disconnect() }
        }

        internal fun unregister(connection: HttpURLConnection) {
            connections.remove(connection)
        }
    }

    data class ProbeResult(
        val contentLength: Long,
        val acceptRanges: Boolean
    )

    data class SegmentPlan(
        val index: Int,
        val startInclusive: Long,
        val endInclusive: Long
    )

    fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        threadCount: Int = 4,
        cancellation: Cancellation? = null,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val safeThreads = threadCount.coerceIn(1, 8)
        val isCancelled: (() -> Boolean)? = cancellation?.let { c -> { c.isCancelled } }

        val meta = probeDownload(url, headers, cancellation)
        if (isCancelled?.invoke() == true) throw InterruptedException("Download cancelled")
        val total = meta.contentLength
        val supportsRanges = meta.acceptRanges

        if (total <= 0L || !supportsRanges || safeThreads == 1) {
            downloadSingle(url, dest, headers, total, onProgress, isCancelled, cancellation)
            return
        }

        downloadMulti(url, dest, headers, total, safeThreads, onProgress, isCancelled, cancellation)
    }

    fun probeDownload(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancellation: Cancellation? = null,
    ): ProbeResult {
        // Prefer HEAD, but some servers don't allow it.
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                applyHeaders(this, headers)
                setRequestProperty("Accept-Encoding", "identity")
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            cancellation?.register(conn)
            val code = conn.responseCode
            if (code in 200..399) {
                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                val acceptRanges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                return ProbeResult(len, acceptRanges)
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            conn?.let { cancellation?.unregister(it); it.disconnect() }
        }

        if (cancellation?.isCancelled == true) return ProbeResult(-1L, false)
        // Fallback GET with Range 0-0 to detect range support.
        var conn2: HttpURLConnection? = null
        try {
            conn2 = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyHeaders(this, headers)
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-0")
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            cancellation?.register(conn2)
            val code = conn2.responseCode
            val acceptRangesHeader = conn2.getHeaderField("Accept-Ranges")
            val acceptRanges = (code == HttpURLConnection.HTTP_PARTIAL) ||
                (acceptRangesHeader?.contains("bytes", ignoreCase = true) == true)

            val contentRange = conn2.getHeaderField("Content-Range")
            val totalFromContentRange = parseTotalFromContentRange(contentRange)
            val total = when {
                totalFromContentRange > 0L -> totalFromContentRange
                else -> conn2.getHeaderFieldLong("Content-Length", -1L)
            }
            return ProbeResult(total, acceptRanges)
        } catch (_: Exception) {
            return ProbeResult(-1L, false)
        } finally {
            conn2?.let { cancellation?.unregister(it); it.disconnect() }
        }
    }

    fun buildSegmentPlan(totalBytes: Long, threadCount: Int): List<SegmentPlan> {
        if (totalBytes <= 0L) {
            return emptyList()
        }
        val safeThreads = threadCount.coerceIn(1, 8)
        val partSize = (totalBytes + safeThreads - 1) / safeThreads
        return buildList(safeThreads) {
            for (part in 0 until safeThreads) {
                val start = part * partSize
                val end = minOf(totalBytes - 1, (part + 1) * partSize - 1)
                if (start <= end) {
                    add(
                        SegmentPlan(
                            index = part,
                            startInclusive = start,
                            endInclusive = end
                        )
                    )
                }
            }
        }
    }

    fun downloadSegment(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        startInclusive: Long = 0L,
        endInclusive: Long? = null,
        append: Boolean = false,
        onChunk: ((chunkBytes: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        cancellation: Cancellation? = null
    ) {
        var conn: HttpURLConnection? = null
        try {
            if (cancellation?.isCancelled == true) throw InterruptedException("Download cancelled")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyHeaders(this, headers)
                setRequestProperty("Accept-Encoding", "identity")
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                if (startInclusive > 0L || endInclusive != null) {
                    val rangeValue =
                        if (endInclusive != null) {
                            "bytes=$startInclusive-$endInclusive"
                        } else {
                            "bytes=$startInclusive-"
                        }
                    setRequestProperty("Range", rangeValue)
                }
            }
            cancellation?.register(conn)

            val code = conn.responseCode
            val expectedPartial = startInclusive > 0L || endInclusive != null
            if (expectedPartial) {
                if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("HTTP $code")
                }
            } else if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }

            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest, append).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled?.invoke() == true) {
                            throw InterruptedException("Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onChunk?.invoke(read)
                    }
                    output.flush()
                }
            }
        } finally {
            conn?.let { cancellation?.unregister(it); it.disconnect() }
        }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long {
        // format: bytes 0-0/12345
        if (contentRange.isNullOrBlank()) return -1L
        val slash = contentRange.lastIndexOf('/')
        if (slash <= 0 || slash >= contentRange.length - 1) return -1L
        return contentRange.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    private fun downloadSingle(
        url: String,
        dest: File,
        headers: Map<String, String>,
        totalBytes: Long,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: (() -> Boolean)?,
        cancellation: Cancellation?
    ) {
        val total = AtomicLong(totalBytes)
        val downloaded = AtomicLong(0L)
        downloadSegment(
            url = url,
            dest = dest,
            headers = headers,
            startInclusive = 0L,
            endInclusive = null,
            append = false,
            onChunk = { chunk ->
                val now = downloaded.addAndGet(chunk.toLong())
                onProgress?.invoke(now, total.get())
            },
            isCancelled = isCancelled,
            cancellation = cancellation
        )
    }

    private fun downloadMulti(
        url: String,
        dest: File,
        headers: Map<String, String>,
        totalBytes: Long,
        threadCount: Int,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: (() -> Boolean)?,
        cancellation: Cancellation?
    ) {
        dest.parentFile?.mkdirs()

        // Pre-allocate file
        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val downloaded = AtomicLong(0L)
        val firstError = AtomicReference<Throwable?>(null)
        // Stops every part: when the caller stops, or when one part fails.
        val parts = cancellation?.child() ?: Cancellation()
        fun stopped() = firstError.get() != null || parts.isCancelled || isCancelled?.invoke() == true
        val pool = Executors.newFixedThreadPool(threadCount)
        val segments = buildSegmentPlan(totalBytes, threadCount)
        // One count per real part: a small file can have fewer parts than threads.
        val latch = CountDownLatch(segments.size)
        fun partFile(index: Int) = File(dest.parentFile, "${dest.name}.part.$index")
        fun cleanUpAfterStop() {
            segments.forEach { runCatching { partFile(it.index).delete() } }
            runCatching { dest.delete() }
        }
        // Parts still running. After a stop, the last one to end removes the files, so nothing
        // writes a file again after the cleanup, however long a part takes to notice the stop.
        val running = java.util.concurrent.atomic.AtomicInteger(segments.size)
        fun partDone() {
            if (running.decrementAndGet() == 0 && firstError.get() != null) cleanUpAfterStop()
        }

        for (segment in segments) {
            val start = segment.startInclusive
            val end = segment.endInclusive

            pool.execute {
                try {
                    val partFile = partFile(segment.index)
                    partFile.delete()
                    downloadSegment(
                        url = url,
                        dest = partFile,
                        headers = headers,
                        startInclusive = start,
                        endInclusive = end,
                        append = false,
                        onChunk = { chunk ->
                            val now = downloaded.addAndGet(chunk.toLong())
                            onProgress?.invoke(now, totalBytes)
                        },
                        // Another part failing stops this one too.
                        isCancelled = { stopped() },
                        cancellation = parts
                    )
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.seek(start)
                        partFile.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (stopped()) throw InterruptedException("Download cancelled")
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                            }
                        }
                    }
                    partFile.delete()
                } catch (t: Throwable) {
                    firstError.compareAndSet(null, t)
                    parts.cancel()
                    runCatching { partFile(segment.index).delete() }
                } finally {
                    partDone()
                    latch.countDown()
                }
            }
        }

        var interrupted: InterruptedException? = null
        try {
            latch.await()
        } catch (e: InterruptedException) {
            interrupted = e
            firstError.compareAndSet(null, e)
            parts.cancel()
        }

        val err = firstError.get()
        if (err != null) {
            // Parts that never started will not run their own end step.
            repeat(pool.shutdownNow().size) { partDone() }
            val allEnded = runCatching {
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrDefault(false)
            // Every part has ended, so nothing writes any more: clean up here too, in case they had
            // all finished before the stop. Otherwise the last part cleans up when it ends.
            if (allEnded) cleanUpAfterStop()
            if (interrupted != null) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            throw RuntimeException("Multi-part download failed", err)
        }
        pool.shutdown()

        onProgress?.invoke(totalBytes, totalBytes)
    }

    private fun applyHeaders(conn: HttpURLConnection, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        headers.forEach { (rawName, rawValue) ->
            val name = sanitizeHeaderName(rawName) ?: return@forEach
            val value = sanitizeHeaderValue(rawValue)
            conn.setRequestProperty(name, value)
        }
    }

    private fun sanitizeHeaderName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("\r") || trimmed.contains("\n")) return null
        if (trimmed.contains(":")) return null
        return trimmed
    }

    private fun sanitizeHeaderValue(value: String?): String {
        return value
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?: ""
    }
}
