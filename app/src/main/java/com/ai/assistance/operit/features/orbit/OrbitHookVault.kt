package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitProfile
import org.json.JSONObject

/**
 * Which hooks may load real vault values (`env=` in their source). Surrogates for the proxy need no
 * approval: they cannot leave the key's own sites. Real values need the user's yes on a card, once
 * per pinned script, or once per key with "always allow" ([OrbitVault.Entry.hookAlways]).
 */
internal object OrbitHookVault {
    const val ACTION = "hook_vault"

    private fun prefs(context: Context) = context.getSharedPreferences("orbit_hook_vault", Context.MODE_PRIVATE)

    /** The variable holding a proxy surrogate for vault entry [id]. */
    fun surrogateVariable(id: String) = "ORBIT_VAULT_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /** The exact [source] (script hash and key ids) the user allowed for this task. */
    private fun approved(context: Context, taskId: String, source: String) =
        prefs(context).getString(taskId, null) == source

    /** The [entries] this hook still needs the user's yes for. */
    fun unapproved(context: Context, taskId: String, source: String, entries: List<OrbitVault.Entry>): List<OrbitVault.Entry> =
        if (entries.isEmpty() || approved(context, taskId, source)) emptyList() else entries.filterNot { it.hookAlways }

    /** Remembers [source] as allowed, so turning "always allow" off later only affects new or changed hooks. */
    fun approve(context: Context, taskId: String, source: String) {
        check(prefs(context).edit().putString(taskId, source).commit()) { "Could not save the approval; try again" }
    }

    /** Puts an approval card in the chat when [task]'s hook loads real values the user has not allowed yet. */
    suspend fun ask(context: Context, orbit: OrbitProfile, chatId: String, task: OrbitTaskEntity): OrbitApprovals.Approval? {
        val hook = OrbitWatchSources.hook(task.source) ?: return null
        if (hook.env.isEmpty()) return null
        val entries = OrbitVault.entries(context, orbit.id).filter { it.id in hook.env }
        val missing = unapproved(context, task.id, task.source, entries)
        if (missing.isEmpty()) return null
        val script = runCatching { OrbitFiles.of(context, orbit.id).read(hook.path) }.getOrDefault("")
        val sites = Regex("https?://([A-Za-z0-9.-]+)").findAll(script).map { it.groupValues[1].lowercase() }
            .distinct().take(8).toList()
        val name = task.title.take(40)
        val sep = context.getString(R.string.orbit_hook_vault_separator)
        val detail = context.getString(R.string.orbit_hook_vault_detail, name,
            missing.joinToString(sep) { it.site }, sites.joinToString(sep).ifEmpty { "—" },
            hook.path.substringAfterLast('/'))
        // The keys shown on the card: a yes covers only these.
        val action = JSONObject().put("type", ACTION).put("task", task.id).put("source", task.source)
            .put("keys", org.json.JSONArray(missing.map { it.id })).toString()
        return OrbitApprovals.request(context, orbit.id, chatId,
            context.getString(R.string.orbit_hook_vault_title, name).take(OrbitApprovals.MAX_TITLE),
            detail.take(OrbitApprovals.MAX_DETAIL), action)
    }

    /** The user said yes: allow this exact script, and with [always] every hook, to load these keys. */
    suspend fun run(context: Context, orbit: OrbitProfile, action: JSONObject, english: Boolean, always: Boolean): String {
        val taskId = action.getString("task")
        val source = action.getString("source")
        val shown = action.optJSONArray("keys")?.let { keys -> (0 until keys.length()).map { keys.getString(it) } }.orEmpty().toSet()
        // A card that no longer fits allows nothing; its answer tells Orbit to ask again with dry_run.
        fun stale(en: String, zh: String) = if (english) "Nothing was allowed: $en Call orbit_task dry_run to put up a new card."
            else "没有生效：$zh 用 orbit_task dry_run 重新出一张卡片。"
        // Checked and remembered together, so the task cannot change in between.
        val outcome: Pair<com.ai.assistance.operit.data.model.OrbitTaskEntity?, String> = OrbitExecutionGate.serialized {
            val found = AppDatabase.getDatabase(context).orbitTaskDao().task(taskId)
            if (found == null || found.orbitId != orbit.id || found.state == "CANCELLED") {
                return@serialized null to (if (english) "Nothing was allowed: this hook no longer exists." else "没有生效：这个 hook 已经不在了。")
            }
            if (found.source != source) {
                return@serialized null to stale("the hook changed after this card was made.", "这张卡片发出后 hook 又改过了。")
            }
            val hook = checkNotNull(OrbitWatchSources.hook(source))
            // A key not on the card needs asking now ("always allow" turned off since): this yes does not cover it.
            val entries = OrbitVault.entries(context, orbit.id).filter { it.id in hook.env }
            if (!unapproved(context, taskId, source, entries).all { it.id in shown }) {
                return@serialized null to stale("which keys need the user's yes changed after this card was made.",
                    "这张卡片发出后需要批准的密钥变了。")
            }
            approve(context, taskId, source)
            found to ""
        }
        val task = outcome.first ?: return outcome.second
        if (always) shown.forEach { OrbitVault.setHookAlways(context, orbit.id, it, true) }
        return if (english) {
            "The hook \"${task.title}\" may now load these keys" + (if (always) ", and so may any later hook" else "") +
                ". It is still paused: call orbit_task dry_run, check the output, then resume."
        } else {
            "hook「${task.title}」现在可以用这些密钥了" + (if (always) "，以后其他 hook 用它们也不用再问" else "") +
                "。它还是暂停状态：先 orbit_task dry_run 看输出，没问题再 resume。"
        }
    }
}
