package com.ai.assistance.operit.core.tools

import android.content.Context
import android.content.res.Configuration
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserProfiles
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.AppLaunchMode
import com.ai.assistance.operit.data.preferences.LaunchModePreferences
import com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences
import com.ai.assistance.operit.data.preferences.OrbitAvatarSize
import com.ai.assistance.operit.data.preferences.OrbitChatPalette
import com.ai.assistance.operit.data.preferences.OrbitColorMode
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitCompanion
import com.ai.assistance.operit.features.orbit.OrbitContextSettings
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitQuota
import com.ai.assistance.operit.util.LocaleUtils
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** An allowlist of ordinary settings. Every write goes through a user-approved app action. */
internal object OrbitSettingsTools {
    const val NAME = "orbit_settings"
    private const val MAX_CHANGES_JSON = 8_000
    /** How many items an already approved card may carry; above today's settings to allow removed ones. */
    private const val MAX_APPROVED_ITEMS = 32
    private val intervals = listOf(5L, 10L, 20L, 50L)
    private val delays = listOf(1L, 5L, 10L, 30L, 60L)

    private enum class Setting(
        val key: String,
        val label: Int,
        val shared: Boolean = false,
        val choices: List<String> = emptyList(),
    ) {
        PALETTE("chat_palette", R.string.orbit_settings_palette, choices = OrbitChatPalette.entries.map { it.name.lowercase(Locale.ROOT) }),
        AVATAR("avatar_size", R.string.orbit_settings_avatar, choices = OrbitAvatarSize.entries.map { it.name.lowercase(Locale.ROOT) }),
        COLOR("color_mode", R.string.orbit_settings_color, choices = OrbitColorMode.entries.map { it.name.lowercase(Locale.ROOT) }),
        WEEKLY("weekly_limit", R.string.orbit_settings_weekly, true),
        EXTRA("extra_limit", R.string.orbit_settings_extra, true),
        EXTRA_WEEKLY("extra_weekly_reset", R.string.orbit_settings_extra_weekly, true),
        REFILL("refill_extra", R.string.orbit_settings_refill, true),
        LOGIN("browser_shares_main_login", R.string.orbit_settings_login),
        MEMORY("memory_review_turns", R.string.orbit_settings_memory),
        SKILLS("skill_review_iterations", R.string.orbit_settings_skills),
        DELAY("learning_delay_minutes", R.string.orbit_settings_delay),
        DEVELOPER("developer_mode", R.string.orbit_settings_developer, true),
        LAUNCH("launch_mode", R.string.orbit_settings_launch, true, listOf("orbit", "standard")),
        NOTIFY("message_notifications", R.string.orbit_settings_message_notifications),
        CONTEXT_WINDOW("context_window_k", R.string.orbit_settings_context_window),
        // Kept last: all other changes finish before the current companion is stopped.
        PAUSE("pause", R.string.orbit_settings_pause, true),
    }

