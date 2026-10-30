package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.data.model.FunctionType
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

class FileBindingService(context: Context) {

    companion object {
        private const val TAG = "FileBindingService"
        private val EDIT_BLOCK_REGEX =
            """\[START-(REPLACE|DELETE)\]\s*\n(.*?)\[END-\1\]""".toRegex(
                RegexOption.DOT_MATCHES_ALL
            )
    }

    private enum class EditAction {
        REPLACE,
        DELETE
    }

    enum class StructuredEditAction {
        REPLACE,
        DELETE
    }

    data class StructuredEditOperation(
        val action: StructuredEditAction,
        val oldContent: String,
        val newContent: String = ""
    )

    private data class EditOperation(
            val action: EditAction,
            val oldContent: String,
            val newContent: String
    )

    /** Result of [applyStructuredEdits]. [error] is null on success. */
    data class StructuredEditResult(
        val content: String,
        val summary: String,
        val diff: String,
        val error: String?
    )

    /**
     * Processes file binding by applying structured edit blocks.
     * Each `[OLD]` block must match exactly one place in the file (see [ExactTextEditor]).
     *
     * 1.  Parses `[START-REPLACE]` or `[START-DELETE]` blocks from the AI-generated code.
     * 2.  For each block, it uses the content of the `[OLD]` section as a search pattern.
     * 3.  The `[OLD]` content is located exactly (Codex apply_patch rules); ambiguous or missing
     *     matches fail without changing anything.
     * 4.  Once the correct range is identified, it applies the `REPLACE` or `DELETE` operation.
     * 5.  If no structured blocks are found, it defaults to a full file replacement.
     *
     * @param originalContent The original content of the file.
     * @param aiGeneratedCode The AI-generated code, containing either edit blocks or full content.
     * @return A Pair containing the final merged content and a diff string.
     */
    suspend fun processFileBinding(
            originalContent: String,
            aiGeneratedCode: String,
            onProgress: ((Float, String) -> Unit)? = null
    ): Pair<String, String> {
        if (originalContent.isNotEmpty() && !aiGeneratedCode.contains("[START-")) {
            val errorMsg =
                "If you want to rewrite an entire existing file: please delete_file first then use apply_file with type=create (do not overwrite directly)." +
                "If you want to modify a file: please use apply_file with type=replace/delete and provide old/new (or old)."
            AppLogger.w(TAG, "Refusing full overwrite for existing content without structured edit blocks. $errorMsg")
            return Pair(originalContent, errorMsg)
        }

        if (aiGeneratedCode.contains("[START-")) {
            onProgress?.invoke(0f, "Parsing patch...")
            AppLogger.d(TAG, "Structured edit blocks detected. Applying exact patch.")
            try {
                val operations = parseEditOperations(aiGeneratedCode)
                if (operations.isEmpty()) {
                    return Pair(originalContent, "Error: Could not apply patch. Reason: No valid edit operations found in the patch code.")
                }
                val result = applyExact(originalContent, operations)
                if (result.error != null) {
                    AppLogger.w(TAG, "Exact patch failed. Reason: ${result.error}")
                    return Pair(originalContent, "Error: Could not apply patch. Reason: ${result.error}")
                }
                onProgress?.invoke(1f, "Patch applied")
                return Pair(result.content, result.summary + "\n" + result.diff)
            } catch (e: InvalidPatchException) {
                AppLogger.w(TAG, "Invalid patch: ${e.message}")
                return Pair(originalContent, "Error: Could not apply patch. Reason: ${e.message}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error during patch process.", e)
                return Pair(originalContent, "Error: An unexpected exception occurred during the patching process: ${e.message}")
            }
        }

        // Default to full file replacement if no special instructions are found
        AppLogger.d(TAG, "No structured blocks found. Assuming full file replacement.")
        val normalizedOriginalContent = originalContent.replace("\r\n", "\n")
        val normalizedAiGeneratedCode = aiGeneratedCode.replace("\r\n", "\n").trim()
        val diffString = generateDiff(normalizedOriginalContent, normalizedAiGeneratedCode)
        return Pair(normalizedAiGeneratedCode, diffString)
    }

