package com.ai.assistance.operit.ui.main.navigation

import androidx.lifecycle.ViewModel

/** Keeps the complete route stack when the Activity or its setContent is recreated. */
class AppNavigationViewModel : ViewModel() {
    private var routerState: AppRouterState? = null
    var restoredEntry: RouteEntry? = null
        private set

    val currentEntry: RouteEntry?
        get() = routerState?.currentEntry ?: restoredEntry

    fun restoreCurrentEntry(entry: RouteEntry) {
        if (routerState == null) restoredEntry = entry
    }

    fun getOrCreateRouter(initialEntry: () -> RouteEntry): AppRouterState =
        routerState ?: AppRouterState(initialEntry()).also { routerState = it }
}
