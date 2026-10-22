package com.ai.assistance.operit.features.orbit

/**
 * What Orbit hears when the user used its browser from the watch screen: a note on the next
 * browser call while an agent is still browsing, or a hidden turn of its own when none is.
 */
object OrbitBrowserNotice {
    /** First line of every hidden note; it names the turn in the activity list. */
    const val MARKER = "[orbit-browser]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")

    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Returned instead of the result of the AI's next browser call after the user handed it back. */
    fun callNote(english: Boolean, url: String): String = if (english) {
        "The user took over the browser for a while and has handed it back. The page may have changed (now at $url). " +
            "Take a fresh browser_snapshot before acting, then continue."
    } else {
        "用户刚才接管了浏览器，自己操作了一会儿，现在交还给你。页面可能变了（现在是 $url）。先 browser_snapshot 重新看一眼，再接着做。"
    }

    /** Returned to a browser call that waited for the user when the user stopped the work instead. */
    fun stoppedCall(english: Boolean): String = if (english) {
        "The user stopped this browser task. Do not continue it or start it again."
    } else {
        "用户停止了这个浏览器任务。不要继续，也不要重新开始。"
    }

    fun handedBack(english: Boolean, url: String): String {
        val quiet = OrbitReactionNotice.NOTHING_TO_DO
        return if (english) buildString {
            append("$MARKER The user handed the browser back\n")
            append("The user took over your browser, used it themselves and handed it back. This is not a message from the user; they typed nothing and cannot see this note.\n")
            append("The page may have changed (now at $url). If the work you were doing in the browser is unfinished, take a fresh browser_snapshot and continue it. ")
            append("If it is done or no longer needed, call $quiet and stay quiet. Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 用户交还了浏览器\n")
            append("用户刚才接管了你的浏览器，自己操作了一会儿，现在交还给你。这不是用户发的消息，用户没有打字，也看不到这段话。\n")
            append("页面可能变了（现在是 $url）。浏览器里的事还没做完，就先 browser_snapshot 看一眼再接着做；")
            append("已经做完或不需要再做，调用 $quiet 保持安静。不要提“隐藏消息”这些内部说法。")
        }
    }

    fun stopped(english: Boolean): String {
        val quiet = OrbitReactionNotice.NOTHING_TO_DO
        return if (english) buildString {
            append("$MARKER The user stopped the browser task\n")
            append("The user pressed Stop on the browser while your subagent was using it, and the subagent has stopped. This is not a message from the user; they cannot see this note.\n")
            append("Do not start that work again. Say in a short line that you stopped, or call $quiet and stay quiet. Do not mention hidden notes.")
        } else buildString {
            append("$MARKER 用户停止了浏览器任务\n")
            append("用户在浏览器里按了“停止任务”，在用浏览器的子代理已经停下。这不是用户发的消息，用户看不到这段话。\n")
            append("不要重新开始这件事。简短说一句停下了就好，或者调用 $quiet 保持安静。不要提“隐藏消息”这些内部说法。")
        }
    }
}
