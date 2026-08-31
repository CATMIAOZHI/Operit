package com.ai.assistance.operit.features.reading

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-source contracts for the manual-only generation boundary.  These checks keep accidental
 * reintroduction of the old direct-summary/background scheduling paths visible without requiring
 * a live Legado provider or an API model.
 */
class ReadingCompanionManualFlowStaticTest {
    private fun source(relativePath: String): String = File(relativePath).readText()

    @Test
    fun `automatic indexing has no summary model call or knowledge-only rescheduling`() {
        val service =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionService.kt",
            )
        assertFalse(service.contains("modelGateway.summarizeChapter("))
        assertFalse(
            service.contains("scheduleMore && (remainingText > 0 || remainingKnowledge > 0)"),
        )
        assertTrue(service.contains("if (scheduleMore && remainingText > 0)"))
    }

    @Test
    fun `bridge defaults chapter summary lookup to no generation`() {
        val bridge =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionBridge.kt",
            )
        assertTrue(bridge.contains("optBoolean(\"generate_if_missing\", false)"))
        assertTrue(bridge.contains("\"manual_batch_summaries\""))
        assertTrue(bridge.contains("\"auto_commentary_manual_batch\""))
    }

    @Test
    fun `summary-only subagent path has a dedicated terminal submit`() {
        val coordinator =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentCoordinator.kt",
            )
        val tools =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentTools.kt",
            )
        val toolExecutionManager =
            source(
                "src/main/java/com/ai/assistance/operit/api/chat/enhance/" +
                    "ToolExecutionManager.kt",
            )
        assertTrue(coordinator.contains("summaryOnly"))
        assertTrue(coordinator.contains("TOOL_SUBMIT_SUMMARY"))
        assertTrue(tools.contains("session.summaryOnly"))
        assertTrue(tools.contains("\"summary_submitted\""))
        assertTrue(
            toolExecutionManager.contains(
                "!readingSession.summaryOnly &&\n" +
                    "                    !readingSession.backend.heartbeatClaimIfOwned(",
            ),
        )
    }

    @Test
    fun `manual summary UI tools are registered end to end`() {
        val bridge =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionBridge.kt",
            )
        val packageSource = source("../examples/reading_companion/packages/reading_companion.js")
        val entryUi =
            source("../examples/reading_companion/ui/reading_companion_entry/index.ui.js")

        listOf("summary_batch_prefs", "cancel_manual_summary_batch").forEach { action ->
            assertTrue("Bridge 缺少 $action", bridge.contains("\"$action\""))
            assertTrue("ToolPkg 缺少 $action 声明", packageSource.contains("\"name\": \"$action\""))
            assertTrue("ToolPkg 缺少 $action 导出", packageSource.contains("exports.$action"))
            assertTrue("入口页未调用 $action", entryUi.contains("\"$action\""))
        }
        assertFalse(entryUi.contains("\"summary_batch_stats\""))
        assertTrue(entryUi.contains("const iterations = 1"))
        assertTrue(entryUi.contains("count: callCount"))
        assertTrue(packageSource.contains("\"name\": \"batch_id\""))
        assertTrue(packageSource.contains("batch_id: params && params.batch_id"))
        assertTrue(entryUi.contains("{ batch_id: targetBatchId }"))
    }

    @Test
    fun `manual read commentary scope is wired end to end`() {
        val bridge =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionBridge.kt",
            )
        val commentary =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionAutoCommentary.kt",
            )
        val packageSource = source("../examples/reading_companion/packages/reading_companion.js")
        val entryUi =
            source("../examples/reading_companion/ui/reading_companion_entry/index.ui.js")

        assertTrue(bridge.contains(".optString("))
        assertTrue(bridge.contains("\"scope\""))
        assertTrue(commentary.contains("allowHistoricalTarget = historical"))
        assertTrue(commentary.contains("expectedManualBookId = state.book.id"))
        assertTrue(commentary.contains("expectedManualTargetSourceId = targetChapter.sourceId"))
        assertTrue(commentary.contains("manualCommentaryAnchorMatches("))
        assertTrue(commentary.contains("requestManualCommentaryBatchStop("))
        assertTrue(commentary.contains("supersededChapterIndex = chapterIndex"))
        assertTrue(commentary.contains("supersededChapterIndex != null -> STATUS_SUPERSEDED"))
        assertTrue(bridge.contains("\"cancel_manual_commentary_batch\""))
        assertTrue(commentary.contains("chapterIndex in 0 until currentChapterIndex"))
        assertTrue(packageSource.contains("scope: params.scope"))
        assertTrue(packageSource.contains("book_id: params.book_id"))
        assertTrue(packageSource.contains("batch_id: params.batch_id"))
        assertTrue(entryUi.contains("runManualBatch(\"comments\", null, \"read\")"))
        assertTrue(entryUi.contains("runManualBatch(\"comments\", null, \"ahead\")"))
        assertTrue(entryUi.contains("\"cancel_manual_commentary_batch\""))
        assertTrue(entryUi.contains("text.batchSuperseded"))
        assertTrue(entryUi.contains("commentaryScope === \"read\""))
        assertTrue(entryUi.contains("start === null || end === null"))
        assertTrue(entryUi.contains("isHistoricalComments"))
        assertTrue(entryUi.contains("isHistoricalComments\n        ? \"10\""))
        assertTrue(entryUi.contains("readRangeCount > 0"))
        assertTrue(entryUi.contains("commentCount <= 10"))
        assertTrue(
            bridge.contains(
                "if (scope == MANUAL_COMMENTARY_SCOPE_READ)",
            ),
        )
        assertTrue(
            commentary.contains(
                "require(startChapterIndex != null && endChapterIndex != null)",
            ),
        )
        assertTrue(
            packageSource.contains(
                "scope=read 时必填，ahead 时可省略",
            ),
        )
        assertTrue(
            packageSource.contains(
                "read 时可省略并固定每次最多处理 10 个缺失章节",
            ),
        )
    }

    @Test
    fun `manual batch gate is ownership based and releases immediately`() {
        val gate =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ManualBatchGate.kt",
            )
        assertTrue(gate.contains("fun acquire("))
        assertTrue(gate.contains("fun release("))
        assertFalse(gate.contains("ACTIVE_WINDOW_MS"))
        assertFalse(gate.contains("fun touch("))
    }

    @Test
    fun `manual summary scan is stoppable and does not retain chapter bodies`() {
        val service =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionService.kt",
            )
        assertTrue(service.contains("val candidates: List<ReaderChapter>"))
        assertFalse(
            service.contains(
                "val candidates: List<Pair<ReaderChapter, ReadableChapterContent>>",
            ),
        )
        assertTrue(service.contains("if (manualSummaryStopRequested)"))
        assertTrue(service.contains("activeManualSummaryBatchId != batchId"))
        assertTrue(service.contains(".put(\"scanComplete\", !scan.stopped)"))
    }

    @Test
    fun `official Legado release provider is preferred over debug`() {
        val provider =
            source(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "LegadoReaderProvider.kt",
            )
        val releaseAuthority = "com.legado.app.release.readerProvider"
        val debugAuthority = "com.legado.app.debug.readerProvider"
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(provider.contains(releaseAuthority))
        assertTrue(provider.indexOf(releaseAuthority) < provider.indexOf(debugAuthority))
        assertTrue(manifest.contains("android:authorities=\"$releaseAuthority\""))
        assertFalse(provider.contains("io.legado.app.release.readerProvider"))
        assertFalse(manifest.contains("io.legado.app.release.readerProvider"))
        assertTrue(provider.contains("LegadoAuthoritySupport.selectInstalled"))
        assertFalse(provider.contains("for (authority in installedAuthorities)"))
    }
}
