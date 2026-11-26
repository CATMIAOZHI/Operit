package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.ai.assistance.operit.util.AppLogger
import com.cefrium.Cefrium
import com.cefrium.CefriumProfile
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val CEFRIUM_TAG = "CefriumEngine"
private const val HOT_RANGES_DIR = "cefrium/hot-ranges/"
private const val WARM_CHUNK_BYTES = 2L shl 20
private const val WARM_PROBE_BYTES = 16L shl 20
private const val WARM_MAX_BYTES = 64L shl 20
private const val WARM_CACHED_NANOS = 12_000_000L

/**
 * Cefrium, the Chromium the browser build carries. Each login set is a persistent profile: the
 * main app's is the engine's default one, an Orbit's has the Orbit's profile name. The engine
 * starts with the first tab, not with the app.
 */
internal object CefriumEngine : BrowserEngine {
    private val main = Handler(Looper.getMainLooper())
    private val libraryWarmed = AtomicBoolean(false)
    private val engineStarting = AtomicBoolean(false)
    private val engineStarted = AtomicBoolean(false)

    override val chromeFullVersion: String get() = Cefrium.getChromiumVersion()

    override val isStarted: Boolean
        get() = engineStarted.get() || runCatching { Cefrium.isInitialized() }.getOrDefault(false)

