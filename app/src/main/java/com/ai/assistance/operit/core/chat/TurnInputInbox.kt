package com.ai.assistance.operit.core.chat

/** Serializes input acceptance with the model loop's decision to finish the turn. */
class TurnInputInbox {
    val turnId: String = java.util.UUID.randomUUID().toString()
    private val lock = Any()
    private var open = true
    data class Input(
        val text: String,
        val consumed: () -> Unit = {},
        val returned: () -> Unit = {},
        val agentPath: String? = null,
        val startsAgentTurn: Boolean = false,
    )
    private val pending = ArrayDeque<Input>()
    private var delivering: List<Input> = emptyList()
    enum class PendingInputKind { USER, AGENT }
    fun pendingInputKind(): PendingInputKind? = synchronized(lock) {
        when {
            pending.any { it.agentPath == null } -> PendingInputKind.USER
            pending.isNotEmpty() -> PendingInputKind.AGENT
            else -> null
        }
    }
    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }
    fun hasPendingUserInput(): Boolean = synchronized(lock) { pending.any { it.agentPath == null } }

    fun offer(input: Input): Boolean = synchronized(lock) {
        if (!open || input.text.isBlank()) return@synchronized false
        pending.addLast(input)
        true
    }

    fun drain(): List<Input> = synchronized(lock) {
        check(delivering.isEmpty())
        pending.toList().also { pending.clear(); delivering = it }
    }

    fun acknowledge() {
        val inputs = synchronized(lock) { delivering.also { delivering = emptyList() } }
        inputs.forEach { it.consumed() }
    }

    fun seal() = synchronized(lock) {
        open = false
    }

    fun finishIfEmpty(): Boolean = synchronized(lock) {
        if (pending.isNotEmpty()) return@synchronized false
        open = false
        true
    }

    fun close(): List<Input> = synchronized(lock) {
        open = false
        (delivering + pending).also { pending.clear(); delivering = emptyList() }
    }
}
