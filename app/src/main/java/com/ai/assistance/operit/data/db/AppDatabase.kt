package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ai.assistance.operit.data.converter.ChatFolderScopeConverter
import com.ai.assistance.operit.data.dao.ChatDao
import com.ai.assistance.operit.data.dao.ChatFolderDao
import com.ai.assistance.operit.data.dao.ChatPlacementDao
import com.ai.assistance.operit.data.dao.MessageDao
import com.ai.assistance.operit.data.dao.MessageVariantDao
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 应用数据库，包含聊天表和消息表 */
@Database(
    entities = [
        ChatEntity::class, MessageEntity::class, MessageVariantEntity::class,
        ChatFolderEntity::class, ChatPlacementEntity::class,
    ],
    version = 22,
    exportSchema = false
)
@TypeConverters(ChatFolderScopeConverter::class)
abstract class AppDatabase : RoomDatabase() {

    /** 获取聊天DAO */
    abstract fun chatDao(): ChatDao

    /** 获取消息DAO */
    abstract fun messageDao(): MessageDao

    abstract fun messageVariantDao(): MessageVariantDao

    abstract fun chatFolderDao(): ChatFolderDao

    abstract fun chatPlacementDao(): ChatPlacementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 定义从版本1到2的迁移
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 创建chats表
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `chats` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `inputTokens` INTEGER NOT NULL DEFAULT 0,
                                `outputTokens` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent()
                    )

                    // 创建messages表
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `messages` (
                                `messageId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `chatId` TEXT NOT NULL,
                                `sender` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                `orderIndex` INTEGER NOT NULL,
                                FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent()
                    )

                    // 为messages表创建索引
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)")
                }

            }

        // 定义从版本10到11的迁移
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加workspaceEnv列
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `workspaceEnv` TEXT")
                    } catch (_: Exception) {

                    }
                }
            }

        // 定义从版本11到12的迁移
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加characterGroupId列（用于绑定群组角色卡）
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `characterGroupId` TEXT")
                    } catch (_: Exception) {

                    }
                }
            }

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `inputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `outputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `cachedInputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `sentAt` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `outputDurationMs` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `waitDurationMs` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                }
            }

        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `problem_records`")
                }
            }

        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `selectedVariantIndex` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `message_variants` (
                                `variantId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `chatId` TEXT NOT NULL,
                                `messageTimestamp` INTEGER NOT NULL,
                                `variantIndex` INTEGER NOT NULL,
                                `content` TEXT NOT NULL,
                                `roleName` TEXT NOT NULL DEFAULT '',
                                `provider` TEXT NOT NULL DEFAULT '',
                                `modelName` TEXT NOT NULL DEFAULT '',
                                `inputTokens` INTEGER NOT NULL DEFAULT 0,
                                `outputTokens` INTEGER NOT NULL DEFAULT 0,
                                `cachedInputTokens` INTEGER NOT NULL DEFAULT 0,
                                `sentAt` INTEGER NOT NULL DEFAULT 0,
                                `outputDurationMs` INTEGER NOT NULL DEFAULT 0,
                                `waitDurationMs` INTEGER NOT NULL DEFAULT 0,
                                FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_message_variants_chatId_messageTimestamp` ON `message_variants` (`chatId`, `messageTimestamp`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_variants_chatId_messageTimestamp_variantIndex` ON `message_variants` (`chatId`, `messageTimestamp`, `variantIndex`)"
                    )
                }
            }

        private val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `displayMode` TEXT NOT NULL DEFAULT 'NORMAL'"
                    )
                }
            }

        private val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_messages_chatId_timestamp` ON `messages` (`chatId`, `timestamp`)"
                    )
                }
            }

        private val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        private val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `completedAt` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "ALTER TABLE message_variants ADD COLUMN `completedAt` INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        private val MIGRATION_19_20 =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chats ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                }
            }

        private val MIGRATION_20_21 =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. 创建 chat_folders 表（不含 DEFAULT，与 Room 自动 schema 对齐）
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_folders` (
                            `id` TEXT NOT NULL,
                            `scope` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `parentFolderId` TEXT,
                            `displayOrder` INTEGER NOT NULL,
                            `pinned` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`parentFolderId`) REFERENCES `chat_folders`(`id`) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_folders_scope` ON `chat_folders` (`scope`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_folders_parentFolderId` ON `chat_folders` (`parentFolderId`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_folders_scope_parentFolderId_displayOrder` ON `chat_folders` (`scope`, `parentFolderId`, `displayOrder`)"
                    )

                    // 2. 创建 chat_placements 表（不含 DEFAULT，与 Room 自动 schema 对齐）
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_placements` (
                            `chatId` TEXT NOT NULL,
                            `scope` TEXT NOT NULL,
                            `folderId` TEXT,
                            `displayOrder` INTEGER NOT NULL,
                            PRIMARY KEY(`chatId`, `scope`),
                            FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE,
                            FOREIGN KEY(`folderId`) REFERENCES `chat_folders`(`id`) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_placements_chatId` ON `chat_placements` (`chatId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_placements_folderId` ON `chat_placements` (`folderId`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_placements_scope_folderId_displayOrder` ON `chat_placements` (`scope`, `folderId`, `displayOrder`)"
                    )

                    // 3. 向 chats 增加 lastMessageAt 列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `lastMessageAt` INTEGER")

                    // 4. 按原始名称精确迁移非 blank 旧 group。
                    val distinctGroups = db.query(
                        "SELECT \"group\", MIN(displayOrder) FROM chats WHERE \"group\" IS NOT NULL GROUP BY \"group\" ORDER BY MIN(displayOrder), \"group\""
                    ).use { cursor ->
                        val groups = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            legacyFolderName(cursor.getString(0))?.let(groups::add)
                        }
                        groups
                    }
                    val folderIds = mutableMapOf<String, String>()
                    for (group in distinctGroups) {
                        val folderId = UUID.nameUUIDFromBytes(("ALL:$group").toByteArray(StandardCharsets.UTF_8)).toString()
                        folderIds[group] = folderId
                        db.execSQL(
                            "INSERT INTO chat_folders (id, scope, name, parentFolderId, displayOrder, pinned) VALUES (?, 'ALL', ?, NULL, 0, 0)",
                            arrayOf(folderId, group)
                        )
                    }
                    // 4b. 用每个文件夹成员的 MIN(displayOrder) 更新文件夹顺序
                    for ((group, folderId) in folderIds) {
                        db.execSQL(
                            "UPDATE chat_folders SET displayOrder = COALESCE((SELECT MIN(c.displayOrder) FROM chats c WHERE c.\"group\" = ?), 0) WHERE id = ?",
                            arrayOf(group, folderId)
                        )
                    }

                    // 5. 为每个对话创建 ALL placement，使用稠密 displayOrder（按 folder 分区）
                    val allChats = db.query(
                        "SELECT id, \"group\" FROM chats ORDER BY displayOrder ASC, createdAt ASC, id ASC"
                    ).use { cursor ->
                        val chats = mutableListOf<Pair<String, String?>>()
                        while (cursor.moveToNext()) {
                            chats.add(cursor.getString(0) to legacyFolderName(cursor.getString(1)))
                        }
                        chats
                    }
                    // 按 folder 分区重新编号
                    val folderOrderCounters = mutableMapOf<String?, Long>()
                    for ((chatId, groupName) in allChats) {
                        val fid = if (groupName != null) folderIds[groupName] else null
                        val displayOrder = (folderOrderCounters[fid] ?: 0L) + 1
                        folderOrderCounters[fid] = displayOrder
                        db.execSQL(
                            "INSERT INTO chat_placements (chatId, scope, folderId, displayOrder) VALUES (?, 'ALL', ?, ?)",
                            arrayOf<Any?>(chatId, fid, displayOrder)
                        )
                    }

                    // 6. 使用相关子查询回填 lastMessageAt
                    db.execSQL(
                        """
                        UPDATE chats SET lastMessageAt = (
                            SELECT MAX(messages.timestamp) FROM messages
                            WHERE messages.chatId = chats.id
                        )
                        """.trimIndent()
                    )
                }
            }

        private val MIGRATION_21_22 =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chat_folders ADD COLUMN `parentKey` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE chat_folders SET parentKey = COALESCE(parentFolderId, '')")

                    // Repair only placements that can be identified as products of the v20->21
                    // migration. Placements moved by the user to another folder are preserved.
                    val legacyChats = db.query(
                        "SELECT c.id, c.\"group\", c.displayOrder, p.folderId FROM chats c LEFT JOIN chat_placements p ON p.chatId = c.id AND p.scope = 'ALL' WHERE c.\"group\" IS NOT NULL"
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    arrayOf<Any?>(
                                        cursor.getString(0),
                                        cursor.getString(1),
                                        cursor.getLong(2),
                                        if (cursor.isNull(3)) null else cursor.getString(3),
                                    )
                                )
                            }
                        }
                    }
                    for (chat in legacyChats) {
                        val chatId = chat[0] as String
                        val rawGroup = legacyFolderName(chat[1] as String?) ?: continue
                        val displayOrder = chat[2] as Long
                        val currentFolderId = chat[3] as String?
                        val sqlTrimmedGroup = rawGroup.trim(' ')
                        val oldFolderId = UUID.nameUUIDFromBytes(
                            ("ALL:$sqlTrimmedGroup").toByteArray(StandardCharsets.UTF_8)
                        ).toString()
                        val misplacedByMixedTrimming =
                            currentFolderId == null && rawGroup.trim() != sqlTrimmedGroup
                        if (currentFolderId != oldFolderId && !misplacedByMixedTrimming) continue

                        val targetFolderId = db.query(
                            "SELECT id FROM chat_folders WHERE scope = 'ALL' AND parentFolderId IS NULL AND name = ? ORDER BY displayOrder, id LIMIT 1",
                            arrayOf(rawGroup),
                        ).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: UUID.nameUUIDFromBytes(
                            ("ALL:$rawGroup").toByteArray(StandardCharsets.UTF_8)
                        ).toString().also { folderId ->
                            db.execSQL(
                                "INSERT OR IGNORE INTO chat_folders (id, scope, name, parentFolderId, parentKey, displayOrder, pinned) VALUES (?, 'ALL', ?, NULL, '', ?, 0)",
                                arrayOf<Any?>(folderId, rawGroup, displayOrder),
                            )
                        }
                        db.execSQL(
                            "UPDATE chat_placements SET folderId = ? WHERE chatId = ? AND scope = 'ALL'",
                            arrayOf(targetFolderId, chatId),
                        )
                    }

                    val usedNames = mutableMapOf<Pair<String, String>, MutableSet<String>>()
                    val folders = db.query(
                        "SELECT id, scope, parentKey, name FROM chat_folders ORDER BY scope, parentKey, displayOrder, id"
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(arrayOf<String>(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                            }
                        }
                    }
                    for (folder in folders) {
                        val id = folder[0]
                        val key = folder[1] to folder[2]
                        val used = usedNames.getOrPut(key) { mutableSetOf() }
                        val resolvedName = nextAvailableExactFolderName(folder[3], used)
                        if (resolvedName != folder[3]) {
                            db.execSQL("UPDATE chat_folders SET name = ? WHERE id = ?", arrayOf(resolvedName, id))
                        }
                    }

                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_folders_scope_parentKey_name` ON `chat_folders` (`scope`, `parentKey`, `name`)"
                    )
                }
            }

        // 定义从版本2到3的迁移
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加group列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `group` TEXT")
                }
            }

        // 定义从版本3到4的迁移
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加displayOrder列，并用updatedAt填充现有数据
                    db.execSQL(
                        "ALTER TABLE chats ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("UPDATE chats SET displayOrder = updatedAt")
                }
            }

        // 定义从版本4到5的迁移
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加workspace列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `workspace` TEXT")
                }
            }

        // 定义从版本5到6的迁移
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 检查currentWindowSize列是否已存在，如果不存在则添加
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `currentWindowSize` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {

                    }
                }
            }

        // 定义从版本6到7的迁移
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向messages表添加roleName列
                    db.execSQL("ALTER TABLE messages ADD COLUMN `roleName` TEXT NOT NULL DEFAULT ''")
                }
            }

        // 定义从版本7到8的迁移
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加parentChatId列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `parentChatId` TEXT")
                    // 向chats表添加characterCardName列（用于绑定角色卡）
                    db.execSQL("ALTER TABLE chats ADD COLUMN `characterCardName` TEXT")
                }
            }

        // 定义从版本8到9的迁移
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向messages表添加provider列（供应商）
                    db.execSQL("ALTER TABLE messages ADD COLUMN `provider` TEXT NOT NULL DEFAULT ''")
                    // 向messages表添加modelName列（模型名称）
                    db.execSQL("ALTER TABLE messages ADD COLUMN `modelName` TEXT NOT NULL DEFAULT ''")
                }
            }

        // 定义从版本9到10的迁移
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加locked列（锁定聊天，禁止删除）
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {

                    }
                }
            }

        /** 获取数据库实例，单例模式 */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE
                ?: synchronized(this) {
                    val instance =
                        Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "app_database"
                        )
                            .addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                                MIGRATION_5_6,
                                MIGRATION_6_7,
                                MIGRATION_7_8,
                                MIGRATION_8_9,
                                MIGRATION_9_10,
                                MIGRATION_10_11,
                                MIGRATION_11_12,
                                MIGRATION_12_13,
                                MIGRATION_13_14,
                                MIGRATION_14_15,
                                MIGRATION_15_16,
                                MIGRATION_16_17,
                                MIGRATION_17_18,
                                MIGRATION_18_19,
                                MIGRATION_19_20,
                                MIGRATION_20_21,
                                MIGRATION_21_22
                            ) // 添加新的迁移
                            .build()
                    INSTANCE = instance
                    instance
                }
        }

        fun closeDatabase() {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } finally {
                    INSTANCE = null
                }
            }
        }
    }
}
