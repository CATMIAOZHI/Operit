package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable

/** 收藏的模型引用：绑定到具体配置ID和模型名称 */
@Serializable
data class FavoriteModelRef(
    val configId: String,
    val modelName: String,
)

/** 带版本号的模型配置备份，包含顺序、收藏和折叠状态 */
@Serializable
data class ModelConfigBackup(
    val version: Int,
    val configs: List<ModelConfigData> = emptyList(),
    val favoriteModels: List<FavoriteModelRef> = emptyList(),
    val collapsedProviderIds: List<String> = emptyList(),
    val collapsedConfigIds: List<String> = emptyList(),
)

/** API提供商类型枚举 */
@Serializable
enum class ApiProviderType {
        OPENAI, // OpenAI (GPT系列)
        OPENAI_RESPONSES, // OpenAI Responses API
        OPENAI_RESPONSES_GENERIC, // OpenAI Responses通用（自定义端点）
        OPENAI_GENERIC, // OpenAI通用（自定义端点）
        ANTHROPIC, // Anthropic (Claude系列)
        ANTHROPIC_GENERIC, // Anthropic通用（自定义端点）
        GOOGLE, // Google (Gemini系列)
        GEMINI_GENERIC, // Gemini通用（自定义端点）
        BAIDU, // 百度 (文心一言系列)
        ALIYUN, // 阿里云 (通义千问系列)
        XUNFEI, // 讯飞 (星火认知系列)
        ZHIPU, // 智谱AI (ChatGLM系列)
        BAICHUAN, // 百川大模型
        MOONSHOT, // 月之暗面大模型
        MIMO, // Xiaomi MiMo
        DEEPSEEK, // Deepseek大模型
        MISTRAL, // Mistral AI (Codestral等)
        SILICONFLOW, // 硅基流动
        IFLOW, // iFlow
        OPENROUTER, // OpenRouter (多模型聚合)
        FOUR_ROUTER, // 4Router
        NOUS_PORTAL, // Nous Portal / Inference API
        INFINIAI, // 无问芯穹
        ALIPAY_BAILING, // 支付宝百灵大模型
        DOUBAO, // 豆包（火山模型）
        NVIDIA, // NVIDIA API Catalog / NIM
        LMSTUDIO, // LM Studio本地模型服务
        OLLAMA, // Ollama 本地/私有部署服务（OpenAI兼容）
        OPENAI_LOCAL, // OpenAI兼容本地模型服务
        MNN, // MNN本地推理引擎
        LLAMA_CPP, // llama.cpp 本地推理引擎
        PPINFRA, // 派欧云
        NOVITA, // Novita AI
        OTHER; // 其他提供商（自定义端点）

        companion object {
                fun fromProviderTypeId(providerTypeId: String): ApiProviderType? {
                        val normalized = providerTypeId.trim()
                        if (normalized.isEmpty()) {
                                return null
                        }
                        return values().firstOrNull {
                                it.name.equals(normalized, ignoreCase = true)
                        }
                }
        }
}

object ModelConfigDefaults {
        const val DEFAULT_CONTEXT_LENGTH = 64.0f
        const val DEFAULT_MAX_CONTEXT_LENGTH = 200.0f
        const val DEFAULT_ENABLE_MAX_CONTEXT_MODE = false
        const val DEFAULT_SUMMARY_TOKEN_THRESHOLD = 0.70f
        const val DEFAULT_ENABLE_SUMMARY = true
        const val DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT = true
        const val DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD = 16
}

