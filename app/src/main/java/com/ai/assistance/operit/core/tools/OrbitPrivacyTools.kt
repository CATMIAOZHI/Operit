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
import com.ai.assistance.operit.features.orbit.OrbitApprovals
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
  - 「忘掉」是把你存的这件事都清掉，不是改一条笔记。改错的事实、换个偏好，用记忆工具改就行。
  - 先 plan（只读）：列出记忆、用户资料、文件、你学到的技能、目标、定时任务、动态和点子里有这件事的地方，以及怎么处理（edit 删掉那几行、delete 整条删掉、stop 停掉）。SOUL.md 和 IDENTITY.md 里的只列出来（user_review），请用户自己改。
  - 用日常说法告诉用户范围，说清删了不能撤销，问一句是否确认。不要复述要忘掉的内容，用「那件事」指代。
  - 用户明确同意后才 confirm；含糊或只同意一部分不算。完成后说清掉了哪些，remaining 里还剩的如实说。
  - 聊天原话不会删，用户想删可以在聊天列表里自己删那段聊天。
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
        description = "可选：以用户的名义往外发消息、邮件、评论之前自查隐私、密码、内容是否定稿、收件人是否明确。",
        parametersStructured = listOf(
            ToolParameterSchema("text", description = "准备发出去的完整正文", required = true),
            ToolParameterSchema("recipient", description = "发给谁：名字、邮箱、号码或群名", required = true),
            ToolParameterSchema("channel", description = "通过什么发：email/sms/wechat/qq/github 或其他具体渠道名；传 approval_id 时必填", required = false),
            ToolParameterSchema("approval_id", description = "可选：orbit_request_approval 已获批准的编号；申请必须写全本次正文、收件人和渠道，且来自当前聊天。有此参数时 channel 必填", required = false),
        ),
        details = """
  - 拿不准时用它自查一下，不是每次发送都必须调用。verdict 是 BLOCK 就不要发，按 checks 里的原因处理（改正文、问清收件人）。
  - 它不替用户批准。用户明确让你发、说清了发什么和发给谁，这本身就是同意；正文是你自己写的或拿不准时，先给用户看草稿，或用 orbit_request_approval 申请，批准后可传 approval_id 核对正文、收件人和渠道。
  - 返回的 user_replies 是你给用户看过这段正文之后、用户说的话（最后一条最新）。其中有“等等”“不要发”或提了修改，就不要发，先问清楚。
  - warnings 不拦截，但要想一想：比如正文里有电话号码、身份证号，确认是用户想发的。
""",
    )

    private fun args(tool: AITool) = tool.parameters.associate { it.name to it.value }
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
                        .put("places", JSONArray(hits.take(OrbitForget.SHOWN).map { it.json() }))
                        .put("scanned", JSONArray(OrbitForget.SCANNED))
                        .put("next", if (hits.isEmpty()) "Nothing was found. Tell the user, or plan again with other terms."
                            else "Tell the user the scope in everyday words, say it cannot be undone, and ask once for confirmation.")
                }
                "confirm" -> {
                    val lastUser = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(chatId, 30)
                        .firstOrNull { it.sender == "user" }?.timestamp ?: 0L
                    val outcome = OrbitForget.confirm(context, orbit, chatId, a["plan_id"]?.trim().orEmpty(), lastUser)
                    JSONObject().put("cleaned", JSONObject(outcome.cleaned as Map<*, *>))
                        .put("remaining", JSONArray(outcome.remaining.take(OrbitForget.SHOWN).map { it.json() }))
                        .put("failed", JSONArray(outcome.failed))
                        .put("note", "Chat messages are not deleted. Places marked user_review are for the user to edit. " +
                            "Report categories only; never repeat the information.")
                }
                "cancel" -> { OrbitForget.cancel(orbit.id); JSONObject().put("cancelled", true) }
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
    private val stopSending = Regex(
        """别发|不要发|不发了|先不发|暂不发|取消|等等|等一下|先等等|停下|撤回|改一下|先改|\b(?:cancel|stop|wait|hold on|hold off|do not send|don['’]?t send|not yet)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun channelNames(channel: String): List<String> = when (channel.lowercase()) {
        "email" -> listOf("email", "e-mail", "邮件", "邮箱")
        "sms" -> listOf("sms", "短信")
        "wechat" -> listOf("wechat", "微信")
        "qq" -> listOf("qq")
        "github" -> listOf("github")
        else -> listOf(channel.lowercase()).filter { it.isNotBlank() && it != "other" }
    }
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
            check("clear recipient", recipient.isNotEmpty() && recipient.lowercase() !in vague,
                if (recipient.isEmpty()) "No recipient" else if (recipient.lowercase() in vague) "Say exactly who receives it" else "OK")
            privacyPatterns.forEach { (label, regex) ->
                val found = regex.findAll(text).map { it.value }.filterNot { label == "email address" && it.equals(recipient, true) }.count()
                if (found > 0) warnings.put("The text contains $found $label(s); make sure the user wants to send them")
            }
            val talk = caller?.let { OrbitPreferences.getInstance(context).current().orbitForChat(it) } != null
            val userReplies = JSONArray()
            var repliesCut = false
            var approval = "UNVERIFIED"
            var approvalBasis = "No approval card was given. The user's clear request to send this is their OK; this check does not approve for them."
            var approvalNote = ""
            if (talk) {
                val messages = ChatHistoryManager.getInstance(context).loadChatMessagesDesc(caller!!, 40)
                // Quote marks, bullets and bold around a draft shown in the chat do not make it a different text.
                fun plain(value: String) = OrbitForget.normalize(value.lines()
                    .joinToString(" ") { it.trimStart().trimStart('>').trim() }.replace(Regex("[*_`~]"), ""))
                val full = plain(text)
                val probe = full.take(80)
                fun said(m: com.ai.assistance.operit.data.model.ChatMessage) = plain(ChatUtils.removeThinkingContent(m.content))
                val approvalId = a["approval_id"]?.trim().orEmpty()
                var since: Long? = null
                if (approvalId.isNotEmpty()) {
                    val card = OrbitApprovals.find(context, orbit.id, approvalId)
                    val approvedHere = card?.kind == OrbitApprovals.REQUEST && card.chatId == caller &&
                        card.state == OrbitApprovals.APPROVED && card.decidedAt > 0L && card.action.isEmpty()
                    check("approved card in this chat", approvedHere,
                        if (approvedHere) "Approval $approvalId belongs to this Orbit and chat and is approved"
                        else "Use an approved request from this chat for an action Orbit will carry out")
                    if (approvedHere && card != null) {
                        val covered = plain(card.title + "\n" + card.detail)
                        // A channel or recipient mentioned only inside the body is not the delivery target.
                        val delivery = covered.replace(full, " ")
                        val rawCovered = OrbitForget.normalize(card.title + "\n" + card.detail)
                        val recipientCovered = recipient.isNotBlank() &&
                            Regex("(?<![a-z0-9._%+@-])${Regex.escape(OrbitForget.normalize(recipient))}(?![a-z0-9._%+@-])")
                                .containsMatchIn(rawCovered) && delivery.contains(plain(recipient))
                        check("approved text", covered.contains(full),
                            if (covered.contains(full)) "The approval contains this exact draft"
                            else "The approval does not contain this complete draft; request approval for the final version")
                        check("approved recipient", recipientCovered,
                            if (recipientCovered) "The approval names this recipient"
                            else "The approval does not name this recipient; ask again")
                        val channel = a["channel"]?.trim().orEmpty()
                        val channelCovered = channelNames(channel).any { delivery.contains(it) }
                        check("approved channel", channelCovered,
                            if (channelCovered) "The approval names channel $channel"
                            else "Give a concrete channel and name it explicitly in the approval outside the message body")
                        val completeHistory = messages.isNotEmpty() &&
                            (messages.size < 40 || messages.last().timestamp <= card.decidedAt)
                        check("replies after approval checked", completeHistory,
                            if (completeHistory) "The recent chat includes the whole period after approval"
                            else "The approval predates the recent chat window; request fresh approval so later changes cannot be missed")
                        since = card.decidedAt
                        approval = "APPROVED"
                        approvalBasis = "Approval card $approvalId covers this text, recipient and channel in this chat."
                        approvalNote = card.note
                        if (approvalNote.isNotBlank()) {
                            approval = "UNVERIFIED"
                            approvalBasis = "The card was approved with an extra note; check approval_note for changes or conditions before sending."
                        }
                        check("approval note unchanged", !stopSending.containsMatchIn(card.note),
                            if (stopSending.containsMatchIn(card.note)) "The user's approval note asks to wait, cancel or change it; ask again"
                            else "The approval note does not ask to stop or change it")
                    }
                } else {
                    // A draft shown in the chat is not required; when there is one, what the user said after it still counts.
                    val shown = messages.firstOrNull { it.sender == "ai" && said(it).contains(probe) }
                        ?.takeIf { said(it).contains(full) }
                    since = shown?.timestamp
                }
                // Hidden approval notices are not ordinary user replies; later visible changes still matter.
                val replies = since?.let { at -> messages.filter {
                    it.sender == "user" && it.timestamp > at &&
                        it.displayMode == com.ai.assistance.operit.data.model.ChatMessageDisplayMode.NORMAL
                }.sortedBy { it.timestamp } }.orEmpty()
                if (replies.isNotEmpty()) {
                    check("no later stop or change", replies.none { stopSending.containsMatchIn(it.content) },
                        if (replies.any { stopSending.containsMatchIn(it.content) }) "The user asked to wait, cancel or change it; ask again"
                        else "Read user_replies for any later changes before sending")
                    if (approvalId.isNotEmpty()) {
                        approval = "UNVERIFIED"
                        approvalBasis = "A card was approved, but the user spoke afterwards; verify their newest reply still permits this exact message."
                    }
                }
                repliesCut = replies.size > 5
                replies.takeLast(5).forEach { m ->
                    val body = m.content.trim()
                    userReplies.put(JSONObject().put("message_id", m.timestamp).put("text", body.take(1000))
                        .apply { if (body.length > 1000) put("cut", true).put("chars", body.length) })
                }
            } else {
                check("approval in a user chat", a["approval_id"].isNullOrBlank(),
                    "approval_id can only be checked in its original chat with the user")
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
                .put("approval", if (blocked) "UNVERIFIED" else approval).put("approval_basis", approvalBasis)
                .apply {
                    if (approvalNote.isNotBlank()) put("approval_note", approvalNote)
                    if (userReplies.length() > 0) put("user_replies", userReplies)
                    if (repliesCut) put("user_replies_note", "Only the 5 newest replies since the draft are shown; read the chat for earlier ones.")
                }
                .put("next", if (blocked) "Do not send. Fix what the failed checks say."
                    else if (approval == "APPROVED") "Send exactly the text to the recipient through the channel covered by the approved card."
                    else if (approvalNote.isNotBlank()) "Check approval_note and any user_replies: send only if they still clearly allow this exact message, recipient and channel; otherwise ask again."
                    else if (talk) "Send if the user clearly asked for this message, recipient and channel; if you wrote it yourself or a later reply hesitates, show it or ask first."
                    else "Send only if the user's task instruction clearly asks for this message.")
            ToolResult(PRESEND, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(PRESEND, e) }
    }
}
