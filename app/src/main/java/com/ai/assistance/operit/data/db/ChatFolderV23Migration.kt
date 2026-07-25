package com.ai.assistance.operit.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ai.assistance.operit.data.model.ChatFolderEntity

internal data class V23FolderRecord(
    val id: String,
    val scope: String,
    val name: String,
    val parentFolderId: String?,
    val displayOrder: Long,
    val pinned: Boolean,
)

private data class V23PlacementRecord(
    val chatId: String,
    val scope: String,
    val folderId: String?,
    val displayOrder: Long,
)

private val validFolderScopes = setOf("ALL", "FAVORITE")

/**
 * Repairs relationships before the v23 table rebuild.
 *
 * Invalid scopes are promoted to ALL, missing/cross-scope parents are removed, and one edge in
 * every cycle is cut. Sibling names and orders are then made deterministic without changing IDs.
 */
internal fun repairV23Folders(source: List<V23FolderRecord>): List<V23FolderRecord> {
    val normalized =
        source.associate { folder ->
            folder.id to
                folder.copy(
                    scope = folder.scope.takeIf(validFolderScopes::contains) ?: "ALL",
                    name = folder.name.takeUnless(String::isBlank) ?: "Folder",
                )
        }
    val visitState = mutableMapOf<String, Int>()
    val resolvedParents = mutableMapOf<String, String?>()

    fun resolveParent(folderId: String): String? {
        resolvedParents[folderId]?.let { return it }
        if (resolvedParents.containsKey(folderId)) return null

        val folder = normalized.getValue(folderId)
        visitState[folderId] = 1
        val candidate =
            folder.parentFolderId?.takeIf { parentId ->
                val parent = normalized[parentId]
                parent != null && parent.scope == folder.scope
            }
        val resolved =
            when {
                candidate == null -> null
                visitState[candidate] == 1 -> null
                else -> {
                    if (visitState[candidate] != 2) resolveParent(candidate)
                    candidate
                }
            }
        visitState[folderId] = 2
        resolvedParents[folderId] = resolved
        return resolved
    }

    normalized.keys.sorted().forEach(::resolveParent)

    val usedNames = mutableMapOf<Pair<String, String?>, MutableSet<String>>()
    val orderCounters = mutableMapOf<Pair<String, String?>, Long>()
    return normalized.values
        .sortedWith(
            compareBy<V23FolderRecord> { it.scope }
                .thenBy { resolvedParents[it.id].orEmpty() }
                .thenByDescending { it.pinned }
                .thenBy { it.displayOrder }
                .thenBy { it.id }
        )
        .map { folder ->
            val parentId = resolvedParents[folder.id]
            val siblingKey = folder.scope to parentId
            val name =
                nextAvailableExactFolderName(
                    folder.name,
                    usedNames.getOrPut(siblingKey) { mutableSetOf() },
                )
            val order = orderCounters.getOrDefault(siblingKey, 0L)
            orderCounters[siblingKey] = order + 1
            folder.copy(
                name = name,
                parentFolderId = parentId,
                displayOrder = order,
            )
        }
}

