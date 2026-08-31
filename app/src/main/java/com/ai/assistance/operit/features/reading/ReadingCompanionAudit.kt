package com.ai.assistance.operit.features.reading

/**
 * 阅读伴侣段评审计（subagent 形态）的稳定标识与判定。
 *
 * 审计聊天以 hiddenReason 前缀区分：每书一个隐藏 NORMAL 父聊天
 * （READING_COMPANION_AUDIT_ROOT:<bookId>），每次 run 一个隐藏 SUBAGENT 子聊天
 * （READING_COMPANION_AUDIT_RUN:<runId>）。永久隐藏只看前缀，与主库
 * [ReadingCompanionStore] 的常量保持一致（同一来源避免漂移）。
 */
object ReadingCompanionAudit {
    private data class AuditNavigationReturn(
        val auditChildChatId: String,
        val returnChatId: String,
    )

    @Volatile
    private var auditNavigationReturn: AuditNavigationReturn? = null

    /** 主库 subagent_runs.externalOwnerType 的阅读伴侣取值（跨库弱关联）。 */
    const val OWNER_TYPE: String = READING_COMPANION_SUBAGENT_OWNER_TYPE

    /** 每书审计根聊天 hiddenReason 前缀（root:<bookId>）。 */
    const val HIDDEN_ROOT_PREFIX: String = READING_COMPANION_HIDDEN_ROOT_REASON_PREFIX

    /** 每次 run 审计子聊天 hiddenReason 前缀（run:<runId>）。 */
    const val HIDDEN_RUN_PREFIX: String = READING_COMPANION_HIDDEN_RUN_REASON_PREFIX

    /** 阅读伴侣审计 subagent 的内置 profile id（hidden，只能内部 coordinator 使用）。 */
    const val PROFILE_ID: String = "reading_companion_audit"

    /** 对话内触发来源（现网无此路径，阶段 2 新增）。 */
    const val TRIGGER_CONVERSATION: String = "conversation"

    /**
     * hiddenReason 是否属于阅读伴侣永久隐藏的审计聊天。
     *
     * 审计根与审计 run 子聊天均不可取消隐藏；普通隐藏聊天不受影响。
     */
    fun isPermanentHiddenReason(reason: String?): Boolean =
        reason?.startsWith(HIDDEN_ROOT_PREFIX) == true ||
            reason?.startsWith(HIDDEN_RUN_PREFIX) == true

    /** 是否是用户从阅读伴侣记录页打开的隐藏审计子聊天。 */
    fun isHiddenAuditRun(reason: String?): Boolean =
        reason?.startsWith(HIDDEN_RUN_PREFIX) == true

    /**
     * 记住打开隐藏审计聊天前的可见聊天。返回时先恢复它，再退出聊天路由，避免当前聊天
     * 落到隐藏父根；若没有安全来源则清掉旧映射，由 UI 选择任一可见聊天兜底。
     */
    @Synchronized
    fun rememberReturnChat(auditChildChatId: String, returnChatId: String?) {
        val childId = auditChildChatId.trim()
        if (childId.isEmpty()) return
        val safeReturnId =
            returnChatId
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != childId }
        if (safeReturnId == null) {
            auditNavigationReturn = null
        } else {
            auditNavigationReturn =
                AuditNavigationReturn(
                    auditChildChatId = childId,
                    returnChatId = safeReturnId,
                )
        }
    }

    /** 一次性取出隐藏审计聊天的安全返回聊天。 */
    @Synchronized
    fun takeReturnChat(auditChildChatId: String): String? {
        val pending = auditNavigationReturn ?: return null
        if (pending.auditChildChatId != auditChildChatId.trim()) return null
        auditNavigationReturn = null
        return pending.returnChatId
    }

    /** 聊天列表尚未加载时，仍可凭当前 chatId 识别本次隐藏审计导航。 */
    @Synchronized
    fun hasPendingReturnFor(auditChildChatId: String?): Boolean =
        auditNavigationReturn?.auditChildChatId == auditChildChatId?.trim()

    /** 在审计子聊天之间切换时沿用最初的安全返回点。 */
    @Synchronized
    fun carryReturnChat(fromAuditChildChatId: String, toAuditChildChatId: String) {
        val pending = auditNavigationReturn ?: return
        if (pending.auditChildChatId != fromAuditChildChatId.trim()) return
        val nextChildId = toAuditChildChatId.trim()
        if (nextChildId.isEmpty()) return
        auditNavigationReturn =
            pending.copy(auditChildChatId = nextChildId)
    }

    /** 构造每书审计根聊天的 hiddenReason。 */
    fun rootHiddenReason(bookId: String): String = "$HIDDEN_ROOT_PREFIX$bookId"

    /** 构造每次 run 审计子聊天的 hiddenReason。 */
    fun runHiddenReason(runId: Long): String = "$HIDDEN_RUN_PREFIX$runId"

    /**
     * 受限宿主动作 openReadingAuditChat 的 run 级授权判定（纯函数，JVM 可测）。
     *
     * 隐藏路径与对话内路径都要求 child 的 subagent run 弱关联与 reading run 一致
     * （externalOwnerType=OWNER_TYPE、externalOwnerId=runId、subagentRunId 一致）且
     * chat.parentChatId == run.parentChatId；隐藏路径额外要求 hiddenReason 精确等于
     * [runHiddenReason]（前缀相同但 run 不同即拒），并校验父根 hiddenReason 与 run.bookId
     * 匹配（防他书错链）。runBookId 为空时跳过父根校验（老数据兜底，不扩大授权面）。
     */
    fun isAuthorizedAuditChat(
        runId: Long,
        runBookId: String?,
        runParentChatId: String?,
        runSubagentRunId: String?,
        chatIsHidden: Boolean,
        chatHiddenReason: String?,
        chatParentChatId: String?,
        chatParentHiddenReason: String?,
        subagentOwnerType: String?,
        subagentOwnerId: String?,
        subagentRunId: String?,
    ): Boolean {
        if (runParentChatId.isNullOrBlank()) return false
        val ownerMatches =
            subagentOwnerType == OWNER_TYPE &&
                subagentOwnerId == runId.toString() &&
                runSubagentRunId != null &&
                subagentRunId == runSubagentRunId
        if (!ownerMatches || chatParentChatId != runParentChatId) return false
        if (!chatIsHidden) {
            // 对话内路径：child 可见（isHidden=false，无审计 hiddenReason），parent 是用户聊天；
            // owner 链与 parent 绑定已通过即视为已授权。
            return true
        }
        val reasonMatches = chatHiddenReason == runHiddenReason(runId)
        val rootMatches =
            runBookId.isNullOrBlank() ||
                chatParentHiddenReason == rootHiddenReason(runBookId)
        return reasonMatches && rootMatches
    }
}
