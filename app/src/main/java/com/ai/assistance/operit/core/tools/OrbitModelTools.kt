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
            "ask the user to add a configuration (they type the API key, or use a signed-in account, and pick the models on the card), " +
            "to give an existing configuration a new API key (typed on the card), or to switch the model you use. " +
            "查看模型配置、查询服务商有哪些模型、申请新建模型配置、申请给已有配置换密钥、申请切换你用的模型（都要用户批准）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / discover / request_create / request_key / request_use / open_settings", required = true),
            ToolParameterSchema("config", description = "discover, request_key, request_use: a configuration's select from list (its name, or name#1, name#2 when names repeat)", required = false),
            ToolParameterSchema("query", description = "discover: optional word to filter model names, e.g. flash", required = false),
            ToolParameterSchema("name", description = "request_create: a short name for the configuration, e.g. DeepSeek (at most $MAX_NAME characters)", required = false),
            ToolParameterSchema("provider", description = "request_create, or discover without config: provider id from list's providers, e.g. DEEPSEEK, OPENAI_GENERIC, ANTHROPIC, OPENROUTER", required = false),
            ToolParameterSchema("endpoint", description = "request_create, or discover without config: the full API endpoint URL from the provider's docs; leave out to use the provider's default", required = false),
            ToolParameterSchema("models", description = "request_create: optional suggested model names, comma-separated; they start ticked on the card. Leave out to let the user pick from the provider's list", required = false),
            ToolParameterSchema("model", description = "request_use: the model name, from the configuration's models or from discover", required = false),
            ToolParameterSchema("use_as", description = "request_create: none (default), main or assistant. request_use (required): main, assistant or multimodal. Only when the user wants it", required = false),
            ToolParameterSchema("thinking", description = "request_use with use_as assistant: optional thinking strength low / medium / high / xhigh / max", required = false),
            ToolParameterSchema("needs_key", description = "request_create: true (default for providers that need one) or false for a local server without a key. Ignored for sign-in accounts", required = false),
            ToolParameterSchema("reason", description = "request_create, request_key, request_use: one sentence telling the user why", required = false),
        ),
        details = """
  - list shows each configuration (no keys, only whether one is set), your main, assistant and picture models, the assistant's thinking strength, the provider ids you may use, and which sign-in accounts are signed in. Pass a configuration's select as config; it is the name, or name#1, name#2 … when several share a name.
  - discover asks the provider which models it offers, using the key already saved in a configuration (config=...), or for a server without a key (provider + endpoint). You only see model names, never the key. Use the exact names it returns; do not guess model names.
  - request_create for an API-key provider: look up the provider's endpoint in its official docs first. Leave models out unless you are sure of the names: on the card the user types the API key, the app fetches the provider's model list, and the user ticks the models. For a sign-in account see below. It returns at once; end the turn with one short line. You get a hidden note with what was added.
  - A new key: prefer a new configuration. Use request_create with a name that tells it apart (e.g. "DeepSeek new key"), then request_use to move to it once it works; the old configuration stays as a fallback.
  - request_key replaces the key of one existing configuration (config = its select from list, e.g. "DeepSeek#2" when several share a name). Use it only when the user wants that configuration's key replaced, e.g. the old key was revoked. If several configurations could be meant, ask the user which one first. The user types the new key on the card; you never see it. The app first tests the new key on each model of that configuration you use (or its first model) and replaces the old key only if all pass; otherwise the new key is not saved and the hidden note says why. End the turn with one short line.
  - open_settings puts a card that opens the model settings page. Only for what the chat cannot do: a configuration that uses several keys, or models that run on the phone. End the turn with one short line.
  - request_use switches the model you use for main (your replies), assistant (titles, memory, background work) or multimodal (reading pictures), and can set the assistant's thinking strength. multimodal requires picture support enabled for that model in its configuration. A model that is not in the configuration yet but is in discover's list is added to it. It puts an approval card in the chat; end the turn with one short line.
  - Never ask for the key in chat. If the user sends one anyway, do not repeat it or store it anywhere.
  - Sign-in accounts (OPENAI_CODEX, GROK_ACCOUNT, CLAUDE_ACCOUNT, GOOGLE_ANTIGRAVITY, COMMAND_CODE) use no key. list's accounts shows which are signed in. Not signed in: put a sign-in card first with orbit_vault request_chatgpt_login (ChatGPT/Codex) or request_account_login (Claude, Grok, Antigravity, Command Code). Signed in: add it yourself with request_create provider=<that id> (leave endpoint and models out); the user picks the models from the account's list on the card. discover with that provider also lists the account's models.
  - Models that run on the phone (MNN, LLAMA_CPP) are set up in the app's model settings; use open_settings for those.
  - 模型名别靠猜：已有配置用 discover 查；新建时让用户在卡片里填密钥（账号登录型不用填）后从服务商列表里勾。用户在卡片里填密钥，你看不到。
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
                require(name.isNotEmpty()) { "Give config: a configuration's select from list" }
                val all = configs.getAllConfigSummaries()
                val picks = selectors(all)
                all.firstOrNull { picks[it.id] == name }?.let { return it }
                require(all.none { it.name == name }) {
                    "Several configurations are named \"$name\"; pass config exactly as list's select shows it, e.g. \"$name#1\""
                }
                error("No configuration \"$name\"; use a select exactly as list shows it")
            }
            val result: JSONObject = when (arg("action")) {
                "list" -> {
                    val mine = OrbitModelPreferences(context).current(orbit.id)
                    val list = JSONArray()
                    val summaries = configs.getAllConfigSummaries()
                    val picks = selectors(summaries)
                    summaries.forEach { s ->
                        val full = configs.getModelConfig(s.id)
                        list.put(JSONObject().put("select", picks[s.id]).put("name", s.name).put("provider", s.apiProviderTypeId)
                            .put("endpoint", full?.apiEndpoint.orEmpty())
                            .put("models", JSONArray(getModelList(s.modelName)))
                            .put("has_key", !full?.apiKey.isNullOrBlank()))
                    }
                    fun label(ref: com.ai.assistance.operit.data.preferences.OrbitModelRef?): String =
                        ref?.let { r -> picks[r.configId]?.let { "$it / ${r.modelName}" } }.orEmpty()
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
                        signedInFor(context, provider)
                        require(provider in OrbitApprovalActions.SIGN_IN_PROVIDERS || !OrbitApprovalActions.requiresKey(provider, endpoint)) {
                            "This provider needs an API key. Use request_create (models can be left out): the user types the key " +
                                "on the card and picks from the provider's list there. For a configuration that already has a key, pass config."
                        }
                        "${provider.name} $endpoint" to if (provider in OrbitApprovalActions.SIGN_IN_PROVIDERS) {
                            fetchAccount(provider) { OrbitApprovalActions.fetchModels(context, provider, endpoint, "") }
                        } else {
                            fetch(context) { OrbitApprovalActions.fetchModels(context, provider, endpoint, "") }
                        }
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
                    if (role == OrbitModelSwitch.ROLE_MULTIMODAL) {
                        val full = checkNotNull(configs.getModelConfig(config.id)) { "Configuration \"${config.name}\" could not be read" }
                        require(OrbitModelSwitch.supportsPictures(full, model)) {
                            "\"${config.name} / $model\" is not marked as supporting pictures. No approval card was created; " +
                                "your picture model is unchanged. Choose a model with picture support enabled. If the provider " +
                                "confirms this model supports pictures, use action=open_settings so the user can enable it, then request_use again."
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
                    signedInFor(context, provider)
                    val signIn = provider in OrbitApprovalActions.SIGN_IN_PROVIDERS
                    // An account's endpoint is fixed by the app; it is not Orbit's to change.
                    val endpoint = if (signIn) endpoint(provider, "") else endpoint(provider, arg("endpoint"))
                    val models = OrbitApprovalActions.splitModels(arg("models"))
                    require(models.size <= MAX_MODELS && models.all { it.length <= 120 }) {
                        "models may suggest at most $MAX_MODELS model names, or be left out"
                    }
                    val useAs = arg("use_as").lowercase().ifEmpty { OrbitApprovalActions.USE_NONE }
                    require(useAs in setOf(OrbitApprovalActions.USE_NONE, OrbitApprovalActions.USE_MAIN, OrbitApprovalActions.USE_ASSISTANT)) {
                        "use_as must be none, main or assistant"
                    }
                    val needsKey = if (signIn) false else when (arg("needs_key").lowercase()) {
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
                "request_key" -> {
                    inChat()
                    val config = named(arg("config"))
                    val full = checkNotNull(configs.getModelConfig(config.id)) { "Configuration \"${config.name}\" could not be read" }
                    val provider = ApiProviderType.fromProviderTypeId(full.apiProviderTypeId) ?: full.apiProviderType
                    require(provider !in OrbitApprovalActions.SIGN_IN_PROVIDERS) {
                        "\"${config.name}\" uses a signed-in account, not a key. If it stopped working, put a sign-in card with " +
                            "${OrbitApprovalActions.signInWith(provider)} relogin=true"
                    }
                    require(provider !in OrbitApprovalActions.LOCAL_PROVIDERS) {
                        "\"${config.name}\" runs a model on the phone and has no key"
                    }
                    require(!full.useMultipleApiKeys) {
                        "\"${config.name}\" uses several keys; put a card with action=open_settings so the user changes them there"
                    }
                    require(getModelList(full.modelName).isNotEmpty()) {
                        "\"${config.name}\" has no models, so a new key cannot be tested on it; make a new configuration with action=request_create instead"
                    }
                    val select = selectors(configs.getAllConfigSummaries())[config.id] ?: config.name
                    val title = context.getString(com.ai.assistance.operit.R.string.orbit_approval_key_title, select)
                        .take(OrbitApprovals.MAX_TITLE)
                    val mine = OrbitModelPreferences(context).current(orbit.id)
                    val roles = listOfNotNull(
                        com.ai.assistance.operit.R.string.orbit_approval_key_role_main.takeIf { mine.main?.configId == config.id },
                        com.ai.assistance.operit.R.string.orbit_approval_key_role_assistant.takeIf { mine.assistant?.configId == config.id },
                        com.ai.assistance.operit.R.string.orbit_approval_key_role_multimodal.takeIf { mine.multimodal?.configId == config.id },
                    ).map { context.getString(it) }
                    val detail = OrbitApprovalActions.keyDetail(context, select, provider.name, full.apiEndpoint,
                        getModelList(full.modelName), roles, arg("reason").take(300))
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(), title, detail,
                        OrbitApprovalActions.keyAction(config.id, select))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat; the user types the new API key on it. End this turn now " +
                            "with one short line. You get a hidden note saying whether the new key passed a short test and replaced the old one.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                "open_settings" -> {
                    inChat()
                    JSONObject().put("card", "placed")
                        .put("note", "A card that opens the model settings page is now in the chat. End this turn with one short line; " +
                            "the user changes the key there. Check with action=list afterwards if needed.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.openPage(orbit.id, OrbitChatCards.PAGE_MODEL_CONFIG))
                }
                else -> error("action must be list, discover, request_create, request_key, request_use or open_settings")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    /**
     * How Orbit names each configuration (by id) when it passes it back: its name when no other one has
     * it, otherwise name#1, name#2 … in list order, skipping any pick already taken (even a real name
     * like "A#2"), so every pick is different and a repeated name never silently picks the first one.
     */
    private fun selectors(all: List<ModelConfigSummary>): Map<String, String> {
        val counts = all.groupingBy { it.name }.eachCount()
        val picks = LinkedHashMap<String, String>()
        all.filter { counts[it.name] == 1 }.forEach { picks[it.id] = it.name }
        val used = picks.values.toMutableSet()
        all.filter { counts[it.name] != 1 }.groupBy { it.name }.forEach { (name, same) ->
            var n = 1
            same.forEach { config ->
                while ("$name#$n" in used) n++
                picks[config.id] = "$name#$n"
                used += "$name#$n"
            }
        }
        return picks
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
        ApiProviderType.fromProviderTypeId(id)?.also {
            require(it !in OrbitApprovalActions.LOCAL_PROVIDERS) {
                "${it.name} runs a model on the phone and is set up in the app's model settings; put a card with action=open_settings"
            }
        } ?: error("provider must be one of list's providers")

    /** A sign-in account must be signed in before Orbit can add it or list its models. */
    private fun signedInFor(context: Context, provider: ApiProviderType) {
        if (provider !in OrbitApprovalActions.SIGN_IN_PROVIDERS) return
        require(OrbitApprovalActions.signedIn(context, provider)) {
            "The user is not signed in to the ${provider.name} account. Put a sign-in card with " +
                "${OrbitApprovalActions.signInWith(provider)} first; after they sign in you get a note, then call request_create again."
        }
    }

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
        require(provider !in OrbitApprovalActions.LOCAL_PROVIDERS) {
            "\"${full.name}\" runs a model on the phone; its models are set up in the app's model settings"
        }
        if (provider in OrbitApprovalActions.SIGN_IN_PROVIDERS) {
            signedInFor(context, provider)
            return fetchAccount(provider) { OrbitApprovalActions.fetchModels(context, provider, full.apiEndpoint, "") }
        }
        val key = full.apiKey.ifBlank { full.apiKeyPool.firstOrNull { it.isEnabled }?.key.orEmpty() }
        return fetch(context, key) {
            OrbitApprovalActions.fetchModels(context, provider, full.apiEndpoint, key,
                com.ai.assistance.operit.api.chat.llmprovider.parseProviderCustomHeaders(full.customHeaders))
        }
    }

    /** A sign-in account's model list failed: often an expired sign-in, fixed by signing in again from a card. */
    private suspend fun fetchAccount(provider: ApiProviderType, block: suspend () -> List<String>): List<String> =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = (e.message ?: e.javaClass.simpleName).take(200)
            error("The ${provider.name} account did not return its model list ($why). If it is a sign-in error (401, expired, " +
                "sign in again), put a sign-in card with ${OrbitApprovalActions.signInWith(provider)} relogin=true; otherwise try again later.")
        }

    /** A failed lookup says why without ever echoing the key. */
    private suspend fun fetch(context: Context, key: String = "", block: suspend () -> List<String>): List<String> =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = (e.message ?: e.javaClass.simpleName).let { if (key.length >= 6) it.replace(key, "***") else it }.take(200)
            error("The provider did not return its model list ($why). Check the endpoint; if it is a key problem (401, invalid key), " +
                "make a new configuration with the new key using action=request_create, or replace this one's key with action=request_key.")
        }
}
