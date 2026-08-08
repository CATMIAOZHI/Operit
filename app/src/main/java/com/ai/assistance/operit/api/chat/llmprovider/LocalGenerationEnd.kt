package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class LocalUsageReporter(
    private val source: String,
    private val onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
) {
    private val reported = AtomicBoolean(false)

    suspend fun report(inputTokens: Int, outputTokens: Int) {
        val callback = onUsageReported ?: return
        if (!reported.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            try {
                callback(
                    ProviderUsageNormalizer.local(
                        uncachedInputTokens = inputTokens,
                        outputTokens = outputTokens,
                        source = source,
                    ),
                    1,
                )
            } catch (_: Exception) {
                // Usage accounting must not replace the generation result or cancellation cause.
            }
        }
    }

    suspend fun <T> runReportingFinally(
        inputTokens: () -> Int,
        outputTokens: () -> Int,
        block: suspend () -> T,
    ): T = try {
        block()
    } finally {
        report(inputTokens(), outputTokens())
    }
}

/**
 * 本地 provider（Llama/MNN）生成结束的统一顺序契约（评审 P2-3 修复）。
 *
 * 顺序即契约，供两个 provider 共用并单独测试：
 * 1. **取消优先**：native 生成返回后，先判定 [cancelled]——取消时先上报已实测的
 *    usage，再抛 [UserCancellationException]，**绝不**转换/emit 不完整的工具 XML；
 * 2. **失败次之**：未取消但 [success] = false 时，先上报已实测 usage，再由
 *    [failWith] 终止（保留用户可见错误文本并以失败异常结束），失败路径同样
 *    **绝不**转换/emit 工具缓冲；
 * 3. **成功最后**：仅成功路径（[success] = true）由 [emitToolResult] 处理工具
 *    缓冲（解析 + emit），随后上报 usage。
 *
 * 背景：旧实现先转换/emit 工具缓冲再检查 isCancelled，取消时会向调用方发出
 * 半截工具 XML，下游可能按完整工具调用执行导致错误落账。
 */
internal object LocalGenerationEnd {

    /**
     * @param cancelled 用户是否已取消（cancelStreaming 触发 native 停止）。
     * @param success native 生成是否正常结束（false = 失败或取消）。
     * @param usageReporter 本次生成共享的一次性 usage 上报器。
     * @param inputTokens 已实测输入 token 数（tokenizer 计数）。
     * @param outputTokens 已生成输出 token 数（逐 token 实测）。
     * @param cancelMessage 取消异常的用户可见消息。
     * @param emitToolResult 仅成功路径的工具缓冲处理（解析/转换/emit）。
     * @param failWith 失败时的终止动作（错误文本 + 抛 IOException 等）。
     */
    suspend fun end(
        cancelled: Boolean,
        success: Boolean,
        usageReporter: LocalUsageReporter,
        inputTokens: Int,
        outputTokens: Int,
        cancelMessage: String,
        emitToolResult: suspend () -> Unit,
        failWith: suspend () -> Unit,
    ) {
        if (cancelled) {
            // 取消优先：先保留已实测 usage，再以取消异常结束——不 emit 工具缓冲
            usageReporter.report(inputTokens, outputTokens)
            throw UserCancellationException(cancelMessage)
        }
        if (!success) {
            // 失败次之：不转换/emit 不完整的工具缓冲，先上报已实测 usage 再终止
            usageReporter.report(inputTokens, outputTokens)
            failWith()
            return
        }
        // 成功最后：仅成功路径处理工具缓冲（解析 + emit），随后上报 usage
        emitToolResult()
        usageReporter.report(inputTokens, outputTokens)
    }
}
