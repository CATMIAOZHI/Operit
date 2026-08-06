package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenStatRequestContext
import com.ai.assistance.operit.data.stats.TokenStatSpool
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsLedger
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.RevisableTextStream
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TimeoutException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException as JavaTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/** A successful model call cannot hide loss of its accepted usage event. */
class TokenStatsPersistenceException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * 统一 usage 记录边界（阶段 2）：包装任意 [AIService]，在逻辑请求级别记录统计事件，
 * 覆盖普通响应、流式响应、正常结束、用户取消、超时与失败。
 *
 * 边界语义（稳定且可解释）：
 * - 一次 sendMessage 调用 = 一个事件。provider 内部重试不产生独立事件；不同 attempt
 *   的 usage 按分量累加（同一 attempt 重复上报按“最新非空字段”合并），最终只落一个
 *   事件，状态为最终结果。eventId 在请求开始时生成一次，配合 DAO 的 IGNORE 插入
 *   幂等防重。
 * - 首个真实内容 chunk（非空，含仅空白 chunk）到达时设置首 token 时间；
 *   空字符串 chunk 不记录；无内容响应保持 null。
 * - 取消（[CancellationException]）不吞掉：先落 CANCELLED 事件再原样重抛。
 * - 超时按异常类型识别：coroutine [TimeoutCancellationException] /
 *   [TimeoutException] / [SocketTimeoutException] / java timeout → TIMEOUT；
 *   [InterruptedIOException] 只有消息明确含 "timeout"（OkHttp 整调用超时）才算，
 *   线程中断等普通中断不算。明确的非超时取消（用户取消/协程取消）优先于 cause
 *   链中的 timeout 信号，避免 UserCancellationException(cause=InterruptedIOException)
 *   被误判为 TIMEOUT。
 * - 请求收尾同步解析价格并 fsync 完整事件到 TokenStatSpool，后台 writer 只做 Room
 *   insert。价格读取失败写 UNKNOWN；磁盘 append 失败会使成功调用明确失败。模型本身
 *   已失败/取消时，持久化故障作为 suppressed 异常保留，不覆盖原异常。
 * - 调用者 usage observer（外部回调）与 provider 业务隔离：非取消异常只记录日志，
 *   不改变账本/请求结果；取消遵循协程取消语义向上传播。
 */
