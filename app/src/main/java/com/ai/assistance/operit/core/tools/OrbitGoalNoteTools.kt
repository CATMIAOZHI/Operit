package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository.Board
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import org.json.JSONArray
import org.json.JSONObject

/** Progress entries and briefings on the user's goals, shown under each goal on the Goals tab. */
internal object OrbitGoalNoteTools {
    const val NAME = "orbit_goal_note"

    val prompt = ToolPrompt(
        name = NAME,
        description = "目标的进展记录和简报，显示在目标页每个目标下面。log 记一条带日期的进展；list 查看某个目标的记录和简报；" +
            "delete 删除一条（用户要求时）；brief 保存一封简报，只有后台的目标简报任务能用。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "log/list/delete/brief", required = true),
            ToolParameterSchema("goal_id", description = "目标编号（来自 orbit_goal list），log/list/brief 必填", required = false),
            ToolParameterSchema("note_id", description = "delete：记录或简报的编号", required = false),
            ToolParameterSchema("title", description = "brief：一句话附言，最多80字符", required = false),
            ToolParameterSchema("text", description = "log：发生了什么；brief：简报正文，最多6000字符", required = false),
        ),
        details = """
  - 用户告诉你某个目标有了进展（完成了一步、遇到了问题、决定暂停一阵），用 log 记一条，目标就有了自己的历史。
  - 简报由后台每天早上写，你不能当场生成，也不要承诺什么时候会有。用户问简报内容时，只根据 list 里真的存在的简报回答。
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            val dao = AppDatabase.getDatabase(context).orbitTaskDao()
            val board = OrbitBoardRepository(context, orbit.id, Board.GOAL_NOTES)
            suspend fun goalId(): String {
                val id = args["goal_id"]?.trim().orEmpty()
                require(id.isNotEmpty()) { "Missing goal_id" }
                val goal = dao.goal(id)
                require(goal != null && goal.orbitId == orbit.id && goal.state != "CANCELLED") { "No such goal: $id" }
                return id
            }
            val result: Any = when (args["action"]) {
                "log" -> {
                    val text = args["text"]?.trim().orEmpty()
                    require(text.isNotEmpty()) { "Missing text" }
                    json(board.create(text.lineSequence().first().take(OrbitBoardRepository.MAX_TITLE), text, goalId(),
                        runtime?.callId ?: java.util.UUID.randomUUID().toString(), state = "LOG"))
                }
                "brief" -> {
                    // Briefings come from the background routine only, as in Muse.
                    val chatId = runtime?.callerChatId
                    require(chatId != null && com.ai.assistance.operit.features.orbit.OrbitTaskExecution.resolve(context, chatId) != null) {
                        "Briefings are written by the background goal briefing; use log for progress in chat"
                    }
                    val text = args["text"]?.trim().orEmpty()
                    require(text.isNotEmpty()) { "Missing text" }
                    json(board.create(args["title"]?.takeIf { it.isNotBlank() } ?: text.lineSequence().first().take(80), text,
                        goalId(), runtime?.callId ?: java.util.UUID.randomUUID().toString(), state = "BRIEF"))
                }
                "list" -> {
                    val id = goalId()
                    // Short text keeps the list small; the goal briefing reads the latest ones in full from its source.
                    JSONArray(board.list().filter { it.extra == id }.take(30).map { json(it).put("text", it.body.take(300)) })
                }
                "delete" -> {
                    val id = args["note_id"]?.trim().orEmpty()
                    require(board.list().any { it.id == id }) { "No such note: $id" }
                    board.delete(id)
                    "Deleted"
                }
                else -> error("Unknown action")
            }
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }

    private fun json(item: OrbitBoardRepository.Item) = JSONObject().put("note_id", item.id).put("goal_id", item.extra)
        .put("kind", if (item.state == "BRIEF") "briefing" else "progress").put("title", item.title)
        .put("text", item.body).put("createdAt", item.createdAt)
}
