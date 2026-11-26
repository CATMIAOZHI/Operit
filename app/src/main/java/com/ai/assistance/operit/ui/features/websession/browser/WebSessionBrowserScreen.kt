package com.ai.assistance.operit.ui.features.websession.browser

import com.ai.assistance.operit.ui.features.orbit.orbitDismissLayer

import android.net.Uri
import android.graphics.Color as AndroidColor
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBookmark
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserHostState
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserSheetRoute
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionHistoryEntry
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionPendingDialogState
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionWebViewHost
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.ui.WebSessionUserscriptUiState
import java.util.Locale

@Composable
internal fun WebSessionBrowserScreen(
    hostState: WebSessionBrowserHostState,
    watch: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserWatchUi? = null,
    bookmarks: List<WebSessionBookmark>,
    globalHistory: List<WebSessionHistoryEntry>,
    userscriptUiState: WebSessionUserscriptUiState,
    webViewHost: WebSessionWebViewHost,
    onHostStateChange: ((WebSessionBrowserHostState) -> WebSessionBrowserHostState) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onMinimize: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onToggleBookmark: (String, String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onSelectSessionHistory: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistoryEntry: (String, Long) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onOpenUserscripts: () -> Unit,
    onImportUserscript: () -> Unit,
    onInstallUserscriptFromUrl: (String) -> Unit,
    onConfirmUserscriptInstall: () -> Unit,
    onCancelUserscriptInstall: () -> Unit,
    onSetUserscriptEnabled: (Long, Boolean) -> Unit,
    onDeleteUserscript: (Long) -> Unit,
    onCheckUserscriptUpdate: (Long) -> Unit,
    onInvokeUserscriptMenu: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteDownload: (String, Boolean) -> Unit,
    onOpenDownloadedFile: (String) -> Unit,
    onOpenDownloadLocation: (String) -> Unit,
    onConfirmExternalOpen: (String) -> Unit,
    onCancelExternalOpen: (String) -> Unit,
    onHandlePendingDialog: (Boolean, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val browserState = hostState.browserState
    var totalHeightPx by remember { mutableIntStateOf(0) }
    var browserAreaHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(totalHeightPx, browserAreaHeightPx) {
        val chromeHeightPx = (totalHeightPx - browserAreaHeightPx).coerceAtLeast(0)
        if (
            chromeHeightPx != hostState.chromeHeightPx ||
                browserAreaHeightPx != hostState.browserAreaHeightPx
        ) {
            onHostStateChange { current ->
                if (
                    current.chromeHeightPx == chromeHeightPx &&
                        current.browserAreaHeightPx == browserAreaHeightPx
                ) {
                    current
                } else {
                    current.copy(
                        chromeHeightPx = chromeHeightPx,
                        browserAreaHeightPx = browserAreaHeightPx
                    )
                }
            }
        }
    }
    val isBookmarked =
        remember(browserState.currentUrl, bookmarks) {
            val normalizedUrl = normalizeLookupUrl(browserState.currentUrl)
            normalizedUrl != null && bookmarks.any { it.url == normalizedUrl }
        }
    val dismissSheet = {
        onHostStateChange { current ->
            current.copy(sheetRoute = WebSessionBrowserSheetRoute.NONE)
        }
    }
    val activeSheetRoute = hostState.sheetRoute
    var promptDraft by remember(browserState.pendingDialog?.message, browserState.pendingDialog?.defaultValue) {
        mutableStateOf(browserState.pendingDialog?.defaultValue.orEmpty())
    }

    if (watch != null) {
        // An Orbit works in this tab: the watch screen replaces the browser's own bars.
        Box(modifier.fillMaxSize()) {
            WebSessionWatchScreen(
                watch = watch,
                webViewHost = webViewHost,
                hasSession = browserState.activeSessionId != null,
                url = browserState.currentUrl,
                isLoading = browserState.isLoading,
                canGoBack = browserState.canGoBack,
                canGoForward = browserState.canGoForward,
                onNavigate = onNavigate,
                onBack = onBack,
                onForward = onForward,
                onRefreshOrStop = onRefreshOrStop,
                onTotalHeight = { totalHeightPx = it },
                onBrowserArea = { width, height ->
                    browserAreaHeightPx = height
                    if (width != hostState.browserAreaWidthPx) {
                        onHostStateChange { current ->
                            if (current.browserAreaWidthPx == width) current else current.copy(browserAreaWidthPx = width)
                        }
                    }
                },
            )
            if (watch.controlling) {
                // The watch header reserves this space beside its centered title.
                Surface(
                    onClick = { onHostStateChange { it.copy(sheetRoute = WebSessionBrowserSheetRoute.DOWNLOADS) } },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp).size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Download, stringResource(R.string.web_session_downloads), Modifier.size(24.dp))
                    }
                }
            }
            if (watch.controlling && activeSheetRoute == WebSessionBrowserSheetRoute.DOWNLOADS) {
                val back = com.ai.assistance.operit.ui.features.orbit.rememberOrbitBackMotion()
                com.ai.assistance.operit.ui.features.orbit.OrbitPopupBack(back, dismissSheet)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = dismissSheet))
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.75f)
                        .navigationBarsPadding().browserKeyboardPadding().orbitDismissLayer(back),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    WebSessionDownloadSheet(
                        uiState = hostState.downloadUiState,
                        onFilterChange = { filter ->
                            onHostStateChange { it.copy(downloadUiState = it.downloadUiState.copy(selectedFilter = filter)) }
                        },
                        onPauseDownload = onPauseDownload,
                        onResumeDownload = onResumeDownload,
                        onCancelDownload = onCancelDownload,
                        onRetryDownload = onRetryDownload,
                        onDeleteDownload = onDeleteDownload,
                        onOpenDownloadedFile = onOpenDownloadedFile,
                        onOpenDownloadLocation = onOpenDownloadLocation,
                    )
                }
            }
            // A page dialog needs an answer from whoever holds the page; the user only once in control.
            if (watch.controlling) {
                hostState.externalOpenPrompt?.let { prompt ->
                    val back = com.ai.assistance.operit.ui.features.orbit.rememberOrbitBackMotion()
                    val cancel = { onCancelExternalOpen(prompt.requestId) }
                    com.ai.assistance.operit.ui.features.orbit.OrbitPopupBack(back, cancel)
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                            .clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = cancel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.orbitDismissLayer(back)) {
                            ExternalOpenPromptBar(prompt.title, prompt.target,
                                onConfirm = { onConfirmExternalOpen(prompt.requestId) }, onCancel = cancel)
                        }
                    }
                }
                browserState.pendingDialog?.let { pendingDialog ->
                    PendingDialogOverlay(
                        orbitStyle = true,
                        dialog = pendingDialog,
                        promptValue = promptDraft,
                        onPromptValueChange = { promptDraft = it },
                        onConfirm = { onHandlePendingDialog(true, promptDraft) },
                        onDismiss = { onHandlePendingDialog(false, null) },
                    )
                }
            }
        }
        return
    }

    val openTyped: (String) -> Unit = { target ->
        onNavigate(target)
        onHostStateChange { current -> current.copy(isEditingUrl = false, urlDraft = target) }
    }
    val submitDraft = {
        // Nothing typed: nothing to open.
        if (hostState.urlDraft.isNotBlank()) openTyped(normalizeNavigationUrl(hostState.urlDraft))
    }
    // A new tab opens ready to type, as in other browsers.
    val newTabReady = {
        onNewTab()
        onHostStateChange { current ->
            current.copy(isEditingUrl = true, urlDraft = "", sheetRoute = WebSessionBrowserSheetRoute.NONE)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserKeyboardLayout(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { totalHeightPx = it.height },
                body = { Column(Modifier.fillMaxSize()) {
            WebSessionTopUrlBar(
                url = browserState.currentUrl.ifBlank { "about:blank" },
                pageTitle = browserState.pageTitle,
                isLoading = browserState.isLoading,
                isEditing = hostState.isEditingUrl,
                urlDraft = hostState.urlDraft,
                isBookmarked = isBookmarked,
                onStartEditing = {
                    onHostStateChange { current ->
                        current.copy(
                            isEditingUrl = true,
                            urlDraft = browserState.currentUrl.takeUnless(::isBlankPage).orEmpty()
                        )
                    }
                },
                onUrlDraftChange = { draft ->
                    onHostStateChange { current ->
                        current.copy(urlDraft = draft)
                    }
                },
                onSubmitUrl = submitDraft,
                onStopEditing = {
                    onHostStateChange { current ->
                        current.copy(
                            isEditingUrl = false,
                            urlDraft = browserState.currentUrl
                        )
                    }
                },
                onToggleBookmark = {
                    onToggleBookmark(browserState.currentUrl, browserState.pageTitle)
                },
                onRefreshOrStop = onRefreshOrStop,
                onMinimize = onMinimize,
                modifier = Modifier.statusBarsPadding(),
                focusKey = browserState.activeSessionId,
            )

            hostState.externalOpenPrompt?.let { prompt ->
                ExternalOpenPromptBar(
                    title = prompt.title,
                    target = prompt.target,
                    onConfirm = { onConfirmExternalOpen(prompt.requestId) },
                    onCancel = { onCancelExternalOpen(prompt.requestId) }
                )
            }

            if (browserState.activeDownloadCount > 0) {
                BrowserDownloadSummaryBar(
                    activeCount = browserState.activeDownloadCount,
                    overallProgress = browserState.overallDownloadProgress,
                    onClick = {
                        onHostStateChange { current ->
                            current.copy(sheetRoute = WebSessionBrowserSheetRoute.DOWNLOADS)
                        }
                    }
                )
            }

            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            )

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged {
                            browserAreaHeightPx = it.height
                            if (it.width != hostState.browserAreaWidthPx) {
                                onHostStateChange { current ->
                                    if (current.browserAreaWidthPx == it.width) {
                                        current
                                    } else {
                                        current.copy(browserAreaWidthPx = it.width)
                                    }
                                }
                            }
                        }
                        .background(MaterialTheme.colorScheme.background)
            ) {
                if (browserState.activeSessionId == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.web_session_no_tabs),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.web_session_new_tab),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { context ->
                                FrameLayout(context).apply {
                                    setBackgroundColor(AndroidColor.WHITE)
                                    webViewHost.attachContainer(this)
                                }
                            },
                            update = { container ->
                                webViewHost.attachContainer(container)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                // Typing an address, or a tab with nothing in it yet: suggestions and sites over the page.
                if (hostState.isEditingUrl || isBlankPage(browserState.currentUrl)) {
                    WebSessionStartPanel(
                        query = if (hostState.isEditingUrl) hostState.urlDraft else "",
                        history = globalHistory,
                        bookmarks = bookmarks,
                        onSubmit = submitDraft,
                        onOpenUrl = openTyped,
                    )
                }
            }
            } },
                bottomBar = { Column(Modifier.fillMaxWidth()) {
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            )

            WebSessionBottomToolbar(
                canGoBack = browserState.canGoBack,
                canGoForward = browserState.canGoForward,
                tabCount = browserState.tabs.size,
                onBack = onBack,
                onForward = onForward,
                onNewTab = newTabReady,
                onTabs = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.TABS)
                    }
                },
                onMenu = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.MENU)
                    }
                },
                modifier = Modifier.navigationBarsPadding()
            )
            } },
            )
        }

        if (activeSheetRoute != WebSessionBrowserSheetRoute.NONE) {
            val scrimInteractionSource = remember { MutableInteractionSource() }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = scrimInteractionSource,
                            indication = null,
                            onClick = dismissSheet
                        )
            )

            // WebSession lives in an overlay window, so using ModalBottomSheet would
            // create a dialog window that does not have a valid activity token here.
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp
            ) {
                WebSessionOverlaySheetContent(
                    sheetRoute = activeSheetRoute,
                    browserState = browserState,
                    bookmarks = bookmarks,
                    globalHistory = globalHistory,
                    userscriptUiState = userscriptUiState,
                    onDismiss = dismissSheet,
                    onSelectTab = onSelectTab,
                    onCloseTab = onCloseTab,
                    onNewTab = newTabReady,
                    onCloseCurrentTab = onCloseCurrentTab,
                    onCloseAllTabs = onCloseAllTabs,
                    onRemoveBookmark = onRemoveBookmark,
                    // Going somewhere from a list ends any typing in the address bar.
                    onSelectSessionHistory = { index ->
                        onHostStateChange { it.copy(isEditingUrl = false) }
                        onSelectSessionHistory(index)
                    },
                    onOpenUrl = { url ->
                        onHostStateChange { it.copy(isEditingUrl = false, urlDraft = url) }
                        onOpenUrl(url)
                    },
                    onClearHistory = onClearHistory,
                    onRemoveHistoryEntry = onRemoveHistoryEntry,
                    onToggleDesktopMode = onToggleDesktopMode,
                    onHostStateChange = onHostStateChange,
                    hostState = hostState,
                    onOpenUserscripts = onOpenUserscripts,
                    onImportUserscript = onImportUserscript,
                    onInstallUserscriptFromUrl = onInstallUserscriptFromUrl,
                    onConfirmUserscriptInstall = onConfirmUserscriptInstall,
                    onCancelUserscriptInstall = onCancelUserscriptInstall,
                    onSetUserscriptEnabled = onSetUserscriptEnabled,
                    onDeleteUserscript = onDeleteUserscript,
                    onCheckUserscriptUpdate = onCheckUserscriptUpdate,
                    onInvokeUserscriptMenu = onInvokeUserscriptMenu,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onCancelDownload = onCancelDownload,
                    onRetryDownload = onRetryDownload,
                    onDeleteDownload = onDeleteDownload,
                    onOpenDownloadedFile = onOpenDownloadedFile,
                    onOpenDownloadLocation = onOpenDownloadLocation
                )
            }
        }

        browserState.pendingDialog?.let { pendingDialog ->
            PendingDialogOverlay(
                dialog = pendingDialog,
                promptValue = promptDraft,
                onPromptValueChange = { promptDraft = it },
                onConfirm = {
                    onHandlePendingDialog(true, promptDraft)
                },
                onDismiss = {
                    onHandlePendingDialog(false, null)
                }
            )
        }
    }
}