class TokenTrackingAIService(
    private val delegate: AIService,
    private val context: Context,
    private val configId: String,
) : AIService {

    private val appContext: Context = context.applicationContext

    override val inputTokenCount: Int get() = delegate.inputTokenCount
    override val cachedInputTokenCount: Int get() = delegate.cachedInputTokenCount
    override val outputTokenCount: Int get() = delegate.outputTokenCount
    override val providerModel: String get() = delegate.providerModel

    override fun resetTokenCounts() = delegate.resetTokenCounts()
    override fun cancelStreaming() = delegate.cancelStreaming()
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        delegate.getModelsList(context)
    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?,
    ): Int = delegate.calculateInputTokens(chatHistory, availableTools)

    override fun release() = delegate.release()

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: TokenStatCategory?,
    ): Stream<String> {
        val request = newRequest(statsCategory)
        val delegateStream =
            delegate.sendMessage(
                context = context,
                chatHistory = chatHistory,
                modelParameters = modelParameters,
                enableThinking = enableThinking,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
                onTokensUpdated = onTokensUpdated,
                // 组合内部记录与调用者 callback：内部按 attempt 记账，调用者回调
                // 原样转发（每次上报都转发，不吞不重）；外部 observer 的异常与
                // provider 业务隔离（非取消只日志，取消仍传播）。
                onUsageReported = { usage, attempt ->
                    request.onUsage(usage, attempt)
                    forwardUsageObserver(onUsageReported, usage, attempt)
                },
                onNonFatalError = onNonFatalError,
                enableRetry = enableRetry,
                statsCategory = statsCategory,
            )
        return wrapStream(delegateStream, request)
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
    ): Result<String> {
        val request = newRequest(TokenStatCategory.CONNECTION_TEST)
        return try {
            val result =
                delegate.testConnection(context) { usage, attempt ->
                    request.onUsage(usage, attempt)
                    forwardUsageObserver(onUsageReported, usage, attempt)
                }
            // 失败 Result 也按统一 cause 分类（timeout/取消语义不丢），
            // 与抛出的异常走同一 classify。
            request.finish(
                result.exceptionOrNull()?.let { classify(it) } ?: TokenStatStatus.COMPLETED
            )
            val persistenceFailure = persistAndCapture(appContext, request, result.exceptionOrNull())
            when {
                result.isFailure -> result
                persistenceFailure != null -> Result.failure(persistenceFailure)
                else -> result
            }
        } catch (e: CancellationException) {
            request.finish(TokenStatStatus.CANCELLED)
            persistAndCapture(appContext, request, e)
            throw e
        } catch (e: Exception) {
            request.finish(classify(e))
            persistAndCapture(appContext, request, e)
            Result.failure(e)
        }
    }

    /**
     * 转发外部 usage observer 并隔离异常：调用者 callback 的非取消异常只记录日志，
     * 不进入 provider 解析/重试控制流，也不改变账本与请求结果；取消（调用者协程
     * 取消）原样向上传播，遵循协程取消语义。
     */
    private suspend fun forwardUsageObserver(
        observer: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
        usage: ProviderUsageSnapshot,
        attempt: Int,
    ) {
        val callback = observer ?: return
        try {
            callback(usage, attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "调用者 usage observer 异常，不影响请求与账本", e)
        }
    }

    private suspend fun newRequest(category: TokenStatCategory?): TokenStatRequestContext {
        // P1 终审：restore 替换开始后本进程不再接受新的统计请求（直到进程重启，UI 允许
        // 稍后重启）——在此明确拒绝开始新跟踪请求，绝不等到收尾才失败，也绝不写入已恢复
        // 替换的数据库。替换前失败的 restore 不置位该标志，新请求照常继续。
        if (!TokenStatSpool.isAcceptingEvents()) {
            throw TokenStatsPersistenceException(
                "Token statistics are not accepting new events until the app restarts after a restore",
            )
        }
        val (provider, model) = TokenStatIdentityResolver.splitProviderModel(delegate.providerModel)
        val acceptedGeneration = TokenStatsLedger.currentResetGeneration(appContext)
        return TokenStatRequestContext(
            eventId = "evt_${UUID.randomUUID().toString().replace("-", "")}",
            category = category ?: TokenStatCategory.OTHER,
            configId = configId,
            provider = provider,
            model = model,
            startedAtMs = System.currentTimeMillis(),
            acceptedGeneration = acceptedGeneration,
            // P1 终审：请求开始时同步捕获 restore epoch（纯内存、无 Room），收尾 append
            // 时验证——restore 屏障开始即递增 epoch，旧请求被明确拒绝，不写新 DB。
            sessionEpoch = TokenStatSpool.captureRestoreEpoch(),
        )
    }

    /** 保持修订流语义：内部流带 eventChannel 时返回同接口的包装流。 */
    private fun wrapStream(
        delegateStream: Stream<String>,
        request: TokenStatRequestContext,
    ): Stream<String> {
        return if (delegateStream is TextStreamEventCarrier) {
            TrackingRevisableStream(
                inner = delegateStream,
                eventChannel = delegateStream.eventChannel,
                request = request,
                appContext = appContext,
            )
        } else {
            TrackingStream(inner = delegateStream, request = request, appContext = appContext)
        }
    }

    private class TrackingStream(
        private val inner: Stream<String>,
        private val request: TokenStatRequestContext,
        private val appContext: Context,
    ) : Stream<String> {
        override val isLocked: Boolean get() = inner.isLocked
        override val bufferedCount: Int get() = inner.bufferedCount
        override suspend fun lock() = inner.lock()
        override suspend fun unlock() = inner.unlock()
        override fun clearBuffer() = inner.clearBuffer()

        override suspend fun collect(collector: StreamCollector<String>) {
            var sawFirstToken = false
            try {
                inner.collect { value ->
                    // 仅空白 chunk 也是真实输出内容（首空格/换行 token），
                    // 只有空字符串 chunk 不记录首 token。
                    if (!sawFirstToken && value.isNotEmpty()) {
                        sawFirstToken = true
                        request.onFirstToken()
                    }
                    collector.emit(value)
                }
            } catch (t: Throwable) {
                request.finish(classify(t))
                persistAndCapture(appContext, request, t)
                throw t
            }
            request.finish(TokenStatStatus.COMPLETED)
            recordSafely(appContext, request)
        }
    }

    private class TrackingRevisableStream(
        private val inner: Stream<String>,
        override val eventChannel: SharedStream<TextStreamEvent>,
        private val request: TokenStatRequestContext,
        private val appContext: Context,
    ) : RevisableTextStream {
        override val isLocked: Boolean get() = inner.isLocked
        override val bufferedCount: Int get() = inner.bufferedCount
        override suspend fun lock() = inner.lock()
        override suspend fun unlock() = inner.unlock()
        override fun clearBuffer() = inner.clearBuffer()

        override suspend fun collect(collector: StreamCollector<String>) {
            var sawFirstToken = false
            try {
                inner.collect { value ->
                    if (!sawFirstToken && value.isNotEmpty()) {
                        sawFirstToken = true
                        request.onFirstToken()
                    }
                    collector.emit(value)
                }
            } catch (t: Throwable) {
                request.finish(classify(t))
                persistAndCapture(appContext, request, t)
                throw t
            }
            request.finish(TokenStatStatus.COMPLETED)
            recordSafely(appContext, request)
        }
    }

    companion object {
        private const val TAG = "TokenTrackingAIService"

        /** 单次统计落账的有界等待时长；测试可缩短以验证超时只日志不阻塞业务。 */
        internal var recordTimeoutMs: Long = 5_000L

        /**
         * 单次落账结果（评审 P1-1/P1-4：进程死亡边界必须向调用方暴露统计失败
         * 状态，不能伪装已记录）：
         * [DURABLE] means the complete event has been fsynced. Non-durable outcomes throw.
         */
        internal enum class RecordOutcome {
            DURABLE,
        }

        /**
         * 可靠、独立、持久落账（companion 版本，供嵌套流类使用）：
         * - 在请求收尾边界有界解析并冻结价格，随后同步 fsync 完整事件；
         * - 价格超时/失败形成 UNKNOWN 事件，append 失败抛明确持久化故障；
         * - [recordTimeoutMs] 只等待可选的 Room 可见性，不参与 durable 判定；
         * - 进程重启后由 OperitApplication 主动 [com.ai.assistance.operit.data.stats.TokenStatSpool.replay]
         *   重放（幂等 eventId IGNORE）。
         */
        internal suspend fun recordSafely(
            appContext: Context,
            request: TokenStatRequestContext,
        ): RecordOutcome {
            return withContext(NonCancellable) {
                val baseJson = request.toSpoolBaseJson()
                val line =
                    try {
                        prepareLineBounded(appContext, request)
                    } catch (e: JavaTimeoutException) {
                        TokenStatsLedger.prepareUnresolvedEventLine(
                            request,
                            baseJson,
                            "pricing_read_timeout",
                        )
                    } catch (e: Exception) {
                        TokenStatsLedger.prepareUnresolvedEventLine(
                            request,
                            baseJson,
                            "pricing_read_failed:${e.javaClass.simpleName}",
                        )
                    }
                if (!TokenStatSpool.append(appContext, line, request.eventId, request.sessionEpoch)) {
                    throw TokenStatsPersistenceException(
                        "Token statistics could not be durably persisted for ${request.eventId}",
                    )
                }
                TokenStatSpool.awaitRoomVisibility(request.eventId, recordTimeoutMs)
                RecordOutcome.DURABLE
            }
        }

        /**
         * Bounded pricing worker (P2-1): one daemon thread plus one queue slot. A wedged price
         * resolution cannot spawn unbounded threads; queue saturation immediately reports UNKNOWN
         * instead of starting more work.
         */
        private var pricingExecutor: ThreadPoolExecutor = newPricingExecutor()

        private fun newPricingExecutor() =
            ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(1),
            ) { runnable -> Thread(runnable, "operit-token-stats-price").apply { isDaemon = true } }

        /** Discard a wedged pricing worker (interrupt-ignoring resolution) between tests. */
        internal fun resetPricingExecutorForTest() {
            pricingExecutor.shutdownNow()
            pricingExecutor = newPricingExecutor()
        }

        /**
         * Pricing resolution with a genuinely bounded lifecycle. The worker builds its own line
         * from a fresh base JSON via [com.ai.assistance.operit.data.stats.TokenStatsLedger.prepareEventLineDetached]
         * and never mutates [request] or any shared serialization object, so a timed-out task can
         * never race the caller's UNKNOWN fallback on the same objects (P2-1).
         */
        private fun prepareLineBounded(
            appContext: Context,
            request: TokenStatRequestContext,
        ): String {
            val task = FutureTask {
                runBlocking { TokenStatsLedger.prepareEventLineDetached(appContext, request) }
            }
            try {
                pricingExecutor.execute(task)
            } catch (e: RejectedExecutionException) {
                // Saturation (a previous resolution still wedged): report UNKNOWN immediately,
                // never start additional threads or queue unbounded work.
                throw JavaTimeoutException("pricing executor saturated")
            }
            return try {
                val result = task.get(TokenStatSpool.prepareTimeoutMs, TimeUnit.MILLISECONDS)
                // Apply the frozen snapshot on the caller thread only after success; the worker
                // never touches request, so these assignments cannot race a discarded task.
                request.frozenPricing = result.frozenPricing
                request.pricingResolutionDiagnostic = result.diagnostic
                result.line
            } catch (e: JavaTimeoutException) {
                task.cancel(true)
                throw e
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }

        /** Keep the model error primary; persistence failure remains observable as suppressed. */
        private suspend fun persistAndCapture(
            appContext: Context,
            request: TokenStatRequestContext,
            original: Throwable?,
        ): TokenStatsPersistenceException? =
            try {
                recordSafely(appContext, request)
                null
            } catch (e: TokenStatsPersistenceException) {
                original?.addSuppressed(e)
                e
            }

        /**
         * 结束状态分类。明确的非超时取消（用户取消/协程取消）优先：其 cause 链里
         * 可能带 InterruptedIOException（如 OkHttp 中断），不能误判为超时；只有
         * [TimeoutCancellationException] 才是超时。随后沿 cause chain 识别 provider
         * 重试耗尽时把超时包装成 IOException 的情况；[InterruptedIOException]
         * 只有消息明确含 "timeout"（OkHttp 整调用超时）才算超时，线程中断等不算。
         */
        internal fun classify(t: Throwable): TokenStatStatus = when {
            isExplicitCancellation(t) -> TokenStatStatus.CANCELLED
            isTimeout(t) -> TokenStatStatus.TIMEOUT
            else -> TokenStatStatus.FAILED
        }

        private fun isExplicitCancellation(t: Throwable): Boolean =
            t is CancellationException && t !is TimeoutCancellationException

        private fun isTimeout(t: Throwable): Boolean {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                when {
                    current is TimeoutCancellationException ||
                        current is TimeoutException ||
                        current is java.util.concurrent.TimeoutException ||
                        current is SocketTimeoutException ||
                        (current is InterruptedIOException &&
                            current.message?.contains("timeout", ignoreCase = true) == true)
                    -> return true
                }
                current = current.cause
                depth++
            }
            return false
        }

        private const val MAX_CAUSE_DEPTH = 8
    }
}
