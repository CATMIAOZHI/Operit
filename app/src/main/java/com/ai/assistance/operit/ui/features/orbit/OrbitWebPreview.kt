package com.ai.assistance.operit.ui.features.orbit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.R
import java.io.File

/**
 * A web page Orbit made, shown inside the app. Phone browsers often cannot open a file that lives
 * inside the app, so the page is drawn here; share and "open in another app" stay at the top.
 */
internal object OrbitWebPreview {
    private val PAGE_TYPES = setOf("html", "htm")

    fun canShow(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in PAGE_TYPES

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun Screen(file: File, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val colors = MaterialTheme.colorScheme
        var webView by remember(file) { mutableStateOf<WebView?>(null) }
        var canGoBack by remember(file) { mutableStateOf(false) }
        var loading by remember(file) { mutableStateOf(true) }
        var title by remember(file) { mutableStateOf(file.name) }
        OrbitModalWindow(onDismissRequest = onDismiss) {
            // Back first steps back inside the page, then closes the viewer.
            BackHandler(enabled = canGoBack) { webView?.goBack() }
            Column(Modifier.fillMaxSize().background(colors.background)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopButton(orbitIcon("arrow-left"), stringResource(R.string.common_close), onDismiss)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("rotate-cw"), stringResource(R.string.orbit_file_reload_page)) {
                        webView?.reload()
                    }
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) {
                        shareOrbitFile(context) { file }
                    }
                    Spacer(Modifier.width(8.dp))
                    TopButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                setBackgroundColor(android.graphics.Color.WHITE)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    // Pictures, styles and scripts next to the page load; the page
                                    // still cannot read other files through script.
                                    allowFileAccess = true
                                    allowContentAccess = false
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        // Only web and local pages open here; app links (tel:, intent:) are ignored.
                                        request.url.scheme?.lowercase() !in setOf("http", "https", "file", "about", "data")

                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        loading = true
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        loading = false
                                        view.title?.takeIf { it.isNotBlank() && !it.startsWith("file:") }?.let { title = it }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        canGoBack = view.canGoBack()
                                    }
                                }
                                loadUrl(Uri.fromFile(file).toString())
                                webView = this
                            }
                        },
                        onRelease = { view ->
                            view.stopLoading()
                            view.destroy()
                        },
                    )
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    @Composable
    private fun TopButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp)) }
        }
    }
}
