package com.ai.assistance.operit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolCacheIdTest {

    @Test
    fun acceptsUuidAndSimpleToolNames() {
        assertTrue(isSimplePoolCacheId("f99c58e1-1c9d-4f60-baef-61dc40941f93"))
        assertTrue(isSimplePoolCacheId("img_1"))
        assertTrue(isSimplePoolCacheId("a.b-c"))
        assertTrue(isSimplePoolCacheId("image-1"))
    }

    @Test
    fun rejectsTraversalAndSeparators() {
        assertFalse(isSimplePoolCacheId("../x"))
        assertFalse(isSimplePoolCacheId("../../x"))
        assertFalse(isSimplePoolCacheId("a/b"))
        assertFalse(isSimplePoolCacheId("a\\b"))
        assertFalse(isSimplePoolCacheId(".."))
        assertFalse(isSimplePoolCacheId("x..y"))
    }

    @Test
    fun rejectsBlankAndErrorSentinel() {
        assertFalse(isSimplePoolCacheId(""))
        assertFalse(isSimplePoolCacheId("   "))
        assertFalse(isSimplePoolCacheId("error"))
    }
}
