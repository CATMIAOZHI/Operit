package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.EnvPreferences
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitPackages
import com.ai.assistance.operit.features.orbit.OrbitVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit finds tool packages and asks to import or switch them. Changes wait for the user's
 * approval on a card; the app carries them out and tells Orbit what happened.
 */
internal object OrbitPackageTools {
    const val NAME = "orbit_packages"
    private const val MAX_DESCRIPTION = 100

    val prompt = ToolPrompt(
        name = NAME,
        description = "List your tool packages and the main app's packages you could import, and ask the user to import some " +
            "(from the main app or from package files), or to switch imported packages on/off. 查看工具包，申请导入或开关（需用户批准）。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "list / request_import / request_toggle", required = true),
            ToolParameterSchema("on", description = "request_toggle: imported package names to switch on, comma-separated", required = false),
            ToolParameterSchema("off", description = "request_toggle: imported package names to switch off, comma-separated; always_on packages cannot be switched off", required = false),
            ToolParameterSchema("query", description = "list: optional word to filter by name, title or description", required = false),
            ToolParameterSchema("packages", description = "request_import: package names from list's available, comma-separated", required = false),
            ToolParameterSchema("paths", description = "request_import: package files (.js/.ts/.hjson/.toolpkg) or folders holding them, comma or newline separated; a folder means the package files right inside it", required = false),
            ToolParameterSchema("title", description = "request_import / request_toggle: optional short title in the user's language", required = false),
            ToolParameterSchema("reason", description = "request_import / request_toggle: one or two sentences telling the user why", required = false),
        ),
        details = """
  - list shows imported (yours, on or off) and available (the main app's packages not imported yet), each with what keys (env) it needs and whether they are set.
  - request_import asks once for several at a time: up to ${OrbitApprovalActions.MAX_ITEMS} packages plus up to ${OrbitApprovalActions.MAX_ITEMS} files. It puts an approval card in the chat and returns at once; end the turn with one short line. When the user approves, the app imports them and switches them on, and you get a hidden note saying what was imported, what failed, and what still needs a key or login. Then use_package them.
  - request_toggle asks the user to switch imported packages on/off (up to ${OrbitApprovalActions.MAX_ITEMS} total, including dependencies that need switching on). Read list's on/always_on first: always_on packages cannot be switched off; asking for an unchanged state is an error. Put names in on/off, never both. Dependencies that also need switching on are shown on the card; import missing ones first. End the turn with one short line after the card appears; the app changes switches only after approval and sends a hidden result. Use use_package for newly enabled packages; disabled packages must no longer be used.
  - Only ask for packages that help with what the user wants. Read a file package first if you are not sure what it does.
  - A package that needs a key: ask with orbit_vault request_api_key, package_name and env_name. codex_draw needs the ChatGPT login: orbit_vault request_chatgpt_login.
  - 一次申请可以同时导入多个；用户同意后由 App 导入，你会收到结果通知，再 use_package。
  - 已导入的包可以用 request_toggle 申请打开或关闭，on/off 填包名；依赖也会列在批准卡里。always_on 的包始终开着，不能关闭；已经是目标状态的包不要再申请。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.trim().orEmpty()
            fun items(name: String) = arg(name).split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val manager = OrbitApprovalActions.packageManager(context)
            val state = OrbitPackages.state(context, orbit.id)
            val result: JSONObject = when (arg("action")) {
                "list" -> {
                    val query = arg("query").lowercase()
                    val env = EnvPreferences.getInstance(context)
                    val candidates = OrbitPackages.candidates(manager, state.imported)
                    suspend fun describe(name: String): JSONObject? {
                        val pkg = candidates[name] ?: return null
                        val title = pkg.displayName.resolve(context).ifBlank { name }
                        val description = pkg.description.resolve(context).replace(Regex("\\s+"), " ").trim()
                        if (query.isNotEmpty() && listOf(name, title, description).none { it.lowercase().contains(query) }) return null
                        val saved = OrbitVault.packageEnv(context, orbit.id, name)
                        return JSONObject().put("name", name)
                            .apply { if (title != name) put("title", title) }
                            .put("description", description.take(MAX_DESCRIPTION))
                            .apply {
                                val needs = pkg.env.filter { it.name.isNotBlank() }
                                if (needs.isNotEmpty()) put("env", JSONArray(needs.map {
                                    val n = it.name.trim()
                                    JSONObject().put("name", n).put("required", it.required)
                                        .put("set", !saved[n].isNullOrEmpty() || !env.getEnv(n).isNullOrEmpty())
                                }))
                            }
                    }
                    val imported = JSONArray()
                    state.imported.sorted().forEach { name ->
                        describe(name)?.let { imported.put(it.put("on", name in state.enabled).put("always_on", name in OrbitPackages.REQUIRED)) }
                    }
                    val available = JSONArray()
                    (OrbitPackages.importable(manager).keys - state.imported).sorted().forEach { name -> describe(name)?.let(available::put) }
                    JSONObject().put("imported", imported).put("available", available)
                        .put("note", "Use request_import for available or phone package files; use request_toggle with on/off for imported packages. always_on packages stay on.")
                }
                "request_import" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    val names = items("packages")
                    val paths = items("paths")
                    require(names.isNotEmpty() || paths.isNotEmpty()) { "Give packages, paths, or both" }
                    require(names.size <= OrbitApprovalActions.MAX_ITEMS) {
                        "At most ${OrbitApprovalActions.MAX_ITEMS} packages in one request"
                    }
                    val importable = OrbitPackages.importable(manager)
                    val already = names.filter { it in state.imported }
                    require(already.isEmpty()) { "Already imported: ${already.joinToString()}; use request_toggle to switch them on if they are off" }
                    val unknown = names.filter { it !in importable }
                    require(unknown.isEmpty()) { "Not in available: ${unknown.joinToString()}; see list" }
                    val files = OrbitApprovalActions.packageFiles(paths)
                    val titled = names.map { it to importable.getValue(it).displayName.resolve(context) }
                    val title = arg("title").ifEmpty { context.getString(com.ai.assistance.operit.R.string.orbit_approval_import_title) }
                        .take(OrbitApprovals.MAX_TITLE)
                    val detail = OrbitApprovalActions.importDetail(context, titled, files, arg("reason").take(300))
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) {
                        "Too long for the user to review in one card; split it into smaller requests with fewer packages or paths"
                    }
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(), title, detail,
                        OrbitApprovalActions.importAction(names, files))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("files", JSONArray(files.map { it.absolutePath }))
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. Once the user " +
                            "approves, the app imports them and you get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                "request_toggle" -> {
                    check(OrbitPreferences.getInstance(context).current().orbitForChat(caller) != null) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    val requestedOn = items("on")
                    val off = items("off")
                    require(requestedOn.isNotEmpty() || off.isNotEmpty()) { "Give on, off, or both" }
                    require(requestedOn.size + off.size <= OrbitApprovalActions.MAX_ITEMS) {
                        "At most ${OrbitApprovalActions.MAX_ITEMS} packages in one toggle request"
                    }
                    require(requestedOn.intersect(off.toSet()).isEmpty()) { "A package cannot be in both on and off" }
                    val required = off.filter { it in OrbitPackages.REQUIRED }
                    require(required.isEmpty()) { "These packages are always on and cannot be switched off: ${required.joinToString()}" }
                    val missing = (requestedOn + off).filter { it !in state.imported }
                    require(missing.isEmpty()) { "Not imported: ${missing.joinToString()}; see list" }
                    val alreadyOn = requestedOn.filter { it in state.enabled }
                    val alreadyOff = off.filter { it !in state.enabled }
                    require(alreadyOn.isEmpty() && alreadyOff.isEmpty()) {
                        "Already on: ${alreadyOn.joinToString().ifEmpty { "(none)" }}; already off: " +
                            "${alreadyOff.joinToString().ifEmpty { "(none)" }}. Remove these and request again"
                    }
                    val available = manager.getAvailablePackages()
                    val unavailable = (requestedOn + off).filter { it !in available }
                    require(unavailable.isEmpty()) { "Package no longer available: ${unavailable.joinToString()}" }
                    val dependencies = requestedOn.flatMap { manager.orbitRequiredPackages(it) }.distinct()
                    val conflicts = dependencies.intersect(off.toSet())
                    require(conflicts.isEmpty()) { "Cannot switch off dependencies of packages being switched on: ${conflicts.joinToString()}" }
                    val missingDependencies = dependencies.filter { it !in state.imported }
                    require(missingDependencies.isEmpty()) {
                        "Import these dependencies first with request_import: ${missingDependencies.joinToString()}"
                    }
                    val on = (requestedOn + dependencies.filter { it !in state.enabled }).distinct()
                    require(on.size + off.size <= OrbitApprovalActions.MAX_ITEMS) {
                        "At most ${OrbitApprovalActions.MAX_ITEMS} packages including dependencies; request fewer packages"
                    }
                    fun titled(names: List<String>) = names.map { it to available[it]?.displayName?.resolve(context).orEmpty() }
                    val title = arg("title").ifEmpty { context.getString(com.ai.assistance.operit.R.string.orbit_approval_toggle_title) }
                        .take(OrbitApprovals.MAX_TITLE)
                    val detail = OrbitApprovalActions.toggleDetail(context, titled(on), titled(off), arg("reason").take(300))
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) { "Too long for one card; request fewer packages" }
                    val approval = OrbitApprovals.request(context, orbit.id, caller.orEmpty(), title, detail,
                        OrbitApprovalActions.toggleAction(on, off))
                    JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. Once the user approves, the app changes the switches and you get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                }
                else -> error("action must be list, request_import or request_toggle")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}
