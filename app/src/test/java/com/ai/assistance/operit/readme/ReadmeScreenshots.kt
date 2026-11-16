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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitBoardRepository
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
import com.ai.assistance.operit.ui.features.orbit.OrbitTab
import com.ai.assistance.operit.ui.features.orbit.OrbitTabPage
import com.github.takahirom.roborazzi.captureRoboImage
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
class ReadmeChatEnglish : ReadmeScreenshots(Demo.english, OrbitTab.CHAT)
class ReadmeChatChinese : ReadmeScreenshots(Demo.chinese, OrbitTab.CHAT)
class ReadmeFeedEnglish : ReadmeScreenshots(Demo.english, OrbitTab.FEED)
class ReadmeFeedChinese : ReadmeScreenshots(Demo.chinese, OrbitTab.FEED)
class ReadmeIdeasEnglish : ReadmeScreenshots(Demo.english, OrbitTab.IDEAS)
class ReadmeIdeasChinese : ReadmeScreenshots(Demo.chinese, OrbitTab.IDEAS)

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
abstract class ReadmeScreenshots internal constructor(private val demo: Demo, private val tab: OrbitTab) {
    @get:Rule
    val compose = createComposeRule()

    private val out: String? = System.getProperty("orbit.readme.shots")

    @Test
    fun draw() {
        assumeTrue(out != null)
        RuntimeEnvironment.setQualifiers("+${demo.qualifier}")
        if (tab == OrbitTab.CHAT) chat() else board()
        settle()
        compose.onRoot().captureRoboImage("$out/${demo.folder}/${tab.name.lowercase()}.png")
    }

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val orbit = OrbitProfile(id = "readme-orbit", sourceRoleCardId = "default", chatId = "readme-chat", name = "Orbit")

    private fun chat() {
        // The input box shows Orbit's own controls only in a chat that belongs to an Orbit.
        runBlocking {
            OrbitPreferences.getInstance(context).putOrbit(orbit)
            com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).saveChatHistory(
                com.ai.assistance.operit.data.model.ChatHistory(id = orbit.chatId, title = "Orbit", messages = emptyList()))
        }
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

    private fun board() {
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
            } else {
                val ideas = OrbitBoardRepository(context, orbit.id, OrbitBoardRepository.Board.IDEAS)
                demo.ideas.reversed().forEach {
                    ideas.create(title = it.title, body = it.body, emoji = it.emoji, category = it.category)
                }
            }
        }
        compose.setContent {
            OrbitAppearanceTheme(orbit, updateAppAppearance = false) {
                val header: @Composable () -> Unit = {
                    OrbitHeader(orbit, sideTitle = null, hasUnread = false, onOpenProfile = {}, onOpenMenu = {},
                        avatarAnimated = false, onTabLayer = true)
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

    /** Lets the pages read their data and lay out their text, which happens off the main thread. */
    private fun settle() {
        repeat(30) {
            Thread.sleep(100)
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
) {
    companion object {
        /** Today at [hour]:[minute], so the chat's time line shows only the time. */
        private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val english = Demo(
            qualifier = "en", folder = "en",
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
