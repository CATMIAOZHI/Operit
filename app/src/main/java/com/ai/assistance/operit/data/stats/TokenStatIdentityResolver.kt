package com.ai.assistance.operit.data.stats

import java.security.MessageDigest

/**
 * 统计身份的规范化与稳定标识生成。
 *
 * - 身份 = (configId, provider, model)：同一 provider/model 在不同配置实例下是不同身份，
 *   旧 DataStore 累计数据不区分配置实例，使用空 [configId]。
 * - [identityId] 必须稳定：相同三元组总是得到相同 ID，避免重复入账或身份漂移。
 * - [normalizeModelName] 是展示合并的规范化依据：同名模型默认归入同一展示分组。
 */
object TokenStatIdentityResolver {

    /** 规范化模型名：trim + 小写 + 压缩连续空白，作为展示分组的默认 key。 */
    fun normalizeModelName(modelName: String): String =
        modelName.trim().lowercase().replace(Regex("\\s+"), " ")

    /** provider 标识规范化：trim + 小写（与配置系统 normalizeProviderId 一致）。 */
    fun normalizeProvider(provider: String): String = provider.trim().lowercase()

    /** 默认展示模型分组 ID：规范化模型名。 */
    fun displayModelIdFor(modelName: String): String = normalizeModelName(modelName)

    /** 生成稳定身份 ID（SHA-256），与展示名无关，只依赖身份三元组。 */
    fun identityId(configId: String, provider: String, model: String): String {
        val canonicalConfigId = configId.trim()
        val canonicalProvider = provider.trim()
        val canonicalModel = model.trim()
        require(canonicalProvider.isNotEmpty()) { "provider must not be blank" }
        require(canonicalModel.isNotEmpty()) { "model must not be blank" }
        val input = listOf(canonicalConfigId, canonicalProvider, canonicalModel)
            .joinToString(separator = "\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** 解析 “provider:model” 复合标识（旧系统约定）为 (provider, model)。 */
    fun splitProviderModel(providerModel: String): Pair<String, String> {
        val trimmed = providerModel.trim()
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex <= 0) return trimmed to ""
        return trimmed.substring(0, colonIndex) to trimmed.substring(colonIndex + 1)
    }
}
