package com.ai.assistance.operit.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Separate bounded files: large request logs and their writer queue cannot bury these records. */
internal class MemoryDiagnosticStore(private val directory: File) {
    @Synchronized
    fun append(text: String, incident: Boolean = false) {
        directory.mkdirs()
        val name = if (incident) "memory-incidents.log" else "memory-samples.log"
        val limit = if (incident) 64 * 1024 else 128 * 1024
        val file = File(directory, name)
        if (file.length() >= limit) {
            val previous = File(directory, "$name.previous")
            if (previous.exists() && !previous.delete()) error("Cannot rotate memory diagnostics")
            if (!file.renameTo(previous)) error("Cannot rotate memory diagnostics")
        }
        file.appendText(text + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun hasRecords(): Boolean = names.any { File(directory, it).length() > 0 }

    @Synchronized
    fun exportTo(zip: ZipOutputStream) {
        for (name in names) {
            val file = File(directory, name)
            if (!file.isFile || file.length() == 0L) continue
            zip.putNextEntry(ZipEntry("memory-diagnostics/$name"))
            file.inputStream().use { it.copyTo(zip, 8192) }
            zip.closeEntry()
        }
    }

    private companion object {
        val names = listOf("memory-samples.log.previous", "memory-samples.log",
            "memory-incidents.log.previous", "memory-incidents.log")
    }
}
