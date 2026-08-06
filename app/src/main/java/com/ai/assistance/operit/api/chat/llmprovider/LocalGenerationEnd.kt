package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.stats.ProviderUsageNormalizer
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.exceptions.UserCancellationException

/**
 * 本地 provider（Llama/MNN）生成结束的统一顺序契约（评审 P2-3 修复）。
 *
 * 顺序即契约，供两个 provider 共用并单独测试：
 * 1. **取消优先**：native 生成返回后，先判定 [cancelled]——取消时先上报已实测的
 *    usage，再抛 [UserCancellationException]，**绝不**转换/emit 不完整的工具 XML；
 * 2. 未取消才由 [emitToolResult] 处理工具缓冲（解析 + emit）；
 * 3. 成功路径上报 usage；失败路径（[success] = false）由 [failWith] 处理
 *    （保留用户可见错误文本并以失败异常终止），失败同样先上报 usage。
 *
 * 背景：旧实现先转换/emit 工具缓冲再检查 isCancelled，取消时会向调用方发出
 * 半截工具 XML，下游可能按完整工具调用执行导致错误落账。
 */
internal object LocalGenerationEnd {

    /**
     * @param cancelled 用户是否已取消（cancelStreaming 触发 native 停止）。
     * @param success native 生成是否正常结束（false = 失败或取消）。
     * @param inputTokens 已实测输入 token 数（tokenizer 计数）。
     * @param outputTokens 已生成输出 token 数（逐 token 实测）。
     * @param source 来源标签（SOURCE_LLAMA / SOURCE_MNN）。
     * @param cancelMessage 取消异常的用户可见消息。
     * @param onUsageReported usage 上报回调（统计账本通道）。
     * @param emitToolResult 未取消时的工具缓冲处理（解析/转换/emit）。
     * @param failWith 失败时的终止动作（错误文本 + 抛 IOException 等）。
     */
    suspend fun end(
        cancelled: Boolean,
        success: Boolean,
        inputTokens: Int,
        outputTokens: Int,
        source: String,
        cancelMessage: String,
        onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
        emitToolResult: suspend () -> Unit,
        failWith: suspend () -> Unit,
    ) {
        if (cancelled) {
            // 取消优先：先保留已实测 usage，再以取消异常结束——不 emit 工具缓冲
            reportUsage(inputTokens, outputTokens, source, onUsageReported)
            throw UserCancellationException(cancelMessage)
        }
        emitToolResult()
        reportUsage(inputTokens, outputTokens, source, onUsageReported)
        if (!success) {
            failWith()
        }
    }

    private suspend fun reportUsage(
        inputTokens: Int,
        outputTokens: Int,
        source: String,
        onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
    ) {
        onUsageReported?.invoke(
            ProviderUsageNormalizer.local(
                uncachedInputTokens = inputTokens,
                outputTokens = outputTokens,
                source = source,
            ),
            1,
        )
    }
}