/** 表示完整的模型配置，包括API设置和模型参数 */
@Serializable
data class ModelConfigData(
        val id: String,
        val name: String,

        // API设置
        val apiKey: String = "",
        val apiEndpoint: String = "",
        val modelName: String = "",
        val apiProviderType: ApiProviderType = ApiProviderType.DEEPSEEK,
        val apiProviderTypeId: String = apiProviderType.name,

        // 多API Key支持
        val useMultipleApiKeys: Boolean = false, // 是否启用多API Key模式
        val apiKeyPool: List<ApiKeyInfo> = emptyList(), // API Key池
        val currentKeyIndex: Int = 0, // 当前使用的Key索引
        val keyRotationMode: String = "ROUND_ROBIN", // 轮询模式: ROUND_ROBIN / RANDOM

        // 是否包含自定义参数
        val hasCustomParameters: Boolean = false,

        // 模型参数的enabled状态
        val maxTokensEnabled: Boolean = false,
        val temperatureEnabled: Boolean = false,
        val topPEnabled: Boolean = false,
        val topKEnabled: Boolean = false,
        val presencePenaltyEnabled: Boolean = false,
        val frequencyPenaltyEnabled: Boolean = false,
        val repetitionPenaltyEnabled: Boolean = false,

        // 模型参数值
        val maxTokens: Int = 4096,
        val temperature: Float = 1.0f,
        val topP: Float = 1.0f,
        val topK: Int = 0,
        val presencePenalty: Float = 0.0f,
        val frequencyPenalty: Float = 0.0f,
        val repetitionPenalty: Float = 1.0f,

        // 自定义参数JSON字符串
        val customParameters: String = "[]",

        // 自定义请求头JSON字符串
        val customHeaders: String = "{}",

        // 上下文/总结配置
        val contextLength: Float = ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH,
        val maxContextLength: Float = ModelConfigDefaults.DEFAULT_MAX_CONTEXT_LENGTH,
        val enableMaxContextMode: Boolean = ModelConfigDefaults.DEFAULT_ENABLE_MAX_CONTEXT_MODE,
        val summaryTokenThreshold: Float = ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD,
        val enableSummary: Boolean = ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY,
        val enableSummaryByMessageCount: Boolean =
                ModelConfigDefaults.DEFAULT_ENABLE_SUMMARY_BY_MESSAGE_COUNT,
        val summaryMessageCountThreshold: Int =
                ModelConfigDefaults.DEFAULT_SUMMARY_MESSAGE_COUNT_THRESHOLD,
        // 自定义总结规则
        val summaryCustomRules: String = "",

        // MNN特定配置
        // 注意：MNN模型路径会根据modelName自动构建，不需要单独存储
        val mnnForwardType: Int = 0, // 前向计算类型 (CPU/GPU等)
        val mnnThreadCount: Int = 4, // 推理线程数

        // llama.cpp 特定配置
        val llamaThreadCount: Int = 4, // 推理线程数
        val llamaContextSize: Int = 2048, // n_ctx
        val llamaBatchSize: Int = 512, // n_batch
        val llamaUBatchSize: Int = 512, // n_ubatch
        val llamaGpuLayers: Int = 0, // n_gpu_layers
        val llamaUseMmap: Boolean = false, // Android上默认关闭，减少mmap导致的兼容性问题
        val llamaFlashAttention: Boolean = false, // Android上默认关闭，更接近PocketPal安全值
        val llamaKvUnified: Boolean = true, // 单并发聊天默认开启统一KV缓存
        val llamaOffloadKqv: Boolean = false, // 仅在启用GPU层时有意义

        // 图片处理配置
        val enableDirectImageProcessing: Boolean = false, // 是否启用直接图片处理

        // 音频/视频处理配置
        val enableDirectAudioProcessing: Boolean = false, // 是否启用直接音频处理
        val enableDirectVideoProcessing: Boolean = false, // 是否启用直接视频处理

        // Gemini特定配置
        val enableGoogleSearch: Boolean = false, // 是否启用Google Search Grounding (仅Gemini支持)

        // Claude特定配置
        val enableClaude1hPromptCache: Boolean = false, // 是否启用1小时提示缓存TTL (仅Claude支持)

        // Tool Call配置
        val enableToolCall: Boolean = false, // 是否启用Tool Call接口调用工具（使用模型原生工具调用而非XML格式）

        // 请求频率限制配置
        val requestLimitPerMinute: Int = 0, // 每分钟最大请求次数，0表示不限流
        val maxConcurrentRequests: Int = 0 // 最大并发请求数，0表示不限制
)

/** 简化版的模型配置数据，用于列表显示 */
@Serializable
data class ModelConfigSummary(
        val id: String,
        val name: String,
        val modelName: String = "",
        val apiEndpoint: String = "",
        val apiProviderType: ApiProviderType = ApiProviderType.DEEPSEEK,
        val apiProviderTypeId: String = apiProviderType.name,
        val modelIndex: Int = 0 // 当modelName包含多个模型（逗号分隔）时，选择第几个模型（从0开始）
)

/** 从逗号分隔的模型名称字符串中根据索引获取具体模型 */
fun getModelByIndex(modelName: String, index: Int): String {
    if (modelName.isEmpty()) return ""
    val models = modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return if (index >= 0 && index < models.size) models[index] else models.getOrNull(0) ?: ""
}

