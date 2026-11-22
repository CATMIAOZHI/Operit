package com.ai.assistance.operit.services.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.DirectoryListingData
import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.core.tools.FileExistsData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages attachment operations for the chat feature Handles adding, removing, and referencing
 * attachments
 */
class AttachmentDelegate(private val context: Context, private val toolHandler: AIToolHandler,
    private val chatIdProvider: () -> String? = { null }) {
    companion object {
        private const val TAG = "AttachmentDelegate"
        private const val PACKAGE_ATTACHMENT_PREFIX = "package_attach:"
        private const val WORKSPACE_MENTION_ATTACHMENT_PREFIX = "workspace_mention:"
    }

    // State for attachments
    private val _attachments = MutableStateFlow<List<AttachmentInfo>>(emptyList())
    val attachments: StateFlow<List<AttachmentInfo>> = _attachments
    private val attachmentListLock = Any()

    /**
     * Each chat keeps its own unsent attachments, like its typed draft: [_attachments] holds the
     * open chat's, the others wait here until their chat opens again. A file picked in a side chat
     * then never goes out with the main chat's next message.
     */
    private var activeChatId: String? = null
    private val otherChats = HashMap<String, List<AttachmentInfo>>()

    /** The chat now open is [chatId]; its attachments show and the previous chat's are kept for it. */
    fun setActiveChat(chatId: String?) {
        synchronized(attachmentListLock) {
            val previous = activeChatId
            if (previous == chatId) return
            val current = _attachments.value
            if (previous != null) {
                if (current.isEmpty()) otherChats.remove(previous) else otherChats[previous] = current
            }
            activeChatId = chatId
            // Picked before any chat was known: they belong to the first one opened.
            _attachments.value = chatId?.let { otherChats.remove(it) } ?: if (previous == null) current else emptyList()
        }
    }

    /** The chat an attachment being prepared now goes to, even if another chat opens meanwhile. */
    private fun targetChat(): String? = synchronized(attachmentListLock) { activeChatId }

    private fun listFor(target: String?): List<AttachmentInfo> =
        if (target == activeChatId) _attachments.value else otherChats[target].orEmpty()

    private fun setList(target: String?, list: List<AttachmentInfo>) {
        if (target == activeChatId) _attachments.value = list
        else if (target != null) { if (list.isEmpty()) otherChats.remove(target) else otherChats[target] = list }
    }

    // Events
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent

    /**
     * Adds multiple attachments in one shot (dedup by filePath). When a path is already in the box,
     * the one kept is a quote if either is: a quoted Orbit file must never lose that mark, or its ✕
     * would delete it as an unsent upload.
     */
    fun addAttachments(attachments: List<AttachmentInfo>, target: String? = targetChat()): List<AttachmentInfo> {
        if (attachments.isEmpty()) return emptyList()
        synchronized(attachmentListLock) {
            val quoted = attachments.filter { it.orbitReference }.mapTo(mutableSetOf()) { it.filePath }
            // An older copy of the same attachment (a queued message, say) still thinks it is an
            // unsent upload; marking it here keeps its ✕ and other cleanups from deleting it.
            quoted.forEach { com.ai.assistance.operit.features.orbit.OrbitUploads.markReferenced(it) }
            val before = listFor(target)
            val currentList = before.map {
                if (it.filePath in quoted && !it.orbitReference) it.copy(orbitReference = true) else it
            }
            val existingPaths = currentList.mapTo(mutableSetOf()) { it.filePath }
            val usedFileNames = currentList.mapTo(mutableSetOf()) { it.fileName }
            val toAdd =
                    attachments.mapNotNull { incoming ->
                        if (!existingPaths.add(incoming.filePath)) {
                            null
                        } else {
                            val uniqueFileName = uniqueAttachmentFileName(incoming.fileName, usedFileNames)
                            usedFileNames.add(uniqueFileName)
                            if (uniqueFileName == incoming.fileName) {
                                incoming
                            } else {
                                incoming.copy(fileName = uniqueFileName)
                            }
                        }
                    }
            if (toAdd.isNotEmpty() || currentList != before) {
                setList(target, currentList + toAdd)
            }
            return toAdd
        }
    }

    private fun appendAttachment(attachment: AttachmentInfo, target: String? = targetChat()): AttachmentInfo {
        synchronized(attachmentListLock) {
            val currentList = listFor(target)
            val usedFileNames = currentList.mapTo(mutableSetOf()) { it.fileName }
            val uniqueFileName = uniqueAttachmentFileName(attachment.fileName, usedFileNames)
            val attachmentToAdd =
                    if (uniqueFileName == attachment.fileName) {
                        attachment
                    } else {
                        attachment.copy(fileName = uniqueFileName)
                    }
            setList(target, currentList + attachmentToAdd)
            return attachmentToAdd
        }
    }

    private fun replaceAttachmentByPath(attachment: AttachmentInfo) {
        synchronized(attachmentListLock) {
            val currentList = _attachments.value.filterNot { it.filePath == attachment.filePath }
            val usedFileNames = currentList.mapTo(mutableSetOf()) { it.fileName }
            val uniqueFileName = uniqueAttachmentFileName(attachment.fileName, usedFileNames)
            val attachmentToAdd =
                    if (uniqueFileName == attachment.fileName) {
                        attachment
                    } else {
                        attachment.copy(fileName = uniqueFileName)
                    }
            _attachments.value = currentList + attachmentToAdd
        }
    }

    private fun uniqueAttachmentFileName(fileName: String, usedFileNames: Set<String>): String {
        if (!usedFileNames.contains(fileName)) return fileName

        val dotIndex = fileName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < fileName.lastIndex
        val baseName = if (hasExtension) fileName.substring(0, dotIndex) else fileName
        val extension = if (hasExtension) fileName.substring(dotIndex) else ""

        var index = 2
        var candidate = "${baseName}_$index$extension"
        while (usedFileNames.contains(candidate)) {
            index += 1
            candidate = "${baseName}_$index$extension"
        }
        return candidate
    }

    /**
     * Inserts a reference to an attachment at the current cursor position in the user's message
     * @return the formatted reference string
     */
    fun createAttachmentReference(attachment: AttachmentInfo): String {
        // Generate XML reference for the attachment
        val attachmentRef = StringBuilder("<attachment ")
        attachmentRef.append("id=\"${attachment.filePath}\" ")
        attachmentRef.append("filename=\"${attachment.fileName}\" ")
        attachmentRef.append("type=\"${attachment.mimeType}\" ")

        // Add size property
        if (attachment.fileSize > 0) {
            attachmentRef.append("size=\"${attachment.fileSize}\" ")
        }

        // Add content property (if exists)
        if (attachment.content.isNotEmpty()) {
            attachmentRef.append("content=\"${attachment.content}\" ")
        }

        attachmentRef.append("/>")

        return attachmentRef.toString()
    }

    /** Handles a photo taken by the camera */
    suspend fun handleTakenPhoto(uri: Uri) =
            withContext(Dispatchers.IO) {
                val target = targetChat()
                try {
                    val fileName = "camera_${System.currentTimeMillis()}.jpg"
                    val tempFile = createTempFileFromUri(uri, fileName, chatId = target)

                    if (tempFile != null) {
                        AppLogger.d(TAG, "Successfully created temp file from camera URI: ${tempFile.absolutePath}")

                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = tempFile.absolutePath,
                                        fileName = fileName,
                                        mimeType = "image/jpeg",
                                        fileSize = tempFile.length()
                                )

                        val addedAttachment = appendAttachment(attachmentInfo, target)

                        _toastEvent.emit(context.getString(R.string.attachment_photo_added, addedAttachment.fileName))
                    } else {
                        AppLogger.e(TAG, "Failed to create temp file from camera URI")
                        _toastEvent.emit(context.getString(R.string.attachment_photo_process_failed))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_photo_add_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error adding photo attachment", e)
                }
            }

    suspend fun attachPackage(packageName: String) =
            withContext(Dispatchers.IO) { attachPackageInternal(packageName) }

    /**
     * Quotes an Orbit's own files in the input box. The files themselves are attached, not copies:
     * the message only tells Orbit where they are, and it reads them when it needs to.
     */
    suspend fun attachOrbitFiles(files: List<java.io.File>) =
            withContext(Dispatchers.IO) {
                val target = targetChat()
                val found = files.filter { it.isFile }
                found.forEach {
                    com.ai.assistance.operit.features.orbit.OrbitUploads.retainReference(context, it.absolutePath)
                }
                // A file already in the box (an upload not yet sent, say) becomes a quote too; see addAttachments.
                addAttachments(found.map { file ->
                    AttachmentInfo(
                            filePath = file.absolutePath,
                            fileName = file.name,
                            mimeType = getMimeTypeFromPath(file.absolutePath) ?: "application/octet-stream",
                            fileSize = file.length(),
                            orbitReference = true,
                    )
                }, target)
                val missing = files.size - found.size
                if (missing > 0) _toastEvent.emit(context.getString(R.string.orbit_quote_files_missing, missing))
            }

    /** Handles a file or image attachment selected by the user 确保在IO线程执行所有文件操作 */
    suspend fun handleAttachment(filePath: String, targetChatId: String? = null) =
            withContext(Dispatchers.IO) {
                // Copying can take a while; the file goes to the chat it was picked in.
                val target = targetChatId ?: targetChat()
                try {
                    when {
                        filePath == "screen_capture" -> {
                            captureScreenContent(target)
                            return@withContext
                        }
                        filePath == "notifications_capture" -> {
                            captureNotifications(target = target)
                            return@withContext
                        }
                        filePath == "location_capture" -> {
                            captureLocation(target = target)
                            return@withContext
                        }
                        filePath.startsWith(PACKAGE_ATTACHMENT_PREFIX) -> {
                            attachPackageInternal(filePath.removePrefix(PACKAGE_ATTACHMENT_PREFIX).trim())
                            return@withContext
                        }
                    }

                    var sourceUri: Uri? = null
                    var fileName: String? = null
                    var mimeType: String? = null
                    // A picture pasted from the keyboard waits in a cache copy until it is copied below.
                    var pastedCopy: java.io.File? = null

                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        AppLogger.d(TAG, "Handling content URI: $uri")

                        sourceUri = uri
                        fileName = getFileNameFromUri(uri)
                        mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    } else {
                        val localPath = if (filePath.startsWith("file://")) Uri.parse(filePath).path else filePath
                        if (localPath.isNullOrBlank()) {
                            _toastEvent.emit(context.getString(R.string.attachment_cannot_attach, filePath))
                            return@withContext
                        }

                        val file = java.io.File(localPath)
                        if (!file.exists()) {
                            _toastEvent.emit(context.getString(R.string.attachment_file_not_exist))
                            return@withContext
                        }

                        sourceUri = Uri.fromFile(file)
                        fileName = file.name
                        mimeType = getMimeTypeFromPath(localPath) ?: "application/octet-stream"
                        pastedCopy = file
                    }

                    val resolvedUri = sourceUri
                    val resolvedFileName = fileName
                    val resolvedMimeType = mimeType
                    if (resolvedUri == null || resolvedFileName.isNullOrBlank() || resolvedMimeType.isNullOrBlank()) {
                        _toastEvent.emit(context.getString(R.string.attachment_cannot_attach, filePath))
                        return@withContext
                    }

                    AppLogger.d(TAG, "Copying attachment source to a local temporary file: $resolvedUri")
                    val tempFile = createTempFileFromUri(resolvedUri, resolvedFileName, chatId = target)
                    pastedCopy?.let {
                        com.ai.assistance.operit.ui.features.chat.components.style.input.common.releaseStagedPaste(context, it)
                    }
                    if (tempFile == null || !tempFile.exists()) {
                        AppLogger.e(TAG, "Failed to create temp file from source: $resolvedUri")
                        _toastEvent.emit(context.getString(R.string.attachment_cannot_attach, resolvedFileName))
                        return@withContext
                    }

                    val attachmentInfo =
                            AttachmentInfo(
                                    filePath = tempFile.absolutePath,
                                    fileName = resolvedFileName,
                                    mimeType = resolvedMimeType,
                                    fileSize = tempFile.length()
                            )
                    val addedAttachment = appendAttachment(attachmentInfo, target)

                    _toastEvent.emit(context.getString(R.string.attachment_added, addedAttachment.fileName))
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_add_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error adding attachment", e)
                }
            }

    /** The Orbit whose chat is open, or null for an ordinary chat. Reads settings; call off the main thread. */
    private fun orbitForCurrentChat(chatId: String? = chatIdProvider()): String? =
        try {
            com.ai.assistance.operit.features.orbit.OrbitUploads.orbitFor(context, chatId)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not tell whether this is an Orbit chat: ${e.message}")
            null
        }

    /** 从URI创建临时文件 */
    private suspend fun createTempFileFromUri(
            uri: Uri,
            fileName: String,
            deferTrim: Boolean = false,
            /** The chat the file is for; its Orbit, if any, keeps the copy. */
            chatId: String? = chatIdProvider(),
    ): java.io.File? =
            withContext(Dispatchers.IO) {
                // In an Orbit chat the file goes to that Orbit's own folder, so it lasts.
                val orbitId = orbitForCurrentChat(chatId)
                if (orbitId != null) {
                    return@withContext try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            com.ai.assistance.operit.features.orbit.OrbitUploads.save(context, orbitId, fileName, input, deferTrim)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to keep an Orbit upload", e)
                        null
                    }
                }
                try {
                    val fileExtension = fileName.substringAfterLast('.', "jpg")

                    // 使用外部存储Download/Operit/cleanOnExit目录，而不是缓存目录
                    val externalDir = OperitPaths.cleanOnExitDir()

                    // 确保目录存在
                    if (!externalDir.exists()) {
                        externalDir.mkdirs()
                    }

                    // 确保.nomedia文件存在，防止媒体扫描
                    val noMediaFile = java.io.File(externalDir, ".nomedia")
                    if (!noMediaFile.exists()) {
                        noMediaFile.createNewFile()
                    }

                    val tempFile = java.io.File.createTempFile("attachment_", ".$fileExtension", externalDir)

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    if (tempFile.exists() && tempFile.length() > 0) {
                        AppLogger.d(TAG, "Successfully created temp image file: ${tempFile.absolutePath}")
                        return@withContext tempFile
                    }

                    return@withContext null
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to create temp file", e)
                    return@withContext null
                }
            }

    private suspend fun attachPackageInternal(packageName: String) {
        if (packageName.isBlank()) {
            _toastEvent.emit(context.getString(R.string.attachment_package_failed, packageName))
            return
        }

        val packageManager = PackageManager.getInstance(context, toolHandler)
        val chatId = chatIdProvider()
        val isStandardPackage =
            packageManager.getAvailablePackages().containsKey(packageName) &&
                !packageManager.isToolPkgContainer(packageName)
        val isSkillPackage =
            SkillRepository.forChat(context.applicationContext, chatId).getAiVisibleSkillPackages().containsKey(packageName)
        val isMcpPackage = packageManager.getAvailableServerPackages().containsKey(packageName)

        if (!isStandardPackage && !isSkillPackage && !isMcpPackage) {
            _toastEvent.emit(context.getString(R.string.attachment_package_failed, packageName))
            return
        }

        if (isStandardPackage) {
            // In an Orbit chat the package goes on the Orbit's own list, not the main app's.
            val orbit = runCatching {
                com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId)
            }.getOrNull()
            if (orbit != null) {
                com.ai.assistance.operit.features.orbit.OrbitPackages
                    .setEnabled(context, orbit.id, packageManager, packageName, true)
            } else {
                packageManager.enablePackage(packageName)
            }
        }

        val packageContent = packageManager.usePackage(packageName, chatId)
        if (isPackageAttachmentError(packageName, packageContent)) {
            AppLogger.w(TAG, "添加包附件失败: $packageName, reason=$packageContent")
            _toastEvent.emit(context.getString(R.string.attachment_package_failed, packageName))
            return
        }

        val attachmentInfo =
            AttachmentInfo(
                filePath = packageAttachmentPath(packageName),
                fileName = packageAttachmentDisplayName(packageName),
                mimeType = "text/plain",
                fileSize = packageContent.length.toLong(),
                content = packageContent
            )
        replaceAttachmentByPath(attachmentInfo)
        _toastEvent.emit(context.getString(R.string.attachment_package_added, packageName))
    }

    fun removePackageAttachment(packageName: String) {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isEmpty()) return

        removeAttachment(packageAttachmentPath(normalizedPackageName))
    }

    fun isAttachedMention(token: String): Boolean = attachments.value.any {
        it.filePath == packageAttachmentPath(token) ||
            it.filePath == workspaceMentionAttachmentPath(normalizeWorkspaceRelativePath(token))
    }

    suspend fun attachWorkspaceMention(
        workspacePath: String,
        relativePath: String,
        workspaceEnv: String? = null,
    ) = withContext(Dispatchers.IO) {
        val normalizedRelativePath = normalizeWorkspaceRelativePath(relativePath)
        if (workspacePath.isBlank() || normalizedRelativePath.isBlank()) {
            return@withContext
        }

        val fullPath = buildWorkspaceChildPath(workspacePath, normalizedRelativePath)
        val existsData = readWorkspaceEntryMetadata(fullPath, workspaceEnv)
        if (existsData == null || !existsData.exists) {
            _toastEvent.emit(context.getString(R.string.attachment_file_not_exist))
            return@withContext
        }

        val content =
            if (existsData.isDirectory) {
                buildWorkspaceDirectoryMentionContent(
                    fullPath = fullPath,
                    relativePath = normalizedRelativePath,
                    workspaceEnv = workspaceEnv,
                )
            } else {
                buildWorkspaceFileMentionContent(
                    fullPath = fullPath,
                    relativePath = normalizedRelativePath,
                    workspaceEnv = workspaceEnv,
                )
            }

        val attachmentInfo =
            AttachmentInfo(
                filePath = workspaceMentionAttachmentPath(normalizedRelativePath),
                fileName = normalizedRelativePath,
                mimeType =
                    if (existsData.isDirectory) {
                        "application/vnd.workspace-directory+plain"
                    } else {
                        "text/plain"
                    },
                fileSize = content.length.toLong(),
                content = content,
            )

        replaceAttachmentByPath(attachmentInfo)
    }

    fun removeWorkspaceMentionAttachment(relativePath: String) {
        val normalizedRelativePath = normalizeWorkspaceRelativePath(relativePath)
        if (normalizedRelativePath.isEmpty()) return
        removeAttachment(workspaceMentionAttachmentPath(normalizedRelativePath))
    }

    private fun packageAttachmentPath(packageName: String): String {
        return "$PACKAGE_ATTACHMENT_PREFIX$packageName"
    }

    private fun packageAttachmentDisplayName(packageName: String): String {
        return "包: $packageName"
    }

    private fun workspaceMentionAttachmentPath(relativePath: String): String {
        return "$WORKSPACE_MENTION_ATTACHMENT_PREFIX$relativePath"
    }

    private fun normalizeWorkspaceRelativePath(relativePath: String): String {
        return relativePath.trim().replace('\\', '/').trim('/')
    }

    private fun buildWorkspaceChildPath(workspacePath: String, relativePath: String): String {
        val normalizedRoot = workspacePath.trimEnd('/', '\\')
        val normalizedChild = relativePath.trimStart('/', '\\')
        return if (normalizedRoot.isEmpty()) {
            normalizedChild
        } else {
            "$normalizedRoot/$normalizedChild"
        }
    }

    private suspend fun readWorkspaceEntryMetadata(
        fullPath: String,
        workspaceEnv: String?,
    ): FileExistsData? {
        return if (workspaceEnv.isNullOrBlank()) {
            val file = File(fullPath)
            FileExistsData(
                path = fullPath,
                exists = file.exists(),
                isDirectory = file.isDirectory,
                size = if (file.exists() && !file.isDirectory) file.length() else 0L,
            )
        } else {
            val result =
                toolHandler.executeTool(
                    AITool(
                        name = "file_exists",
                        parameters = listOf(
                            ToolParameter("path", fullPath),
                            ToolParameter("environment", workspaceEnv),
                        ),
                    ),
                )
            result.result as? FileExistsData
        }
    }

    private suspend fun buildWorkspaceFileMentionContent(
        fullPath: String,
        relativePath: String,
        workspaceEnv: String?,
    ): String {
        val parameters =
            buildList {
                add(ToolParameter("path", fullPath))
                add(ToolParameter("text_only", "true"))
                if (!workspaceEnv.isNullOrBlank()) {
                    add(ToolParameter("environment", workspaceEnv))
                }
            }
        val result =
            toolHandler.executeTool(
                AITool(
                    name = "read_file_full",
                    parameters = parameters,
                ),
            )
        val content = (result.result as? FileContentData)?.content.orEmpty()
        return buildString {
            appendLine("Selected workspace file: $relativePath")
            appendLine("This file was referenced via @ mention.")
            appendLine()
            appendLine("File content:")
            append(content)
        }
    }

    private suspend fun buildWorkspaceDirectoryMentionContent(
        fullPath: String,
        relativePath: String,
        workspaceEnv: String?,
    ): String {
        val parameters =
            buildList {
                add(ToolParameter("path", fullPath))
                if (!workspaceEnv.isNullOrBlank()) {
                    add(ToolParameter("environment", workspaceEnv))
                }
            }
        val result =
            toolHandler.executeTool(
                AITool(
                    name = "list_files",
                    parameters = parameters,
                ),
            )
        val listing = result.result as? DirectoryListingData
        return buildString {
            appendLine("Selected workspace directory: $relativePath")
            appendLine("This directory was referenced via @ mention.")
            appendLine()
            appendLine("Directory entries:")
            if (listing == null || listing.entries.isEmpty()) {
                appendLine("(empty)")
            } else {
                listing.entries
                    .sortedWith(
                        compareBy<DirectoryListingData.FileEntry> { !it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    )
                    .forEach { entry ->
                        val kind = if (entry.isDirectory) "[DIR]" else "[FILE]"
                        appendLine("$kind ${entry.name}")
                    }
            }
        }
    }

    private fun isPackageAttachmentError(packageName: String, packageContent: String): Boolean {
        if (packageContent.isBlank()) {
            return true
        }
        return packageContent.startsWith("Package not found: ") ||
            packageContent.startsWith("Failed to load package data for: ") ||
            packageContent.startsWith("Missing required environment variables for package ") ||
            packageContent.startsWith("ToolPkg container '") ||
            packageContent.contains(" is inactive.") ||
            packageContent.startsWith("MCP server '") ||
            packageContent.startsWith("Cannot connect to MCP server") ||
            packageContent.startsWith("Cannot get MCP server configuration") ||
            packageContent == "Skill '$packageName' is set to not show to AI"
    }

    /** Removes an attachment by its file path */
    fun removeAttachment(filePath: String) {
        synchronized(attachmentListLock) {
            _attachments.value = _attachments.value.filter { it.filePath != filePath }
        }
    }

    /**
     * The user took an attachment out of the input box. Besides [removeAttachment], an upload an
     * Orbit kept for it goes too: it was never sent. (Moving attachments into the send queue uses
     * [removeAttachment] alone, so queued files stay.) A quoted Orbit file is the file itself and
     * is never deleted here; nor is a path no longer in the box (a second tap on its ✕).
     */
    fun discardAttachment(filePath: String) {
        val upload = synchronized(attachmentListLock) {
            val attachment = _attachments.value.firstOrNull { it.filePath == filePath }
            _attachments.value = _attachments.value.filter { it.filePath != filePath }
            attachment != null && !attachment.orbitReference
        }
        if (!upload || com.ai.assistance.operit.features.orbit.OrbitUploads.isReferenced(filePath)) return
        discardScope.launch {
            try {
                com.ai.assistance.operit.features.orbit.OrbitUploads.discard(context, filePath)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not remove an unsent Orbit upload: ${e.message}")
            }
        }
    }

    private val discardScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Swaps the picture at [oldPath] for [edited], an edited copy, keeping its place in the box,
     * and returns once it is there. The copy is stored like any picture the user adds (an Orbit's
     * upload in an Orbit chat); the old one goes as when taken out with its ✕, so a quoted Orbit
     * file itself is never touched. While [keepOld] says a message is being sent, which has taken
     * the box's pictures, nothing is replaced. Throws when the copy could not be stored or placed.
     * [edited] is a scratch file the caller removes.
     */
    suspend fun replaceEditedImage(
            oldPath: String,
            edited: java.io.File,
            fileName: String,
            keepOld: () -> Boolean = { false },
    ) =
            withContext(Dispatchers.IO) {
                // Gone from the box meanwhile: nothing to replace.
                if (synchronized(attachmentListLock) { _attachments.value.none { it.filePath == oldPath } }) {
                    return@withContext
                }
                // In an Orbit chat the copy is that Orbit's upload; room is made only once it has
                // replaced the old picture, so a replacement that falls through costs no older upload.
                val orbitId = orbitForCurrentChat()
                val stored = createTempFileFromUri(Uri.fromFile(edited), fileName, deferTrim = true)
                check(stored != null && stored.exists()) { context.getString(R.string.attachment_edit_save_failed) }
                var placed = false
                try {
                    val size = stored.length()
                    val mimeType = getMimeTypeFromPath(stored.absolutePath)
                        ?: if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                    // On the main thread, where sending reads the box and marks the chat as preparing in
                    // one go: either the send took the old picture (and keeps it), or it sees the new one.
                    withContext(Dispatchers.Main.immediate) {
                        synchronized(attachmentListLock) {
                            val list = _attachments.value
                            val index = list.indexOfFirst { it.filePath == oldPath }
                            if (index >= 0 && !keepOld()) {
                                val old = list[index]
                                val others = list.filterIndexed { i, _ -> i != index }.mapTo(mutableSetOf()) { it.fileName }
                                _attachments.value = list.toMutableList().also {
                                    it[index] = AttachmentInfo(
                                            filePath = stored.absolutePath,
                                            fileName = uniqueAttachmentFileName(fileName, others),
                                            mimeType = mimeType,
                                            fileSize = size,
                                    )
                                }
                                placed = true
                                // Started here, with the swap: the editor closing now (its picture is
                                // replaced) cannot stop the old upload's removal or the room making.
                                val dropOld = !old.orbitReference
                                discardScope.launch {
                                    try {
                                        if (dropOld) {
                                            com.ai.assistance.operit.features.orbit.OrbitUploads.discard(context, oldPath)
                                        }
                                        if (orbitId != null) {
                                            // The old picture, if still there (a quoted file, or one a
                                            // message holds), is not made room with either.
                                            com.ai.assistance.operit.features.orbit.OrbitUploads.makeRoom(
                                                    context, orbitId, stored, protect = java.io.File(oldPath))
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.w(TAG, "Could not tidy up after an edited picture: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                    check(placed) { context.getString(R.string.attachment_edit_save_failed) }
                } finally {
                    // Taken out while being stored, a send under way, or stopped part way: the copy is not wanted.
                    if (!placed) discardStoredCopy(stored)
                }
            }

    /** Removes a copy made for the box that never got there. */
    private fun discardStoredCopy(stored: java.io.File) {
        try {
            com.ai.assistance.operit.features.orbit.OrbitUploads.discard(context, stored.absolutePath)
            if (stored.absoluteFile.startsWith(OperitPaths.cleanOnExitDir().absoluteFile)) stored.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not remove an unused edited picture: ${e.message}")
        }
    }

    /** Clears the attachments of [chatId], by default the chat on screen. */
    fun clearAttachments(chatId: String? = null) {
        synchronized(attachmentListLock) {
            setList(chatId ?: activeChatId, emptyList())
        }
    }

    /** Update attachments with a new list */
    fun updateAttachments(newAttachments: List<AttachmentInfo>) {
        synchronized(attachmentListLock) {
            _attachments.value = newAttachments
        }
    }

    /** Captures the screen as an image attachment, using the same image path as selected regions. */
    suspend fun captureScreenContent(target: String? = targetChat()) = withContext(Dispatchers.IO) {
        var capturedFile: File? = null
        var attachmentFile: File? = null
        try {
            // The user's own capture, so it does not go through the model's (retired) phone tools.
            val screenshotPath = com.ai.assistance.operit.core.tools.UserScreenCapture.capture(context)
            if (screenshotPath == null) {
                _toastEvent.emit(context.getString(
                    R.string.attachment_screen_content_failed,
                    context.getString(R.string.attachment_screenshot_failed)
                ))
                return@withContext
            }
            val file = File(screenshotPath)
            capturedFile = file
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(screenshotPath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                _toastEvent.emit(context.getString(
                    R.string.attachment_screen_content_failed,
                    context.getString(R.string.screen_ocr_load_failed)
                ))
                return@withContext
            }
            // Some capture backends reuse short filenames. An attachment must keep its own
            // snapshot until the model reads it, even after subsequent screen captures.
            // In an Orbit chat it goes to that Orbit's Library instead, so it lasts.
            val orbitId = orbitForCurrentChat(target)
            val snapshot = if (orbitId != null) {
                file.inputStream().use { input ->
                    com.ai.assistance.operit.features.orbit.OrbitUploads.save(context, orbitId, "screenshot.png", input)
                } ?: error("The screenshot is empty")
            } else {
                File.createTempFile(
                    "screen_attachment_", ".png", OperitPaths.cleanOnExitInternalDir(context)
                ).also { temp ->
                    attachmentFile = temp
                    file.inputStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            appendAttachment(AttachmentInfo(
                filePath = snapshot.absolutePath,
                fileName = "screenshot.png",
                mimeType = "image/png",
                fileSize = snapshot.length()
            ), target)
            attachmentFile = null
            _toastEvent.emit(context.getString(R.string.attachment_screen_content_added))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _toastEvent.emit(context.getString(R.string.attachment_screen_content_failed, e.message ?: ""))
            AppLogger.e(TAG, "Error capturing screen content", e)
        } finally {
            attachmentFile?.delete()
            capturedFile?.delete()
        }
    }

    /** 获取设备当前通知并作为附件添加到消息 使用get_notifications AITool获取通知数据 确保在IO线程中执行 */
    suspend fun captureNotifications(limit: Int = 10, target: String? = targetChat()) =
            withContext(Dispatchers.IO) {
                try {
                    // 创建工具参数
                    val toolParams =
                            listOf(
                                    ToolParameter("limit", limit.toString()),
                                    ToolParameter("include_ongoing", "true")
                            )

                    // 创建工具
                    val notificationsToolTask =
                            AITool(name = "get_notifications", parameters = toolParams)

                    // 执行工具
                    val result = toolHandler.executeTool(notificationsToolTask)

                    if (result.success) {
                        // 生成唯一ID
                        val captureId = "notifications_${System.currentTimeMillis()}"
                        val notificationsContent = result.result.toString()

                        // 创建附件信息
                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = captureId,
                                        fileName = "notifications.json",
                                        mimeType = "application/json",
                                        fileSize = notificationsContent.length.toLong(),
                                        content = notificationsContent
                                )

                        appendAttachment(attachmentInfo, target)

                        _toastEvent.emit(context.getString(R.string.attachment_notifications_added))
                    } else {
                        _toastEvent.emit(context.getString(R.string.attachment_notifications_failed, result.error ?: context.getString(R.string.attachment_unknown_error)))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_notifications_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing notifications", e)
                }
            }

    /** 获取设备当前位置并作为附件添加到消息 使用get_device_location AITool获取位置数据 确保在IO线程中执行 */
    suspend fun captureLocation(highAccuracy: Boolean = true, target: String? = targetChat()) =
            withContext(Dispatchers.IO) {
                try {
                    // 创建工具参数
                    val toolParams =
                            listOf(
                                    ToolParameter("high_accuracy", highAccuracy.toString()),
                                    ToolParameter("timeout", "10") // 10秒超时
                            )

                    // 创建工具
                    val locationToolTask =
                            AITool(name = "get_device_location", parameters = toolParams)

                    // 执行工具
                    val result = toolHandler.executeTool(locationToolTask)

                    if (result.success) {
                        // 生成唯一ID
                        val captureId = "location_${System.currentTimeMillis()}"
                        val locationContent = result.result.toString()

                        // 创建附件信息
                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = captureId,
                                        fileName = "location.json",
                                        mimeType = "application/json",
                                        fileSize = locationContent.length.toLong(),
                                        content = locationContent
                                )

                        appendAttachment(attachmentInfo, target)

                        _toastEvent.emit(context.getString(R.string.attachment_location_added))
                    } else {
                        _toastEvent.emit(context.getString(R.string.attachment_location_failed, result.error ?: context.getString(R.string.attachment_unknown_error)))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_location_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing location", e)
                }
            }

    /** 获取当前时间并作为附件添加到消息 */
    suspend fun captureCurrentTime() =
            withContext(Dispatchers.IO) {
                try {
                    val captureId = "time_${System.currentTimeMillis()}"
                    val timeText =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                    val content = context.getString(R.string.attachment_current_time, timeText)
                    val attachmentInfo =
                        AttachmentInfo(
                            filePath = captureId,
                            fileName = "time.txt",
                            mimeType = "text/plain",
                            fileSize = content.length.toLong(),
                            content = content
                        )

                    appendAttachment(attachmentInfo)

                    _toastEvent.emit(context.getString(R.string.attachment_time_added))
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_time_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing current time", e)
                }
            }

    /**
     * 捕获记忆文件夹并作为附件添加到消息
     * @param folderPaths 选中的记忆文件夹路径列表
     */
    suspend fun captureMemoryFolders(folderPaths: List<String>) =
            withContext(Dispatchers.IO) {
                try {
                    if (folderPaths.isEmpty()) {
                        _toastEvent.emit(context.getString(R.string.attachment_no_memory_folder))
                        return@withContext
                    }

                    // 生成唯一ID
                    val captureId = "memory_context_${System.currentTimeMillis()}"

                    // 构建XML格式的记忆上下文
                    val memoryContext = buildMemoryContextXml(folderPaths)

                    // 创建附件信息
                    val attachmentInfo =
                            AttachmentInfo(
                                    filePath = captureId,
                                    fileName = "memory_context.xml",
                                    mimeType = "application/xml",
                                    fileSize = memoryContext.length.toLong(),
                                    content = memoryContext
                            )

                    appendAttachment(attachmentInfo)

                    val folderCountText = if (folderPaths.size == 1) {
                        context.getString(R.string.attachment_memory_folder_added, folderPaths[0])
                    } else {
                        context.getString(R.string.attachment_memory_folders_added, folderPaths.size)
                    }
                    _toastEvent.emit(folderCountText)
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_memory_folder_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing memory folders", e)
                }
            }

    /**
     * 构建记忆上下文XML字符串
     */
    private fun buildMemoryContextXml(folderPaths: List<String>): String {
        val foldersText = folderPaths.joinToString("\n") { "  - $it" }
        val examplePath = folderPaths.firstOrNull() ?: "some/folder/path"

        return """
<memory_context>
 <available_folders>
$foldersText
 </available_folders>
 <instruction>
- **CRITICAL**: To search within the folders listed above, you **MUST** use the `query_memory` tool and provide the `folder_path` parameter.
- The `folder_path` parameter's value **MUST** be one of the paths from the `<available_folders>` list.
- Autonomously decide whether a search is needed and what query to use based on the user's question.
- Example: `<tool name="query_memory"><param name="query">search query</param><param name="folder_path">$examplePath</param></tool>`
 </instruction>
</memory_context>""".trimIndent()
    }

    /** Get file name from content URI */
    private suspend fun getFileNameFromUri(uri: Uri): String =
            withContext(Dispatchers.IO) {
                val contentResolver = context.contentResolver
                var fileName = context.getString(R.string.attachment_unknown_file)

                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex =
                                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (displayNameIndex != -1) {
                                fileName = cursor.getString(displayNameIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error getting file name from URI", e)
                }

                return@withContext fileName
            }

    /** Get MIME type from file path */
    private fun getMimeTypeFromPath(filePath: String): String? {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}
