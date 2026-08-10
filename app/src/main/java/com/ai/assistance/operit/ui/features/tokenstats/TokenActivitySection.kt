package com.ai.assistance.operit.ui.features.tokenstats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.util.AtomicFile
import android.widget.Toast
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.stats.TokenActivityDay
import com.ai.assistance.operit.data.stats.TokenActivityViewMode
import com.ai.assistance.operit.data.stats.TokenActivityWeek
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class AvatarImportDecision(
    val applyAvatar: Boolean,
    val avatarPath: String?,
    val avatarRevision: Long,
)

internal fun decideAvatarImport(
    globalAvatarUri: String?,
    persistedPath: String?,
    currentPath: String?,
    currentRevision: Long,
    nowMs: Long,
): AvatarImportDecision {
    if (globalAvatarUri.isNullOrBlank()) {
        return AvatarImportDecision(true, null, maxOf(nowMs, currentRevision + 1L))
    }
    if (persistedPath == null) {
        return AvatarImportDecision(false, currentPath, currentRevision)
    }
    return AvatarImportDecision(true, persistedPath, maxOf(nowMs, currentRevision + 1L))
}

@Composable
internal fun TokenActivitySection(
    state: TokenActivityUiState,
    zone: ZoneId,
    onSelectRecent: () -> Unit,
    onSelectYear: (Int) -> Unit,
    onSelectMode: (TokenActivityViewMode) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var yearMenuExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.token_activity_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    text = if (state.recentSelected) {
                        "${stringResource(R.string.token_activity_recent)} ▾"
                    } else {
                        "${state.selectedYear} ▾"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = state.availableYears.isNotEmpty()) { yearMenuExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(
                    expanded = yearMenuExpanded,
                    onDismissRequest = { yearMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.token_activity_recent),
                                fontWeight = if (state.recentSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            yearMenuExpanded = false
                            onSelectRecent()
                        },
                    )
                    state.availableYears.forEach { year ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    year.toString(),
                                    fontWeight = if (!state.recentSelected && year == state.selectedYear) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            onClick = {
                                yearMenuExpanded = false
                                onSelectYear(year)
                            },
                        )
                    }
                }
            }
        }

        TokenActivityProfileCard()

        TokenStatsWhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.token_activity_insights),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.token_activity_total_requests),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (state.loading) "–" else formatCount(state.insights.totalRequests),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.token_activity_peak_hours),
                    style = MaterialTheme.typography.bodySmall,
                    color = TokenStatsCardMuted,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { index ->
                        val hour = state.insights.topHours.getOrNull(index)
                        Text(
                            text = if (state.loading || hour == null) "–" else stringResource(
                                R.string.token_activity_hour_range,
                                hour,
                                (hour + 1) % 24,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF7F2F4))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            TokenActivityViewMode.entries.forEach { mode ->
                val selected = state.viewMode == mode
                Text(
                    text = stringResource(
                        when (mode) {
                            TokenActivityViewMode.DAILY -> R.string.token_activity_daily
                            TokenActivityViewMode.WEEKLY -> R.string.token_activity_weekly
                            TokenActivityViewMode.CUMULATIVE -> R.string.token_activity_cumulative
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color(0xFFE91E63) else TokenStatsCardMuted,
                    modifier = Modifier.clickable { onSelectMode(mode) },
                )
            }
        }

        val stats = state.yearData?.stats
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenActivityStat(
                    stringResource(R.string.token_activity_total_tokens),
                    if (state.loading || stats == null) "–" else formatCompactCount(stats.totalTokens),
                    Modifier.weight(1f),
                )
                TokenActivityStat(
                    stringResource(R.string.token_activity_peak_tokens),
                    if (state.loading || stats == null) "–" else formatCompactCount(stats.peakTokens),
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenActivityStat(
                    stringResource(R.string.token_activity_current_streak),
                    if (state.loading || stats == null) "–" else stringResource(R.string.token_activity_days, stats.currentStreak),
                    Modifier.weight(1f),
                )
                TokenActivityStat(
                    stringResource(R.string.token_activity_longest_streak),
                    if (state.loading || stats == null) "–" else stringResource(R.string.token_activity_days, stats.longestStreak),
                    Modifier.weight(1f),
                )
            }
        }

        TokenStatsWhiteCard(Modifier.fillMaxWidth()) {
            Crossfade(
                targetState = state.viewMode,
                animationSpec = tween(150),
                label = "token_activity_heatmap",
            ) { mode ->
                TokenActivityHeatmap(
                    state = state.copy(viewMode = mode),
                    zone = zone,
                    locale = locale,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TokenActivityStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TokenStatsCardMuted)
    }
}

@Composable
private fun TokenActivityProfileCard() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE) }
    var nickname by remember { mutableStateOf(prefs.getString(KEY_NICKNAME, "").orEmpty()) }
    var email by remember { mutableStateOf(prefs.getString(KEY_EMAIL, "").orEmpty()) }
    val initialAvatarPath = remember { prefs.getString(KEY_AVATAR_PATH, null) }
    var avatarPath by remember { mutableStateOf(initialAvatarPath) }
    var avatarRevision by remember {
        mutableStateOf(
            prefs.getLong(
                KEY_AVATAR_REVISION,
                initialAvatarPath?.let { File(it).lastModified() } ?: 0L,
            )
        )
    }
    var showEdit by remember { mutableStateOf(false) }
    var showAvatarActions by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun persistAvatar(uri: Uri?): String? = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, AVATAR_FILE)
        val atomicFile = AtomicFile(target)
        if (uri == null) {
            atomicFile.delete()
            return@withContext null
        }
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open avatar")
        var output: FileOutputStream? = null
        try {
            input.use {
                val stream = atomicFile.startWrite()
                output = stream
                it.copyTo(stream)
                atomicFile.finishWrite(stream)
                output = null
            }
        } catch (e: Exception) {
            output?.let(atomicFile::failWrite)
            throw e
        }
        target.absolutePath
    }

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { croppedUri ->
                scope.launch {
                    runCatching { persistAvatar(croppedUri) }.onSuccess { path ->
                        val revision = maxOf(System.currentTimeMillis(), avatarRevision + 1L)
                        avatarPath = path
                        avatarRevision = revision
                        prefs.edit()
                            .putString(KEY_AVATAR_PATH, path)
                            .putLong(KEY_AVATAR_REVISION, revision)
                            .apply()
                    }
                }
            }
        } else if (result.error != null) {
            Toast.makeText(
                context,
                resources.getString(R.string.avatar_crop_failed, result.error?.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cropLauncher.launch(
                CropImageContractOptions(
                    uri,
                    CropImageOptions().apply {
                        guidelines = CropImageView.Guidelines.ON
                        outputCompressFormat = Bitmap.CompressFormat.PNG
                        outputCompressQuality = 90
                        fixAspectRatio = true
                        aspectRatioX = 1
                        aspectRatioY = 1
                        cropMenuCropButtonTitle = resources.getString(R.string.theme_crop_done)
                        activityTitle = resources.getString(R.string.crop_avatar)
                        toolbarColor = Color.Gray.toArgb()
                        toolbarTitleColor = Color.White.toArgb()
                    },
                )
            )
        }
    }

    TokenStatsWhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.token_activity_profile),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showImportConfirm = true },
                ) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.token_activity_profile_import))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(context)
                        .data(avatarPath?.let(::File))
                        .memoryCacheKey("$avatarPath:$avatarRevision")
                        .diskCacheKey("$avatarPath:$avatarRevision")
                        .build()
                )
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF7F2F4))
                        .clickable { showAvatarActions = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarPath != null && painter.state !is AsyncImagePainter.State.Error) {
                        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        val trimmed = nickname.trim()
                        if (trimmed.isNotEmpty()) {
                            Text(
                                String(Character.toChars(trimmed.codePointAt(0))),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TokenStatsCardMuted,
                            )
                        } else {
                            Icon(Icons.Default.Person, null, tint = TokenStatsCardMuted)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val empty = stringResource(R.string.token_activity_profile_empty)
                    Text(
                        nickname.ifBlank { empty },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        email.ifBlank { empty },
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showEdit = true }) {
                    Icon(Icons.Default.Edit, stringResource(R.string.token_activity_profile_edit))
                }
            }
        }
    }

    if (showImportConfirm) {
        var remaining by remember { mutableIntStateOf(PROFILE_IMPORT_COUNTDOWN_SECONDS) }
        LaunchedEffect(Unit) {
            while (remaining > 0) {
                delay(1_000)
                remaining--
            }
        }
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.token_activity_profile_import_title)) },
            text = { Text(stringResource(R.string.token_activity_profile_import_message)) },
            confirmButton = {
                TextButton(
                    enabled = remaining == 0,
                    onClick = {
                        showImportConfirm = false
                        scope.launch {
                            val global = DisplayPreferencesManager.getInstance(context)
                            val importedName = global.globalUserName.first().orEmpty()
                            val globalAvatarUri = global.globalUserAvatarUri.first()?.takeUnless { it.isBlank() }
                            val importedPath = if (globalAvatarUri == null) {
                                persistAvatar(null)
                            } else {
                                runCatching { persistAvatar(Uri.parse(globalAvatarUri)) }.getOrNull()
                            }
                            val decision = decideAvatarImport(
                                globalAvatarUri,
                                importedPath,
                                avatarPath,
                                avatarRevision,
                                System.currentTimeMillis(),
                            )
                            nickname = importedName
                            val editor = prefs.edit().putString(KEY_NICKNAME, importedName)
                            if (decision.applyAvatar) {
                                avatarPath = decision.avatarPath
                                avatarRevision = decision.avatarRevision
                                editor.putLong(KEY_AVATAR_REVISION, decision.avatarRevision).apply {
                                    if (decision.avatarPath == null) remove(KEY_AVATAR_PATH)
                                    else putString(KEY_AVATAR_PATH, decision.avatarPath)
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.token_activity_profile_avatar_import_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            editor.apply()
                        }
                    },
                ) {
                    Text(
                        if (remaining > 0) {
                            stringResource(R.string.token_activity_profile_import_countdown, remaining)
                        } else {
                            stringResource(R.string.token_activity_profile_import_confirm)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showEdit) {
        var editingName by remember(nickname) { mutableStateOf(nickname) }
        var editingEmail by remember(email) { mutableStateOf(email) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text(stringResource(R.string.token_activity_profile_edit)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        label = { Text(stringResource(R.string.token_activity_nickname)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingEmail,
                        onValueChange = { editingEmail = it },
                        label = { Text(stringResource(R.string.token_activity_email)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    nickname = editingName
                    email = editingEmail
                    prefs.edit().putString(KEY_NICKNAME, nickname).putString(KEY_EMAIL, email).apply()
                    showEdit = false
                }) { Text(stringResource(R.string.token_activity_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEdit = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    if (showAvatarActions) {
        AlertDialog(
            onDismissRequest = { showAvatarActions = false },
            title = { Text(stringResource(R.string.token_activity_avatar)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showAvatarActions = false
                        picker.launch("image/*")
                    }) { Text(stringResource(R.string.token_activity_avatar_pick)) }
                    if (avatarPath != null) {
                        TextButton(onClick = {
                            File(avatarPath.orEmpty()).delete()
                            val revision = maxOf(System.currentTimeMillis(), avatarRevision + 1L)
                            avatarPath = null
                            avatarRevision = revision
                            prefs.edit()
                                .remove(KEY_AVATAR_PATH)
                                .putLong(KEY_AVATAR_REVISION, revision)
                                .apply()
                            showAvatarActions = false
                        }) { Text(stringResource(R.string.token_activity_avatar_remove)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarActions = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun TokenActivityHeatmap(
    state: TokenActivityUiState,
    zone: ZoneId,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val data = state.yearData
    if (state.loading || data == null) {
        Box(modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val days = if (state.viewMode == TokenActivityViewMode.CUMULATIVE) data.cumulative else data.daily
    val firstDate = days.firstOrNull()?.date
    val padding = firstDate?.let { it.dayOfWeek.value % 7 } ?: 0
    val columns = ((padding + days.size + 6) / 7).coerceAtLeast(1)
    val grid = remember(days, padding) {
        List(columns) { column ->
            List<TokenActivityDay?>(7) { row ->
                days.getOrNull(column * 7 + row - padding)
            }
        }
    }
    val density = LocalDensity.current
    val block = 11.dp
    val gap = 3.dp
    val stepPx = with(density) { (block + gap).toPx() }
    val blockPx = with(density) { block.toPx() }
    val radiusPx = with(density) { 3.dp.toPx() }
    val width = (block + gap) * columns - gap
    val gridHeight = (block + gap) * 7 - gap
    val monthLabelHeight = 20.dp
    val canvasHeight = gridHeight + monthLabelHeight
    val gridHeightPx = with(density) { gridHeight.toPx() }
    val monthLabelGapPx = with(density) { 4.dp.toPx() }
    val scroll = rememberScrollState()
    var selectedDay by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableStateOf<TokenActivityDay?>(null)
    }
    var selectedWeek by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableStateOf<TokenActivityWeek?>(null)
    }
    var indicatorDay by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableStateOf<TokenActivityDay?>(null)
    }
    var indicatorWeek by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableStateOf<TokenActivityWeek?>(null)
    }
    var indicatorColumn by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableIntStateOf(-1)
    }
    var indicatorRow by remember(state.recentSelected, state.selectedYear, state.viewMode) {
        mutableIntStateOf(-1)
    }
    val colors = listOf(
        Color(0xFFEFE0E5), Color(0xFFFFD6E0), Color(0xFFFFB3C6),
        Color(0xFFFF85A2), Color(0xFFFF6B8E), Color(0xFFE84973),
    )
    val selectionColor = MaterialTheme.colorScheme.primary
    val selectionStroke = with(density) { 1.5.dp.toPx() }
    val monthLabels = remember(grid, locale) {
        val formatter = DateTimeFormatter.ofPattern("MMM", locale)
        val raw = buildList {
            var previousMonth = -1
            grid.forEachIndexed { index, week ->
                val date = week.firstOrNull { it != null }?.date ?: return@forEachIndexed
                if (index == 0 || date.monthValue != previousMonth) {
                    add(TokenActivityMonthLabel(index, formatter.format(date)))
                    previousMonth = date.monthValue
                }
            }
        }
        raw.filterIndexed { index, label ->
            when {
                index == 0 -> raw.getOrNull(1)?.let { it.column - label.column >= 3 } ?: false
                index == raw.lastIndex -> columns - label.column >= 3
                else -> true
            }
        }
    }
    val monthPaint = remember(density) {
        Paint().apply {
            textSize = with(density) { 12.sp.toPx() }
            color = TokenStatsCardMuted.toArgb()
            isAntiAlias = true
        }
    }

    LaunchedEffect(columns, state.recentSelected, state.selectedYear, state.viewMode) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(scroll.maxValue)
    }

    Column(modifier) {
        Column(Modifier.horizontalScroll(scroll)) {
            Canvas(
                modifier = Modifier
                    .size(width, canvasHeight)
                    // 顺序与 RainyToken 一致：查看/滚动仲裁必须先于点击检测收到事件。
                    .pointerInput(state.viewMode, grid, data.weekly, stepPx, blockPx) {
                        val viewSpeedThresholdPxPerMs =
                            with(density) { HEATMAP_VIEW_SPEED_DP_PER_S.dp.toPx() } / 1_000f

                        fun updateIndicator(point: Offset) {
                            val column = (point.x / stepPx).toInt().coerceIn(0, columns - 1)
                            val row = (point.y / stepPx).toInt().coerceIn(0, 6)
                            if (state.viewMode == TokenActivityViewMode.WEEKLY) {
                                val week = data.weekly.getOrNull(column)
                                indicatorWeek = week
                                indicatorDay = null
                                indicatorColumn = if (week == null) -1 else column
                                indicatorRow = if (week == null) -1 else row
                            } else {
                                val day = grid.getOrNull(column)?.getOrNull(row)
                                indicatorDay = day
                                indicatorWeek = null
                                indicatorColumn = if (day == null) -1 else column
                                indicatorRow = if (day == null) -1 else row
                            }
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var mode: HeatmapDragMode? = null
                            var lastPosition = down.position
                            var lastTime = SystemClock.uptimeMillis()
                            var totalDx = 0f
                            var totalDy = 0f
                            val slop = viewConfiguration.touchSlop
                            val downTime = lastTime
                            val longPressMs = viewConfiguration.longPressTimeoutMillis

                            while (mode == null) {
                                val remaining = longPressMs - (SystemClock.uptimeMillis() - downTime)
                                val event = if (remaining > 0L) {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    null
                                }
                                if (event == null) {
                                    mode = HeatmapDragMode.VIEW
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val current = change.position
                                val now = SystemClock.uptimeMillis()
                                val dx = current.x - lastPosition.x
                                val dy = current.y - lastPosition.y
                                val elapsed = (now - lastTime).coerceAtLeast(1L)
                                val horizontalSpeed = abs(dx) / elapsed
                                lastPosition = current
                                lastTime = now
                                totalDx += dx
                                totalDy += dy
                                if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                    mode = if (
                                        abs(totalDx) > abs(totalDy) &&
                                        horizontalSpeed < viewSpeedThresholdPxPerMs
                                    ) {
                                        HeatmapDragMode.VIEW
                                    } else {
                                        HeatmapDragMode.SCROLL
                                    }
                                    if (mode == HeatmapDragMode.VIEW) change.consume()
                                }
                            }

                            if (mode == HeatmapDragMode.VIEW) {
                                updateIndicator(lastPosition)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    updateIndicator(change.position)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            } else if (mode == HeatmapDragMode.SCROLL) {
                                indicatorDay = null
                                indicatorWeek = null
                                indicatorColumn = -1
                                indicatorRow = -1
                            }
                        }
                    }
                    .pointerInput(state.viewMode, grid, data.weekly) {
                        detectTapGestures { point ->
                            indicatorDay = null
                            indicatorWeek = null
                            indicatorColumn = -1
                            indicatorRow = -1
                            if (point.x % stepPx >= blockPx || point.y % stepPx >= blockPx) return@detectTapGestures
                            val column = (point.x / stepPx).toInt()
                            val row = (point.y / stepPx).toInt()
                            if (state.viewMode == TokenActivityViewMode.WEEKLY) {
                                val week = data.weekly.getOrNull(column)
                                selectedWeek = if (selectedWeek == week) null else week
                                selectedDay = null
                            } else {
                                val day = grid.getOrNull(column)?.getOrNull(row)
                                selectedDay = if (selectedDay == day) null else day
                                selectedWeek = null
                            }
                        }
                    },
            ) {
                if (state.viewMode == TokenActivityViewMode.WEEKLY) {
                    data.weekly.forEachIndexed { column, week ->
                        repeat(7) { row ->
                            val inBar = row >= 7 - week.barHeight
                            drawRoundRect(
                                color = if (inBar) colors[week.level.coerceIn(0, 5)] else colors[0],
                                topLeft = Offset(column * stepPx, row * stepPx),
                                size = Size(blockPx, blockPx),
                                cornerRadius = CornerRadius(radiusPx),
                            )
                        }
                    }
                } else {
                    grid.forEachIndexed { column, week ->
                        week.forEachIndexed { row, day ->
                            if (day != null) drawRoundRect(
                                color = colors[day.level.coerceIn(0, 5)],
                                topLeft = Offset(column * stepPx, row * stepPx),
                                size = Size(blockPx, blockPx),
                                cornerRadius = CornerRadius(radiusPx),
                            )
                        }
                    }
                }

                drawIntoCanvas { canvas ->
                    val baseline = gridHeightPx + monthLabelGapPx - monthPaint.ascent()
                    monthLabels.forEach { label ->
                        canvas.nativeCanvas.drawText(
                            label.text,
                            label.column * stepPx,
                            baseline,
                            monthPaint,
                        )
                    }
                }

                val indicatorValid = when {
                    indicatorWeek != null -> data.weekly.getOrNull(indicatorColumn) != null
                    indicatorDay != null -> grid.getOrNull(indicatorColumn)?.getOrNull(indicatorRow) != null
                    else -> false
                }
                if (indicatorValid && indicatorColumn in 0 until columns && indicatorRow in 0..6) {
                    drawRoundRect(
                        color = selectionColor,
                        topLeft = Offset(indicatorColumn * stepPx, indicatorRow * stepPx),
                        size = Size(blockPx, blockPx),
                        cornerRadius = CornerRadius(radiusPx),
                        style = Stroke(width = selectionStroke * 1.5f),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
            val text = when {
                indicatorDay != null -> stringResource(
                    R.string.token_activity_day_detail,
                    indicatorDay!!.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    formatCompactCount(indicatorDay!!.tokens),
                )
                indicatorWeek != null -> stringResource(
                    R.string.token_activity_week_detail,
                    indicatorWeek!!.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    indicatorWeek!!.startDate.plusDays(6).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    formatCompactCount(indicatorWeek!!.tokens),
                )
                selectedDay != null -> stringResource(
                    R.string.token_activity_day_detail,
                    selectedDay!!.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    formatCompactCount(selectedDay!!.tokens),
                )
                selectedWeek != null -> stringResource(
                    R.string.token_activity_week_detail,
                    selectedWeek!!.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    selectedWeek!!.startDate.plusDays(6).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    formatCompactCount(selectedWeek!!.tokens),
                )
                else -> stringResource(R.string.token_activity_tap_hint)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = TokenStatsCardMuted, maxLines = 1)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.token_activity_less), fontSize = 12.sp, color = TokenStatsCardMuted)
            Spacer(Modifier.width(4.dp))
            colors.forEach { color ->
                Box(Modifier.size(block).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(gap))
            }
            Text(stringResource(R.string.token_activity_more), fontSize = 12.sp, color = TokenStatsCardMuted)
        }
    }
}

private enum class HeatmapDragMode { VIEW, SCROLL }

private data class TokenActivityMonthLabel(val column: Int, val text: String)

private const val HEATMAP_VIEW_SPEED_DP_PER_S = 150f
private const val PROFILE_IMPORT_COUNTDOWN_SECONDS = 3

private const val PROFILE_PREFS = "token_activity_profile"
private const val KEY_NICKNAME = "nickname"
private const val KEY_EMAIL = "email"
private const val KEY_AVATAR_PATH = "avatar_path"
private const val KEY_AVATAR_REVISION = "avatar_revision"
private const val AVATAR_FILE = "token_activity_avatar"
