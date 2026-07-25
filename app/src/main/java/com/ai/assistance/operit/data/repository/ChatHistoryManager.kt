package com.ai.assistance.operit.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ai.assistance.operit.util.AppLogger
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.backup.OperitBackupDirs
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.db.nextAvailableExactFolderName
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import com.ai.assistance.operit.data.model.CharacterCardChatStats
import com.ai.assistance.operit.data.model.CharacterGroupChatStats
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import com.ai.assistance.operit.data.model.OperitArchivedChat
import com.ai.assistance.operit.data.model.OperitArchivedFolder
import com.ai.assistance.operit.data.model.OperitArchivedMessage
import com.ai.assistance.operit.data.model.OperitArchivedMessageVariant
import com.ai.assistance.operit.data.model.OperitArchivedPlacement
import com.ai.assistance.operit.data.model.OperitChatArchive
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatFolderScope
import com.ai.assistance.operit.data.model.ChatPlacementEntity
import com.ai.assistance.operit.data.model.WorkspaceRenameResult
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.data.converter.*
import com.ai.assistance.operit.data.exporter.*
import com.google.gson.GsonBuilder
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.io.BufferedWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// 仅保留这个DataStore用于存储当前聊天ID
private val Context.currentChatIdDataStore by preferencesDataStore(name = "current_chat_id")

class FolderNameConflictException : IllegalArgumentException()

class InvalidFolderNameException : IllegalArgumentException()

data class CreatedFolderWithChat(
    val folder: ChatFolderEntity,
    val chat: ChatHistory,
)

internal fun calculateEmptyFoldersAffectedBySources(
    folders: List<ChatFolderEntity>,
    placements: List<ChatPlacementEntity>,
    sourceFolderIds: Set<String>,
): List<ChatFolderEntity> {
    if (sourceFolderIds.isEmpty()) return emptyList()

    val foldersById = folders.associateBy { it.id }
    val childrenByParent = folders.groupBy { it.parentFolderId }
    val candidateIds = mutableSetOf<String>()
    sourceFolderIds.forEach { sourceId ->
        var currentId: String? = sourceId
        val visited = mutableSetOf<String>()
        while (currentId != null && visited.add(currentId)) {
            candidateIds += currentId
            currentId = foldersById[currentId]?.parentFolderId
        }
    }

    val occupiedFolderIds = placements.mapNotNullTo(mutableSetOf()) { it.folderId }
    val subtreeCache = mutableMapOf<String, Set<String>>()
    fun subtreeIds(rootId: String): Set<String> =
        subtreeCache.getOrPut(rootId) {
            buildSet {
                val pending = mutableListOf(rootId)
                while (pending.isNotEmpty()) {
                    val id = pending.removeAt(pending.lastIndex)
                    if (!add(id)) continue
                    childrenByParent[id].orEmpty().forEach { pending += it.id }
                }
            }
        }

    val emptyCandidateIds = candidateIds.filterTo(mutableSetOf()) { candidateId ->
        subtreeIds(candidateId).none { it in occupiedFolderIds }
    }
    val deletionIds = mutableSetOf<String>()
    emptyCandidateIds.forEach { deletionIds += subtreeIds(it) }

    fun depth(folder: ChatFolderEntity): Int {
        var value = 0
        var parentId = folder.parentFolderId
        val visited = mutableSetOf(folder.id)
        while (parentId != null && visited.add(parentId)) {
            value++
            parentId = foldersById[parentId]?.parentFolderId
        }
        return value
    }

    return folders
        .filter { it.id in deletionIds }
        .sortedWith(
            compareByDescending<ChatFolderEntity> { depth(it) }
                .thenBy { it.displayOrder },
        )
}

internal fun reorderPlacementWithinCurrentFolder(
    placements: List<ChatPlacementEntity>,
    chatId: String,
    delta: Int,
): List<ChatPlacementEntity>? {
    require(delta == -1 || delta == 1) { "delta must be -1 or 1" }
    val siblings = placements.sortedBy { it.displayOrder }.toMutableList()
    val sourceIndex = siblings.indexOfFirst { it.chatId == chatId }
    if (sourceIndex < 0) return null

    val targetIndex = sourceIndex + delta
    if (targetIndex !in siblings.indices) return null

    val moved = siblings.removeAt(sourceIndex)
    siblings.add(targetIndex, moved)
    return siblings.mapIndexed { index, placement ->
        placement.copy(displayOrder = index.toLong())
    }
}

class ChatHistoryManager private constructor(private val context: Context) {
        companion object {
            private const val TAG = "ChatHistoryManager"
            private const val LOCATOR_PREVIEW_CHAR_COUNT = 48

            @Volatile
            private var INSTANCE: ChatHistoryManager? = null

            fun getInstance(context: Context): ChatHistoryManager {
                return INSTANCE
                    ?: synchronized(this) {
                        val instance = ChatHistoryManager(context.applicationContext)
                        INSTANCE = instance
                        instance
                    }
            }
        }

        // 使用Room数据库
        private val database = AppDatabase.getDatabase(context)
        private val chatDao = database.chatDao()
        private val messageDao = database.messageDao()
        private val messageVariantDao = database.messageVariantDao()
        private val chatFolderDao = database.chatFolderDao()
        private val chatPlacementDao = database.chatPlacementDao()

    // ---- IDEA 3: placement helper ----

    /** 幂等创建 ALL placement（不会重置已有 placement）。返回新生成或已存在的 placement。 */
    private suspend fun ensureAllPlacement(chatId: String): ChatPlacementEntity {
        val existing = chatPlacementDao.getPlacement(chatId, ChatFolderScope.ALL)
        if (existing != null) return existing
        val maxOrder =
            chatPlacementDao.getPlacementsInRoot(ChatFolderScope.ALL)
                .maxOfOrNull { it.displayOrder }?.let { it + 1 } ?: 0L
        val placement = ChatPlacementEntity(
            chatId = chatId,
            scope = ChatFolderScope.ALL,
            folderId = null,
            displayOrder = maxOrder,
        )
        chatPlacementDao.insertPlacement(placement)
        return placement
    }

