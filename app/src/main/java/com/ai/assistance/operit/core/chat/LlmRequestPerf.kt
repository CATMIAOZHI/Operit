package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.util.AppLogger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 用户点发送那一刻的起点，挂在这条消息的发送任务协程上。主请求建计时卡时取走（只取一次），
 * 算成“发送入口”；直到任务结束都没被取走（插件接管、准备失败、没发请求），由 [reportIfUnused] 补一行。
 * 跟着协程走，同一个聊天同时有两轮发送也不会串。
 */
class LlmPerfStart internal constructor(internal val mark: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmPerfStart>

    private val taken = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 主请求的计时卡取走起点；已被别的请求取走时返回 null。 */
    internal fun take(): Long? = if (taken.compareAndSet(false, true)) mark else null

    /** 发送任务结束时调用：起点没被用过，说明这一轮没走主模型请求。 */
    internal fun reportIfUnused(cause: Throwable?) {
        if (!taken.compareAndSet(false, true)) return
        LlmRequestPerf.logUnsent(mark, cause, "这一轮没走主模型请求，可能插件接管、准备出错或没发出")
    }
}

/**
 * 一次模型请求的分段计时卡。挂在协程上下文里：本地准备每一步用 [lap] 记，服务商记转 JSON
 * 的耗时，网络监听记连接、上传、等回复头的时间点，结束时 [log] 输出一行总结。
 *
 * 日志标签 [TAG]（LlmPerf），一行就能看出慢在本地、网络还是服务器。
 * [start] 是用户点发送的起点（见 [LlmPerfStart]），有的话把之前那段算成“发送入口”。
 */
