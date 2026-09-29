package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.Memory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMemoryDialog(
    memory: Memory?,
    allFolderPaths: List<String>,
    onDismiss: () -> Unit,
    onSave: (
        memory: Memory?,
        title: String,
        content: String,
        contentType: String,
        source: String,
        credibility: Float,
        importance: Float,
        folderPath: String,
        tags: List<String>
    ) -> Unit
) {
    val defaultFolder = stringResource(R.string.memory_uncategorized)
    val scrollState = rememberScrollState()
    // Saved so a rotation cannot throw away what the user was typing.
    var title by rememberSaveable { mutableStateOf(memory?.title ?: "") }
    var content by rememberSaveable { mutableStateOf(memory?.content ?: "") }
    var contentType by rememberSaveable { mutableStateOf(memory?.contentType ?: "text/plain") }
    var source by rememberSaveable { mutableStateOf(memory?.source ?: "user_input") }
    var credibility by rememberSaveable { mutableStateOf(memory?.credibility ?: 0.8f) }
    var importance by rememberSaveable { mutableStateOf(memory?.importance ?: 0.5f) }
    var folderPath by rememberSaveable { mutableStateOf(memory?.folderPath ?: defaultFolder) }
    val tags = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() },
        restore = { it.toMutableStateList() }
    )) { mutableStateListOf<String>() }

    LaunchedEffect(memory) {
        // Only seeds an empty list, so restored edits are not overwritten on recomposition.
        if (tags.isEmpty()) memory?.tags?.let {
            tags.clear()
            tags.addAll(it.map { tag -> tag.name })
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .fillMaxHeight(0.9f)
                    .imePadding(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = if (memory == null) {
                            stringResource(R.string.memory_create_new)
                        } else {
                            stringResource(R.string.memory_edit_memory)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)
                    )
                    HorizontalDivider()

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(24.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.memory_title)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text(stringResource(R.string.memory_content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 200.dp),
                            enabled = memory?.isDocumentNode != true
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        FolderSelector(
                            allFolderPaths = allFolderPaths,
                            selectedPath = folderPath,
                            onPathSelected = { folderPath = it }
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        TagsEditor(tags = tags, onTagsChanged = { tags.clear(); tags.addAll(it) })
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            // The stored value is an internal key, so it is shown translated and the
                            // user cannot type a new one and break how the source is read back.
                            value = memorySourceText(source),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.memory_source)) },
                            supportingText = { Text(stringResource(R.string.memory_source_readonly)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("${stringResource(R.string.memory_credibility)}: ${String.format("%.2f", credibility)}")
                        Slider(
                            value = credibility,
                            onValueChange = { credibility = it },
                            valueRange = 0f..1f
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("${stringResource(R.string.memory_importance)}: ${String.format("%.2f", importance)}")
                        Slider(
                            value = importance,
                            onValueChange = { importance = it },
                            valueRange = 0f..1f
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.memory_cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSave(
                                    memory,
                                    title,
                                    content,
                                    contentType,
                                    source,
                                    credibility,
                                    importance,
                                    folderPath,
                                    tags.toList()
                                )
                                onDismiss()
                            }
                        ) {
                            Text(stringResource(R.string.memory_save))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(
    tags: List<String>,
    onTagsChanged: (List<String>) -> Unit
) {
    var newTagText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column {
        Text(stringResource(R.string.memory_tags), style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { /* Not used */ },
                    label = { Text(tag) },
                    trailingIcon = {
                        IconButton(
                            onClick = { onTagsChanged(tags - tag) },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.memory_tag_remove))
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = newTagText,
            onValueChange = { newTagText = it },
            placeholder = { Text(stringResource(R.string.memory_add_tag_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (newTagText.isNotBlank() && newTagText !in tags) {
                    onTagsChanged(tags + newTagText)
                    newTagText = ""
                }
                keyboardController?.hide()
            }),
            trailingIcon = {
                IconButton(onClick = {
                    if (newTagText.isNotBlank() && newTagText !in tags) {
                        onTagsChanged(tags + newTagText)
                        newTagText = ""
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_tag_add))
                }
            }
        )
    }
}
