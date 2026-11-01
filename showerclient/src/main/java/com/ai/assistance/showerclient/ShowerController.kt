package com.ai.assistance.showerclient

import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.IShowerVideoSink
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Lightweight controller to talk to the Shower server running locally on the device.
 *
 * Responsibilities:
 * - Maintain a Binder connection to the Shower service
 * - Send commands for a specific virtual display: ensureDisplay, launchApp, tap, swipe, touch, key, screenshot
 * - Track the virtual display id and video size for this session
 */
class ShowerController {

    companion object {
        private const val TAG = "ShowerController"
        private const val CODEC_SIZE_ALIGNMENT = 16
        private const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024

        @Volatile
        private var binderService: IShowerService? = null

        private fun alignToCodecBlockSize(value: Int): Int =
            ((value + CODEC_SIZE_ALIGNMENT - 1) / CODEC_SIZE_ALIGNMENT) * CODEC_SIZE_ALIGNMENT

        private suspend fun getBinder(context: Context? = null): IShowerService? = withContext(Dispatchers.IO) {
            if (binderService?.asBinder()?.isBinderAlive == true) {
                return@withContext binderService
            }

            fun clearDeadService() {
                binderService = null
                ShowerBinderRegistry.setService(null)
            }

            val maxAttempts = if (context != null) 2 else 1
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                try {
                    val cachedService = ShowerBinderRegistry.getService()
                    val binder = cachedService?.asBinder()
                    val alive = binder?.isBinderAlive == true
                    ShowerLog.d(TAG, "getBinder: attempt=$attempt cachedService=$cachedService binder=$binder alive=$alive")
                    if (cachedService != null && alive) {
                        binderService = cachedService
                        ShowerLog.d(TAG, "Connected to Shower Binder service on attempt=$attempt")
                        return@withContext binderService
                    } else {
                        ShowerLog.w(TAG, "No alive Shower Binder cached in ShowerBinderRegistry on attempt=$attempt")
                        clearDeadService()
                    }
                } catch (e: Exception) {
                    ShowerLog.e(TAG, "Failed to connect to Binder service on attempt=$attempt", e)
                    clearDeadService()
                }

                if (context != null && attempt == 1) {
                    try {
                        val ctx = context.applicationContext
                        ShowerLog.d(TAG, "getBinder: attempting to restart Shower server after connection failure")
                        val ok = ShowerServerManager.ensureServerStarted(ctx)
                        if (!ok) {
                            ShowerLog.e(TAG, "getBinder: failed to restart Shower server")
                            break
                        }
                        // Wait a bit for broadcast to propagate
                        delay(200)
                    } catch (e: Exception) {
                        ShowerLog.e(TAG, "getBinder: exception while restarting Shower server", e)
                        break
                    }
                }
            }
            null
        }
    }

    @Volatile
    private var virtualDisplayId: Int? = null

    fun getDisplayId(): Int? = virtualDisplayId

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    fun getVideoSize(): Pair<Int, Int>? =
        if (videoWidth > 0 && videoHeight > 0) Pair(videoWidth, videoHeight) else null

    private val binaryLock = Any()
    /**
     * The frames since the latest keyframe, kept whether or not someone watches, so a viewer
     * that opens on a still screen gets a full picture at once instead of waiting for a change.
     */
    private val gopFrames = ArrayDeque<ByteArray>()
    /** The latest SPS and PPS: a viewer that attaches late needs them before any frame. */
    private val configFrames = arrayOfNulls<ByteArray>(2)

    @Volatile
    private var binaryHandler: ((ByteArray) -> Unit)? = null

    /**
     * Live frames that arrive while a new receiver is still getting the cached ones; they go
     * to it right after, in order. Null when no replay is running.
     */
    private var replayBacklog: ArrayDeque<ByteArray>? = null

    /** The type of the first H.264 NAL unit in an Annex-B packet; -1 when unknown. */
    private fun nalType(packet: ByteArray): Int {
        var i = 0
        while (i + 3 < packet.size) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) return packet[i + 3].toInt() and 0x1F
                if (i + 4 < packet.size && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) {
                    return packet[i + 4].toInt() and 0x1F
                }
            }
            i++
            if (i > 8) break
        }
        return -1
    }

    /** Whether any NAL unit in the Annex-B [packet] is a keyframe slice (IDR), not just the first. */
    private fun hasIdr(packet: ByteArray): Boolean {
        var i = 0
        while (i + 3 < packet.size) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte() && packet[i + 2] == 1.toByte()) {
                if ((packet[i + 3].toInt() and 0x1F) == 5) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Sets the receiver of the video stream. A new receiver first gets the latest SPS/PPS, then the
     * frames since the latest keyframe, so it can start decoding however late it attaches.
     * Call it off the main thread: the replay goes through the decoder. It runs outside the lock,
     * so removing the receiver never waits for it; live frames meanwhile wait in [replayBacklog]
     * and follow the replayed ones in order.
     */
    fun setBinaryHandler(handler: ((ByteArray) -> Unit)?) {
        var batch: List<ByteArray>
        synchronized(binaryLock) {
            binaryHandler = handler
            ShowerLog.d(TAG, "setBinaryHandler: id=${virtualDisplayId} handlerSet=${handler != null}, bufferedFrames=${gopFrames.size}")
            if (handler == null) {
                replayBacklog = null
                return
            }
            batch = configFrames.filterNotNull() + gopFrames
            replayBacklog = ArrayDeque()
        }
        val receiver = handler ?: return
        while (true) {
            for (frame in batch) {
                // Replaced or removed meanwhile: the next receiver has its own replay.
                if (binaryHandler !== receiver) return
                try {
                    receiver(frame)
                } catch (_: Exception) {
                }
            }
            synchronized(binaryLock) {
                if (binaryHandler !== receiver) return
                val backlog = replayBacklog ?: return
                if (backlog.isEmpty()) {
                    replayBacklog = null
                    return
                }
                batch = backlog.toList()
                backlog.clear()
            }
        }
    }

    /** Removes [handler], unless another one has replaced it meanwhile. */
    fun clearBinaryHandler(handler: (ByteArray) -> Unit) {
        synchronized(binaryLock) {
            if (binaryHandler === handler) {
                binaryHandler = null
                replayBacklog = null
            }
        }
    }

    private val videoSink = object : IShowerVideoSink.Stub() {
        override fun onVideoFrame(data: ByteArray) {
            var handler: ((ByteArray) -> Unit)?
            synchronized(binaryLock) {
                handler = binaryHandler
                val type = nalType(data)
                when {
                    // A keyframe, even one sent together with SPS/PPS: frames before it are
                    // not needed any more, and it starts the cache.
                    type == 5 || hasIdr(data) -> {
                        gopFrames.clear()
                        gopFrames.addLast(data)
                    }
                    type == 7 || type == 8 -> configFrames[type - 7] = data
                    // Keyframes come every second while the screen changes; past the cap,
                    // keep the start so the cached frames still decode.
                    gopFrames.size < 120 -> gopFrames.addLast(data)
                }
                val backlog = replayBacklog
                if (handler != null && backlog != null) {
                    // The receiver is still replaying; it takes this one after. If too much piles
                    // up, it restarts from the latest keyframe on, which still decodes.
                    if (backlog.size < 240) {
                        backlog.addLast(data)
                    } else {
                        backlog.clear()
                        backlog.addAll(configFrames.filterNotNull())
                        backlog.addAll(gopFrames)
                    }
                    handler = null
                }
            }
            handler?.invoke(data)
        }
    }

    /**
     * The server connection that turned out to be from before the pipe call; only the binder
     * reply is used with it. A restarted server is a new connection and is asked again.
     */
    @Volatile
    private var pipeUnsupportedBinder: IBinder? = null

    /**
     * The display's picture. Through a pipe first, so a full-size picture is not cut down to fit
     * the binder buffer; [jpegQuality] 1..100 asks for JPEG, else PNG. An older server answers
     * through the binder reply instead, which is PNG or JPEG small enough for it.
     */
    suspend fun requestScreenshot(timeoutMs: Long = 3000L, jpegQuality: Int = 0): ByteArray? =
        withContext(Dispatchers.IO) {
            val service = getBinder() ?: return@withContext null
            val id = virtualDisplayId ?: return@withContext null
            try {
                withTimeout(timeoutMs) {
                    val binder = service.asBinder()
                    if (pipeUnsupportedBinder !== binder) {
                        try {
                            val pipe = service.requestScreenshotFd(id, jpegQuality)
                            return@withTimeout pipe?.let { readScreenshot(it) }
                        } catch (e: UnsupportedOperationException) {
                            ShowerLog.w(TAG, "Shower server has no screenshot pipe; using the binder reply")
                            pipeUnsupportedBinder = binder
                        }
                    }
                    service.requestScreenshot(id)
                }
            } catch (e: Exception) {
                ShowerLog.e(TAG, "requestScreenshot failed for $id", e)
                null
            }
        }

    /**
     * Reads the whole picture from [pipe]. It waits for data in short polls, so a timeout or a
     * cancel stops it within a moment even when the server stops writing; the pipe is closed
     * on every way out, which also ends the server's writer.
     */
    private suspend fun readScreenshot(pipe: ParcelFileDescriptor): ByteArray? =
        pipe.use { pfd ->
            val fd = pfd.fileDescriptor
            val out = ByteArrayOutputStream(512 * 1024)
            val buffer = ByteArray(64 * 1024)
            val pollFd = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            val pollFds = arrayOf(pollFd)
            while (true) {
                coroutineContext.ensureActive()
                pollFd.revents = 0
                val ready = try {
                    Os.poll(pollFds, 50)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (ready == 0) continue
                // Readable, or the server closed its end: then read gives 0.
                val read = try {
                    Os.read(fd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue else throw e
                }
                if (read <= 0) break
                // A screen picture is a few MB at most; anything far bigger is not one.
                check(out.size() + read <= MAX_SCREENSHOT_BYTES) {
                    "Screenshot larger than ${MAX_SCREENSHOT_BYTES / (1024 * 1024)} MB"
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }

    /**
     * Prepares this controller to inject input on the physical main display (displayId=0)
     * without creating a virtual display.
     */
    suspend fun prepareMainDisplay(context: Context): Boolean = withContext(Dispatchers.IO) {
        fun clearCachedBinder() {
            binderService = null
            ShowerBinderRegistry.setService(null)
        }

        fun resetLocalDisplayState() {
            virtualDisplayId = null
            videoWidth = 0
            videoHeight = 0
        }

        fun isBinderDied(e: Throwable): Boolean {
            return e is DeadObjectException || e is RemoteException || e.cause is DeadObjectException
        }

        fun doPrepare(service: IShowerService): Boolean {
            // If this controller was previously bound to a virtual display, release it first.
            val oldId = virtualDisplayId
            if (oldId != null && oldId > 0) {
                try {
                    service.setVideoSink(oldId, null)
                } catch (_: Exception) {
                }
                try {
                    service.destroyDisplay(oldId)
                } catch (_: Exception) {
                }
            }

            virtualDisplayId = 0
            videoWidth = 0
            videoHeight = 0
            // Probe the input path once. keyCode=0 (KEYCODE_UNKNOWN) should be harmless.
            service.injectKey(0, 0)
            ShowerLog.d(TAG, "prepareMainDisplay complete, displayId=0")
            return true
        }

        val service = getBinder(context) ?: return@withContext false
        try {
            doPrepare(service)
        } catch (e: Exception) {
            ShowerLog.e(TAG, "prepareMainDisplay failed", e)
            resetLocalDisplayState()
            if (!isBinderDied(e)) {
                return@withContext false
            }

            clearCachedBinder()
            try {
                ShowerLog.d(TAG, "prepareMainDisplay: binder died, restarting Shower server and retrying")
                val ok = ShowerServerManager.ensureServerStarted(context.applicationContext)
                if (!ok) {
                    ShowerLog.e(TAG, "prepareMainDisplay: failed to restart Shower server")
                    return@withContext false
                }
                delay(200)
                val retryService = getBinder(context) ?: return@withContext false
                doPrepare(retryService)
            } catch (retryError: Exception) {
                ShowerLog.e(TAG, "prepareMainDisplay retry failed", retryError)
                resetLocalDisplayState()
                clearCachedBinder()
                false
            }
        }
    }

    suspend fun ensureDisplay(
        context: Context,
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        fun clearCachedBinder() {
            binderService = null
            ShowerBinderRegistry.setService(null)
        }

        fun resetLocalDisplayState() {
            virtualDisplayId = null
            videoWidth = 0
            videoHeight = 0
        }

        fun isBinderDied(e: Throwable): Boolean {
            return e is DeadObjectException || e is RemoteException || e.cause is DeadObjectException
        }

        val targetWidth = alignToCodecBlockSize(width)
        val targetHeight = alignToCodecBlockSize(height)
        val bitrate = bitrateKbps ?: 0

        suspend fun doEnsure(service: IShowerService): Boolean {
            val existingId = virtualDisplayId
            if (existingId != null && videoWidth == targetWidth && videoHeight == targetHeight) {
                service.setVideoSink(existingId, videoSink.asBinder())
                ShowerLog.d(TAG, "ensureDisplay reuse existing displayId=$existingId, size=${videoWidth}x${videoHeight}")
                return true
            }

            if (existingId != null) {
                try {
                    service.destroyDisplay(existingId)
                    ShowerLog.d(TAG, "ensureDisplay: destroyed previous displayId=$existingId before recreate")
                } catch (e: Exception) {
                    ShowerLog.w(TAG, "ensureDisplay: failed to destroy previous displayId=$existingId before recreate", e)
                }
                resetLocalDisplayState()
            }

            // Changed: ensureDisplay now returns the ID and doesn't destroy existing ones.
            val id = service.ensureDisplay(targetWidth, targetHeight, dpi, bitrate)
            if (id < 0) {
                resetLocalDisplayState()
                ShowerLog.e(TAG, "ensureDisplay: server reported invalid displayId=$id")
                return false
            }

            virtualDisplayId = id
            videoWidth = targetWidth
            videoHeight = targetHeight

            // Link local sink to this display on the server
            service.setVideoSink(id, videoSink.asBinder())

            ShowerLog.d(TAG, "ensureDisplay complete, new displayId=$virtualDisplayId, size=${videoWidth}x${videoHeight}")
            return true
        }

        val service = getBinder(context) ?: return@withContext false
        try {
            doEnsure(service)
        } catch (e: Exception) {
            ShowerLog.e(TAG, "ensureDisplay failed", e)
            resetLocalDisplayState()
            if (!isBinderDied(e)) {
                return@withContext false
            }

            clearCachedBinder()
            try {
                ShowerLog.d(TAG, "ensureDisplay: binder died, restarting Shower server and retrying")
                val ok = ShowerServerManager.ensureServerStarted(context.applicationContext)
                if (!ok) {
                    ShowerLog.e(TAG, "ensureDisplay: failed to restart Shower server")
                    return@withContext false
                }
                delay(200)
                val retryService = getBinder(context) ?: return@withContext false
                doEnsure(retryService)
            } catch (retryError: Exception) {
                ShowerLog.e(TAG, "ensureDisplay retry failed", retryError)
                resetLocalDisplayState()
                clearCachedBinder()
                false
            }
        }
    }

    suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        if (packageName.isBlank()) return@withContext false
        try {
            service.launchApp(packageName, id)
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "launchApp failed for $packageName on $id", e)
            false
        }
    }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.tap(id, x.toFloat(), y.toFloat())
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "tap($x, $y) failed on $id", e)
            false
        }
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L,
    ): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.swipe(id, startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), durationMs)
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "swipe failed on $id", e)
            false
        }
    }

    suspend fun touchDown(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.touchDown(id, x.toFloat(), y.toFloat())
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "touchDown($x, $y) failed on $id", e)
            false
        }
    }

    suspend fun touchMove(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.touchMove(id, x.toFloat(), y.toFloat())
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "touchMove($x, $y) failed on $id", e)
            false
        }
    }

    suspend fun touchUp(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.touchUp(id, x.toFloat(), y.toFloat())
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "touchUp($x, $y) failed on $id", e)
            false
        }
    }

    suspend fun injectTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        pressure: Float,
        size: Float,
        metaState: Int,
        xPrecision: Float,
        yPrecision: Float,
        deviceId: Int,
        edgeFlags: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            service.injectTouchEvent(
                id,
                action,
                x,
                y,
                downTime,
                eventTime,
                pressure,
                size,
                metaState,
                xPrecision,
                yPrecision,
                deviceId,
                edgeFlags
            )
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "injectTouchEvent(action=$action, x=$x, y=$y) failed on $id", e)
            false
        }
    }

    fun shutdown() {
        ShowerLog.d(TAG, "shutdown requested for display $virtualDisplayId")
        val service = binderService
        val id = virtualDisplayId
        virtualDisplayId = null
        videoWidth = 0
        videoHeight = 0
        synchronized(binaryLock) {
            binaryHandler = null
            replayBacklog = null
            gopFrames.clear()
            configFrames.fill(null)
        }
        if (id != null && id > 0 && service?.asBinder()?.isBinderAlive == true) {
            try {
                service.setVideoSink(id, null)
                service.destroyDisplay(id)
            } catch (e: Exception) {
                ShowerLog.e(TAG, "shutdown: destroyDisplay failed for $id", e)
            }
        }
    }

    suspend fun key(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        keyWithMeta(keyCode, 0)
    }

    suspend fun keyWithMeta(keyCode: Int, metaState: Int): Boolean = withContext(Dispatchers.IO) {
        val service = getBinder() ?: return@withContext false
        val id = virtualDisplayId ?: return@withContext false
        try {
            if (metaState == 0) {
                service.injectKey(id, keyCode)
            } else {
                service.injectKeyWithMeta(id, keyCode, metaState)
            }
            true
        } catch (e: Exception) {
            ShowerLog.e(TAG, "keyWithMeta(keyCode=$keyCode, metaState=$metaState) failed on $id", e)
            false
        }
    }
}
