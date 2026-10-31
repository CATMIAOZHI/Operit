package com.ai.assistance.showerclient

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * H.264 decoder that renders the Shower video stream onto a Surface.
 * Each instance handles one video stream for a specific virtual display.
 */
class ShowerVideoRenderer {

    companion object {
        private const val TAG = "ShowerVideoRenderer"
    }

    private val lock = Any()

    @Volatile
    private var decoder: MediaCodec? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var csd0: ByteArray? = null

    @Volatile
    private var csd1: ByteArray? = null

    private val pendingFrames = mutableListOf<ByteArray>()

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    @Volatile
    private var warnedNoSurface: Boolean = false

    /** Shows each decoded picture as soon as it is ready; see [startDrainLocked]. */
    private var drainThread: Thread? = null

    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int) {
        synchronized(lock) {
            this.surface = surface
            this.width = videoWidth
            this.height = videoHeight
            warnedNoSurface = false
            releaseDecoderLocked()
            pendingFrames.clear()
        }
    }

    fun detach() {
        synchronized(lock) {
            releaseDecoderLocked()
            surface = null
            pendingFrames.clear()
            warnedNoSurface = false
        }
    }

    private fun releaseDecoderLocked() {
        val dec = decoder
        decoder = null
        val drain = drainThread
        drainThread = null
        if (drain != null && drain !== Thread.currentThread()) {
            try {
                drain.join(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (dec != null) {
            try {
                dec.stop()
            } catch (_: Exception) {
            }
            try {
                dec.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Called for each H.264 packet. */
    fun onFrame(data: ByteArray) {
        synchronized(lock) {
            if (surface == null || width <= 0 || height <= 0) {
                if (!warnedNoSurface) {
                    ShowerLog.w(TAG, "onFrame: no surface or invalid size; dropping frames")
                    warnedNoSurface = true
                }
                return
            }

            val packet = maybeAvccToAnnexb(data)

            if (decoder == null) {
                val nalType = findNalUnitType(packet)
                if (nalType == 7) { // SPS
                    if (csd0 == null) {
                        csd0 = packet
                    }
                } else if (nalType == 8) { // PPS
                    if (csd1 == null) {
                        csd1 = packet
                    }
                } else {
                    // Without SPS/PPS nothing decodes; keep only the latest frames meanwhile.
                    if (pendingFrames.size >= 120) pendingFrames.removeAt(0)
                    pendingFrames.add(packet)
                }

                if (csd0 != null && csd1 != null) {
                    initDecoderLocked()
                    val framesToProcess = pendingFrames.toList()
                    pendingFrames.clear()
                    framesToProcess.forEach { frame -> queueFrameToDecoder(frame) }
                }
                return
            }

            queueFrameToDecoder(packet)
        }
    }

    private fun findNalUnitType(packet: ByteArray): Int {
        var offset = -1
        for (i in 0 until packet.size - 3) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) {
                    offset = i + 3
                    break
                } else if (i + 3 < packet.size && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) {
                    offset = i + 4
                    break
                }
            }
        }
        if (offset != -1 && offset < packet.size) {
            return (packet[offset].toInt() and 0x1F)
        }
        return -1
    }

    private fun queueFrameToDecoder(packet: ByteArray) {
        synchronized(lock) {
            val dec = decoder ?: return
            try {
                val inIndex = dec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = dec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        dec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                }
            } catch (e: Exception) {
                ShowerLog.e(TAG, "Decoder error on frame", e)
                releaseDecoderLocked()
                pendingFrames.clear()
            }
        }
    }

    private fun maybeAvccToAnnexb(packet: ByteArray): ByteArray {
        if (packet.size >= 4) {
            val b0 = packet[0].toInt() and 0xFF
            val b1 = packet[1].toInt() and 0xFF
            val b2 = packet[2].toInt() and 0xFF
            val b3 = packet[3].toInt() and 0xFF
            if (b0 == 0 && b1 == 0 && ((b2 == 0 && b3 == 1) || b2 == 1)) {
                return packet
            }
        }
        val out = ByteArrayOutputStream()
        var i = 0
        val n = packet.size
        while (i + 4 <= n) {
            val nalLen =
                ((packet[i].toInt() and 0xFF) shl 24) or
                    ((packet[i + 1].toInt() and 0xFF) shl 16) or
                    ((packet[i + 2].toInt() and 0xFF) shl 8) or
                    (packet[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > n) return packet
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(packet, i, nalLen)
            i += nalLen
        }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else packet
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        val s: Surface
        val w: Int
        val h: Int
        synchronized(lock) {
            val localSurface = surface
            if (localSurface == null || width <= 0 || height <= 0) return null
            s = localSurface
            w = width
            h = height
        }

        if (Build.VERSION.SDK_INT < 26) return null

        return withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                PixelCopy.request(s, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        try {
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            cont.resume(baos.toByteArray())
                        } catch (e: Exception) {
                            cont.resume(null)
                        } finally {
                            bitmap.recycle()
                        }
                    } else {
                        bitmap.recycle()
                        cont.resume(null)
                    }
                }, handler)
            }
        }
    }

    private fun initDecoderLocked() {
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        if (width <= 0 || height <= 0) return

        try {
            val csd0Annexb = maybeAvccToAnnexb(localCsd0)
            val csd1Annexb = maybeAvccToAnnexb(localCsd1)

            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Annexb))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Annexb))
            // Hand out each picture at once instead of holding a few back.
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

            val dec = try {
                startCodec(format, s)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT < 30) throw e
                // A codec that failed may be left unusable, so a fresh one goes without it.
                ShowerLog.w(TAG, "Decoder refused low latency; starting a new one without it", e)
                format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                startCodec(format, s)
            }
            decoder = dec
            startDrainLocked(dec)
            ShowerLog.d(TAG, "MediaCodec decoder initialized for ${width}x${height}")
        } catch (e: Exception) {
            ShowerLog.e(TAG, "Failed to init decoder", e)
            releaseDecoderLocked()
        }
    }

    /** A started decoder, or an exception with the half-made one released. */
    private fun startCodec(format: MediaFormat, s: Surface): MediaCodec {
        val dec = MediaCodec.createDecoderByType("video/avc")
        try {
            dec.configure(format, s, null, 0)
            dec.start()
            return dec
        } catch (e: Exception) {
            try {
                dec.release()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /**
     * Takes pictures out of [dec] on its own thread. A still screen sends no new packets, so
     * waiting for the next packet to collect the last picture would leave the view blank. If
     * the codec fails, it is dropped so the next packet starts a new one.
     */
    private fun startDrainLocked(dec: MediaCodec) {
        val thread = Thread({
            val info = BufferInfo()
            while (decoder === dec) {
                try {
                    val index = dec.dequeueOutputBuffer(info, 20_000)
                    if (index < 0) continue
                    dec.releaseOutputBuffer(index, true)
                } catch (e: Exception) {
                    synchronized(lock) {
                        // Not when it was already replaced or released on purpose.
                        if (decoder === dec) {
                            ShowerLog.e(TAG, "Decoder failed while showing a picture", e)
                            releaseDecoderLocked()
                            pendingFrames.clear()
                        }
                    }
                    break
                }
            }
        }, "shower-video-drain")
        thread.isDaemon = true
        drainThread = thread
        thread.start()
    }
}