internal class LlmRequestPerf(
    private val kind: String,
    start: LlmPerfStart? = null,
    streaming: Boolean = true,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LlmRequestPerf> {
        const val TAG = "LlmPerf"

        /** 单调时钟（毫秒）；nanoTime 在 JVM 单测里也能用。 */
        fun now(): Long = System.nanoTime() / 1_000_000L

        private fun outcomeOf(cause: Throwable?, none: String): String = when (cause) {
            null -> none
            is kotlinx.coroutines.CancellationException -> "中断"
            else -> "失败: ${cause.javaClass.simpleName}"
        }

        /** 从点发送 [mark] 到现在都没建立主请求时的一行说明。 */
        fun logUnsent(mark: Long, cause: Throwable?, where: String) {
            AppLogger.i(TAG, "[发消息 ${outcomeOf(cause, "未发主请求")}] 从点发送到结束 ${now() - mark}ms($where)")
        }

        /** 一次 HTTP 往返的事件：OkHttp 在同一个 Call 里重试或重定向会再来一遍。 */
        private val EXCHANGE_EVENTS = listOf(
            "requestHeadersStart", "requestHeadersEnd", "requestBodyStart", "requestBodyEnd",
            "responseHeadersStart", "responseHeadersEnd", "responseBodyStart", "responseBodyEnd",
        )
        /** 拿连接的事件：重定向换连接时整组重来，只看最后一次。 */
        private val CONNECT_EVENTS = setOf(
            "proxySelectStart", "proxySelectEnd", "dnsStart", "dnsEnd", "connectStart",
            "secureConnectStart", "secureConnectEnd", "connectEnd", "connectFailed", "connectionAcquired",
        )
    }

    private val laps = LinkedHashMap<String, Long>()
    /** 实际是否流式；服务商强制流式时由 [actualStreaming] 改正。 */
    private var streaming = streaming
    private var lastLap = now()
    private val createdAt: Long
    init {
        val from = start?.take()?.takeIf { it <= lastLap }
        if (from != null) laps["发送入口(可能含等总结)"] = lastLap - from
        createdAt = from ?: lastLap
    }
    private var requestAt = -1L
    private var bodyMs = 0L
    private var bodyBytes = -1L
    private var bodies = 0
    /** 转 JSON 内部各段耗时（纳秒），见 [LlmBodyStages]。重试重新打包会累加。 */
    private val bodyStageNs = LinkedHashMap<String, Long>()
    private var firstChunkAt = -1L
    /** 最后一个 Call 的网络事件：连接只留最后一次拿连接，上传和等待只留最后一次往返。 */
    private val net = HashMap<String, Long>()
    private var calls = 0
    private var firstCallAt = -1L
    /** 最后一次拿连接从什么时候开始算（Call 开始，或上一个连接放回去之后）。 */
    private var connectFrom = -1L
    /** 上一个连接放回去的时间；之后再拿连接就是重定向换了一次连接。 */
    private var releasedAt = -1L
    /** 之前各个 Call 的往返次数之和（连接就失败、一次都没发出的 Call 也算一次尝试）。 */
    private var earlierAttempts = 0
    /** 当前 Call 里的往返次数。 */
    private var callExchanges = 0
    private var logged = false

    /** 记一段本地步骤：从上一次记录到现在的耗时算在 [stage] 上。 */
    @Synchronized
    fun lap(stage: String) {
        val t = now()
        laps[stage] = (laps[stage] ?: 0L) + (t - lastLap)
        lastLap = t
    }

    /** 本地准备结束、开始交给服务商发请求。 */
    @Synchronized
    fun requestStarted() {
        lap("其他")
        requestAt = now()
    }

    /** 服务商决定的实际发送方式（有的服务商不管设置都用流式）。 */
    @Synchronized
    fun actualStreaming(stream: Boolean) {
        streaming = stream
    }

    /** 服务商把对话转成请求体（JSON）用了 [ms]，请求体 [bytes] 字节。重试会累加。 */
    @Synchronized
    fun body(ms: Long, bytes: Long) {
        bodyMs += ms
        bodies++
        if (bytes >= 0) bodyBytes = bytes
    }

    /** 转 JSON 内部各段的耗时（纳秒）。 */
    @Synchronized
    fun bodyStages(stages: Map<String, Long>) {
        stages.forEach { (stage, ns) -> bodyStageNs[stage] = (bodyStageNs[stage] ?: 0L) + ns }
    }

    /** 网络监听的事件（callStart、dnsStart、requestBodyEnd、responseHeadersStart 等）。 */
    @Synchronized
    fun network(event: String) {
        val t = now()
        when (event) {
            "callStart" -> {
                if (calls > 0) earlierAttempts += maxOf(1, callExchanges)
                calls++
                callExchanges = 0
                if (firstCallAt < 0) firstCallAt = t
                net.clear()
                connectFrom = t
                releasedAt = -1L
            }
            "requestHeadersStart" -> {
                callExchanges++
                EXCHANGE_EVENTS.forEach { net.remove(it) }
            }
            "connectionReleased" -> releasedAt = t
        }
        if (releasedAt >= 0 && event in CONNECT_EVENTS) {
            CONNECT_EVENTS.forEach { net.remove(it) }
            connectFrom = releasedAt
            releasedAt = -1L
        }
        net.putIfAbsent(event, t)
    }

    /** 第一段内容到达（流式是第一个字；非流式是整段回复收完）。 */
    @Synchronized
    fun firstChunk() {
        if (firstChunkAt < 0) firstChunkAt = now()
    }

    @Synchronized
    fun summary(model: String?, outcome: String): String {
        val end = now()
        fun gap(from: String, to: String): Long? {
            val a = net[from] ?: return null
            val b = net[to] ?: return null
            return (b - a).coerceAtLeast(0)
        }
        val steps = LinkedHashMap(laps)
        // 没走到发请求就结束了：最后这段算在“未完成”上，看得出卡在哪一步之后。
        if (requestAt < 0 && end > lastLap) steps["未完成"] = end - lastLap
        val sentAt = requestAt.takeIf { it >= 0 } ?: end
        val parts = mutableListOf<String>()
        // 大多是本地耗时，但“发送入口”“压缩上下文”可能在等总结模型。
        parts += "请求前准备 ${sentAt - createdAt}ms(" +
            steps.entries.joinToString("/") { "${it.key} ${it.value}" } + ")"
        if (bodies > 0) {
            val size = if (bodyBytes >= 0) "${(bodyBytes + 1023) / 1024}KB" else "大小未知"
            // 只列 1ms 以上的段，看得出慢在历史整理、消息组装、写日志还是转文字。
            val stages = bodyStageNs.entries
                .map { it.key to it.value / 1_000_000L }
                .filter { it.second > 0 }
                .joinToString("/") { "${it.first} ${it.second}" }
            parts += "转JSON ${bodyMs}ms($size" + (if (bodies > 1) "，${bodies}次" else "") +
                (if (stages.isNotEmpty()) "：$stages" else "") + ")"
        }
        // 只算第一次发出；重试花掉的时间单独列在“重试”里，免得算成本地慢。
        if (requestAt >= 0 && firstCallAt >= 0) parts += "到发出 ${(firstCallAt - requestAt).coerceAtLeast(0)}ms(含转JSON)"
        if (net.containsKey("callStart")) {
            val connect = net["connectionAcquired"]?.let { (it - connectFrom).coerceAtLeast(0) }
            parts += when {
                connect == null -> "连接 未建立"
                !net.containsKey("connectStart") -> "连接 复用 ${connect}ms"
                else -> "连接 ${connect}ms(DNS ${gap("dnsStart", "dnsEnd") ?: 0}/TLS ${gap("secureConnectStart", "secureConnectEnd") ?: 0})"
            }
            val sendEnd = if (net.containsKey("requestBodyEnd")) "requestBodyEnd" else "requestHeadersEnd"
            gap("requestHeadersStart", sendEnd)?.let { parts += "上传 ${it}ms" }
            // 包含网络往返、代理和服务器排队，没法单独拆出服务器本身。
            gap(sendEnd, "responseHeadersStart")?.let { parts += "等回复头(含网络) ${it}ms" }
        }
        // 回复最后一个字节到达的时间；之后的是本地收尾（统计落账等），不算模型生成。
        val bodyEnd = net["responseBodyEnd"]
        if (streaming) {
            if (requestAt >= 0 && firstChunkAt >= 0) parts += "首字 ${firstChunkAt - requestAt}ms"
            if (firstChunkAt >= 0) {
                if (bodyEnd != null && bodyEnd >= firstChunkAt) {
                    // 模型继续输出的时间，也包含边收边处理（解析、更新界面）造成的读取变慢。
                    parts += "首字到收完 ${bodyEnd - firstChunkAt}ms(含本地边收边处理)"
                    parts += "收完后处理 ${(end - bodyEnd).coerceAtLeast(0)}ms"
                } else {
                    parts += "首字后 ${end - firstChunkAt}ms(含本地收尾)"
                }
            }
        } else if (requestAt >= 0) {
            // 非流式要等整段回复收完，生成时间都在“收完整段”里。
            if (bodyEnd != null) {
                parts += "非流式 收完整段 ${(bodyEnd - requestAt).coerceAtLeast(0)}ms"
                parts += "收完后处理 ${(end - bodyEnd).coerceAtLeast(0)}ms"
            } else if (firstChunkAt >= 0) {
                parts += "非流式 拿到回复 ${firstChunkAt - requestAt}ms(含解析)"
            }
        }
        parts += "总计 ${end - createdAt}ms"
        val attempts = if (calls > 0) earlierAttempts + maxOf(1, callExchanges) else 0
        if (attempts > 1) {
            // 从第一次发出到最后一次往返开始的总时间，包含正常的连接耗时，不全是白等。
            val lastStart = net["requestHeadersStart"] ?: net["callStart"]
            val span = if (firstCallAt >= 0 && lastStart != null) "(首次发出到末次发出 ${(lastStart - firstCallAt).coerceAtLeast(0)}ms)" else ""
            parts += "重试或重定向 ${attempts - 1}次$span"
        }
        return "[$kind${model?.let { " $it" }.orEmpty()} $outcome] " + parts.joinToString("｜")
    }

    /** 输出总结；同一次请求只输出一次（成功路径和失败路径都会调用）。 */
    fun log(model: String?, outcome: String) {
        val line = synchronized(this) {
            if (logged) return
            logged = true
            summary(model, outcome)
        }
        AppLogger.i(TAG, line)
    }
}

