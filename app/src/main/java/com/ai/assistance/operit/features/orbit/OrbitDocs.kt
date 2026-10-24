package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The product docs Orbit reads about itself, in docs/ inside each Orbit's folder. They are the
 * app's built-in copy, written over the folder's copy once per app start (and when the language
 * changes), so an edit by anyone is undone and an app update always reaches them.
 */
object OrbitDocs {
    const val DIR = "docs"
    private const val TAG = "OrbitDocs"
    private const val ASSETS = "orbit_docs"

    // Orbit id -> language last written in this app run.
    private val refreshed = ConcurrentHashMap<String, Boolean>()

    /** Writes the built-in docs into the Orbit's folder, at most once per app run and language. */
    suspend fun ensure(context: Context, orbitId: String, english: Boolean) {
        if (refreshed[orbitId] == english) return
        withContext(Dispatchers.IO) {
            runCatching { write(context, orbitId, english) }
                .onSuccess { refreshed[orbitId] = english }
                .onFailure { Log.w(TAG, "Could not refresh docs for $orbitId", it) }
        }
    }

    private fun write(context: Context, orbitId: String, english: Boolean) {
        val source = "$ASSETS/" + if (english) "en" else "zh"
        val names = context.assets.list(source).orEmpty().filter { it.endsWith(".md") }.toSet()
        check(names.isNotEmpty()) { "No built-in docs in $source" }
        val root = OrbitPreferences.getInstance(context).orbitDir(orbitId)
        check(root.mkdirs() || root.isDirectory)
        val target = File(root, DIR).toPath()
        // docs/ must be a real folder inside Orbit's folder: a link or a file in its place is
        // removed itself, never followed.
        if (Files.exists(target, NOFOLLOW) && !Files.isDirectory(target, NOFOLLOW)) Files.delete(target)
        Files.createDirectories(target)
        check(target.toRealPath().startsWith(root.toPath().toRealPath())) { "docs/ is outside Orbit's folder" }
        // Anything that is not one of the built-in files goes, so the folder matches the app.
        Files.list(target).use { children ->
            // Snapshot first: deleting while the directory stream is open would skip entries.
            children.collect(java.util.stream.Collectors.toList()).forEach { child ->
                if (child.fileName.toString() !in names || !Files.isRegularFile(child, NOFOLLOW)) deleteNoFollow(child)
            }
        }
        names.forEach { name ->
            val body = context.assets.open("$source/$name").use { it.readBytes() }
            val file = target.resolve(name)
            if (Files.isRegularFile(file, NOFOLLOW) && Files.readAllBytes(file).contentEquals(body)) return@forEach
            // Written beside it and moved in, which replaces the entry instead of writing through it.
            val temp = Files.createTempFile(target, ".doc-", ".tmp")
            try {
                Files.write(temp, body)
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    /** Deletes a file or folder without following links inside it. */
    private fun deleteNoFollow(start: Path) {
        if (!Files.isDirectory(start, NOFOLLOW)) { Files.deleteIfExists(start); return }
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                FileVisitResult.CONTINUE.also { Files.delete(file) }
            override fun postVisitDirectory(dir: Path, e: java.io.IOException?) =
                FileVisitResult.CONTINUE.also { Files.delete(dir) }
        })
    }

    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS
}
