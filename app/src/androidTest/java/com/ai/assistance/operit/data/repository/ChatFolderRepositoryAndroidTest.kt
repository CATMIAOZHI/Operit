package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.SYSTEM_UNGROUPED_FOLDER_ID
import com.ai.assistance.operit.data.model.SubagentRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFolderRepositoryAndroidTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatFolderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = ChatFolderRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun folderDepthAndCycleRulesAreEnforced() = runBlocking {
        val root = repository.createFolder(null, "Same")
        val child = repository.createFolder(root, "Same")
        repository.createFolder(child, "Same")

        expectIllegalArgument {
            repository.createFolder(
                database.chatFolderDao().getFolders().first { it.parentFolderId == child }.id,
                "Too deep",
            )
        }
        expectIllegalArgument {
            repository.moveFolder(
                folderId = root,
                targetParentFolderId = child,
                expectedSourceSiblings = siblingSnapshot(null),
                expectedTargetSiblings = siblingSnapshot(child),
            )
        }
    }

    @Test
    fun visibleSlotReorderPreservesHiddenChatSlot() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("favorite-a", folderId, 0, favorite = true)
        insertChat("hidden-b", folderId, 1, favorite = false)
        insertChat("favorite-c", folderId, 2, favorite = true)

        repository.moveChat(
            chatId = "favorite-c",
            targetFolderId = folderId,
            expectedSourceSiblings = siblingSnapshot(folderId),
            expectedTargetSiblings = siblingSnapshot(folderId),
            orderedVisibleNodeKeys = listOf("chat:favorite-c", "chat:favorite-a"),
        )

        val ordered =
            database.chatDao().getAllChatsDirectly()
                .filter { it.folderId == folderId }
                .sortedBy { it.displayOrder }
                .map { it.id }
        assertEquals(listOf("favorite-c", "hidden-b", "favorite-a"), ordered)
        assertEquals(
            mapOf("favorite-a" to 0L, "hidden-b" to 1L, "favorite-c" to 2L),
            database.chatDao().getAllChatsDirectly().associate { it.id to it.updatedAt },
        )
    }

    @Test
    fun folderAndInitialChatArePersistedInOneTransaction() = runBlocking {
        val initialChat =
            ChatEntity(
                id = "initial-chat",
                title = "New conversation",
                characterGroupId = "group-id",
            )
        val openingMessage =
            MessageEntity(
                chatId = initialChat.id,
                sender = "ai",
                content = "Opening",
                timestamp = 1234L,
                orderIndex = 0,
                roleName = "Agent",
            )

        val persisted =
            repository.createFolderWithInitialChat(
                parentFolderId = null,
                name = "Atomic",
                initialChat = initialChat,
                openingMessage = openingMessage,
            )

        val folder = database.chatFolderDao().getFolder(requireNotNull(persisted.folderId))
        val messages = database.messageDao().getMessagesForChat(persisted.id)
        assertEquals("Atomic", folder?.name)
        assertEquals("group-id", persisted.characterGroupId)
        assertEquals(1234L, persisted.lastMessageAt)
        assertEquals(listOf("Opening"), messages.map { it.content })
    }

    @Test
    fun failedInitialChatInsertRollsBackFolder() = runBlocking {
        val duplicateChat = ChatEntity(id = "duplicate-chat", title = "Existing")
        database.chatDao().insertChat(duplicateChat)

        val result =
            runCatching {
                repository.createFolderWithInitialChat(
                    parentFolderId = null,
                    name = "Must roll back",
                    initialChat = duplicateChat.copy(title = "Duplicate"),
                )
            }

        assertTrue(result.isFailure)
        assertFalse(
            database.chatFolderDao().getFolders().any { it.name == "Must roll back" }
        )
        assertEquals("Existing", database.chatDao().getChatById(duplicateChat.id)?.title)
    }

    @Test
    fun visibleChatReorderIgnoresSubagentChildrenInTheSameFolder() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("a", folderId, 0, favorite = false)
        insertChat("b", folderId, 2, favorite = false)
        insertChat(
            id = "subagent",
            folderId = folderId,
            order = 1,
            favorite = false,
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "a",
        )

        repository.moveChat(
            chatId = "b",
            targetFolderId = folderId,
            expectedSourceSiblings = siblingSnapshot(folderId),
            expectedTargetSiblings = siblingSnapshot(folderId),
            orderedVisibleNodeKeys = listOf("chat:b", "chat:a"),
        )

        val visibleOrder =
            database.chatDao().getAllChatsDirectly()
                .filter {
                    it.folderId == folderId &&
                        it.chatKind != ChatKind.SUBAGENT.name
                }
                .sortedBy { it.displayOrder }
                .map { it.id }
        assertEquals(listOf("b", "a"), visibleOrder)
        assertEquals(1L, database.chatDao().getChatById("subagent")?.displayOrder)
    }

    @Test
    fun projectedWebReorderPreservesFolderSlots() = runBlocking {
        database.chatFolderDao().insertFolder(folder("hidden-folder", null, 1))
        insertChat("a", null, 0, favorite = false)
        insertChat("b", null, 2, favorite = false)

        val reordered =
            repository.reorderProjectedChats(
                expectedChatIds = listOf("a", "b"),
                orderedChatIds = listOf("b", "a"),
                expectedFolderIdsByChatId = mapOf("a" to null, "b" to null),
                expectedDisplayOrdersByChatId = mapOf("a" to 0L, "b" to 2L),
            )

        assertTrue(reordered)
        assertEquals(
            listOf("chat:b", "folder:hidden-folder", "chat:a"),
            siblingSnapshot(null).map { it.stableKey },
        )
    }

    @Test
    fun projectedWebReorderRejectsStaleFolderSnapshot() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("a", null, 0, favorite = false)
        insertChat("b", null, 1, favorite = false)
        database.chatDao().updateChatOrderAndFolder("b", 0, folderId)

        val reordered =
            repository.reorderProjectedChats(
                expectedChatIds = listOf("a", "b"),
                orderedChatIds = listOf("b", "a"),
                expectedFolderIdsByChatId = mapOf("a" to null, "b" to null),
                expectedDisplayOrdersByChatId = mapOf("a" to 0L, "b" to 1L),
            )

        assertFalse(reordered)
        assertEquals(folderId, database.chatDao().getChatById("b")?.folderId)
    }

    @Test
    fun chatCanMoveIntoFolderAndBackToEmptyUngroupedRoot() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("favorite", null, 0, favorite = true)

        repository.moveChat(
            chatId = "favorite",
            targetFolderId = folderId,
            expectedSourceSiblings = siblingSnapshot(null),
            expectedTargetSiblings = siblingSnapshot(folderId),
        )
        repository.moveChat(
            chatId = "favorite",
            targetFolderId = null,
            expectedSourceSiblings = siblingSnapshot(folderId),
            expectedTargetSiblings = siblingSnapshot(null),
        )

        assertEquals(null, database.chatDao().getChatById("favorite")?.folderId)
    }

    @Test
    fun explicitUngroupedDropAppendsAfterHiddenRootChats() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("hidden-root", null, 0, favorite = false)
        insertChat("favorite", folderId, 0, favorite = true)

        repository.moveChat(
            chatId = "favorite",
            targetFolderId = null,
            expectedSourceSiblings = siblingSnapshot(folderId),
            expectedTargetSiblings = siblingSnapshot(null),
            allowAppendToNonEmptyTarget = true,
        )

        val rootOrder =
            database.chatDao().getAllChatsDirectly()
                .filter { it.folderId == null }
                .sortedBy { it.displayOrder }
                .map { it.id }
        assertEquals(listOf("hidden-root", "favorite"), rootOrder)
    }

    @Test
    fun deletingFolderPromotesChildrenAndChatsWithoutDeletingChats() = runBlocking {
        val before = folder("before", null, 0)
        val deleted = folder("deleted", null, 1)
        val after = folder("after", null, 2)
        val child = folder("child", "deleted", 0)
        database.chatFolderDao().insertFolders(listOf(before, deleted, after, child))
        insertChat("chat", "deleted", 0, favorite = false)

        repository.deleteFolder("deleted")

        assertEquals(null, database.chatFolderDao().getFolder("deleted"))
        assertEquals(null, database.chatFolderDao().getFolder("child")?.parentFolderId)
        assertEquals(null, database.chatDao().getChatById("chat")?.folderId)
        assertEquals(1, database.chatDao().getTotalChatCount())
        assertEquals(
            listOf("before", "child", "after"),
            database.chatFolderDao().getFolders()
                .filter { it.parentFolderId == null }
                .sortedBy { it.displayOrder }
                .map { it.id },
        )
    }

    @Test
    fun deletingFolderWithChatsDeletesDescendantChatsButPreservesLockedChats() = runBlocking {
        database.chatFolderDao().insertFolders(
            listOf(
                folder("root", null, 0),
                folder("child", "root", 0),
                folder("grandchild", "child", 0),
            )
        )
        insertChat("direct", "root", 0, favorite = false)
        insertChat("nested", "child", 0, favorite = false)
        insertChat("deep", "grandchild", 0, favorite = false)
        insertChat("locked", "grandchild", 1, favorite = false, locked = true)

        val deleted =
            repository.deleteFolderWithChats(
                "root",
                characterCardName = null,
                characterGroupId = null,
                expectedChatIds = setOf("direct", "nested", "deep"),
            )

        assertEquals(setOf("direct", "nested", "deep"), deleted.toSet())
        assertEquals(null, database.chatFolderDao().getFolder("root"))
        assertEquals(null, database.chatFolderDao().getFolder("child")?.parentFolderId)
        assertEquals("grandchild", database.chatDao().getChatById("locked")?.folderId)
        assertEquals(1, database.chatDao().getTotalChatCount())
    }

    @Test
    fun deletingFolderWithChatsHonorsCharacterGroupFilter() = runBlocking {
        database.chatFolderDao().insertFolders(
            listOf(folder("root", null, 0), folder("child", "root", 0))
        )
        insertChat(
            "matching",
            "child",
            0,
            favorite = false,
            characterGroupId = "group-a",
        )
        insertChat(
            "other",
            "child",
            1,
            favorite = false,
            characterGroupId = "group-b",
        )

        val deleted =
            repository.deleteFolderWithChats(
                "root",
                characterCardName = null,
                characterGroupId = "group-a",
                expectedChatIds = setOf("matching"),
            )

        assertEquals(listOf("matching"), deleted)
        assertEquals(null, database.chatFolderDao().getFolder("child")?.parentFolderId)
        assertEquals("child", database.chatDao().getChatById("other")?.folderId)
    }

    @Test
    fun deletingFolderWithChatsDeletesSubagentChildrenBeforeParents() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("parent", folderId, 0, favorite = false)
        insertChat(
            id = "child",
            folderId = folderId,
            order = 1,
            favorite = false,
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "parent",
        )
        insertSubagentRun("run", "parent", "child")

        val expectedChatIds = repository.getFolderDeletionChatIds(folderId, null, null)
        val deleted = repository.deleteFolderWithChats(folderId, null, null, expectedChatIds)

        assertEquals(setOf("parent", "child"), deleted.toSet())
        assertEquals(null, database.subagentRunDao().getById("run"))
        assertEquals(0, database.chatDao().getTotalChatCount())
    }

    @Test
    fun deletingFolderWithChatsPreservesLockedSubagentGraph() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("parent", folderId, 0, favorite = false, locked = true)
        insertChat(
            id = "child",
            folderId = folderId,
            order = 1,
            favorite = false,
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "parent",
        )
        insertSubagentRun("run", "parent", "child")

        val expectedChatIds = repository.getFolderDeletionChatIds(folderId, null, null)
        val deleted = repository.deleteFolderWithChats(folderId, null, null, expectedChatIds)

        assertTrue(deleted.isEmpty())
        assertEquals(
            setOf("parent", "child"),
            database.chatDao().getAllChatsDirectly().map { it.id }.toSet(),
        )
        assertEquals("run", database.subagentRunDao().getById("run")?.id)
    }

    @Test
    fun deletingFolderWithChatsPreservesSubagentGraphSplitAcrossFolders() = runBlocking {
        val deletedFolderId = repository.createFolder(null, "Deleted")
        val retainedFolderId = repository.createFolder(null, "Retained")
        insertChat("parent", deletedFolderId, 0, favorite = false)
        insertChat(
            id = "child",
            folderId = retainedFolderId,
            order = 1,
            favorite = false,
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "parent",
        )
        insertSubagentRun("run", "parent", "child")

        val expectedChatIds =
            repository.getFolderDeletionChatIds(deletedFolderId, null, null)
        val deleted =
            repository.deleteFolderWithChats(deletedFolderId, null, null, expectedChatIds)

        assertTrue(deleted.isEmpty())
        assertEquals(null, database.chatDao().getChatById("parent")?.folderId)
        assertEquals(retainedFolderId, database.chatDao().getChatById("child")?.folderId)
        assertEquals("run", database.subagentRunDao().getById("run")?.id)
    }

    @Test
    fun characterStatisticsExcludeSubagentChatsAndMessages() = runBlocking {
        insertChat(
            id = "card-parent",
            folderId = null,
            order = 0,
            favorite = false,
            characterCardName = "card",
        )
        insertChat(
            id = "card-child",
            folderId = null,
            order = 1,
            favorite = false,
            characterCardName = "card",
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "card-parent",
        )
        insertChat(
            id = "group-parent",
            folderId = null,
            order = 2,
            favorite = false,
            characterGroupId = "group",
        )
        insertChat(
            id = "group-child",
            folderId = null,
            order = 3,
            favorite = false,
            characterGroupId = "group",
            chatKind = ChatKind.SUBAGENT,
            parentChatId = "group-parent",
        )
        insertMessage("card-parent", 10)
        insertMessage("card-child", 11)
        insertMessage("card-child", 12)
        insertMessage("group-parent", 13)
        insertMessage("group-child", 14)
        insertMessage("group-child", 15)

        val cardStats = database.chatDao().getCharacterCardChatStats().first()
            .single { it.characterCardName == "card" }
        val groupStats = database.chatDao().getCharacterGroupChatStats().first()
            .single { it.characterGroupId == "group" }

        assertEquals(1, cardStats.chatCount)
        assertEquals(1, cardStats.messageCount)
        assertEquals(1, groupStats.chatCount)
        assertEquals(1, groupStats.messageCount)
    }

    @Test
    fun chatsAndFoldersCanBeInterleavedWithinOneParent() = runBlocking {
        database.chatFolderDao().insertFolders(
            listOf(folder("folder-a", null, 0), folder("folder-b", null, 2))
        )
        insertChat("chat", null, 1, favorite = false)

        repository.moveChat(
            chatId = "chat",
            targetFolderId = null,
            expectedSourceSiblings = siblingSnapshot(null),
            expectedTargetSiblings = siblingSnapshot(null),
            orderedVisibleNodeKeys =
                listOf("chat:chat", "folder:folder-a", "folder:folder-b"),
        )

        assertEquals(
            listOf("chat:chat", "folder:folder-a", "folder:folder-b"),
            siblingSnapshot(null).map { it.stableKey },
        )
    }

    @Test
    fun chatCanMoveOutOfFolderBeforeRootFolder() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("chat", folderId, 0, favorite = true)

        repository.moveChat(
            chatId = "chat",
            targetFolderId = null,
            expectedSourceSiblings = siblingSnapshot(folderId),
            expectedTargetSiblings = siblingSnapshot(null),
            beforeNodeKey = "folder:$folderId",
        )

        assertEquals(
            listOf("chat:chat", "folder:$folderId"),
            siblingSnapshot(null).map { it.stableKey },
        )
    }

    @Test
    fun folderCanMoveAcrossChatSibling() = runBlocking {
        database.chatFolderDao().insertFolders(
            listOf(folder("folder-a", null, 0), folder("folder-b", null, 2))
        )
        insertChat("chat", null, 1, favorite = false)

        repository.moveFolder(
            folderId = "folder-b",
            targetParentFolderId = null,
            expectedSourceSiblings = siblingSnapshot(null),
            expectedTargetSiblings = siblingSnapshot(null),
            beforeNodeKey = "chat:chat",
        )

        assertEquals(
            listOf("folder:folder-a", "folder:folder-b", "chat:chat"),
            siblingSnapshot(null).map { it.stableKey },
        )
    }

    @Test
    fun systemUngroupedCanReorderAtRootButCannotBeNestedOrContainFolders() = runBlocking {
        repository.ensureUngroupedFolder()
        val regular = repository.createFolder(null, "Regular")

        repository.moveFolder(
            folderId = SYSTEM_UNGROUPED_FOLDER_ID,
            targetParentFolderId = null,
            expectedSourceSiblings = siblingSnapshot(null),
            expectedTargetSiblings = siblingSnapshot(null),
            afterNodeKey = "folder:$regular",
        )

        assertEquals(
            listOf("folder:$regular", "folder:$SYSTEM_UNGROUPED_FOLDER_ID"),
            siblingSnapshot(null).map { it.stableKey },
        )
        expectIllegalArgument {
            repository.moveFolder(
                folderId = SYSTEM_UNGROUPED_FOLDER_ID,
                targetParentFolderId = regular,
                expectedSourceSiblings = siblingSnapshot(null),
                expectedTargetSiblings = siblingSnapshot(regular),
            )
        }
        expectIllegalArgument {
            repository.createFolder(SYSTEM_UNGROUPED_FOLDER_ID, "Forbidden child")
        }
        expectIllegalArgument {
            repository.moveFolder(
                folderId = regular,
                targetParentFolderId = SYSTEM_UNGROUPED_FOLDER_ID,
                expectedSourceSiblings = siblingSnapshot(null),
                expectedTargetSiblings = siblingSnapshot(SYSTEM_UNGROUPED_FOLDER_ID),
            )
        }
    }

    @Test
    fun systemUngroupedCannotBeRenamedOrDeleted() = runBlocking {
        repository.ensureUngroupedFolder()

        expectIllegalArgument {
            repository.renameFolder(SYSTEM_UNGROUPED_FOLDER_ID, "Renamed")
        }
        expectIllegalArgument {
            repository.deleteFolder(SYSTEM_UNGROUPED_FOLDER_ID)
        }
        assertEquals(
            SYSTEM_UNGROUPED_FOLDER_ID,
            database.chatFolderDao().getFolder(SYSTEM_UNGROUPED_FOLDER_ID)?.id,
        )
    }

    @Test
    fun chatMoveRejectsSystemUngroupedIdAsAStoredFolderReference() = runBlocking {
        repository.ensureUngroupedFolder()
        insertChat("chat", null, 0, favorite = false)

        expectIllegalArgument {
            repository.moveChat(
                chatId = "chat",
                targetFolderId = SYSTEM_UNGROUPED_FOLDER_ID,
                expectedSourceSiblings = siblingSnapshot(null),
                expectedTargetSiblings = siblingSnapshot(SYSTEM_UNGROUPED_FOLDER_ID),
            )
        }
        assertEquals(null, database.chatDao().getChatById("chat")?.folderId)
    }

    @Test
    fun ensureUngroupedRepairsHistoricalSystemFolderReferences() = runBlocking {
        database.chatFolderDao().insertFolder(
            folder(SYSTEM_UNGROUPED_FOLDER_ID, null, 0)
        )
        insertChat("legacy", SYSTEM_UNGROUPED_FOLDER_ID, 0, favorite = false)

        repository.ensureUngroupedFolder()

        assertEquals(null, database.chatDao().getChatById("legacy")?.folderId)
    }

    @Test
    fun chatMoveRejectsConcurrentProjectionChange() = runBlocking {
        val folderId = repository.createFolder(null, "Folder")
        insertChat("a", folderId, 0, favorite = true)
        insertChat("b", folderId, 1, favorite = true)
        val expected = siblingSnapshot(folderId)

        database.chatDao().updateChatFavorite("b", false)

        expectIllegalArgument {
            repository.moveChat(
                chatId = "b",
                targetFolderId = folderId,
                orderedVisibleNodeKeys = listOf("chat:b", "chat:a"),
                expectedSourceSiblings = expected,
                expectedTargetSiblings = expected,
            )
        }
        assertEquals(
            listOf("a", "b"),
            database.chatDao().getAllChatsDirectly().sortedBy { it.displayOrder }.map { it.id },
        )
    }

    @Test
    fun folderMoveRejectsConcurrentSiblingCreation() = runBlocking {
        database.chatFolderDao().insertFolders(
            listOf(folder("a", null, 0), folder("b", null, 1))
        )
        val expected = siblingSnapshot(null)
        database.chatFolderDao().insertFolder(folder("concurrent", null, 2))

        expectIllegalArgument {
            repository.moveFolder(
                folderId = "b",
                targetParentFolderId = null,
                expectedSourceSiblings = expected,
                expectedTargetSiblings = expected,
                beforeNodeKey = "folder:a",
            )
        }
        assertEquals(
            listOf("a", "b", "concurrent"),
            database.chatFolderDao().getFolders().sortedBy { it.displayOrder }.map { it.id },
        )
    }

    private suspend fun insertChat(
        id: String,
        folderId: String?,
        order: Long,
        favorite: Boolean,
        locked: Boolean = false,
        characterCardName: String? = null,
        characterGroupId: String? = null,
        chatKind: ChatKind = ChatKind.NORMAL,
        parentChatId: String? = null,
    ) {
        database.chatDao().insertChat(
            ChatEntity(
                id = id,
                title = id,
                createdAt = order,
                updatedAt = order,
                folderId = folderId,
                displayOrder = order,
                isFavorite = favorite,
                locked = locked,
                characterCardName = characterCardName,
                characterGroupId = characterGroupId,
                chatKind = chatKind.name,
                parentChatId = parentChatId,
            )
        )
    }

    private suspend fun insertMessage(chatId: String, timestamp: Long) {
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                sender = "assistant",
                content = "message-$timestamp",
                timestamp = timestamp,
                orderIndex = timestamp.toInt(),
            )
        )
    }

    private suspend fun insertSubagentRun(id: String, parentChatId: String, childChatId: String) {
        database.subagentRunDao().insert(
            SubagentRunEntity(
                id = id,
                parentChatId = parentChatId,
                childChatId = childChatId,
                agentProfileId = "general",
                title = id,
            )
        )
    }

    private suspend fun siblingSnapshot(
        parentFolderId: String?,
    ): List<HistorySiblingSnapshot> =
        (
            database.chatFolderDao().getFolders()
                .asSequence()
                .filter { it.parentFolderId == parentFolderId }
                .map(HistorySiblingSnapshot::fromFolder) +
                database.chatDao().getAllChatsDirectly()
                    .asSequence()
                    .filter {
                        it.folderId == parentFolderId &&
                            it.chatKind != ChatKind.SUBAGENT.name
                    }
                    .map(HistorySiblingSnapshot::fromChat)
        ).sortedWith(
            compareBy<HistorySiblingSnapshot> { it.displayOrder }
                .thenBy { it.kind }
                .thenBy { it.id }
        ).toList()

    private fun folder(id: String, parentId: String?, order: Long) =
        ChatFolderEntity(
            id = id,
            name = id,
            parentFolderId = parentId,
            displayOrder = order,
            createdAt = order,
        )

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
