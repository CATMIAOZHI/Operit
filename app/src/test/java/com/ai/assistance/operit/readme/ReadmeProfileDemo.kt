package com.ai.assistance.operit.readme

/**
 * The made-up records behind the README's profile and Goals pictures: what Orbit did, what it asked
 * for, what it has open, its schedules, goals and memory. Times are minutes before now.
 */
internal class Schedule(
    val id: String,
    val title: String,
    val kind: String,
    val mode: String,
    val source: String = "",
    /** Every task has one, 15 minutes at least, even when it runs at set times. */
    val intervalMinutes: Int = 15,
    /** Minutes after midnight, for daily, weekly and monthly ones. */
    val minute: Int = 0,
    val rule: String = "",
)

internal class Run(val taskId: String, val minutesAgo: Int, val observation: String)

internal class Ask(
    val kind: String,
    val state: String,
    val title: String,
    val detail: String,
    val minutesAgo: Int,
)

internal class Goal(val id: String, val title: String, val plan: String, val active: Boolean, val steps: List<Pair<String, Boolean>>)

internal class Tracked(val title: String, val body: String)

internal class Page(val title: String, val url: String, val minutesAgo: Int)

internal class App(val label: String, val done: Boolean, val minutesAgo: Int)

internal class ProfileDemo(
    val schedules: List<Schedule>,
    val runs: List<Run>,
    val asks: List<Ask>,
    val goals: List<Goal>,
    val tracked: List<Tracked>,
    val pages: List<Page>,
    val apps: List<App>,
    val memory: String,
    val user: String,
    val soul: String,
) {
    companion object {
        /** Run of Orbit's own Feed and Ideas routines (see OrbitRoutines). */
        const val FEED = "@feed"
        const val IDEAS = "@ideas"

        val english = ProfileDemo(
            schedules = listOf(
                Schedule("workout", "Morning workout reminder", "ASSIST", "DAILY", minute = 7 * 60 + 30),
                Schedule("report", "Weekly report reminder", "ASSIST", "WEEKLY", minute = 17 * 60, rule = "5"),
                Schedule("rent", "Pay the rent", "ASSIST", "MONTHLY", minute = 10 * 60, rule = "1"),
                Schedule("notify", "Watch app notifications", "WATCH", "INTERVAL", source = "notifications", intervalMinutes = 120),
                Schedule("sms", "Watch new texts", "WATCH", "INTERVAL", source = "sms", intervalMinutes = 120),
                Schedule("tickets", "Watch the concert ticket page", "URL_CHANGE", "INTERVAL", source = "https://tickets.example.com/concert", intervalMinutes = 60),
                Schedule("issues", "Watch GitHub issues", "WATCH", "INTERVAL", source = "script:files/hooks/github-issues-1f3a9c2e.sh@4b1d7e0a9c2f6e83", intervalMinutes = 30),
            ),
            runs = listOf(
                Run("issues", 12, "New issue #42: the app closes when Settings opens. A subagent fixed it and opened PR #43."),
                Run("notify", 35, "Your parcel is at the pickup point. I sent you the code."),
                Run("tickets", 95, "The Saturday show opened a second round of tickets."),
                Run("workout", 180, "Reminded you. You said you would run 3 km today."),
                Run(FEED, 60 * 14, "Wrote three new posts for your Feed."),
                Run("sms", 60 * 17, "A bank login code came in. Nothing for you to do."),
                Run(IDEAS, 60 * 26, "Added two ideas: a subscription list and a running plan."),
            ),
            asks = listOf(
                Ask("request", "pending", "Reply to your landlord",
                    "Send in the chat with your landlord: \"December's rent is paid, thank you!\"", 6),
                Ask("request", "approved", "Book a table for Saturday",
                    "Book a table for two at 7 pm on Saturday at the restaurant you saved.", 150),
                Ask("vault_saved", "saved", "booking.com", "login", 60 * 20),
                Ask("permission", "approved", "Send notifications",
                    "Let Orbit tell you when a background job finishes or a reminder is due.", 60 * 26),
                Ask("request", "denied", "Delete old screenshots",
                    "Delete 412 screenshots older than a year from the Screenshots folder.", 60 * 30),
            ),
            goals = listOf(
                Goal("run", "Run a 10 km race in spring", "Three runs a week, a little longer each month.", true,
                    listOf("Run 3 km without stopping" to true, "Run 5 km" to true, "Run 8 km" to false, "Sign up for the race" to false)),
                Goal("read", "Read 12 books this year", "One book a month, with notes in the reading app.", true,
                    listOf("January book" to true, "February book" to true, "March book" to false)),
                Goal("japanese", "Learn basic Japanese", "Fifteen minutes a day before the trip in May.", false,
                    listOf("Learn hiragana" to true, "Learn katakana" to false)),
            ),
            tracked = listOf(
                Tracked("Parcel from the bookstore", "At the pickup point since this morning; the code is in your texts."),
                Tracked("Dentist follow-up", "Second visit on the 28th at 3 pm."),
            ),
            pages = listOf(
                Page("Hotels in Hangzhou", "https://www.booking.com/city/cn/hangzhou.html", 4),
                Page("10-day weather forecast", "https://weather.com/weather/tenday", 38),
            ),
            apps = listOf(App("Calendar", false, 2), App("Settings", true, 3)),
            memory = "# Memory\n\n- Allergic to peanuts.\n- Cycles to work; takes the bus when it rains.\n",
            user = "# About you\n\nLikes trains, long walks and mystery novels.\n",
            soul = "Warm and direct. Speaks up only when it matters, and keeps every promise it makes.",
        )

        val chinese = ProfileDemo(
            schedules = listOf(
                Schedule("workout", "早上锻炼提醒", "ASSIST", "DAILY", minute = 7 * 60 + 30),
                Schedule("report", "周五交周报提醒", "ASSIST", "WEEKLY", minute = 17 * 60, rule = "5"),
                Schedule("rent", "交房租", "ASSIST", "MONTHLY", minute = 10 * 60, rule = "1"),
                Schedule("notify", "盯着 App 通知", "WATCH", "INTERVAL", source = "notifications", intervalMinutes = 120),
                Schedule("sms", "盯着新短信", "WATCH", "INTERVAL", source = "sms", intervalMinutes = 120),
                Schedule("tickets", "盯着演唱会购票页", "URL_CHANGE", "INTERVAL", source = "https://tickets.example.com/concert", intervalMinutes = 60),
                Schedule("issues", "盯着 GitHub issue", "WATCH", "INTERVAL", source = "script:files/hooks/github-issues-1f3a9c2e.sh@4b1d7e0a9c2f6e83", intervalMinutes = 30),
            ),
            runs = listOf(
                Run("issues", 12, "新 issue #42：打开设置时闪退。子代理已经修好，开了 PR #43。"),
                Run("notify", 35, "快递到驿站了，取件码已经发给你。"),
                Run("tickets", 95, "周六场开放第二轮购票了。"),
                Run("workout", 180, "提醒过你了，你说今天跑 3 公里。"),
                Run(FEED, 60 * 14, "给你的动态写了三条新帖子。"),
                Run("sms", 60 * 17, "收到一条银行登录验证码，不需要你处理。"),
                Run(IDEAS, 60 * 26, "新增两个点子：订阅清单和跑步计划。"),
            ),
            asks = listOf(
                Ask("request", "pending", "回复房东", "在和房东的聊天里发送：“12 月房租已付，谢谢！”", 6),
                Ask("request", "approved", "订周六的位子", "在你收藏的那家餐厅订周六晚上 7 点两个人的位子。", 150),
                Ask("vault_saved", "saved", "ctrip.com", "login", 60 * 20),
                Ask("permission", "approved", "发送通知", "后台任务做完、提醒到点时，Orbit 能通知你。", 60 * 26),
                Ask("request", "denied", "删除旧截图", "删除截图文件夹里一年前的 412 张截图。", 60 * 30),
            ),
            goals = listOf(
                Goal("run", "春天跑完一场 10 公里", "一周跑三次，每个月跑远一点。", true,
                    listOf("不停歇跑完 3 公里" to true, "跑完 5 公里" to true, "跑完 8 公里" to false, "报名比赛" to false)),
                Goal("read", "今年读完 12 本书", "一个月一本，在阅读 App 里做笔记。", true,
                    listOf("一月的书" to true, "二月的书" to true, "三月的书" to false)),
                Goal("japanese", "学会基础日语", "五月出发前，每天学 15 分钟。", false,
                    listOf("学会平假名" to true, "学会片假名" to false)),
            ),
            tracked = listOf(
                Tracked("书店寄来的快递", "早上到驿站了，取件码在短信里。"),
                Tracked("牙医复诊", "28 号下午 3 点第二次复诊。"),
            ),
            pages = listOf(
                Page("杭州酒店预订 - 携程", "https://hotels.ctrip.com/hotels/list?city=17", 4),
                Page("15 天天气预报 - 中国天气网", "https://www.weather.com.cn/weather15d/101210101.shtml", 38),
            ),
            apps = listOf(App("日历", false, 2), App("设置", true, 3)),
            memory = "# 记忆\n\n- 对花生过敏。\n- 平时骑车上班，下雨坐公交。\n",
            user = "# 关于你\n\n喜欢坐火车、散步和推理小说。\n",
            soul = "温柔、直接。只在值得的时候开口，答应的事一定做到。",
        )
    }
}
