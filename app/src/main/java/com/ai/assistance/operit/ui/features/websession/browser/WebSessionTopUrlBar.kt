package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

/**
 * The address bar. At rest it shows the site's name; tapped, it becomes a focused field with the
 * address selected, so typing replaces it, and the page area shows suggestions until it is done.
 */
@Composable
internal fun WebSessionTopUrlBar(
    url: String,
    pageTitle: String,
    isLoading: Boolean,
    isEditing: Boolean,
    urlDraft: String,
    isBookmarked: Boolean,
    onStartEditing: () -> Unit,
    onUrlDraftChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onStopEditing: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    /** The tab the bar is for: a new tab starts typing again, with the keyboard. */
    focusKey: Any? = null,
) {
    val colors = MaterialTheme.colorScheme
    val blank = isBlankPage(url)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(isEditing, focusKey) {
        // The old address is selected: typing replaces it, a tap places the cursor.
        if (isEditing) field = TextFieldValue(urlDraft, TextRange(0, urlDraft.length))
    }
    // Asked again once the window can take focus: the browser may still be opening its window.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(isEditing, focusKey, windowFocused) {
        if (isEditing && windowFocused) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(urlDraft) {
        if (isEditing && field.text != urlDraft) field = TextFieldValue(urlDraft, TextRange(urlDraft.length))
    }

    Surface(modifier = modifier.fillMaxWidth(), color = colors.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val addressBar = stringResource(R.string.web_session_address_bar)
                Surface(
                    onClick = { if (!isEditing) onStartEditing() },
                    enabled = !isEditing,
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).height(44.dp).semantics { contentDescription = addressBar },
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight().padding(start = 14.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                isEditing || blank -> Icons.Filled.Search
                                url.startsWith("https://") -> Icons.Filled.Lock
                                else -> Icons.Filled.Language
                            },
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        if (isEditing) {
                            val inputLabel = stringResource(R.string.web_session_address_input)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (field.text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.web_session_search_or_url),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = field,
                                    onValueChange = {
                                        field = it
                                        if (it.text != urlDraft) onUrlDraftChange(it.text)
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { onSubmitUrl() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                        .semantics { contentDescription = inputLabel }
                                )
                            }
                            if (field.text.isNotEmpty()) {
                                IconButton(onClick = { onUrlDraftChange("") }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = stringResource(R.string.web_session_clear_input),
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = if (blank) stringResource(R.string.web_session_search_or_url) else siteHost(url),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (blank) colors.onSurfaceVariant else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!blank) {
                                IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                        contentDescription = stringResource(
                                            if (isBookmarked) R.string.web_session_remove_bookmark else R.string.web_session_add_bookmark
                                        ),
                                        tint = if (isBookmarked) colors.primary else colors.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (isEditing) {
                    TextButton(onClick = {
                        keyboard?.hide()
                        onStopEditing()
                    }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                } else {
                    IconButton(onClick = onRefreshOrStop) {
                        Icon(
                            imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                            contentDescription = stringResource(
                                if (isLoading) R.string.web_session_stop else R.string.web_session_refresh
                            )
                        )
                    }
                    IconButton(onClick = onMinimize) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.web_session_minimize))
                    }
                }
            }
            // A thin line under the bar while the page loads; the same height stays when it does not.
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.primary,
                    trackColor = colors.surface
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
