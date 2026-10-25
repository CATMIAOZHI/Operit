package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelConfigSummary
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
import com.ai.assistance.operit.features.orbit.OrbitModelSwitch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit sets up models for the user: it lists the model configurations, looks up which models a
 * provider offers, asks to add a configuration, and asks to switch the model it uses. Adding and
 * switching wait for the user's approval on a card; for a new configuration the user types the
 * API key and ticks the models there, so the key never reaches the chat.
 */
internal object OrbitModelTools {
    const val NAME = "orbit_models"
    private const val MAX_NAME = 40
    private const val MAX_MODELS = 20
    /** Model names [discover] returns at most; a search word narrows a long list. */
    private const val MAX_DISCOVERED = 150

    val prompt = ToolPrompt(
        name = NAME,
        description = "List the app's model configurations and which ones you use, look up the models a provider offers, " +
            "ask the user to add a configuration (they type the API key and pick the models on the card), or to switch the model you use. " +
            "查看模型配置、查询服务商有哪些模型、申请新建模型配置、申请切换你用的模型（都要用户批准）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / discover / request_create / request_use", required = true),
            ToolParameterSchema("config", description = "discover, request_use: a configuration name from list", required = false),
            ToolParameterSchema("query", description = "discover: optional word to filter model names, e.g. flash", required = false),
            ToolParameterSchema("name", description = "request_create: a short name for the configuration, e.g. DeepSeek (at most $MAX_NAME characters)", required = false),
            ToolParameterSchema("provider", description = "request_create, or discover without config: provider id from list's providers, e.g. DEEPSEEK, OPENAI_GENERIC, ANTHROPIC, OPENROUTER", required = false),
            ToolParameterSchema("endpoint", description = "request_create, or discover without config: the full API endpoint URL from the provider's docs; leave out to use the provider's default", required = false),
            ToolParameterSchema("models", description = "request_create: optional suggested model names, comma-separated; they start ticked on the card. Leave out to let the user pick from the provider's list", required = false),
            ToolParameterSchema("model", description = "request_use: the model name, from the configuration's models or from discover", required = false),
            ToolParameterSchema("use_as", description = "request_create: none (default), main or assistant. request_use (required): main, assistant or multimodal. Only when the user wants it", required = false),
            ToolParameterSchema("thinking", description = "request_use with use_as assistant: optional thinking strength low / medium / high / xhigh / max", required = false),
            ToolParameterSchema("needs_key", description = "request_create: true (default for providers that need one) or false for a local server without a key", required = false),
            ToolParameterSchema("reason", description = "request_create: one sentence telling the user why", required = false),
        ),
        details = """
  - list shows each configuration (no keys, only whether one is set), your main, assistant and picture models, the assistant's thinking strength, the provider ids you may use, and which sign-in accounts are signed in.
  - discover asks the provider which models it offers, using the key already saved in a configuration (config=...), or for a server without a key (provider + endpoint). You only see model names, never the key. Use the exact names it returns; do not guess model names.
  - request_create: look up the provider's endpoint in its official docs first. Leave models out unless you are sure of the names: on the card the user types the API key, the app fetches the provider's model list, and the user ticks the models. It returns at once; end the turn with one short line. You get a hidden note with what was added.
  - request_use switches the model you use for main (your replies), assistant (titles, memory, background work) or multimodal (reading pictures), and can set the assistant's thinking strength. A model that is not in the configuration yet but is in discover's list is added to it. It puts an approval card in the chat; end the turn with one short line.
  - Never ask for the key in chat. If the user sends one anyway, do not repeat it or store it anywhere.
  - Accounts that sign in instead of using a key are not added here. Put a sign-in card with orbit_vault request_chatgpt_login (ChatGPT/Codex) or request_account_login (Claude, Grok, Antigravity, Command Code). After signing in, the user adds the model configuration from that card's "Add model" button; then request_use can switch to it.
  - 模型名别靠猜：已有配置用 discover 查；新建时让用户在卡片里填密钥后从服务商列表里勾。用户在卡片里填密钥，你看不到。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            suspend fun inChat() = check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                "Approval cards only work in a chat with the user; ask the user in chat"
            }
            val configs = ModelConfigManager(context)
            configs.initializeIfNeeded()
            suspend fun named(name: String): ModelConfigSummary {
                require(name.isNotEmpty()) { "Give config: a configuration name from list" }
                return configs.getAllConfigSummaries().firstOrNull { it.name == name }
                    ?: error("No configuration named \"$name\"; use a name exactly as list shows it")
            }
            val result: JSONObject = when (arg("action")) {
                "list" -> {
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
                        .put("your_multimodal", label(mine.multimodal))
                        .put("assistant_thinking", OrbitModelSwitch.effort(mine.assistantThinkingLevel))
                        .put("providers", JSONArray(OrbitApprovalActions.providers()))
                        .put("accounts", accounts(context))
                }
                "discover" -> {
                    val configName = arg("config")
                    val (label, models) = if (configName.isNotEmpty()) {
                        val full = checkNotNull(configs.getModelConfig(named(configName).id)) { "Configuration \"$configName\" could not be read" }
                        configName to remoteModels(context, full)
                    } else {
                        val provider = provider(arg("provider"))
                        val endpoint = endpoint(provider, arg("endpoint"))
                        require(!OrbitApprovalActions.requiresKey(provider, endpoint)) {
                            "This provider needs an API key. Use request_create (models can be left out): the user types the key " +
                                "on the card and picks from the provider's list there. For a configuration that already has a key, pass config."
                        }
                        "${provider.name} $endpoint" to fetch(context) { OrbitApprovalActions.fetchModels(context, provider, endpoint, "") }
                    }
                    val query = arg("query").lowercase()
                    val matching = models.filter { query.isEmpty() || it.lowercase().contains(query) }
                    val inConfig = if (configName.isNotEmpty()) getModelList(named(configName).modelName).toSet() else emptySet()
                    JSONObject().put("source", label).put("total", models.size).put("matching", matching.size)
                        .put("models", JSONArray(matching.take(MAX_DISCOVERED)))
                        .apply { if (inConfig.isNotEmpty()) put("already_in_configuration", JSONArray(inConfig.filter { it in models })) }
                        .put("note", when {
                            models.isEmpty() -> "The provider listed no models."
                            matching.isEmpty() -> "No model name contains \"$query\"; try another word or leave query out."
                            matching.size > MAX_DISCOVERED -> "Showing the first $MAX_DISCOVERED of ${matching.size}; pass query to narrow it down."
                            configName.isNotEmpty() -> "Use these exact names. request_use switches to one (it is added to \"$configName\" if missing)."
                            else -> "Use these exact names in request_create's models."
                        })
                }
                "request_use" -> {
                    inChat()
                    val config = named(arg("config"))
                    val model = arg("model")
                    require(model.isNotEmpty() && model.length <= 120) { "Give model: a model name from the configuration or from discover" }
                    val role = arg("use_as").lowercase()
                    require(role in OrbitModelSwitch.ROLES) { "use_as must be main, assistant or multimodal" }
                    val thinking = arg("thinking").takeIf { it.isNotEmpty() }?.let {
                        require(role == OrbitModelSwitch.ROLE_ASSISTANT) { "thinking only applies with use_as assistant" }
                        OrbitModelSwitch.parseThinking(it) ?: error("thinking must be low, medium, high, xhigh or max")
                    }
                    val adds = model !in getModelList(config.modelName)
                    if (adds) {
                        val full = checkNotNull(configs.getModelConfig(config.id)) { "Configuration \"${config.name}\" could not be read" }
                        val offered = remoteModels(context, full)
                        require(model in offered) {
                            "\"$model\" is neither in \"${config.name}\" nor in the provider's list. Use discover with config=${config.name} " +
                                "and pick an exact name" + offered.filter { it.contains(model, ignoreCase = true) }.take(5)
                                    .takeIf { it.isNotEmpty() }?.joinToString(prefix = "; close names: ").orEmpty()
                        }
                    }
                    val mine = OrbitModelPreferences(context).current(orbit.id)
                    val summaries = configs.getAllConfigSummaries()
                    val current = OrbitModelSwitch.ref(mine, role)
                    require(current?.configId != config.id || current.modelName != model ||
                        (thinking != null && thinking != mine.assistantThinkingLevel)) {
                        "That is already your $role model" + if (thinking != null) " with that thinking strength" else ""
                    }
                    val currentLabel = current?.let { r -> summaries.firstOrNull { it.id == r.configId }?.name?.let { "$it / ${r.modelName}" } }.orEmpty()
                    val request = OrbitModelSwitch.Request(role, config.id, config.name, model, thinking, adds)
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(),
                        OrbitModelSwitch.title(context, request).take(OrbitApprovals.MAX_TITLE),
                        OrbitModelSwitch.detail(context, request, currentLabel, mine.assistantThinkingLevel, arg("reason").take(300)),
                        OrbitModelSwitch.action(request))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. " +
                            "Once the user approves, the app switches it and you get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                "request_create" -> {
                    inChat()
                    val name = arg("name").replace(Regex("\\s+"), " ")
                    require(name.isNotEmpty() && name.length <= MAX_NAME) { "name must be 1-$MAX_NAME characters" }
                    val provider = provider(arg("provider"))
                    val endpoint = endpoint(provider, arg("endpoint"))
                    val models = OrbitApprovalActions.splitModels(arg("models"))
                    require(models.size <= MAX_MODELS && models.all { it.length <= 120 }) {
                        "models may suggest at most $MAX_MODELS model names, or be left out"
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
                            (if (needsKey) "; the user types the API key on it, then picks the models from the provider's list"
                            else "; the user picks the models from the provider's list") +
                            ". End this turn now with one short line. You get a hidden note with what was added.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be list, discover, request_create or request_use")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /** Whether each sign-in account is signed in; never a token or an email. */
    private fun accounts(context: Context): JSONArray {
        val list = JSONArray()
        val chatGpt = runCatching {
            com.ai.assistance.operit.data.preferences.CodexAuthPreferences.getInstance(context).currentState() != null
        }.getOrDefault(false)
        list.put(JSONObject().put("account", "chatgpt").put("provider", ApiProviderType.OPENAI_CODEX.name)
            .put("signed_in", chatGpt).put("sign_in_with", "orbit_vault request_chatgpt_login"))
        OrbitChatCards.accountProviders.forEach { (id, provider) ->
            val signedIn = runCatching {
                com.ai.assistance.operit.data.api.ProviderAccountManager.get(context, provider).account.value != null
            }.getOrDefault(false)
            list.put(JSONObject().put("account", id).put("provider", provider.type.name).put("signed_in", signedIn)
                .put("sign_in_with", "orbit_vault request_account_login provider=$id"))
        }
        return list
    }

    private fun provider(id: String): ApiProviderType =
        ApiProviderType.fromProviderTypeId(id)?.takeIf { it !in OrbitApprovalActions.ACCOUNT_PROVIDERS }
            ?: error("provider must be one of list's providers")

    private fun endpoint(provider: ApiProviderType, given: String): String {
        val endpoint = given.ifEmpty { OrbitApprovalActions.defaultEndpoint(provider) }
        require(endpoint.matches(Regex("https?://\\S{3,300}"))) {
            "endpoint must be a full http(s) URL; this provider has no default, so give it"
        }
        return endpoint
    }

    /** The provider's models for a saved configuration; the saved key is used here and never returned. */
    private suspend fun remoteModels(context: Context, full: ModelConfigData): List<String> {
        val provider = ApiProviderType.fromProviderTypeId(full.apiProviderTypeId) ?: full.apiProviderType
        require(provider !in OrbitApprovalActions.ACCOUNT_PROVIDERS) {
            "\"${full.name}\" signs in with an account; its models are picked in Orbit settings > Models"
        }
        val key = full.apiKey.ifBlank { full.apiKeyPool.firstOrNull { it.isEnabled }?.key.orEmpty() }
        return fetch(context, key) {
            OrbitApprovalActions.fetchModels(context, provider, full.apiEndpoint, key,
                com.ai.assistance.operit.api.chat.llmprovider.parseProviderCustomHeaders(full.customHeaders))
        }
    }

    /** A failed lookup says why without ever echoing the key. */
    private suspend fun fetch(context: Context, key: String = "", block: suspend () -> List<String>): List<String> =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = (e.message ?: e.javaClass.simpleName).let { if (key.length >= 6) it.replace(key, "***") else it }.take(200)
            error("The provider did not return its model list ($why). Check the endpoint; if it is a key problem, the user can fix the key in the app's model settings.")
        }
}
