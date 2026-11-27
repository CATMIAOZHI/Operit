package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

/** The browser build opens its tabs on Cefrium. */
internal fun builtInBrowserEngine(): BrowserEngine? = CefriumEngine

/**
 * Before the engine's start-up provider runs. Most launches never open a page, so the engine's
 * library (about 145 MB of it) is not read ahead at every cold start; the first page opens a
 * little later instead.
 */
fun configureBrowserEngineStartup() {
    runCatching { com.cefrium.Cefrium.setLibraryPrefetchEnabled(false) }
}
