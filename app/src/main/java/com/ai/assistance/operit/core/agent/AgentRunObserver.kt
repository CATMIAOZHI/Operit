package com.ai.assistance.operit.core.agent

import com.ai.assistance.operit.data.model.AITool
import java.util.concurrent.ConcurrentHashMap

/** Narrow opt-in lifecycle for internal agents. The shared engine knows no feature databases. */
interface AgentRunObserver {
    val capabilityTools: Set<String>
    suspend fun beforeToolBatch(tools: List<AITool>)
    fun onModelRequest()
}
object AgentRunObservers {
    private val observers = ConcurrentHashMap<String, AgentRunObserver>()
    fun register(chatId: String, observer: AgentRunObserver) { observers[chatId] = observer }
    fun forChat(chatId: String?): AgentRunObserver? = chatId?.let(observers::get)
    fun unregister(chatId: String) { observers.remove(chatId) }
    fun isCapabilityTool(name: String): Boolean = observers.values.any { name in it.capabilityTools }
}