@Composable
private fun PendingDialogOverlay(
    dialog: WebSessionPendingDialogState,
    promptValue: String,
    onPromptValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    orbitStyle: Boolean = false,
) {
    val backMotion = if (orbitStyle) com.ai.assistance.operit.ui.features.orbit.rememberOrbitBackMotion() else null
    if (backMotion != null) com.ai.assistance.operit.ui.features.orbit.OrbitPopupBack(backMotion, onDismiss)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f))
                .browserKeyboardPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp)
                .then(if (backMotion != null) Modifier.orbitDismissLayer(backMotion) else Modifier),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text =
                        when (dialog.type.lowercase(Locale.ROOT)) {
                            "confirm" -> stringResource(R.string.web_session_dialog_title_confirm)
                            "prompt" -> stringResource(R.string.web_session_dialog_title_prompt)
                            else -> stringResource(R.string.web_session_dialog_title_alert)
                        },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = dialog.message.ifBlank { stringResource(R.string.web_session_dialog_empty_message) },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (dialog.type.equals("prompt", ignoreCase = true)) {
                    OutlinedTextField(
                        value = promptValue,
                        onValueChange = onPromptValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!dialog.type.equals("alert", ignoreCase = true)) {
                        TextButton(onClick = onDismiss) {
                            Text(text = stringResource(android.R.string.cancel))
                        }
                    }
                    TextButton(onClick = onConfirm) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun WebSessionOverlaySheetContent(
    sheetRoute: WebSessionBrowserSheetRoute,
    browserState: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBrowserState,
    bookmarks: List<WebSessionBookmark>,
    globalHistory: List<WebSessionHistoryEntry>,
    userscriptUiState: WebSessionUserscriptUiState,
    onDismiss: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onSelectSessionHistory: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistoryEntry: (String, Long) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onHostStateChange: ((WebSessionBrowserHostState) -> WebSessionBrowserHostState) -> Unit,
    hostState: WebSessionBrowserHostState,
    onOpenUserscripts: () -> Unit,
    onImportUserscript: () -> Unit,
    onInstallUserscriptFromUrl: (String) -> Unit,
    onConfirmUserscriptInstall: () -> Unit,
    onCancelUserscriptInstall: () -> Unit,
    onSetUserscriptEnabled: (Long, Boolean) -> Unit,
    onDeleteUserscript: (Long) -> Unit,
    onCheckUserscriptUpdate: (Long) -> Unit,
    onInvokeUserscriptMenu: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteDownload: (String, Boolean) -> Unit,
    onOpenDownloadedFile: (String) -> Unit,
    onOpenDownloadLocation: (String) -> Unit
) {
    when (sheetRoute) {
        WebSessionBrowserSheetRoute.TABS ->
            WebSessionTabSheet(
                tabs = browserState.tabs,
                onSelectTab = { sessionId ->
                    onSelectTab(sessionId)
                    onDismiss()
                },
                onCloseTab = onCloseTab,
                onNewTab = {
                    onNewTab()
                    onDismiss()
                }
            )

        WebSessionBrowserSheetRoute.MENU ->
            WebSessionMenuSheet(
                isDesktopMode = browserState.isDesktopMode,
                activeDownloadCount = browserState.activeDownloadCount,
                onOpenHistory = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.HISTORY)
                    }
                },
                onOpenDownloads = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.DOWNLOADS)
                    }
                },
                onOpenBookmarks = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.BOOKMARKS)
                    }
                },
                onOpenUserscripts = {
                    onHostStateChange { current ->
                        current.copy(sheetRoute = WebSessionBrowserSheetRoute.USERSCRIPTS)
                    }
                    onOpenUserscripts()
                },
                userscriptMenuCommands = browserState.userscriptMenuCommands,
                onInvokeUserscriptMenu = { commandId ->
                    onDismiss()
                    onInvokeUserscriptMenu(commandId)
                },
                onToggleDesktopMode = {
                    onDismiss()
                    onToggleDesktopMode()
                },
                onCloseCurrentTab = {
                    onDismiss()
                    onCloseCurrentTab()
                },
                onCloseAllTabs = {
                    onDismiss()
                    onCloseAllTabs()
                }
            )

        WebSessionBrowserSheetRoute.DOWNLOADS ->
            WebSessionDownloadSheet(
                uiState = hostState.downloadUiState,
                onFilterChange = { filter ->
                    onHostStateChange { current ->
                        current.copy(
                            downloadUiState =
                                current.downloadUiState.copy(selectedFilter = filter)
                        )
                    }
                },
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onRetryDownload = onRetryDownload,
                onDeleteDownload = onDeleteDownload,
                onOpenDownloadedFile = onOpenDownloadedFile,
                onOpenDownloadLocation = onOpenDownloadLocation
            )

        WebSessionBrowserSheetRoute.HISTORY ->
            WebSessionHistorySheet(
                sessionHistory = browserState.sessionHistory,
                globalHistory = globalHistory,
                onSelectSessionHistory = { index ->
                    onSelectSessionHistory(index)
                    onDismiss()
                },
                onOpenHistoryUrl = { url ->
                    onOpenUrl(url)
                    onDismiss()
                },
                onClearHistory = onClearHistory,
                onRemoveEntry = { onRemoveHistoryEntry(it.url, it.visitedAt) }
            )

        WebSessionBrowserSheetRoute.BOOKMARKS ->
            WebSessionBookmarkSheet(
                bookmarks = bookmarks,
                onOpenBookmark = { url ->
                    onOpenUrl(url)
                    onDismiss()
                },
                onRemoveBookmark = onRemoveBookmark
            )

        WebSessionBrowserSheetRoute.USERSCRIPTS ->
            WebSessionUserscriptSheet(
                state = userscriptUiState,
                currentPageMenuCommands = browserState.userscriptMenuCommands,
                onInstallFromUrl = onInstallUserscriptFromUrl,
                onImportLocal = onImportUserscript,
                onConfirmInstall = onConfirmUserscriptInstall,
                onCancelInstall = onCancelUserscriptInstall,
                onSetScriptEnabled = onSetUserscriptEnabled,
                onDeleteScript = onDeleteUserscript,
                onCheckUpdate = onCheckUserscriptUpdate,
                onInvokeMenuCommand = onInvokeUserscriptMenu
            )

        WebSessionBrowserSheetRoute.NONE -> Unit
    }
}

