package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.CodexAuthPreferences
import com.ai.assistance.operit.data.preferences.EnvPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelRef
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.LocaleUtils
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an approval asks the app to do once the user says yes ([OrbitApprovals.Approval.action]):
 * import tool packages into Orbit, or add a model configuration. The app does it, so Orbit never
 * holds the switch or the key; Orbit hears what happened in the answer's hidden note.
 */
internal object OrbitApprovalActions {
    const val IMPORT_PACKAGES = "import_packages"
    const val TOGGLE_PACKAGES = "toggle_packages"
    const val CREATE_MODEL = "create_model"
    const val USE_MODEL = "use_model"
    const val INSTALL_COMPONENTS = "install_components"
    const val CHANGE_SETTINGS = "change_settings"

    /** At most this many packages, and this many files, in one request. */
    const val MAX_ITEMS = 20
    /** Package files are read whole while importing, so a huge one is turned away first. */
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    val PACKAGE_EXTENSIONS = setOf("js", "ts", "hjson", "toolpkg")
    const val USE_NONE = "none"
    const val USE_MAIN = "main"
    const val USE_ASSISTANT = "assistant"

    /** Signing in to these is done on the provider's account page, not with an API key. */
    val ACCOUNT_PROVIDERS = setOf(ApiProviderType.OPENAI_CODEX, ApiProviderType.GROK_ACCOUNT,
        ApiProviderType.COMMAND_CODE, ApiProviderType.GOOGLE_ANTIGRAVITY, ApiProviderType.CLAUDE_ACCOUNT,
        ApiProviderType.MNN, ApiProviderType.LLAMA_CPP)

    /** Packages that use the app's ChatGPT login instead of a key. */
    private val CHATGPT_PACKAGES = setOf("codex_draw")

    fun type(action: String): String = runCatching { JSONObject(action).optString("type") }.getOrDefault("")

    /** The approval needs the user to type an API key before it can be approved. */
    fun needsSecret(action: String): Boolean =
        runCatching { JSONObject(action).let { it.optString("type") == CREATE_MODEL && it.optBoolean("needs_key") } }
            .getOrDefault(false)

    /** The user picks the models on the sheet, from the provider's own list, before approving. */
    fun picksModels(action: String): Boolean = type(action) == CREATE_MODEL

    /** The model request inside [action], for the sheet; null for any other action. */
    fun modelRequestOf(action: String): ModelRequest? =
        runCatching { JSONObject(action).takeIf { it.optString("type") == CREATE_MODEL }?.let(::modelRequest) }.getOrNull()

    /**
     * The provider's model names, asked with [key] (empty for a server without one). Only the app
     * holds the key; it never reaches Orbit.
     */
    suspend fun fetchModels(context: Context, provider: ApiProviderType, endpoint: String, key: String,
        customHeaders: Map<String, String> = emptyMap()): List<String> =
        com.ai.assistance.operit.api.chat.llmprovider.ModelListFetcher
            .getModelsList(context, key.trim(), endpoint, provider, customHeaders = customHeaders)
            .getOrThrow().map { it.id.trim() }.filter { it.isNotEmpty() }.distinct()

