package com.ai.assistance.operit.ui.features.chat.components

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.AttachmentInfo
import java.io.File

/** [edit] (old path, new file, name to show) bound to the picture at [path], for [AttachmentChip]'s onEdited. */
fun editAttachmentAt(path: String, edit: suspend (String, File, String) -> Unit): suspend (File, String) -> Unit =
    { file, name -> edit(path, file, name) }

@Composable
fun AttachmentChip(
    attachmentInfo: AttachmentInfo,
    onRemove: () -> Unit,
    onInsert: () -> Unit,
    /**
     * Takes an edited copy of a picture (the file and the name to show), returning once it is in
     * the box; throws if it could not be. Null: tapping only shows the picture.
     */
    onEdited: (suspend (File, String) -> Unit)? = null,
    /** Whether tapping a picture opens it full screen. */
    openable: Boolean = false,
) {
    val context = LocalContext.current
    val isImage = attachmentInfo.mimeType.startsWith("image/")
    val icon: ImageVector = if (isImage) Icons.Default.Image else Icons.Default.Description
    val chipShape = RoundedCornerShape(13.dp)
    val imageModel =
        remember(attachmentInfo.filePath, isImage) {
            if (isImage) {
                Uri.fromFile(File(attachmentInfo.filePath))
            } else {
                null
            }
        }

    if (isImage) {
        var open by remember(attachmentInfo.filePath) { mutableStateOf(false) }
        val openLabel = androidx.compose.ui.res.stringResource(R.string.attachment_edit_open)
        Box(
            modifier =
                Modifier.size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (openable) Modifier.clickable(onClickLabel = openLabel, role = Role.Image) { open = true }
                        else Modifier
                    )
        ) {
            AsyncImage(
                model = imageModel,
                contentDescription = attachmentInfo.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            Surface(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.remove_attachment),
                        modifier = Modifier.size(10.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        if (open) {
            com.ai.assistance.operit.ui.features.chat.components.attachments.AttachmentImageEditor(
                file = File(attachmentInfo.filePath),
                fileName = attachmentInfo.fileName,
                mimeType = attachmentInfo.mimeType,
                onEdited = onEdited,
                onDismiss = { open = false },
            )
        }
    } else {
        // A file still waiting to be sent can be looked at too, in the app's own viewers.
        var previewing by remember(attachmentInfo.filePath) { mutableStateOf(false) }
        val previewFile = remember(attachmentInfo.filePath) {
            File(attachmentInfo.filePath).takeIf { it.isAbsolute && it.isFile }
        }
        val canOpen = openable && previewFile != null
        Surface(
            modifier =
                Modifier.height(26.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = chipShape,
                    )
                    .clip(chipShape)
                    .then(
                        if (canOpen) Modifier.clickable(onClickLabel = androidx.compose.ui.res.stringResource(R.string.attachment_edit_open)) {
                            val file = previewFile ?: return@clickable
                            val name = attachmentInfo.fileName.ifBlank { file.name }
                            val preview = com.ai.assistance.operit.ui.features.orbit.OrbitMediaPreview
                            // The viewer goes by the stored copy's own name; both must agree.
                            if (preview.canPreview(name) && preview.canPreview(file.name)) previewing = true
                            else com.ai.assistance.operit.ui.features.orbit.openOrbitFile(context) { file }
                        } else Modifier
                    ),
            shape = chipShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = attachmentInfo.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 80.dp),
                )

                Spacer(modifier = Modifier.width(2.dp))

                IconButton(onClick = onRemove, modifier = Modifier.size(14.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.remove_attachment),
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (previewing && previewFile != null) {
            com.ai.assistance.operit.ui.features.orbit.OrbitMediaPreview.Screen(previewFile) { previewing = false }
        }
    }
}
