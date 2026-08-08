package com.ai.assistance.operit.ui.features.tokenstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarImportDecisionTest {

    @Test
    fun `missing global avatar clears current avatar`() {
        listOf<String?>(null, "", "  \t\n").forEach { globalAvatar ->
            val decision = decideAvatarImport(globalAvatar, null, "/old/avatar", 10L, 20L)
            assertTrue(decision.applyAvatar)
            assertNull(decision.avatarPath)
            assertEquals(20L, decision.avatarRevision)
        }
    }

    @Test
    fun `failed nonnull global avatar import preserves current avatar and revision`() {
        val decision = decideAvatarImport("content://global", null, "/old/avatar", 10L, 20L)
        assertFalse(decision.applyAvatar)
        assertEquals("/old/avatar", decision.avatarPath)
        assertEquals(10L, decision.avatarRevision)
    }

    @Test
    fun `successful global avatar import applies new path and revision`() {
        val decision = decideAvatarImport("content://global", "/new/avatar", "/old/avatar", 10L, 20L)
        assertTrue(decision.applyAvatar)
        assertEquals("/new/avatar", decision.avatarPath)
        assertEquals(20L, decision.avatarRevision)
    }
}
