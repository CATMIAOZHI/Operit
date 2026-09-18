package com.ai.assistance.operit.data.stats

/**
 * 一次逻辑请求收到的 provider usage 记账，规则与统计账本完全一致。
 *
 * [TokenStatRequestContext] 是账本自己的那份；自动审核要在「预分类结果」页显示分类器这一次调用
 * 的用量，需要同一套算术，因此两边共用这里的实现。与用量统计页不一致的行比没有行更糟。
 *
 * - 每个 attempt 一项；同一 attempt 的重复上报按 [mergeSameAttemptUsage] 合并。
 * - 只有当 1..最高 attempt 全部上报时，[aggregateAttemptUsages] 才按分量累加；缺 attempt 时该分量
 *   保持未知，而不是偏小。
 * - 负值分量一律拒绝为未知；未知绝不报告为 0。
 */
internal class ProviderUsageAccumulator {
    /** attempt -> 该 attempt 自己的视图，同一 attempt 取最后一次上报。 */
    private val attemptUsages = LinkedHashMap<Int, ProviderUsageSnapshot>()

    /** 最后一次上报的快照（无论属于哪个 attempt）；从未上报时为 null。 */
    var lastUsage: ProviderUsageSnapshot? = null
        private set

    /** 收到上报的总次数（含重复上报）。 */
    var reportCount: Int = 0
        private set

    /** 见过的最大 attempt 序号；从未上报时为 0。 */
    var attemptCount: Int = 0
        private set

    fun onUsage(usage: ProviderUsageSnapshot, attempt: Int = 1) {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        val sanitized = sanitizeProviderUsage(usage)
        reportCount += 1
        lastUsage = sanitized
        attemptUsages[normalizedAttempt] =
            mergeSameAttemptUsage(attemptUsages[normalizedAttempt], sanitized)
        if (normalizedAttempt > attemptCount) {
            attemptCount = normalizedAttempt
        }
    }

    /** 整个请求的用量；provider 从未上报时为 null。 */
    fun aggregatedUsage(): ProviderUsageSnapshot? =
        aggregateAttemptUsages(attemptUsages, attemptCount)
}

/** 防御：负值分量一律拒绝为未知（真实负值只会来自异常 provider 数据）。 */
internal fun sanitizeProviderUsage(usage: ProviderUsageSnapshot): ProviderUsageSnapshot {
    fun nonNegative(value: Long?): Long? = value?.takeIf { it >= 0 }
    return ProviderUsageSnapshot(
        uncachedInputTokens = nonNegative(usage.uncachedInputTokens),
        cachedInputTokens = nonNegative(usage.cachedInputTokens),
        cacheWriteTokens = nonNegative(usage.cacheWriteTokens),
        totalInputTokens = nonNegative(usage.totalInputTokens),
        outputTokens = nonNegative(usage.outputTokens),
        reasoningTokens = nonNegative(usage.reasoningTokens),
        reasoningIncludedInOutput = usage.reasoningIncludedInOutput,
        cacheWriteSeparateBilling = usage.cacheWriteSeparateBilling,
        completeSnapshot = usage.completeSnapshot,
        source = usage.source,
    )
}

/**
 * 同一 attempt 的快照合并：
 * - 完整快照（[ProviderUsageSnapshot.completeSnapshot] = true）：整份覆盖，null 字段 = 明确未知
 *   （撤销旧值）；
 * - 部分更新（false）：最新上报的非空字段优先；新快照缺失的字段保留旧值。累计字段（output 等）
 *   直接取最新值，不能 start/delta 相加。
 */
internal fun mergeSameAttemptUsage(
    previous: ProviderUsageSnapshot?,
    latest: ProviderUsageSnapshot,
): ProviderUsageSnapshot {
    if (previous == null) return latest
    if (latest.completeSnapshot) return latest
    return ProviderUsageSnapshot(
        uncachedInputTokens = latest.uncachedInputTokens ?: previous.uncachedInputTokens,
        cachedInputTokens = latest.cachedInputTokens ?: previous.cachedInputTokens,
        cacheWriteTokens = latest.cacheWriteTokens ?: previous.cacheWriteTokens,
        totalInputTokens = latest.totalInputTokens ?: previous.totalInputTokens,
        outputTokens = latest.outputTokens ?: previous.outputTokens,
        reasoningTokens = latest.reasoningTokens ?: previous.reasoningTokens,
        reasoningIncludedInOutput =
            latest.reasoningIncludedInOutput ?: previous.reasoningIncludedInOutput,
        cacheWriteSeparateBilling = latest.cacheWriteSeparateBilling,
        completeSnapshot = false,
        source = latest.source,
    )
}

/**
 * 按 attempt 聚合后的 usage：分量在所有上报 attempt 中都已知时才求和（Long 饱和加法，绝不溢出为
 * 负），任一 attempt 该分量未知则聚合值保持未知；来源/包含推理声明取最后一次。没有任何上报时
 * 返回 null。
 */
internal fun aggregateAttemptUsages(
    attemptUsages: Map<Int, ProviderUsageSnapshot>,
    attemptCount: Int,
): ProviderUsageSnapshot? {
    val snapshots = attemptUsages.values.toList()
    if (snapshots.isEmpty()) return null
    val allAttemptsReported =
        attemptCount > 0 && (1..attemptCount).all { attempt -> attemptUsages.containsKey(attempt) }
    return ProviderUsageSnapshot(
        uncachedInputTokens =
            sumUsageComponent(snapshots, allAttemptsReported) { it.uncachedInputTokens },
        cachedInputTokens =
            sumUsageComponent(snapshots, allAttemptsReported) { it.cachedInputTokens },
        cacheWriteTokens =
            sumUsageComponent(snapshots, allAttemptsReported) { it.cacheWriteTokens },
        totalInputTokens =
            sumUsageComponent(snapshots, allAttemptsReported) { it.totalInputTokens },
        outputTokens = sumUsageComponent(snapshots, allAttemptsReported) { it.outputTokens },
        reasoningTokens =
            sumUsageComponent(snapshots, allAttemptsReported) { it.reasoningTokens },
        reasoningIncludedInOutput = snapshots.lastOrNull()?.reasoningIncludedInOutput,
        cacheWriteSeparateBilling = snapshots.lastOrNull()?.cacheWriteSeparateBilling ?: true,
        completeSnapshot = true,
        source = snapshots.lastOrNull()?.source ?: "unknown",
    )
}

private fun sumUsageComponent(
    snapshots: List<ProviderUsageSnapshot>,
    allAttemptsReported: Boolean,
    pick: (ProviderUsageSnapshot) -> Long?,
): Long? {
    if (!allAttemptsReported) return null
    val values = snapshots.mapNotNull(pick)
    if (values.size != snapshots.size) return null
    return values.fold(0L) { acc, value -> TokenCostCalculator.saturatedAdd(acc, value) }
}
