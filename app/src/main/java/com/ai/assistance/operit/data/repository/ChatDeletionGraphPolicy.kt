package com.ai.assistance.operit.data.repository

import com.ai.assistance.operit.data.model.ChatEntity

internal object ChatDeletionGraphPolicy {
    /**
     * Returns [rootId] and every id transitively reachable through the `chats.parentChatId` graph
     * (SUBAGENT and BRANCH descendants), in discovery order. A rootId absent from the graph still
     * yields a singleton set; callers verify existence separately.
     *
     * [childrenById] maps a parent id (null for roots) to its direct children ids.
     * The `add` deduplication guarantees termination even if the graph contains a cycle.
     */
    fun descendantClosure(
        rootId: String,
        childrenById: Map<String?, List<String>>,
    ): LinkedHashSet<String> {
        val subtree = linkedSetOf(rootId)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            childrenById[queue.removeFirst()]?.forEach { child ->
                if (subtree.add(child)) {
                    queue.add(child)
                }
            }
        }
        return subtree
    }

    fun selectDeletableChats(
        allChats: List<ChatEntity>,
        scopedChatIds: Set<String>,
    ): List<ChatEntity> {
        val protectedChatIds =
            allChats
                .asSequence()
                .filter { it.locked || it.id !in scopedChatIds }
                .mapTo(hashSetOf()) { it.id }
        var changed: Boolean
        do {
            changed = false
            allChats.forEach { chat ->
                if (chat.id in protectedChatIds) {
                    chat.parentChatId?.let { parentId ->
                        changed = protectedChatIds.add(parentId) || changed
                    }
                } else if (chat.parentChatId in protectedChatIds) {
                    changed = protectedChatIds.add(chat.id) || changed
                }
            }
        } while (changed)

        return allChats.filter { it.id in scopedChatIds && it.id !in protectedChatIds }
    }

    fun orderChildFirst(chats: List<ChatEntity>): List<ChatEntity> {
        val remaining = chats.toMutableList()
        val ordered = mutableListOf<ChatEntity>()
        while (remaining.isNotEmpty()) {
            val parentIds = remaining.mapNotNullTo(hashSetOf()) { it.parentChatId }
            val leaves = remaining.filter { it.id !in parentIds }
            check(leaves.isNotEmpty()) { "Chat parent graph contains a cycle" }
            ordered += leaves
            remaining.removeAll(leaves.toSet())
        }
        return ordered
    }
}
