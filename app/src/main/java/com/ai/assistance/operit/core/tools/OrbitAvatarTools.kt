package com.ai.assistance.operit.core.tools

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import java.io.File

/**
 * Orbit's avatar. The user does not change it on the page: they pick a picture or a pet there,
 * which lands in the chat as a request, and Orbit changes it with this tool.
 */
internal object OrbitAvatarTools {
    const val NAME = "orbit_set_avatar"

    val prompt = ToolPrompt(
        name = NAME,
        description = "换你自己的头像：用用户发来的图片、用户的某只宠物，或者恢复角色卡的默认头像。",
        parametersStructured = listOf(
            ToolParameterSchema("source", description = "image：用图片；pet：用宠物；default：恢复默认头像", required = true),
            ToolParameterSchema("image_path", description = "image：图片附件的 id（就是它的路径）", required = false),
            ToolParameterSchema("pet_id", description = "pet：宠物编号，用户的消息里会写明", required = false),
        ),
        details = """
  - 头像只能由你用这个工具换。用户想换头像时，会在 Orbit 资料页选图片或宠物，选好的内容作为一条消息发给你。
  - 图片附件不用先读，直接把它的 id 填进 image_path。
  - 换好后简单告诉用户就行。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value.trim() }
            val preferences = OrbitPreferences.getInstance(context)
            when (args["source"]) {
                "image" -> {
                    val path = args["image_path"].orEmpty()
                    require(path.isNotEmpty()) { "Missing image_path" }
                    val uri = when {
                        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
                        else -> File(path).also { require(it.isFile) { "No image at $path" } }.let(Uri::fromFile)
                    }
                    val saved = checkNotNull(
                        com.ai.assistance.operit.util.FileUtils.copyFileToInternalStorage(context, uri, "orbit_avatar")
                    ) { "Could not read the image" }
                    preferences.setAvatar(orbit.id, saved.toString())
                }
                "pet" -> {
                    val petId = args["pet_id"].orEmpty()
                    val pet = checkNotNull(
                        com.ai.assistance.operit.pet.PetPreferences.get(context).pets.value.firstOrNull { it.id == petId }
                    ) { "No pet with id $petId" }
                    val file = checkNotNull(
                        com.ai.assistance.operit.pet.PetAvatar.export(context, pet, OrbitPreferences.AVATAR_FILE_PREFIX)
                    ) { "Could not make a picture of this pet" }
                    preferences.setAvatar(orbit.id, Uri.fromFile(file).toString())
                }
                "default" -> OrbitCompanion.resetAvatar(context, orbit.id)
                else -> error("source must be image, pet or default")
            }
            ToolResult(NAME, true, StringResultData("Avatar changed."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: "Could not change the avatar")
        }
    }
}
