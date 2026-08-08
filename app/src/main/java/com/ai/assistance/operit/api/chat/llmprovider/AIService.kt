package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.Stream

/** AI服务接口，定义与不同AI提供商进行交互的标准方法 */
interface AIService {
    /** 输入token计数 (仅新增部分) */
    val inputTokenCount: Int

    /** 缓存命中的输入token计数 */
    val cachedInputTokenCount: Int

    /** 输出token计数 */
    val outputTokenCount: Int

    /** 获取供应商:模型标识符，格式如"DEEPSEEK:deepseek-chat" */
    val providerModel: String

    /** 重置token计数器 */
    fun resetTokenCounts()

    /** 取消当前流式传输 */
    fun cancelStreaming()

    /**
     * 获取模型列表
     *
     * @param context Android Context
     * @return 模型列表结果，成功返回模型列表，失败返回错误信息
     */
    suspend fun getModelsList(context: Context): Result<List<ModelOption>>

    /**
     * 发送消息到AI服务
     *
     * @param context Android Context
     * @param chatHistory 完整聊天历史，必须已包含本次最新输入
     * @param modelParameters 模型参数列表
     * @param enableThinking 是否启用思考模式
     * @param stream 是否使用流式输出，true为流式，false为非流式（但返回值仍为Stream）
     * @param availableTools 可用工具列表(用于Tool Call API)，如果为null则使用系统提示词中的工具描述
     * @param onTokensUpdated Token更新回调（UI 计数通道，可能携带估算值）
     * @param onUsageReported 规范化 usage 上报回调（统计账本通道；只在解析到
     *   provider 真实 usage/本地实测计数时回调，估算值不上报；可被多次调用，
     *   第二次参数为 provider 内部尝试序号 attempt（从 1 开始，内部重试递增），
     *   记录方按 attempt 聚合：同一 attempt 取最后一次，不同 attempt 累加）
     * @param onNonFatalError 非致命错误回调
     * @param enableRetry 是否允许内部重试
     * @param statsCategory 业务分类（统计账本）；null 表示调用方未声明（按 OTHER 记录）
     * @return 流式响应内容的Stream（无论stream参数如何，都返回Stream）
     */
    suspend fun sendMessage(
            context: Context,
            chatHistory: List<PromptTurn> = emptyList(),
            modelParameters: List<ModelParameter<*>> = emptyList(),
            enableThinking: Boolean = false,
            stream: Boolean = true,
            availableTools: List<ToolPrompt>? = null,
            preserveThinkInHistory: Boolean = false, // 新增参数，控制是否保留历史中的思考过程
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit = { _, _, _ -> },
            onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
            onNonFatalError: suspend (error: String) -> Unit = {},
            enableRetry: Boolean = true,
            statsCategory: TokenStatCategory? = null
    ): Stream<String>

    /**
     * 测试与AI服务的连接
     *
     * @param context Android Context
     * @param onUsageReported 与 [sendMessage] 相同的 usage 上报回调；实现内部
     *   通过 sendMessage 发起测试模型调用时必须透传，使探测用量进入统计账本。
     * @return 成功时返回成功信息，失败时返回包含错误的Result
     */
    suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)? = null
    ): Result<String>

    /**
     * 精确计算下一次请求的输入Token数量
     *
     * @param chatHistory 完整聊天历史，必须已包含本次最新输入
     * @param availableTools 可用工具列表（可选）
     * @return 估算的输入token总数 (包括缓存和新增部分)
     */
    suspend fun calculateInputTokens(
            chatHistory: List<PromptTurn>,
            availableTools: List<ToolPrompt>? = null
    ): Int

    /**
     * 释放资源
     * 对于本地模型（如MNN），需要释放native内存和模型资源
     * 对于API服务，通常不需要特别处理
     */
    fun release() {
        // 默认空实现，子类按需覆盖
    }
}