    fun packageManager(context: Context): PackageManager =
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))

    /**
     * The package files [paths] stand for: a file as it is, a folder as the package files right in
     * it. Throws with the reason for a path that cannot be used.
     */
    fun packageFiles(paths: List<String>): List<File> {
        val files = LinkedHashSet<File>()
        paths.forEach { raw ->
            val file = File(raw.trim()).let { runCatching { it.canonicalFile }.getOrDefault(it) }
            require(file.exists()) { "Not found: $raw" }
            require(file.canRead()) { "Cannot read: $raw" }
            if (file.isDirectory) {
                val inside = file.listFiles()?.filter { it.isFile && it.extension.lowercase() in PACKAGE_EXTENSIONS }
                    ?.sortedBy { it.name.lowercase() }.orEmpty()
                require(inside.isNotEmpty()) { "No package files (.js, .ts, .hjson, .toolpkg) right inside $raw" }
                files += inside
            } else {
                require(file.extension.lowercase() in PACKAGE_EXTENSIONS) {
                    "$raw is not a package file; use .js, .ts, .hjson or .toolpkg"
                }
                files += file
            }
            require(files.size <= MAX_ITEMS) { "More than $MAX_ITEMS package files; ask for fewer at a time" }
        }
        // Imported files are kept by file name, so two with the same name would overwrite each other.
        val clash = files.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        require(clash.isEmpty()) { "Files with the same name: ${clash.joinToString()}; ask for them in separate requests" }
        val big = files.filter { it.length() > MAX_FILE_BYTES }
        require(big.isEmpty()) {
            "Too large to import (over ${MAX_FILE_BYTES / 1024 / 1024} MB): ${big.joinToString { it.name }}"
        }
        require(files.sumOf { it.length() } <= MAX_TOTAL_BYTES) {
            "These files are over ${MAX_TOTAL_BYTES / 1024 / 1024} MB together; ask for fewer at a time"
        }
        return files.toList()
    }

    /**
     * What [file] takes once read: its size, or for a .toolpkg (a zip) what it really unpacks to,
     * counted by reading it. Stops counting past [limit]; a file it cannot read counts as too big.
     */
    private fun loadedSize(file: File, limit: Long): Long {
        if (!file.name.endsWith(".toolpkg", ignoreCase = true)) return file.length()
        return runCatching {
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (zip.nextEntry != null) {
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) return@runCatching total
                    }
                }
            }
            total
        }.getOrDefault(Long.MAX_VALUE)
    }

    fun importAction(packages: List<String>, files: List<File>): String = JSONObject()
        .put("type", IMPORT_PACKAGES)
        .put("packages", JSONArray(packages))
        .put("files", JSONArray(files.map { it.absolutePath }))
        .toString()

    /** The request as the user reads it on the card: exactly what will be imported. */
    fun importDetail(context: Context, packages: List<Pair<String, String>>, files: List<File>, reason: String): String =
        buildString {
            if (packages.isNotEmpty()) {
                append(context.getString(R.string.orbit_approval_import_from_app))
                packages.forEach { (name, title) ->
                    append("\n• ").append(if (title.isBlank() || title == name) name else "$title ($name)")
                }
            }
            if (files.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(context.getString(R.string.orbit_approval_import_from_files))
                files.forEach { append("\n• ").append(it.absolutePath) }
            }
            if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
        }

    fun toggleAction(on: List<String>, off: List<String>): String = JSONObject()
        .put("type", TOGGLE_PACKAGES)
        .put("on", JSONArray(on))
        .put("off", JSONArray(off))
        .toString()

    /** Includes dependencies that also need switching on, so the card covers every change. */
    fun toggleDetail(context: Context, on: List<Pair<String, String>>, off: List<Pair<String, String>>,
        reason: String): String = buildString {
        fun section(label: Int, packages: List<Pair<String, String>>) {
            if (packages.isEmpty()) return
            if (isNotEmpty()) append("\n\n")
            append(context.getString(label))
            packages.forEach { (name, title) ->
                append("\n• ").append(if (title.isBlank() || title == name) name else "$title ($name)")
            }
        }
        section(R.string.orbit_approval_toggle_on, on)
        section(R.string.orbit_approval_toggle_off, off)
        if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
    }

    data class ModelRequest(
        val name: String,
        val provider: ApiProviderType,
        val endpoint: String,
        val models: List<String>,
        val useAs: String,
        val needsKey: Boolean,
    )

    fun modelAction(m: ModelRequest): String = JSONObject()
        .put("type", CREATE_MODEL)
        .put("name", m.name)
        .put("provider", m.provider.name)
        .put("endpoint", m.endpoint)
        .put("models", JSONArray(m.models))
        .put("use_as", m.useAs)
        .put("needs_key", m.needsKey)
        .toString()

    private fun modelRequest(action: JSONObject): ModelRequest = ModelRequest(
        action.getString("name"),
        checkNotNull(ApiProviderType.fromProviderTypeId(action.getString("provider"))) { "Unknown provider" },
        action.getString("endpoint"),
        action.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        action.optString("use_as", USE_NONE),
        action.optBoolean("needs_key"),
    )

    fun modelDetail(context: Context, m: ModelRequest, reason: String): String = buildString {
        append(context.getString(R.string.orbit_approval_model_name, m.name))
        when (m.useAs) {
            USE_MAIN -> append("\n").append(context.getString(R.string.orbit_approval_model_use_main))
            USE_ASSISTANT -> append("\n").append(context.getString(R.string.orbit_approval_model_use_assistant))
        }
        append("\n").append(context.getString(R.string.orbit_approval_model_provider, m.provider.name))
        append("\n").append(context.getString(R.string.orbit_approval_model_endpoint, m.endpoint))
        append("\n").append(
            if (m.models.isEmpty()) context.getString(R.string.orbit_approval_model_models_pick)
            else context.getString(R.string.orbit_approval_model_models_suggested, m.models.joinToString(", "))
        )
        if (m.needsKey) append("\n").append(context.getString(R.string.orbit_approval_model_key))
        if (reason.isNotBlank()) append("\n\n").append(context.getString(R.string.orbit_approval_reason, reason.trim()))
    }

    /** At most this many models the user can pick for one configuration. */
    const val MAX_PICKED_MODELS = 50

    /**
     * Carries out an approved [approval]'s action and says what happened. [models] are the models
     * the user picked on the sheet for a new model configuration.
     */
    suspend fun run(context: Context, orbit: OrbitProfile, approval: OrbitApprovals.Approval, secret: String?,
        models: List<String>? = null): String {
        val action = JSONObject(approval.action)
        val english = LocaleUtils.usesEnglishPrompt(context)
        return when (action.optString("type")) {
            IMPORT_PACKAGES -> importPackages(context, orbit, action, english)
            TOGGLE_PACKAGES -> togglePackages(context, orbit, action, english)
            USE_MODEL -> OrbitModelSwitch.run(context, orbit, action, english)
            INSTALL_COMPONENTS -> OrbitComponents.start(context, orbit, approval.chatId, action, english)
            CHANGE_SETTINGS -> com.ai.assistance.operit.core.tools.OrbitSettingsTools.applyApproved(context, orbit, action)
            CREATE_MODEL -> {
                val asked = modelRequest(action)
                val picked = models.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                require(picked.size <= MAX_PICKED_MODELS && picked.all { it.length <= 120 }) { "Too many models picked" }
                val request = if (picked.isNotEmpty()) asked.copy(models = picked) else asked
                require(request.models.isNotEmpty()) { "No model picked" }
                createModel(context, orbit, approval.id, request, secret, english)
            }
            else -> error("Unknown approval action")
        }
    }

    private val existsMessage = Regex("A package with name '([^']+)' already exists")
    private val importLock = kotlinx.coroutines.sync.Mutex()
    private val importedMessage = Regex("^Successfully imported (?:toolpkg|package): (\\S+)")

    private suspend fun importPackages(context: Context, orbit: OrbitProfile, action: JSONObject, english: Boolean): String {
        val manager = packageManager(context)
        fun list(key: String) = action.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val wanted = LinkedHashSet(list("packages"))
        val fileProblems = LinkedHashMap<String, String>()
        val paths = list("files")
        // The files may have grown since the request was made; check them all before importing any.
        val total = paths.sumOf { File(it).length() }
        require(total <= MAX_TOTAL_BYTES) {
            "The files are now over ${MAX_TOTAL_BYTES / 1024 / 1024} MB together; nothing was imported"
        }
        // Imported files are stored by file name: one already there from another package is not replaced.
        val store = manager.externalPackagesFolder()
        fun taken(p: String): Boolean {
            val target = File(store, File(p).name)
            return target.exists() && runCatching { target.canonicalPath != File(p).canonicalPath }.getOrDefault(true)
        }
        // One import at a time, so two approvals cannot write the same file name at once.
        // One budget for the whole request, counted by what each file unpacks to.
        var left = MAX_TOTAL_BYTES
        importLock.withLock { paths.forEach { path ->
            val size = if (File(path).length() > MAX_FILE_BYTES) Long.MAX_VALUE else loadedSize(File(path), left)
            val tooBig = size > left
            if (!tooBig) left -= size
            val message = if (tooBig) "Too large (over ${MAX_TOTAL_BYTES / 1024 / 1024} MB unpacked for one request)"
                else if (taken(path)) "The app already has a package file named ${File(path).name}; if it is the same " +
                    "package, import it by name from orbit_packages list, otherwise rename this file"
                else runCatching { manager.addPackageFileFromExternalStorage(path) }
                    .getOrElse { it.message ?: it.toString() }
            val after = manager.getAvailablePackages()
            // The name this file brought in, from the importer's own answer: another import running at
            // the same time must not slip its packages into this approval.
            val named = (importedMessage.find(message) ?: existsMessage.find(message))?.groupValues?.get(1)
            // A plugin brings its parts in; Orbit takes the parts, never the plugin itself.
            fun parts(name: String) = if (manager.isToolPkgContainer(name)) {
                after.keys.filter { it != name && manager.toolPkgContainerOf(it) == name }
            } else listOf(name)
            val names = named?.takeIf { it in after }?.let(::parts).orEmpty()
            if (names.isEmpty()) fileProblems[File(path).name] = message.lineSequence().first().take(160)
            wanted += names.filterNot { manager.isToolPkgContainer(it) }
        } }
        val had = OrbitPackages.state(context, orbit.id).imported
        val already = wanted.filter { it in had }
        val toImport = wanted - had
        val result = if (toImport.isEmpty()) OrbitPackages.ImportResult(emptySet(), emptyMap())
            else OrbitPackages.importSelected(context, orbit.id, manager, toImport)
        val unavailable = toImport.filter { it !in result.added && it !in result.failed }
        if (result.added.isNotEmpty()) OrbitPackages.refreshPromptWhenIdle(context, orbit)
        // What the new packages still need before they work.
        val available = manager.getAvailablePackages()
        val env = EnvPreferences.getInstance(context)
        val needKeys = result.added.mapNotNull { name ->
            val saved = OrbitVault.packageEnv(context, orbit.id, name)
            val missing = available[name]?.env.orEmpty()
                .filter { it.required && it.name.isNotBlank() }
                .map { it.name.trim() }
                .filter { saved[it].isNullOrEmpty() && env.getEnv(it).isNullOrEmpty() }
            missing.takeIf { it.isNotEmpty() }?.let { "$name (${it.joinToString()})" }
        }
        val needLogin = (result.added + already).filter { it in CHATGPT_PACKAGES }
        val signedIn = CodexAuthPreferences.getInstance(context).currentState() != null
        val parts = mutableListOf<String>()
        if (english) {
            if (result.added.isNotEmpty()) parts += "Imported and switched on: ${result.added.joinToString()}."
            if (already.isNotEmpty()) parts += "Already imported: ${already.joinToString()}."
            result.failed.forEach { (name, e) -> parts += "Not imported $name: ${e.message.orEmpty().take(160)}" }
            if (unavailable.isNotEmpty()) parts += "Not available to import: ${unavailable.joinToString()}."
            fileProblems.forEach { (file, why) -> parts += "Could not import $file: $why" }
            if (needKeys.isNotEmpty()) parts += "Still need a key: ${needKeys.joinToString("; ")}."
            if (needLogin.isNotEmpty() && !signedIn) parts += "Need the ChatGPT login: ${needLogin.joinToString()}."
            if (parts.isEmpty()) parts += "Nothing was imported."
        } else {
            if (result.added.isNotEmpty()) parts += "已导入并打开：${result.added.joinToString("、")}。"
            if (already.isNotEmpty()) parts += "原本就有：${already.joinToString("、")}。"
            result.failed.forEach { (name, e) -> parts += "$name 没导入：${e.message.orEmpty().take(160)}" }
            if (unavailable.isNotEmpty()) parts += "这些没法导入：${unavailable.joinToString("、")}。"
            fileProblems.forEach { (file, why) -> parts += "$file 没导入：$why" }
            if (needKeys.isNotEmpty()) parts += "还缺密钥：${needKeys.joinToString("；")}。"
            if (needLogin.isNotEmpty() && !signedIn) parts += "需要登录 ChatGPT：${needLogin.joinToString("、")}。"
            if (parts.isEmpty()) parts += "什么都没导入。"
        }
        return parts.joinToString("\n")
    }

    private suspend fun togglePackages(context: Context, orbit: OrbitProfile, action: JSONObject, english: Boolean): String {
        fun list(key: String) = action.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getString(it) }.distinct()
        }.orEmpty()
        val on = list("on")
        val off = list("off")
        val allowedEnabled = on.toSet()
        require(on.isNotEmpty() || off.isNotEmpty()) { "No packages to switch" }
        require(on.size + off.size <= MAX_ITEMS && on.intersect(off.toSet()).isEmpty()) { "Invalid package switch request" }
        val manager = packageManager(context)
        val opened = linkedSetOf<String>()
        val closed = linkedSetOf<String>()
        val unchanged = linkedMapOf<String, String>()
        fun why(e: Exception): String = when (e) {
            is OrbitPackages.RequiredException -> if (english) "always on; cannot switch off" else "始终开着，不能关闭"
            is OrbitPackages.NotImportedException -> if (english) "no longer imported" else "已不在导入列表里"
            is OrbitPackages.StillNeededException -> if (english) "still needed by ${e.dependents.joinToString()}"
                else "仍被这些包依赖：${e.dependents.joinToString("、")}"
            else -> e.message.orEmpty().take(160)
        }
        // A dependency requested off with its dependents is retried after they have been switched off.
        suspend fun switch(name: String, enable: Boolean, waiting: Set<String> = emptySet()): Boolean {
            try {
                val before = OrbitPackages.state(context, orbit.id)
                when {
                    !enable && name in OrbitPackages.REQUIRED -> throw OrbitPackages.RequiredException(name)
                    name !in before.imported -> throw OrbitPackages.NotImportedException(name)
                    (name in before.enabled) == enable -> if (name !in opened && name !in closed) {
                        unchanged[name] = if (english) "already ${if (enable) "on" else "off"}"
                            else "原本就${if (enable) "开着" else "关着"}"
                    }
                    name !in manager.getAvailablePackages() -> unchanged[name] =
                        if (english) "package is no longer available" else "工具包已不可用"
                    else -> {
                        val added = OrbitPackages.setEnabled(context, orbit.id, manager, name, enable, allowedEnabled)
                        if (enable) opened += added - before.enabled else closed += name
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is OrbitPackages.StillNeededException && e.dependents.any { it in waiting }) return false
                unchanged[name] = why(e)
            }
            return true
        }
        try {
            val waiting = off.toMutableSet()
            while (waiting.isNotEmpty()) {
                val completed = waiting.filter { switch(it, false, waiting - it) }
                if (completed.isEmpty()) {
                    waiting.forEach { switch(it, false) }
                    break
                }
                waiting.removeAll(completed.toSet())
            }
            on.forEach { switch(it, true) }
        } finally {
            if (opened.isNotEmpty() || closed.isNotEmpty()) OrbitPackages.refreshPromptWhenIdle(context, orbit)
        }
        val parts = mutableListOf<String>()
        if (english) {
            if (opened.isNotEmpty()) parts += "Switched on: ${opened.joinToString()}. Load the packages you need with use_package."
            if (closed.isNotEmpty()) parts += "Switched off: ${closed.joinToString()}. Do not use these packages any more."
            unchanged.forEach { (name, reason) -> parts += "Unchanged $name: $reason." }
        } else {
            if (opened.isNotEmpty()) parts += "已打开：${opened.joinToString("、")}。需要使用的包先用 use_package 加载。"
            if (closed.isNotEmpty()) parts += "已关闭：${closed.joinToString("、")}。不要再使用这些包。"
            unchanged.forEach { (name, reason) -> parts += "$name 没变：$reason。" }
        }
        return parts.joinToString("\n")
    }

    private suspend fun createModel(context: Context, orbit: OrbitProfile, approvalId: String, m: ModelRequest,
        secret: String?, english: Boolean): String {
        val key = secret?.trim().orEmpty()
        require(!m.needsKey || key.isNotEmpty()) { "API key missing" }
        require(key.length <= 4000) { "API key too long" }
        val configs = ModelConfigManager(context)
        configs.initializeIfNeeded()
        // Named after the approval, so saying yes again after a crash fills in the same config.
        val id = "orbit-${orbit.id.take(8)}-$approvalId"
        val existed = configs.getAllConfigSummaries().any { it.id == id }
        if (!existed) configs.createConfig(m.name, id)
        // Anything after the config exists that fails, or a cancel, takes the config away again.
        try {
            configs.updateModelConfig(id, key, m.endpoint, m.models.joinToString(","), m.provider)
            val first = m.models.first()
            when (m.useAs) {
                USE_MAIN -> OrbitModelPreferences(context).update(orbit.id) { it.copy(main = OrbitModelRef(id, first)) }
                USE_ASSISTANT -> OrbitModelPreferences(context).update(orbit.id) { it.copy(assistant = OrbitModelRef(id, first)) }
            }
        } catch (e: Throwable) {
            if (!existed) withContext(NonCancellable) { runCatching { configs.deleteConfig(id) } }
            throw e
        }
        val first = m.models.first()
        return if (english) {
            "Added the model configuration \"${m.name}\" (${m.provider.name}, ${m.endpoint}, models ${m.models.joinToString()})" +
                (if (key.isNotEmpty()) " with the API key the user typed" else "") + "." +
                when (m.useAs) {
                    USE_MAIN -> " It is now your main model, $first. If replies fail, tell the user to switch back in Orbit settings > Models."
                    USE_ASSISTANT -> " It is now your assistant model, $first."
                    else -> " It is not in use yet; the user can pick it in Orbit settings > Models."
                }
        } else {
            "已新建模型配置「${m.name}」（${m.provider.name}，${m.endpoint}，模型 ${m.models.joinToString("、")}）" +
                (if (key.isNotEmpty()) "，API 密钥是用户亲手填的" else "") + "。" +
                when (m.useAs) {
                    USE_MAIN -> "已设为你的主模型 $first。要是回复出错，请用户到 Orbit 设置 > 模型 换回去。"
                    USE_ASSISTANT -> "已设为你的辅助模型 $first。"
                    else -> "还没启用，用户可以在 Orbit 设置 > 模型 里选它。"
                }
        }
    }

    /** Providers Orbit may add, for the tool's list. */
    fun providers(): List<String> = ApiProviderType.values().filter { it !in ACCOUNT_PROVIDERS }.map { it.name }

    fun defaultEndpoint(provider: ApiProviderType): String = ApiProviderConfigs.getDefaultApiEndpoint(provider)

    fun requiresKey(provider: ApiProviderType, endpoint: String): Boolean =
        ApiProviderConfigs.requiresApiKey(provider, endpoint)

    fun splitModels(text: String): List<String> = getModelList(text.replace('\n', ',')).distinct()
}
