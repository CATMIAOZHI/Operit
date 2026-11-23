package com.ai.assistance.operit.api.voice

import java.io.File
import java.io.FilterOutputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * A request body written as [head], then [file] in Base64, then [tail]. The recording is encoded
 * while it is sent, so a several-megabyte sample never sits in memory as one big string.
 */
internal class Base64FileBody(
    private val head: String,
    private val file: File,
    private val tail: String,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long =
        head.toByteArray().size + 4L * ((file.length() + 2) / 3) + tail.toByteArray().size

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(head)
        // Closing the encoder writes its last bytes; the sink itself must stay open.
        val keepOpen = object : FilterOutputStream(sink.outputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        java.util.Base64.getEncoder().wrap(keepOpen).use { encoder ->
            file.inputStream().use { it.copyTo(encoder, 64 * 1024) }
        }
        sink.writeUtf8(tail)
    }
}
