package com.ai.assistance.operit.features.orbit

import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The words of a voice message, kept beside its recording when Orbit makes it, for "to text" on
 * the voice bar: `files/voice/.text/<file name>.txt`. A dot folder never shows in the Library or to
 * the file tools. The words name the recording's size and content hash, so they are only shown for
 * that very recording (not a later file of the same name), and still after a backup is restored,
 * which rewrites the file's time; deleting the recording deletes them too.
 *
 * Every call names the folder the recording must really be in ([within], links resolved), and the
 * words folder and file must not be links, so nothing here reads, writes or deletes outside it.
 */
object OrbitVoiceText {
    private const val FOLDER = ".text"
    /** Far more than the longest words a recording can have (6000 characters). */
    private const val MAX_BYTES = 128 * 1024L

    private val forgottenCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Counts recordings deleted, so words shown for one can be taken away (see [forget]). */
    val forgotten: kotlinx.coroutines.flow.StateFlow<Int> = forgottenCount

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /**
     * The words file for [audio], or null when [audio] is not one of the recordings in a real
     * `files/voice/` folder inside [within], or a link sits on the way to its words.
     */
    private fun wordsFor(audio: File, within: File): File? {
        val folder = audio.parentFile ?: return null
        if (folder.name != "voice" || folder.parentFile?.name != "files") return null
        if (isLink(audio) || isLink(folder)) return null
        val base: Path = within.toPath().toRealPath()
        if (!folder.toPath().toRealPath().startsWith(base)) return null
        val words = File(folder, FOLDER)
        if (isLink(words)) return null
        val target = File(words, "${audio.name}.txt")
        return if (isLink(target)) null else target
    }

    /** A new SHA-256 digest, for [OrbitFiles.saveBytes] to fill as it writes a recording. */
    internal fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    /** The hash of [f], or null when [alive] turns false before it is read to the end. */
    private fun hashOf(f: File, alive: () -> Boolean): String? {
        val digest = digest()
        f.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (!alive()) return null
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest)
    }

    /**
     * Keeps [text] for [audio], whose bytes [digest] was filled with as it was written (called by
     * [OrbitFiles.saveBytes] under its lock, so the file is not read again). A failure is ignored: the voice still plays.
     */
    internal fun save(audio: File, text: String, within: File, digest: MessageDigest) {
        runCatching {
            if (!audio.isFile) return
            val target = wordsFor(audio, within) ?: return
            target.parentFile?.mkdirs()
            target.writeText(
                JSONObject().put("size", audio.length()).put("sha256", hex(digest)).put("text", text.trim()).toString()
            )
        }
    }

    /** Drops the words kept for [audio], when it is deleted (called by [OrbitFiles] under its lock). */
    internal fun forget(audio: File, within: File) {
        val gone = runCatching { wordsFor(audio, within)?.let { Files.deleteIfExists(it.toPath()) } }.getOrNull() == true
        // Only a recording that had words can have them on screen.
        if (gone) forgottenCount.update { it + 1 }
    }

    /** A folder holding recordings ([folder], short path) was deleted whole, words and all. */
    internal fun forgetFolder(folder: String) {
        val p = folder.trimEnd('/')
        if (p == "files" || p == "files/voice" || p.startsWith("files/voice/")) forgottenCount.update { it + 1 }
    }

    /** The words for [lines] of a podcast, one speaker's line a row; a single voice is just its text. */
    fun of(lines: List<Pair<String, String>>): String =
        if (lines.size == 1) lines[0].second.trim()
        else lines.joinToString("\n") { (speaker, text) -> "$speaker：${text.trim()}" }

    /**
     * The words kept for the recording at [url] (a `file://` url or a plain path) inside [within]
     * (the app's files); null when there are none. Reads the recording once to check it is the same,
     * and stops (null) when [alive] turns false.
     */
    fun read(url: String, within: File, alive: () -> Boolean = { true }): String? {
        val audio = fileOf(url) ?: return null
        return runCatching {
            if (!audio.isFile) return null
            val text = wordsFor(audio, within) ?: return null
            if (!text.isFile || text.length() > MAX_BYTES) return null
            val kept = JSONObject(text.readText())
            // The words of an earlier recording that had this name say nothing about this one.
            if (kept.optLong("size", -1) != audio.length() || kept.optString("sha256") != hashOf(audio, alive)) return null
            kept.optString("text").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * A cheap mark of the recording at [url] inside [within] and of its words (sizes and change
     * times), with the same folder and link checks as [read]; null when either is gone. Words already
     * checked are shown again without reading the recording while the mark is the same. Orbit never
     * rewrites a recording in place (a new one takes a new name), so a changed recording changes its time.
     */
    fun stamp(url: String, within: File): String? = runCatching {
        val audio = fileOf(url)?.takeIf { it.isFile } ?: return null
        val words = wordsFor(audio, within)?.takeIf { it.isFile } ?: return null
        "${audio.length()}:${audio.lastModified()}:${words.length()}:${words.lastModified()}"
    }.getOrNull()

    private fun fileOf(url: String): File? {
        val raw = url.substringBefore('#').substringBefore('?').trim()
        val path = when {
            raw.startsWith("file://") -> raw.removePrefix("file://")
            raw.startsWith("/") -> raw
            else -> return null
        }
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        return File(decoded)
    }
}
