package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository.Board
import com.ai.assistance.operit.features.orbit.OrbitFeedGenerator
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject

/** Orbit's Feed, Ideas and Tracking boards, the tabs at the bottom of the Orbit page (like Muse). */
internal object OrbitBoardTools {
    const val FEED = "orbit_feed"
    const val IDEA = "orbit_idea"
    const val TRACKING = "orbit_tracking"
    val names = setOf(FEED, IDEA, TRACKING)
    private val LIVE_SURFACE = Regex("\"surfaceId\"\\s*:\\s*\"live_")

    val prompts = listOf(
        ToolPrompt(
            name = FEED,
            description = "管理用户「动态」标签页：Orbit 为用户写的信息流。你是作者，内容由动态简介（brief）引导。" +
                "post 发一条；list 查看已有的；delete 删除；move 调整顺序；brief_get / brief_update 读写简介；" +
                "generate 马上在后台写一条新的。平时每 12 小时会自动在后台写一条。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "post/list/delete/move/brief_get/brief_update/generate", required = true),
                ToolParameterSchema("post_id", description = "已有短文编号，delete/move 必填", required = false),
                ToolParameterSchema("title", description = "post：标题，最多80字符", required = false),
                ToolParameterSchema("emoji", description = "post 必填：标题旁显示的一个 emoji，贴合内容，如 📱 🏎️ 🎧", required = false),
                ToolParameterSchema("body", description = "post：正文，几段短文字，最多6000字符；链接写成 [文字](网址)，只引用你真的打开过的", required = false),
                ToolParameterSchema("why_for_you", description = "post：一句话说明为什么给这位用户看，必填", required = false),
                ToolParameterSchema("card", description = "post 可选：先用 dynamic_ui 做好的卡片引用名（如 card-1），显示在正文下面", required = false),
                ToolParameterSchema("brief", description = "brief_update：新的简介全文，最多2000字符；空字符串表示恢复默认简介", required = false),
                ToolParameterSchema("direction", description = "move：up 或 down", required = false),
            ),
            details = """
  - 用户说「多写点 F1、少写加密货币」这类话时，用 brief_get 读出简介，改好后 brief_update。真的改了内容会马上在后台写一条新的。
  - 动态由你的后台任务「动态更新」每 12 小时写一条；用户想改频率时用 orbit_task 改这个任务的时间，不要取消它。
  - 新短文出现在动态页，不会推送，也不会在聊天里说。不要说「已经发好了」，只能说已经开始写。
  - 每条都要写 why_for_you 和 emoji。不要编造链接。
  - 写之前可以搜网页、打开页面查最新信息。表格、对比、图表、可点的清单这类内容，可以先用 dynamic_ui 做一张卡片（不要用 live），
    再把它的引用名填进 card；卡片和正文一起显示。不要把 a2ui-ref 写进 body。
  - list 里的 vote 是用户的反馈：liked 多写这类，disliked 少写这类。
""",
        ),
        ToolPrompt(
            name = IDEA,
            description = "在用户「点子」标签页提出你可以为他做的事或做的东西。用户点「就这么办」只会把 plan 放进他的输入框，" +
                "他发出来你才收到，由你去做。create 新建；list 查看；update 改图标或分类；done 在你真的做完后把卡片标成已做。你不能替用户关掉卡片。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/update/done", required = true),
                ToolParameterSchema("idea_id", description = "update/done：卡片编号（先 list 查）", required = false),
                ToolParameterSchema("title", description = "create：卡片标题，最多80字符", required = false),
                ToolParameterSchema("emoji", description = "create 必填、update 可选：标题旁显示的一个 emoji，贴合内容，如 📦 🛡️ 🎁", required = false),
                ToolParameterSchema("category", description = "create 必填、update 可选：分类，productivity 效率提升、money 财务管理、relationships 人际关系、health 健康与健身、shopping 购物、other 更多点子", required = false),
                ToolParameterSchema("description", description = "create：给用户看的说明：做什么、对他有什么用，最多6000字符", required = false),
                ToolParameterSchema("includes", description = "create：做完会交付的东西，每行一项「名称 | 一句说明」，最多6行", required = false),
                ToolParameterSchema("plan", description = "create：用户选中后你要做的具体事，写给你自己看，最多2000字符", required = false),
                ToolParameterSchema("expires_in_days", "integer", "create：几天后自动下架，1–30，默认14", false),
            ),
            details = """
  - 只提和用户真实情况有关、你现在就做得到的事；先 list，不要重复已有的卡片。
  - 每个点子都要有一个贴合内容的 emoji 和一个分类；同一页尽量不要用同样的 emoji。用户想换图标或分类时用 update。
  - 你的后台任务「每日点子」每天上午 9 点会想新点子；用户想改频率时用 orbit_task 改这个任务的时间，不要取消它。
  - 卡片两周左右自动下架，用户喜欢的会一直留着。用户可以关掉它；你没有关掉卡片的工具，不要说你关掉了。
  - 用户右滑表示喜欢、左滑表示不喜欢，list 里的 vote 就是这个反馈：多提 liked 这类，少提 disliked 这类。
  - list 里 past 是已经下架、关掉或做过的点子和用户当时的反馈，用来了解用户口味，不要再提一样的。
""",
        ),
        ToolPrompt(
            name = TRACKING,
            description = "跟踪你替用户扛着的具体事项（快递、预订、承诺、待回复的事），显示在「目标」页的「我在跟进」里。" +
                "和 orbit_goal 不同：目标是用户自己的长期目标，跟踪是你替他盯着的事。只有进行中和已完成两种状态。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/update/note/complete/reopen/delete", required = true),
                ToolParameterSchema("tracking_id", description = "已有事项编号，除 create/list 外必填", required = false),
                ToolParameterSchema("title", description = "create/update：标题，最多80字符", required = false),
                ToolParameterSchema("detail", description = "create/update：具体情况，最多6000字符", required = false),
                ToolParameterSchema("note", description = "note/complete：一条带日期的进展，最多1000字符", required = false),
            ),
            details = """
  - 有新进展就用 note 记一条；事情办完用 complete。需要定期查看时，另用 orbit_task 建定时任务去查。
  - 用户要求或建错了才 delete，办完的事用 complete，不要删。
""",
        ),
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            fun arg(name: String) = args[name]?.takeIf { it.isNotBlank() } ?: error("Missing $name")
            val requestId = runtime?.callId ?: java.util.UUID.randomUUID().toString()
            val result: Any = when (tool.name) {
                FEED -> {
                    val feed = OrbitBoardRepository(context, orbit.id, Board.FEED)
                    when (args["action"]) {
                        "post" -> {
                            val card = args["card"]?.takeIf { it.isNotBlank() }?.let { reference ->
                                val run = runtime?.dynamicUiRunId ?: error("Cards can only be made inside a running turn")
                                val document = com.ai.assistance.operit.ui.common.dynamicui.CardDelivery.resolve(run, reference)
                                    ?: error("Unknown card $reference. Make it with dynamic_ui in this turn first")
                                // A live card belongs to one chat and updates there; a feed post needs a finished one.
                                require(!LIVE_SURFACE.containsMatchIn(document)) { "Live cards cannot go in the feed; make a normal card" }
                                document
                            }.orEmpty()
                            json(feed.create(arg("title"), arg("body"), arg("why_for_you"), requestId,
                                emoji = emoji(arg("emoji")), card = card), Board.FEED)
                        }
                        "list" -> JSONArray(feed.list().take(30).map { json(it, Board.FEED, brief = true) })
                        "delete" -> { feed.delete(arg("post_id")); "Deleted" }
                        "move" -> { feed.move(arg("post_id"), arg("direction") == "up"); "Moved" }
                        "brief_get" -> OrbitBoardRepository.readBrief(context, orbit.id).ifBlank {
                            "(The user has not written one; the default is used:) " + OrbitFeedGenerator.defaultBrief(context)
                        }
                        "brief_update" -> {
                            // A missing brief is a mistake; only an explicit empty string resets it.
                            val brief = args["brief"] ?: error("Missing brief")
                            val started = OrbitFeedGenerator.saveBrief(context, orbit.id, brief)
                            when {
                                started == false -> "Saved, but the feed routine is paused, so no new post started."
                                brief.isBlank() -> "Reset to the default brief."
                                started == true -> "Saved. A new post is being written in the background."
                                else -> "Saved. The brief did not change."
                            }
                        }
                        "generate" -> if (OrbitFeedGenerator.writeOne(context, orbit.id)) "Started in the background"
                            else error("The feed routine is paused; resume it with orbit_task first")
                        else -> error("Unknown action")
                    }
                }
                IDEA -> {
                    val ideas = OrbitBoardRepository(context, orbit.id, Board.IDEAS)
                    when (args["action"]) {
                        "create" -> {
                            val days = args["expires_in_days"]?.takeIf { it.isNotBlank() }?.toInt() ?: 14
                            require(days in 1..30) { "expires_in_days must be 1 to 30" }
                            json(ideas.create(arg("title"), arg("description"), arg("plan"), requestId,
                                System.currentTimeMillis() + days * 24L * 60 * 60 * 1000, emoji = emoji(arg("emoji")),
                                parts = parts(args["includes"]), category = arg("category")), Board.IDEAS)
                        }
                        "list" -> {
                            val all = ideas.listAll()
                            val showing = ideas.list().filter { it.state == "OPEN" }
                            val ids = showing.map { it.id }.toSet()
                            // What the user answered before still teaches their taste after the card is gone.
                            val answered = all.filter { it.id !in ids && (it.vote != 0 || it.state == "BUILT") }
                            // The done ideas the Ideas tab shows come first, so Orbit can always update them.
                            val past = (answered.filter { it.state == "BUILT" }.sortedByDescending { it.builtAt }.take(10) +
                                answered.sortedByDescending { it.updatedAt }).distinctBy { it.id }.take(40)
                            JSONObject().put("open", JSONArray(showing.map { json(it, Board.IDEAS, brief = true) }))
                                .put("past", JSONArray(past.map {
                                    JSONObject().put("id", it.id).put("title", it.title).put("emoji", it.emoji)
                                        .put("category", OrbitBoardRepository.categoryOf(it))
                                        .put("state", it.state).put("vote", voteName(it.vote))
                                }))
                        }
                        "update" -> {
                            val emoji = args["emoji"]?.takeIf { it.isNotBlank() }
                            val category = args["category"]?.takeIf { it.isNotBlank() }
                            require(emoji != null || category != null) { "Give emoji or category to change" }
                            json(ideas.update(arg("idea_id"), emoji = emoji, category = category), Board.IDEAS)
                        }
                        "done" -> json(ideas.update(arg("idea_id"), state = "BUILT"), Board.IDEAS)
                        else -> error("Unknown action")
                    }
                }
                TRACKING -> {
                    val tracking = OrbitBoardRepository(context, orbit.id, Board.TRACKING)
                    when (args["action"]) {
                        "create" -> json(tracking.create(arg("title"), args["detail"].orEmpty(), requestId = requestId), Board.TRACKING)
                        "list" -> JSONArray(tracking.list().map { json(it, Board.TRACKING) })
                        "update" -> json(tracking.update(arg("tracking_id"), title = args["title"]?.takeIf { it.isNotBlank() },
                            body = args["detail"]), Board.TRACKING)
                        "note" -> json(tracking.update(arg("tracking_id"), note = arg("note")), Board.TRACKING)
                        "complete" -> json(tracking.update(arg("tracking_id"), state = "DONE", note = args["note"]), Board.TRACKING)
                        "reopen" -> json(tracking.update(arg("tracking_id"), state = "ACTIVE", note = args["note"]), Board.TRACKING)
                        "delete" -> { tracking.delete(arg("tracking_id")); "Deleted" }
                        else -> error("Unknown action")
                    }
                }
                else -> error("Unknown tool")
            }
            ToolResult(tool.name, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            ToolResult(tool.name, false, StringResultData(""), message)
        }
    }

    /** "Name | one line" per line; a line without a bar is just a name. */
    private fun parts(value: String?): List<OrbitBoardRepository.Part> =
        value.orEmpty().lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
            .map { line ->
                val bar = line.indexOfFirst { it == '|' || it == '｜' }
                if (bar < 0) OrbitBoardRepository.Part(line, "")
                else OrbitBoardRepository.Part(line.substring(0, bar).trim(), line.substring(bar + 1).trim())
            }

    private fun voteName(vote: Int) = when {
        vote > 0 -> "liked"
        vote < 0 -> "disliked"
        else -> "none"
    }

    /** One emoji, or a clear error the model can fix. */
    private fun emoji(value: String): String =
        OrbitBoardRepository.emojiOf(value).ifEmpty { error("emoji must be one emoji, like 📦") }

    private fun json(item: OrbitBoardRepository.Item, board: Board, brief: Boolean = false): JSONObject {
        val body = if (brief) item.body.take(300) else item.body
        val value = JSONObject().put("id", item.id).put("title", item.title).put("state", item.state)
            .put("createdAt", item.createdAt)
        when (board) {
            Board.FEED -> value.put("emoji", item.emoji).put("body", body).put("why_for_you", item.extra)
                .put("vote", voteName(item.vote)).put("has_card", item.card)
            Board.IDEAS -> value.put("emoji", item.emoji).put("description", body).put("plan", item.extra)
                .put("category", item.category)
                .put("includes", JSONArray(item.parts.map { "${it.title} | ${it.detail}".trimEnd(' ', '|') }))
                .put("vote", voteName(item.vote)).put("expiresAt", item.expiresAt)
            Board.TRACKING -> value.put("detail", body).put("notes", JSONArray(item.notes.takeLast(10).map {
                JSONObject().put("at", it.at).put("text", it.text)
            }))
            Board.GOAL_NOTES -> value.put("text", body).put("goal_id", item.extra)
        }
        return value
    }
}
