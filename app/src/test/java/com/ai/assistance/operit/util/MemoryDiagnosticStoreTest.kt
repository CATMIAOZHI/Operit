package com.ai.assistance.operit.util

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryDiagnosticStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rotationIsBoundedAndExportSurvivesAStoreRestart() {
        val dir = temp.newFolder()
        val store = MemoryDiagnosticStore(dir)
        repeat(2000) { store.append("sample=$it " + "0".repeat(200)) }
        repeat(1000) { store.append("incident=$it " + "0".repeat(200), true) }
        val reopened = MemoryDiagnosticStore(dir)
        assertTrue(reopened.hasRecords())
        assertEquals(4, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } < 400 * 1024)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { reopened.exportTo(it) }
        val entries = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(4, entries.size)
        assertTrue(entries.getValue("memory-diagnostics/memory-samples.log").contains("sample=1999"))
        assertTrue(entries.getValue("memory-diagnostics/memory-incidents.log").contains("incident=999"))
    }

    @Test fun emptyStoreExportsNoEntries() {
        val store = MemoryDiagnosticStore(temp.newFolder())
        assertFalse(store.hasRecords())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { store.exportTo(it) }
        ZipInputStream(output.toByteArray().inputStream()).use { assertNull(it.nextEntry) }
    }
}