@Composable
private fun ExternalOpenPromptBar(
    title: String,
    target: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.web_session_external_open_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(R.string.web_session_external_open_allow_once))
                }
            }
        }
    }
}

@Composable
private fun BrowserDownloadSummaryBar(
    activeCount: Int,
    overallProgress: Float?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.web_session_downloads_active_bar, activeCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text =
                    overallProgress?.let { progress ->
                        "${(progress * 100f).toInt()}%"
                    } ?: stringResource(R.string.web_session_downloads_active_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** Where words typed in the address bar are searched. */
private const val SEARCH_URL = "https://www.bing.com/search?q="

private val URI_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

/** Schemes written without "//" that still name something to open, never words to search for. */
private val OPAQUE_SCHEMES = setOf("about", "blob", "data", "javascript", "mailto", "tel", "sms", "geo", "market", "intent")

private enum class TypedTarget { SEARCH, AS_IS, WEB_HOST }

/** A bare host such as "example.com", "localhost", "192.168.1.1" or "[::1]", optionally with a port and path. */
private fun isBareHost(text: String): Boolean {
    if (text.any { it.isWhitespace() }) return false
    val hostPort = text.substringBefore('/').substringBefore('?').substringBefore('#')
    if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']')
        if (end < 2) return false
        val inside = hostPort.substring(1, end)
        val after = hostPort.substring(end + 1)
        return ':' in inside && inside.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' } &&
            (after.isEmpty() || (after.length > 1 && after[0] == ':' && after.drop(1).all(Char::isDigit)))
    }
    // "example.com." names the same site; its last dot is not an empty label.
    val host = hostPort.substringBefore(':').removeSuffix(".")
    val port = hostPort.substringAfter(':', "")
    if (port.isNotEmpty() && !port.all(Char::isDigit)) return false
    val labels = host.split('.')
    return host.equals("localhost", ignoreCase = true) ||
        (labels.size > 1 && labels.all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } })
}

