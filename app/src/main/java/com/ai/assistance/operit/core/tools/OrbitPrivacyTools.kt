package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.features.orbit.OrbitForget
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitVault
import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Forgetting, where a memory came from, and the check before anything is sent for the user. */
internal object OrbitPrivacyTools {
    const val FORGET = "orbit_forget"
    const val ORIGIN = "orbit_memory_origin"
    const val PRESEND = "orbit_presend_check"

    val forgetPrompt = ToolPrompt(
        name = FORGET,
        description = "用户要你忘掉某件事时用：先找出这条信息在你这里的所有副本，给用户看清理范围，用户同意后一起清掉。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "plan/confirm/cancel", required = true),
            ToolParameterSchema("terms", description = "plan：能找到这条信息的关键词，每行一个，1-10个", required = false),
            ToolParameterSchema("plan_id", description = "confirm：plan 返回的编号", required = false),
        ),
        details = """
  - 「忘掉」是在你这里全面清理，不是改一条笔记。改错的事实、换个偏好，用记忆工具改就行，不用这个。
  - 先 plan（只读，不改任何东西）。它会列出记忆、用户资料、文件（含每晚复盘记录、对齐状态和回收站）、技能、目标、定时任务、动态和点子、还没发出的汇报、修改记录里有这条信息的地方，以及准备怎么处理（edit 整行删除、delete 整条删除、stop 停止、purge 把回收站里的那份彻底删掉）。SOUL.md、IDENTITY.md 和自定义提示词里的命中只列出来，不会自动改，要请用户自己在 Orbit 资料页里改。scanned 是查过的范围，unscanned 是读不了、没能检查的地方。
  - 用日常说法把范围告诉用户：记忆、提醒和定时任务、你做的东西、记录。说清楚删除不能撤销，再问一句是否确认。不要在回复里重复要忘掉的内容，用「那件事」指代。
  - 用户明确同意后才 confirm。沉默、含糊、只同意一部分，都不算同意；用户改了范围就重新 plan。
  - confirm 会重新检查，情况变了会要求重新 plan。完成后按结果汇报清掉了哪些类别、停了哪些任务、还剩什么。
  - 聊天记录不会被清理：这次和以前的聊天里还留着原话，用户可以在聊天列表里自己删掉那些聊天。汇报时要说明这一点。
  - 只有 all_clear 为 true 时，才能说「我存的内容里已经清干净了」；不要说成「完全忘掉了」。all_clear 为 false 时，照 remaining、failed 和 unscanned 如实说还剩哪里、哪里没能检查。
  - 清理之后，这条信息不能再写回记忆。别人的邮件、日历、已经发出去的东西要另外问用户。
""",
    )

    val originPrompt = ToolPrompt(
        name = ORIGIN,
        description = "查一条记忆是从哪来的：哪次聊天、什么时候、谁批准的、替换了什么。",
        parametersStructured = listOf(
            ToolParameterSchema("line", description = "记忆位置，比如 memory.md#L12 或 user.md#L3", required = false),
            ToolParameterSchema("text", description = "或者给记忆里的一段原文", required = false),
        ),
        details = """
  - 用户问「你怎么知道的」「这条是哪来的」时用。按结果回答来源，不要凭印象编。
  - 结果不会给出被替换掉的旧内容，只告诉你替换了几行。
  - 找不到时如实说：可能是用户自己改的、很早以前写的，或者记录已经清理。
""",
    )

    val presendPrompt = ToolPrompt(
        name = PRESEND,
        description = "以用户的名义往外发消息、邮件、评论之前的检查：隐私、密码、内容是否定稿、收件人是否明确、用户是否看过并同意。",
        parametersStructured = listOf(
            ToolParameterSchema("text", description = "准备发出去的完整正文", required = true),
            ToolParameterSchema("recipient", description = "发给谁：名字、邮箱、号码或群名", required = true),
            ToolParameterSchema("channel", description = "通过什么发：email/sms/wechat/qq/github/other", required = false),
        ),
        details = """
  - 每次替用户对外发东西前都先调用。verdict 是 BLOCK 就不要发，按 checks 里的原因处理（改正文、问清收件人、先给用户看草稿）。
  - 草稿要先原样发给用户看，用户同意后再检查和发送；改过的版本要重新给用户看。
  - 这个工具不能判断用户的回复是不是同意：approval 永远是 UNVERIFIED。verdict 是 PASS 时，看 user_replies 里用户看过草稿后说的话（按时间排，最后一条最新），以最新的为准：明确同意才发；后面又说“等等”“不要发”、提了修改或含糊其辞都不算。
  - warnings 不拦截，但要想一想：比如正文里有电话号码、身份证号，确认是用户想发的。
""",
    )

    private fun args(tool: AITool) = tool.parameters.associate { it.name to it.value }
    private val CANNOT_ERASE = listOf("this chat and earlier chats (the user can delete those chats from the chat list)",
        "things already sent to outside services or other apps", "data backups the user made", "background run logs")
    private fun fail(name: String, e: Exception) = ToolResult(name, false, StringResultData(""), error = e.message ?: e.toString())

    private suspend fun orbitOf(context: Context): Pair<OrbitProfile, String?> {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        return checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" } to caller
    }

    fun executeForget(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val (orbit, caller) = orbitOf(context)
            val chatId = checkNotNull(caller?.takeIf { OrbitPreferences.getInstance(context).current().orbitForChat(it) != null }) {
                "Forgetting only works in a chat with the user"
            }
            val a = args(tool)
            val result = when (a["action"]?.trim()) {
                "plan" -> {
                    val terms = a["terms"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val planned = OrbitForget.plan(context, orbit, chatId, terms)
                    val hits = planned.hits
                    JSONObject().put("plan_id", planned.id)
                        .put("summary", JSONObject(hits.groupingBy { it.category }.eachCount()))
                        .put("places", JSONArray(hits.map { it.json() }))
                        .put("scanned", JSONArray(OrbitForget.SCANNED))
                        .put("unscanned", JSONArray(planned.unscanned.take(50)))
                        .put("cannot_erase", JSONArray(CANNOT_ERASE))
                        .put("next", when {
                            hits.isEmpty() && planned.unscanned.isEmpty() -> "Nothing was found. Tell the user, or plan again with other terms."
                            hits.isEmpty() -> "Nothing was found in what could be read, but the places in unscanned could not be checked; tell the user that."
                            else -> "Tell the user the scope in everyday words, say it cannot be undone, and ask once for confirmation." +
                                if (planned.unscanned.isNotEmpty()) " Also say some places could not be checked." else ""
                        })
                }
                "confirm" -> {
                    val lastUser = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(chatId, 30)
                        .firstOrNull { it.sender == "user" }?.timestamp ?: 0L
                    val outcome = OrbitForget.confirm(context, orbit, chatId, a["plan_id"]?.trim().orEmpty(), lastUser)
                    JSONObject().put("cleaned", JSONObject(outcome.cleaned as Map<*, *>))
                        .put("remaining", JSONArray(outcome.remaining.take(OrbitForget.MAX_PLACES).map { it.json() }))
                        .put("failed", JSONArray(outcome.failed))
                        .put("scanned", JSONArray(OrbitForget.SCANNED))
                        .put("unscanned", JSONArray(outcome.unscanned.take(50)))
                        .put("all_clear", outcome.remaining.isEmpty() && outcome.failed.isEmpty() && outcome.unscanned.isEmpty())
                        .put("cannot_erase", JSONArray(CANNOT_ERASE))
                        .put("note", "all_clear covers only what this Orbit stores, not chat history. " +
                            "Places in unscanned could not be read, so they may still hold it. " +
                            "Places marked user_review must be edited by the user. Report categories only; never repeat the information.")
                }
                "cancel" -> { OrbitForget.cancel(context, orbit.id); JSONObject().put("cancelled", true) }
                else -> error("Unknown action \"${a["action"].orEmpty()}\"; use plan, confirm or cancel")
            }
            ToolResult(FORGET, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(FORGET, e) }
    }

    fun executeOrigin(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val (orbit, _) = orbitOf(context)
            val spaceId = OrbitMemoryScope.ensureInitialized(context, orbit)
            val a = args(tool)
            val line = a["line"]?.trim().orEmpty()
            val (file, needle) = if (line.isNotEmpty()) {
                val m = Regex("(memory\\.md|user\\.md)#L(\\d+)").matchEntire(line) ?: error("line must look like memory.md#L12")
                val text = if (m.groupValues[1] == "memory.md") MemoryNotesRepository(context, spaceId).load().markdown
                    else UserProfileDocumentRepository.forMemorySpace(context, spaceId).load()
                m.groupValues[1] to (text.lines().getOrNull(m.groupValues[2].toInt() - 1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("That line is empty or does not exist"))
            } else "" to a["text"]?.trim().orEmpty().also { require(it.length >= 2) { "Give line or text" } }
            val key = OrbitForget.normalize(needle.trimStart('-', '*', ' '))
            fun has(text: String) = text.lines().any { OrbitForget.normalize(it).contains(key) }
            val changes = MemoryReviewRepository(context, spaceId).list()
                .filter { it.kind in setOf("notes", "user") && it.status == "approved" }
                .filter { file.isEmpty() || it.title == file }
                .sortedBy { it.createdAt }
            val introduced = changes.firstOrNull { has(it.body) && !has(it.before) }
            val result = if (introduced == null) JSONObject().put("found", false)
                .put("note", "No recorded change added it. The user may have edited it directly, it may predate the record, or the record was trimmed.")
            else {
                val later = changes.filter { it.createdAt > introduced.createdAt && has(it.before) &&
                    it.before.lines().filter { l -> OrbitForget.normalize(l).contains(key) } != it.body.lines().filter { l -> OrbitForget.normalize(l).contains(key) } }
                val removed = introduced.before.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .count { it !in introduced.body.lines().map { l -> l.trim() }.toSet() }
                JSONObject().put("found", true).put("file", introduced.title).put("added_at", introduced.createdAt)
                    .put("source_chat", chatInfo(context, orbit, introduced.sourceChatId))
                    .put("decided_by", when { introduced.automatic || introduced.reviewer == "automatic" -> "automatic review"
                        introduced.reviewer.isBlank() -> "unknown"; else -> introduced.reviewer })
                    .put("replaced_lines", removed)
                    .put("later_edits", later.size)
                    .apply { if (introduced.restoredFrom.isNotEmpty()) put("restored_from_earlier_version", true) }
                    .put("still_present", has(if (introduced.title == "user.md")
                        UserProfileDocumentRepository.forMemorySpace(context, spaceId).load() else MemoryNotesRepository(context, spaceId).load().markdown))
            }
            ToolResult(ORIGIN, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(ORIGIN, e) }
    }

    private suspend fun chatInfo(context: Context, orbit: OrbitProfile, chatId: String): JSONObject {
        if (chatId.isBlank()) return JSONObject().put("kind", "unknown")
        val title = AppDatabase.getDatabase(context).chatDao().getChatById(chatId)?.title.orEmpty()
        val kind = when {
            chatId == orbit.chatId -> "main chat"
            orbit.sideChats.any { it.chatId == chatId } -> "side chat"
            AppDatabase.getDatabase(context).orbitTaskDao().binding(chatId) != null -> "background task"
            title.isEmpty() -> "deleted chat"
            else -> "other chat"
        }
        return JSONObject().put("chat_id", chatId).put("kind", kind).put("title", title.take(80))
    }

    private val placeholder = Regex("""\[(?:名字|姓名|xx+|todo|tbd|placeholder|insert[^\]]*)\]|\{\{|\}\}|\bTODO\b|\bTBD\b|占位|待填|待补充|XXX|lorem ipsum""", RegexOption.IGNORE_CASE)
    private val secretLike = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})""")
    private val vague = setOf("他", "她", "它", "他们", "她们", "大家", "对方", "那个人", "someone", "them", "him", "her", "they", "everyone")
    private val privacyPatterns = listOf(
        "phone number" to Regex("""(?<!\d)1[3-9]\d{9}(?!\d)|\+\d{1,3}[ -]?\d{3,4}[ -]?\d{3,4}[ -]?\d{3,4}"""),
        "ID card number" to Regex("""(?<!\d)\d{17}[\dXx](?![\dXx])"""),
        "bank card number" to Regex("""(?<!\d)\d{16,19}(?!\d)"""),
        "email address" to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
    )

    fun executePresend(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val (orbit, caller) = orbitOf(context)
            val a = args(tool)
            val text = a["text"].orEmpty()
            val recipient = a["recipient"]?.trim().orEmpty()
            val checks = JSONArray()
            val warnings = JSONArray()
            var blocked = false
            fun check(name: String, pass: Boolean, reason: String) {
                if (!pass) blocked = true
                checks.put(JSONObject().put("check", name).put("pass", pass).put("reason", reason))
            }
            check("final draft", text.isNotBlank() && !placeholder.containsMatchIn(text),
                if (text.isBlank()) "The text is empty" else if (placeholder.containsMatchIn(text)) "The text still has placeholders" else "OK")
            val hasVaultSecret = OrbitVault.containsSecret(context, orbit.id, text)
            check("no secrets", !hasVaultSecret && !secretLike.containsMatchIn(text),
                if (hasVaultSecret) "The text contains a value saved in the vault" else if (secretLike.containsMatchIn(text)) "The text contains what looks like a key or token" else "OK")
            val forgotten = OrbitForget.containsForgotten(context, orbit.id, text + "\n" + recipient)
            check("nothing forgotten", !forgotten, if (forgotten) "The text contains information the user asked you to forget" else "OK")
            check("clear recipient", recipient.isNotEmpty() && recipient.lowercase() !in vague,
                if (recipient.isEmpty()) "No recipient" else if (recipient.lowercase() in vague) "Say exactly who receives it" else "OK")
            privacyPatterns.forEach { (label, regex) ->
                val found = regex.findAll(text).map { it.value }.filterNot { label == "email address" && it.equals(recipient, true) }.count()
                if (found > 0) warnings.put("The text contains $found $label(s); make sure the user wants to send them")
            }
            val talk = caller?.let { OrbitPreferences.getInstance(context).current().orbitForChat(it) } != null
            val userReplies = JSONArray()
            var repliesCut = false
            if (talk) {
                val messages = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(caller!!, 40)
                // Quote marks, bullets and bold around a draft shown in the chat do not make it a different text.
                fun plain(value: String) = OrbitForget.normalize(value.lines()
                    .joinToString(" ") { it.trimStart().trimStart('>').trim() }.replace(Regex("[*_`~]"), ""))
                val full = plain(text)
                val probe = full.take(80)
                fun said(m: com.ai.assistance.operit.data.model.ChatMessage) = plain(ChatUtils.removeThinkingContent(m.content))
                // The newest draft that starts the same way, and whether it is the whole of this text.
                val started = messages.firstOrNull { it.sender == "ai" && said(it).contains(probe) }
                val shown = started?.takeIf { said(it).contains(full) }
                // Every visible user message since the draft, oldest first: a later "wait, don't" overrides an earlier "ok".
                val replies = shown?.let { s ->
                    messages.filter {
                        it.sender == "user" && it.timestamp > s.timestamp &&
                            it.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL
                    }.sortedBy { it.timestamp }
                }.orEmpty()
                repliesCut = replies.size > 5
                replies.takeLast(5).forEach { m ->
                    val body = m.content.trim()
                    userReplies.put(JSONObject().put("message_id", m.timestamp).put("text", body.take(1000))
                        .apply { if (body.length > 1000) put("cut", true).put("chars", body.length) })
                }
                check("draft shown and answered", replies.isNotEmpty(), when {
                    started == null -> "Show the user this exact text first and wait for their answer"
                    shown == null -> "The draft you showed is different from this text; show the final version to the user first"
                    replies.isEmpty() -> "The user has not answered since you showed the draft"
                    else -> "The user answered after seeing this exact text; read user_replies (the last one is the newest) to decide if it approves"
                })
            } else {
                warnings.put("Background run: send only if the task instruction from the user clearly asks for this message")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest("$recipient\n${OrbitForget.normalize(text)}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val log = File(OrbitPreferences.getInstance(context).orbitDir(orbit.id), "presend_log.json")
            val now = System.currentTimeMillis()
            val past = runCatching { JSONArray(log.readText()) }.getOrDefault(JSONArray())
            val recent = (0 until past.length()).map { past.getJSONObject(it) }.filter { now - it.optLong("at") < 7L * 24 * 3600_000 }
            if (recent.any { it.optString("hash") == digest && it.optBoolean("passed") && now - it.optLong("at") < 24L * 3600_000 }) {
                warnings.put("The same text to the same recipient already passed this check in the last 24 hours; make sure it is not a duplicate")
            }
            // Only a hash and the verdict are kept, never the text.
            runCatching {
                log.parentFile?.mkdirs()
                log.writeText(JSONArray((recent + JSONObject().put("hash", digest).put("at", now).put("passed", !blocked)
                    .put("channel", a["channel"].orEmpty().take(20))).takeLast(200)).toString())
            }
            val result = JSONObject().put("verdict", if (blocked) "BLOCK" else "PASS").put("checks", checks).put("warnings", warnings)
                // The tool can see that the user answered, never whether the answer was a yes.
                .put("approval", "UNVERIFIED")
                .apply {
                    if (userReplies.length() > 0) put("user_replies", userReplies)
                    if (repliesCut) put("user_replies_note", "Only the 5 newest replies since the draft are shown; read the chat for earlier ones.")
                }
                .put("next", if (blocked) "Do not send. Fix what the failed checks say."
                    else if (talk) "Send only if the newest of user_replies still clearly approves this text; if any later reply hesitates or says no, do not send and ask again."
                    else "Send only if the user's task instruction clearly asks for this message.")
            ToolResult(PRESEND, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(PRESEND, e) }
    }
}