    override fun warmLibrary(context: Context) {
        if (engineStarting.get() || isStarted || !libraryWarmed.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching {
                    if (engineStarting.get() || isStarted) return@runCatching
                    val lib = File(app.applicationInfo.nativeLibraryDir, "libcef.so")
                    if (!lib.isFile) return@runCatching
                    val ranges =
                        runCatching {
                            readBuildId(lib)?.let { id ->
                                app.assets.open(HOT_RANGES_DIR + id + ".txt").use { stream ->
                                    parseHotRanges(lib.length(), id, stream)
                                }
                            }
                        }.getOrNull()
                    FileInputStream(lib).use { input ->
                        FileOutputStream("/dev/null").use { sink ->
                            val from = input.channel
                            val to = sink.channel
                            val total = from.size()
                            val plan = ranges ?: longArrayOf(0L, total)
                            var sent = 0L
                            var probing = true
                            val start = System.nanoTime()
                            var idx = 0
                            while (idx + 1 < plan.size && sent < WARM_MAX_BYTES && !engineStarting.get()) {
                                var pos = plan[idx]
                                val rangeEnd = plan[idx + 1]
                                while (pos < rangeEnd && sent < WARM_MAX_BYTES && !engineStarting.get()) {
                                    val remainingBudget = WARM_MAX_BYTES - sent
                                    val step =
                                        if (probing) {
                                            minOf(WARM_CHUNK_BYTES, WARM_PROBE_BYTES - sent, rangeEnd - pos, remainingBudget)
                                        } else {
                                            minOf(WARM_CHUNK_BYTES, rangeEnd - pos, remainingBudget)
                                        }
                                    if (step <= 0L) break
                                    val target = pos + step
                                    while (pos < target && !engineStarting.get()) {
                                        val n = from.transferTo(pos, target - pos, to)
                                        if (n <= 0L) return@runCatching
                                        pos += n
                                        sent += n
                                    }
                                    if (probing && sent >= WARM_PROBE_BYTES) {
                                        probing = false
                                        if (System.nanoTime() - start < WARM_CACHED_NANOS) return@runCatching
                                    }
                                }
                                idx += 2
                            }
                        }
                    }
                }.onFailure {
                    AppLogger.w(CEFRIUM_TAG, "Library prefetch skipped: ${it.message}")
                }
            },
            "CefriumWarm"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun readBuildId(elf: File): String? {
        RandomAccessFile(elf, "r").use { f ->
            val h = readLe(f, 0L, 64)
            if (h.getInt(0) != 0x464c457f || h.get(5).toInt() != 1) return null
            val is64 = when (h.get(4).toInt()) {
                2 -> true
                1 -> false
                else -> return null
            }
            val phoff = if (is64) h.getLong(0x20) else h.getInt(0x1c).toLong() and 0xffffffffL
            val phentsize = h.getShort(if (is64) 0x36 else 0x2a).toInt() and 0xffff
            val phnum = h.getShort(if (is64) 0x38 else 0x2c).toInt() and 0xffff
            for (i in 0 until phnum) {
                val ph = readLe(f, phoff + i.toLong() * phentsize, if (is64) 56 else 32)
                if (ph.getInt(0) != 4) continue // PT_NOTE
                val off = if (is64) ph.getLong(8) else ph.getInt(4).toLong() and 0xffffffffL
                val size = if (is64) ph.getLong(32) else ph.getInt(16).toLong() and 0xffffffffL
                val len = minOf(size, 1L shl 16).toInt()
                val notes = readLe(f, off, len)
                var p = 0
                while (p + 12 <= len) {
                    val namesz = notes.getInt(p)
                    val descsz = notes.getInt(p + 4)
                    val type = notes.getInt(p + 8)
                    val name = p + 12
                    val desc = name + ((namesz + 3) and 3.inv())
                    if (namesz < 0 || descsz < 0 || desc + descsz > len) break
                    if (type == 3 && namesz == 4 &&
                        notes.get(name) == 'G'.code.toByte() &&
                        notes.get(name + 1) == 'N'.code.toByte() &&
                        notes.get(name + 2) == 'U'.code.toByte()
                    ) {
                        val hex = StringBuilder(descsz * 2)
                        for (b in 0 until descsz) {
                            hex.append(String.format("%02x", notes.get(desc + b).toInt() and 0xff))
                        }
                        return hex.toString()
                    }
                    p = desc + ((descsz + 3) and 3.inv())
                }
            }
            return null
        }
    }

    private fun readLe(f: RandomAccessFile, pos: Long, len: Int): ByteBuffer {
        val b = ByteArray(len)
        f.seek(pos)
        f.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun parseHotRanges(size: Long, expectedId: String, stream: InputStream): LongArray? {
        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.US_ASCII))
        var out = LongArray(64)
        var n = 0
        var matched = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("build-id ")) {
                matched = expectedId == line.substring("build-id ".length).trim()
                if (!matched) return null
                continue
            }
            if (!matched) return null
            val parts = line.trim().split(' ')
            if (parts.size < 2) return null
            val start = parts[0].toLong()
            val end = parts[1].toLong()
            if (start < 0L || end <= start || end > size) return null
            if (n + 2 > out.size) out = out.copyOf(out.size * 2)
            out[n++] = start
            out[n++] = end
        }
        return if (matched && n > 0) out.copyOf(n) else null
    }

    fun profile(name: String): CefriumProfile =
        if (name == BrowserProfiles.MAIN) CefriumProfile.getDefault() else CefriumProfile.get(name)

    override fun createPage(context: Context, profileName: String, desktop: Boolean): EngineBrowserPage {
        engineStarting.set(true)
        return CefriumBrowserPage.create(context, profile(profileName), desktop)
            .also { engineStarted.set(true) }
    }

    override fun cookieFor(profileName: String, url: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        engineStarting.set(true)
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                profile(profileName).cookieManager.getCookie(url) { cookie ->
                    engineStarted.set(true)
                    result.set(cookie)
                    latch.countDown()
                }
            }.onFailure {
                AppLogger.w(CEFRIUM_TAG, "Cookies could not be read: ${it.message}")
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()?.takeIf { it.isNotBlank() }
    }

    override fun setCookies(profileName: String, url: String, setCookies: List<String>): Boolean {
        if (setCookies.isEmpty()) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        engineStarting.set(true)
        val latch = CountDownLatch(setCookies.size)
        val stored = AtomicInteger(0)
        main.post {
            setCookies.forEach { line ->
                runCatching {
                    profile(profileName).cookieManager.setCookie(url, line) { ok ->
                        engineStarted.set(true)
                        if (ok == true) stored.incrementAndGet()
                        latch.countDown()
                    }
                }.onFailure {
                    AppLogger.w(CEFRIUM_TAG, "A cookie could not be stored: ${it.message}")
                    latch.countDown()
                }
            }
        }
        return latch.await(3, TimeUnit.SECONDS) && stored.get() == setCookies.size
    }

    override fun clearLogins(profileName: String, onDone: (Boolean) -> Unit) {
        engineStarting.set(true)
        val profile = profile(profileName)
        profile.listSiteData { sites ->
            engineStarted.set(true)
            val left = AtomicInteger(sites.size + 1)
            val finish = {
                if (left.decrementAndGet() == 0) {
                    profile.cookieManager.flush { main.post { onDone(true) } }
                }
            }
            sites.forEach { site -> profile.clearSiteData(site.site) { finish() } }
            profile.cookieManager.removeAllCookies { finish() }
        }
    }

    override fun deleteProfile(profileName: String) {
        if (profileName == BrowserProfiles.MAIN) return
        engineStarting.set(true)
        // Deleted now, or at the next start when this run already used it.
        CefriumProfile.delete(profileName)
        engineStarted.set(true)
    }
}
