package com.ai.assistance.operit.ui.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.ai.assistance.operit.ui.theme.OperitTheme
import com.ai.assistance.operit.util.OnDemandResources

/**
 * Dialog host for downloads requested while no Operit screen is on top (floating ball, wake service,
 * background chat). Shows the confirmation, then the progress of the approved download.
 */
class ResourceDownloadConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OperitTheme(updateSystemBars = false) {
                val pending by OnDemandResources.pendingRequests.collectAsState()
                val states by OnDemandResources.downloads.collectAsState()
                var approvedId by rememberSaveable { mutableStateOf<String?>(null) }
                val request =
                    pending.firstOrNull { !it.hostInApp }
                val tracked = approvedId?.let { states[it] }

                LaunchedEffect(request, approvedId, tracked) {
                    if (request != null) return@LaunchedEffect
                    // The progress card appears a moment after approval and disappears when its
                    // group finishes, so only leave once nothing is waiting or showing. A second
                    // queued request must keep this host alive to ask its own question.
                    // Without an approved group there is nothing to wait for, so only a short
                    // grace period covers a request that is still being published.
                    repeat(if (approvedId == null) 2 else 10) {
                        delay(300)
                        if (OnDemandResources.pendingRequests.value.any { !it.hostInApp }) {
                            return@LaunchedEffect
                        }
                        val id = approvedId
                        if (id != null &&
                            (OnDemandResources.downloads.value.containsKey(id) ||
                                OnDemandResources.isGroupActive(id))
                        ) {
                            return@LaunchedEffect
                        }
                    }
                    finish()
                }

                BackHandler {
                    // Back means "no" while the question is open; afterwards the download
                    // continues in the background. Answering keeps this host available for a
                    // request that is still queued behind this one.
                    val current = request
                    if (current == null) finish()
                    else OnDemandResources.decline(current.id)
                }

                when {
                    request != null ->
                        ResourceDownloadConfirmDialog(
                            request = request,
                            onApprove = {
                                approvedId = request.id
                                OnDemandResources.approve(request.id)
                            },
                            onDecline = {
                                OnDemandResources.decline(request.id)
                            }
                        )
                    tracked != null && tracked.error == null ->
                        ResourceDownloadProgressDialog(
                            state = tracked,
                            onCancel = { OnDemandResources.cancel(tracked.id) },
                            onKeepDownloading = { finish() }
                        )
                    tracked != null ->
                        ResourceDownloadFailedDialog(
                            state = tracked,
                            onRetry = { OnDemandResources.retry(this@ResourceDownloadConfirmActivity, tracked.id) },
                            onDismiss = {
                                OnDemandResources.dismiss(tracked.id)
                                finish()
                            }
                        )
                }
            }
        }
    }

}