private fun createParentKeyTriggers(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS `chat_folders_sync_parent_key_after_insert`
        AFTER INSERT ON `chat_folders`
        WHEN NEW.`parentKey` !=
            CASE
                WHEN NEW.`parentFolderId` IS NULL THEN 'root:'
                ELSE 'id:' || NEW.`parentFolderId`
            END
        BEGIN
            UPDATE `chat_folders`
            SET `parentKey` =
                CASE
                    WHEN NEW.`parentFolderId` IS NULL THEN 'root:'
                    ELSE 'id:' || NEW.`parentFolderId`
                END
            WHERE `id` = NEW.`id`;
        END
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS `chat_folders_sync_parent_key_after_update`
        AFTER UPDATE OF `parentFolderId`, `parentKey` ON `chat_folders`
        WHEN NEW.`parentKey` !=
            CASE
                WHEN NEW.`parentFolderId` IS NULL THEN 'root:'
                ELSE 'id:' || NEW.`parentFolderId`
            END
        BEGIN
            UPDATE `chat_folders`
            SET `parentKey` =
                CASE
                    WHEN NEW.`parentFolderId` IS NULL THEN 'root:'
                    ELSE 'id:' || NEW.`parentFolderId`
                END
            WHERE `id` = NEW.`id`;
        END
        """.trimIndent()
    )
}

internal val CHAT_FOLDER_INTEGRITY_CALLBACK =
    object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            createParentKeyTriggers(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            createParentKeyTriggers(db)
        }
    }

internal val MIGRATION_22_23 =
    object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val repairedFolders =
                repairV23Folders(
                    db.query(
                        "SELECT id, scope, name, parentFolderId, displayOrder, pinned FROM chat_folders"
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    V23FolderRecord(
                                        id = cursor.getString(0),
                                        scope = cursor.getString(1),
                                        name = cursor.getString(2),
                                        parentFolderId =
                                            if (cursor.isNull(3)) null else cursor.getString(3),
                                        displayOrder = cursor.getLong(4),
                                        pinned = cursor.getInt(5) != 0,
                                    )
                                )
                            }
                        }
                    }
                )
            val foldersById = repairedFolders.associateBy(V23FolderRecord::id)
            val chatRows =
                db.query("SELECT id, displayOrder, createdAt FROM chats ORDER BY displayOrder, createdAt, id")
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)))
                            }
                        }
                    }
            val chatIds = chatRows.mapTo(mutableSetOf()) { it.first }
            val placements =
                db.query("SELECT chatId, scope, folderId, displayOrder FROM chat_placements")
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val chatId = cursor.getString(0)
                                val scope = cursor.getString(1)
                                if (chatId !in chatIds || scope !in validFolderScopes) continue
                                val rawFolderId =
                                    if (cursor.isNull(2)) null else cursor.getString(2)
                                val folderId =
                                    rawFolderId?.takeIf { id -> foldersById[id]?.scope == scope }
                                add(
                                    V23PlacementRecord(
                                        chatId = chatId,
                                        scope = scope,
                                        folderId = folderId,
                                        displayOrder = cursor.getLong(3),
                                    )
                                )
                            }
                        }
                    }
                    .associateBy { it.chatId to it.scope }
                    .toMutableMap()

            chatRows.forEach { (chatId, displayOrder, _) ->
                placements.putIfAbsent(
                    chatId to "ALL",
                    V23PlacementRecord(
                        chatId = chatId,
                        scope = "ALL",
                        folderId = null,
                        displayOrder = displayOrder,
                    ),
                )
            }
            val repairedPlacements =
                placements.values
                    .groupBy { it.scope to it.folderId }
                    .flatMap { (_, siblings) ->
                        siblings
                            .sortedWith(
                                compareBy<V23PlacementRecord> { it.displayOrder }
                                    .thenBy { it.chatId }
                            )
                            .mapIndexed { index, placement ->
                                placement.copy(displayOrder = index.toLong())
                            }
                    }

            db.execSQL(
                """
                CREATE TABLE `chat_folders_v23` (
                    `id` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `parentFolderId` TEXT,
                    `parentKey` TEXT NOT NULL DEFAULT 'root:',
                    `displayOrder` INTEGER NOT NULL,
                    `pinned` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`parentFolderId`, `scope`)
                        REFERENCES `chat_folders_v23`(`id`, `scope`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_chat_folders_id_scope` ON `chat_folders_v23` (`id`, `scope`)"
            )

            fun folderDepth(folder: V23FolderRecord): Int {
                var depth = 0
                var parentId = folder.parentFolderId
                while (parentId != null) {
                    depth++
                    parentId = foldersById[parentId]?.parentFolderId
                }
                return depth
            }
            repairedFolders
                .sortedWith(compareBy(::folderDepth).thenBy { it.id })
                .forEach { folder ->
                    db.execSQL(
                        """
                        INSERT INTO `chat_folders_v23`
                            (`id`, `scope`, `name`, `parentFolderId`, `parentKey`, `displayOrder`, `pinned`)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            folder.id,
                            folder.scope,
                            folder.name,
                            folder.parentFolderId,
                            ChatFolderEntity.parentKeyFor(folder.parentFolderId),
                            folder.displayOrder,
                            if (folder.pinned) 1 else 0,
                        ),
                    )
                }

            db.execSQL(
                """
                CREATE TABLE `chat_placements_v23` (
                    `chatId` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `folderId` TEXT,
                    `displayOrder` INTEGER NOT NULL,
                    PRIMARY KEY(`chatId`, `scope`),
                    FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`folderId`, `scope`)
                        REFERENCES `chat_folders_v23`(`id`, `scope`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent()
            )
            repairedPlacements.forEach { placement ->
                db.execSQL(
                    """
                    INSERT INTO `chat_placements_v23`
                        (`chatId`, `scope`, `folderId`, `displayOrder`)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        placement.chatId,
                        placement.scope,
                        placement.folderId,
                        placement.displayOrder,
                    ),
                )
            }

            db.execSQL("DROP TABLE `chat_placements`")
            db.execSQL("DROP TABLE `chat_folders`")
            db.execSQL("ALTER TABLE `chat_folders_v23` RENAME TO `chat_folders`")
            db.execSQL("ALTER TABLE `chat_placements_v23` RENAME TO `chat_placements`")

            db.execSQL(
                "CREATE UNIQUE INDEX `index_chat_folders_scope_parentKey_name` ON `chat_folders` (`scope`, `parentKey`, `name`)"
            )
            db.execSQL("CREATE INDEX `index_chat_folders_scope` ON `chat_folders` (`scope`)")
            db.execSQL(
                "CREATE INDEX `index_chat_folders_parentFolderId` ON `chat_folders` (`parentFolderId`)"
            )
            db.execSQL(
                "CREATE INDEX `index_chat_folders_scope_parentFolderId_displayOrder` ON `chat_folders` (`scope`, `parentFolderId`, `displayOrder`)"
            )
            db.execSQL(
                "CREATE INDEX `index_chat_placements_chatId` ON `chat_placements` (`chatId`)"
            )
            db.execSQL(
                "CREATE INDEX `index_chat_placements_folderId` ON `chat_placements` (`folderId`)"
            )
            db.execSQL(
                "CREATE INDEX `index_chat_placements_scope_folderId_displayOrder` ON `chat_placements` (`scope`, `folderId`, `displayOrder`)"
            )
            createParentKeyTriggers(db)

            db.query("PRAGMA foreign_key_check").use { cursor ->
                check(!cursor.moveToFirst()) {
                    "Foreign-key violation after chat folder migration: table=${cursor.getString(0)}, rowId=${cursor.getLong(1)}"
                }
            }
        }
    }
