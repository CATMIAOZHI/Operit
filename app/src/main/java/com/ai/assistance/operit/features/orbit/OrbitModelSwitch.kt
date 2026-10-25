package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.preferences.OrbitProfile
import org.json.JSONObject

/**
 * Orbit asks to switch which model it uses for one role (main chat, assistant, pictures), and the
 * assistant's thinking strength. The user approves on a card; the app makes the switch.
 */
internal object OrbitModelSwitch {
    const val ROLE_MAIN = "main"
    const val ROLE_ASSISTANT = "assistant"
    const val ROLE_MULTIMODAL = "multimodal"
    val ROLES = listOf(ROLE_MAIN, ROLE_ASSISTANT, ROLE_MULTIMODAL)

    data class Request(
        val role: String,
        val configId: String,
        val configName: String,
        val model: String,
        /** The assistant's thinking strength, 1..5, or null to keep it. */
        val thinking: Int?,
        /** The model is not in the configuration yet (it was found in the provider's list): add it. */
        val adds: Boolean = false,
    )

    fun action(r: Request): String = JSONObject()
        .put("type", OrbitApprovalActions.USE_MODEL)
        .put("role", r.role)
        .put("config_id", r.configId)
        .put("config_name", r.configName)
        .put("model", r.model)
        .apply { r.thinking?.let { put("thinking", it) } }
        .put("adds", r.adds)
        .toString()

    private fun request(action: JSONObject) = Request(
        action.getString("role"),
        action.getString("config_id"),
        action.optString("config_name"),
        action.getString("model"),
        if (action.has("thinking")) action.getInt("thinking") else null,
        action.optBoolean("adds"),
    )

    fun ref(settings: OrbitModelSettings, role: String): OrbitModelRef? = when (role) {
        ROLE_MAIN -> settings.main
        ROLE_ASSISTANT -> settings.assistant
        else -> settings.multimodal
    }

    fun effort(level: Int): String = ApiPreferences.thinkingQualityEffort(level)

    /** A thinking strength by its name (low, medium, high, xhigh, max) or its level 1..5. */
    fun parseThinking(text: String): Int? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it.takeIf { n -> n in ApiPreferences.MIN_THINKING_QUALITY_LEVEL..ApiPreferences.MAX_THINKING_QUALITY_LEVEL } }
        val index = ApiPreferences.THINKING_QUALITY_EFFORTS.indexOf(t)
        return if (index < 0) null else index + ApiPreferences.MIN_THINKING_QUALITY_LEVEL
    }

    private fun roleLabel(context: Context, role: String) = context.getString(when (role) {
        ROLE_MAIN -> R.string.orbit_model_switch_role_main
        ROLE_ASSISTANT -> R.string.orbit_model_switch_role_assistant
        else -> R.string.orbit_model_switch_role_multimodal
    })

    fun detail(context: Context, r: Request, current: String, currentThinking: Int, reason: String): String = buildString {
        append(context.getString(R.string.orbit_model_switch_detail, roleLabel(context, r.role),
            current.ifBlank { context.getString(R.string.orbit_model_switch_none) }, "${r.configName} / ${r.model}"))
        if (r.adds) append("\n").append(context.getString(R.string.orbit_model_switch_adds, r.model, r.configName))
        r.thinking?.let {
            append("\n").append(context.getString(R.string.orbit_model_switch_thinking, effort(currentThinking), effort(it)))
        }
        if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
    }

    fun title(context: Context, r: Request): String =
        context.getString(R.string.orbit_model_switch_title, roleLabel(context, r.role), r.model)

    /** Makes the approved switch, checking again that the configuration and model still exist. */
    suspend fun run(context: Context, orbit: OrbitProfile, action: JSONObject, english: Boolean): String {
        val r = request(action)
        val configs = ModelConfigManager(context)
        configs.initializeIfNeeded()
        val summaries = configs.getAllConfigSummaries()
        val config = summaries.firstOrNull { it.id == r.configId }
        val name = config?.name ?: r.configName
        check(config != null) {
            if (english) "The model configuration \"$name\" no longer exists; nothing was switched. Check orbit_models list."
            else "模型配置「$name」已经不在了，什么都没换。用 orbit_models list 重新看看。"
        }
        var added = false
        var auto = com.ai.assistance.operit.data.model.ModelAutoConfig.Result(emptyList(), emptyList())
        if (r.model !in getModelList(config.modelName)) {
            check(r.adds) {
                if (english) "\"${r.model}\" is no longer in \"$name\"; nothing was switched. Check orbit_models list."
                else "「$name」里已经没有 ${r.model} 了，什么都没换。用 orbit_models list 重新看看。"
            }
            val full = checkNotNull(configs.getModelConfig(config.id))
            configs.saveModelConfig(full.copy(modelName = (getModelList(full.modelName) + r.model).joinToString(",")))
            auto = com.ai.assistance.operit.data.model.ModelAutoConfig.fill(context, config.id, listOf(r.model))
            added = true
        }
        val prefs = OrbitModelPreferences(context)
        val before = prefs.current(orbit.id)
        fun label(ref: OrbitModelRef?) = ref?.let { x -> summaries.firstOrNull { it.id == x.configId }?.name?.let { "$it / ${x.modelName}" } }
            ?: if (english) "none" else "无"
        val old = label(ref(before, r.role))
        val next = OrbitModelRef(config.id, r.model)
        prefs.update(orbit.id) { s ->
            val switched = when (r.role) {
                ROLE_MAIN -> s.copy(main = next)
                ROLE_ASSISTANT -> s.copy(assistant = next)
                else -> s.copy(multimodal = next)
            }
            r.thinking?.let { switched.copy(assistantThinkingLevel = it) } ?: switched
        }
        val now = "$name / ${r.model}"
        val thinking = r.thinking?.let { effort(before.assistantThinkingLevel) to effort(it) }
        return if (english) {
            val role = when (r.role) { ROLE_MAIN -> "main chat model"; ROLE_ASSISTANT -> "assistant model"; else -> "picture model" }
            (if (added) "Added ${r.model} to \"$name\"." + OrbitApprovalActions.autoConfigNote(auto, listOf(r.model), true) + " " else "") +
                "Your $role is now $now (was $old)." +
                (thinking?.let { " The assistant's thinking strength is now ${it.second} (was ${it.first})." } ?: "") +
                (if (r.role == ROLE_MAIN) " Your next reply uses it. If replies fail, tell the user they can switch back in Orbit settings > Models." else "")
        } else {
            val role = when (r.role) { ROLE_MAIN -> "主聊天模型"; ROLE_ASSISTANT -> "辅助模型"; else -> "看图模型" }
            (if (added) "已把 ${r.model} 加进「$name」。" + OrbitApprovalActions.autoConfigNote(auto, listOf(r.model), false) else "") +
                "你的${role}已经换成 $now（原来是 $old）。" +
                (thinking?.let { "辅助模型的思考强度改成了 ${it.second}（原来是 ${it.first}）。" } ?: "") +
                (if (r.role == ROLE_MAIN) "下一条回复就用它。要是回复出错，告诉用户可以在 Orbit 设置 > 模型 换回去。" else "")
        }
    }
}
