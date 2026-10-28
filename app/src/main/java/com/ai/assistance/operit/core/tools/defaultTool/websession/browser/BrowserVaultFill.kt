package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.net.Uri
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitBrowserControl
import com.ai.assistance.operit.features.orbit.OrbitVault

/**
 * Types a vault value into the current page for Orbit's vault tool. Unlike browser_type the value is
 * never echoed: the page must be [site] over https, and the value is masked in everything returned.
 */
internal fun StandardBrowserSessionTools.typeVaultValue(
    toolName: String,
    ref: String,
    value: String,
    site: String,
    submit: Boolean,
): ToolResult = OrbitBrowserControl.aiCall {
    val session = getSession(null) ?: return@aiCall error(toolName, "No active browser tab. Open the sign-in page first.")
    val wrongSite = "The current page is not $site over https. Open that site's sign-in page, wait for it to load, and retry."
    if (session.isLoading || !session.pageLoaded) {
        return@aiCall error(toolName, "The page is still loading. Wait for it to finish, take a new snapshot and retry.")
    }
    // Both the navigation target and the live document must be the site; the fill script checks again.
    val live = readCurrentUrl(session.webView, "")
    if (listOf(session.currentUrl, live).any { url ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            uri?.scheme != "https" || !OrbitVault.matchesHost(uri.host.orEmpty(), site)
        }) {
        return@aiCall error(toolName, wrongSite)
    }
    runOnMainSync<Unit> { ensureSessionAttachedOnMain(session.id) }
    if (requireSnapshotNode(session, ref) == null) {
        return@aiCall error(toolName, "Ref $ref was not found in the current snapshot. Capture a new snapshot and retry.")
    }
    val markers = captureActionMarkers(session)
    try {
        typeIntoElementByRef(session.webView, ref, value, submit, false, requiredSite = site)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never pass the script error on: it is built from a script that holds the value.
        return@aiCall error(toolName, if (e.message.orEmpty().contains("wrong_site")) wrongSite
            else "Could not fill that field. Take a new snapshot and check the ref.")
    }
    val settlement = settleBrowserAction(
        initialSession = session,
        markers = markers,
        policy = if (submit) BrowserToolActionPolicy(
            waitForDocumentReady = true, waitForTimeSeconds = 0.5) else BrowserToolActionPolicy(),
    )
    val response = buildSettledBrowserResponse(
        settlement = settlement,
        code = "await ${locatorExpressionForRef(session, ref)}.fill(/* from the vault */);",
        result = "Filled from the vault",
    )
    ok(toolName, OrbitVault.redact(response, listOf(value)))
}
