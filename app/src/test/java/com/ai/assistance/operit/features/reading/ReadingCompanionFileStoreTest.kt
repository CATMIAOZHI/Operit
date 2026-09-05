package com.ai.assistance.operit.features.reading

import android.content.Context
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ReadingCompanionFileStoreTest {
    private lateinit var root: File
    private lateinit var store: ReadingCompanionFileStore

    private val book = ReaderBook(
        id = "book-1",
        name = "测试书",
        author = "作者",
        totalChapterCount = 2,
        lastReadAt = 1L,
    )
    private val chapter = ReaderChapter(
        bookId = book.id,
        sourceId = "source-1",
        index = 0,
        title = "第一章",
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("reading-companion-file-store").toFile()
        store = ReadingCompanionFileStore(mock(Context::class.java), root)
        store.syncBookCatalog(book, listOf(chapter))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `content file is persisted with independent hash metadata and path`() {
        val content = "第一段\n第二段"
        store.writeChapterContent(book, chapter, content)

        val paths = store.chapterFilePaths(book.id, chapter.sourceId)
        assertNotNull(paths)
        assertEquals(
            File(paths!!.getString("chapterDirectory"), "content.md").absolutePath,
            paths.getString("contentPath"),
        )
        assertEquals(JSONObject.NULL, paths.get("summaryPath"))
        assertEquals(content, File(paths.getString("contentPath")).readText())

        val meta = JSONObject(File(paths.getString("metaPath")).readText())
        assertEquals(ReadingCompanionFileStore.contentHash(content), meta.getString("contentFileHash"))
        assertEquals(
            ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
            meta.getString("contentFileHashKind"),
        )
        assertFalse("仅保存正文不能伪造摘要 freshness hash", meta.has("contentHash"))
    }

    @Test
    fun `content refresh replaces body atomically without changing summary freshness contract`() {
        val firstContent = "旧正文"
        val secondContent = "净化替换后的正文"
        store.writeSummary(book, chapter, firstContent, "客观摘要")
        val firstMeta = store.chapterFilePaths(book.id, chapter.sourceId)
            ?.let { JSONObject(File(it.getString("metaPath")).readText()) }
            ?: error("meta missing")
        val firstSummaryHash = firstMeta.getString("contentHash")

        store.writeChapterContent(book, chapter, secondContent)

        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        assertEquals(secondContent, File(paths.getString("contentPath")).readText())
        val meta = JSONObject(File(paths.getString("metaPath")).readText())
        assertEquals(firstSummaryHash, meta.getString("contentHash"))
        assertEquals(
            ReadingCompanionFileStore.contentHash(secondContent),
            meta.getString("contentFileHash"),
        )
        assertEquals("客观摘要", store.readSummary(book.id, chapter.sourceId, firstSummaryHash))
        assertNull(
            "新的正文 hash 不应让旧摘要通过 freshness 校验",
            store.readSummary(
                book.id,
                chapter.sourceId,
                ReadingCompanionFileStore.contentHash(secondContent),
            ),
        )
    }

    @Test
    fun `verified legacy summary migration also persists fetched content`() {
        val content = "旧版本已经处理过的完整正文"
        val contentHash = ReadingCompanionFileStore.contentHash(content)
        val knowledge = StoredChapterKnowledge(
            bookId = book.id,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            sourceEndPosition = content.length,
            isComplete = true,
            contentHash = contentHash,
            summary = "旧版本摘要",
            structuredJson = "{}",
            keywords = "",
            updatedAt = 10L,
        )

        store.migrateLegacyChapter(
            book = book,
            chapter = chapter,
            knowledge = knowledge,
            autoChapter = null,
            comments = emptyList(),
            sourceContent = content,
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
        )

        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        assertEquals(content, File(paths.getString("contentPath")).readText())
        assertEquals("旧版本摘要", File(paths.getString("summaryPath")).readText().trim())
        val meta = JSONObject(File(paths.getString("metaPath")).readText())
        assertEquals(contentHash, meta.getString("contentFileHash"))
        assertEquals(contentHash, meta.getString("contentHash"))
    }

    @Test
    fun `pending legacy migration never downgrades newly generated chapter metadata`() {
        val currentContent = "当前正文"
        val currentComment = AutoCommentRecord(
            bookId = book.id,
            chapterIndex = chapter.index,
            paragraphIndex = 0,
            text = "当前段评",
            kind = "reaction",
            roleCardId = "role-current",
            roleCardName = "当前伴读",
            evidenceJson = "{}",
            createdAt = 20L,
        )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = currentContent,
            contentHash = ReadingCompanionFileStore.contentHash(currentContent),
            contractHash = "current-contract",
            roleCardId = "role-current",
            roleCardName = "当前伴读",
            summary = "当前摘要",
            comments = listOf(currentComment),
        )
        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        val metaBefore = File(paths.getString("metaPath")).readText()
        val summaryBefore = File(paths.getString("summaryPath")).readText()
        val commentsBefore = File(paths.getString("commentsPath")).readText()
        val legacyKnowledge = StoredChapterKnowledge(
            bookId = book.id,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            sourceEndPosition = 4,
            isComplete = true,
            contentHash = "legacy-hash",
            summary = "旧摘要",
            structuredJson = "{}",
            keywords = "",
            updatedAt = 10L,
        )

        store.migrateLegacyChapter(
            book = book,
            chapter = chapter,
            knowledge = legacyKnowledge,
            autoChapter = null,
            comments = emptyList(),
            sourceContent = currentContent,
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
        )

        assertEquals(metaBefore, File(paths.getString("metaPath")).readText())
        assertEquals(summaryBefore, File(paths.getString("summaryPath")).readText())
        assertEquals(commentsBefore, File(paths.getString("commentsPath")).readText())
        assertEquals(
            "current-contract",
            JSONObject(File(paths.getString("metaPath")).readText()).getString("contractHash"),
        )
        assertEquals(
            LegacyMigrationRequirements(summary = false, comments = false, content = false),
            store.legacyMigrationRequirements(
                bookId = book.id,
                sourceId = chapter.sourceId,
                chapterIndex = chapter.index,
                hasLegacySummary = true,
                hasLegacyComments = true,
                hasLegacyContentSource = true,
            ),
        )
    }

    @Test
    fun `legacy summary can fill missing component without invalidating current comments`() {
        val content = "同一份已验证正文"
        val currentComment = AutoCommentRecord(
            bookId = book.id,
            chapterIndex = chapter.index,
            paragraphIndex = 0,
            text = "当前段评",
            kind = "reaction",
            roleCardId = "role-current",
            roleCardName = "当前伴读",
            evidenceJson = "{}",
            createdAt = 20L,
        )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = "current-contract",
            roleCardId = "role-current",
            roleCardName = "当前伴读",
            summary = "",
            comments = listOf(currentComment),
            publishSummary = false,
        )
        val legacyKnowledge = StoredChapterKnowledge(
            bookId = book.id,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            sourceEndPosition = content.length,
            isComplete = true,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            summary = "可补迁的旧摘要",
            structuredJson = "{}",
            keywords = "",
            updatedAt = 10L,
        )

        store.migrateLegacyChapter(
            book = book,
            chapter = chapter,
            knowledge = legacyKnowledge,
            autoChapter = null,
            comments = emptyList(),
            sourceContent = content,
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
        )

        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        assertEquals("可补迁的旧摘要", File(paths.getString("summaryPath")).readText().trim())
        assertEquals(
            "current-contract",
            JSONObject(File(paths.getString("metaPath")).readText()).getString("contractHash"),
        )
        assertEquals(
            1,
            JSONObject(File(paths.getString("commentsPath")).readText())
                .getJSONArray("comments")
                .length(),
        )
    }

    @Test
    fun `generated annotation chapter stores source body and preserves file metadata`() {
        val content = "段落一\n段落二"
        val contractHash = ReadingCompanionFileStore.annotationContractHash(content)
        val comment = AutoCommentRecord(
            bookId = book.id,
            chapterIndex = chapter.index,
            paragraphIndex = 1,
            text = "有意思",
            kind = "reaction",
            roleCardId = "role-1",
            roleCardName = "伴读",
            evidenceJson = "{}",
            createdAt = 2L,
        )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = contractHash,
            roleCardId = "role-1",
            roleCardName = "伴读",
            summary = "目标章摘要",
            comments = listOf(comment),
        )

        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        assertTrue(File(paths.getString("contentPath")).isFile)
        assertEquals(content, File(paths.getString("contentPath")).readText())
        val meta = JSONObject(File(paths.getString("metaPath")).readText())
        assertEquals(
            ReadingCompanionFileStore.CONTENT_HASH_KIND_ANNOTATION,
            meta.getString("contentFileHashKind"),
        )
        assertEquals(
            ReadingCompanionFileStore.contentHash(content),
            meta.getString("contentFileHash"),
        )
        assertEquals(contractHash, meta.getString("contractHash"))
        val storedComment =
            JSONObject(File(paths.getString("commentsPath")).readText())
                .getJSONArray("comments")
                .getJSONObject(0)
        assertEquals(
            ReadingCompanionFileStore.paragraphFingerprint("段落一"),
            storedComment.getString(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD),
        )
        assertTrue(
            storedComment.getBoolean(
                ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_UNIQUE_FIELD,
            ),
        )
        assertEquals(
            ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION,
            storedComment.getString(
                ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION_FIELD,
            ),
        )
    }

    @Test
    fun `stale contract is exposed only for explicit fingerprint remapping`() {
        val content = "段落一\n段落二"
        val contractHash = ReadingCompanionFileStore.annotationContractHash(content)
        val comment =
            AutoCommentRecord(
                bookId = book.id,
                chapterIndex = chapter.index,
                paragraphIndex = 2,
                text = "第二段的段评",
                kind = "reaction",
                roleCardId = "role-1",
                roleCardName = "伴读",
                evidenceJson = "{}",
                createdAt = 2L,
            )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = contractHash,
            roleCardId = "role-1",
            roleCardName = "伴读",
            summary = "目标章摘要",
            comments = listOf(comment),
        )

        val legacyResult =
            store.readPublishedComments(
                bookId = book.id,
                chapterIndex = chapter.index,
                expectedContractHash = "changed-contract",
            ) ?: error("published comments missing")
        assertFalse(legacyResult.getBoolean("ready"))
        assertTrue(legacyResult.getBoolean("stale"))

        val remappable =
            store.readPublishedComments(
                bookId = book.id,
                chapterIndex = chapter.index,
                expectedContractHash = "changed-contract",
                allowStaleFingerprintMapping = true,
            ) ?: error("published comments missing")
        assertTrue(remappable.getBoolean("ready"))
        assertTrue(remappable.getBoolean("remapRequired"))
        assertEquals(contractHash, remappable.getString("contractHash"))
    }

    @Test
    fun `paragraph fingerprint matches the cross-app fixed vector`() {
        assertEquals(
            "208a07e6199994f4e9e1442ba9fc8202ae30a0ed619b338c2c277ba16a8b7b8e",
            ReadingCompanionFileStore.paragraphFingerprint("第一段"),
        )
    }

    @Test
    fun `old comments are fingerprinted before their source snapshot is refreshed`() {
        val content = "保留段\n将被净化的广告"
        val contractHash = ReadingCompanionFileStore.annotationContractHash(content)
        val comment =
            AutoCommentRecord(
                bookId = book.id,
                chapterIndex = chapter.index,
                paragraphIndex = 1,
                text = "保留段评",
                kind = "reaction",
                roleCardId = "role-1",
                roleCardName = "伴读",
                evidenceJson = "{}",
                createdAt = 2L,
            )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = contractHash,
            roleCardId = "role-1",
            roleCardName = "伴读",
            summary = "目标章摘要",
            comments = listOf(comment),
        )
        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        val commentsFile = File(paths.getString("commentsPath"))
        val oldRoot = JSONObject(commentsFile.readText())
        oldRoot.getJSONArray("comments").getJSONObject(0)
            .remove(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD)
        oldRoot.getJSONArray("comments").getJSONObject(0)
            .remove(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_UNIQUE_FIELD)
        oldRoot.getJSONArray("comments").getJSONObject(0)
            .remove(ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION_FIELD)
        commentsFile.writeText(oldRoot.toString(2))

        store.writeChapterContent(
            book = book,
            chapter = chapter,
            sourceContent = "保留段",
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_ANNOTATION,
        )

        val enriched =
            JSONObject(commentsFile.readText())
                .getJSONArray("comments")
                .getJSONObject(0)
        assertEquals(
            ReadingCompanionFileStore.paragraphFingerprint("保留段"),
            enriched.getString(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD),
        )
        assertEquals(
            "保留段",
            File(paths.getString("contentPath")).readText(),
        )
    }

    @Test
    fun `duplicate source paragraphs are marked ambiguous`() {
        val content = "相同段\n相同段"
        val contractHash = ReadingCompanionFileStore.annotationContractHash(content)
        val comment =
            AutoCommentRecord(
                bookId = book.id,
                chapterIndex = chapter.index,
                paragraphIndex = 1,
                text = "不应猜是哪一个相同段",
                kind = "reaction",
                roleCardId = "role-1",
                roleCardName = "伴读",
                evidenceJson = "{}",
                createdAt = 2L,
            )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = contractHash,
            roleCardId = "role-1",
            roleCardName = "伴读",
            summary = "目标章摘要",
            comments = listOf(comment),
        )

        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        val storedComment =
            JSONObject(File(paths.getString("commentsPath")).readText())
                .getJSONArray("comments")
                .getJSONObject(0)
        assertFalse(
            storedComment.getBoolean(
                ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_UNIQUE_FIELD,
            ),
        )
        val stale =
            store.readPublishedComments(
                bookId = book.id,
                chapterIndex = chapter.index,
                expectedContractHash = "changed-contract",
                allowStaleFingerprintMapping = true,
            ) ?: error("published comments missing")
        assertFalse(stale.getBoolean("ready"))
    }

    @Test
    fun `mismatched content snapshot cannot invent fingerprints for old comments`() {
        val content = "旧第一段\n旧第二段"
        val contractHash = ReadingCompanionFileStore.annotationContractHash(content)
        val comment =
            AutoCommentRecord(
                bookId = book.id,
                chapterIndex = chapter.index,
                paragraphIndex = 1,
                text = "旧段评",
                kind = "reaction",
                roleCardId = "role-1",
                roleCardName = "伴读",
                evidenceJson = "{}",
                createdAt = 2L,
            )
        store.writeGeneratedChapter(
            book = book,
            chapter = chapter,
            sourceContent = content,
            contentHash = ReadingCompanionFileStore.contentHash(content),
            contractHash = contractHash,
            roleCardId = "role-1",
            roleCardName = "伴读",
            summary = "目标章摘要",
            comments = listOf(comment),
        )
        val paths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        val commentsFile = File(paths.getString("commentsPath"))
        val oldRoot = JSONObject(commentsFile.readText())
        oldRoot.getJSONArray("comments").getJSONObject(0).apply {
            remove(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD)
            remove(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_UNIQUE_FIELD)
            remove(ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION_FIELD)
        }
        commentsFile.writeText(oldRoot.toString(2))
        File(paths.getString("contentPath")).writeText("已经变化、无法证明来源的正文")

        val stale =
            store.readPublishedComments(
                bookId = book.id,
                chapterIndex = chapter.index,
                expectedContractHash = "changed-contract",
                allowStaleFingerprintMapping = true,
            ) ?: error("published comments missing")

        assertFalse(stale.getBoolean("ready"))
        val untouched =
            JSONObject(commentsFile.readText())
                .getJSONArray("comments")
                .getJSONObject(0)
        assertFalse(untouched.has(ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD))
    }

    @Test
    fun `content-only chapter directory remains enumerable`() {
        val content = "只有正文文件"
        store.writeChapterContent(book, chapter, content)
        val firstPaths = store.chapterFilePaths(book.id, chapter.sourceId) ?: error("chapter missing")
        File(firstPaths.getString("metaPath")).delete()

        val paths = store.chapterFilePaths(book.id, chapter.sourceId)
        assertNotNull("只有 content.md 时仍应返回章节目录", paths)
        assertEquals(
            File(firstPaths.getString("contentPath")).absolutePath,
            paths!!.getString("contentPath"),
        )
        assertEquals(JSONObject.NULL, paths.get("metaPath"))
    }

    @Test
    fun `safe search paths exclude prefetched future chapters without listing every past chapter`() {
        val chapters =
            (0..101).map { index ->
                ReaderChapter(
                    bookId = book.id,
                    sourceId = "source-$index",
                    index = index,
                    title = "第${index + 1}章",
                )
            }
        store.syncBookCatalog(book.copy(totalChapterCount = chapters.size), chapters)
        listOf(0, 99, 100, 101).forEach { index ->
            store.writeChapterContent(book, chapters[index], "正文-$index")
        }

        val paths = store.safeChapterSearchPaths(book.id, chapters, beforeChapterIndex = 100)
        val returned = (0 until paths.length()).map(paths::getString)
        val firstGroupDirectory =
            File(store.chaptersRootPath(book.id), "0001-0100").absolutePath
        val currentPrefetchedDirectory =
            store.chapterFilePaths(book.id, chapters[100].sourceId)
                ?.getString("chapterDirectory")
                ?: error("current chapter missing")
        val futureChapterDirectory =
            store.chapterFilePaths(book.id, chapters[101].sourceId)
                ?.getString("chapterDirectory")
                ?: error("future chapter missing")

        assertEquals(listOf(firstGroupDirectory), returned)
        assertFalse(currentPrefetchedDirectory in returned)
        assertFalse(futureChapterDirectory in returned)
    }

    @Test
    fun `search paths ignore removed sources while catalog sync preserves their files`() {
        val removedChapter =
            ReaderChapter(book.id, "source-removed", 1, "被替换章节")
        store.syncBookCatalog(book, listOf(chapter, removedChapter))
        store.writeChapterContent(book, removedChapter, "净化前旧正文")
        val oldDirectory =
            store.chapterFilePaths(book.id, removedChapter.sourceId)
                ?.getString("chapterDirectory")
                ?: error("old chapter missing")

        val replacement =
            ReaderChapter(book.id, "source-replacement", 1, "替换后章节")
        store.syncBookCatalog(book, listOf(chapter, replacement))

        assertTrue(File(oldDirectory).isDirectory)
        assertEquals(
            "净化前旧正文",
            File(oldDirectory, ReadingCompanionFileStore.CONTENT_FILE_NAME).readText(),
        )
        val safePaths =
            store.safeChapterSearchPaths(book.id, listOf(chapter, replacement), 1)
        val allCurrentPaths =
            store.allCurrentChapterSearchPaths(book.id, listOf(chapter, replacement))
        listOf(safePaths, allCurrentPaths).forEach { searchPaths ->
            assertFalse(
                (0 until searchPaths.length())
                    .map(searchPaths::getString)
                    .any { path -> path == oldDirectory },
            )
        }

        store.syncBookCatalog(book, listOf(chapter, removedChapter))
        val restoredContentPath =
            store.chapterFilePaths(book.id, removedChapter.sourceId)
                ?.getString("contentPath")
                ?: error("re-added stable source was not restored")
        assertEquals("净化前旧正文", File(restoredContentPath).readText())
    }

    @Test
    fun `unchanged catalog sync does not rewrite book metadata or catalogs`() {
        val catalogPath = store.catalogPaths(book.id).getString(0)
        val bookMetadata = File(store.bookMetadataPath(book.id))
        val catalog = File(catalogPath)
        val sentinelTimestamp = 1_234_567_890L
        assertTrue(bookMetadata.setLastModified(sentinelTimestamp))
        assertTrue(catalog.setLastModified(sentinelTimestamp))

        store.syncBookCatalog(book, listOf(chapter))

        assertEquals(sentinelTimestamp, bookMetadata.lastModified())
        assertEquals(sentinelTimestamp, catalog.lastModified())
    }

    @Test
    fun `persistent browser paginates active catalog files and keeps content read only`() {
        store.writeChapterContent(book, chapter, "正文")
        store.writeSummary(book, chapter, "正文", "摘要")
        store.ensureCharactersDocument(book.id)
        val listing = store.listPersistedFiles(book, listOf(chapter), offset = 0, limit = 2)
        assertEquals(0, listing.getInt("offset"))
        assertEquals(2, listing.getInt("limit"))
        assertTrue(listing.getJSONArray("entries").length() <= 2)
        assertTrue(listing.has("nextOffset"))

        val entries = store.listPersistedFiles(book, listOf(chapter), 0, 100)
            .getJSONArray("entries")
        val contentEntry =
            (0 until entries.length())
                .map(entries::getJSONObject)
                .firstOrNull { it.getString("name") == "content.md" }
                ?: error("content.md was not listed")
        assertTrue(contentEntry.getBoolean("readOnly"))
        val read = store.readPersistedFile(book.id, contentEntry.getString("path"))
        assertEquals("正文", read.getString("content"))
        assertTrue(read.getBoolean("readOnly"))
    }

    @Test
    fun `persistent browser rejects canonical path traversal and disallowed names`() {
        val outside = File(root.parentFile, "book.md").apply { writeText("outside") }
        try {
            val traversal = File(store.bookRootPath(book.id), "../book.md").path
            val traversalError =
                runCatching { store.readPersistedFile(book.id, traversal) }.exceptionOrNull()
            assertNotNull("越界路径必须被拒绝", traversalError)

            val rootBook = File(store.bookRootPath(book.id), "book.md")
            rootBook.writeText("inside")
            val disallowed = File(store.bookRootPath(book.id), "secret.txt")
            disallowed.writeText("secret")
            val typeError =
                runCatching { store.readPersistedFile(book.id, disallowed.path) }.exceptionOrNull()
            assertNotNull("不在 allowlist 的文件必须被拒绝", typeError)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `file pages reconstruct persisted text without gaps or repeated characters`() {
        val content = "人物甲\n第二段正文".repeat(3000)
        store.writeChapterContent(book, chapter, content)
        val path = store.chapterFilePaths(book.id, chapter.sourceId)!!.getString("contentPath")
        val joined = StringBuilder()
        var offset = 0
        do {
            val page = store.readPersistedFile(book.id, path, offset, 113)
            assertTrue(page.getString("content").length <= 113)
            joined.append(page.getString("content"))
            if (page.isNull("nextOffset")) break
            val next = page.getInt("nextOffset")
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(content, joined.toString())
        val beyond = store.readPersistedFile(book.id, path, content.length + 20, 100)
        assertEquals("", beyond.getString("content"))
        assertTrue(beyond.isNull("nextOffset"))
    }

    @Test
    fun `character evidence uses active chapter files and visible current prefix`() {
        store.writeChapterContent(book, chapter, "人物甲在街上。\n人物乙在屋内。")
        val evidence = store.findCharacterEvidence(
            book.id, listOf(chapter), "人物甲", 0, currentBodyPosition = 8,
        ).getJSONArray("evidence")
        assertEquals(1, evidence.length())
        assertTrue(evidence.getJSONObject(0).getString("text").contains("人物甲"))
        assertEquals(0, store.findCharacterEvidence(
            book.id, listOf(chapter), "人物乙", 0, currentBodyPosition = 8,
        ).getJSONArray("evidence").length())
        val replacement = chapter.copy(sourceId = "new-source")
        store.syncBookCatalog(book, listOf(replacement))
        assertEquals(0, store.findCharacterEvidence(
            book.id, listOf(replacement), "人物甲", 1,
        ).getJSONArray("evidence").length())
    }

    @Test
    fun `persistent browser rejects content from a stale source directory`() {
        store.writeChapterContent(book, chapter, "旧来源正文")
        val stalePath =
            store.chapterFilePaths(book.id, chapter.sourceId)
                ?.getString("contentPath")
                ?: error("stale chapter content missing")

        val replacement = chapter.copy(sourceId = "source-replacement")
        store.syncBookCatalog(book, listOf(replacement))
        assertTrue(File(stalePath).isFile)

        val error =
            runCatching { store.readPersistedFile(book.id, stalePath) }.exceptionOrNull()
        assertNotNull("已从当前 catalog 移除的来源目录必须拒绝读取", error)
    }
}
