package com.ai.assistance.operit.readme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.core.tools.phone.PhoneBackgroundControl
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.OrbitGoalEntity
import com.ai.assistance.operit.data.model.OrbitRunEntity
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitAppearance
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository
import com.ai.assistance.operit.features.orbit.OrbitBrowserWatch
import com.ai.assistance.operit.features.orbit.OrbitRoutines
import com.ai.assistance.operit.features.orbit.OrbitTaskRepository
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitChatLook
import com.ai.assistance.operit.ui.features.chat.components.LocalOrbitReactions
import com.ai.assistance.operit.ui.features.chat.components.OrbitChatTimeLine
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleStyleChatMessage
import com.ai.assistance.operit.ui.features.chat.components.style.input.classic.ClassicChatInputSection
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitBubbleColor
import com.ai.assistance.operit.ui.features.orbit.LocalOrbitTabHeader
import com.ai.assistance.operit.ui.features.orbit.OrbitAppearanceTheme
import com.ai.assistance.operit.ui.features.orbit.OrbitBottomBar
import com.ai.assistance.operit.ui.features.orbit.OrbitHeader
import com.ai.assistance.operit.ui.features.orbit.OrbitProfilePage
import com.ai.assistance.operit.ui.features.orbit.OrbitTab
import com.ai.assistance.operit.ui.features.orbit.OrbitTabPage
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

// One class per picture: the app's settings are app-wide singletons, and each class runs in a
// fresh process (see app/build.gradle.kts), so every picture starts from a fresh app.
class ReadmeChatEnglish : ReadmeScreenshots(Demo.english, Shot.CHAT)
class ReadmeChatChinese : ReadmeScreenshots(Demo.chinese, Shot.CHAT)
class ReadmeFeedEnglish : ReadmeScreenshots(Demo.english, Shot.FEED)
class ReadmeFeedChinese : ReadmeScreenshots(Demo.chinese, Shot.FEED)
class ReadmeIdeasEnglish : ReadmeScreenshots(Demo.english, Shot.IDEAS)
class ReadmeIdeasChinese : ReadmeScreenshots(Demo.chinese, Shot.IDEAS)
class ReadmeGoalsEnglish : ReadmeScreenshots(Demo.english, Shot.GOALS)
class ReadmeGoalsChinese : ReadmeScreenshots(Demo.chinese, Shot.GOALS)
class ReadmeHistoryEnglish : ReadmeScreenshots(Demo.english, Shot.HISTORY)
class ReadmeHistoryChinese : ReadmeScreenshots(Demo.chinese, Shot.HISTORY)
class ReadmeApprovalsEnglish : ReadmeScreenshots(Demo.english, Shot.APPROVALS)
class ReadmeApprovalsChinese : ReadmeScreenshots(Demo.chinese, Shot.APPROVALS)
class ReadmeBrowserEnglish : ReadmeScreenshots(Demo.english, Shot.BROWSER)
class ReadmeBrowserChinese : ReadmeScreenshots(Demo.chinese, Shot.BROWSER)
class ReadmeSchedulesEnglish : ReadmeScreenshots(Demo.english, Shot.SCHEDULES)
class ReadmeSchedulesChinese : ReadmeScreenshots(Demo.chinese, Shot.SCHEDULES)
class ReadmeIdentityEnglish : ReadmeScreenshots(Demo.english, Shot.IDENTITY)
class ReadmeIdentityChinese : ReadmeScreenshots(Demo.chinese, Shot.IDENTITY)

/**
 * The README pictures: three of Orbit's tabs, and the profile with the tab its icon row opens
 * ([profileTab]; none for the first one, the task history).
 */
internal enum class Shot(val file: String, val tab: OrbitTab? = null, val profileTab: Int? = null) {
    CHAT("chat", OrbitTab.CHAT),
    FEED("feed", OrbitTab.FEED),
    IDEAS("ideas", OrbitTab.IDEAS),
    GOALS("goals", OrbitTab.GOALS),
    HISTORY("history"),
    APPROVALS("approvals", profileTab = R.string.orbit_profile_tab_approvals),
    BROWSER("browser", profileTab = R.string.orbit_profile_tab_browser),
    SCHEDULES("schedules", profileTab = R.string.orbit_profile_tab_schedules),
    IDENTITY("identity", profileTab = R.string.orbit_profile_tab_identity),
}

