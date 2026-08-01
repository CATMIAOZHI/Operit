package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ai.assistance.operit.data.dao.ChatDao
import com.ai.assistance.operit.data.dao.ChatFolderDao
import com.ai.assistance.operit.data.dao.MessageDao
import com.ai.assistance.operit.data.dao.MessageVariantDao
import com.ai.assistance.operit.data.dao.SubagentRunDao
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.util.ChatMarkupRegex

/** 应用数据库，包含聊天表和消息表 */
@Database(
    entities = [
        ChatEntity::class,
        ChatFolderEntity::class,
        MessageEntity::class,
        MessageVariantEntity::class,
        SubagentRunEntity::class,
    ],
    version = 28,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    /** 获取聊天DAO */
    abstract fun chatDao(): ChatDao

    abstract fun chatFolderDao(): ChatFolderDao

    /** 获取消息DAO */
    abstract fun messageDao(): MessageDao

    abstract fun messageVariantDao(): MessageVariantDao

    abstract fun subagentRunDao(): SubagentRunDao

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

        internal val MIGRATION_20_24 =
            object : Migration(20, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chats ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE chats ADD COLUMN `lastMessageAt` INTEGER")
                    db.execSQL(
                        """
                        UPDATE chats
                        SET lastMessageAt = (
                            SELECT MAX(messages.timestamp)
                            FROM messages
                            WHERE messages.chatId = chats.id
                        )
                        """.trimIndent()
                    )
                }
            }

        internal val MIGRATION_24_25 =
            object : Migration(24, 25) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_folders` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `parentFolderId` TEXT,
                            `displayOrder` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`parentFolderId`) REFERENCES `chat_folders`(`id`)
                                ON UPDATE NO ACTION ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_folders_parentFolderId_displayOrder` " +
                            "ON `chat_folders` (`parentFolderId`, `displayOrder`)"
                    )
                    db.execSQL(
                        "ALTER TABLE `chats` ADD COLUMN `folderId` TEXT " +
                            "REFERENCES `chat_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_folderId` ON `chats` (`folderId`)"
                    )

                    data class BucketKey(
                        val characterCardName: String?,
                        val characterGroupId: String?,
                        val rawGroup: String,
                    )
                    data class BucketValue(
                        val chatIds: MutableList<String> = mutableListOf(),
                        var minimumDisplayOrder: Long = Long.MAX_VALUE,
                        var minimumCreatedAt: Long = Long.MAX_VALUE,
                    )

                    val buckets = linkedMapOf<BucketKey, BucketValue>()
                    db.query(
                        """
                        SELECT `id`, `characterCardName`, `characterGroupId`, `group`,
                               `displayOrder`, `createdAt`
                        FROM `chats`
                        WHERE `group` IS NOT NULL
                        """.trimIndent()
                    ).use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        val cardColumn = cursor.getColumnIndexOrThrow("characterCardName")
                        val characterGroupColumn = cursor.getColumnIndexOrThrow("characterGroupId")
                        val rawGroupColumn = cursor.getColumnIndexOrThrow("group")
                        val orderColumn = cursor.getColumnIndexOrThrow("displayOrder")
                        val createdColumn = cursor.getColumnIndexOrThrow("createdAt")
                        while (cursor.moveToNext()) {
                            val rawGroup = cursor.getString(rawGroupColumn)
                            if (rawGroup.isBlank()) continue
                            val key =
                                BucketKey(
                                    characterCardName =
                                        if (cursor.isNull(cardColumn)) null else cursor.getString(cardColumn),
                                    characterGroupId =
                                        if (cursor.isNull(characterGroupColumn)) {
                                            null
                                        } else {
                                            cursor.getString(characterGroupColumn)
                                        },
                                    rawGroup = rawGroup,
                                )
                            val bucket = buckets.getOrPut(key) { BucketValue() }
                            bucket.chatIds += cursor.getString(idColumn)
                            bucket.minimumDisplayOrder =
                                minOf(bucket.minimumDisplayOrder, cursor.getLong(orderColumn))
                            bucket.minimumCreatedAt =
                                minOf(bucket.minimumCreatedAt, cursor.getLong(createdColumn))
                        }
                    }

                    fun compareNullable(left: String?, right: String?): Int =
                        when {
                            left == null && right == null -> 0
                            left == null -> -1
                            right == null -> 1
                            else -> left.compareTo(right)
                        }

                    val typedBucketComparator =
                        Comparator<BucketKey> { left, right ->
                            compareNullable(left.characterCardName, right.characterCardName)
                                .takeIf { it != 0 }
                                ?: compareNullable(left.characterGroupId, right.characterGroupId)
                                    .takeIf { it != 0 }
                                ?: left.rawGroup.compareTo(right.rawGroup)
                        }
                    val sortedBuckets =
                        buckets.entries.sortedWith(
                            Comparator { left, right ->
                                left.value.minimumDisplayOrder.compareTo(right.value.minimumDisplayOrder)
                                    .takeIf { it != 0 }
                                    ?: left.value.minimumCreatedAt.compareTo(right.value.minimumCreatedAt)
                                        .takeIf { it != 0 }
                                    ?: typedBucketComparator.compare(left.key, right.key)
                            }
                        )

                    val allocatedIds = hashSetOf<String>()
                    sortedBuckets.forEachIndexed { index, (key, bucket) ->
                        var folderId: String
                        do {
                            folderId = java.util.UUID.randomUUID().toString()
                        } while (!allocatedIds.add(folderId))
                        db.execSQL(
                            """
                            INSERT INTO `chat_folders`
                                (`id`, `name`, `parentFolderId`, `displayOrder`, `createdAt`)
                            VALUES (?, ?, NULL, ?, ?)
                            """.trimIndent(),
                            arrayOf(
                                folderId,
                                key.rawGroup.trim(),
                                index.toLong(),
                                bucket.minimumCreatedAt,
                            )
                        )
                        bucket.chatIds.forEach { chatId ->
                            db.execSQL(
                                "UPDATE `chats` SET `folderId` = ? WHERE `id` = ?",
                                arrayOf(folderId, chatId),
                            )
                        }
                    }

                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        if (cursor.moveToFirst()) {
                            val violations = mutableListOf<String>()
                            do {
                                violations +=
                                    "${cursor.getString(0)}:${cursor.getLong(1)}:${cursor.getString(2)}"
                            } while (cursor.moveToNext())
                            error("Foreign key violations after v24 to v25 migration: $violations")
                        }
                    }
                }
            }

        internal val MIGRATION_25_26 =
            object : Migration(25, 26) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `chats` ADD COLUMN `chatKind` TEXT NOT NULL DEFAULT 'NORMAL'"
                    )
                    db.execSQL(
                        "UPDATE `chats` SET `chatKind` = 'BRANCH' WHERE `parentChatId` IS NOT NULL"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_chatKind` " +
                            "ON `chats` (`chatKind`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_parentChatId_chatKind` " +
                            "ON `chats` (`parentChatId`, `chatKind`)"
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `subagent_runs` (
                            `id` TEXT NOT NULL,
                            `parentChatId` TEXT NOT NULL,
                            `childChatId` TEXT NOT NULL,
                            `parentToolCallId` TEXT,
                            `agentProfileId` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `startedAt` INTEGER,
                            `completedAt` INTEGER,
                            `error` TEXT,
                            `agentConfigSnapshot` TEXT,
                            `modelConfigIdSnapshot` TEXT,
                            `modelIndexSnapshot` INTEGER,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`parentChatId`) REFERENCES `chats`(`id`)
                                ON UPDATE NO ACTION ON DELETE NO ACTION,
                            FOREIGN KEY(`childChatId`) REFERENCES `chats`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_subagent_runs_parentChatId` " +
                            "ON `subagent_runs` (`parentChatId`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_subagent_runs_childChatId` " +
                            "ON `subagent_runs` (`childChatId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_subagent_runs_parentChatId_parentToolCallId` " +
                            "ON `subagent_runs` (`parentChatId`, `parentToolCallId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_subagent_runs_status` " +
                            "ON `subagent_runs` (`status`)"
                    )
                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) {
                            "Foreign key violations after v25 to v26 migration"
                        }
                    }
                }
            }

        internal val MIGRATION_26_27 =
            object : Migration(26, 27) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `subagent_runs` ADD COLUMN " +
                            "`toolInvocationCount` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "UPDATE `subagent_runs` SET `toolInvocationCount` = 0"
                    )
                    val invocationCounts = mutableMapOf<String, Int>()
                    db.query(
                        """
                        SELECT `subagent_runs`.`id`, `messages`.`content`
                        FROM `subagent_runs`
                        LEFT JOIN `messages`
                            ON `messages`.`chatId` = `subagent_runs`.`childChatId`
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val runId = cursor.getString(0)
                            invocationCounts.putIfAbsent(runId, 0)
                            if (!cursor.isNull(1)) {
                                invocationCounts[runId] =
                                    invocationCounts.getValue(runId) +
                                        countFinalToolResults(cursor.getString(1))
                            }
                        }
                    }
                    db.compileStatement(
                        """
                        UPDATE `subagent_runs`
                        SET `toolInvocationCount` = ?
                        WHERE `id` = ?
                        """.trimIndent()
                    ).use { statement ->
                        invocationCounts.forEach { (runId, count) ->
                            statement.bindLong(1, count.toLong())
                            statement.bindString(2, runId)
                            statement.executeUpdateDelete()
                            statement.clearBindings()
                        }
                    }
                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) {
                            "Foreign key violations after v26 to v27 migration"
                        }
                    }
                }
            }

        internal val MIGRATION_27_28 =
            object : Migration(27, 28) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `subagent_runs` ADD COLUMN `archivedAt` INTEGER")
                }
            }

        private val finalTrueAttributeRegex =
            Regex("""\bfinal\s*=\s*["']true["']""", RegexOption.IGNORE_CASE)

        private fun countFinalToolResults(content: String): Int =
            ChatMarkupRegex.toolResultTagWithAttrs
                .findAll(content)
                .count { match ->
                    finalTrueAttributeRegex.containsMatchIn(match.groupValues[2])
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
                                MIGRATION_20_24,
                                MIGRATION_24_25,
                                MIGRATION_25_26,
                                MIGRATION_26_27,
                                MIGRATION_27_28,
                            ) // 添加新的迁移
                            // personal/dev briefly shipped experimental schemas 21-23. Only those
                            // development inputs are intentionally rebuilt; stable v20 is migrated.
                            .fallbackToDestructiveMigrationFrom(true, 21, 22, 23)
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
