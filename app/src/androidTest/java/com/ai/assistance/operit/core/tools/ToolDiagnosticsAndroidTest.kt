package com.ai.assistance.operit.core.tools

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardFileSystemTools
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.ToolErrorExportHelper
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run only this class via am instrument; every database/input file is test-owned. */
@RunWith(AndroidJUnit4::class)
class ToolDiagnosticsAndroidTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var repository: ToolErrorRepository

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "tool-diagnostics-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }
        repository = ToolErrorRepository(context)
    }

    @After fun tearDown(): Unit = runBlocking {
        repository.close()
        // Only the UUID directory created by this fixture; never a formal database or preference.
        assertTrue(directory.name.startsWith("tool-diagnostics-test-"))
        directory.deleteRecursively()
    }

    @Test fun realReadFileRecordsIgnoredParametersAndRealMissingFileError() = runBlocking {
        val input = File(context.filesDir, "fixture.txt").apply { writeText("first line\nsecond line\nthird line\n") }
        val tools = StandardFileSystemTools(context)
        val declaration = SystemToolPrompts.fileSystemTools.tools.single { it.name == "read_file" }
            .parametersStructured!!.map { it.name }.toSet()
        val executor = object : ToolExecutor {
            override fun parameterNames(tool: AITool) = declaration
            override fun invoke(tool: AITool): ToolResult = runBlocking { tools.readFile(tool) }
        }
        suspend fun execute(call: AITool, id: String): ToolResult {
            val observer = ToolParameterObservation(call, call) { throw AssertionError(it) }
            val invocation = ToolInvocation(call, "", 0..0, id, 0)
            val result = withContext(ToolExecutionManager.toolRuntimeContextElement(
                ToolExecutionManager.ToolRuntimeContext(parameterObserver = observer),
            )) { ToolExecutionManager.executeToolSafely(invocation, executor).last() }
            repository.record(requireNotNull(createToolErrorRecord(id, invocation, result, 100, observer.snapshot())))
            return result
        }
        val success = execute(AITool("read_file", listOf(
            ToolParameter("path", input.absolutePath), ToolParameter("startline", "2"), ToolParameter("endline", "2"),
        )), "ignored-lines")
        assertTrue(success.success)
        assertTrue(success.result.toString().contains("first line"))
        val failed = execute(AITool("read_file", listOf(ToolParameter("path", File(context.filesDir, "missing.txt").absolutePath))), "missing")
        assertFalse(failed.success)
        withTimeout(10_000) { repository.changes.first { it >= 2 } }
        val saved = repository.load(null, 0, 0)
        assertEquals(2L, saved.total)
        assertEquals(listOf("startline", "endline"), saved.records.single { it.id == "ignored-lines" }.undeclaredParameters)
        assertFalse(saved.records.single { it.id == "ignored-lines" }.executionFailed)
        assertEquals(failed.error, saved.records.single { it.id == "missing" }.error)
    }

    @Test fun sqlitePersistsDeduplicatesPaginatesAndExportsAllMatchingRecords() = runBlocking {
        val longError = "original error\n" + "原始错误".repeat(20_000)
        val parameters = listOf(ToolParameter("path", "  fixture<&\"\\\n  "), ToolParameter("path", "duplicate name"))
        repeat(65) { index ->
            val record = ToolErrorRecord("fixture-$index", 100L + index, "fixture:read_file", parameters,
                if (index == 0) longError else "fixture error $index", true, emptyList())
            repository.record(record)
            repository.record(record)
        }
        repository.record(ToolErrorRecord("other", 200, "fixture:other", emptyList(), "other error", true, emptyList()))
        withTimeout(10_000) { repository.changes.first { it >= 66 } }
        repository.close()
        repository = ToolErrorRepository(context)
        assertEquals(66L, repository.load(null, 0, 0).total)
        assertEquals(30, repository.load("fixture:read_file", 0, 0).records.size)
        assertEquals(5, repository.load("fixture:read_file", 0, 2).records.size)
        assertEquals(15L, repository.load("fixture:read_file", 150, 0).total)

        val allToolsExport = File(context.cacheDir, "all-tools.zip")
        allToolsExport.outputStream().use { repository.export(it, null, 0) }
        ZipFile(allToolsExport).use { zip ->
            val records = zip.getInputStream(zip.getEntry("errors.jsonl")).bufferedReader().readLines()
            assertEquals(66, records.size)
        }
        val exported = ToolErrorExportHelper.export(context, "fixture:read_file", 0, repository)
        val file = File(Environment.getExternalStorageDirectory(), exported)
        assertTrue(file.isFile)
        ZipFile(file).use { zip ->
            val summary = Json.decodeFromString<List<ToolErrorCount>>(zip.getInputStream(zip.getEntry("summary.json")).bufferedReader().readText())
            assertEquals(65L, summary.single().count)
            val records = zip.getInputStream(zip.getEntry("errors.jsonl")).bufferedReader().readLines()
                .map { Json.decodeFromString<ToolErrorRecord>(it) }
            assertEquals(65, records.size)
            assertTrue(records.all { it.toolName == "fixture:read_file" })
            assertEquals(longError, records.single { it.id == "fixture-0" }.error)
            assertEquals(parameters, records.single { it.id == "fixture-0" }.parameters)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nValidated fixture export: ${file.absolutePath}\n")
        })
    }
}