/** 获取模型列表 */
fun getModelList(modelName: String): List<String> {
    if (modelName.isEmpty()) return emptyList()
    return modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/** 
 * 计算有效的模型索引（处理越界情况）
 * 如果索引超出范围，自动返回0（第一个模型）
 * @param modelName 逗号分隔的模型名称字符串
 * @param requestedIndex 请求的索引
 * @return 有效的索引值（0到模型数量-1之间）
 */
fun getValidModelIndex(modelName: String, requestedIndex: Int): Int {
    val modelList = getModelList(modelName)
    return if (requestedIndex >= 0 && requestedIndex < modelList.size) {
        requestedIndex
    } else {
        0 // 索引越界时使用第一个
    }
}

/** 规范化 provider ID：trim + 小写 */
fun normalizeProviderId(providerTypeId: String): String =
    providerTypeId.trim().lowercase()

/**
 * 在给定的 configSummaries 中解析收藏引用对应的模型索引。
 * 返回 null 表示无法解析（配置不存在或模型中无此模型名）。
 */
fun resolveFavoriteModelIndex(
    configSummaries: List<ModelConfigSummary>,
    favorite: FavoriteModelRef,
): Int? {
    val config = configSummaries.find { it.id == favorite.configId } ?: return null
    val models = getModelList(config.modelName)
    val index = models.indexOfFirst { it.equals(favorite.modelName, ignoreCase = true) }
    return if (index >= 0) index else null
}

/**
 * 过滤出在给定 configSummaries 中可解析的有效收藏引用，
 * 保留原有相对顺序，去重。
 */
fun resolveValidFavorites(
    configSummaries: List<ModelConfigSummary>,
    favorites: List<FavoriteModelRef>,
): List<FavoriteModelRef> {
    val seen = mutableSetOf<Pair<String, String>>()
    return favorites.filter { fav ->
        val key = fav.configId to fav.modelName
        if (key in seen) return@filter false
        seen.add(key)
        resolveFavoriteModelIndex(configSummaries, fav) != null
    }
}

/**
 * 将配置列表按折叠配置 ID 分区。
 * Pair.first = 正常配置, Pair.second = 折叠配置。
 * 各区内部保持原顺序。config ID 使用精确匹配。
 */
fun partitionConfigsByCollapsedIds(
    configSummaries: List<ModelConfigSummary>,
    collapsedConfigIds: Set<String>,
): Pair<List<ModelConfigSummary>, List<ModelConfigSummary>> {
    val normal = mutableListOf<ModelConfigSummary>()
    val collapsed = mutableListOf<ModelConfigSummary>()
    for (summary in configSummaries) {
        if (summary.id in collapsedConfigIds) {
            collapsed.add(summary)
        } else {
            normal.add(summary)
        }
    }
    return normal to collapsed
}

/**
 * 将配置列表按提供商折叠状态分区。
 * 已废弃：请用 partitionConfigsByCollapsedIds 代替。
 * Pair.first = 正常配置, Pair.second = 折叠配置。
 * 各区内部保持原顺序。
 */
@Deprecated("Use partitionConfigsByCollapsedIds for config-level collapse", ReplaceWith("partitionConfigsByCollapsedIds(configSummaries, collapsedConfigIds)"))
fun partitionConfigsByCollapsed(
    configSummaries: List<ModelConfigSummary>,
    collapsedProviderIds: Set<String>,
): Pair<List<ModelConfigSummary>, List<ModelConfigSummary>> {
    val normal = mutableListOf<ModelConfigSummary>()
    val collapsed = mutableListOf<ModelConfigSummary>()
    for (summary in configSummaries) {
        val normalizedId = normalizeProviderId(summary.apiProviderTypeId)
        if (normalizedId.isNotEmpty() && collapsedProviderIds.contains(normalizedId)) {
            collapsed.add(summary)
        } else {
            normal.add(summary)
        }
    }
    return normal to collapsed
}

/**
 * 合并本地与备份的配置折叠 ID。
 * 本地集合与备份集合取并集，然后过滤掉不在 mergedConfigIds 中的幽灵 ID。
 *
 * @param localIds 本地当前有效的折叠 config ID
 * @param backupIds 备份中的折叠 config ID（v1 导入时传空集合）
 * @param mergedConfigIds 合并后存在的所有 config ID
 * @return 合并过滤后的折叠 config ID 集合
 */
fun mergeCollapsedConfigIds(
    localIds: Set<String>,
    backupIds: Collection<String>,
    mergedConfigIds: Collection<String>,
): Set<String> {
    val mergedSet = mergedConfigIds.toSet()
    return (localIds + backupIds).filter { it in mergedSet }.toSet()
}

/**
 * 归一化配置ID顺序。
 * 1. 对请求 ID 去重并保持请求顺序。
 * 2. 忽略已不存在的配置 ID（不在 allCurrentIds 中）。
 * 3. 将当前存在但请求中缺失的 ID 追加到末尾。
 * 4. 每个当前配置 ID 恰好保留一次。
 * 5. 空请求保留全部当前 ID。
 *
 * @param requestedOrder 请求的配置 ID 顺序
 * @param allCurrentIds 当前存在的所有配置 ID（顺序无关，用于去重和补全）
 * @return 归一化后的配置 ID 列表
 */
fun normalizeConfigOrder(
    requestedOrder: List<String>,
    allCurrentIds: List<String>,
): List<String> {
    if (requestedOrder.isEmpty()) return allCurrentIds.toList()
    val currentSet = allCurrentIds.toSet()
    // 去重并保持请求顺序，忽略已不存在的 ID
    val seen = mutableSetOf<String>()
    val result = requestedOrder
        .filter { it in currentSet }
        .filter { seen.add(it) }
        .toMutableList()
    // 追加请求中缺失的当前 ID
    for (id in allCurrentIds) {
        if (id !in seen) {
            result.add(id)
            seen.add(id)
        }
    }
    return result
}

/**
 * 替代当前排除键比较，使用规范化 provider ID 避免大小写不一致。
 */
fun isProviderCollapsed(
    providerTypeId: String,
    collapsedProviderIds: Set<String>,
): Boolean {
    val normalized = normalizeProviderId(providerTypeId)
    return normalized.isNotEmpty() && collapsedProviderIds.contains(normalized)
}
