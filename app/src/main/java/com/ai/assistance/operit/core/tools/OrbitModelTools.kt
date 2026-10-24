package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit sets up a model for the user: it lists the model configurations, and asks to add one.
 * Adding waits for the user's approval on a card, where the user types the API key; the key goes
 * straight into the configuration and never into the chat.
 */
internal object OrbitModelTools {
    const val NAME = "orbit_models"
    private const val MAX_NAME = 40
    private const val MAX_MODELS = 20

    val prompt = ToolPrompt(
        name = NAME,
        description = "List the app's model configurations and which ones you use, and ask the user to add a new one " +
            "(they type the API key on the approval card). 查看模型配置，申请新建模型配置（需用户批准，密钥由用户在卡片里填）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / request_create", required = true),
            ToolParameterSchema("name", description = "request_create: a short name for the configuration, e.g. DeepSeek (at most $MAX_NAME characters)", required = false),
            ToolParameterSchema("provider", description = "request_create: provider id from list's providers, e.g. DEEPSEEK, OPENAI_GENERIC, ANTHROPIC, OPENROUTER", required = false),
            ToolParameterSchema("endpoint", description = "request_create: the full API endpoint URL from the provider's docs; leave out to use the provider's default", required = false),
            ToolParameterSchema("models", description = "request_create: model names, comma-separated, exactly as the provider names them", required = false),
            ToolParameterSchema("use_as", description = "request_create: none (default), main or assistant; only when the user asked you to switch to it", required = false),
            ToolParameterSchema("needs_key", description = "request_create: true (default for providers that need one) or false for a local server without a key", required = false),
            ToolParameterSchema("reason", description = "request_create: one sentence telling the user why", required = false),
        ),
        details = """
  - list shows each configuration (no keys, only whether one is set), your main and assistant models, and the provider ids you may use.
  - request_create: look up the provider's endpoint and model names in its official docs first. It puts an approval card in the chat and returns at once; end the turn with one short line. The user reads what will be added, types the API key on the card and approves; the app adds it and you get a hidden note with the result.
  - Never ask for the key in chat. If the user sends one anyway, do not repeat it or store it anywhere.
  - Accounts that sign in instead of using a key (ChatGPT/Codex, Claude, Grok accounts) are added in the app's model settings, not here.
  - use_as main switches the model you reply with; ask for it only when the user wants that.
  - 先查服务商官方文档确认地址和模型名；用户在批准卡片里填密钥，你看不到。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            val result: JSONObject = when (arg("action")) {
                "list" -> {
                    val configs = ModelConfigManager(context)
                    configs.initializeIfNeeded()
                    val mine = OrbitModelPreferences(context).current(orbit.id)
                    val list = JSONArray()
                    val summaries = configs.getAllConfigSummaries()
                    summaries.forEach { s ->
                        val full = configs.getModelConfig(s.id)
                        list.put(JSONObject().put("name", s.name).put("provider", s.apiProviderTypeId)
                            .put("endpoint", full?.apiEndpoint.orEmpty())
                            .put("models", JSONArray(getModelList(s.modelName)))
                            .put("has_key", !full?.apiKey.isNullOrBlank()))
                    }
                    fun label(ref: com.ai.assistance.operit.data.preferences.OrbitModelRef?): String =
                        ref?.let { r -> summaries.firstOrNull { it.id == r.configId }?.name?.let { "$it / ${r.modelName}" } }.orEmpty()
                    JSONObject().put("configurations", list)
                        .put("your_main", label(mine.main)).put("your_assistant", label(mine.assistant))
                        .put("providers", JSONArray(OrbitApprovalActions.providers()))
                }
                "request_create" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    val name = arg("name").replace(Regex("\\s+"), " ")
                    require(name.isNotEmpty() && name.length <= MAX_NAME) { "name must be 1-$MAX_NAME characters" }
                    val provider = ApiProviderType.fromProviderTypeId(arg("provider"))
                        ?.takeIf { it !in OrbitApprovalActions.ACCOUNT_PROVIDERS }
                        ?: error("provider must be one of list's providers")
                    val endpoint = arg("endpoint").ifEmpty { OrbitApprovalActions.defaultEndpoint(provider) }
                    require(endpoint.matches(Regex("https?://\\S{3,300}"))) {
                        "endpoint must be a full http(s) URL; this provider has no default, so give it"
                    }
                    val models = OrbitApprovalActions.splitModels(arg("models"))
                    require(models.isNotEmpty() && models.size <= MAX_MODELS && models.all { it.length <= 120 }) {
                        "models must list 1-$MAX_MODELS model names"
                    }
                    val useAs = arg("use_as").lowercase().ifEmpty { OrbitApprovalActions.USE_NONE }
                    require(useAs in setOf(OrbitApprovalActions.USE_NONE, OrbitApprovalActions.USE_MAIN, OrbitApprovalActions.USE_ASSISTANT)) {
                        "use_as must be none, main or assistant"
                    }
                    val needsKey = when (arg("needs_key").lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> OrbitApprovalActions.requiresKey(provider, endpoint)
                    }
                    val request = OrbitApprovalActions.ModelRequest(name, provider, endpoint, models, useAs, needsKey)
                    val title = context.getString(com.ai.assistance.operit.R.string.orbit_approval_model_title, name)
                        .take(OrbitApprovals.MAX_TITLE)
                    val detail = OrbitApprovalActions.modelDetail(context, request, arg("reason").take(300))
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) {
                        "Too long for the user to review in one card; list fewer models and add the rest later"
                    }
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(), title, detail,
                        OrbitApprovalActions.modelAction(request))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat" +
                            (if (needsKey) "; the user types the API key on it" else "") +
                            ". End this turn now with one short line. You get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be list or request_create")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
