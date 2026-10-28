package com.ai.assistance.operit.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import java.lang.ref.WeakReference

/**
 * Decoded chat images by image id, so a message drawn again (a waiting copy turning into the
 * sent message, a row scrolled back in) shows its picture at once instead of loading it again.
 */
object ChatImageBitmapCache {
    // Pushed out of the cache but maybe still on screen: found again while something still shows it.
    private val evicted = HashMap<String, WeakReference<Bitmap>>()

    private val cache =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(64L * 1024 * 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount

            override fun entryRemoved(evictedByLimit: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                if (newValue == null) synchronized(evicted) {
                    evicted.values.removeAll { it.get() == null }
                    evicted[key] = WeakReference(oldValue)
                }
            }
        }

    fun get(id: String): Bitmap? =
        cache.get(id) ?: synchronized(evicted) { evicted[id]?.get() }

    /** Decodes the image from the pool (call off the main thread) and keeps it. */
    fun load(id: String, sourcePath: String?): Bitmap? {
        get(id)?.let { return it }
        val data = ImagePoolManager.resolveImage(id, sourcePath) ?: return null
        val bitmap = runCatching {
            ImageBitmapLimiter.decodeDownsampledBitmap(Base64.decode(data.base64, Base64.DEFAULT))
        }.getOrNull() ?: return null
        cache.put(id, bitmap)
        return bitmap
    }

    /** A small copy of the image for a grid tile, no larger than [maxSide] pixels on its long side. */
    fun loadThumbnail(id: String, sourcePath: String?, maxSide: Int): Bitmap? {
        val key = "thumb:$maxSide:$id"
        get(key)?.let { return it }
        val data = ImagePoolManager.resolveImage(id, sourcePath) ?: return null
        val bitmap = runCatching {
            ImageBitmapLimiter.decodeDownsampledBitmap(
                Base64.decode(data.base64, Base64.DEFAULT),
                maxPixels = maxSide.toLong() * maxSide,
                maxDimension = maxSide,
            )
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
