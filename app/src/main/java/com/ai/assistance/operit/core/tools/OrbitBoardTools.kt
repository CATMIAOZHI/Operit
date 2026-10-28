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
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Orbit's Feed, Ideas and Tracking boards, the tabs at the bottom of the Orbit page. */
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
                "post 发一条；list 查看已有的（正文只给前300字）；read 读一条全文；delete 删除；move 调整顺序；brief_get / brief_update 读写简介；" +
                "generate 马上在后台写一条新的。平时每 12 小时会自动在后台写一条。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "post/list/read/delete/move/brief_get/brief_update/generate", required = true),
                ToolParameterSchema("post_id", description = "list 返回的 post_id，read/delete/move 必填", required = false),
                ToolParameterSchema("offset", "integer", "list 可选：从第几条开始（从0开始），默认0；每次最多30条", false),
                ToolParameterSchema("title", description = "post：标题，最多80字符", required = false),
                ToolParameterSchema("emoji", description = "post 必填：标题旁显示的一个 emoji，贴合内容，如 📱 🏎️ 🎧", required = false),
                ToolParameterSchema("body", description = "post：正文，几段短文字，最多6000字符；链接写成 [文字](网址)，只引用你真的打开过的", required = false),
                ToolParameterSchema("why_for_you", description = "post：一句话说明为什么给这位用户看，必填", required = false),
                ToolParameterSchema("card", description = "post 可选：先用 dynamic_ui 做好的卡片引用名（如 card-1），显示在正文下面", required = false),
                ToolParameterSchema("images", description = "post 可选：配图，最多9张，每行一个。可以是 files/ 下的图片、App 自己目录或 Download/Operit 里的图片路径，或图片网址 https://…", required = false),
                ToolParameterSchema("brief", description = "brief_update：新的简介全文，最多2000字符；空字符串表示恢复默认简介", required = false),
                ToolParameterSchema("direction", description = "move：up 或 down", required = false),
            ),
            details = """
  - 用户说「多写点 F1、少写加密货币」这类话时，用 brief_get 读出简介，改好后 brief_update。真的改了内容会马上在后台写一条新的。
  - 动态由你的后台任务「动态更新」每 12 小时写一条；用户想改频率时用 orbit_task 改这个任务的时间，不要取消它。
  - post 会马上出现在动态页，不会推送，也不会在聊天里说。generate 和改简介触发的新动态是在后台写，不要说「已经发好了」，只能说已经开始写。
  - 每条都要写 why_for_you 和 emoji。不要编造链接。
  - 素材可以来自网上（搜网页、打开页面），也可以来自手机上和用户有关的事（短信、通知、日程、邮件等），比如短信里的快递取件码。
    表格、对比、图表、可点的清单、取件提醒这类内容，可以先用 dynamic_ui 做一张卡片（不要用 live），
    再把它的引用名填进 card；卡片和正文一起显示。不要把 a2ui-ref 写进 body。
  - 像发朋友圈一样，可以给动态配图（images，最多9张）：网页上看到合适的图，直接填图片网址，或先存成文件再填路径。
    App 会把图下载、压小后存进这条动态，之后原图变了也不影响。只放你确认过内容的图：不确定是不是那张，先用 read_file 看一眼。
    图片网址要填图片本身的地址（打开后就是那张图），不要填网页地址；下载失败或不是图片时整条 post 会失败，换一张再发。
  - 用户通过 👍 点赞、👎 点踩按钮反馈；list 里的 vote 中，liked 表示点赞，多写这类；disliked 表示点踩，少写这类。
    动态不支持左右滑动反馈，不要叫用户左右滑；右滑喜欢、左滑不喜欢是点子页的操作。
""",
        ),
        ToolPrompt(
            name = IDEA,
            description = "在用户「点子」标签页提出你可以为他做的事或做的东西。用户点「就这么办」只会把 plan 放进他的输入框，" +
                "他发出来你才收到，由你去做。create 新建；list 查看；update 改图标或分类；done 在你真的做完后把卡片标成已做。你不能替用户关掉卡片。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/read/update/done", required = true),
                ToolParameterSchema("idea_id", description = "list 返回的 idea_id，read/update/done 必填", required = false),
                ToolParameterSchema("title", description = "create：卡片标题，最多80字符", required = false),
                ToolParameterSchema("emoji", description = "create 必填、update 可选：标题旁显示的一个 emoji，贴合内容，如 📦 🛡️ 🎁", required = false),
                ToolParameterSchema("category", description = "create 必填、update 可选：分类，productivity 效率提升、money 财务管理、relationships 人际关系、health 健康与健身、shopping 购物、other 更多点子", required = false),
                ToolParameterSchema("description", description = "create：给用户看的说明：做什么、对他有什么用，最多6000字符", required = false),
                ToolParameterSchema("includes", description = "create：做完会交付的东西，每行一项「名称 | 一句说明」，最多6行", required = false),
                ToolParameterSchema("plan", description = "create：用户选中后你要做的具体事，写给你自己看，最多2000字符", required = false),
                ToolParameterSchema("feasibility", "integer", "create 必填：1–5，你现在真能做成的把握（工具、权限、信息够不够）", false),
                ToolParameterSchema("personal_fit", "integer", "create 必填：1–5，和这位用户的真实情况、目标、口味有多贴合", false),
                ToolParameterSchema("novelty", "integer", "create 必填：1–5，有多新：和已有、过去的点子不重复，用户自己还没想到", false),
                ToolParameterSchema("rationale", description = "create 必填：一两句话，为什么现在给这位用户提这个，最多1000字符", required = false),
                ToolParameterSchema("sources", description = "create 必填：依据从哪来，每行一条，如聊天消息 id、memory.md、目标名、你打开过的链接；最多6条", required = false),
                ToolParameterSchema("expires_in_days", "integer", "create：几天后自动下架，1–30，默认14", false),
                ToolParameterSchema("offset", "integer", "list 可选：past 从第几条开始（从0开始），用上次返回的 past_next_offset；每次最多30条", false),
            ),
            details = """
  - 只提和用户真实情况有关、你现在就做得到的事；先 list，不要重复已有的卡片。
  - create 前给点子打三项分（feasibility、personal_fit、novelty，各 1–5），任何一项低于 3 就别提。
    rationale 和 sources 必须有真实依据；找不到依据的点子不要提。用户问「为什么提这个」时用 read 看 rationale 和 sources。
  - list 里的 description 只给前300字，要全文（含 plan 和 includes）用 read。
  - 每个点子都要有一个贴合内容的 emoji 和一个分类；同一页尽量不要用同样的 emoji。用户想换图标或分类时用 update。
  - 你的后台任务「每日点子」每天上午 9 点会想新点子；用户想改频率时用 orbit_task 改这个任务的时间，不要取消它。
  - 卡片两周左右自动下架，用户喜欢的会一直留着。用户可以关掉它；你没有关掉卡片的工具，不要说你关掉了。
  - 用户右滑表示喜欢、左滑表示不喜欢，list 里的 vote 就是这个反馈：多提 liked 这类，少提 disliked 这类。
  - list 里 past 是已经不在点子页的点子：gone_reason 是 built（你做过）、dismissed（用户关掉）或 expired（到期下架），vote 是用户当时的反馈。
    用来了解用户口味，不要再提一样的。past 一次最多30条，past_next_offset 不为空时用 offset 往后翻。
    没回应的旧点子超过 60 条时，最旧的会被删掉，不会出现在 past 里。
""",
        ),
        ToolPrompt(
            name = TRACKING,
            description = "跟踪你替用户扛着的具体事项（快递、预订、承诺、待回复的事），显示在「目标」页的「我在跟进」里。" +
                "和 orbit_goal 不同：目标是用户自己的长期目标，跟踪是你替他盯着的事。只有进行中和已完成两种状态。",
            parametersStructured = listOf(
                ToolParameterSchema("action", description = "create/list/read/update/note/complete/reopen/delete", required = true),
                ToolParameterSchema("tracking_id", description = "list 返回的 tracking_id，除 create/list 外必填", required = false),
                ToolParameterSchema("title", description = "create/update：标题，最多80字符", required = false),
                ToolParameterSchema("detail", description = "create/update：具体情况，最多6000字符", required = false),
                ToolParameterSchema("note", description = "note/complete：一条带日期的进展，最多1000字符", required = false),
            ),
            details = """
  - 有新进展就用 note 记一条；事情办完用 complete。需要定期查看时，另用 orbit_task 建定时任务去查。
  - list 只给每件事的前300字和最近3条进展（notes_total 是总条数）；read 给全文和全部进展（最多最近100条）。
  - 用户要求或建错了才 delete，办完的事用 complete，不要删。
""",
        ),
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val runtime = ToolExecutionManager.currentToolRuntimeContext()
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime?.callerChatId)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value }
            val action = args["action"]?.trim().orEmpty()
            fun arg(name: String) = args[name]?.takeIf { it.isNotBlank() } ?: error("$action needs $name")
            val requestId = runtime?.callId ?: java.util.UUID.randomUUID().toString()
            // An id that is not there says how to find the right one.
            suspend fun item(repository: OrbitBoardRepository, key: String): OrbitBoardRepository.Item {
                val id = arg(key)
                return repository.listAll().firstOrNull { it.id == id }
                    ?: error("No item with $key \"$id\"; call ${tool.name} with action=list to find it")
            }
            val result: Any = when (tool.name) {
                FEED -> {
                    val feed = OrbitBoardRepository(context, orbit.id, Board.FEED)
                    when (action) {
                        "post" -> {
                            // A replayed call returns the post it already made, without fetching its pictures again.
                            feed.madeBy(requestId)?.let { return@runBlockingIoPreservingToolRuntimeContext ToolResult(
                                tool.name, true, StringResultData(json(it, Board.FEED).toString())) }
                            // A card reference written into the body is moved to card: the post
                            // shows its card under the text, never inside it.
                            val rawBody = arg("body")
                            val fenced = OrbitBoardRepository.cardFences(rawBody).distinct()
                            val named = args["card"]?.trim()?.takeIf { it.isNotEmpty() }
                            require(fenced.size <= 1 && (named == null || fenced.all { it == named })) {
                                "A post shows one card, under its text. Put that card's name in card and leave " +
                                    "a2ui-ref out of body (body names: ${fenced.joinToString()}" +
                                    (named?.let { "; card: $it" } ?: "") + ")"
                            }
                            val body = OrbitBoardRepository.withoutCardFences(rawBody)
                            require(body.isNotBlank()) { "post needs body text besides the card" }
                            val card = (named ?: fenced.firstOrNull())?.let { reference ->
                                val run = runtime?.dynamicUiRunId ?: error("Cards can only be made inside a running turn")
                                val document = com.ai.assistance.operit.ui.common.dynamicui.CardDelivery.resolve(run, reference)
                                    ?: error("Unknown card $reference. Make it with dynamic_ui in this turn first")
                                // A live card belongs to one chat and updates there; a feed post needs a finished one.
                                require(!LIVE_SURFACE.containsMatchIn(document)) { "Live cards cannot go in the feed; make a normal card" }
                                document
                            }.orEmpty()
                            val images = feedImages(context, orbit.id, args["images"])
                            json(feed.create(arg("title"), body, arg("why_for_you"), requestId,
                                emoji = emoji(arg("emoji")), card = card, images = images), Board.FEED)
                        }
                        "list" -> page(feed.list(), args["offset"]) { json(it, Board.FEED, brief = true) }
                        "read" -> json(item(feed, "post_id"), Board.FEED)
                        "delete" -> {
                            val post = item(feed, "post_id")
                            JSONObject().put("post_id", post.id).put("title", post.title).put("deleted", feed.delete(post.id))
                        }
                        "move" -> {
                            val up = when (arg("direction").trim().lowercase()) {
                                "up" -> true
                                "down" -> false
                                else -> error("direction must be up or down; got \"${args["direction"]}\"")
                            }
                            val post = item(feed, "post_id")
                            val (from, to) = feed.move(post.id, up)
                            JSONObject().put("post_id", post.id).put("old_position", from).put("new_position", to)
                                .put("changed", from != to)
                                .apply { if (from == to) put("note", "Already at the ${if (up) "top" else "bottom"}") }
                        }
                        "brief_get" -> OrbitBoardRepository.readBrief(context, orbit.id).ifBlank {
                            "(The user has not written one; the default is used:) " + OrbitFeedGenerator.defaultBrief(context)
                        }
                        "brief_update" -> {
                            // A missing brief is a mistake; only an explicit empty string resets it.
                            val brief = args["brief"] ?: error("brief_update needs brief: the whole new feed brief")
                            val started = OrbitFeedGenerator.saveBrief(context, orbit.id, brief)
                            val now = OrbitFeedGenerator.effectiveBrief(context, orbit.id)
                            JSONObject().put("saved", true).put("changed", started != null).put("reset_to_default", brief.isBlank())
                                .put("new_post_started", started == true)
                                .put("brief", if (now.length <= 300) now else now.take(300) + "…")
                                .put("note", when (started) {
                                    false -> "The feed routine is paused, so no new post started."
                                    true -> "A new post is being written in the background; it is not there yet."
                                    else -> "The brief did not change."
                                })
                        }
                        "generate" -> if (OrbitFeedGenerator.writeOne(context, orbit.id)) JSONObject().put("started", true)
                                .put("note", "Being written in the background; it shows on the feed when done.")
                            else error("The feed routine is paused; resume it with orbit_task first")
                        else -> error("Unknown action \"$action\"; use post, list, read, delete, move, brief_get, brief_update or generate")
                    }
                }
                IDEA -> {
                    val ideas = OrbitBoardRepository(context, orbit.id, Board.IDEAS)
                    when (action) {
                        "create" -> {
                            val days = args["expires_in_days"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                it.toIntOrNull() ?: error("expires_in_days must be a whole number from 1 to 30; got \"$it\"")
                            } ?: 14
                            require(days in 1..30) { "expires_in_days must be 1 to 30" }
                            fun score(name: String): Int {
                                val value = arg(name).trim()
                                return value.toIntOrNull()?.takeIf { it in 1..5 }
                                    ?: error("$name must be a whole number from 1 to 5; got \"$value\"")
                            }
                            val scores = OrbitBoardRepository.Scores(score("feasibility"), score("personal_fit"), score("novelty"))
                            val sources = arg("sources").lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
                                .filter { it.isNotEmpty() }
                            require(sources.isNotEmpty()) { "sources needs at least one real source, one per line" }
                            json(ideas.create(arg("title"), arg("description"), arg("plan"), requestId,
                                System.currentTimeMillis() + days * 24L * 60 * 60 * 1000, emoji = emoji(arg("emoji")),
                                parts = parts(args["includes"]), category = arg("category"), scores = scores,
                                rationale = arg("rationale"), sources = sources), Board.IDEAS, brief = true)
                        }
                        "list" -> {
                            val all = ideas.listAll()
                            val showing = ideas.list().filter { it.state == "OPEN" }
                            val ids = showing.map { it.id }.toSet()
                            // Every card that left the tab, and why, still teaches the user's taste.
                            val gone = all.filter { it.id !in ids }
                            // The done ideas the Ideas tab shows come first, so Orbit can always update them.
                            val past = (gone.filter { it.state == "BUILT" }.sortedByDescending { it.builtAt }.take(10) +
                                gone.sortedByDescending { it.updatedAt }).distinctBy { it.id }
                            // offset pages through past ideas; the open ones always come in full.
                            val pastPage = page(past, args["offset"]) {
                                JSONObject().put("idea_id", it.id).put("title", it.title).put("emoji", it.emoji)
                                    .put("category", OrbitBoardRepository.categoryOf(it))
                                    .put("gone_reason", when (it.state) {
                                        "BUILT" -> "built"
                                        "DISMISSED" -> "dismissed"
                                        else -> "expired"
                                    })
                                    .put("vote", voteName(it.vote))
                            }
                            JSONObject().put("open", JSONArray(showing.map { json(it, Board.IDEAS, brief = true) }))
                                .put("past", pastPage.getJSONArray("items"))
                                .put("past_total", pastPage.getInt("total"))
                                .put("past_next_offset", pastPage.get("next_offset"))
                        }
                        "read" -> json(item(ideas, "idea_id"), Board.IDEAS)
                        "update" -> {
                            val emoji = args["emoji"]?.takeIf { it.isNotBlank() }
                            val category = args["category"]?.takeIf { it.isNotBlank() }
                            require(emoji != null || category != null) { "Give emoji or category to change" }
                            val before = item(ideas, "idea_id")
                            val after = ideas.update(before.id, emoji = emoji, category = category)
                            json(after, Board.IDEAS, brief = true).put("changed_fields", JSONArray(buildList {
                                if (before.emoji != after.emoji) add("emoji")
                                if (before.category != after.category) add("category")
                            }))
                        }
                        "done" -> {
                            val before = item(ideas, "idea_id")
                            json(ideas.update(before.id, state = "BUILT"), Board.IDEAS, brief = true).put("changed", before.state != "BUILT")
                        }
                        else -> error("Unknown action \"$action\"; use create, list, read, update or done")
                    }
                }
                TRACKING -> {
                    val tracking = OrbitBoardRepository(context, orbit.id, Board.TRACKING)
                    when (action) {
                        "create" -> json(tracking.create(arg("title"), args["detail"].orEmpty(), requestId = requestId), Board.TRACKING, brief = true)
                        "list" -> JSONArray(tracking.list().map { json(it, Board.TRACKING, brief = true) })
                        "read" -> json(item(tracking, "tracking_id"), Board.TRACKING)
                        "update" -> {
                            val before = item(tracking, "tracking_id")
                            val after = tracking.update(before.id, title = args["title"]?.takeIf { it.isNotBlank() }, body = args["detail"])
                            json(after, Board.TRACKING, brief = true).put("changed_fields", JSONArray(buildList {
                                if (before.title != after.title) add("title")
                                if (before.body != after.body) add("detail")
                            }))
                        }
                        "note" -> json(tracking.update(item(tracking, "tracking_id").id, note = arg("note")), Board.TRACKING, brief = true)
                        "complete" -> json(tracking.update(item(tracking, "tracking_id").id, state = "DONE", note = args["note"]), Board.TRACKING, brief = true)
                        "reopen" -> json(tracking.update(item(tracking, "tracking_id").id, state = "ACTIVE", note = args["note"]), Board.TRACKING, brief = true)
                        "delete" -> {
                            val entry = item(tracking, "tracking_id")
                            JSONObject().put("tracking_id", entry.id).put("title", entry.title).put("deleted", tracking.delete(entry.id))
                        }
                        else -> error("Unknown action \"$action\"; use create, list, read, update, note, complete, reopen or delete")
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
        val cut = brief && item.body.length > EXCERPT
        val body = if (cut) item.body.take(EXCERPT) + "…" else item.body
        val key = when (board) { Board.FEED -> "post_id"; Board.IDEAS -> "idea_id"; Board.TRACKING -> "tracking_id"; Board.GOAL_NOTES -> "note_id" }
        val value = JSONObject().put(key, item.id).put("title", item.title).put("state", item.state)
            .put("created_at", time(item.createdAt))
        if (cut) value.put("cut", true)
        when (board) {
            Board.FEED -> value.put("emoji", item.emoji).put("body", body).put("why_for_you", item.extra)
                .put("vote", voteName(item.vote)).put("has_card", item.card).put("images", item.images.size)
            Board.IDEAS -> {
                value.put("emoji", item.emoji).put("description", body).put("category", item.category)
                    .put("vote", voteName(item.vote)).put("expires_at", time(item.expiresAt))
                if (item.scores.rated) value.put("scores", JSONObject().put("feasibility", item.scores.feasibility)
                    .put("personal_fit", item.scores.fit).put("novelty", item.scores.novelty))
                // The plan and parts are for doing the idea; a list leaves them to read.
                if (!brief) value.put("plan", item.extra)
                    .put("includes", JSONArray(item.parts.map { "${it.title} | ${it.detail}".trimEnd(' ', '|') }))
                    .put("rationale", item.rationale).put("sources", JSONArray(item.sources))
            }
            Board.TRACKING -> value.put("detail", body).put("notes_total", item.notes.size)
                .put("notes", JSONArray(item.notes.takeLast(if (brief) 3 else 100).map {
                    JSONObject().put("at", time(it.at)).put("text", it.text)
                }))
            Board.GOAL_NOTES -> value.put("text", body).put("goal_id", item.extra)
        }
        return value
    }

    private const val EXCERPT = 300
    private const val PAGE = 30
    /** The most one picture may weigh before it is shrunk: a page image, not a whole album. */
    private const val MAX_IMAGE_DOWNLOAD = 20L * 1024 * 1024
    /** The longest one picture may take to download, however slowly the server sends it. */
    private const val MAX_IMAGE_DOWNLOAD_MS = 60_000L

    /**
     * A post's pictures, one per line: a files/ path, a path inside the app's own folders or
     * Download/Operit, or an https image link. Each is shrunk the way pictures for the model are
     * (long edge 2048, at most 4 MB) and handed over as bytes, so the post keeps its own copy.
     */
    private suspend fun feedImages(context: Context, orbitId: String, value: String?): List<Pair<ByteArray, String>> {
        val text = value.orEmpty().trim()
        // One per line; a JSON list of strings is taken too.
        val listed = if (text.startsWith("[")) runCatching {
            JSONArray(text).let { array -> (0 until array.length()).map { array.optString(it) } }
        }.getOrNull() else null
        val lines = (listed ?: text.lines()).map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        require(lines.size <= OrbitBoardRepository.MAX_IMAGES) {
            "A post has at most ${OrbitBoardRepository.MAX_IMAGES} pictures; got ${lines.size}"
        }
        val orbitFiles = com.ai.assistance.operit.features.orbit.OrbitFiles.of(context, orbitId)
        return lines.mapIndexed { index, source ->
            // Stopped by the user: do not start on the next picture.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val label = "Picture ${index + 1} ($source)"
            var temporary: java.io.File? = null
            try {
                val file = when {
                    source.startsWith("https://", true) || source.startsWith("http://", true) ->
                        download(context, source, label).also { temporary = it }
                    source.removePrefix("/").startsWith("files/") ->
                        orbitFiles.fileFor(source.removePrefix("/")).also { require(it.isFile) { "$label: no such file" } }
                    else -> runCatching { OrbitFileTools.importable(context, source) }
                        .getOrElse { throw IllegalArgumentException("$label: ${it.message}") }
                }
                val pool = com.ai.assistance.operit.util.ImagePoolManager
                val id = pool.addImage(file.absolutePath)
                require(id != "error") { "$label is not a picture the app can open" }
                try {
                    val data = checkNotNull(pool.getImage(id)) { "$label could not be read" }
                    val bytes = android.util.Base64.decode(data.base64, android.util.Base64.DEFAULT)
                    bytes to if (data.mimeType.equals("image/png", true)) "png" else "jpg"
                } finally {
                    pool.removeImage(id)
                }
            } finally {
                temporary?.delete()
            }
        }
    }

    private suspend fun download(context: Context, url: String, label: String): java.io.File {
        val request = try {
            okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Operit").build()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$label is not a valid link")
        }
        // callTimeout bounds the whole request: connecting, waiting for headers and reading the body.
        // The body is read in the callback too, so cancelling the call stops every part at once.
        val call = downloadClient.newCall(request)
        val target = java.io.File.createTempFile("feed-", ".img", context.cacheDir)
        try {
            return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        val reason = if (call.isCanceled()) "it took over a minute" else e.message
                        continuation.resumeWith(Result.failure(IllegalArgumentException("$label could not be downloaded: $reason")))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val result = runCatching {
                            response.use { save(it, target, label) }
                            target
                        }.recoverCatching { e ->
                            if (e is java.io.IOException) {
                                val reason = if (call.isCanceled()) "it took over a minute" else e.message
                                throw IllegalArgumentException("$label could not be downloaded: $reason")
                            }
                            throw e
                        }
                        // A stopped download leaves nothing behind in the cache.
                        if (result.isFailure || !continuation.isActive) target.delete()
                        continuation.resumeWith(result)
                    }
                })
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun save(response: okhttp3.Response, target: java.io.File, label: String) {
        require(response.isSuccessful) { "$label: the link answered HTTP ${response.code}" }
        val body = checkNotNull(response.body) { "$label: the link sent nothing" }
        val type = body.contentType()?.toString().orEmpty().lowercase()
        require(type.isEmpty() || type.startsWith("image/") || type.startsWith("application/octet-stream")) {
            "$label: the link is not a picture ($type); use the address of the image itself, not the page"
        }
        require(body.contentLength() <= MAX_IMAGE_DOWNLOAD) { "$label is larger than 20 MB" }
        body.byteStream().use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMAGE_DOWNLOAD) { "$label is larger than 20 MB" }
                    out.write(buffer, 0, count)
                }
            }
        }
    }
    private val downloadClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(MAX_IMAGE_DOWNLOAD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }
    private fun time(at: Long): Any = if (at <= 0) JSONObject.NULL
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

    /** [PAGE] items from [offset], with the total and where the next page starts. */
    private fun page(all: List<OrbitBoardRepository.Item>, offset: String?, one: (OrbitBoardRepository.Item) -> JSONObject): JSONObject {
        val start = offset?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: error("offset must be a whole number from 0; got \"$it\"")
        } ?: 0
        val end = (start + PAGE).coerceAtMost(all.size)
        return JSONObject().put("total", all.size)
            .put("items", JSONArray(if (start < all.size) all.subList(start, end).map(one) else emptyList()))
            .put("next_offset", if (end < all.size) end else JSONObject.NULL)
    }
}