/** 当前请求的计时卡；不在计时中的调用（测试连接、拉模型列表等）返回 null。 */
internal suspend fun currentLlmPerf(): LlmRequestPerf? = kotlin.coroutines.coroutineContext[LlmRequestPerf]

/** 把计时卡挂到请求上，网络监听据此记录连接、上传和等回复头的时间。 */
internal fun okhttp3.Request.withPerf(perf: LlmRequestPerf?): okhttp3.Request =
    if (perf == null) this else newBuilder().tag(LlmRequestPerf::class.java, perf).build()

/** 记录服务商把对话转成请求体的耗时。 */
internal inline fun <T> LlmRequestPerf?.timeBody(bytes: (T) -> Long, build: () -> T): T {
    val start = System.nanoTime()
    val result = build()
    this?.body((System.nanoTime() - start) / 1_000_000L, runCatching { bytes(result) }.getOrDefault(-1L))
    return result
}

/**
 * 转 JSON 内部的分段计时。只在 [collect] 包住的同步打包代码里生效（同一线程）；
 * 服务商打包时每做完一段调用一次 [lap]，从上一次记录到现在的耗时算在这一段上。
 * 不在计时中（测试连接、估算 token 等）时 [lap] 什么都不做。
 */
internal object LlmBodyStages {
    private class Laps {
        val ns = LinkedHashMap<String, Long>()
        var last = System.nanoTime()
    }

    private val current = ThreadLocal<Laps?>()

    fun lap(stage: String) {
        val laps = current.get() ?: return
        val t = System.nanoTime()
        laps.ns[stage] = (laps.ns[stage] ?: 0L) + (t - laps.last)
        laps.last = t
    }

    /**
     * 在当前线程上同步执行 [build]，成功后把各段耗时记到 [perf]。打包失败时不记，
     * 和 [timeBody] 只累加成功打包的总耗时保持一致。
     */
    fun <T> collect(perf: LlmRequestPerf?, build: () -> T): T {
        if (perf == null) return build()
        val previous = current.get()
        val laps = Laps()
        current.set(laps)
        try {
            val result = build()
            lap("其他")
            perf.bodyStages(laps.ns)
            return result
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
