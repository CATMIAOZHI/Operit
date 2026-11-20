package com.ai.assistance.operit.ui.features.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ModelLimits
import com.ai.assistance.operit.ui.common.input.bringIntoViewOnImeFocus
import com.ai.assistance.operit.ui.features.chat.util.formatContextK
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/**
 * "195.3" for a token count, in the K (1024 tokens) the context settings use; a count under 0.1K
 * shows to the thousandth, so it never reads as 0.
 */
private fun tokensToK(tokens: Int): String {
    val tenths = (tokens / 1024.0 * 10).roundToLong()
    if (tenths > 0) return formatContextK(tenths / 10f)
    return BigDecimal(tokens).divide(BigDecimal(1024), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** Tokens for a typed K amount; null for blank, and [INVALID] when it is not a usable amount. */
private fun parseK(text: String): Int? {
    if (text.isBlank()) return null
    val trimmed = text.trim().removeSuffix("K").removeSuffix("k").trim()
    if (trimmed.isEmpty()) return INVALID
    val value = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return INVALID
    val tokens = (value * 1024).roundToLong()
    return if (tokens in 1..Int.MAX_VALUE.toLong()) tokens.toInt() else INVALID
}

private const val INVALID = -1

/**
 * One model's context window and longest reply, filled in from the catalog and editable.
 * [onChange] gets the change to make to the model's current limits: only the numbers edited.
 */
@Composable
internal fun ModelLimitsEditor(
    modelName: String,
    limits: ModelLimits?,
    onChange: ((ModelLimits?) -> ModelLimits) -> Unit,
) {
    var editing by remember(modelName) { mutableStateOf(false) }
    val unlimited = stringResource(R.string.model_limits_unlimited)
    val cleaned = limits?.cleaned()
    SettingsSelectorRow(
        title = stringResource(R.string.model_limits_title),
        subtitle = stringResource(R.string.model_limits_desc),
        value = stringResource(
            R.string.model_limits_value,
            cleaned?.contextTokens?.let { tokensToK(it) + "K" } ?: unlimited,
            cleaned?.outputTokens?.let { tokensToK(it) + "K" } ?: unlimited,
        ),
        onClick = { editing = true },
    )
    if (editing) {
        // The limits as the dialog opened. The shown amounts are rounded, so a field left as shown
        // keeps its exact token count.
        val opened = remember(modelName) { cleaned ?: ModelLimits() }
        val shownContext = remember(modelName) { opened.contextTokens?.let(::tokensToK).orEmpty() }
        val shownOutput = remember(modelName) { opened.outputTokens?.let(::tokensToK).orEmpty() }
        var context by remember(modelName) { mutableStateOf(shownContext) }
        var output by remember(modelName) { mutableStateOf(shownOutput) }
        val contextTokens = if (context == shownContext) opened.contextTokens else parseK(context)
        val outputTokens = if (output == shownOutput) opened.outputTokens else parseK(output)
        val valid = contextTokens != INVALID && outputTokens != INVALID
        val invalidText = stringResource(R.string.model_limits_invalid)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.model_limits_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.model_limits_dialog_hint))
                    OutlinedTextField(
                        value = context,
                        onValueChange = { context = it },
                        label = { Text(stringResource(R.string.model_limits_context)) },
                        suffix = { Text("K") },
                        placeholder = { Text(unlimited) },
                        isError = contextTokens == INVALID,
                        supportingText = if (contextTokens == INVALID) ({ Text(invalidText) }) else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().bringIntoViewOnImeFocus(),
                    )
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        label = { Text(stringResource(R.string.model_limits_output)) },
                        suffix = { Text("K") },
                        placeholder = { Text(unlimited) },
                        isError = outputTokens == INVALID,
                        supportingText = if (outputTokens == INVALID) ({ Text(invalidText) }) else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().bringIntoViewOnImeFocus(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        // Only the numbers actually changed are written, so opening and confirming
                        // changes nothing, not even limits the catalog filled in meanwhile.
                        val contextChanged = contextTokens != opened.contextTokens
                        val outputChanged = outputTokens != opened.outputTokens
                        if (contextChanged || outputChanged) onChange { current ->
                            ModelLimits(
                                contextTokens = if (contextChanged) contextTokens else current?.contextTokens,
                                outputTokens = if (outputChanged) outputTokens else current?.outputTokens,
                            )
                        }
                        editing = false
                    },
                ) { Text(stringResource(R.string.confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
