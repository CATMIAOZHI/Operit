package com.ai.assistance.operit.ui.features.startup.components

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's language, chosen on the first screens before anything else is set up: follow the
 * phone, Chinese or English. Each choice is written in its own language so it can be read before
 * the switch. Picking another one redraws the screen in it; what the user picked on the screen stays.
 */
@Composable
fun FirstLaunchLanguagePicker(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** True while a new language is being saved; the page holds its next step until it is done. */
    onSwitchingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { UserPreferencesManager.getInstance(context) }
    // Nothing is marked until the saved choice is read, so a slow read never shows the wrong one.
    var current by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(switching) { onSwitchingChange(switching) }
    LaunchedEffect(Unit) {
        val saved = try {
            withContext(Dispatchers.IO) { preferences.appLanguage.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (current == null) current = choiceOf(saved)
    }
    val choices = listOf(
        LocaleUtils.LanguageCodes.AUTO to "跟随系统\nSystem",
        LocaleUtils.LanguageCodes.CHINESE to "简体中文",
        LocaleUtils.LanguageCodes.ENGLISH to "English",
    )
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choices.forEach { (code, label) ->
            val selected = current == code
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled && !switching,
                        role = Role.RadioButton,
                    ) {
                        if (code == current || switching) return@selectable
                        current = code
                        switching = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { preferences.saveAppLanguage(code) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Still shown in the new language; the next start reads what was saved.
                            }
                            LocaleUtils.applyAppLanguage(context, code)
                            AgentProfileRepository.instance.initialize(context.applicationContext)
                            // MainActivity applies the saved language when it is created, and no
                            // per-app system language is set, so the screen is redrawn here on every
                            // Android version. Saved state (such as the launch mode picked below) survives.
                            (context as? Activity)?.recreate()
                            switching = false
                        }
                    },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** What the user chose before ("system" until they pick one). */
private fun choiceOf(saved: String?): String = saved?.takeIf { it.isNotBlank() }?.let {
    when {
        it == LocaleUtils.LanguageCodes.AUTO -> it
        it.lowercase().startsWith("zh") -> LocaleUtils.LanguageCodes.CHINESE
        it.lowercase().startsWith("en") -> LocaleUtils.LanguageCodes.ENGLISH
        else -> it
    }
} ?: LocaleUtils.LanguageCodes.AUTO
