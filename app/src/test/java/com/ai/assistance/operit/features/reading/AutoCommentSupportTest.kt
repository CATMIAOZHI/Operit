package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCommentSupportTest {

    @Test
    fun `replacement refuses empty list so published comments survive`() {
        val oldComments = listOf(
            AutoCommentRecord(
                bookId = "book",
                chapterIndex = 1,
                paragraphIndex = 5,
                text = "坏了",
                kind = "reaction",
                roleCardId = "rainy",
                roleCardName = "Rainy",
                evidenceJson = """{"paragraphs":[5],"quote":""}""",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AutoCommentSupport.requireReplacementComments(emptyList())
        }

        assertEquals(oldComments, AutoCommentSupport.requireReplacementComments(oldComments))
    }

    @Test
    fun `empty model output never passes validation`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")

        val comments = AutoCommentSupport.parseAndValidate(
            rawJson = """{"comments":[]}""",
            paragraphs = paragraphs,
            maximumComments = 6,
        )

        assertEquals(0, comments.size)
    }

    @Test
    fun `manual enqueue rejects when latest run is still generating`() {
        assertEquals(
            true,
            AutoCommentSupport.shouldRejectManualEnqueue(
                latestRunStatus = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING,
                activeGenerationRunning = false,
            ),
        )
    }

    @Test
    fun `manual enqueue rejects when latest run finished but an active generation still runs`() {
        // 并发顺序：任务 A 仍在生成并持有 claim；任务 B 更晚启动，在 claim 阶段发现
        // A 占用后立刻以 already_generating 结束，按 started_at 排序排在 A 前面。
        // 只检查最新 run（已结束的 B）会漏报，必须同时检查是否存在活动生成。
        assertEquals(
            true,
            AutoCommentSupport.shouldRejectManualEnqueue(
                latestRunStatus = "already_generating",
                activeGenerationRunning = true,
            ),
        )
    }

    @Test
    fun `manual enqueue allows when no generation is active`() {
        assertEquals(
            false,
            AutoCommentSupport.shouldRejectManualEnqueue(
                latestRunStatus = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATED,
                activeGenerationRunning = false,
            ),
        )
    }

    @Test
    fun `malformed anchors and late evidence are filtered out`() {
        val paragraphs = listOf("第一段", "第二段", "第三段真相")
        val json =
            """
            {
              "comments": [
                {
                  "anchorId": "p0001",
                  "evidenceIds": ["p0001"],
                  "evidenceQuote": "第一段",
                  "text": "好",
                  "kind": "reaction"
                },
                {
                  "anchorId": "p0099",
                  "evidenceIds": ["p0099"],
                  "evidenceQuote": "",
                  "text": "越界",
                  "kind": "reaction"
                },
                {
                  "anchorId": "p0002",
                  "evidenceIds": ["p0003"],
                  "evidenceQuote": "真相",
                  "text": "提前挂载",
                  "kind": "analysis"
                },
                {
                  "anchorId": "p0003",
                  "evidenceIds": ["p0003"],
                  "evidenceQuote": "不存在",
                  "text": "引文不匹配",
                  "kind": "reaction"
                }
              ]
            }
            """.trimIndent()

        val comments = AutoCommentSupport.parseAndValidate(json, paragraphs, 6)

        assertEquals(1, comments.size)
        assertEquals(1, comments.single().paragraphIndex)
        assertEquals("好", comments.single().text)
    }

    @Test
    fun `previous context keeps nearest chapters and returns chronological order`() {
        val selected = AutoCommentSupport.selectPreviousContext(
            chaptersNearestFirst = listOf(
                annotationChapter(index = 3, title = "第四章", content = "4444"),
                annotationChapter(index = 2, title = "第三章", content = "123456"),
                annotationChapter(index = 1, title = "第二章", content = "不应出现"),
            ),
            maximumCharacters = 7,
        )

        assertEquals(listOf(2, 3), selected.map(AutoCommentContextChapter::chapterIndex))
        assertEquals("456", selected.first().content)
        assertEquals(true, selected.first().excerptFromEnd)
        assertEquals("4444", selected.last().content)
        assertEquals(false, selected.last().excerptFromEnd)
    }

    @Test
    fun `runtime context trimming keeps nearest chapters and tail excerpts`() {
        val selected = AutoCommentSupport.trimPreviousContext(
            chaptersChronological = listOf(
                contextChapter(index = 1, content = "不应出现"),
                contextChapter(index = 2, content = "123456"),
                contextChapter(index = 3, content = "4444"),
            ),
            maximumCharacters = 7,
        )

        assertEquals(listOf(2, 3), selected.map(AutoCommentContextChapter::chapterIndex))
        assertEquals("456", selected.first().content)
        assertEquals(true, selected.first().excerptFromEnd)
        assertEquals("4444", selected.last().content)
    }

    @Test
    fun `previous context freshness ignores provider edge whitespace`() {
        val full =
            contextChapter(
                index = 2,
                content = "稳定正文",
            )
        val tail =
            contextChapter(
                index = 3,
                content = "最后一段",
            ).copy(excerptFromEnd = true)

        assertTrue(
            AutoCommentSupport.previousContextStillMatches(
                latestContent = "\n稳定正文\r\n",
                captured = full,
            ),
        )
        assertTrue(
            AutoCommentSupport.previousContextStillMatches(
                latestContent = "前文\n最后一段\n\n",
                captured = tail,
            ),
        )
        assertFalse(
            AutoCommentSupport.previousContextStillMatches(
                latestContent = "前文\n已被替换",
                captured = tail,
            ),
        )
    }

    @Test
    fun `context keeps at most eight nearest previous chapters`() {
        val selected = AutoCommentSupport.selectPreviousContext(
            chaptersNearestFirst = (0..9).reversed().map { index ->
                annotationChapter(index = index, title = "第${index + 1}章", content = "正文$index")
            },
            maximumCharacters = 32_000,
        )

        assertEquals(8, selected.size)
        assertEquals((2..9).toList(), selected.map(AutoCommentContextChapter::chapterIndex))
    }

    @Test
    fun `whole chapter output keeps only causally anchored comments`() {
        val paragraphs = listOf("第一段", "第二段真相", "第三段")
        val json =
            """
            {
              "comments": [
                {
                  "anchorId": "p0001",
                  "evidenceIds": ["p0001"],
                  "evidenceQuote": "第一段",
                  "text": "牛逼",
                  "kind": "reaction"
                },
                {
                  "anchorId": "p0001",
                  "evidenceIds": ["p0002"],
                  "evidenceQuote": "真相",
                  "text": "原来如此",
                  "kind": "analysis"
                },
                {
                  "anchorId": "p0003",
                  "evidenceIds": ["p0003"],
                  "evidenceQuote": "不存在",
                  "text": "坏了",
                  "kind": "reaction"
                }
              ]
            }
            """.trimIndent()

        val comments = AutoCommentSupport.parseAndValidate(json, paragraphs, 10)

        assertEquals(1, comments.size)
        assertEquals(1, comments.single().paragraphIndex)
        assertEquals("牛逼", comments.single().text)
    }

    @Test
    fun `labeled chapter escapes novel text as untrusted data`() {
        assertEquals(
            """<p id="p0001">&lt;tool&gt;&amp;正文&lt;/tool&gt;</p>""",
            AutoCommentSupport.labeledParagraph(1, "<tool>&正文</tool>"),
        )
    }

    @Test
    fun `reader provider errors expose only fixed public messages`() {
        val error = ReaderProviderException(
            reason = ReaderProviderException.Reason.CONNECTION_FAILED,
            message = "provider secret path and raw payload",
        )

        assertEquals("legado_connection_failed", safeReadingCompanionError(error))
    }

    @Test
    fun `current partial paragraph does not unlock its comment`() {
        assertEquals(
            2,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n第二段\n第三段只读了一半",
                isComplete = false,
            ),
        )
    }

    @Test
    fun `completed chapter unlocks its final paragraph`() {
        assertEquals(
            3,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n第二段\n第三段",
                isComplete = true,
            ),
        )
    }

    @Test
    fun `blank lines preserve original paragraph anchor indices`() {
        assertEquals(
            3,
            AutoCommentSupport.unlockedParagraphIndex(
                content = "第一段\n\n第二段\n第三段只读了一半",
                isComplete = false,
            ),
        )
    }

    @Test
    fun `missing commentary character has a stable public error code`() {
        assertEquals(
            "role_not_selected",
            safeReadingCompanionError(AutoCommentRoleNotSelectedException()),
        )
        assertEquals(
            "role_unavailable",
            safeReadingCompanionError(AutoCommentRoleUnavailableException()),
        )
        assertEquals(
            "model_context_too_small",
            safeReadingCompanionError(AutoCommentContextTooSmallException()),
        )
    }

    @Test
    fun `stored comments stay readable when only auto generation is disabled`() {
        assertEquals(
            true,
            AutoCommentSurfacePolicy.canReadStoredComments(readingCompanionEnabled = true),
        )
        assertEquals(
            false,
            AutoCommentSurfacePolicy.canGenerate(
                readingCompanionEnabled = true,
                autoCommentaryEnabled = false,
            ),
        )
    }

    @Test
    fun `disabling the parent package hides the annotation surface`() {
        assertEquals(
            false,
            AutoCommentSurfacePolicy.canReadStoredComments(readingCompanionEnabled = false),
        )
        assertEquals(
            false,
            AutoCommentSurfacePolicy.canGenerate(
                readingCompanionEnabled = false,
                autoCommentaryEnabled = true,
            ),
        )
    }

    private fun annotationChapter(
        index: Int,
        title: String,
        content: String,
    ) = AnnotationChapterContent(
        bookId = "book",
        sourceId = "chapter-$index",
        chapterIndex = index,
        chapterTitle = title,
        content = content,
        contractHash = "hash-$index",
        capturedAt = 0L,
    )

    private fun contextChapter(
        index: Int,
        content: String,
    ) = AutoCommentContextChapter(
        sourceId = "chapter-$index",
        chapterIndex = index,
        chapterTitle = "第${index + 1}章",
        content = content,
        excerptFromEnd = false,
    )

    @Test
    fun `first missing prefetch chapter skips absent and covered chapters`() {
        val chapters = setOf(1, 2, 3, 4, 5, 6)

        assertEquals(
            3,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 1,
                prefetchAhead = 5,
                chapterIndices = chapters,
                isCovered = { it in setOf(2) },
            ),
        )

        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 1,
                prefetchAhead = 5,
                chapterIndices = chapters,
                isCovered = { it in setOf(2, 3, 4, 5, 6) },
            ),
        )
    }

    @Test
    fun `first missing prefetch chapter handles numbering gaps and empty books`() {
        val gapped = setOf(2, 4, 6)

        assertEquals(
            2,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 0,
                prefetchAhead = 5,
                chapterIndices = gapped,
                isCovered = { false },
            ),
        )

        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 8,
                prefetchAhead = 5,
                chapterIndices = gapped,
                isCovered = { false },
            ),
        )
    }

    @Test
    fun `prefetch ahead clamps to one to ten`() {
        assertEquals(1, AutoCommentSupport.clampPrefetchAheadChapters(0))
        assertEquals(5, AutoCommentSupport.clampPrefetchAheadChapters(5))
        assertEquals(10, AutoCommentSupport.clampPrefetchAheadChapters(99))
        assertEquals(
            AutoCommentSupport.DEFAULT_PREFETCH_AHEAD_CHAPTERS,
            5,
        )
    }

    @Test
    fun `manual commentary batch targets are unique and bounded`() {
        val targets =
            selectManualCommentaryTargets(
                currentChapterIndex = 0,
                upperChapterIndex = 8,
                availableChapterIndices = listOf(1, 1, 2, 3, 4, 5, 6, 7, 8),
                count = 4,
            )

        assertEquals(listOf(1, 2, 3, 4), targets)
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun `manual read commentary targets stop before current chapter`() {
        val targets =
            selectManualCommentaryTargets(
                currentChapterIndex = 5,
                upperChapterIndex = 8,
                availableChapterIndices = 0..8,
                count = 10,
                startChapterIndex = 2,
                scope = MANUAL_COMMENTARY_SCOPE_READ,
            )

        assertEquals(listOf(2, 3, 4), targets)
    }

    @Test
    fun `manual commentary target anchor rejects book and source replacement`() {
        assertTrue(
            manualCommentaryAnchorMatches(
                expectedBookId = "book-a",
                expectedSourceId = "source-a",
                actualBookId = "book-a",
                actualSourceId = "source-a",
            ),
        )
        assertFalse(
            manualCommentaryAnchorMatches(
                expectedBookId = "book-a",
                expectedSourceId = "source-a",
                actualBookId = "book-b",
                actualSourceId = "source-a",
            ),
        )
        assertFalse(
            manualCommentaryAnchorMatches(
                expectedBookId = "book-a",
                expectedSourceId = "source-a",
                actualBookId = "book-a",
                actualSourceId = "source-b",
            ),
        )
    }

    @Test
    fun `first missing prefetch chapter window is bounded by clamped ahead`() {
        // 只扫描到 current + MAX_PREFETCH_AHEAD_CHAPTERS，不越界扫全书。
        assertEquals(10, AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS)
        val chaptersOnlyOutsideClampedWindow = (11..20).toSet()
        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 0,
                prefetchAhead = 99,
                chapterIndices = chaptersOnlyOutsideClampedWindow,
                isCovered = { false },
            ),
        )
        assertEquals(
            5,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 0,
                prefetchAhead = 99,
                chapterIndices = (5..14).toSet(),
                isCovered = { false },
            ),
        )
    }

    @Test
    fun `first missing prefetch chapter skips chapters in retry cooldown`() {
        // 第3章已覆盖；第4章最近失败处于冷却；第5章缺失且未冷却 → 选第5章。
        assertEquals(
            5,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 2,
                prefetchAhead = 5,
                chapterIndices = setOf(3, 4, 5, 6),
                isCovered = { it == 3 },
                isCoolingDown = { it == 4 },
            ),
        )
        // 窗口内其余章节全部冷却 → 无目标，避免重复烧 Token。
        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 2,
                prefetchAhead = 5,
                chapterIndices = setOf(3, 4, 5),
                isCovered = { it == 3 },
                isCoolingDown = { it in setOf(4, 5) },
            ),
        )
    }

    @Test
    fun `failed chapter retry cooldown is fifteen minutes`() {
        assertEquals(15 * 60_000L, AutoCommentSupport.RETRY_FAILED_CHAPTER_AFTER_MS)
    }

    @Test
    fun `prefetch window is inclusive of current chapter plus clamped ahead`() {
        // 窗口上界 = current + ahead（含）；确保“清理边界跟随窗口”的语义明确：
        // current=4, ahead=3 → 保留到 7，覆盖记录 5/6/7 都不应被清理。
        for (chapterIndex in 5..7) {
            assertEquals(
                chapterIndex,
                AutoCommentSupport.firstMissingPrefetchChapter(
                    currentChapterIndex = 4,
                    prefetchAhead = 3,
                    chapterIndices = setOf(chapterIndex),
                    isCovered = { false },
                ),
            )
        }
        // 超出窗口的章节不在扫描范围（也不会被选中为生成目标）。
        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 4,
                prefetchAhead = 3,
                chapterIndices = setOf(8),
                isCovered = { false },
            ),
        )
    }

    @Test
    fun `minimum prefetch window retains next chapter coverage`() {
        // ahead 最小为 1：即使预取关闭到最小值，也只保留“下一章”的覆盖记录。
        assertEquals(
            5,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 4,
                prefetchAhead = 0,
                chapterIndices = setOf(5),
                isCovered = { false },
            ),
        )
        assertEquals(
            null,
            AutoCommentSupport.firstMissingPrefetchChapter(
                currentChapterIndex = 4,
                prefetchAhead = 0,
                chapterIndices = setOf(6),
                isCovered = { false },
            ),
        )
    }

    @Test
    fun `prefetch window upper index uses saturating addition`() {
        // 极端章节索引下窗口上界饱和到 Int.MAX_VALUE，不回绕成小数。
        assertEquals(
            Int.MAX_VALUE,
            AutoCommentSupport.prefetchWindowUpperIndex(
                currentChapterIndex = Int.MAX_VALUE - 1,
                prefetchAhead = 5,
            ),
        )
        assertEquals(
            Int.MAX_VALUE,
            AutoCommentSupport.prefetchWindowUpperIndex(
                currentChapterIndex = Int.MAX_VALUE,
                prefetchAhead = 5,
            ),
        )
        // 正常路径：current + ahead（含）。
        assertEquals(
            7,
            AutoCommentSupport.prefetchWindowUpperIndex(
                currentChapterIndex = 4,
                prefetchAhead = 3,
            ),
        )
    }

    @Test
    fun `bare array payload is accepted`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")

        val report = AutoCommentSupport.parseAndValidateReport(
            rawJson =
                """
                [
                  {
                    "anchorId": "p0001",
                    "evidenceIds": ["p0001"],
                    "evidenceQuote": "第一段",
                    "text": "好",
                    "kind": "reaction"
                  }
                ]
                """.trimIndent(),
            paragraphs = paragraphs,
            maximumComments = 6,
        )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(1, report.accepted.size)
        assertEquals(1, report.accepted.single().paragraphIndex)
        assertEquals("好", report.accepted.single().text)
        assertEquals(0, report.rejectedCount)
        assertTrue(report.reasonCounts.isEmpty())
    }

    @Test
    fun `wrapped comments object stays compatible`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")

        val report = AutoCommentSupport.parseAndValidateReport(
            rawJson = """{"comments":[{"anchorId":"p0002","evidenceIds":["p0002"],"evidenceQuote":"第二段","text":"嗯","kind":"echo"}]}""",
            paragraphs = paragraphs,
            maximumComments = 6,
        )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(2, report.accepted.single().paragraphIndex)
        assertEquals("嗯", report.accepted.single().text)
    }

    @Test
    fun `malformed payloads report invalid json shape`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")

        val inputs =
            listOf(
                "",
                "   ",
                "not json at all",
                "\"just a string\"",
                "12345",
                """{"comments": "not an array"}""",
            )

        inputs.forEach { input ->
            val report = AutoCommentSupport.parseAndValidateReport(input, paragraphs, 6)
            assertEquals(
                AutoCommentSupport.REPORT_INVALID_JSON_SHAPE,
                report.code,
            )
            assertNull(report.inputCount)
            assertTrue(report.accepted.isEmpty())
            assertTrue(report.rejections.isEmpty())
        }
    }

    @Test
    fun `wrapped object without comments key reports invalid json shape`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson = """{"other":"field"}""",
                paragraphs = listOf("第一段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_INVALID_JSON_SHAPE, report.code)
    }

    @Test
    fun `empty array reports empty candidates`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson = "[]",
                paragraphs = listOf("第一段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_EMPTY_CANDIDATES, report.code)
        assertEquals(0, report.inputCount)
        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun `non object array items report invalid item shape`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson = """["not-an-object", 42, null]""",
                paragraphs = listOf("第一段", "第二段", "第三段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_ALL_CANDIDATES_REJECTED, report.code)
        assertEquals(3, report.inputCount)
        assertEquals(3, report.rejectedCount)
        assertEquals(3, report.reasonCounts[AutoCommentSupport.REASON_INVALID_ITEM_SHAPE])
        assertEquals(listOf("invalid_item_shape"), report.rejections.first().reasons)
    }

    @Test
    fun `out of range or malformed anchor reports invalid anchor`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {"anchorId": "p0099", "text": "越界", "kind": "reaction"},
                      {"anchorId": "x0001", "text": "格式错", "kind": "reaction"},
                      {"anchorId": "p0", "text": "短格式", "kind": "reaction"}
                    ]
                    """.trimIndent(),
                paragraphs = paragraphs,
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_ALL_CANDIDATES_REJECTED, report.code)
        assertEquals(3, report.reasonCounts[AutoCommentSupport.REASON_INVALID_ANCHOR])
        assertEquals(3, report.rejections.size)
    }

    @Test
    fun `duplicate anchor reports duplicate anchor`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {"anchorId": "p0001", "text": "第一条", "kind": "reaction"},
                      {"anchorId": "p0001", "text": "第二条", "kind": "reaction"}
                    ]
                    """.trimIndent(),
                paragraphs = listOf("第一段", "第二段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(1, report.accepted.size)
        assertEquals(1, report.rejections.size)
        assertEquals(listOf("duplicate_anchor"), report.rejections.single().reasons)
        assertEquals(1, report.reasonCounts[AutoCommentSupport.REASON_DUPLICATE_ANCHOR])
    }

    @Test
    fun `evidence without anchor paragraph reports missing anchor evidence`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {
                        "anchorId": "p0002",
                        "evidenceIds": ["p0001"],
                        "text": "缺锚点证据",
                        "kind": "reaction"
                      }
                    ]
                    """.trimIndent(),
                paragraphs = listOf("第一段", "第二段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_ALL_CANDIDATES_REJECTED, report.code)
        assertEquals(
            listOf(AutoCommentSupport.REASON_MISSING_ANCHOR_EVIDENCE),
            report.rejections.single().reasons,
        )
    }

    @Test
    fun `evidence past anchor reports evidence after anchor`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {
                        "anchorId": "p0002",
                        "evidenceIds": ["p0003"],
                        "text": "引用未读段落",
                        "kind": "reaction"
                      }
                    ]
                    """.trimIndent(),
                paragraphs = listOf("第一段", "第二段", "第三段"),
                maximumComments = 6,
            )

        assertEquals(
            listOf(AutoCommentSupport.REASON_EVIDENCE_AFTER_ANCHOR),
            report.rejections.single().reasons,
        )
    }

    @Test
    fun `quote missing from cited paragraph reports quote not found`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {
                        "anchorId": "p0001",
                        "evidenceIds": ["p0001"],
                        "evidenceQuote": "不存在的原文",
                        "text": "引文不匹配",
                        "kind": "reaction"
                      }
                    ]
                    """.trimIndent(),
                paragraphs = listOf("第一段", "第二段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_ALL_CANDIDATES_REJECTED, report.code)
        assertEquals(
            listOf(AutoCommentSupport.REASON_QUOTE_NOT_FOUND),
            report.rejections.single().reasons,
        )
    }

    @Test
    fun `empty and duplicate text are rejected with their own reasons`() {
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {"anchorId": "p0001", "text": "   ", "kind": "reaction"},
                      {"anchorId": "p0002", "text": "重复", "kind": "reaction"},
                      {"anchorId": "p0003", "text": "重复", "kind": "reaction"}
                    ]
                    """.trimIndent(),
                paragraphs = listOf("第一段", "第二段", "第三段"),
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(1, report.accepted.size)
        assertEquals("重复", report.accepted.single().text)
        assertEquals(1, report.reasonCounts[AutoCommentSupport.REASON_EMPTY_TEXT])
        assertEquals(1, report.reasonCounts[AutoCommentSupport.REASON_DUPLICATE_TEXT])
    }

    @Test
    fun `mixed valid and invalid candidates report accurate counts`() {
        val paragraphs = listOf("第一段", "第二段", "第三段真相")
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {
                        "anchorId": "p0001",
                        "evidenceIds": ["p0001"],
                        "evidenceQuote": "第一段",
                        "text": "好",
                        "kind": "reaction"
                      },
                      {
                        "anchorId": "p0099",
                        "evidenceIds": ["p0099"],
                        "text": "越界",
                        "kind": "reaction"
                      },
                      {
                        "anchorId": "p0003",
                        "evidenceIds": ["p0003"],
                        "evidenceQuote": "不存在",
                        "text": "引文不匹配",
                        "kind": "reaction"
                      }
                    ]
                    """.trimIndent(),
                paragraphs = paragraphs,
                maximumComments = 6,
            )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(3, report.inputCount)
        assertEquals(1, report.accepted.size)
        assertEquals(1, report.accepted.single().paragraphIndex)
        assertEquals(2, report.rejectedCount)
        assertEquals(1, report.reasonCounts[AutoCommentSupport.REASON_INVALID_ANCHOR])
        assertEquals(1, report.reasonCounts[AutoCommentSupport.REASON_QUOTE_NOT_FOUND])
        assertEquals(listOf(2, 3), report.rejections.map(CandidateRejection::candidateNumber))
    }

    @Test
    fun `caller maximum below global cap does not truncate accepted comments`() {
        val paragraphs = listOf("第一段", "第二段", "第三段", "第四段")
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {"anchorId":"p0001","text":"甲","kind":"reaction","evidenceIds":["p0001"],"evidenceQuote":"第一段"},
                      {"anchorId":"p0002","text":"乙","kind":"echo","evidenceIds":["p0002"],"evidenceQuote":"第二段"},
                      {"anchorId":"p0003","text":"丙","kind":"question","evidenceIds":["p0003"],"evidenceQuote":"第三段"},
                      {"anchorId":"p0004","text":"丁","kind":"reaction","evidenceIds":["p0004"],"evidenceQuote":"第四段"}
                    ]
                    """.trimIndent(),
                paragraphs = paragraphs,
                maximumComments = 3,
            )

        assertEquals(AutoCommentSupport.REPORT_SUBMITTED, report.code)
        assertEquals(4, report.accepted.size)
    }

    @Test
    fun `diagnostic report never leaks chapter or candidate content`() {
        val paragraphs = listOf("秘密第一段", "秘密第二段")
        val report =
            AutoCommentSupport.parseAndValidateReport(
                rawJson =
                    """
                    [
                      {
                        "anchorId": "p0001",
                        "evidenceIds": ["p0002"],
                        "evidenceQuote": "秘密第二段",
                        "text": "秘密候选正文",
                        "kind": "reaction"
                      }
                    ]
                    """.trimIndent(),
                paragraphs = paragraphs,
                maximumComments = 6,
            )

        val diagnosticText =
            buildString {
                append(report.code)
                append(report.rejections.joinToString { it.reasons.joinToString() })
                append(report.reasonCounts.keys.joinToString())
            }
        assertTrue(!diagnosticText.contains("秘密第一段"))
        assertTrue(!diagnosticText.contains("秘密第二段"))
        assertTrue(!diagnosticText.contains("秘密候选正文"))
        assertEquals(
            listOf(AutoCommentSupport.REASON_EVIDENCE_AFTER_ANCHOR),
            report.rejections.single().reasons,
        )
    }

    @Test
    fun `legacy parseAndValidate keeps object wrapping semantics`() {
        val paragraphs = listOf("第一段", "第二段", "第三段")

        val accepted =
            AutoCommentSupport.parseAndValidate(
                rawJson = """{"comments":[{"anchorId":"p0001","text":"好","kind":"reaction"}]}""",
                paragraphs = paragraphs,
                maximumComments = 6,
            )
        assertEquals(1, accepted.size)

        val wrappedWithoutComments: List<AutoCommentDraft> =
            AutoCommentSupport.parseAndValidate(
                rawJson = """{"other":"field"}""",
                paragraphs = paragraphs,
                maximumComments = 6,
            )
        assertEquals(emptyList<AutoCommentDraft>(), wrappedWithoutComments)

        val exception =
            assertThrows(
                org.json.JSONException::class.java,
            ) {
                AutoCommentSupport.parseAndValidate(
                    rawJson = "[]",
                    paragraphs = paragraphs,
                    maximumComments = 6,
                )
            }
        assertEquals(
            "auto comment payload must be a JSON object wrapping a comments array",
            exception.message,
        )
    }
}
