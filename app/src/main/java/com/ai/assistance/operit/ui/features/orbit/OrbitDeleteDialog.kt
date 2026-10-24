package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import kotlinx.coroutines.delay

/** Seconds the final delete button stays locked. */
private const val DELETE_WAIT_SECONDS = 5

/** Spaces and letter case do not matter when comparing the typed phrase. */
internal fun orbitDeletePhraseMatches(typed: String, phrase: String): Boolean {
    fun normalize(text: String) = text.filterNot { it.isWhitespace() }.lowercase()
    return normalize(phrase).isNotEmpty() && normalize(typed) == normalize(phrase)
}

/**
 * Deleting an Orbit cannot be undone, so it takes two steps: a warning, then a dialog where the
 * user types "I confirm deleting <name>" and the button unlocks after a short wait.
 */
@Composable
internal fun OrbitDeleteDialog(
    orbit: OrbitProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = orbit.name.ifBlank { stringResource(R.string.nav_orbit) }
    var typing by remember(orbit.id) { mutableStateOf(false) }
    if (!typing) {
        OrbitDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.orbit_delete_title, name),
            text = { Text(stringResource(R.string.orbit_delete_message)) },
            confirmButton = {
                OrbitDialogAction(onClick = { typing = true }, destructive = true) {
                    Text(stringResource(R.string.orbit_delete_continue))
                }
            },
            dismissButton = { OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    val phrase = stringResource(R.string.orbit_delete_phrase, name)
    var typed by remember(orbit.id) { mutableStateOf("") }
    var secondsLeft by remember(orbit.id) { mutableIntStateOf(DELETE_WAIT_SECONDS) }
    LaunchedEffect(orbit.id) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    val matches = orbitDeletePhraseMatches(typed, phrase)
    OrbitDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.orbit_delete_confirm_title),
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.orbit_delete_type_hint))
                Spacer(Modifier.height(8.dp))
                Text(phrase, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(200) },
                    singleLine = true,
                    placeholder = { Text(phrase) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            OrbitDialogAction(enabled = matches && secondsLeft == 0, onClick = onConfirm, destructive = true) {
                Text(
                    if (secondsLeft > 0) stringResource(R.string.orbit_delete_wait, secondsLeft)
                    else stringResource(R.string.orbit_delete),
                )
            }
        },
        dismissButton = { OrbitDialogAction(onClick = onDismiss, secondary = true) { Text(stringResource(R.string.cancel)) } },
    )
}