    val prompt = ToolPrompt(
        name = NAME,
        description = "Read Orbit and app settings, ask to change several with one approval card, or put a data export card in the chat. " +
            "查看设置，申请修改外观、额度、共用登录、学习频率、开发者模式、启动模式、消息通知、上下文预算或暂停 Orbit，用户批准后由 App 执行；也能放一张数据导出卡片。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "get / request_change / request_export / open_page", required = true),
            ToolParameterSchema("changes", "string",
                "request_change: JSON object of setting names and new values, e.g. {\"color_mode\":\"dark\",\"weekly_limit\":1000000000}. Read get for keys/options first.", false),
            ToolParameterSchema("page", description = "open_page: tool_access, tool_permissions, models or notifications", required = false),
            ToolParameterSchema("title", description = "request_change: optional short title in the user's language", required = false),
            ToolParameterSchema("reason", description = "request_change: explain why these changes help, in the user's language", required = false),
        ),
        details = """
  - get returns current values, allowed values/ranges, units and shared_by_all_orbits. Quotas, developer mode, launch mode and pausing are shared; appearance, browser login choice, learning cadence, message notifications and the context budget belong to this Orbit.
  - context_window_k is the context budget for the main chat and every side chat together, in K tokens measured against your main model. Automatic compaction is always on and the app picks where it starts (get returns it); neither can be changed. For DeepSeek Flash models 400K is recommended.
  - message_notifications=true lets your messages also appear as their own phone notification while the app is closed; false keeps them in the chat only. It is separate from the system notification permission, which orbit_permission_card notify asks for.
  - request_change validates every item, including unknown keys, invalid values and unchanged values; correct all reported errors and ask again. Token limits use raw tokens, never millions or currency. Only get's allowlisted settings can be changed.
  - The approval card lists old → new values. End this turn with one short line after the card appears. Only after approval does the app recheck and apply each item, then send you a hidden result. Read each result; an outdated or failed item may be skipped.
  - browser_shares_main_login=true uses the main app's browser account environment; false returns to this Orbit's separate environment. This tool never reads or returns cookies. Unsupported WebViews cannot switch.
  - pause accepts only true. Approval stops the current Orbit and its background work; only the user can resume it manually. An old approval cannot pause another Orbit.
  - refill_extra accepts only true and clears extra allowance usage, without changing usage history or the weekly allowance. There is no weekly-usage reset.
  - request_export puts a data export card in the chat. The user taps it, confirms, and the app packs this Orbit's files, memory, tasks, goals and chats into one zip and opens the share sheet. You never get the zip or its path. End this turn with one short line after the card appears.
  - open_page puts a card in the chat that opens one Orbit settings page; the user changes it there themselves: tool_access (full access mode), tool_permissions (ask / allow / deny for each tool), models (which model you use for each job, thinking strength), notifications (notification switch, quiet hours at night, how often reports are sent). End this turn with one short line after the card appears.
  - 工具权限和全访问模式你不能改，用户想改时用 open_page 放卡片带他过去，由他自己决定。语音设置用 orbit_voice 的 request_settings，技能开关用 orbit_skills 的 request_visibility，账号登录用 orbit_vault 的 request_account_login。设置必须等卡片批准，不要自行编辑偏好文件。
""",
    )

    private data class Snapshot(
        val settings: OrbitSettings,
        val orbit: OrbitProfile,
        val quota: OrbitQuota.State,
        val memory: MemorySearchSettingsPreferences,
        val launchMode: AppLaunchMode,
        /** Null when the main model is not set; [contextError] then says why. */
        val context: OrbitContextSettings.State?,
        val contextError: String?,
    ) {
        fun value(setting: Setting): Any = when (setting) {
            Setting.PALETTE -> orbit.appearance.palette.name.lowercase(Locale.ROOT)
            Setting.AVATAR -> orbit.appearance.avatarSize.name.lowercase(Locale.ROOT)
            Setting.COLOR -> orbit.appearance.mode.name.lowercase(Locale.ROOT)
            Setting.WEEKLY -> quota.weeklyLimit
            Setting.EXTRA -> quota.extraLimit
            Setting.EXTRA_WEEKLY -> quota.extraWeekly
            Setting.REFILL -> quota.extraUsed
            Setting.LOGIN -> orbit.browserSharesMainLogin || !BrowserProfiles.isSupported()
            Setting.MEMORY -> memory.memoryReviewInterval().toLong()
            Setting.SKILLS -> memory.skillReviewInterval().toLong()
            Setting.DELAY -> memory.learningDelayMinutes().toLong()
            Setting.DEVELOPER -> settings.developerMode
            Setting.LAUNCH -> launchMode.name.lowercase(Locale.ROOT)
            Setting.NOTIFY -> orbit.messageNotifications
            Setting.CONTEXT_WINDOW -> context?.policy?.windowK?.let(::kValue) ?: JSONObject.NULL
            Setting.PAUSE -> settings.activeOrbitId == null
        }
    }

    /** A K size exactly as the user set it in the app: whole numbers stay whole, 100.4 stays 100.4. */
    private fun kValue(value: Float): Number {
        val exact = java.math.BigDecimal(value.toString()).stripTrailingZeros()
        return if (exact.scale() <= 0) exact.toLong() else exact.toDouble()
    }

    private suspend fun snapshot(context: Context, orbitId: String): Snapshot {
        val settings = OrbitPreferences.getInstance(context).current()
        val orbit = settings.orbits.firstOrNull { it.id == orbitId && !it.deleting }
            ?: error(context.getString(R.string.orbit_settings_missing))
        val spaceId = OrbitMemoryScope.ensureInitialized(context, orbit)
        var contextError: String? = null
        val contextState = try {
            OrbitContextSettings.state(context, orbit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            contextError = e.message ?: context.getString(R.string.orbit_settings_failed)
            null
        }
        return Snapshot(settings, orbit, OrbitQuota.state(context),
            MemorySearchSettingsPreferences(context, spaceId), LaunchModePreferences.getInstance(context).mode,
            contextState, contextError)
    }

    private fun textContext(context: Context): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale(if (LocaleUtils.usesEnglishPrompt(context)) "en" else "zh"))
        })

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        val text = textContext(context)
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(key: String) = args[key]?.trim().orEmpty()
            val state = snapshot(context, orbit.id)
            val result = when (arg("action")) {
                "get" -> describe(text, state)
                "request_export" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller)?.id == orbit.id) {
                        "The export card only works in a chat with the user"
                    }
                    JSONObject().put("card_sent", true)
                        .put("note", "A data export card is now in the chat. The user taps it to make the zip and share it; " +
                            "you are not told whether they did. End this turn now with one short line.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.dataExport(orbit.id))
                }
                "open_page" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller)?.id == orbit.id) {
                        "Cards only work in a chat with the user"
                    }
                    val page = when (arg("page").lowercase(Locale.ROOT)) {
                        "tool_access" -> OrbitChatCards.PAGE_TOOL_ACCESS
                        "tool_permissions" -> {
                            require(state.settings.developerMode) {
                                "The per-tool permission page only shows in developer mode, which is off. Use page=tool_access, " +
                                    "or ask with request_change {\"developer_mode\":true} first if the user wants per-tool choices."
                            }
                            OrbitChatCards.PAGE_TOOL_PERMISSIONS
                        }
                        "models" -> OrbitChatCards.PAGE_ORBIT_MODELS
                        "notifications" -> OrbitChatCards.PAGE_NOTIFICATIONS
                        else -> error("page must be tool_access, tool_permissions, models or notifications")
                    }
                    JSONObject().put("card", "placed")
                        .put("note", "A card that opens this settings page is now in the chat. The user changes it there; " +
                            "you are not told what they chose (read get or orbit_models list afterwards if needed). End this turn now with one short line.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.openPage(orbit.id, page))
                }
                "request_change" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller)?.id == orbit.id) {
                        "Approval cards only work in a chat with the user"
                    }
                    val raw = arg("changes")
                    require(raw.isNotEmpty() && raw.length <= MAX_CHANGES_JSON) {
                        "changes must be a JSON object, 1 to $MAX_CHANGES_JSON characters"
                    }
                    val changes = JSONObject(raw)
                    require(changes.length() in 1..Setting.entries.size) { "Give at least one setting, and no more than ${Setting.entries.size}" }
                    val normalized = JSONObject()
                    val expected = JSONObject()
                    val errors = mutableListOf<String>()
                    changes.keys().forEach { key ->
                        val setting = Setting.entries.firstOrNull { it.key == key }
                        if (setting == null) {
                            errors += "$key: ${text.getString(R.string.orbit_settings_unknown)}"
                        } else {
                            try {
                                val value = validate(text, setting, changes.get(key), state)
                                require(!same(state.value(setting), value)) { text.getString(R.string.orbit_settings_unchanged) }
                                normalized.put(key, value)
                                expected.put(key, state.value(setting))
                            } catch (e: IllegalArgumentException) {
                                errors += "${text.getString(setting.label)} ($key): ${e.message}"
                            }
                        }
                    }
                    require(errors.isEmpty()) { errors.joinToString("\n") }
                    val detail = detail(text, normalized, state, arg("reason").take(300))
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) { "Too long for one approval card; request fewer changes" }
                    val action = JSONObject().put("type", OrbitApprovalActions.CHANGE_SETTINGS)
                        .put("changes", normalized).put("expected", expected).toString()
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(),
                        arg("title").ifEmpty { text.getString(R.string.orbit_settings_title) }.take(OrbitApprovals.MAX_TITLE),
                        detail, action)
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", text.getString(R.string.orbit_settings_requested))
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be get, request_change, request_export or open_page")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun describe(text: Context, state: Snapshot): JSONObject {
        val settings = Setting.entries.map { setting ->
            JSONObject().put("key", setting.key).put("name", text.getString(setting.label))
                .put("current", state.value(setting)).put("shared_by_all_orbits", setting.shared)
                .apply {
                    when (setting) {
                        Setting.WEEKLY, Setting.EXTRA -> {
                            put("min", if (setting == Setting.WEEKLY) OrbitQuota.MIN_WEEKLY else 0L)
                            put("max", OrbitQuota.MAX_LIMIT)
                            put("unit", "tokens")
                        }
                        Setting.MEMORY, Setting.SKILLS -> {
                            put("options", JSONArray(intervals))
                            put("unit", if (setting == Setting.MEMORY) "turns" else "iterations")
                        }
                        Setting.DELAY -> { put("options", JSONArray(delays)); put("unit", "minutes") }
                        Setting.CONTEXT_WINDOW -> {
                            put("min", 1L)
                            state.context?.let { put("max", capacityK(it)) }
                            put("unit", "K tokens")
                        }
                        Setting.REFILL, Setting.PAUSE -> put("options", JSONArray(listOf(true)))
                        Setting.EXTRA_WEEKLY, Setting.LOGIN, Setting.DEVELOPER, Setting.NOTIFY ->
                            put("options", JSONArray(listOf(false, true)))
                        else -> put("options", JSONArray(setting.choices))
                    }
                    if (setting == Setting.LOGIN) {
                        put("can_change", BrowserProfiles.isSupported())
                        put("note", text.getString(if (BrowserProfiles.isSupported())
                            R.string.orbit_settings_login_note else R.string.orbit_settings_login_unsupported))
                    }
                    if (setting == Setting.PAUSE) {
                        put("can_change", state.settings.activeOrbitId == state.orbit.id)
                        put("note", text.getString(R.string.orbit_settings_pause_note))
                    }
                    if (setting == Setting.REFILL) {
                        put("current_means", "extra_used_tokens")
                        put("note", text.getString(R.string.orbit_settings_refill_note))
                    }
                    if (setting == Setting.CONTEXT_WINDOW) {
                        put("can_change", state.context != null)
                        state.contextError?.let { put("note", it) }
                        state.context?.let {
                            put("main_model_max_k", capacityK(it))
                            put("effective_compact_at_k", Math.round(it.compactAtK).toLong())
                            if (OrbitContextSettings.isDeepSeekFlash(it.modelName)) {
                                put("recommended_k", OrbitContextSettings.DEEPSEEK_FLASH_BUDGET_K)
                            }
                        }
                    }
                }
        }
        return JSONObject().put("settings", JSONArray(settings))
            .put("note", text.getString(R.string.orbit_settings_get_note))
    }

    private fun capacityK(state: OrbitContextSettings.State): Long = kotlin.math.floor(state.capacityK).toLong().coerceAtLeast(1L)

    private fun integer(text: Context, raw: Any): Long =
        (raw as? Number)?.toString()?.toLongOrNull()
            ?: throw IllegalArgumentException(text.getString(R.string.orbit_settings_integer))

    private fun validate(text: Context, setting: Setting, raw: Any, state: Snapshot): Any {
        if (setting.choices.isNotEmpty()) {
            val value = (raw as? String)?.lowercase(Locale.ROOT)
            require(value in setting.choices) {
                text.getString(R.string.orbit_settings_options, setting.choices.joinToString())
            }
            return checkNotNull(value)
        }
        if (setting == Setting.CONTEXT_WINDOW) {
            val current = state.context ?: throw IllegalArgumentException(state.contextError)
            val value = integer(text, raw)
            val max = capacityK(current)
            require(value in 1L..max) { text.getString(R.string.orbit_settings_range, "1", max.toString()) }
            return value
        }
        return when (setting) {
            Setting.WEEKLY, Setting.EXTRA -> {
                val value = integer(text, raw)
                val min = if (setting == Setting.WEEKLY) OrbitQuota.MIN_WEEKLY else 0L
                require(value in min..OrbitQuota.MAX_LIMIT) {
                    text.getString(R.string.orbit_settings_range, min.toString(), OrbitQuota.MAX_LIMIT.toString())
                }
                value
            }
            Setting.MEMORY, Setting.SKILLS, Setting.DELAY -> {
                val value = integer(text, raw)
                val options = if (setting == Setting.DELAY) delays else intervals
                require(value in options) { text.getString(R.string.orbit_settings_options, options.joinToString()) }
                value
            }
            else -> {
                require(raw is Boolean) { text.getString(R.string.orbit_settings_boolean) }
                when (setting) {
                    Setting.REFILL -> {
                        require(raw) { text.getString(R.string.orbit_settings_true_only) }
                        require(state.quota.extraUsed > 0L) { text.getString(R.string.orbit_settings_refill_unchanged) }
                    }
                    Setting.PAUSE -> {
                        require(raw) { text.getString(R.string.orbit_settings_pause_only) }
                        require(state.settings.activeOrbitId == state.orbit.id) {
                            text.getString(R.string.orbit_settings_not_active)
                        }
                    }
                    Setting.LOGIN -> require(BrowserProfiles.isSupported()) {
                        text.getString(R.string.orbit_settings_login_unsupported)
                    }
                    else -> Unit
                }
                raw
            }
        }
    }

    /** Whole numbers compare exactly as Long; a fraction such as 100.4K is compared as it is. */
    private fun same(a: Any, b: Any): Boolean = when {
        a is Number && b is Number ->
            if (a is Double || a is Float || b is Double || b is Float) a.toDouble() == b.toDouble()
            else a.toLong() == b.toLong()
        else -> a == b
    }

    private fun display(text: Context, setting: Setting, value: Any): String {
        val label = when (setting) {
            Setting.PALETTE -> when (value) {
                "avatar" -> R.string.orbit_palette_avatar
                "sky" -> R.string.orbit_palette_sky
                "ink" -> R.string.orbit_palette_ink
                "sand" -> R.string.orbit_palette_sand
                "blue" -> R.string.orbit_palette_blue
                "lavender" -> R.string.orbit_palette_lavender
                "pink" -> R.string.orbit_palette_pink
                "peach" -> R.string.orbit_palette_peach
                else -> R.string.orbit_palette_green
            }
            Setting.AVATAR -> when (value) {
                "small" -> R.string.orbit_avatar_small
                "medium" -> R.string.orbit_avatar_medium
                "large" -> R.string.orbit_avatar_large
                "extra_large" -> R.string.orbit_avatar_extra_large
                else -> R.string.orbit_avatar_hidden
            }
            Setting.COLOR -> when (value) {
                "light" -> R.string.orbit_mode_light
                "dark" -> R.string.orbit_mode_dark
                else -> R.string.orbit_mode_system
            }
            Setting.LAUNCH -> if (value == "orbit") R.string.nav_orbit else R.string.launch_mode_standard
            Setting.LOGIN -> if (value == true) R.string.orbit_settings_main_login else R.string.orbit_settings_own_login
            Setting.PAUSE -> if (value == true) R.string.orbit_settings_paused else R.string.orbit_settings_running
            else -> null
        }
        if (label != null) return text.getString(label)
        return when (setting) {
            Setting.WEEKLY, Setting.EXTRA -> text.getString(R.string.orbit_settings_tokens, OrbitQuota.amount(text, (value as Number).toLong()))
            Setting.REFILL -> if (value == true) text.getString(R.string.orbit_settings_refilled)
                else text.getString(R.string.orbit_settings_extra_used, OrbitQuota.amount(text, (value as Number).toLong()))
            Setting.MEMORY -> text.getString(R.string.orbit_settings_turns, value.toString())
            Setting.SKILLS -> text.getString(R.string.orbit_settings_iterations, value.toString())
            Setting.DELAY -> text.getString(R.string.orbit_settings_minutes, value.toString())
            Setting.CONTEXT_WINDOW -> "${value}K"
            else -> text.getString(if (value == true) R.string.orbit_settings_on else R.string.orbit_settings_off)
        }
    }

    private fun detail(text: Context, changes: JSONObject, state: Snapshot, reason: String): String =
        buildString {
            if (reason.isNotBlank()) append(reason).append("\n\n")
            Setting.entries.filter { changes.has(it.key) }.forEach { setting ->
                append(text.getString(R.string.orbit_settings_change_line,
                    text.getString(setting.label), display(text, setting, state.value(setting)),
                    display(text, setting, changes.get(setting.key)),
                    if (setting.shared) text.getString(R.string.orbit_settings_shared) else "")).append('\n')
                if (setting == Setting.LOGIN) append(text.getString(R.string.orbit_settings_login_note)).append('\n')
                if (setting == Setting.REFILL) append(text.getString(R.string.orbit_settings_refill_note)).append('\n')
                if (setting == Setting.PAUSE) append(text.getString(R.string.orbit_settings_pause_note)).append('\n')
            }
            if (changes.has(Setting.CONTEXT_WINDOW.key)) {
                append(text.getString(R.string.orbit_settings_context_scope)).append('\n')
            }
        }.trim()

    /** Called only by the app after the user approves; the model cannot invoke this entry point. */
    suspend fun applyApproved(context: Context, orbit: OrbitProfile, action: JSONObject): String {
        val text = textContext(context)
        val changes = action.getJSONObject("changes")
        val expected = action.getJSONObject("expected")
        // A card approved before a setting was removed may list more items than exist now; those
        // are skipped one by one below instead of failing the whole card.
        require(changes.length() in 1..MAX_APPROVED_ITEMS && changes.toString().length <= MAX_CHANGES_JSON)
        val results = mutableListOf<String>()
        val unknown = changes.keys().asSequence().filter { key -> Setting.entries.none { it.key == key } }.toList()
        unknown.forEach { key ->
            results += text.getString(R.string.orbit_settings_skipped, key, text.getString(R.string.orbit_settings_unknown))
        }
        for (setting in Setting.entries.filter { changes.has(it.key) }) {
            try {
                val state = snapshot(context, orbit.id)
                val value = validate(text, setting, changes.get(setting.key), state)
                val current = state.value(setting)
                require(!same(current, value)) { text.getString(R.string.orbit_settings_unchanged) }
                // Extra usage can grow while the requesting reply finishes. Approval is for a
                // refill at execution time; treating that growth as a stale preference prevents
                // refilling precisely when the conversation is using the extra allowance.
                require(expected.has(setting.key) && (setting == Setting.REFILL ||
                    same(current, expected.get(setting.key)))) {
                    text.getString(R.string.orbit_settings_stale)
                }
                write(context, state, setting, value)
                results += text.getString(R.string.orbit_settings_applied,
                    text.getString(setting.label), display(text, setting, value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = if (setting == Setting.PAUSE && e is java.util.ConcurrentModificationException)
                    text.getString(R.string.orbit_settings_not_active)
                else e.message ?: text.getString(R.string.orbit_settings_failed)
                results += text.getString(R.string.orbit_settings_skipped,
                    text.getString(setting.label), if (reason.length <= 60) reason else reason.take(59) + "…")
            }
        }
        return results.joinToString("\n")
    }

    private suspend fun write(context: Context, state: Snapshot, setting: Setting, value: Any) {
        val preferences = OrbitPreferences.getInstance(context)
        when (setting) {
            Setting.PALETTE -> preferences.updateAppearance(state.orbit.id) {
                it.copy(palette = OrbitChatPalette.valueOf((value as String).uppercase(Locale.ROOT)))
            }
            Setting.AVATAR -> preferences.updateAppearance(state.orbit.id) {
                it.copy(avatarSize = OrbitAvatarSize.valueOf((value as String).uppercase(Locale.ROOT)))
            }
            Setting.COLOR -> preferences.updateAppearance(state.orbit.id) {
                it.copy(mode = OrbitColorMode.valueOf((value as String).uppercase(Locale.ROOT)))
            }
            // Only the approved field is written; the other two are read inside the same edit.
            Setting.WEEKLY -> OrbitQuota.setLimits(context, weekly = value as Long)
            Setting.EXTRA -> OrbitQuota.setLimits(context, extra = value as Long)
            Setting.EXTRA_WEEKLY -> OrbitQuota.setLimits(context, extraWeekly = value as Boolean)
            Setting.REFILL -> OrbitQuota.refillExtra(context)
            Setting.LOGIN -> preferences.setBrowserSharesMainLogin(state.orbit.id, value as Boolean)
            Setting.MEMORY -> state.memory.setMemoryReviewInterval((value as Long).toInt())
            Setting.SKILLS -> state.memory.setSkillReviewInterval((value as Long).toInt())
            Setting.DELAY -> state.memory.setLearningDelayMinutes((value as Long).toInt())
            Setting.DEVELOPER -> preferences.setDeveloperMode(value as Boolean)
            Setting.LAUNCH -> LaunchModePreferences.getInstance(context).setMode(
                AppLaunchMode.valueOf((value as String).uppercase(Locale.ROOT)))
            Setting.NOTIFY -> preferences.setMessageNotifications(state.orbit.id, value as Boolean)
            Setting.CONTEXT_WINDOW -> OrbitContextSettings.setBudget(context, state.orbit.id, (value as Long).toFloat())
            Setting.PAUSE -> OrbitCompanion.pause(context, state.orbit.id)
        }
    }
}
