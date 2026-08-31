package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegadoAuthoritySupportTest {
    private fun select(vararg installed: String): String? {
        val installedSet = installed.toSet()
        return LegadoAuthoritySupport.selectInstalled(installedSet::contains)
    }

    @Test
    fun `release wins over debug`() {
        assertEquals(
            "com.legado.app.release.readerProvider",
            select(
                "com.legado.app.debug.readerProvider",
                "com.legado.app.release.readerProvider",
            ),
        )
    }

    @Test
    fun `debug and legacy remain valid single-install fallbacks`() {
        assertEquals(
            "com.legado.app.debug.readerProvider",
            select("com.legado.app.debug.readerProvider"),
        )
        assertEquals(
            "com.legado.app.readerProvider",
            select("com.legado.app.readerProvider"),
        )
    }

    @Test
    fun `no installed provider returns null`() {
        assertNull(select())
    }

    @Test
    fun `chapter identity falls back to the catalog when provider omits chapter url`() {
        assertEquals(
            "catalog-chapter-url",
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = null,
                catalogChapterUrlBeforeContent = "catalog-chapter-url",
                catalogChapterUrlAfterContent = "catalog-chapter-url",
            ),
        )
        assertEquals(
            "catalog-chapter-url",
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = "",
                catalogChapterUrlBeforeContent = "catalog-chapter-url",
                catalogChapterUrlAfterContent = "catalog-chapter-url",
            ),
        )
    }

    @Test
    fun `chapter identity still prefers the provider response`() {
        assertEquals(
            "response-chapter-url",
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = "response-chapter-url",
                catalogChapterUrlBeforeContent = "old-catalog-chapter-url",
                catalogChapterUrlAfterContent = "new-catalog-chapter-url",
            ),
        )
        assertNull(
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = null,
                catalogChapterUrlBeforeContent = null,
                catalogChapterUrlAfterContent = null,
            ),
        )
    }

    @Test
    fun `missing response identity rejects an inserted or reordered chapter`() {
        assertNull(
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = null,
                catalogChapterUrlBeforeContent = "old-chapter-url",
                catalogChapterUrlAfterContent = "new-chapter-url",
            ),
        )
        assertNull(
            LegadoChapterIdentitySupport.resolve(
                responseChapterUrl = null,
                catalogChapterUrlBeforeContent = "old-chapter-url",
                catalogChapterUrlAfterContent = null,
            ),
        )
    }

    @Test
    fun `catalog snapshot comparison is order independent but rejects identity changes`() {
        val first =
            ReaderChapter(
                bookId = "book",
                sourceId = "source-a",
                index = 0,
                title = "chapter-a",
            )
        val second =
            ReaderChapter(
                bookId = "book",
                sourceId = "source-b",
                index = 1,
                title = "chapter-b",
            )
        assertEquals(
            true,
            ReaderChapterCatalogSupport.hasSameIdentity(
                before = listOf(first, second),
                after = listOf(second, first),
            ),
        )
        assertEquals(
            false,
            ReaderChapterCatalogSupport.hasSameIdentity(
                before = listOf(first, second),
                after = listOf(first, second.copy(sourceId = "inserted-source")),
            ),
        )
    }
}
