package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.net.Uri
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * App sign-ins (mail connectors) that run on a provider's page in a tab of the user's browser
 * window. The provider ends by sending the tab to a return address no site serves
 * (`http://localhost/`, `https://localhost`). A sign-in tab, and any window it opens, refuses that
 * address for as long as it is open, so it never loads, nor lands in the history; with the
 * sign-in's random `state` it is handed to the sign-in. Sign-in tabs run no userscripts.
 */
internal object BrowserSignInCatch {
    sealed interface Outcome {
        /** The provider sent the tab back to the return address with this answer. */
        class Answered(val uri: Uri) : Outcome
        /** Every tab of the sign-in was closed before the provider answered. */
        object TabClosed : Outcome
        /** The tab could not be shown. */
        object NotShown : Outcome
    }

    private class SignIn(val redirect: Uri, val state: String) {
        val outcome = CompletableDeferred<Outcome>()
        /** The sign-in tab and the windows it opened. */
        val tabs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun isReturn(uri: Uri): Boolean =
            uri.scheme.equals(redirect.scheme, true) && uri.host.equals(redirect.host, true) &&
                uri.port == redirect.port && uri.path.orEmpty().ifEmpty { "/" } == redirect.path.orEmpty().ifEmpty { "/" }
    }

    /** Sign-ins by tab id; a tab is listed before it exists, so its first page is already guarded. */
    private val byTab = ConcurrentHashMap<String, SignIn>()
    /** Sign-ins by their first tab's id, until their last tab closes, also when that first one closed. */
    private val byFirstTab = ConcurrentHashMap<String, SignIn>()

    /**
     * Opens a sign-in: [open] gets the id the tab must be created with and says whether it shows.
     * Waits until the provider answers or the tab is closed. Cancelling stops the wait; the tab
     * keeps refusing the return address until it closes.
     */
    suspend fun await(redirect: String, state: String, open: suspend (tabId: String) -> Boolean): Outcome {
        val signIn = SignIn(Uri.parse(redirect), state)
        val tab = UUID.randomUUID().toString()
        signIn.tabs += tab
        byTab[tab] = signIn
        byFirstTab[tab] = signIn
        try {
            if (!open(tab)) return Outcome.NotShown
            return signIn.outcome.await()
        } finally {
            // A tab that never opened lets go now; an open one when it closes ([tabClosed]).
            if (!StandardBrowserSessionTools.sessions.containsKey(tab)) tabClosed(tab)
        }
    }

    /** Whether [tabId] is a sign-in tab, or a window one opened: it runs no userscripts. */
    fun isSignInTab(tabId: String): Boolean = byTab.containsKey(tabId)

    /** A sign-in tab opened a window, which is part of the sign-in from now on. */
    fun windowOpened(openerId: String, windowId: String) {
        val signIn = byTab[openerId] ?: return
        signIn.tabs += windowId
        byTab[windowId] = signIn
    }

    /**
     * The tabs still open of the sign-in [firstTab] started (its own first, then the windows it
     * opened), for showing or closing them together.
     */
    fun tabsOf(firstTab: String): List<String> {
        val tabs = byFirstTab[firstTab]?.tabs ?: return listOf(firstTab)
        return listOf(firstTab).filter { it in tabs } + tabs.filter { it != firstTab }
    }

    /** A tab closed; once a sign-in has none left, it ends unanswered. */
    fun tabClosed(tabId: String) {
        val signIn = byTab.remove(tabId) ?: return
        signIn.tabs.remove(tabId)
        if (signIn.tabs.isEmpty()) {
            byFirstTab.values.remove(signIn)
            signIn.outcome.complete(Outcome.TabClosed)
        }
    }

    /**
     * A main-frame navigation of [tabId] to [uri]. True when it is the sign-in's return address:
     * the tab must not load it. With the sign-in's state, the sign-in gets it.
     */
    fun offer(tabId: String, uri: Uri): Boolean {
        if (byTab.isEmpty()) return false
        val signIn = byTab[tabId] ?: return false
        if (!signIn.isReturn(uri)) return false
        if (runCatching { uri.getQueryParameter("state") }.getOrNull() == signIn.state) {
            signIn.outcome.complete(Outcome.Answered(uri))
        }
        return true
    }
}