/** Rainy's pink, as on the launch screen (res/drawable/orbit_launch_logo.xml). */
private const val PINK = "#FF6B8E"

/**
 * Draws the README pictures from Orbit's real screens, filled with made-up demo data, one set per
 * README language. Nothing here touches a phone or real data: Robolectric gives each run its own
 * empty app folder.
 *
 * Run it with tools/readme/readme-shots.ps1; ordinary test runs skip it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w392dp-h850dp-xxhdpi")
abstract class ReadmeScreenshots internal constructor(private val demo: Demo, private val shot: Shot) {
    @get:Rule
    val compose = createComposeRule()

    private val out: String? = System.getProperty("orbit.readme.shots")

    @Test
    fun draw() {
        assumeTrue(out != null)
        // Robolectric's Android clock starts at a made-up date; "today" and "yesterday" need the real one.
        android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis())
        RuntimeEnvironment.setQualifiers("+${demo.qualifier}")
        when (val tab = shot.tab) {
            OrbitTab.CHAT -> chat()
            null -> profile()
            else -> board(tab)
        }
        waitForData()
        settle()
        compose.onRoot().captureRoboImage("$out/${demo.folder}/${shot.file}.png")
    }

    /**
     * Text each picture shows only once its data is read (from the database or files, off the main
     * thread), so a slow run fails instead of drawing an empty or loading page.
     */
    private fun expectedText(): List<String> {
        val more = demo.profile
        return when (shot) {
            Shot.CHAT -> emptyList()
            Shot.FEED -> listOf(demo.posts.first().title)
            Shot.IDEAS -> listOf(demo.ideas.first().title)
            Shot.GOALS -> listOf(more.goals.first().title, more.tracked.first().title)
            // Its detail line is shortened for the list; the title shows in full.
            Shot.HISTORY -> listOf(more.schedules.first { it.id == more.runs.first().taskId }.title)
            Shot.APPROVALS -> listOf(more.asks.first().title, more.asks.last().title)
            Shot.BROWSER -> listOf(more.apps.first().label, more.pages.first().title)
            Shot.SCHEDULES -> listOf(more.schedules.first().title)
            // The cards show the day their files changed: today.
            Shot.IDENTITY -> listOf(java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT,
                context.resources.configuration.locales[0]).format(java.util.Date()))
        }
    }

    private fun waitForData() {
        val deadline = System.currentTimeMillis() + 60_000
        for (text in expectedText()) {
            while (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) {
                check(System.currentTimeMillis() < deadline) { "${shot.file} (${demo.folder}) never showed \"$text\"" }
                Thread.sleep(100)
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
        }
    }

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    // Orbit's logo in Rainy's pink, the color of the launch mark; the chat colors follow the logo.
    private val orbit = OrbitProfile(
        id = "readme-orbit", sourceRoleCardId = "default", chatId = "readme-chat", name = "Orbit",
        appearance = OrbitAppearance(logoUserColor = PINK, logoOrbitColor = PINK),
    )

    /** The Orbit, and its main chat, as the app keeps them. */
    private suspend fun saveOrbit() {
        OrbitPreferences.getInstance(context).putOrbit(orbit)
        com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).saveChatHistory(
            com.ai.assistance.operit.data.model.ChatHistory(id = orbit.chatId, title = "Orbit", messages = emptyList()))
    }

    private fun chat() {
        // The input box shows Orbit's own controls only in a chat that belongs to an Orbit.
        runBlocking { saveOrbit() }
        val viewModel = ChatViewModel(context)
        viewModel.switchChat(orbit.chatId, scrollToBottom = false, refreshWorkspace = false)
        val reactions = demo.chat.filter { it.reaction != null }.associate { it.time.toString() to it.reaction!! }
        compose.setContent {
            OrbitAppearanceTheme(orbit, updateAppAppearance = false) {
                val colors = MaterialTheme.colorScheme
                val userColor = LocalOrbitBubbleColor.current
                CompositionLocalProvider(LocalOrbitChatLook provides true, LocalOrbitReactions provides reactions) {
                    // Like ChatScreenContent: the messages start below the header's measured height.
                    var headerHeight by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current
                    Box(Modifier.fillMaxSize().background(colors.background)) {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(headerHeight))
                            // Like VirtualTranscript: the time line sits right above its message, and
                            // each message ends with 8dp of space.
                            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                                OrbitChatTimeLine(demo.chat.first().time)
                                demo.chat.forEach { line ->
                                    BubbleStyleChatMessage(
                                        message = ChatMessage(if (line.fromUser) "user" else "ai", line.text, timestamp = line.time),
                                        userMessageColor = userColor,
                                        // The light theme's colors from AIChatScreen's Orbit look.
                                        aiMessageColor = Color(0xFFE9E9EB),
                                        userTextColor = Color(0xFF1C1C1E),
                                        aiTextColor = Color(0xFF1C1C1E),
                                        systemMessageColor = colors.surfaceVariant,
                                        systemTextColor = colors.onSurfaceVariant,
                                        bubbleUserRoundedCornersEnabled = true,
                                        bubbleAiRoundedCornersEnabled = true,
                                        showAssistantHeader = false,
                                        enableDialogs = false,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            InputBox(viewModel)
                            OrbitBottomBar(OrbitTab.CHAT, onSelect = {})
                        }
                        Box(Modifier.onGloballyPositioned { headerHeight = with(density) { it.size.height.toDp() } }) {
                            OrbitHeader(orbit, sideTitle = null, hasUnread = false, onOpenProfile = {}, onOpenMenu = {},
                                onOpenSearch = {}, avatarAnimated = false)
                        }
                    }
                }
            }
        }
    }

    private fun board(tab: OrbitTab) {
        runBlocking {
            if (tab == OrbitTab.FEED) {
                val feed = OrbitBoardRepository(context, orbit.id, OrbitBoardRepository.Board.FEED)
                // Created oldest first: the newest post shows on top.
                // Orbit always says why it wrote a post, which gives the post its ⓘ button.
                demo.posts.reversed().forEach { feed.create(title = it.title, body = it.body, extra = it.why, emoji = it.emoji) }
                // Posts are dated when they are made; give them the ages a real feed would have.
                val file = java.io.File(OrbitPreferences.getInstance(context).orbitDir(orbit.id),
                    OrbitBoardRepository.Board.FEED.fileName)
                val saved = org.json.JSONArray(file.readText())
                val now = System.currentTimeMillis()
                for (i in 0 until saved.length()) {
                    val item = saved.getJSONObject(i)
                    val post = demo.posts.first { it.title == item.getString("title") }
                    item.put("createdAt", now - post.ageMinutes * 60_000L).put("updatedAt", now - post.ageMinutes * 60_000L)
                }
                file.writeText(saved.toString())
                feed.list()
            } else if (tab == OrbitTab.IDEAS) {
                val ideas = OrbitBoardRepository(context, orbit.id, OrbitBoardRepository.Board.IDEAS)
                demo.ideas.reversed().forEach {
                    ideas.create(title = it.title, body = it.body, emoji = it.emoji, category = it.category)
                }
            } else {
                saveOrbit()
                saveRecords()
            }
        }
        compose.setContent {
            OrbitAppearanceTheme(orbit, updateAppAppearance = false) {
                val header: @Composable ((@Composable () -> Unit)?) -> Unit = { trailing ->
                    OrbitHeader(orbit, sideTitle = null, hasUnread = false, onOpenProfile = {}, onOpenMenu = {},
                        avatarAnimated = false, onTabLayer = true, trailing = trailing)
                }
                CompositionLocalProvider(LocalOrbitTabHeader provides header) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { OrbitTabPage(orbit, tab, onDraft = {}) }
                        OrbitBottomBar(tab, onSelect = {})
                    }
                }
            }
        }
    }

    /** Orbit's profile, on the tab of this picture. */
    private fun profile() {
        runBlocking {
            saveOrbit()
            saveRecords()
        }
        if (shot == Shot.BROWSER) openPagesAndApps()
        // The profile reads its lists again every few seconds and its identity cards move without
        // end, so the clock never reaches an idle moment by itself; it is moved on by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OrbitAppearanceTheme(orbit, updateAppAppearance = false) {
                OrbitProfilePage(
                    orbit = orbit, visible = true, onClose = {}, onOpenSettings = {}, onOpenTask = {},
                    onOpenAllTasks = {}, onOpenSystemTasks = {}, onOpenProcess = {}, onOpenSoul = {},
                    onOpenMemory = {}, onOpenMemoryLibrary = {}, onOpenIdentity = {}, onOpenUser = {}, onOpenFiles = {},
                )
            }
        }
        // Like the user: a tap on the tab's icon.
        shot.profileTab?.let { label ->
            compose.waitForIdle()
            compose.onNodeWithContentDescription(context.getString(label)).performClick()
        }
    }

    /**
     * What the profile and Goals pages read: tasks and their runs, Orbit's own routines, goals and
     * their steps, things it tracks, approvals, and its memory files.
     */
    private suspend fun saveRecords() {
        val more = demo.profile
        val dao = OrbitTaskRepository(context).dao
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun taskId(local: String) = when (local) {
            ProfileDemo.FEED -> OrbitRoutines.feedId(orbit.id)
            ProfileDemo.IDEAS -> OrbitRoutines.ideasId(orbit.id)
            else -> "readme-$local"
        }
        // As the task tools create them: every task has a valid interval (15 minutes at least), and
        // reminders always speak while watches speak only on a change.
        fun task(id: String, title: String, kind: String, mode: String, source: String = "", interval: Int = 15,
            minute: Int = 0, rule: String = "", goalId: String = "", state: String = "ACTIVE", next: Long = now + day) =
            OrbitTaskEntity(
                id = id, orbitId = orbit.id, chatId = orbit.chatId, title = title, instruction = title, kind = kind,
                source = source, intervalMinutes = interval, dailyMinute = minute,
                notifyPolicy = if (kind == "ASSIST") "ALWAYS" else "CHANGES", state = state,
                version = 1, nextRunAt = next, lastObservation = "", lastFingerprint = "", createdAt = now - 30 * day,
                updatedAt = now - day, scheduleMode = mode, goalId = goalId, calendarRule = rule,
            )
        more.schedules.forEach {
            dao.insertTask(task(taskId(it.id), it.title, it.kind, it.mode, it.source, it.intervalMinutes, it.minute, it.rule,
                next = nextRun(it, now)))
        }
        // Orbit's own routines, under their names in the app.
        listOf(
            OrbitRoutines.selfReviewId(orbit.id) to R.string.orbit_routine_self_review,
            OrbitRoutines.goalBriefingId(orbit.id) to R.string.orbit_routine_goal_briefing,
            OrbitRoutines.feedId(orbit.id) to R.string.orbit_routine_feed,
            OrbitRoutines.ideasId(orbit.id) to R.string.orbit_routine_ideas,
            OrbitRoutines.alignmentId(orbit.id) to R.string.orbit_routine_alignment,
            OrbitRoutines.proactiveScanId(orbit.id) to R.string.orbit_routine_proactive,
        ).forEach { (id, title) -> dao.insertTask(task(id, context.getString(title), "ASSIST", "DAILY", minute = 3 * 60)) }
        more.runs.forEachIndexed { i, run ->
            val start = now - run.minutesAgo * 60_000L
            dao.insertRun(OrbitRunEntity(
                id = "readme-run-$i", eventId = "readme-event-$i", taskId = taskId(run.taskId), taskVersion = 1,
                state = "COMPLETED", owner = "readme", generation = 1, bootId = "readme", leaseUntilElapsed = 0,
                leaseUntilWall = 0, sourceText = "", fingerprint = "", hasSource = false, observation = run.observation,
                error = "", startedAt = start, finishedAt = start + 40_000, decision = "send",
            ))
        }
        more.goals.forEachIndexed { i, goal ->
            val id = "readme-goal-${goal.id}"
            dao.insertGoal(OrbitGoalEntity(id, orbit.id, goal.title, goal.plan, if (goal.active) "ACTIVE" else "PAUSED",
                1, now - 40 * day, now - i * day))
            goal.steps.forEachIndexed { s, (title, done) ->
                // A step is a one-off task of the goal, due a few days apart.
                dao.insertTask(task("$id-$s", title, "ASSIST", "ONCE", goalId = id, state = if (done) "DONE" else "ACTIVE",
                    next = now + (s + 2) * day))
            }
        }
        val tracking = OrbitBoardRepository(context, orbit.id, OrbitBoardRepository.Board.TRACKING)
        more.tracked.reversed().forEach { tracking.create(title = it.title, body = it.body) }
        val folder = OrbitPreferences.getInstance(context).orbitDir(orbit.id)
        java.io.File(folder, "approvals.json").writeText(org.json.JSONObject().put("items", org.json.JSONArray().apply {
            more.asks.forEachIndexed { i, ask ->
                val at = now - ask.minutesAgo * 60_000L
                put(org.json.JSONObject().put("id", "readme-$i").put("kind", ask.kind).put("source", "orbit")
                    .put("title", ask.title).put("detail", ask.detail).put("chatId", orbit.chatId).put("state", ask.state)
                    .put("note", "").put("createdAt", at - 60_000).put("decidedAt", if (ask.state == "pending") 0 else at)
                    .put("notified", ask.state != "pending"))
            }
        }).toString())
        java.io.File(folder, "memory").mkdirs()
        java.io.File(folder, "memory/memory.md").writeText(more.memory)
        java.io.File(folder, "user.md").writeText(more.user)
        com.ai.assistance.operit.features.orbit.OrbitSoulRepository(context, orbit.id).seed(more.soul)
    }

    /** The next time a schedule is due, so the list says "next in ..." as it would. */
    private fun nextRun(schedule: Schedule, now: Long): Long {
        if (schedule.mode == "INTERVAL") return now + schedule.intervalMinutes * 60_000L / 2
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, schedule.minute / 60); set(Calendar.MINUTE, schedule.minute % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            when (schedule.mode) {
                "WEEKLY" -> set(Calendar.DAY_OF_WEEK, schedule.rule.toInt() % 7 + 1)
                "MONTHLY" -> set(Calendar.DAY_OF_MONTH, schedule.rule.toInt())
            }
        }
        val step = when (schedule.mode) { "WEEKLY" -> Calendar.WEEK_OF_YEAR; "MONTHLY" -> Calendar.MONTH; else -> Calendar.DAY_OF_YEAR }
        while (next.timeInMillis <= now) next.add(step, 1)
        return next.timeInMillis
    }

    /**
     * Pages open in Orbit's browser and apps on its background screens. Both live only in memory
     * while Orbit works, so they are put there as Orbit's tools would; a page counts as open while
     * its browser tab is.
     */
    private fun openPagesAndApps() {
        val more = demo.profile
        val now = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        fun <T> state(owner: Any, name: String): MutableStateFlow<T> =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>
        state<Map<String, OrbitBrowserWatch.Work>>(OrbitBrowserWatch, "_works").value =
            more.pages.mapIndexed { i, page ->
                val task = "readme-browser-$i"
                val session = "readme-web-$i"
                StandardBrowserSessionTools.sessions[session] = StandardBrowserSessionTools.WebSession(
                    id = session,
                    page = com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebViewBrowserPage(android.webkit.WebView(context)),
                    sessionName = null, browserTaskId = task)
                task to OrbitBrowserWatch.Work(
                    talkChatId = orbit.chatId, orbitId = orbit.id, orbitName = orbit.name, executorChatId = task,
                    sessionId = session, url = page.url, title = page.title,
                    lastActionAt = now - page.minutesAgo * 60_000L, startedAt = now - (page.minutesAgo + 10) * 60_000L,
                )
            }.toMap()
        state<Map<String, PhoneBackgroundControl.Screen>>(PhoneBackgroundControl, "_screens").value =
            more.apps.mapIndexed { i, app ->
                val used = now - app.minutesAgo * 60_000L
                "readme-screen-$i" to PhoneBackgroundControl.Screen(
                    id = "readme-screen-$i", ownerChat = "readme-agent-$i", talkChatId = orbit.chatId, orbitId = orbit.id,
                    orbitName = orbit.name, displayId = 10 + i, width = 1080, height = 2400, createdAt = used - 5 * 60_000L,
                    appLabel = app.label, lastUsedAt = used, doneAt = if (app.done) used else null,
                )
            }.toMap()
    }

    /** Lets the pages read their data and lay out their text, which happens off the main thread. */
    private fun settle() {
        repeat(30) {
            Thread.sleep(100)
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}

/** Orbit's real input box, empty, with the options its chat screen gives it (see AIChatScreen). */
@Composable
private fun InputBox(viewModel: ChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        ClassicChatInputSection(
            actualViewModel = viewModel,
            userMessage = TextFieldValue(""),
            onUserMessageChange = {},
            onSendMessage = {},
            onQueueMessage = {},
            onCancelMessage = {},
            isLoading = false,
            allowTextInputWhileProcessing = true,
            onTakePhoto = {},
            chatInputFloating = true,
            // Thinking on at "high", as a new Orbit starts.
            enableThinkingMode = true,
            thinkingQualityLevel = 3,
        )
    }
}

