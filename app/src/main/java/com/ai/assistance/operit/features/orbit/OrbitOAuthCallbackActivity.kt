package com.ai.assistance.operit.features.orbit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Separate from ordinary links: authorization codes never pass through the chat or its browser tools. */
class OrbitOAuthCallbackActivity : ComponentActivity() {
    private val completion by viewModels<OrbitOAuthCompletion>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent.data
        if (callback == null && !completion.started) { finish(); return }
        completion.start(callback)
        // Do not retain the code in an activity intent while processing the result.
        intent = Intent()
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.orbit_oauth_completing))
                    }
                }
            }
        }
        lifecycleScope.launch {
            val result = completion.result.filterNotNull().first()
            if (result != OrbitOAuthCompletion.Outcome.CANCELLED) Toast.makeText(this@OrbitOAuthCallbackActivity,
                if (result == OrbitOAuthCompletion.Outcome.CONNECTED) R.string.orbit_oauth_connected
                else R.string.orbit_oauth_failed, Toast.LENGTH_LONG).show()
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(it)
            }
            finish()
        }
    }
}

/** Configuration changes keep the same exchange and result; the callback code is never put into saved instance state. */
internal class OrbitOAuthCompletion(application: Application) : AndroidViewModel(application) {
    enum class Outcome { CONNECTED, CANCELLED, FAILED }
    var started = false
        private set
    private val completed = MutableStateFlow<Outcome?>(null)
    val result = completed.asStateFlow()

    fun start(callback: Uri?) {
        if (started || callback == null) return
        started = true
        viewModelScope.launch {
            completed.value = try {
                when (OrbitOAuth.complete(getApplication(), callback)) {
                    true -> Outcome.CONNECTED
                    null -> Outcome.CANCELLED
                    false -> Outcome.FAILED
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { Outcome.FAILED }
        }
    }
}
