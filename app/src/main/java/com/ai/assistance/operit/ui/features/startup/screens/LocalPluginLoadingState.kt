package com.ai.assistance.operit.ui.features.startup.screens

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope

val LocalPluginLoadingState = staticCompositionLocalOf<PluginLoadingState> {
    error("LocalPluginLoadingState not provided")
}

object PluginLoadingStateRegistry {
    class Binding internal constructor()

    data class ActiveBinding internal constructor(
        val state: PluginLoadingState,
        val scope: CoroutineScope,
    )

    private val bindings = LinkedHashMap<Binding, ActiveBinding>()

    @Synchronized
    fun bind(state: PluginLoadingState, scope: CoroutineScope): Binding {
        val binding = Binding()
        bindings[binding] = ActiveBinding(state, scope)
        return binding
    }

    /** Returns true only when this removal leaves no Activity binding behind. */
    @Synchronized
    fun unbind(binding: Binding): Boolean {
        if (bindings.remove(binding) == null) return false
        return bindings.isEmpty()
    }

    @Synchronized
    fun isActive(binding: Binding): Boolean = bindings.keys.lastOrNull() === binding

    @Synchronized
    fun getActiveBinding(): ActiveBinding? = bindings.values.lastOrNull()

    fun getState(): PluginLoadingState? = getActiveBinding()?.state

    fun getScope(): CoroutineScope? = getActiveBinding()?.scope
}
