package com.ai.assistance.operit.core.tools

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitAppearance
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.ui.features.orbit.OrbitLogoDefaults
import java.io.File
import org.json.JSONObject

/**
 * Orbit's avatar. The user does not change it on the page: they pick a picture or a pet there,
 * which lands in the chat as a request, and Orbit changes it with this tool.
 */
internal object OrbitAvatarTools {
    const val NAME = "orbit_set_avatar"
    /** The logo color value that goes back to the logo's own default color. */
    private const val DEFAULT = "default"

    val prompt = ToolPrompt(
        name = NAME,
        description = "换你自己的头像：用用户发来的图片、用户的某只宠物、某张角色卡的头像，或者换回默认的 Orbit logo 并给它配色。",
        parametersStructured = listOf(
            ToolParameterSchema("source", description = "image：用图片；pet：用宠物；role_card：用角色卡的头像；default：恢复默认头像（Orbit 的 logo，会跟着你的状态动）；logo_colors：只改 logo 的颜色", required = true),
            ToolParameterSchema("image_path", description = "image：图片附件的 id（就是它的路径）", required = false),
            ToolParameterSchema("pet_id", description = "pet：宠物编号，用户的消息里会写明", required = false),
            ToolParameterSchema("role_card_id", description = "role_card：角色卡 id，用户的消息里会写明，或用 orbit_soul action=list_role_cards 查", required = false),
            ToolParameterSchema("user_color", description = "default / logo_colors：logo 中间核心的颜色，代表用户。任意 #RRGGBB（或 #RGB），default 表示恢复默认色；不填就不变", required = false),
            ToolParameterSchema("orbit_color", description = "default / logo_colors：logo 外圈和两颗卫星的颜色，代表你自己。任意 #RRGGBB（或 #RGB），default 表示恢复默认色；不填就不变", required = false),
        ),
        details = """
  - 头像只能由你用这个工具换。用户想换头像时，会在 Orbit 资料页选图片或宠物；选好的内容会放进聊天框，他点发送后才会发给你。
  - 图片附件不用先读，直接把它的 id 填进 image_path。
  - 默认 logo 的含义：中间的核心是用户，外圈和两颗卫星是你，你始终围绕着用户转。两种颜色都可以自己挑，也可以按用户的要求填，颜色是头像的一部分，只能由你改。默认色：中间 ${OrbitLogoDefaults.USER_HEX}，外圈 ${OrbitLogoDefaults.ORBIT_HEX}。
  - logo 就是你的头像。聊天配色选「从头像取色」（默认）时，会从中间代表用户的颜色取色，用户的消息气泡也是这个颜色，所以改 user_color 也会改变聊天配色。
  - logo_colors 至少填 user_color、orbit_color 之一。正在用图片或宠物头像时 logo 不显示，颜色会先存着，换回 source=default 后生效。颜色按你填的原样显示；logo 画在白底上，很浅的颜色会看不清。
  - 换好后简单告诉用户就行。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value.trim() }
            val preferences = OrbitPreferences.getInstance(context)
            val source = args["source"]
            // Read and check both colors before changing anything, so a bad value changes nothing.
            val userColor = color(args, "user_color")
            val orbitColor = color(args, "orbit_color")
            val colorGiven = "user_color" in args || "orbit_color" in args
            require(!colorGiven || source == "default" || source == "logo_colors") {
                "user_color and orbit_color only work with source=default or source=logo_colors"
            }
            // The colors before and after this call, taken inside the same write so another chat
            // changing them at the same time cannot mix into this result.
            var before: Pair<String?, String?>? = null
            var after: Pair<String?, String?>? = null
            // Colors are saved before the logo comes back, so it never shows the old colors first.
            suspend fun saveColors() {
                if (userColor == null && orbitColor == null) return
                preferences.updateAppearance(orbit.id) {
                    val next = it.copy(
                        logoUserColor = if (userColor != null) userColor.takeIf { c -> c != DEFAULT } else it.logoUserColor,
                        logoOrbitColor = if (orbitColor != null) orbitColor.takeIf { c -> c != DEFAULT } else it.logoOrbitColor,
                    )
                    before = it.logoUserColor to it.logoOrbitColor
                    after = next.logoUserColor to next.logoOrbitColor
                    next
                }
            }
            when (source) {
                "image" -> {
                    val path = args["image_path"].orEmpty()
                    require(path.isNotEmpty()) { "source=image needs image_path: the attachment id (its path) from the user's message" }
                    val uri = when {
                        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
                        else -> File(path).also { require(it.isFile) { "No image at $path" } }.let(Uri::fromFile)
                    }
                    val saved = checkNotNull(
                        com.ai.assistance.operit.util.FileUtils.copyFileToInternalStorage(context, uri, "orbit_avatar")
                    ) { "Could not read the image at $path; ask the user to send it again" }
                    preferences.setAvatar(orbit.id, saved.toString())
                }
                "pet" -> {
                    val petId = args["pet_id"].orEmpty()
                    require(petId.isNotEmpty()) { "source=pet needs pet_id; the user's message names it" }
                    val pet = checkNotNull(
                        com.ai.assistance.operit.pet.PetPreferences.get(context).pets.value.firstOrNull { it.id == petId }
                    ) { "No pet with id $petId" }
                    val file = checkNotNull(
                        com.ai.assistance.operit.pet.PetAvatar.export(context, pet, OrbitPreferences.AVATAR_FILE_PREFIX)
                    ) { "Could not make a picture of this pet" }
                    preferences.setAvatar(orbit.id, Uri.fromFile(file).toString(), petId = pet.id)
                }
                "role_card" -> {
                    val cardId = args["role_card_id"].orEmpty()
                    require(cardId.isNotEmpty()) { "source=role_card needs role_card_id; the user's message names it, or list cards with orbit_soul action=list_role_cards" }
                    OrbitCompanion.useRoleCardAvatar(context, orbit.id, cardId)
                }
                "default" -> { saveColors(); preferences.setAvatar(orbit.id, null) }
                "logo_colors" -> {
                    require(userColor != null || orbitColor != null) {
                        "source=logo_colors needs user_color, orbit_color or both: #RRGGBB, #RGB or default"
                    }
                    saveColors()
                }
                else -> error("source must be image, pet, role_card, default or logo_colors")
            }
            val now = preferences.current().orbits.firstOrNull { it.id == orbit.id }
            val result = JSONObject().put("changed", true).put("source", source)
            if (now != null && (source == "default" || source == "logo_colors")) {
                val current = now.appearance.logoUserColor to now.appearance.logoOrbitColor
                val (old, new) = (before ?: current) to (after ?: current)
                result.put("user_color", colorChange(old.first, new.first, OrbitLogoDefaults.USER_HEX))
                result.put("orbit_color", colorChange(old.second, new.second, OrbitLogoDefaults.ORBIT_HEX))
                val logoVisible = now.avatarUri.isNullOrBlank()
                result.put("logo_visible", logoVisible)
                result.put("note", if (logoVisible) "The logo with these colors shows on the Orbit page right away."
                    else "You still use a picture, so the logo is hidden; the colors apply once you switch back with source=default.")
            } else {
                result.put("note", "The new avatar shows on the Orbit page right away.")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: "Could not change the avatar")
        }
    }

    /** A given color as "#RRGGBB" or [DEFAULT]; null only when the argument is left out. */
    private fun color(args: Map<String, String>, key: String): String? {
        val raw = args[key] ?: return null
        require(raw.isNotEmpty()) { "$key is empty; give #RRGGBB, #RGB or default, or leave $key out to keep it" }
        if (raw.equals(DEFAULT, ignoreCase = true)) return DEFAULT
        return requireNotNull(OrbitAppearance.normalizeColor(raw)) { "$key=$raw is not a color; use #RRGGBB, #RGB or default" }
    }

    /** before/after as saved; the logo's own color reads "default (#RRGGBB)". */
    private fun colorChange(before: String?, after: String?, defaultHex: String): JSONObject = JSONObject()
        .put("before", before ?: "$DEFAULT ($defaultHex)").put("after", after ?: "$DEFAULT ($defaultHex)")
}
