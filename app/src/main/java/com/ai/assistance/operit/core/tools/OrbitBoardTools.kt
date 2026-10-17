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

    val prompts = listOf(
        ToolPrompt(
            name = FEED,
            description = "管理用户「动态」标签页里的短文。你是作者，内容由动态简介（brief）引导。" +
                "post 发一条；list 查看已有的；delete 删除；move 调整顺序；brief_get / brief_update 读写简介；" +
                "generate 在后台按简介写一条新的。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "post/list/delete/move/brief_get/brief_update/generate", required = true),
                ToolParameterSchema("post_id", description = "已有短文编号，delete/move 必填", required = false),
                ToolParameterSchema("title", description = "post：标题，最多80字符", required = false),
                ToolParameterSchema("body", description = "post：正文，几段短文字，最多6000字符；只引用你真的打开过的链接", required = false),
                ToolParameterSchema("why_for_you", description = "post：一句话说明为什么给这位用户看，必填", required = false),
                ToolParameterSchema("brief", description = "brief_update：新的简介全文，最多2000字符；空字符串表示清空并停止每日动态", required = false),
                ToolParameterSchema("direction", description = "move：up 或 down", required = false),
            ),
            details = """
  - 用户说「多写点 F1、少写加密货币」这类话时，用 brief_get 读出简介，改好后 brief_update。真的改了内容会马上在后台写一条新的，并每天早上写一条。
  - 新短文出现在动态页，不会推送，也不会在聊天里说。不要说「已经发好了」，只能说已经开始写。
  - 每条都要写 why_for_you。不要编造链接。
""",
        ),
        ToolPrompt(
            name = IDEA,
            description = "在用户「点子」标签页提出你可以为他做的事或做的东西。用户点「就这么办」后，" +
                "卡片里的 plan 会作为一条消息发给你，由你去做。create 新建；list 查看。你不能替用户关掉卡片。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list", required = true),
                ToolParameterSchema("title", description = "create：卡片标题，最多80字符", required = false),
                ToolParameterSchema("description", description = "create：给用户看的说明：做什么、对他有什么用，最多6000字符", required = false),
                ToolParameterSchema("plan", description = "create：用户选中后你要做的具体事，写给你自己看，最多2000字符", required = false),
                ToolParameterSchema("expires_in_days", "integer", "create：几天后自动下架，1–30，默认14", false),
            ),
            details = """
  - 只提和用户真实情况有关、你现在就做得到的事；先 list，不要重复已有的卡片。
  - 卡片两周左右自动下架。用户可以长按关掉它；你没有关掉卡片的工具，不要说你关掉了。
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
                        "post" -> json(feed.create(arg("title"), arg("body"), arg("why_for_you"), requestId), Board.FEED)
                        "list" -> JSONArray(feed.list().take(30).map { json(it, Board.FEED, brief = true) })
                        "delete" -> { feed.delete(arg("post_id")); "Deleted" }
                        "move" -> { feed.move(arg("post_id"), arg("direction") == "up"); "Moved" }
                        "brief_get" -> OrbitBoardRepository.readBrief(context, orbit.id).ifBlank { "(empty)" }
                        "brief_update" -> {
                            // A missing brief is a mistake; only an explicit empty string clears it.
                            val brief = args["brief"] ?: error("Missing brief")
                            OrbitFeedGenerator.saveBrief(context, orbit.id, brief)
                            if (brief.isBlank()) "Cleared. The daily feed post is stopped."
                            else "Saved. A changed brief starts a new post in the background."
                        }
                        "generate" -> if (OrbitFeedGenerator.writeOne(context, orbit.id)) "Started in the background"
                            else error("The feed brief is empty; set it with brief_update first")
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
                                System.currentTimeMillis() + days * 24L * 60 * 60 * 1000), Board.IDEAS)
                        }
                        "list" -> JSONArray(ideas.list().map { json(it, Board.IDEAS, brief = true) })
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

    private fun json(item: OrbitBoardRepository.Item, board: Board, brief: Boolean = false): JSONObject {
        val body = if (brief) item.body.take(300) else item.body
        val value = JSONObject().put("id", item.id).put("title", item.title).put("state", item.state)
            .put("createdAt", item.createdAt)
        when (board) {
            Board.FEED -> value.put("body", body).put("why_for_you", item.extra).put("liked", item.liked)
            Board.IDEAS -> value.put("description", body).put("plan", item.extra).put("expiresAt", item.expiresAt)
            Board.TRACKING -> value.put("detail", body).put("notes", JSONArray(item.notes.takeLast(10).map {
                JSONObject().put("at", it.at).put("text", it.text)
            }))
            Board.GOAL_NOTES -> value.put("text", body).put("goal_id", item.extra)
        }
        return value
    }
}