private fun typedTarget(trimmed: String): TypedTarget {
    if (trimmed.isEmpty()) return TypedTarget.SEARCH
    URI_SCHEME.matchEntire(trimmed)?.let { match ->
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        val rest = match.groupValues[2]
        return when {
            // A web address needs a host; anything else after "http(s):" is searched for.
            scheme == "http" || scheme == "https" ->
                Uri.parse(trimmed).host.let { host ->
                    if (host.isNullOrBlank() || host.any { it.isWhitespace() }) TypedTarget.SEARCH else TypedTarget.AS_IS
                }
            rest.startsWith("//") -> if (rest.length > 2) TypedTarget.AS_IS else TypedTarget.SEARCH
            scheme in OPAQUE_SCHEMES -> if (rest.isNotEmpty()) TypedTarget.AS_IS else TypedTarget.SEARCH
            // "example.com:8080/path" reads as a scheme too.
            isBareHost(trimmed) -> TypedTarget.WEB_HOST
            else -> TypedTarget.SEARCH
        }
    }
    return if (isBareHost(trimmed)) TypedTarget.WEB_HOST else TypedTarget.SEARCH
}

/** Whether text typed in the address bar is something to open; otherwise it is searched for. */
internal fun navigationTargetIsAddress(raw: String): Boolean = typedTarget(raw.trim()) != TypedTarget.SEARCH

internal fun normalizeNavigationUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) {
        return "about:blank"
    }
    return when (typedTarget(trimmed)) {
        TypedTarget.AS_IS -> trimmed
        TypedTarget.WEB_HOST -> "https://$trimmed"
        TypedTarget.SEARCH -> SEARCH_URL + Uri.encode(trimmed)
    }
}

private fun normalizeLookupUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) {
        return null
    }

    val lower = trimmed.lowercase(Locale.ROOT)
    if (lower.startsWith("about:") || lower.startsWith("blob:") || lower.startsWith("data:")) {
        return null
    }
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
        return null
    }

    return runCatching {
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val portPart =
            when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
        val path = uri.encodedPath?.ifBlank { "/" } ?: "/"
        buildString {
            append(scheme)
            append("://")
            append(host)
            append(portPart)
            append(path)
            uri.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                append('?')
                append(it)
            }
        }
    }.getOrElse { trimmed }
}
