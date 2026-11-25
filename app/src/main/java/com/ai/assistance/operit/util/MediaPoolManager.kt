package com.ai.assistance.operit.util

import android.util.Base64
import android.util.Base64InputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object MediaPoolManager {
    private const val TAG = "MediaPoolManager"

    private const val MAX_INPUT_BYTES = MediaBase64Limiter.DEFAULT_MAX_DECODED_BYTES

    private fun q(path: String): String = "\"" + path.replace("\"", "\\\"") + "\""

    private data class TranscodedMedia(
        val file: File,
        val mimeType: String
    )

    private fun transcodeToFit(source: File, mimeType: String, baseDir: File?): TranscodedMedia? {
        if (baseDir == null) return null
        val dir = File(baseDir, "media_pool_transcoded")
        runCatching { if (!dir.exists()) dir.mkdirs() }

        fun ok(out: File): Boolean = out.isFile && out.length() in 1L..MAX_INPUT_BYTES.toLong()

        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            val out = File(dir, "${UUID.randomUUID()}.mp3")
            val inPath = q(source.absolutePath)
            val outPath = q(out.absolutePath)

            val commands = listOf(
                "-y -i $inPath -vn -ac 1 -ar 16000 -b:a 64k $outPath",
                "-y -i $inPath -vn -ac 1 -ar 16000 -b:a 32k $outPath"
            )

            for (cmd in commands) {
                runCatching { out.delete() }
                if (FFmpegUtil.executeCommand(cmd) && ok(out)) {
                    return TranscodedMedia(out, "audio/mpeg")
                }
            }
            runCatching { out.delete() }
            return null
        }

        if (mimeType.startsWith("video/", ignoreCase = true)) {
            val out = File(dir, "${UUID.randomUUID()}.mp4")
            val inPath = q(source.absolutePath)
            val outPath = q(out.absolutePath)
            val scale640 = FFmpegUtil.scaleFilterMaxWidth(640)
            val scale480 = FFmpegUtil.scaleFilterMaxWidth(480)

            val commands = listOf(
                "-y -i $inPath -vf $scale640 -c:v h264 -preset veryfast -crf 32 -c:a aac -b:a 64k -movflags +faststart $outPath",
                "-y -i $inPath -vf $scale640 -c:v mpeg4 -q:v 8 -c:a aac -b:a 64k -movflags +faststart $outPath",
                "-y -i $inPath -vf $scale480 -c:v mpeg4 -q:v 12 -c:a aac -b:a 48k -movflags +faststart $outPath"
            )

            for (cmd in commands) {
                runCatching { out.delete() }
                if (FFmpegUtil.executeCommand(cmd) && ok(out)) {
                    return TranscodedMedia(out, "video/mp4")
                }
            }
            runCatching { out.delete() }
            return null
        }

        return null
    }

    private fun extForMimeType(mimeType: String): String {
        val mt = mimeType.lowercase().substringBefore(';')
        return when (mt) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/webm" -> "webm"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/ogg" -> "ogv"
            else -> mt.substringAfter('/', "bin").ifBlank { "bin" }
        }
    }

    private fun decodeBase64ToFile(base64: String, outFile: File) {
        val input: InputStream = Base64InputStream(AsciiStringInputStream(base64), Base64.DEFAULT)
        input.use { ins ->
            FileOutputStream(outFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = ins.read(buffer)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                }
            }
        }
    }

    private class AsciiStringInputStream(private val data: String) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= data.length) return -1
            return data[index++].code and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index >= data.length) return -1
            val count = minOf(len, data.length - index)
            for (i in 0 until count) {
                b[off + i] = data[index + i].code.toByte()
            }
            index += count
            return count
        }
    }

    @Volatile var maxPoolSize = 12
        set(value) {
            if (value > 0) {
                field = value
                AppLogger.d(TAG, "池子大小限制已更新为: $value")
            }
        }

    @Volatile private var cacheDir: File? = null
    // A disk read started before a removal or directory switch must not repopulate the index.
    private var indexVersion = 0L

    data class MediaData(
        val base64: String,
        val mimeType: String
    )

    fun initialize(cacheDirPath: File, preloadNow: Boolean = true) {
        val target = OperitPaths.mediaPoolDir(cacheDirPath)
        synchronized(this) {
            if (cacheDir != target) {
                mediaPool.clear()
                indexVersion++
                cacheDir = target
            }
        }
        if (preloadNow) {
            preloadFromDisk()
        }
    }

    private val mediaPool = object : LinkedHashMap<String, MediaData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaData>?): Boolean {
            val shouldRemove = size > maxPoolSize
            if (shouldRemove && eldest != null) {
                AppLogger.d(TAG, "释放媒体内存，保留聊天附件: ${eldest.key}")
            }
            return shouldRemove
        }
    }

    fun addMedia(filePath: String, mimeType: String): String {
        return try {
            val dir = cacheDir
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                AppLogger.e(TAG, "文件不存在或不是文件: $filePath")
                return "error"
            }

            val fileSize = runCatching { file.length() }.getOrNull() ?: -1L
            var effectiveFile = file
            var effectiveMimeType = mimeType
            if (fileSize > MAX_INPUT_BYTES) {
                val transcoded = transcodeToFit(file, mimeType, dir)
                if (transcoded == null) {
                    AppLogger.e(TAG, "媒体文件过大且压缩失败，拒绝加入池子: $filePath, bytes=$fileSize")
                    return "error"
                }
                effectiveFile = transcoded.file
                effectiveMimeType = transcoded.mimeType
            }

            val bytes = try {
                FileInputStream(effectiveFile).use { it.readBytes() }
            } catch (e: Exception) {
                AppLogger.e(TAG, "读取文件失败", e)
                if (effectiveFile != file) {
                    runCatching { effectiveFile.delete() }
                }
                return "error"
            }

            if (effectiveFile != file) {
                runCatching { effectiveFile.delete() }
            }

            if (bytes.size > MAX_INPUT_BYTES) {
                AppLogger.e(TAG, "媒体文件过大，拒绝加入池子: $filePath, bytes=${bytes.size}")
                return "error"
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mediaData = MediaData(base64 = base64, mimeType = effectiveMimeType)
            val id = registerMedia(dir, mediaData)
            AppLogger.d(TAG, "成功添加媒体到池子: $id, mimeType=$mimeType, sizeBytes=${bytes.size}")
            id
        } catch (e: Exception) {
            AppLogger.e(TAG, "添加媒体时发生异常: $filePath", e)
            "error"
        }
    }

    fun addMediaFromBase64(base64: String, mimeType: String): String {
        return try {
            val baseDir = cacheDir
            val estimated = MediaBase64Limiter.estimateDecodedSizeBytes(base64)
            if (estimated == null) {
                AppLogger.e(TAG, "媒体base64估算失败，拒绝加入池子: mimeType=$mimeType")
                return "error"
            }

            if (estimated <= MAX_INPUT_BYTES) {
                val bytes = try {
                    Base64.decode(base64, Base64.DEFAULT)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "媒体base64解码失败", e)
                    return "error"
                }

                if (bytes.size > MAX_INPUT_BYTES) {
                    AppLogger.e(TAG, "媒体base64解码后过大，拒绝加入池子: mimeType=$mimeType, bytes=${bytes.size}")
                    return "error"
                }

                val normalizedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mediaData = MediaData(base64 = normalizedBase64, mimeType = mimeType)
                val id = registerMedia(baseDir, mediaData)
                AppLogger.d(TAG, "成功从base64添加媒体到池子: $id, mimeType=$mimeType, sizeChars=${base64.length}")
                return id
            }

            if (baseDir == null) {
                AppLogger.e(TAG, "媒体base64过大且缓存目录未初始化，拒绝加入池子: mimeType=$mimeType, estimatedBytes=$estimated")
                return "error"
            }

            val dir = File(baseDir, "media_pool_base64_decode")
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val inFile = File(dir, "${UUID.randomUUID()}.${extForMimeType(mimeType)}")
            try {
                decodeBase64ToFile(base64, inFile)
            } catch (e: Exception) {
                AppLogger.e(TAG, "媒体base64流式解码失败", e)
                runCatching { inFile.delete() }
                return "error"
            }

            val transcoded = transcodeToFit(inFile, mimeType, baseDir)
            runCatching { inFile.delete() }
            if (transcoded == null) {
                AppLogger.e(TAG, "媒体base64过大且压缩失败，拒绝加入池子: mimeType=$mimeType, estimatedBytes=$estimated")
                return "error"
            }

            val bytes = try {
                FileInputStream(transcoded.file).use { it.readBytes() }
            } catch (e: Exception) {
                AppLogger.e(TAG, "读取转码后的媒体失败", e)
                runCatching { transcoded.file.delete() }
                return "error"
            }

            runCatching { transcoded.file.delete() }

            if (bytes.size > MAX_INPUT_BYTES) {
                AppLogger.e(TAG, "转码后的媒体仍然过大，拒绝加入池子: mimeType=${transcoded.mimeType}, bytes=${bytes.size}")
                return "error"
            }

            val normalizedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mediaData = MediaData(base64 = normalizedBase64, mimeType = transcoded.mimeType)
            val id = registerMedia(baseDir, mediaData)
            AppLogger.d(TAG, "成功从base64添加媒体到池子(已压缩): $id, mimeType=${transcoded.mimeType}, sizeChars=${base64.length}")
            id
        } catch (e: Exception) {
            AppLogger.e(TAG, "从base64添加媒体时发生异常", e)
            "error"
        }
    }

    fun getMedia(id: String): MediaData? {
        val dir: File
        val version: Long
        synchronized(this) {
            mediaPool[id]?.let { return it }
            dir = cacheDir ?: return null
            version = indexVersion
        }
        val loaded = loadOneFromDisk(dir, id) ?: return null
        synchronized(this) {
            if (cacheDir != dir) return null
            if (indexVersion == version) mediaPool[id] = loaded
            return loaded
        }
    }

    /** Base64 length of a clip without loading it, or null when it is gone. */
    fun base64Length(id: String): Long? {
        val dir: File
        synchronized(this) {
            mediaPool[id]?.let { return it.base64.length.toLong() }
            dir = cacheDir ?: return null
        }
        if (!isSimplePoolCacheId(id)) return null
        return File(dir, "$id.b64").length().takeIf { it > 0 }
    }

    private fun loadOneFromDisk(dir: File, id: String): MediaData? {
        if (!isSimplePoolCacheId(id)) {
            return null
        }
        return try {
            val metaFile = File(dir, "$id.meta")
            val b64File = File(dir, "$id.b64")
            if (!metaFile.exists() || !b64File.exists()) {
                return null
            }
            val mimeType = runCatching { metaFile.readText().trim() }.getOrNull() ?: return null
            val base64 = runCatching { b64File.readText().trim() }.getOrNull() ?: return null
            if (mimeType.isBlank() || base64.isBlank()) {
                return null
            }
            MediaData(base64 = base64, mimeType = mimeType)
        } catch (e: Exception) {
            AppLogger.e(TAG, "从磁盘加载媒体失败: $id", e)
            null
        }
    }

    fun removeMedia(id: String) {
        val dir = cacheDir
        if (dir != null) deleteFromDisk(dir, id)
        synchronized(this) {
            if (dir == cacheDir) {
                mediaPool.remove(id)
                indexVersion++
            }
        }
    }

    private fun registerMedia(dir: File?, data: MediaData): String {
        val id = UUID.randomUUID().toString()
        if (dir != null) {
            // Each registration owns its UUID. Preload discovers an entry by its .meta file,
            // so publish that marker only after its complete base64 payload is on disk.
            val marker = File(dir, "$id.meta.tmp")
            try {
                File(dir, "$id.b64").bufferedWriter(Charsets.US_ASCII).use { it.write(data.base64) }
                marker.writeText(data.mimeType)
                check(marker.renameTo(File(dir, "$id.meta"))) { "Could not publish media $id" }
            } catch (e: Exception) {
                marker.delete()
                deleteFromDisk(dir, id)
                AppLogger.e(TAG, "保存媒体到磁盘失败: $id", e)
            }
        }
        synchronized(this) {
            if (dir == cacheDir) {
                mediaPool[id] = data
                return id
            }
        }
        // This registration belongs to the old directory.
        if (dir != null) deleteFromDisk(dir, id)
        return "error"
    }

    private fun deleteFromDisk(dir: File, id: String) {
        if (!isSimplePoolCacheId(id)) {
            AppLogger.w(TAG, "忽略非法媒体 id，不删除磁盘文件: $id")
            return
        }
        try {
            File(dir, "$id.meta").delete()
            File(dir, "$id.b64").delete()
        } catch (_: Exception) {
        }
    }

    fun preloadFromDisk() {
        val dir = cacheDir ?: return
        try {
            val metaFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".meta") } ?: return
            metaFiles.sortedByDescending { it.lastModified() }.take(maxPoolSize).forEach { metaFile ->
                val id = metaFile.name.removeSuffix(".meta")
                if (dir != cacheDir) return
                getMedia(id)
            }
            AppLogger.d(TAG, "从磁盘加载媒体缓存完成: size=${synchronized(this) { mediaPool.size }}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "从磁盘加载媒体缓存失败", e)
        }
    }
}
