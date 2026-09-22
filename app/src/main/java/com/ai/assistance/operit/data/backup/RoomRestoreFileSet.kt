package com.ai.assistance.operit.data.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Cold-start-only replacement. The journal and old copies survive a killed process. */
internal class RoomRestoreFileSet(
    private val database: File,
    private val workspace: File,
    private val beforeReplace: (Int) -> Unit = {},
    private val spoolDirectory: File? = null,
    private val syncDirectory: (File) -> Unit = ::syncRestoreDirectory,
) {
    private val names = listOf(database.name, "${database.name}-wal", "${database.name}-shm")
    private val journal = File(workspace, "rollback-ready")
    val hasRollback: Boolean get() = journal.exists()

    fun replace() {
        check(!hasRollback) { "An interrupted database restore must be rolled back first" }
        val old = File(workspace, "old")
        check(old.mkdirs() || old.isDirectory)
        val existed = names.map { name ->
            val source = File(database.parentFile, name)
            if (source.exists()) durableCopy(source, File(old, name))
            source.exists()
        }
        val spoolExisted = spoolDirectory?.isDirectory == true
        if (spoolExisted) copyTree(checkNotNull(spoolDirectory), File(old, "spool"))
        syncDirectory(old)
        // A complete, durable journal is the only permission to touch the original files.
        val temporary = File(workspace, "rollback-ready.tmp")
        FileOutputStream(temporary).use { out ->
            out.write((existed + spoolExisted).joinToString("\n") { if (it) "1" else "0" }.toByteArray())
            out.fd.sync()
        }
        atomicMove(temporary, journal)
        names.forEachIndexed { index, name ->
            beforeReplace(index)
            install(File(workspace, name), File(database.parentFile, name))
        }
    }

    fun rollback() {
        val lines = journal.readLines()
        check(lines.size == names.size + 1 && lines.all { it == "0" || it == "1" }) {
            "Invalid database restore rollback journal"
        }
        check(lines.last() != "1" || File(workspace, "old/spool").isDirectory) {
            "Missing statistics rollback directory"
        }
        // Validate the whole source set before attempting any rollback.
        names.forEachIndexed { index, name ->
            check(lines[index] != "1" || File(workspace, "old/$name").isFile) {
                "Missing database rollback file: $name"
            }
        }
        names.forEachIndexed { index, name ->
            val target = File(database.parentFile, name)
            if (lines[index] == "1") install(File(workspace, "old/$name"), target)
            else {
                Files.deleteIfExists(target.toPath())
                syncDirectory(checkNotNull(target.parentFile))
            }
        }
        spoolDirectory?.let { target ->
            check(!target.exists() || target.deleteRecursively()) { "Cannot restore statistics spool" }
            if (lines.last() == "1") copyTree(File(workspace, "old/spool"), target)
            syncDirectory(checkNotNull(target.parentFile))
        }
        // Keep the sources/journal until the outer initialization gate is durably cleared.
    }

    private fun install(source: File, target: File) {
        if (!source.exists()) {
            Files.deleteIfExists(target.toPath())
            syncDirectory(checkNotNull(target.parentFile))
            return
        }
        val temporary = File(target.parentFile, "${target.name}.restore-install")
        durableCopy(source, temporary)
        atomicMove(temporary, target)
    }

    private fun durableCopy(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun atomicMove(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(checkNotNull(target.parentFile))
    }

    private fun copyTree(source: File, target: File) {
        check(target.mkdirs() || target.isDirectory)
        val children = checkNotNull(source.listFiles()) { "Cannot read rollback source" }
        children.forEach { child ->
            val destination = File(target, child.name)
            if (child.isDirectory) copyTree(child, destination) else durableCopy(child, destination)
        }
        syncDirectory(target)
    }
}

internal fun syncRestoreDirectory(directory: File) {
    val descriptor = android.system.Os.open(
        directory.absolutePath,
        android.system.OsConstants.O_RDONLY,
        0,
    )
    try {
        android.system.Os.fsync(descriptor)
    } finally {
        android.system.Os.close(descriptor)
    }
}