    suspend fun processFileBindingOperations(
        originalContent: String,
        operations: List<StructuredEditOperation>,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<String, String> {
        onProgress?.invoke(0f, "Searching match...")
        val result = applyStructuredEdits(originalContent, operations)
        if (result.error != null) {
            return Pair(originalContent, "Error: Could not apply patch. Reason: ${result.error}")
        }
        onProgress?.invoke(1f, "Patch applied")
        return Pair(result.content, result.summary + "\n" + result.diff)
    }

    /** Applies structured edits with exact matching and reports what changed. */
    fun applyStructuredEdits(
        originalContent: String,
        operations: List<StructuredEditOperation>
    ): StructuredEditResult {
        if (operations.isEmpty()) {
            return StructuredEditResult(originalContent, "", "", "No valid edit operations provided")
        }
        val internalOps = ArrayList<EditOperation>(operations.size)
        for ((index, op) in operations.withIndex()) {
            val label = if (operations.size > 1) "Edit #${index + 1}: " else ""
            if (op.oldContent.isBlank()) {
                return StructuredEditResult(originalContent, "", "", "$label`old` is empty. Nothing was changed.")
            }
            when (op.action) {
                StructuredEditAction.REPLACE -> {
                    if (op.newContent.isEmpty()) {
                        return StructuredEditResult(
                            originalContent, "", "",
                            "$label`new` is empty. To remove text, use type=delete. Nothing was changed."
                        )
                    }
                    internalOps.add(EditOperation(EditAction.REPLACE, op.oldContent, op.newContent))
                }
                StructuredEditAction.DELETE -> internalOps.add(EditOperation(EditAction.DELETE, op.oldContent, ""))
            }
        }
        return applyExact(originalContent, internalOps)
    }

    private fun applyExact(originalContent: String, operations: List<EditOperation>): StructuredEditResult {
        val edits = operations.map { op ->
            ExactTextEditor.Edit(
                oldText = op.oldContent,
                newText = if (op.action == EditAction.DELETE) "" else op.newContent
            )
        }
        return when (val result = ExactTextEditor.apply(originalContent, edits)) {
            is ExactTextEditor.Result.Failure ->
                StructuredEditResult(originalContent, "", "", result.message)
            is ExactTextEditor.Result.Success ->
                StructuredEditResult(
                    content = result.content,
                    summary = ExactTextEditor.describe(result.applied),
                    diff = generateDiff(originalContent, result.content),
                    error = null
                )
        }
    }

    private fun generateDiff(original: String, modified: String): String {
        return generateUnifiedDiff(original, modified)
    }

    /**
     * Generates a unified diff string with line numbers and change indicators (+, -).
     * This is a public utility that can be used by other services.
     *
     * @param original The original text content.
     * @param modified The modified text content.
     * @return A formatted string representing the unified diff.
     */
    fun generateUnifiedDiff(original: String, modified: String): String {
        val originalLines = if (original.isEmpty()) emptyList() else original.lines()
        val modifiedLines = if (modified.isEmpty()) emptyList() else modified.lines()
        val patch = DiffUtils.diff(originalLines, modifiedLines)

        if (patch.deltas.isEmpty()) {
            return "No changes detected (files are identical)"
        }

        // First, calculate stats
        val sb = StringBuilder()
        var additions = 0
        var deletions = 0
        patch.deltas.forEach { delta ->
            when (delta.type) {
                com.github.difflib.patch.DeltaType.INSERT -> additions += delta.target.lines.size
                com.github.difflib.patch.DeltaType.DELETE -> deletions += delta.source.lines.size
                com.github.difflib.patch.DeltaType.CHANGE -> {
                    additions += delta.target.lines.size
                    deletions += delta.source.lines.size
                }
                else -> {}
            }
        }
        sb.appendLine("Changes: +$additions -$deletions lines")
        // sb.appendLine()

        // Generate a standard unified diff to process
        val unifiedDiffLines = UnifiedDiffUtils.generateUnifiedDiff(
            "a/file",
            "b/file",
            originalLines,
            patch,
            3 // Context lines
        )

        val resultLines = mutableListOf<String>()
        var origLineNum = 0
        var newLineNum = 0
        val hunkHeaderRegex = """^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""".toRegex()

        for (line in unifiedDiffLines) {
            when {
                line.startsWith("---") || line.startsWith("+++") -> continue // Skip header lines
                line.startsWith("@@") -> {
                    resultLines.add(line)
                    hunkHeaderRegex.find(line)?.let {
                        origLineNum = it.groupValues[1].toInt()
                        newLineNum = it.groupValues[3].toInt()
                    }
                }
                line.startsWith("-") -> {
                    resultLines.add("-${origLineNum.toString().padEnd(4)}|${line.substring(1)}")
                    origLineNum++
                }
                line.startsWith("+") -> {
                    resultLines.add("+${newLineNum.toString().padEnd(4)}|${line.substring(1)}")
                    newLineNum++
                }
                line.startsWith(" ") -> {
                    resultLines.add(" ${origLineNum.toString().padEnd(4)}|${line.substring(1)}")
                    origLineNum++
                    newLineNum++
                }
            }
        }

        sb.append(resultLines.joinToString("\n"))
        return sb.toString()
    }

    private fun parseEditOperations(patchCode: String): List<EditOperation> {
        val operations = mutableListOf<EditOperation>()
        val lines = patchCode.lines()
        var i = 0
        var blockNumber = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("[START-")) {
                blockNumber++
                val header = line
                val actionStr = header.substringAfter("[START-").substringBefore("]")

                val action = try {
                    EditAction.valueOf(actionStr)
                } catch (e: IllegalArgumentException) {
                    throw InvalidPatchException("Edit block #$blockNumber has unknown action [START-$actionStr]. Nothing was changed.")
                }

                var oldContent = ""
                var newContent = ""
                var inBlock: String? = null
                i++ // Move to content

                while (i < lines.size && !lines[i].trim().startsWith("[END-$actionStr]")) {
                    val currentLine = lines[i]
                    val trimmedLine = currentLine.trim()

                    if (trimmedLine.startsWith("[START-")) {
                        throw InvalidPatchException("Edit block #$blockNumber is missing [END-$actionStr] before the next [START-...]. Nothing was changed.")
                    } else if (trimmedLine.startsWith("[OLD]")) {
                        inBlock = "OLD"
                        val inline = currentLine.substringAfter("[OLD]", "")
                        if (inline.isNotEmpty()) {
                            oldContent += inline + "\n"
                        }
                    } else if (trimmedLine.startsWith("[NEW]")) {
                        inBlock = "NEW"
                        val inline = currentLine.substringAfter("[NEW]", "")
                        if (inline.isNotEmpty()) {
                            newContent += inline + "\n"
                        }
                    } else if (trimmedLine.startsWith("[/OLD]")) inBlock = null
                    else if (trimmedLine.startsWith("[/NEW]")) inBlock = null
                    else {
                        when (inBlock) {
                            "OLD" -> oldContent += currentLine + "\n"
                            "NEW" -> newContent += currentLine + "\n"
                        }
                    }
                    i++
                }
                if (i >= lines.size) {
                    throw InvalidPatchException("Edit block #$blockNumber is missing [END-$actionStr]. Nothing was changed.")
                }

                val normalizedOld = oldContent.removeSuffix("\n").removeSuffix("\r")
                val normalizedNew = newContent.removeSuffix("\n").removeSuffix("\r")

                // Basic validation
                if ((action == EditAction.REPLACE || action == EditAction.DELETE) && normalizedOld.isBlank()) {
                    throw InvalidPatchException("Edit block #$blockNumber has an empty [OLD] section. Nothing was changed.")
                }
                if (action == EditAction.REPLACE && normalizedNew.isBlank()) {
                    throw InvalidPatchException("Edit block #$blockNumber has an empty [NEW] section. Nothing was changed.")
                }

                operations.add(EditOperation(action, normalizedOld, normalizedNew))
            }
            i++
        }
        return operations
    }

    private class InvalidPatchException(message: String) : IllegalArgumentException(message)

    private fun String.trimTrailingNewline(): String = this.trimEnd('\n', '\r')
}