internal class Line(val fromUser: Boolean, val text: String, val time: Long, val reaction: String? = null)
internal class Post(val emoji: String, val title: String, val body: String, val ageMinutes: Int, val why: String)
internal class Idea(val emoji: String, val title: String, val body: String, val category: String)

/** The made-up conversation, feed and ideas each README shows. */
internal class Demo(
    val qualifier: String,
    val folder: String,
    val chat: List<Line>,
    val posts: List<Post>,
    val ideas: List<Idea>,
    val profile: ProfileDemo,
) {
    companion object {
        /** Today at [hour]:[minute], so the chat's time line shows only the time. */
        private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val english = Demo(
            qualifier = "en", folder = "en",
            profile = ProfileDemo.english,
            chat = listOf(
                Line(false, "Morning! Your dentist appointment is at 3 pm today. I'll nudge you at 2:30 so you can leave on time.", at(9, 12)),
                Line(true, "Thanks! Can you also keep an eye on my package and tell me when it arrives?", at(9, 14), reaction = "👍"),
                Line(false, "Sure. I'll watch your texts and notifications and let you know as soon as it's delivered.", at(9, 14) + 20_000),
                Line(true, "Do you remember what I'm allergic to?", at(9, 16)),
                Line(false, "Peanuts. I check the ingredients whenever we order food, and I'll keep doing that.", at(9, 16) + 15_000),
            ),
            posts = listOf(
                Post("🚆", "Weekend train tickets open tomorrow at 8 am",
                    "Tickets for the long weekend go on sale tomorrow morning. Last time the 9 am train to the coast sold out in twenty minutes, so it may be worth setting an alarm.", 25,
                    "You mentioned a trip to the coast over the long weekend."),
                Post("🌧️", "Rain from Thursday evening",
                    "Heavy rain is expected from Thursday evening into Friday morning. If you cycle to work on Friday, the bus might be the better choice.", 180,
                    "You usually cycle to work."),
                Post("📚", "The sequel you were waiting for is out",
                    "The second book in the series you finished last month came out this week. The first chapters are free to read in your reading app.", 1500,
                    "You finished the first book last month and asked when the next one comes out."),
            ),
            ideas = listOf(
                Idea("🧾", "Track your subscriptions in one list",
                    "I found four monthly charges in your texts. I can keep them in one list and remind you before each renewal.", "money"),
                Idea("🏃", "A gentle running plan for the mornings",
                    "You said you want to start running again. Three short runs a week, with a reminder the night before.", "health"),
                Idea("🎂", "Never miss a friend's birthday",
                    "I can read the birthdays in your contacts and suggest a gift a week ahead.", "relationships"),
            ),
        )

        val chinese = Demo(
            qualifier = "zh-rCN", folder = "zh",
            profile = ProfileDemo.chinese,
            chat = listOf(
                Line(false, "早呀。今天下午 3 点有牙医预约，我 2 点半提醒你出门。", at(9, 12)),
                Line(true, "好，谢谢！顺便帮我盯一下快递，到了告诉我", at(9, 14), reaction = "👍"),
                Line(false, "收到。我会看着短信和通知，快递一到就跟你说。", at(9, 14) + 20_000),
                Line(true, "你还记得我对什么过敏吗", at(9, 16)),
                Line(false, "记得，花生。点外卖的时候我都会帮你看配料，以后也会。", at(9, 16) + 15_000),
            ),
            posts = listOf(
                Post("🚆", "周末火车票明早 8 点开抢",
                    "小长假的车票明天早上开售。上次去海边的 9 点那班二十分钟就卖完了，可以定个闹钟。", 25,
                    "你说过小长假想去海边。"),
                Post("🌧️", "周四晚上开始下雨",
                    "周四晚上到周五早上有大雨。你周五要是骑车上班，坐公交可能更稳妥。", 180,
                    "你平时骑车上班。"),
                Post("📚", "你在等的续集出了",
                    "你上个月读完的那套书，第二本这周上架了，阅读 App 里前几章可以免费看。", 1500,
                    "你上个月读完第一本，问过续集什么时候出。"),
            ),
            ideas = listOf(
                Idea("🧾", "把订阅扣费整理成一张清单",
                    "我在短信里找到四笔每月扣费，可以整理成清单，每次续费前提醒你。", "money"),
                Idea("🏃", "早上慢跑的轻松计划",
                    "你说过想重新开始跑步。一周三次短跑，前一晚提醒你。", "health"),
                Idea("🎂", "不再错过朋友生日",
                    "我可以读通讯录里的生日，提前一周帮你想礼物。", "relationships"),
            ),
        )
    }
}
