package com.ai.assistance.operit.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatFolderMigrationPolicyTest {
    @Test
    fun legacyFolderName_preservesExactNonBlankNames() {
        assertEquals(" Work ", legacyFolderName(" Work "))
        assertEquals("Work\t", legacyFolderName("Work\t"))
        assertEquals("work", legacyFolderName("work"))
    }

    @Test
    fun legacyFolderName_treatsOnlyBlankNamesAsRoot() {
        assertNull(legacyFolderName(null))
        assertNull(legacyFolderName(""))
        assertNull(legacyFolderName(" \t\n"))
    }

    @Test
    fun nextAvailableExactFolderName_numbersConflictsWithoutMergingVariants() {
        val used = mutableSetOf("Work", "Work (2)", "work", " Work ")

        assertEquals("Work (3)", nextAvailableExactFolderName("Work", used))
        assertEquals("work (2)", nextAvailableExactFolderName("work", used))
        assertEquals(" Work  (2)", nextAvailableExactFolderName(" Work ", used))
    }

    @Test
    fun nextAvailableExactFolderName_repairsBlankImportedNames() {
        val used = mutableSetOf("Folder")

        assertEquals("Folder (2)", nextAvailableExactFolderName(" \t", used))
    }
}