    private suspend fun syncLegacyGroupPlacement(chatId: String, group: String?, displayOrder: Long) {
        val groupName = group?.takeUnless(String::isBlank)
        val folderId = groupName?.let { name ->
            val existing = chatFolderDao.getFolderByNameInParent(ChatFolderScope.ALL, null, name)
            existing?.id ?: run {
                val siblings = chatFolderDao.getRootFolders(ChatFolderScope.ALL)
                ChatFolderEntity(
                    scope = ChatFolderScope.ALL,
                    name = name,
                    parentFolderId = null,
                    displayOrder = siblings.maxOfOrNull { it.displayOrder }?.plus(1) ?: 0L,
                ).also { chatFolderDao.insertFolder(it) }.id
            }
        }
        chatPlacementDao.upsertPlacements(
            listOf(
                ChatPlacementEntity(
                    chatId = chatId,
                    scope = ChatFolderScope.ALL,
                    folderId = folderId,
                    displayOrder = displayOrder,
                )
            )
        )
    }
    private val operitArchiveJson =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }

    private data class ImportCounters(
        var newCount: Int = 0,
        var updatedCount: Int = 0,
        var skippedCount: Int = 0,
    )

    private sealed interface StreamImportedChat {
        data class Archive(val chat: OperitArchivedChat) : StreamImportedChat

        data class Legacy(val history: ChatHistory) : StreamImportedChat
    }

    private fun hydrateMessages(
        messageEntities: List<MessageEntity>,
        variants: List<MessageVariantEntity>,
    ): List<ChatMessage> {
        val variantsByTimestamp = variants.groupBy { it.messageTimestamp }
        return messageEntities.map { messageEntity ->
            val baseMessage = messageEntity.toChatMessage()
            val messageVariants = variantsByTimestamp[messageEntity.timestamp].orEmpty()
            val variantCount = messageVariants.size + 1
            if (messageEntity.selectedVariantIndex == 0) {
                baseMessage.copy(
                    selectedVariantIndex = 0,
                    variantCount = variantCount,
                )
            } else {
                val selectedVariant =
                    messageVariants.first { it.variantIndex == messageEntity.selectedVariantIndex }
                selectedVariant.applyTo(baseMessage, variantCount)
            }
        }
    }

    private suspend fun hydrateMessages(
        chatId: String,
        messageEntities: List<MessageEntity>,
    ): List<ChatMessage> {
        if (messageEntities.isEmpty()) {
            return emptyList()
        }
        val visibleTimestamps = messageEntities.map { it.timestamp }
        val variants = messageVariantDao.getVariantsForMessages(chatId, visibleTimestamps)
        return hydrateMessages(messageEntities, variants)
    }

    private suspend fun loadDisplayHistory(chatHistory: ChatHistory): ChatHistory {
        val messages = loadChatMessages(chatHistory.id)
        return chatHistory.copy(messages = messages)
    }

    private suspend fun loadDisplayHistories(chatHistories: List<ChatHistory>): List<ChatHistory> {
        val completeHistories = mutableListOf<ChatHistory>()
        for (chatHistory in chatHistories) {
            completeHistories.add(loadDisplayHistory(chatHistory))
        }
        return completeHistories
    }

    private suspend fun buildOperitArchivedChat(chatHistory: ChatHistory): OperitArchivedChat {
        val messageEntities = messageDao.getMessagesForChat(chatHistory.id)
        val variantsByTimestamp =
            messageVariantDao.getVariantsForChat(chatHistory.id).groupBy { it.messageTimestamp }
        val archivedMessages =
            messageEntities.map { messageEntity ->
                val messageVariants = variantsByTimestamp[messageEntity.timestamp].orEmpty()
                OperitArchivedMessage(
                    baseMessage =
                        messageEntity.toChatMessage().copy(
                            variantCount = messageVariants.size + 1,
                        ),
                    variants = messageVariants.map(OperitArchivedMessageVariant::fromEntity),
                )
            }
        return OperitArchivedChat.fromChatHistory(chatHistory, archivedMessages)
    }

    private suspend fun exportOperitArchiveJsonStream(
        file: File,
        chatHistories: List<ChatHistory>,
    ) {
        AppLogger.d(TAG, "开始流式导出 Operit 聊天记录，共 ${chatHistories.size} 个会话，目标=${file.absolutePath}")

        // IDEA 3: 查询所有 folders 和 placements（ALL + FAVORITE）
        val folders = chatFolderDao.getAllFolders(ChatFolderScope.ALL) +
            chatFolderDao.getAllFolders(ChatFolderScope.FAVORITE)
        val placements = chatPlacementDao.getAllPlacements(ChatFolderScope.ALL) +
            chatPlacementDao.getAllPlacements(ChatFolderScope.FAVORITE)

        val archiveFolders = folders.map { f ->
            OperitArchivedFolder(
                id = f.id,
                scope = f.scope.value,
                name = f.name,
                parentFolderId = f.parentFolderId,
                displayOrder = f.displayOrder,
                pinned = f.pinned,
            )
        }
        val archivePlacements = placements.map { p ->
            OperitArchivedPlacement(
                chatId = p.chatId,
                scope = p.scope.value,
                folderId = p.folderId,
                displayOrder = p.displayOrder,
            )
        }

        BufferedWriter(
            OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8),
        ).use { writer ->
            writer.append("{\n")
            writer.append("  \"archiveType\": ")
            writer.append(operitArchiveJson.encodeToString(OperitChatArchive.ARCHIVE_TYPE))
            writer.append(",\n")
            writer.append("  \"formatVersion\": ${OperitChatArchive.CURRENT_FORMAT_VERSION},\n")
            writer.append("  \"exportedAt\": ${System.currentTimeMillis()},\n")

            // folders
            writer.append("  \"folders\": ")
            writer.append(operitArchiveJson.encodeToString(archiveFolders))
            writer.append(",\n")

            // placements
            writer.append("  \"placements\": ")
            writer.append(operitArchiveJson.encodeToString(archivePlacements))
            writer.append(",\n")

            writer.append("  \"chats\": [")

            chatHistories.forEachIndexed { index, chatHistory ->
                val archivedChat = buildOperitArchivedChat(chatHistory)
                if (index == 0) {
                    writer.append('\n')
                } else {
                    writer.append(",\n")
                }
                writer.append(operitArchiveJson.encodeToString(archivedChat))
                writer.flush()
                if ((index + 1) % 20 == 0 || index == chatHistories.lastIndex) {
                    AppLogger.d(
                        TAG,
                        "流式导出进度: ${index + 1}/${chatHistories.size}，chatId=${archivedChat.id}，messages=${archivedChat.messages.size}",
                    )
                }
            }

            if (chatHistories.isNotEmpty()) {
                writer.append('\n')
            }
            writer.append("  ]\n")
            writer.append("}\n")
        }
        AppLogger.d(TAG, "流式导出 Operit 聊天记录完成，共 ${chatHistories.size} 个会话，目标=${file.absolutePath}")
    }

    private fun <T> decodeStreamElement(
        reader: JsonReader,
        decode: (String) -> T,
    ): T {
        val element = Streams.parse(reader)
        return decode(element.toString())
    }

    private suspend fun consumeImportedChat(
        imported: StreamImportedChat,
        existingIds: MutableSet<String>,
        importedIds: MutableSet<String>,
        counters: ImportCounters,
        importedIndex: Int,
    ) {
        when (imported) {
            is StreamImportedChat.Archive -> {
                val archivedChat = imported.chat
                if (archivedChat.messages.isEmpty()) {
                    counters.skippedCount++
                    AppLogger.w(TAG, "流式导入跳过空归档会话: index=$importedIndex, chatId=${archivedChat.id}")
                    return
                }

                val existed = existingIds.contains(archivedChat.id)
                if (existed) {
                    counters.updatedCount++
                } else {
                    counters.newCount++
                    existingIds.add(archivedChat.id)
                }

                saveArchivedChat(archivedChat)
                importedIds.add(archivedChat.id)

                if (importedIndex % 20 == 0) {
                    AppLogger.d(
                        TAG,
                        "流式导入进度: index=$importedIndex, archive chatId=${archivedChat.id}, messages=${archivedChat.messages.size}, new=${counters.newCount}, updated=${counters.updatedCount}, skipped=${counters.skippedCount}",
                    )
                }
            }

            is StreamImportedChat.Legacy -> {
                val chatHistory = imported.history
                if (chatHistory.messages.isEmpty()) {
                    counters.skippedCount++
                    AppLogger.w(TAG, "流式导入跳过空会话: index=$importedIndex, chatId=${chatHistory.id}")
                    return
                }

                val existed = existingIds.contains(chatHistory.id)
                if (existed) {
                    counters.updatedCount++
                } else {
                    counters.newCount++
                    existingIds.add(chatHistory.id)
                }

                saveChatHistory(chatHistory)
                importedIds.add(chatHistory.id)

                if (importedIndex % 20 == 0) {
                    AppLogger.d(
                        TAG,
                        "流式导入进度: index=$importedIndex, legacy chatId=${chatHistory.id}, messages=${chatHistory.messages.size}, new=${counters.newCount}, updated=${counters.updatedCount}, skipped=${counters.skippedCount}",
                    )
                }
            }
        }
    }

    private suspend fun importOperitChatHistoriesStream(
        inputStream: InputStream,
        existingIds: MutableSet<String>,
    ): ChatImportResult {
        val counters = ImportCounters()
        val importedIds = mutableSetOf<String>()
        var importedIndex = 0
        var archivedFolders: List<OperitArchivedFolder> = emptyList()
        var archivedPlacements: List<OperitArchivedPlacement> = emptyList()
        var formatVersion = 0

        JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = true
            when (reader.peek()) {
                JsonToken.END_DOCUMENT -> {
                    throw Exception(context.getString(R.string.chat_history_imported_file_empty))
                }

                JsonToken.BEGIN_OBJECT -> {
                    AppLogger.d(TAG, "检测到 Operit 归档对象，开始流式导入")
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "formatVersion" -> {
                                formatVersion = reader.nextInt()
                            }

                            "folders" -> {
                                val raw = Streams.parse(reader)
                                archivedFolders = operitArchiveJson.decodeFromString<List<OperitArchivedFolder>>(raw.toString())
                                AppLogger.d(TAG, "导入归档文件夹 ${archivedFolders.size} 个")
                            }

                            "placements" -> {
                                val raw = Streams.parse(reader)
                                archivedPlacements = operitArchiveJson.decodeFromString<List<OperitArchivedPlacement>>(raw.toString())
                                AppLogger.d(TAG, "导入归档 placement ${archivedPlacements.size} 个")
                            }

                            "chats" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    importedIndex++
                                    val archivedChat =
                                        decodeStreamElement(reader) {
                                            operitArchiveJson.decodeFromString<OperitArchivedChat>(it)
                                        }
                                    consumeImportedChat(
                                        StreamImportedChat.Archive(archivedChat),
                                        existingIds,
                                        importedIds,
                                        counters,
                                        importedIndex,
                                    )
                                }
                                reader.endArray()
                            }

                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }

                JsonToken.BEGIN_ARRAY -> {
                    AppLogger.d(TAG, "检测到旧版聊天数组，开始流式导入")
                    reader.beginArray()
                    while (reader.hasNext()) {
                        importedIndex++
                        val chatHistory =
                            decodeStreamElement(reader) {
                                operitArchiveJson.decodeFromString<ChatHistory>(it)
                            }
                        consumeImportedChat(
                            StreamImportedChat.Legacy(chatHistory),
                            existingIds,
                            importedIds,
                            counters,
                            importedIndex,
                        )
                    }
                    reader.endArray()
                }

                else -> {
                    throw Exception(context.getString(R.string.chat_history_parse_backup_failed, "unexpected json token"))
                }
            }
        }

        // ---- IDEA 3: 导入后恢复文件夹和 placement（v3+） ----
        if (formatVersion >= 3 && (archivedFolders.isNotEmpty() || archivedPlacements.isNotEmpty())) {
            val folderRemap = applyArchivedFolders(archivedFolders)
            applyArchivedPlacements(archivedPlacements, importedIds, folderRemap)
        } else {
            restoreLegacyGroupPlacements(importedIds)
        }

        AppLogger.d(
            TAG,
            "流式导入完成: total=$importedIndex, new=${counters.newCount}, updated=${counters.updatedCount}, skipped=${counters.skippedCount}",
        )
        return ChatImportResult(counters.newCount, counters.updatedCount, counters.skippedCount)
    }

    /**
     * IDEA 3: 应用归档文件夹（有冲突则 ID remap）。
     * 拓扑排序保证父文件夹先创建；验证 scope、父子关系，处理悬空父节点。
     * @return 归档 ID → 最终本地 ID 的映射（含跳过的同 ID 同名校验也不覆盖，但记录在 remap）
     */
    private suspend fun applyArchivedFolders(folders: List<OperitArchivedFolder>): Map<String, String> {
        val remap = mutableMapOf<String, String>()
        val validScopes = setOf("ALL", "FAVORITE")
        val folderById = folders.filter { it.scope in validScopes }.associateBy { it.id }

        // 拓扑排序：父节点优先（P1-7 fix）
        val sorted = mutableListOf<OperitArchivedFolder>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            if (id in visiting) {
                AppLogger.w(TAG, "归档文件夹存在环: $id，跳过该文件夹及后代")
                return
            }
            val folder = folderById[id] ?: run {
                visited.add(id)
                return
            }
            visiting.add(id)
            folder.parentFolderId?.let { visit(it) }
            visiting.remove(id)
            visited.add(id)
            sorted.add(folder)
        }
        for (id in folderById.keys) visit(id)

        for (folder in sorted) {
            val scope = ChatFolderScope.fromValue(folder.scope)
            var resolvedParentId = when {
                folder.parentFolderId == null -> null
                folder.parentFolderId in remap -> remap[folder.parentFolderId]
                chatFolderDao.getFolderById(folder.parentFolderId!!) != null -> folder.parentFolderId
                else -> {
                    AppLogger.w(TAG, "归档文件夹 ${folder.id} 的父节点 ${folder.parentFolderId} 不存在，提升到根目录")
                    null
                }
            }
            if (resolvedParentId != null) {
                val parent = chatFolderDao.getFolderById(resolvedParentId)
                if (parent == null || parent.scope != scope) {
                    AppLogger.w(TAG, "归档文件夹 ${folder.id} 的父节点 scope 不匹配，提升到根目录")
                    resolvedParentId = null
                }
            }

            val existing = chatFolderDao.getFolderById(folder.id)
            suspend fun expectedNameExcluding(candidateId: String): String {
                val otherNames = chatFolderDao.getChildFolders(scope, resolvedParentId)
                    .filter { it.id != candidateId }
                    .mapTo(mutableSetOf()) { it.name }
                return nextAvailableExactFolderName(folder.name, otherNames)
            }
            if (
                existing != null &&
                    existing.scope == scope &&
                    existing.parentFolderId == resolvedParentId
            ) {
                if (existing.name == expectedNameExcluding(existing.id)) {
                    remap[folder.id] = folder.id
                    continue
                }
            }
            var reuseResolvedFolder = false
            val resolvedId = if (existing == null) {
                folder.id
            } else {
                var salt = 0
                var candidateId: String
                while (true) {
                    candidateId = UUID.nameUUIDFromBytes(
                        "archive-folder:${folder.id}:${scope.value}:${resolvedParentId.orEmpty()}:${folder.name}:$salt"
                            .toByteArray(StandardCharsets.UTF_8)
                    ).toString()
                    val remappedExisting = chatFolderDao.getFolderById(candidateId)
                    if (remappedExisting == null) break
                    if (
                        remappedExisting.scope == scope &&
                            remappedExisting.parentFolderId == resolvedParentId &&
                            remappedExisting.name == expectedNameExcluding(candidateId)
                    ) {
                        reuseResolvedFolder = true
                        break
                    }
                    salt++
                }
                candidateId
            }
            remap[folder.id] = resolvedId
            if (reuseResolvedFolder) {
                continue
            }

            try {
                val usedNames = chatFolderDao.getChildFolders(scope, resolvedParentId)
                    .mapTo(mutableSetOf()) { it.name }
                val resolvedName = nextAvailableExactFolderName(folder.name, usedNames)
                val entity = ChatFolderEntity(
                    id = resolvedId,
                    scope = scope,
                    name = resolvedName,
                    parentFolderId = resolvedParentId,
                    displayOrder = folder.displayOrder,
                    pinned = folder.pinned,
                )
                chatFolderDao.insertFolder(entity)
            } catch (e: Exception) {
                AppLogger.w(TAG, "无法导入归档文件夹 ${folder.id}: ${e.message}")
            }
        }
        return remap
    }

    /**
     * IDEA 3: 应用归档 placement（P1-3 fix: 使用 upsertPlacements 覆盖 ALL placement 信息；
     *   P1-R2/R3 fix: 对悬空 folderId 置 null，用 remap 修正冲突 ID）。
     * 只更新归档包含的对话，不删除本地其他 placement。
     * v2 导入不产生 FAVORITE placement。
     */
    private suspend fun applyArchivedPlacements(
        placements: List<OperitArchivedPlacement>,
        importedIds: Set<String>,
        folderRemap: Map<String, String> = emptyMap(),
    ) {
        val validPlacements = placements.mapNotNull { pl ->
            val scope = try {
                ChatFolderScope.fromValue(pl.scope)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (pl.chatId !in importedIds) return@mapNotNull null
            // P1-R3 fix: 用 remap 修正冲突 folder ID
            val resolvedFolderId = when {
                pl.folderId == null -> null
                pl.folderId in folderRemap -> folderRemap[pl.folderId]
                else -> folderRemap[pl.folderId] ?: pl.folderId
            }
            val validatedFolderId = resolvedFolderId?.let { folderId ->
                val folder = chatFolderDao.getFolderById(folderId)
                if (folder?.scope == scope) {
                    folderId
                } else {
                    AppLogger.w(TAG, "归档 placement 的 folderId $folderId 不存在或 scope 不匹配，提升到根目录")
                    null
                }
            }
            ChatPlacementEntity(
                chatId = pl.chatId,
                scope = scope,
                folderId = validatedFolderId,
                displayOrder = pl.displayOrder,
            )
        }
        // P1-R2 fix: 单条写入 + try/catch，避免批量失败
        for (pl in validPlacements) {
            try {
                chatPlacementDao.upsertPlacements(listOf(pl))
            } catch (e: Exception) {
                AppLogger.w(TAG, "无法导入 placement ${pl.chatId}/${pl.scope}: ${e.message}")
            }
        }
    }

    init {
        // 确保数据库被初始化
        AppLogger.d(TAG, "ChatHistoryManager初始化，预加载数据库")
        // 使用独立的协程作用域触发数据库初始化
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 预先尝试执行一个简单查询
                val chats = chatDao.getAllChats().first()
                database.withTransaction {
                    ChatFolderScope.entries.forEach { scope ->
                        val folderIds =
                            chatFolderDao.getAllFolders(scope).mapTo(mutableSetOf()) { it.id }
                        pruneEmptyFoldersAffectedBy(scope, folderIds)
                    }
                }
                AppLogger.d(TAG, "数据库预加载完成，现有聊天数：${chats.size}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "数据库预加载失败", e)
            }
        }
    }

    // 互斥锁用于同步操作
    private val globalMutex = Mutex()
    private val chatMutexes = ConcurrentHashMap<String, Mutex>()

    private fun chatMutex(chatId: String): Mutex {
        return chatMutexes.getOrPut(chatId) { Mutex() }
    }

    // DataStore键
    private object PreferencesKeys {
        val CURRENT_CHAT_ID = stringPreferencesKey("current_chat_id")
    }

    // 辅助函数：将ChatEntity转换为ChatHistory
    private fun ChatEntity.toChatHistory(): ChatHistory {
        val createdAt = Instant.ofEpochMilli(this.createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

        val updatedAt = Instant.ofEpochMilli(this.updatedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

        return ChatHistory(
            id = this.id,
            title = this.title,
            messages = emptyList(), // 关键改动：不加载完整消息，以提高侧边栏性能
            createdAt = createdAt,
            updatedAt = updatedAt,
            inputTokens = this.inputTokens,
            outputTokens = this.outputTokens,
            currentWindowSize = this.currentWindowSize,
            group = this.group, // 映射group字段
            displayOrder = this.displayOrder,
            workspace = this.workspace, // 映射workspace字段
            workspaceEnv = this.workspaceEnv, // 映射workspaceEnv字段
            parentChatId = this.parentChatId, // 映射parentChatId字段
            characterCardName = this.characterCardName, // 映射characterCardName字段
            characterGroupId = this.characterGroupId, // 映射characterGroupId字段
            locked = this.locked,
            pinned = this.pinned,
            lastMessageAt = this.lastMessageAt?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
            }
        )
    }

    // 获取所有聊天历史（转换为UI层需要的ChatHistory对象）
    private val _chatHistoriesFlow: Flow<List<ChatHistory>> =
        // 使用原始的Flow方式，这样可以确保数据库变化时会自动刷新
        chatDao.getAllChats().map { chatEntities ->
            // AppLogger.d(TAG, "加载聊天列表，共 ${chatEntities.size} 个聊天")

            // 使用withContext将处理移至IO线程
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                chatEntities.map { it.toChatHistory() }
            }
        }

    // 转换为StateFlow以便共享
    val chatHistoriesFlow =
        _chatHistoriesFlow.stateIn(
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            SharingStarted.Lazily,
            emptyList()
        )

    suspend fun getTotalChatCount(): Int {
        return withContext(Dispatchers.IO) { chatDao.getTotalChatCount() }
    }

    suspend fun getTotalMessageCount(): Int {
        return withContext(Dispatchers.IO) { messageDao.getTotalMessageCount() }
    }

    suspend fun getMessageCountsByChatId(): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.getMessageCountsByChatId().associate { it.chatId to it.count }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get message counts by chatId", e)
                emptyMap()
            }
        }
    }

    // 角色卡聊天统计
    val characterCardStatsFlow: Flow<List<CharacterCardChatStats>> =
        chatDao.getCharacterCardChatStats()
    val characterGroupStatsFlow: Flow<List<CharacterGroupChatStats>> =
        chatDao.getCharacterGroupChatStats()

    /**
     * 根据角色卡过滤聊天历史
     * @param characterCardName 角色卡名称
     * @param isDefault 是否为默认角色卡
     * @return 过滤后的聊天历史Flow
     */
    fun getChatHistoriesByCharacterCard(
        characterCardName: String,
        isDefault: Boolean
    ): Flow<List<ChatHistory>> {
        val sourceFlow = if (isDefault) {
            // 默认角色卡：显示该角色卡名称的对话 + 所有characterCardName为null的对话
            chatDao.getChatsByCharacterCardOrNull(characterCardName)
        } else {
            // 非默认角色卡：只显示该角色卡名称的对话
            chatDao.getChatsByCharacterCard(characterCardName)
        }

        return sourceFlow.map { chatEntities ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                chatEntities.map { it.toChatHistory() }
            }
        }
    }

    // ============================================================
    // IDEA 3: 消息分类、文件夹树、收藏和最近查询
    // ============================================================

    /** 轻量树节点，供 UI 扁平化展示 */
    sealed interface HistoryTreeNode {
        val stableKey: String
        val depth: Int

        data class Folder(
            val folder: ChatFolderEntity,
            override val depth: Int,
        ) : HistoryTreeNode {
            override val stableKey: String get() = "folder:${folder.id}"
        }

        data class Chat(
            val history: ChatHistory,
            override val depth: Int,
        ) : HistoryTreeNode {
            override val stableKey: String get() = "chat:${history.id}"
        }

        data class CharacterHeader(
            val name: String,
            override val depth: Int = 0,
        ) : HistoryTreeNode {
            override val stableKey: String get() = "character:$name"
        }
    }

    /** 获取最近消息 flow，严格按最后消息时间倒序 */
    val recentChatsFlow: Flow<List<ChatHistory>> =
        chatDao.observeRecentChats().map { entities ->
            entities.map { it.toChatHistory() }
        }

    /** 收藏的对话 ID 集合 flow */
    val favoriteChatIdsFlow: Flow<Set<String>> =
        chatPlacementDao.observeAllPlacements(ChatFolderScope.FAVORITE)
            .map { placements -> placements.map { it.chatId }.toSet() }
            .catch { emit(emptySet()) }

    /**
     * 获取所有 scope 的文件夹 Flow（用于构建树）。
     * 返回 scope 下所有文件夹列表（包含所有深度）。
     */
    fun observeFolders(scope: ChatFolderScope): Flow<List<ChatFolderEntity>> =
        chatFolderDao.observeAllFolders(scope)

    fun observePlacements(scope: ChatFolderScope): Flow<List<ChatPlacementEntity>> =
        chatPlacementDao.observeAllPlacements(scope)

    // ---- 文件夹操作 ----

    private fun createBlankChatHistory(
        characterCardName: String?,
        characterGroupId: String?,
    ): ChatHistory {
        val dateTime = LocalDateTime.now()
        val formattedTime =
            "${dateTime.hour}:${
                dateTime.minute.toString().padStart(2, '0')
            }:${dateTime.second.toString().padStart(2, '0')}"
        val localizedContext = LocaleUtils.getLocalizedContext(context)
        return ChatHistory(
            title = "${localizedContext.getString(R.string.new_conversation)} $formattedTime",
            messages = emptyList(),
            inputTokens = 0,
            outputTokens = 0,
            characterCardName = characterCardName,
            characterGroupId = characterGroupId,
        )
    }

    /**
     * 创建文件夹及其首个对话。
     *
     * 文件夹在产品语义上不能以空状态存在，因此文件夹、聊天实体和 placement
     * 必须在同一事务内创建。FAVORITE scope 仍会为真实聊天保留根级 ALL placement。
     */
    suspend fun createFolderWithChat(
        scope: ChatFolderScope,
        parentFolderId: String?,
        name: String,
        characterCardName: String?,
        characterGroupId: String?,
    ): CreatedFolderWithChat {
        if (name.isBlank()) throw InvalidFolderNameException()
        val newHistory = createBlankChatHistory(characterCardName, characterGroupId)
        return database.withTransaction {
            if (parentFolderId != null) {
                val parent = chatFolderDao.getFolderById(parentFolderId)
                    ?: throw IllegalArgumentException("Parent folder not found: $parentFolderId")
                require(parent.scope == scope) { "Cannot create folder across scopes" }
            }
            if (chatFolderDao.folderNameExistsInParent(scope, parentFolderId, name)) {
                throw FolderNameConflictException()
            }

            val folderSiblings = chatFolderDao.getChildFolders(scope, parentFolderId)
            val folder = ChatFolderEntity(
                scope = scope,
                name = name,
                parentFolderId = parentFolderId,
                displayOrder = folderSiblings.maxOfOrNull { it.displayOrder }?.plus(1) ?: 0L,
            )
            val chatEntity = ChatEntity.fromChatHistory(newHistory)

            chatFolderDao.insertFolder(folder)
            chatDao.insertChat(chatEntity)
            ensureAllPlacement(chatEntity.id)

            val destinationOrder =
                chatPlacementDao.getPlacementsInFolder(scope, folder.id)
                    .maxOfOrNull { it.displayOrder }?.plus(1) ?: 0L
            chatPlacementDao.upsertPlacements(
                listOf(
                    ChatPlacementEntity(
                        chatId = chatEntity.id,
                        scope = scope,
                        folderId = folder.id,
                        displayOrder = destinationOrder,
                    )
                )
            )

            CreatedFolderWithChat(folder = folder, chat = newHistory)
        }
    }

    private suspend fun restoreLegacyGroupPlacements(importedIds: Set<String>) {
        if (importedIds.isEmpty()) return
        database.withTransaction {
            val chats = importedIds.mapNotNull { chatDao.getChatById(it) }
            val grouped = chats.groupBy { it.group?.takeUnless(String::isBlank) }
            val folderIds = mutableMapOf<String, String>()
            for ((groupName, members) in grouped.entries.filter { it.key != null }.sortedBy { entry ->
                entry.value.minOfOrNull { it.displayOrder } ?: Long.MAX_VALUE
            }) {
                val name = groupName ?: continue
                val existing = chatFolderDao.getFolderByNameInParent(ChatFolderScope.ALL, null, name)
                val folder = existing ?: ChatFolderEntity(
                    scope = ChatFolderScope.ALL,
                    name = name,
                    parentFolderId = null,
                    displayOrder = members.minOfOrNull { it.displayOrder } ?: 0L,
                ).also { chatFolderDao.insertFolder(it) }
                folderIds[name] = folder.id
            }
            val placements = chats.map { chat ->
                val groupName = chat.group?.takeUnless(String::isBlank)
                ChatPlacementEntity(
                    chatId = chat.id,
                    scope = ChatFolderScope.ALL,
                    folderId = groupName?.let(folderIds::get),
                    displayOrder = chat.displayOrder,
                )
            }
            chatPlacementDao.upsertPlacements(placements)
        }
    }

    /** 重命名文件夹 */
    suspend fun renameFolder(folderId: String, name: String) {
        if (name.isBlank()) throw InvalidFolderNameException()
        database.withTransaction {
            val folder = chatFolderDao.getFolderById(folderId)
                ?: throw IllegalArgumentException("Folder not found: $folderId")
            if (name == folder.name) return@withTransaction
            if (
                chatFolderDao.folderNameExistsInParentExcluding(
                    folder.scope,
                    folder.parentFolderId,
                    name,
                    folderId,
                )
            ) {
                throw FolderNameConflictException()
            }
            chatFolderDao.renameFolder(folderId, name)
        }
    }

    /** 置顶/取消置顶文件夹 */
    suspend fun setFolderPinned(folderId: String, pinned: Boolean) {
        chatFolderDao.setFolderPinned(folderId, pinned)
    }

    /**
     * 移动文件夹到目标父文件夹的指定位置。
     * 拒绝跨 scope、移入自身和移入后代。
     */
    suspend fun moveFolder(folderId: String, targetParentFolderId: String?, targetIndex: Int) {
        database.withTransaction {
            val folder = chatFolderDao.getFolderById(folderId)
                ?: throw IllegalArgumentException("Folder not found: $folderId")
            val sourceParentFolderId = folder.parentFolderId
            if (targetParentFolderId != null) {
                val targetFolder = chatFolderDao.getFolderById(targetParentFolderId)
                    ?: throw IllegalArgumentException("Target folder not found")
                if (targetFolder.scope != folder.scope) {
                    throw IllegalArgumentException("Cannot move folder across scopes")
                }
                if (
                    folderId == targetParentFolderId ||
                        isAncestor(folderId, targetParentFolderId)
                ) {
                    throw IllegalArgumentException("Cannot move folder into itself or descendant")
                }
            }
            if (
                chatFolderDao.folderNameExistsInParentExcluding(
                    folder.scope,
                    targetParentFolderId,
                    folder.name,
                    folder.id,
                )
            ) {
                throw FolderNameConflictException()
            }
            val siblings = chatFolderDao.getChildFolders(folder.scope, targetParentFolderId)
                .filter { it.id != folderId }
                .toMutableList()
            val destination = targetIndex.coerceIn(0, siblings.size)
            siblings.add(destination, folder.copy(parentFolderId = targetParentFolderId))
            siblings.forEachIndexed { index, sibling ->
                chatFolderDao.moveFolder(
                    sibling.id,
                    targetParentFolderId,
                    index.toLong(),
                )
            }
            if (sourceParentFolderId != null && sourceParentFolderId != targetParentFolderId) {
                pruneEmptyFoldersAffectedBy(folder.scope, setOf(sourceParentFolderId))
            }
        }
    }

    private suspend fun isAncestor(ancestorId: String, nodeId: String): Boolean {
        var current: String? = nodeId
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (current == ancestorId) return true
            current = chatFolderDao.getFolderById(current)?.parentFolderId
        }
        return false
    }

    private suspend fun pruneEmptyFoldersAffectedBy(
        scope: ChatFolderScope,
        sourceFolderIds: Set<String>,
    ): List<ChatFolderEntity> {
        val folders = chatFolderDao.getAllFolders(scope)
        val placements = chatPlacementDao.getAllPlacements(scope)
        val deleted =
            calculateEmptyFoldersAffectedBySources(folders, placements, sourceFolderIds)
        deleted.forEach { folder -> chatFolderDao.deleteFolder(folder.id) }
        return deleted
    }

    /** 返回删除该聊天后会因整个子树无聊天而被清理的文件夹，供 UI 预先确认。 */
    suspend fun foldersDeletedWithChat(chatId: String): List<ChatFolderEntity> =
        database.withTransaction {
            val placementsForChat = chatPlacementDao.getPlacementsForChat(chatId)
            placementsForChat
                .groupBy { it.scope }
                .flatMap { (scope, chatPlacements) ->
                    val folders = chatFolderDao.getAllFolders(scope)
                    val remainingPlacements =
                        chatPlacementDao.getAllPlacements(scope).filter { it.chatId != chatId }
                    calculateEmptyFoldersAffectedBySources(
                        folders = folders,
                        placements = remainingPlacements,
                        sourceFolderIds =
                            chatPlacements.mapNotNullTo(mutableSetOf()) { it.folderId },
                    )
                }
                .distinctBy { it.id }
        }

    /**
     * 删除文件夹，将直属子文件夹和 placement 提升到父级。
     * 收藏文件夹删除不删除真实对话。
     */
    suspend fun deleteFolder(folderId: String) {
        database.withTransaction {
            val folder = chatFolderDao.getFolderById(folderId)
                ?: throw IllegalArgumentException("Folder not found: $folderId")
            val parentId = folder.parentFolderId
            val children = chatFolderDao.getChildFolders(folder.scope, folderId)
            val placements = chatPlacementDao.getPlacementsInFolder(folder.scope, folderId)
            val parentSiblingFolders = chatFolderDao.getChildFolders(folder.scope, parentId)
                .filter { it.id != folder.id }
            val usedNames = parentSiblingFolders.mapTo(mutableSetOf()) { it.name }
            var nextOrder = (parentSiblingFolders.maxOfOrNull { it.displayOrder } ?: 0L) + 1
            val parentPlacements = chatPlacementDao.getPlacementsInFolder(folder.scope, parentId)
            var nextPlOrder = (parentPlacements.maxOfOrNull { it.displayOrder } ?: 0L) + 1

            val finalNames = children.associate { child ->
                child.id to nextAvailableExactFolderName(child.name, usedNames)
            }
            val occupiedNames = (children.map { it.name } + usedNames).toMutableSet()
            for (child in children) {
                var temporaryName = ".operit-${child.id}"
                while (!occupiedNames.add(temporaryName)) temporaryName += "-"
                chatFolderDao.renameFolder(child.id, temporaryName)
                chatFolderDao.reparentFolder(child.id, parentId, nextOrder++)
                chatFolderDao.renameFolder(child.id, finalNames.getValue(child.id))
            }
            for (placement in placements) {
                chatPlacementDao.movePlacement(placement.chatId, placement.scope, parentId, nextPlOrder++)
            }
            chatFolderDao.deleteFolder(folderId)
        }
    }

    // ---- 会话位置 & 收藏 ----

    /**
     * 移动对话到目标文件夹的指定位置。
     * scope 内完整 sibling 集合参与重排；不修改 updatedAt。
     */
    suspend fun moveChat(chatId: String, scope: ChatFolderScope, targetFolderId: String?, targetIndex: Int) {
        database.withTransaction {
            val current = chatPlacementDao.getPlacement(chatId, scope)
                ?: throw IllegalArgumentException("Placement not found for chat $chatId in scope $scope")
            val sourceFolderId = current.folderId
            if (targetFolderId != null) {
                val targetFolder = chatFolderDao.getFolderById(targetFolderId)
                    ?: throw IllegalArgumentException("Target folder not found")
                require(targetFolder.scope == scope) { "Cannot move chat across scopes" }
            }
            val siblings = chatPlacementDao.getPlacementsInFolder(scope, targetFolderId)
                .filter { it.chatId != chatId }
                .toMutableList()
            siblings.add(
                targetIndex.coerceIn(0, siblings.size),
                current.copy(folderId = targetFolderId),
            )
            siblings.forEachIndexed { index, placement ->
                chatPlacementDao.movePlacement(
                    placement.chatId,
                    placement.scope,
                    targetFolderId,
                    index.toLong(),
                )
            }
            if (sourceFolderId != null && sourceFolderId != targetFolderId) {
                pruneEmptyFoldersAffectedBy(scope, setOf(sourceFolderId))
            }
        }
    }

    /**
     * 在对话当前所在文件夹内移动一个相邻位置。
     *
     * 查询和重排处于同一事务中，并且只更新 placement 顺序，避免旧版 group 排序链路
     * 改写 folderId 或其他 scope 的 placement。
     */
    suspend fun moveChatWithinCurrentFolder(
        chatId: String,
        scope: ChatFolderScope,
        delta: Int,
    ): Boolean {
        require(delta == -1 || delta == 1) { "delta must be -1 or 1" }
        return database.withTransaction {
            val current =
                chatPlacementDao.getPlacement(chatId, scope)
                    ?: return@withTransaction false
            val reordered =
                reorderPlacementWithinCurrentFolder(
                    placements =
                        chatPlacementDao.getPlacementsInFolder(scope, current.folderId),
                    chatId = chatId,
                    delta = delta,
                )
                    ?: return@withTransaction false

            reordered.forEach { placement ->
                chatPlacementDao.updatePlacementOrder(
                    chatId = placement.chatId,
                    scope = scope,
                    displayOrder = placement.displayOrder,
                )
            }
            true
        }
    }

    /** 删除整个文件夹子树及其中未锁定的真实对话；锁定对话会保留并回到根目录。 */
    suspend fun deleteFolderAndChats(folderId: String) {
        val root = chatFolderDao.getFolderById(folderId)
            ?: throw IllegalArgumentException("Folder not found: $folderId")
        val allFolders = chatFolderDao.getAllFolders(root.scope)
        val childrenByParent = allFolders.groupBy { it.parentFolderId }
        val folderIds = mutableSetOf<String>()
        fun collect(id: String) {
            if (!folderIds.add(id)) return
            childrenByParent[id].orEmpty().forEach { collect(it.id) }
        }
        collect(folderId)

        val affectedChatIds = chatPlacementDao.getAllPlacements(root.scope)
            .filter { it.folderId in folderIds }
            .map { it.chatId }
            .distinct()

        if (root.scope == ChatFolderScope.FAVORITE) {
            database.withTransaction {
                affectedChatIds.forEach { chatId ->
                    chatPlacementDao.deletePlacement(chatId, ChatFolderScope.FAVORITE)
                }
            }
        } else {
            affectedChatIds.forEach { deleteChatHistory(it) }
        }

        database.withTransaction {
            allFolders
                .filter { it.id in folderIds }
                .sortedByDescending { folderDepth(it, allFolders.associateBy { folder -> folder.id }) }
                .forEach { chatFolderDao.deleteFolder(it.id) }
        }
    }

    private fun folderDepth(folder: ChatFolderEntity, foldersById: Map<String, ChatFolderEntity>): Int {
        var depth = 0
        var parentId = folder.parentFolderId
        val visited = mutableSetOf(folder.id)
        while (parentId != null && visited.add(parentId)) {
            depth++
            parentId = foldersById[parentId]?.parentFolderId
        }
        return depth
    }

    /** 收藏/取消收藏对话 */
    suspend fun setChatFavorite(chatId: String, favorite: Boolean) {
        if (favorite) {
            val existing = chatPlacementDao.getPlacement(chatId, ChatFolderScope.FAVORITE)
            if (existing != null) return // 幂等
            val maxOrder =
                (chatPlacementDao.getPlacementsInRoot(ChatFolderScope.FAVORITE)
                    .maxOfOrNull { it.displayOrder } ?: 0L) + 1
            chatPlacementDao.insertPlacement(
                ChatPlacementEntity(
                    chatId = chatId,
                    scope = ChatFolderScope.FAVORITE,
                    folderId = null,
                    displayOrder = maxOrder,
                )
            )
        } else {
            chatPlacementDao.deletePlacement(chatId, ChatFolderScope.FAVORITE)
        }
    }

    /** 判断对话是否已收藏 */
    suspend fun isChatFavorite(chatId: String): Boolean {
        return chatPlacementDao.getPlacement(chatId, ChatFolderScope.FAVORITE) != null
    }

    // ============================================================

    // 获取当前聊天ID
    private val _currentChatIdFlow: Flow<String?> =
        context.currentChatIdDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences -> preferences[PreferencesKeys.CURRENT_CHAT_ID] }

    // 转换为StateFlow以便共享
    val currentChatIdFlow =
        _currentChatIdFlow.stateIn(
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            SharingStarted.Lazily,
            null
        )

    private fun validateArchivedMessageVariants(
        message: ChatMessage,
        variants: List<OperitArchivedMessageVariant>,
    ) {
        if (variants.isEmpty()) {
            return
        }

        require(message.sender == "ai") {
            "Only AI messages can contain archived variants"
        }
        require(message.selectedVariantIndex >= 0) {
            "Selected variant index must not be negative for message ${message.timestamp}"
        }

        val variantIndices = variants.map { it.variantIndex }
        require(variantIndices.all { it > 0 }) {
            "Variant indices must be positive for message ${message.timestamp}"
        }
        require(variantIndices.distinct().size == variantIndices.size) {
            "Duplicate variant indices found for message ${message.timestamp}"
        }
        require(
            message.selectedVariantIndex == 0 ||
                variantIndices.contains(message.selectedVariantIndex),
        ) {
            "Selected variant ${message.selectedVariantIndex} is missing for message ${message.timestamp}"
        }
    }

    private suspend fun saveChatHistoryInternal(
        history: ChatHistory,
        variantsByTimestamp: Map<Long, List<OperitArchivedMessageVariant>> = emptyMap(),
    ) {
        chatMutex(history.id).withLock {
            try {
                val chatEntity = ChatEntity.fromChatHistory(history)
                val existed = chatDao.getChatById(chatEntity.id) != null

                database.withTransaction {
                    if (existed) {
                        // P0-1 fix: 使用 updateChat 而非 REPLACE，避免级联删除 placements
                        chatDao.updateChat(chatEntity)
                    } else {
                        chatDao.insertChat(chatEntity)
                    }
                    // IDEA 3: 幂等创建 ALL placement（导入/保存新对话）
                    ensureAllPlacement(chatEntity.id)

                    // 先删除该聊天的所有现有消息
                    messageDao.deleteAllMessagesForChat(chatEntity.id)
                    messageVariantDao.deleteAllVariantsForChat(chatEntity.id)

                    // 批量插入所有消息
                    val messageEntities =
                        history.messages.mapIndexed { index, message ->
                            val archivedVariants =
                                variantsByTimestamp[message.timestamp]
                                    .orEmpty()
                                    .sortedBy { it.variantIndex }
                            if (archivedVariants.isNotEmpty()) {
                                validateArchivedMessageVariants(message, archivedVariants)
                            }
                            MessageEntity.fromChatMessage(
                                chatEntity.id,
                                if (archivedVariants.isEmpty()) {
                                    message.copy(selectedVariantIndex = 0, variantCount = 1)
                                } else {
                                    message.copy(variantCount = archivedVariants.size + 1)
                                },
                                index,
                            )
                        }
                    messageDao.insertMessages(messageEntities)

                    val variantEntities =
                        history.messages.flatMap { message ->
                            variantsByTimestamp[message.timestamp]
                                .orEmpty()
                                .sortedBy { it.variantIndex }
                                .map { variant ->
                                    variant.toEntity(
                                        chatId = chatEntity.id,
                                        messageTimestamp = message.timestamp,
                                    )
                                }
                        }
                    if (variantEntities.isNotEmpty()) {
                        messageVariantDao.insertVariants(variantEntities)
                    }

                    // IDEA 3: 导入/保存后重算 lastMessageAt
                    chatDao.recalculateLastMessageAt(chatEntity.id)
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    // 保存聊天历史
    suspend fun saveChatHistory(history: ChatHistory) {
        saveChatHistoryInternal(history)
    }

    private suspend fun saveArchivedChat(history: OperitArchivedChat) {
        val variantsByTimestamp =
            history.messages.associate { archivedMessage ->
                archivedMessage.baseMessage.timestamp to archivedMessage.variants
            }
        saveChatHistoryInternal(history.toChatHistory(), variantsByTimestamp)
    }

    /** 更新聊天锁定状态 */
    suspend fun updateChatLocked(chatId: String, locked: Boolean) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatLocked(chatId, locked)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat locked state for chat $chatId", e)
                throw e
            }
        }
    }

    /** 更新聊天置顶状态 */
    suspend fun updateChatPinned(chatId: String, pinned: Boolean) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatPinned(chatId, pinned)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat pinned state for chat $chatId", e)
                throw e
            }
        }
    }

    private suspend fun persistMessageLocked(chatId: String, messageToPersist: ChatMessage): ChatMessage {
        val nextOrderIndex = (messageDao.getMaxOrderIndex(chatId) ?: -1) + 1
        val messageEntity =
            MessageEntity.fromChatMessage(
                chatId = chatId,
                message = messageToPersist,
                orderIndex = nextOrderIndex,
            )
        database.withTransaction {
            messageDao.insertMessage(messageEntity)

            // IDEA 3: 更新 lastMessageAt
            val ts = messageToPersist.timestamp
            val chat = chatDao.getChatById(chatId)
            if (chat != null && (chat.lastMessageAt == null || ts > chat.lastMessageAt!!)) {
                chatDao.updateLastMessageAt(chatId, ts)
            }

            chatDao.getChatById(chatId)?.let { chat ->
                chatDao.updateChatMetadata(
                    chatId = chatId,
                    title = chat.title,
                    timestamp = System.currentTimeMillis(),
                    inputTokens = chat.inputTokens,
                    outputTokens = chat.outputTokens,
                    currentWindowSize = chat.currentWindowSize
                )
            }
        }

        return messageToPersist
    }

    private suspend fun resolveAnchoredMessageLocked(
        chatId: String,
        message: ChatMessage,
        beforeTimestamp: Long?,
        afterTimestamp: Long?,
    ): ChatMessage? {
        if (beforeTimestamp == null && afterTimestamp == null) {
            val hasAnyMessages = messageDao.getMessagesForChatAsc(chatId, 1).isNotEmpty()
            return if (hasAnyMessages) {
                AppLogger.w(TAG, "缺少插入锚点，拒绝在非空聊天中插入消息: chatId=$chatId")
                null
            } else {
                message
            }
        }

        val beforeMessage =
            when {
                beforeTimestamp != null -> messageDao.getMessageByTimestamp(chatId, beforeTimestamp)
                afterTimestamp != null ->
                    messageDao
                        .getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            afterTimestamp,
                            1,
                        ).firstOrNull()

                else -> null
            }
        val afterMessage =
            when {
                beforeTimestamp != null && afterTimestamp == null ->
                    messageDao
                        .getMessagesForChatAfterTimestampExclusiveAsc(
                            chatId,
                            beforeTimestamp,
                            1,
                        ).firstOrNull()
                afterTimestamp != null -> messageDao.getMessageByTimestamp(chatId, afterTimestamp)
                else -> null
            }

        if (beforeTimestamp != null && beforeMessage == null) {
            AppLogger.w(
                TAG,
                "插入消息失败，未找到前置锚点: chatId=$chatId, beforeTimestamp=$beforeTimestamp",
            )
            return null
        }
        if (afterTimestamp != null && afterMessage == null) {
            AppLogger.w(
                TAG,
                "插入消息失败，未找到后置锚点: chatId=$chatId, afterTimestamp=$afterTimestamp",
            )
            return null
        }

        val actualBeforeTimestamp = beforeMessage?.timestamp
        val actualAfterTimestamp = afterMessage?.timestamp

        if (
            actualBeforeTimestamp != null &&
            actualAfterTimestamp != null &&
            actualBeforeTimestamp >= actualAfterTimestamp
        ) {
            AppLogger.w(
                TAG,
                "插入消息失败，前后锚点顺序非法: chatId=$chatId, before=$actualBeforeTimestamp, after=$actualAfterTimestamp",
            )
            return null
        }

        return when {
            actualBeforeTimestamp != null && actualAfterTimestamp != null -> {
                if (actualAfterTimestamp - actualBeforeTimestamp <= 1L) {
                    AppLogger.w(
                        TAG,
                        "插入消息失败，前后锚点时间戳间隔不足: chatId=$chatId, before=$actualBeforeTimestamp, after=$actualAfterTimestamp",
                    )
                    null
                } else {
                    message.copy(
                        timestamp =
                            actualBeforeTimestamp +
                                (actualAfterTimestamp - actualBeforeTimestamp) / 2L,
                    )
                }
            }

            actualBeforeTimestamp != null -> {
                message.copy(timestamp = actualBeforeTimestamp + 1L)
            }

            actualAfterTimestamp != null -> {
                message.copy(timestamp = actualAfterTimestamp - 1L)
            }

            else -> message
        }
    }

    suspend fun addSummaryMessageBetweenSliceNeighbors(
        chatId: String,
        message: ChatMessage,
        beforeTimestamp: Long?,
        afterTimestamp: Long?,
    ): ChatMessage? {
        chatMutex(chatId).withLock {
            try {
                val beforeMessage =
                    when {
                        beforeTimestamp != null -> messageDao.getMessageByTimestamp(chatId, beforeTimestamp)
                        afterTimestamp != null ->
                            messageDao
                                .getMessagesForChatBeforeTimestampExclusiveDesc(
                                    chatId,
                                    afterTimestamp,
                                    1,
                                ).firstOrNull()
                        else -> null
                    }
                val afterMessage =
                    when {
                        beforeTimestamp != null && afterTimestamp == null ->
                            messageDao
                                .getMessagesForChatAfterTimestampExclusiveAsc(
                                    chatId,
                                    beforeTimestamp,
                                    1,
                                ).firstOrNull()
                        afterTimestamp != null -> messageDao.getMessageByTimestamp(chatId, afterTimestamp)
                        else -> null
                    }

                if (beforeMessage?.sender == "summary" || afterMessage?.sender == "summary") {
                    AppLogger.w(
                        TAG,
                        "相邻消息已是 summary，取消插入: chatId=$chatId, before=${beforeMessage?.timestamp}, after=${afterMessage?.timestamp}",
                    )
                    return null
                }

                val messageToPersist =
                    resolveAnchoredMessageLocked(
                        chatId = chatId,
                        message = message,
                        beforeTimestamp = beforeTimestamp,
                        afterTimestamp = afterTimestamp,
                    ) ?: return null
                return persistMessageLocked(chatId, messageToPersist)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to add summary message between slice neighbors for chat $chatId", e)
                throw e
            }
        }
    }

    // 添加单条消息，并返回最终持久化的消息
    suspend fun addMessage(chatId: String, message: ChatMessage): ChatMessage {
        chatMutex(chatId).withLock {
            try {
                return persistMessageLocked(chatId, message)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to add message for chat $chatId", e)
                throw e
            }
        }
    }

    /**
     * 批量更新聊天记录的顺序和分组
     * @param updatedHistories 包含更新信息的ChatHistory列表
     */
    suspend fun updateChatOrderAndGroup(updatedHistories: List<ChatHistory>) {
        globalMutex.withLock {
            try {
                val timestamp = System.currentTimeMillis()
                val entitiesToUpdate = updatedHistories.map { history ->
                    // Find the original entity to keep other fields intact
                    val originalEntity = chatDao.getChatById(history.id)
                    originalEntity?.copy(
                        displayOrder = history.displayOrder,
                        group = history.group,
                        updatedAt = timestamp
                    ) ?: ChatEntity.fromChatHistory(history.copy(updatedAt = LocalDateTime.now()))
                }
                database.withTransaction {
                    chatDao.updateChats(entitiesToUpdate)
                    entitiesToUpdate.forEach { entity ->
                        syncLegacyGroupPlacement(entity.id, entity.group, entity.displayOrder)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat order and group", e)
                throw e
            }
        }
    }

    /**
     * 重命名分组
     * @param oldName 旧的分组名称
     * @param newName 新的分组名称
     * @param characterCardName 角色卡名称，如果为null则更新所有同名分组
     */
    suspend fun updateGroupName(oldName: String, newName: String, characterCardName: String?) {
        globalMutex.withLock {
            try {
                if (newName.isBlank()) throw InvalidFolderNameException()
                database.withTransaction {
                    val affected = if (characterCardName != null) {
                        chatDao.getChatsInGroupForCharacter(oldName, characterCardName)
                    } else {
                        chatDao.getChatsInGroup(oldName)
                    }
                    if (characterCardName != null) {
                        chatDao.updateGroupNameForCharacter(oldName, newName, characterCardName)
                    } else {
                        chatDao.updateGroupName(oldName, newName)
                    }
                    affected.forEach { chat ->
                        syncLegacyGroupPlacement(chat.id, newName, chat.displayOrder)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to rename group from $oldName to $newName (character: $characterCardName)",
                    e
                )
                throw e
            }
        }
    }

    /**
     * 删除分组
     * @param groupName 要删除的分组名称
     * @param deleteChats 是否同时删除分组下的聊天记录
     * @param characterCardName 角色卡名称，如果为null则删除所有同名分组
     */
    suspend fun deleteGroup(groupName: String, deleteChats: Boolean, characterCardName: String?) {
        globalMutex.withLock {
            try {
                database.withTransaction {
                    val affected = if (characterCardName != null) {
                        chatDao.getChatsInGroupForCharacter(groupName, characterCardName)
                    } else {
                        chatDao.getChatsInGroup(groupName)
                    }
                    if (characterCardName != null) {
                        if (deleteChats) {
                            chatDao.deleteChatsInGroupForCharacter(groupName, characterCardName)
                            chatDao.removeGroupFromLockedChatsForCharacter(groupName, characterCardName)
                        } else {
                            chatDao.removeGroupFromChatsForCharacter(groupName, characterCardName)
                        }
                    } else {
                        if (deleteChats) {
                            chatDao.deleteChatsInGroup(groupName)
                            chatDao.removeGroupFromLockedChats(groupName)
                        } else {
                            chatDao.removeGroupFromChats(groupName)
                        }
                    }
                    affected.forEach { chat ->
                        chatDao.getChatById(chat.id)?.let { remaining ->
                            syncLegacyGroupPlacement(remaining.id, remaining.group, remaining.displayOrder)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to delete group $groupName (deleteChats: $deleteChats, character: $characterCardName)",
                    e
                )
                throw e
            }
        }
    }

    /**
     * 删除单条消息.
     * @param chatId 聊天ID
     * @param timestamp 消息时间戳
     */
    suspend fun deleteMessage(chatId: String, timestamp: Long) {
        chatMutex(chatId).withLock {
            try {
                val chat = chatDao.getChatById(chatId)
                database.withTransaction {
                    AppLogger.d(TAG, "正在从数据库删除消息. ChatId: $chatId, Timestamp: $timestamp")
                    messageVariantDao.deleteVariantsForMessage(chatId, timestamp)
                    messageDao.deleteMessageByTimestamp(chatId, timestamp)
                    AppLogger.d(TAG, "消息从数据库删除成功.")

                    // IDEA 3: 删除后重算 lastMessageAt
                    chatDao.recalculateLastMessageAt(chatId)

                    // Update chat metadata
                    chat?.let {
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = it.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = it.inputTokens,
                            outputTokens = it.outputTokens,
                            currentWindowSize = it.currentWindowSize
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to delete message with timestamp $timestamp for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun deleteMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ) {
        chatMutex(chatId).withLock {
            try {
                val baseMessage =
                    messageDao.getMessageByTimestamp(chatId, messageTimestamp)
                        ?: throw IllegalArgumentException(
                            "Message $messageTimestamp does not exist in chat $chatId",
                        )
                if (baseMessage.sender != "ai") {
                    throw IllegalArgumentException("Only AI messages can delete variants")
                }

                val variants =
                    messageVariantDao.getVariantsForMessage(chatId, messageTimestamp)
                        .sortedBy { it.variantIndex }
                if (variants.isEmpty()) {
                    throw IllegalStateException("Message $messageTimestamp has no deletable variants")
                }

                if (variantIndex == 0) {
                    val replacementVariant =
                        variants.firstOrNull()
                            ?: throw IllegalStateException(
                                "Message $messageTimestamp has no replacement variant",
                            )
                    val promotedBaseMessage =
                        baseMessage.copy(
                            content = replacementVariant.content,
                            roleName = replacementVariant.roleName.ifBlank { baseMessage.roleName },
                            selectedVariantIndex = 0,
                            provider = replacementVariant.provider,
                            modelName = replacementVariant.modelName,
                            inputTokens = replacementVariant.inputTokens,
                            outputTokens = replacementVariant.outputTokens,
                            cachedInputTokens = replacementVariant.cachedInputTokens,
                            sentAt = replacementVariant.sentAt,
                            outputDurationMs = replacementVariant.outputDurationMs,
                            waitDurationMs = replacementVariant.waitDurationMs,
                            completedAt = replacementVariant.completedAt,
                        )
                    messageDao.updateMessage(promotedBaseMessage)
                    messageVariantDao.deleteVariant(
                        chatId = chatId,
                        messageTimestamp = messageTimestamp,
                        variantIndex = replacementVariant.variantIndex,
                    )
                    variants
                        .asSequence()
                        .filter { it.variantIndex > replacementVariant.variantIndex }
                        .forEach { variant ->
                            messageVariantDao.updateVariant(
                                variant.copy(variantIndex = variant.variantIndex - 1),
                            )
                        }
                } else {
                    val targetVariant =
                        variants.firstOrNull { it.variantIndex == variantIndex }
                            ?: throw IllegalArgumentException(
                                "Variant $variantIndex does not exist for message $messageTimestamp",
                            )
                    messageVariantDao.deleteVariant(
                        chatId = chatId,
                        messageTimestamp = messageTimestamp,
                        variantIndex = targetVariant.variantIndex,
                    )
                    variants
                        .asSequence()
                        .filter { it.variantIndex > targetVariant.variantIndex }
                        .forEach { variant ->
                            messageVariantDao.updateVariant(
                                variant.copy(variantIndex = variant.variantIndex - 1),
                            )
                        }
                    val newSelectedVariantIndex =
                        when {
                            variants.any { it.variantIndex > targetVariant.variantIndex } -> targetVariant.variantIndex
                            else -> (targetVariant.variantIndex - 1).coerceAtLeast(0)
                        }
                    messageDao.updateSelectedVariantIndex(
                        chatId = chatId,
                        timestamp = messageTimestamp,
                        selectedVariantIndex = newSelectedVariantIndex,
                    )
                }

                chatDao.getChatById(chatId)?.let { chat ->
                    chatDao.updateChatMetadata(
                        chatId = chatId,
                        title = chat.title,
                        timestamp = System.currentTimeMillis(),
                        inputTokens = chat.inputTokens,
                        outputTokens = chat.outputTokens,
                        currentWindowSize = chat.currentWindowSize,
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to delete variant $variantIndex for message $messageTimestamp in chat $chatId",
                    e,
                )
                throw e
            }
        }
    }

    // 更新现有消息
    suspend fun updateMessage(chatId: String, message: ChatMessage) {
        chatMutex(chatId).withLock {
            try {
                // 找到相应的消息实体
                val existingMessage = messageDao.getMessageByTimestamp(chatId, message.timestamp)

                if (existingMessage != null) {
                    if (message.selectedVariantIndex > 0) {
                        val existingVariant =
                            messageVariantDao.getVariantForMessage(
                                chatId,
                                message.timestamp,
                                message.selectedVariantIndex,
                            ) ?: throw IllegalStateException(
                                "Missing variant ${message.selectedVariantIndex} for message ${message.timestamp}",
                            )
                        messageVariantDao.updateVariant(
                            MessageVariantEntity.fromChatMessage(
                                chatId = chatId,
                                messageTimestamp = message.timestamp,
                                variantIndex = message.selectedVariantIndex,
                                message = message,
                                variantId = existingVariant.variantId,
                            )
                        )
                        messageDao.updateSelectedVariantIndex(
                            chatId,
                            message.timestamp,
                            message.selectedVariantIndex,
                        )
                        chatDao.getChatById(chatId)?.let { chat ->
                            chatDao.updateChatMetadata(
                                chatId = chatId,
                                title = chat.title,
                                timestamp = System.currentTimeMillis(),
                                inputTokens = chat.inputTokens,
                                outputTokens = chat.outputTokens,
                                currentWindowSize = chat.currentWindowSize
                            )
                        }
                        return@withLock
                    }

                    val shouldUpdateChatMetadata =
                        message.contentStream == null ||
                            (existingMessage.content.isEmpty() && message.content.isNotEmpty())
                    val updatedMessageEntity =
                        MessageEntity.fromChatMessage(
                            chatId = chatId,
                            message = message,
                            orderIndex = existingMessage.orderIndex,
                            messageId = existingMessage.messageId
                        )
                    messageDao.updateMessage(updatedMessageEntity)

                    if (shouldUpdateChatMetadata) {
                        // 更新聊天元数据时间戳
                        val chat = chatDao.getChatById(chatId)
                        if (chat != null) {
                            chatDao.updateChatMetadata(
                                chatId = chatId,
                                title = chat.title,
                                timestamp = System.currentTimeMillis(),
                                inputTokens = chat.inputTokens,
                                outputTokens = chat.outputTokens,
                                currentWindowSize = chat.currentWindowSize
                            )
                        }
                    }
                } else {
                    // 如果找不到现有消息，则插入新消息（避免在同一互斥锁下递归调用 addMessage）
                    val nextOrderIndex = (messageDao.getMaxOrderIndex(chatId) ?: -1) + 1
                    val messageEntity = MessageEntity.fromChatMessage(
                        chatId = chatId,
                        message = message,
                        orderIndex = nextOrderIndex,
                    )
                    messageDao.insertMessage(messageEntity)

                    // IDEA 3 (P1-4 fix): 维护 lastMessageAt
                    val chat0 = chatDao.getChatById(chatId)
                    if (chat0 != null && (chat0.lastMessageAt == null || message.timestamp > chat0.lastMessageAt!!)) {
                        chatDao.updateLastMessageAt(chatId, message.timestamp)
                    }

                    // 更新聊天元数据
                    val chat = chatDao.getChatById(chatId)
                    if (chat != null) {
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = chat.inputTokens,
                            outputTokens = chat.outputTokens,
                            currentWindowSize = chat.currentWindowSize
                        )
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun setMessageFavorite(chatId: String, timestamp: Long, isFavorite: Boolean) {
        chatMutex(chatId).withLock {
            try {
                val existingMessage =
                    messageDao.getMessageByTimestamp(chatId, timestamp) ?: return@withLock
                if (existingMessage.isFavorite == isFavorite) {
                    return@withLock
                }
                messageDao.updateMessageFavorite(chatId, timestamp, isFavorite)
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to update favorite state for message $timestamp in chat $chatId",
                    e,
                )
                throw e
            }
        }
    }

    suspend fun addMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        message: ChatMessage,
    ): Int {
        return chatMutex(chatId).withLock {
            val baseMessage =
                messageDao.getMessageByTimestamp(chatId, messageTimestamp)
                    ?: throw IllegalArgumentException("Message $messageTimestamp does not exist in chat $chatId")
            if (baseMessage.sender != "ai") {
                throw IllegalArgumentException("Only AI messages can have regenerated variants")
            }
            val nextVariantIndex =
                messageVariantDao.getVariantsForMessage(chatId, messageTimestamp).size + 1
            messageVariantDao.insertVariant(
                MessageVariantEntity.fromChatMessage(
                    chatId = chatId,
                    messageTimestamp = messageTimestamp,
                    variantIndex = nextVariantIndex,
                    message = message.copy(selectedVariantIndex = nextVariantIndex, variantCount = 1),
                )
            )
            messageDao.updateSelectedVariantIndex(chatId, messageTimestamp, nextVariantIndex)
            chatDao.getChatById(chatId)?.let { chat ->
                chatDao.updateChatMetadata(
                    chatId = chatId,
                    title = chat.title,
                    timestamp = System.currentTimeMillis(),
                    inputTokens = chat.inputTokens,
                    outputTokens = chat.outputTokens,
                    currentWindowSize = chat.currentWindowSize
                )
            }
            nextVariantIndex
        }
    }

    suspend fun selectMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        selectedVariantIndex: Int,
    ) {
        chatMutex(chatId).withLock {
            messageDao.getMessageByTimestamp(chatId, messageTimestamp)
                ?: throw IllegalArgumentException("Message $messageTimestamp does not exist in chat $chatId")
            if (selectedVariantIndex > 0) {
                messageVariantDao.getVariantForMessage(chatId, messageTimestamp, selectedVariantIndex)
                    ?: throw IllegalArgumentException(
                        "Variant $selectedVariantIndex does not exist for message $messageTimestamp",
                    )
            }
            messageDao.updateSelectedVariantIndex(chatId, messageTimestamp, selectedVariantIndex)
        }
    }

    /**
     * 从数据库中删除指定时间戳之后的所有消息。 这需要您在MessageDao中添加相应的@Query。
     *
     * 示例:
     * ```
     * @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp >= :timestamp")
     * suspend fun deleteMessagesFrom(chatId: String, timestamp: Long)
     * ```
     */
    suspend fun deleteMessagesFrom(chatId: String, timestamp: Long) {
        chatMutex(chatId).withLock {
            try {
                val chat = chatDao.getChatById(chatId)
                database.withTransaction {
                    AppLogger.d(TAG, "正在从数据库删除消息. ChatId: $chatId, Timestamp >=: $timestamp")
                    messageVariantDao.deleteVariantsFrom(chatId, timestamp)
                    messageDao.deleteMessagesFrom(chatId, timestamp)
                    AppLogger.d(TAG, "后续消息从数据库删除成功.")

                    // IDEA 3: 截断后重算 lastMessageAt
                    chatDao.recalculateLastMessageAt(chatId)

                    // 更新聊天元数据时间戳
                    chat?.let {
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = it.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = it.inputTokens,
                            outputTokens = it.outputTokens,
                            currentWindowSize = it.currentWindowSize
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "从 $timestamp 开始为聊天 $chatId 删除消息失败", e)
                throw e
            }
        }
    }

    /**
     * 清除一个聊天中的所有消息，但保留聊天本身。
     *
     * 这需要您在MessageDao中添加相应的@Query。
     * ```
     * @Query("DELETE FROM messages WHERE chatId = :chatId")
     * suspend fun deleteAllMessagesForChat(chatId: String)
     * ```
     */
    suspend fun clearChatMessages(chatId: String) {
        chatMutex(chatId).withLock {
            try {
                database.withTransaction {
                    messageVariantDao.deleteAllVariantsForChat(chatId)
                    messageDao.deleteAllMessagesForChat(chatId)

                    // IDEA 3: 清空后重算 lastMessageAt (= null)
                    chatDao.recalculateLastMessageAt(chatId)

                    // 更新聊天元数据
                    chatDao.getChatById(chatId)?.let { chat ->
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = 0,
                            outputTokens = 0,
                            currentWindowSize = 0
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "为聊天 $chatId 清除消息失败", e)
                throw e
            }
        }
    }

    // 更新聊天标题
    suspend fun updateChatTitle(chatId: String, title: String) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatTitle(chatId, title)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat title for chat $chatId", e)
                throw e
            }
        }
    }

    // 更新聊天绑定的角色卡
    suspend fun updateChatCharacterCardName(chatId: String, characterCardName: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterCardName(chatId, characterCardName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character card for chat $chatId", e)
                throw e
            }
        }
    }

    // 更新聊天的token计数
    suspend fun updateChatTokenCounts(
        chatId: String,
        inputTokens: Int,
        outputTokens: Int,
        currentWindowSize: Int
    ) {
        chatMutex(chatId).withLock {
            try {
                val chat = chatDao.getChatById(chatId)
                if (chat != null) {
                    chatDao.updateChatMetadata(
                        chatId = chatId,
                        title = chat.title,
                        timestamp = System.currentTimeMillis(),
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        currentWindowSize = currentWindowSize
                    )
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    // 设置当前聊天ID
    suspend fun setCurrentChatId(chatId: String) {
        context.currentChatIdDataStore.edit { preferences ->
            preferences[PreferencesKeys.CURRENT_CHAT_ID] = chatId
        }
    }

    // 清除当前聊天ID
    suspend fun clearCurrentChatId() {
        context.currentChatIdDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
        }
    }

    // 检查聊天是否存在
    suspend fun chatExists(chatId: String): Boolean {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                chatDao.getChatById(chatId) != null
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check chat existence for chat $chatId", e)
                false
            }
        }
    }

    suspend fun canDeleteChatHistory(chatId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val chat = chatDao.getChatById(chatId)
                chat != null && chat.locked != true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check whether chat $chatId can be deleted", e)
                false
            }
        }
    }

    // 删除聊天历史
    suspend fun deleteChatHistory(chatId: String): Boolean {
        chatMutex(chatId).withLock {
            try {
                val deleted = database.withTransaction {
                    val chat = chatDao.getChatById(chatId)
                    if (chat?.locked == true) {
                        AppLogger.w(TAG, "Chat $chatId is locked; skip deletion")
                        return@withTransaction false
                    }
                    if (chat == null) {
                        return@withTransaction false
                    }

                    val sourceFoldersByScope =
                        chatPlacementDao.getPlacementsForChat(chatId)
                            .groupBy { it.scope }
                            .mapValues { (_, placements) ->
                                placements.mapNotNullTo(mutableSetOf()) { it.folderId }
                            }

                    // 删除聊天实体会通过外键级联删除消息和 placements。
                    chatDao.deleteChat(chatId)
                    sourceFoldersByScope.forEach { (scope, sourceFolderIds) ->
                        pruneEmptyFoldersAffectedBy(scope, sourceFolderIds)
                    }
                    true
                }
                if (!deleted) return false

                // 如果删除的是当前聊天，清除当前聊天ID
                val currentChatId = currentChatIdFlow.first()
                if (currentChatId == chatId) {
                    context.currentChatIdDataStore.edit { preferences ->
                        preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
                    }
                }
                return true
            } catch (e: Exception) {
                throw e
            }
        }
    }

    // 创建新对话
    suspend fun createNewChat(
        group: String? = null,
        inheritGroupFromChatId: String? = null,
        characterCardName: String? = null,
        characterGroupId: String? = null,
        setAsCurrentChat: Boolean = true
    ): ChatHistory {
        val dateTime = LocalDateTime.now()
        val formattedTime =
            "${dateTime.hour}:${
                dateTime.minute.toString().padStart(2, '0')
            }:${dateTime.second.toString().padStart(2, '0')}"

        val localizedContext = LocaleUtils.getLocalizedContext(context)

        // 确定新对话的分组
        val finalGroup = when {
            // 如果显式指定了分组，使用指定的分组
            group != null -> group
            // 如果要继承分组，尝试从指定的对话获取分组
            inheritGroupFromChatId != null -> {
                chatDao.getChatById(inheritGroupFromChatId)?.group
            }
            // 默认为空分组（不分组）
            else -> null
        }

        val newHistory =
            ChatHistory(
                title = "${localizedContext.getString(R.string.new_conversation)} $formattedTime",
                messages = listOf<ChatMessage>(),
                inputTokens = 0,
                outputTokens = 0,
                group = finalGroup,
                characterCardName = characterCardName, // 使用传入的角色卡名称，如果为null则不绑定
                characterGroupId = characterGroupId // 绑定群组角色卡ID（可选）
            )

        // 保存新聊天及其 ALL 位置
        val chatEntity = ChatEntity.fromChatHistory(newHistory)
        database.withTransaction {
            chatDao.insertChat(chatEntity)
            ensureAllPlacement(chatEntity.id)
            syncLegacyGroupPlacement(chatEntity.id, chatEntity.group, chatEntity.displayOrder)
        }

        // 设置为当前聊天
        if (setAsCurrentChat) {
            setCurrentChatId(newHistory.id)
        }

        return newHistory
    }

    /** 更新聊天工作区 */
    suspend fun updateChatWorkspace(chatId: String, workspace: String?, workspaceEnv: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatWorkspace(chatId, workspace, workspaceEnv)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat workspace for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun renameManagedWorkspace(
        chatId: String,
        newWorkspaceName: String
    ): WorkspaceRenameResult {
        chatMutex(chatId).withLock {
            val trimmedName = newWorkspaceName.trim()
            if (trimmedName.isEmpty()) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_empty))
            }
            if (
                trimmedName == "." ||
                trimmedName == ".." ||
                trimmedName.contains('/') ||
                trimmedName.contains('\\')
            ) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_invalid))
            }

            val chat = chatDao.getChatById(chatId)
                ?: throw IllegalStateException(context.getString(R.string.workspace_rename_chat_missing))
            val workspacePath = chat.workspace
                ?: throw IllegalStateException(context.getString(R.string.chat_not_bound_to_workspace))
            if (!chat.workspaceEnv.isNullOrBlank()) {
                throw IllegalStateException(
                    context.getString(R.string.workspace_rename_only_managed_supported)
                )
            }

            val workspaceRoot = File(context.filesDir, "workspace").apply { mkdirs() }.canonicalFile
            val sourceDir = File(workspacePath).canonicalFile
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                throw IllegalStateException(context.getString(R.string.workspace_directory_invalid))
            }
            if (sourceDir.parentFile?.canonicalFile != workspaceRoot) {
                throw IllegalStateException(
                    context.getString(R.string.workspace_rename_only_managed_supported)
                )
            }

            val targetDir = File(workspaceRoot, trimmedName).canonicalFile
            if (targetDir.parentFile?.canonicalFile != workspaceRoot) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_invalid))
            }
            if (targetDir != sourceDir && targetDir.exists()) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_exists))
            }

            if (targetDir != sourceDir && !sourceDir.renameTo(targetDir)) {
                throw IOException(context.getString(R.string.workspace_rename_failed))
            }

            chatDao.updateChatTitleAndWorkspace(
                chatId = chatId,
                title = trimmedName,
                workspace = targetDir.absolutePath,
                workspaceEnv = chat.workspaceEnv
            )

            return WorkspaceRenameResult(
                workspacePath = targetDir.absolutePath,
                workspaceEnv = chat.workspaceEnv,
                workspaceName = trimmedName
            )
        }
    }

    // 更新聊天分组
    suspend fun updateChatGroup(chatId: String, group: String?) {
        chatMutex(chatId).withLock {
            try {
                database.withTransaction {
                    chatDao.updateChatGroup(chatId, group)
                    chatDao.getChatById(chatId)?.let { chat ->
                        syncLegacyGroupPlacement(chat.id, chat.group, chat.displayOrder)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat group for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun getChatTitle(chatId: String): String? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                chatDao.getChatById(chatId)?.title
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get chat title for chat $chatId", e)
                null
            }
        }
    }

    // 直接加载聊天消息
    suspend fun loadChatMessages(chatId: String): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // AppLogger.d(TAG, "直接从数据库加载聊天 $chatId 的消息")
                val messageEntities = messageDao.getMessagesForChat(chatId)
                // AppLogger.d(TAG, "聊天 $chatId 共加载 ${messages.size} 条消息")
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessages(
        chatId: String,
        order: String? = null,
        limit: Int? = null
    ): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val normalizedOrder = order?.trim()?.lowercase()
                val effectiveLimit = limit?.coerceAtLeast(1)

                val messageEntities = when (normalizedOrder) {
                    "desc" -> {
                        if (effectiveLimit != null) {
                            messageDao.getMessagesForChatDesc(chatId, effectiveLimit)
                        } else {
                            messageDao.getMessagesForChat(chatId).asReversed()
                        }
                    }

                    else -> {
                        if (effectiveLimit != null) {
                            messageDao.getMessagesForChatAsc(chatId, effectiveLimit)
                        } else {
                            messageDao.getMessagesForChat(chatId)
                        }
                    }
                }

                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesRange(
        chatId: String,
        order: String? = null,
        start: Int,
        end: Int,
    ): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val normalizedOrder = order?.trim()?.lowercase()
                val limit = end - start + 1

                val messageEntities = when (normalizedOrder) {
                    "desc" -> messageDao.getMessagesForChatDescRange(chatId, start, limit)
                    else -> messageDao.getMessagesForChatAscRange(chatId, start, limit)
                }

                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按区间加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    /** 搜索包含特定关键词的聊天ID列表 */
    suspend fun searchChatIdsByContent(query: String): Set<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) {
                    return@withContext emptySet()
                }
                val escapedQuery =
                    query
                        .trim()
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")

                val chatIds = messageDao.searchChatIdsByContent(escapedQuery)
                chatIds.toSet()
            } catch (e: Exception) {
                AppLogger.e(TAG, "搜索聊天内容失败: $query", e)
                emptySet()
            }
        }
    }

    /**
     * 创建对话分支
     * @param parentChatId 父对话ID
     * @param upToMessageTimestamp 复制消息到指定时间戳（包含该时间戳的消息）
     * @return 新创建的分支对话
     */
    suspend fun createBranch(
        parentChatId: String,
        upToMessageTimestamp: Long? = null
    ): ChatHistory {
        return globalMutex.withLock {
            try {
                // 获取父对话
                val parentChat = chatDao.getChatById(parentChatId)
                    ?: throw IllegalArgumentException(context.getString(R.string.chat_history_parent_not_exist, parentChatId))

                val branchEntity =
                    ChatEntity(
                        title = parentChat.title,
                        inputTokens = parentChat.inputTokens,
                        outputTokens = parentChat.outputTokens,
                        currentWindowSize = parentChat.currentWindowSize,
                        group = parentChat.group,
                        workspace = parentChat.workspace,
                        workspaceEnv = parentChat.workspaceEnv,
                        parentChatId = parentChatId,
                        characterCardName = parentChat.characterCardName,
                        characterGroupId = parentChat.characterGroupId,
                        locked = false,
                        pinned = false,
                    )

                chatDao.insertChat(branchEntity)

                // IDEA 3: 幂等创建 ALL placement
                ensureAllPlacement(branchEntity.id)

                val copiedMessageCount =
                    messageDao.countMessagesForChatUpToTimestamp(parentChatId, upToMessageTimestamp)
                if (copiedMessageCount > 0) {
                    messageDao.copyMessagesToChat(
                        sourceChatId = parentChatId,
                        targetChatId = branchEntity.id,
                        upToTimestampInclusive = upToMessageTimestamp,
                    )
                    messageVariantDao.copyVariantsToChat(
                        sourceChatId = parentChatId,
                        targetChatId = branchEntity.id,
                        upToTimestampInclusive = upToMessageTimestamp,
                    )
                }

                // IDEA 3: 重算分支的 lastMessageAt（基于已复制的消息）
                chatDao.recalculateLastMessageAt(branchEntity.id)

                val branchHistory = branchEntity.toChatHistory(emptyList())

                // 设置为当前聊天
                setCurrentChatId(branchHistory.id)

                AppLogger.d(
                    TAG,
                    "创建分支对话: ${branchHistory.id}, 父对话: $parentChatId, 消息数: $copiedMessageCount"
                )
                branchHistory
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建分支对话失败", e)
                throw e
            }
        }
    }

    /**
     * 获取指定对话的所有分支
     * @param parentChatId 父对话ID
     * @return 分支对话列表
     */
    suspend fun getBranches(parentChatId: String): List<ChatHistory> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val branchEntities = chatDao.getBranchesByParentId(parentChatId)
                branchEntities.map { entity ->
                    val createdAt = Instant.ofEpochMilli(entity.createdAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                    val updatedAt = Instant.ofEpochMilli(entity.updatedAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    ChatHistory(
                        id = entity.id,
                        title = entity.title,
                        messages = emptyList(), // 不加载消息以提高性能
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        inputTokens = entity.inputTokens,
                        outputTokens = entity.outputTokens,
                        currentWindowSize = entity.currentWindowSize,
                        group = entity.group,
                        displayOrder = entity.displayOrder,
                        workspace = entity.workspace,
                        parentChatId = entity.parentChatId,
                        characterCardName = entity.characterCardName,
                        characterGroupId = entity.characterGroupId,
                        locked = entity.locked,
                        pinned = entity.pinned
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取分支对话失败: $parentChatId", e)
                emptyList()
            }
        }
    }

    /**
     * 获取指定对话的所有分支（Flow版本）
     * @param parentChatId 父对话ID
     * @return 分支对话Flow
     */
    fun getBranchesFlow(parentChatId: String): Flow<List<ChatHistory>> {
        return chatDao.getBranchesByParentIdFlow(parentChatId).map { branchEntities ->
            branchEntities.map { entity ->
                val createdAt = Instant.ofEpochMilli(entity.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                val updatedAt = Instant.ofEpochMilli(entity.updatedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()

                ChatHistory(
                    id = entity.id,
                    title = entity.title,
                    messages = emptyList(), // 不加载消息以提高性能
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    inputTokens = entity.inputTokens,
                    outputTokens = entity.outputTokens,
                    currentWindowSize = entity.currentWindowSize,
                    group = entity.group,
                    displayOrder = entity.displayOrder,
                    workspace = entity.workspace,
                    parentChatId = entity.parentChatId,
                    characterCardName = entity.characterCardName,
                    characterGroupId = entity.characterGroupId,
                    locked = entity.locked,
                    pinned = entity.pinned
                )
            }
        }
    }

    /**
     * 导出所有聊天记录到「下载/Operit」目录（默认 JSON 格式）
     * @return 生成的文件绝对路径，失败时返回null
     */
    suspend fun exportChatHistoriesToDownloads(): String? =
        exportChatHistoriesToDownloads(ExportFormat.JSON)

    /**
     * 导出所有聊天记录到「下载/Operit」目录（支持多种格式）
     * @param format 导出格式
     * @return 生成的文件绝对路径，失败时返回null
     */
    suspend fun exportChatHistoriesToDownloads(format: ExportFormat): String? =
        withContext(Dispatchers.IO) {
            try {
                val chatHistoriesBasic = chatHistoriesFlow.first()

                val exportDir = OperitBackupDirs.chatDir()

                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                val timestamp = dateFormat.format(Date())

                val exportFile = when (format) {
                    ExportFormat.MARKDOWN -> {
                        val completeHistories = loadDisplayHistories(chatHistoriesBasic)
                        val zipFile = File(exportDir, "chat_backup_$timestamp.zip")
                        val usedNames = HashSet<String>()
                        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                            for (history in completeHistories) {
                                val content = MarkdownExporter.exportSingle(context, history)
                                // 处理文件名中的非法字符
                                var safeTitle = history.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                // 避免文件名过长
                                if (safeTitle.length > 50) {
                                    safeTitle = safeTitle.substring(0, 50)
                                }
                                safeTitle = safeTitle.trim()
                                
                                // 确保文件名唯一
                                var baseName = "$safeTitle.md"
                                var counter = 1
                                while (usedNames.contains(baseName)) {
                                    baseName = "$safeTitle ($counter).md"
                                    counter++
                                }
                                usedNames.add(baseName)

                                zos.putNextEntry(ZipEntry(baseName))
                                zos.write(content.toByteArray())
                                zos.closeEntry()
                            }
                        }
                        zipFile
                    }

                    ExportFormat.JSON -> {
                        val file = File(exportDir, "chat_backup_$timestamp.json")
                        exportOperitArchiveJsonStream(file, chatHistoriesBasic)
                        file
                    }

                    ExportFormat.HTML -> {
                        val completeHistories = loadDisplayHistories(chatHistoriesBasic)
                        val file = File(exportDir, "chat_backup_$timestamp.html")
                        file.writeText(HtmlExporter.exportMultiple(context, completeHistories))
                        file
                    }

                    ExportFormat.TXT -> {
                        val completeHistories = loadDisplayHistories(chatHistoriesBasic)
                        val file = File(exportDir, "chat_backup_$timestamp.txt")
                        file.writeText(TextExporter.exportMultiple(context, completeHistories))
                        file
                    }

                    ExportFormat.CSV -> {
                        val file = File(exportDir, "chat_backup_$timestamp.json")
                        exportOperitArchiveJsonStream(file, chatHistoriesBasic)
                        file
                    }
                }

                exportFile.absolutePath
            } catch (e: Exception) {
                AppLogger.e(TAG, "导出聊天记录失败", e)
                null
            }
        }

    /**
     * 从指定URI导入聊天记录（指定格式）
     * @param uri 备份文件URI
     * @param format 指定的格式
     * @return 导入结果统计
     */
    suspend fun importChatHistoriesFromUri(uri: Uri, format: ChatFormat): ChatImportResult =
        withContext(Dispatchers.IO) {
            try {
                val chatHistories = mutableListOf<ChatHistory>()
                var isZipProcessed = false

                // 如果是 Markdown 格式，尝试作为 Zip 处理
                if (format == ChatFormat.MARKDOWN) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { fis ->
                            // 尝试作为 Zip 读取
                            // 注意：ZipInputStream 可能会消耗流，如果不是 Zip，后续重读需要重新 openInputStream
                            ZipInputStream(fis).use { zipStream ->
                                var entry = zipStream.nextEntry
                                if (entry != null) {
                                    // 确实是 Zip 文件
                                    do {
                                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".md")) {
                                            val buffer = ByteArrayOutputStream()
                                            val data = ByteArray(4096)
                                            var count: Int
                                            while (zipStream.read(data).also { count = it } != -1) {
                                                buffer.write(data, 0, count)
                                            }
                                            val content = buffer.toString("UTF-8")
                                            if (content.isNotBlank()) {
                                                chatHistories.addAll(convertToOperitFormat(content, ChatFormat.MARKDOWN))
                                            }
                                        }
                                        zipStream.closeEntry()
                                        entry = zipStream.nextEntry
                                    } while (entry != null)
                                    isZipProcessed = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "尝试解析 Zip 失败，将尝试作为普通文件读取: ${e.message}")
                    }
                }

                if (!isZipProcessed) {
                    AppLogger.d(TAG, "使用指定格式导入: $format")
                    if (format == ChatFormat.OPERIT) {
                        val existingIds = chatHistoriesFlow.first().map { it.id }.toMutableSet()
                        val inputStream =
                            context.contentResolver.openInputStream(uri)
                                ?: return@withContext ChatImportResult(0, 0, 0)
                        inputStream.use { stream ->
                            AppLogger.d(TAG, "开始流式导入 Operit JSON: uri=$uri")
                            return@withContext importOperitChatHistoriesStream(stream, existingIds)
                        }
                    } else {
                        val inputStream = context.contentResolver.openInputStream(uri)
                            ?: return@withContext ChatImportResult(0, 0, 0)
                        val content = inputStream.bufferedReader().use { it.readText() }

                        if (content.isBlank()) {
                            throw Exception(context.getString(R.string.chat_history_imported_file_empty))
                        }

                        // 转换为 ChatHistory 列表
                        chatHistories.addAll(convertToOperitFormat(content, format))
                    }
                }

                if (chatHistories.isEmpty()) {
                    return@withContext ChatImportResult(0, 0, 0)
                }

                // 保存导入的对话
                val existingIds = chatHistoriesFlow.first().map { it.id }.toMutableSet()

                var newCount = 0
                var updatedCount = 0
                var skippedCount = 0
                val importedIds = mutableSetOf<String>()

                for (chatHistory in chatHistories) {
                    if (chatHistory.messages.isEmpty()) {
                        skippedCount++
                        continue
                    }

                    if (existingIds.contains(chatHistory.id)) {
                        updatedCount++
                    } else {
                        newCount++
                        existingIds.add(chatHistory.id)
                    }

                    saveChatHistory(chatHistory)
                    importedIds.add(chatHistory.id)
                }
                if (chatHistories.any { it.id in importedIds && !it.group.isNullOrBlank() }) {
                    restoreLegacyGroupPlacements(importedIds)
                }

                AppLogger.d(TAG, "导入完成: 新增=$newCount, 更新=$updatedCount, 跳过=$skippedCount")
                ChatImportResult(newCount, updatedCount, skippedCount)
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入聊天记录失败", e)
                throw e
            }
        }

    private fun parseLegacyOperitChatHistories(content: String): List<ChatHistory> {
        try {
            return operitArchiveJson.decodeFromString<List<ChatHistory>>(content)
        } catch (e: Exception) {
            val gson =
                GsonBuilder()
                    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    .create()
            val type = object : TypeToken<List<ChatHistory>>() {}.type
            return gson.fromJson<List<ChatHistory>>(content, type)
        }
    }

    private fun convertToOperitFormat(content: String, format: ChatFormat): List<ChatHistory> {
        return try {
            when (format) {
                ChatFormat.OPERIT -> {
                    parseLegacyOperitChatHistories(content)
                }
                
                ChatFormat.CHATGPT -> {
                    AppLogger.d(TAG, "使用 ChatGPT 转换器")
                    ChatGPTConverter().convert(content)
                }
                
                ChatFormat.CHATBOX -> {
                    AppLogger.d(TAG, "使用 ChatBox 转换器")
                    ChatBoxConverter(context).convert(content)
                }
                
                ChatFormat.MARKDOWN -> {
                    AppLogger.d(TAG, "使用 Markdown 转换器")
                    MarkdownConverter(context).convert(content)
                }
                
                ChatFormat.GENERIC_JSON -> {
                    AppLogger.d(TAG, "使用通用 JSON 转换器")
                    GenericJsonConverter().convert(content)
                }
                
                ChatFormat.CLAUDE -> {
                    // Claude 格式暂不支持，回退到通用 JSON
                    AppLogger.d(TAG, "Claude 格式回退到通用 JSON 转换器")
                    GenericJsonConverter().convert(content)
                }
                
                else -> {
                    throw ConversionException(context.getString(R.string.chat_history_unsupported_format, format))
                }
            }
        } catch (e: ConversionException) {
            throw Exception(context.getString(R.string.chat_history_convert_format_failed, e.message ?: ""), e)
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.chat_history_parse_backup_failed, e.message ?: ""), e)
        }
    }

    /**
     * 清理绑定已删除角色卡的对话（将characterCardName设为null）
     * @param characterCardName 已删除的角色卡名称
     */
    suspend fun clearCharacterCardBinding(characterCardName: String) {
        try {
            withContext(Dispatchers.IO) {
                chatDao.clearCharacterCardBinding(characterCardName)
                AppLogger.d(TAG, "已清理绑定角色卡 '$characterCardName' 的对话")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "清理角色卡绑定失败: $characterCardName", e)
        }
    }

    /**
     * 将指定角色卡或未绑定的对话转移到新的角色卡
     * @return 受影响的对话数量
     */
    suspend fun reassignChatsToCharacterCard(
            sourceCharacterCardName: String?,
            targetCharacterCardName: String
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = if (sourceCharacterCardName == null) {
                    chatDao.assignCharacterCardToUnbound(targetCharacterCardName)
                } else {
                    chatDao.renameCharacterCardBinding(sourceCharacterCardName, targetCharacterCardName)
                }
                AppLogger.d(
                        TAG,
                        "角色卡聊天重分配: ${sourceCharacterCardName ?: "未绑定"} -> $targetCharacterCardName, 更新 $updated 条记录"
                )
                updated
            } catch (e: Exception) {
                AppLogger.e(
                        TAG,
                        "重命名角色卡绑定失败: ${sourceCharacterCardName ?: "未绑定"} -> $targetCharacterCardName",
                        e
                )
                throw e
            }
        }
    }

    suspend fun getLatestSummaryTimestamp(chatId: String): Long? {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.getLatestSummaryTimestamp(chatId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取最新 summary 时间戳失败", e)
                null
            }
        }
    }

    suspend fun loadMessagesAfterLatestSummaryInRange(
        chatId: String,
        beforeTimestampExclusive: Long? = null,
        upToTimestampInclusive: Long? = null,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val latestSummaryTimestamp =
                    when {
                        beforeTimestampExclusive != null ->
                            messageDao.getLatestSummaryTimestampBefore(
                                chatId,
                                beforeTimestampExclusive,
                            )
                        upToTimestampInclusive != null ->
                            messageDao.getLatestSummaryTimestampUpTo(
                                chatId,
                                upToTimestampInclusive,
                            )
                        else -> messageDao.getLatestSummaryTimestamp(chatId)
                    }
                val messageEntities =
                    messageDao.getMessagesForChatInRangeAsc(
                        chatId = chatId,
                        afterTimestampExclusive = latestSummaryTimestamp,
                        beforeTimestampExclusive = beforeTimestampExclusive,
                        upToTimestampInclusive = upToTimestampInclusive,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按总结窗口加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun hasUserMessage(chatId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsUserMessage(chatId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查聊天是否存在用户消息失败", e)
                false
            }
        }
    }

    suspend fun loadRuntimeChatMessages(chatId: String): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val latestSummaryTimestamp = messageDao.getLatestSummaryTimestamp(chatId)
                val messageEntities =
                    if (latestSummaryTimestamp != null) {
                        messageDao.getMessagesForChatFromTimestampAsc(chatId, latestSummaryTimestamp)
                    } else {
                        messageDao.getMessagesForChat(chatId)
                    }
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载运行态聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadRuntimeChatMessagesUpTo(
        chatId: String,
        upToTimestampInclusive: Long
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            val latestSummaryTimestamp =
                messageDao.getLatestSummaryTimestampUpTo(chatId, upToTimestampInclusive)
            val messageEntities =
                if (latestSummaryTimestamp != null) {
                    messageDao.getMessagesForChatWindowAsc(
                        chatId = chatId,
                        startTimestampInclusive = latestSummaryTimestamp,
                        endTimestampInclusive = upToTimestampInclusive
                    )
                } else {
                    messageDao.getMessagesForChatInRangeAsc(
                        chatId = chatId,
                        afterTimestampExclusive = null,
                        beforeTimestampExclusive = null,
                        upToTimestampInclusive = upToTimestampInclusive
                    )
                }
            hydrateMessages(chatId, messageEntities)
        }
    }

    suspend fun loadChatMessageLocatorPreviews(
        chatId: String,
        query: String = "",
    ): List<ChatMessageLocatorPreview> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedQuery = query.trim()
                if (normalizedQuery.isBlank()) {
                    messageDao.getLocatorPreviewsForChat(chatId, LOCATOR_PREVIEW_CHAR_COUNT)
                } else {
                    messageDao.searchLocatorPreviewsForChat(
                        chatId = chatId,
                        query = normalizedQuery,
                        previewCharCount = LOCATOR_PREVIEW_CHAR_COUNT,
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天定位轻量预览失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesFromTimestamp(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    messageDao.getMessagesForChatFromTimestampAsc(chatId, startTimestampInclusive)
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按起始时间加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesWindow(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    messageDao.getMessagesForChatWindowAsc(
                        chatId = chatId,
                        startTimestampInclusive = startTimestampInclusive,
                        endTimestampInclusive = endTimestampInclusive,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按时间窗口加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesAscAfter(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    messageDao.getMessagesForChatAfterTimestampExclusiveAsc(
                        chatId,
                        afterTimestampExclusive,
                        limit,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按起始时间后分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadOlderChatMessages(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    messageDao
                        .getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            beforeTimestampExclusive,
                            limit,
                        ).asReversed()
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载更早聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun hasMessagesBefore(
        chatId: String,
        beforeTimestampExclusive: Long,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsMessagesBeforeTimestamp(chatId, beforeTimestampExclusive)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查是否存在更早聊天消息失败", e)
                false
            }
        }
    }

    suspend fun hasMessagesAfter(
        chatId: String,
        afterTimestampExclusive: Long,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsMessagesAfterTimestamp(chatId, afterTimestampExclusive)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查是否存在更新聊天消息失败", e)
                false
            }
        }
    }

    suspend fun loadChatMessagesDesc(
        chatId: String,
        limit: Int,
        beforeTimestampExclusive: Long? = null,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    if (beforeTimestampExclusive != null) {
                        messageDao.getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            beforeTimestampExclusive,
                            limit,
                        )
                    } else {
                        messageDao.getMessagesForChatDesc(chatId, limit)
                    }
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按倒序分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesDescUpTo(
        chatId: String,
        maxTimestampInclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    messageDao.getMessagesForChatBeforeTimestampDesc(
                        chatId,
                        maxTimestampInclusive,
                        limit,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按截止时间倒序分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    /**
     * 将指定角色群组下的对话转移到新的角色群组
     * @return 受影响的对话数量
     */
    suspend fun reassignChatsToCharacterGroup(
        sourceCharacterGroupId: String?,
        targetCharacterGroupId: String
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = if (sourceCharacterGroupId == null) {
                    chatDao.assignCharacterGroupToUnbound(targetCharacterGroupId)
                } else {
                    chatDao.renameCharacterGroupBinding(sourceCharacterGroupId, targetCharacterGroupId)
                }
                AppLogger.d(
                    TAG,
                    "角色群组聊天重分配: ${sourceCharacterGroupId ?: "未绑定"} -> $targetCharacterGroupId, 更新 $updated 条记录"
                )
                updated
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "重命名角色群组绑定失败: ${sourceCharacterGroupId ?: "未绑定"} -> $targetCharacterGroupId",
                    e
                )
                throw e
            }
        }
    }

    // 更新聊天绑定的群组角色卡
    suspend fun updateChatCharacterGroupId(chatId: String, characterGroupId: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterGroupId(chatId, characterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character group for chat $chatId", e)
                throw e
            }
        }
    }

    // 同时更新聊天绑定的角色卡与群组
    suspend fun updateChatCharacterBinding(
        chatId: String,
        characterCardName: String?,
        characterGroupId: String?
    ) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterBinding(chatId, characterCardName, characterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character binding for chat $chatId", e)
                throw e
            }
        }
    }

    /**
     * 清理绑定已删除角色群组的对话（将characterGroupId设为null）
     * @return 受影响的对话数量
     */
    suspend fun clearCharacterGroupBinding(characterGroupId: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = chatDao.clearCharacterGroupBinding(characterGroupId)
                AppLogger.d(TAG, "已清理绑定角色群组 '$characterGroupId' 的对话: $updated")
                updated
            } catch (e: Exception) {
                AppLogger.e(TAG, "清理角色群组绑定失败: $characterGroupId", e)
                throw e
            }
        }
    }

    /**
     * 批量删除绑定到缺失角色卡（或未绑定）的未锁定对话
     * @return 实际删除的对话数量
     */
    suspend fun deleteChatsByCharacterCardBinding(sourceCharacterCardName: String?): Int {
        return withContext(Dispatchers.IO) {
            try {
                val currentChatId = currentChatIdFlow.first()
                val currentChat = currentChatId?.let { chatDao.getChatById(it) }

                val deletedCount = if (sourceCharacterCardName == null) {
                    chatDao.deleteUnlockedUnboundChats()
                } else {
                    chatDao.deleteUnlockedChatsByCharacterCardName(sourceCharacterCardName)
                }

                val currentChatShouldBeCleared =
                    currentChat != null &&
                        !currentChat.locked &&
                        (
                            if (sourceCharacterCardName == null) {
                                currentChat.characterCardName == null && currentChat.characterGroupId == null
                            } else {
                                currentChat.characterCardName == sourceCharacterCardName
                            }
                        )

                if (currentChatShouldBeCleared) {
                    context.currentChatIdDataStore.edit { preferences ->
                        preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
                    }
                }

                AppLogger.d(
                    TAG,
                    "删除缺失角色卡残留对话: ${sourceCharacterCardName ?: "未绑定"}, 删除 $deletedCount 条"
                )
                deletedCount
            } catch (e: Exception) {
                AppLogger.e(TAG, "删除缺失角色卡残留对话失败: ${sourceCharacterCardName ?: "未绑定"}", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新角色卡绑定
     * @return 受影响的对话数量
     */
    suspend fun assignCharacterCardToChats(
        chatIds: List<String>,
        targetCharacterCardName: String?
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.updateCharacterCardForChats(chatIds, targetCharacterCardName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量更新聊天角色卡失败: $targetCharacterCardName, chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新角色群组绑定
     * @return 受影响的对话数量
     */
    suspend fun assignCharacterGroupToChats(
        chatIds: List<String>,
        targetCharacterGroupId: String?
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.updateCharacterGroupForChats(chatIds, targetCharacterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量更新聊天角色群组失败: $targetCharacterGroupId, chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天移除角色群组绑定
     * @return 受影响的对话数量
     */
    suspend fun clearCharacterGroupBindingForChats(
        chatIds: List<String>
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.clearCharacterGroupForChats(chatIds)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量清理聊天角色群组失败: chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新分组
     * @return 受影响的对话数量
     */
    suspend fun assignGroupToChats(
        chatIds: List<String>,
        groupName: String?
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                database.withTransaction {
                    val updated = chatDao.updateGroupForChats(chatIds, groupName)
                    chatIds.forEach { chatId ->
                        chatDao.getChatById(chatId)?.let { chat ->
                            syncLegacyGroupPlacement(chat.id, chat.group, chat.displayOrder)
                        }
                    }
                    updated
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量更新聊天分组失败: $groupName, chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量重命名对话中绑定的角色卡名称
     * @return 受影响的对话数量
     */
    suspend fun renameCharacterCardInChats(oldName: String, newName: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                chatDao.renameCharacterCardBinding(oldName, newName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量重命名对话绑定角色卡失败: $oldName -> $newName", e)
                throw e
            }
        }
    }

    /**
     * 批量重命名消息中的角色名称
     * @return 受影响的消息数量
     */
    suspend fun renameRoleNameInMessages(oldName: String, newName: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.renameRoleName(oldName, newName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量重命名消息中的角色名失败: $oldName -> $newName", e)
                throw e
            }
        }
    }
}

data class ChatImportResult(
    val new: Int,
    val updated: Int,
    val skipped: Int
) {
    val total: Int
        get() = new + updated
}
