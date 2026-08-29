package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.data.model.AITool
import org.json.JSONObject

/**
 * 阅读伴侣段评审计子代理的死循环护栏（纯逻辑，JVM 可测）。
 *
 * 不是模型 round / 工具次数上限，只拦截两类确定性的死循环：
 * 1. [recordCall]：同一工具 + 规范化参数连续 3 次 -> [ReadingCompanionCallVerdict.LOOP_DETECTED]
 *    （由 ToolExecutionManager 在权限审查前调用，绝不落到 requestExplicitApproval 弹框）。
 * 2. [recordResult]：同一“调用 + 结果”组合在无新增证据（候选修订/检索命中）期间出现
 *    3 次 -> [ReadingCompanionProgressVerdict.NO_PROGRESS]（由工具执行器在结果产出后调用）。
 *
 * 两个判定都只在触发时各写一次 run trace，trace 内容不含正文与密钥。
 */
class ReadingCompanionLoopGuard(
    val runId: Long,
    private val traceSink: (operation: String, status: String, metadataJson: String?) -> Unit =
        { _, _, _ -> },
) {
    private var previousCallFingerprint: String? = null
    private var consecutiveCallCount: Int = 0

    private val pairHistory = ArrayDeque<String>()
    private val pairCounts = HashMap<String, Int>()

    /** 同一工具 + 规范化参数连续 3 次返回 [ReadingCompanionCallVerdict.LOOP_DETECTED]。 */
    fun recordCall(toolName: String, normalizedArguments: String): ReadingCompanionCallVerdict {
        val fingerprint = "$toolName\u0001$normalizedArguments"
        consecutiveCallCount =
            if (fingerprint == previousCallFingerprint) consecutiveCallCount + 1 else 1
        previousCallFingerprint = fingerprint
        if (consecutiveCallCount < 3) return ReadingCompanionCallVerdict.OK
        trace(
            operation = "loop_guard",
            status = "loop_detected",
            metadata = JSONObject()
                .put("runId", runId)
                .put("tool", toolName)
                .put("consecutiveCalls", consecutiveCallCount)
                .toString(),
        )
        return ReadingCompanionCallVerdict.LOOP_DETECTED
    }

    /**
     * 记录一次已完成调用的“调用 + 结果”指纹。
     *
     * [evidenceAdvanced] 表示该调用带来了新增证据（候选被接受修订等），会清空计数重新起算；
     * 否则同一组合累积出现 3 次且期间无新增证据 -> [ReadingCompanionProgressVerdict.NO_PROGRESS]。
     */
    fun recordResult(
        toolName: String,
        normalizedArguments: String,
        resultFingerprint: String,
        evidenceAdvanced: Boolean = false,
    ): ReadingCompanionProgressVerdict {
        val pair = "$toolName\u0001$normalizedArguments\u0001$resultFingerprint"
        if (evidenceAdvanced) {
            // 出现了新增证据（候选被接受修订）：清空重复计数，重新起算证据窗口。
            pairHistory.clear()
            pairCounts.clear()
            return ReadingCompanionProgressVerdict.OK
        }
        pairHistory.addLast(pair)
        if (pairHistory.size > MAX_TRACKED_PAIRS) {
            val evicted = pairHistory.removeFirst()
            val remaining = pairCounts.getValue(evicted) - 1
            if (remaining <= 0) {
                pairCounts.remove(evicted)
            } else {
                pairCounts[evicted] = remaining
            }
        }
        val count = pairCounts.merge(pair, 1, Int::plus) ?: 1
        if (count >= 3) {
            trace(
                operation = "loop_guard",
                status = "no_progress",
                metadata = JSONObject()
                    .put("runId", runId)
                    .put("tool", toolName)
                    .put("repeatCount", count)
                    .toString(),
            )
            return ReadingCompanionProgressVerdict.NO_PROGRESS
        }
        return ReadingCompanionProgressVerdict.OK
    }

    private fun trace(operation: String, status: String, metadata: String) {
        traceSink(operation, status, metadata)
    }

    companion object {
        /** 同一 run 内最多追踪的“调用 + 结果”组合数（防止无界增长；不是工具次数上限）。 */
        private const val MAX_TRACKED_PAIRS = 12
    }
}

enum class ReadingCompanionCallVerdict { OK, LOOP_DETECTED }

enum class ReadingCompanionProgressVerdict { OK, NO_PROGRESS }

/**
 * 阅读伴读子代理路径的终止护栏异常。reason 取值：loop_detected / no_progress / claim_lost。
 *
 * 该异常绝不触发权限确认弹框；由 EnhancedAIService 失败整轮并保留旧段评。
 */
class ReadingCompanionLoopException(
    val runId: Long,
    val reason: String,
    message: String,
) : IllegalStateException(message)

/**
 * 把一次工具调用规范化为“工具名 + 排序后的参数名=值”。参数顺序不同的等价调用得到同一指纹。
 */
fun normalizeReadingCompanionToolCall(tool: AITool): String =
    tool.parameters
        .sortedBy { it.name }
        .joinToString("&", prefix = "${tool.name}:") { parameter ->
            "${parameter.name}=${parameter.value.trim()}"
        }
