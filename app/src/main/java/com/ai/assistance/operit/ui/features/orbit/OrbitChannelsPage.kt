package com.ai.assistance.operit.ui.features.orbit

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitWeixin
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WeixinGreen = Color(0xFF07C160)

/** WeChat's mark as Orbit draws it: a green rounded square with a white speech bubble. */
@Composable
private fun WeixinMark(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(WeixinGreen),
        contentAlignment = Alignment.Center,
    ) {
        Icon(orbitIcon("message-circle"), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
private fun rememberWeixinStatus(orbit: OrbitProfile): OrbitWeixin.Status? {
    val context = LocalContext.current
    LaunchedEffect(orbit.id) { OrbitWeixin.refreshStatus(context, orbit.id) }
    val status by OrbitWeixin.status.collectAsState()
    return status?.takeIf { it.orbitId == orbit.id }
}

/** Other messaging apps the user can talk to Orbit from. WeChat for now. */
@Composable
internal fun OrbitChannelsPage(orbit: OrbitProfile, onBack: () -> Unit, onOpenWeixin: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val status = rememberWeixinStatus(orbit)
    val cardColor = settingsCardColor(false)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OrbitPageHeader(stringResource(R.string.orbit_channels_title), onBack, Modifier.padding(start = 4.dp))
            Text(
                stringResource(R.string.orbit_channels_intro, orbit.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                stringResource(if (status?.connected == true) R.string.orbit_channels_connected else R.string.orbit_channels_available),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
            )
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(0.dp)) {
                Surface(onClick = onOpenWeixin, color = Color.Transparent, shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WeixinMark(40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.orbit_weixin), style = MaterialTheme.typography.titleMedium)
                            if (status?.connected == true) {
                                Text(
                                    stringResource(if (status.loginExpired) R.string.orbit_weixin_expired else R.string.orbit_channels_connected),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status.loginExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(orbitIcon("chevron-right"), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** WeChat: what linking means, the button that links it, and how the link is doing once linked. */
@Composable
internal fun OrbitWeixinPage(orbit: OrbitProfile, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = rememberWeixinStatus(orbit)
    var connecting by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val connected = status?.connected == true
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(start = 20.dp, top = 8.dp)) {
                OrbitCircleButton(orbitIcon("chevron-left"), stringResource(R.string.back), onBack)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                WeixinMark(88.dp)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.orbit_weixin), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                if (!connected) {
                    Text(
                        stringResource(R.string.orbit_weixin_intro, orbit.name),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.orbit_weixin_detail, orbit.name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    WeixinStatusCard(status!!)
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!connected) {
                    Text(
                        stringResource(R.string.orbit_weixin_footer, orbit.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WeixinButton(stringResource(R.string.orbit_weixin_connect)) { connecting = true }
                } else {
                    if (status!!.loginExpired) {
                        WeixinButton(stringResource(R.string.orbit_weixin_reconnect)) { connecting = true }
                    } else {
                        status.chatId?.let { chat -> WeixinButton(stringResource(R.string.orbit_weixin_open_chat)) { onOpenChat(chat) } }
                    }
                    TextButton(
                        onClick = { confirmDisconnect = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.orbit_weixin_disconnect), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    if (confirmDisconnect) {
        OrbitConfirmDialog(
            title = stringResource(R.string.orbit_weixin_disconnect_title),
            message = stringResource(R.string.orbit_weixin_disconnect_note, orbit.name),
            confirmLabel = stringResource(R.string.orbit_weixin_disconnect),
            destructive = true,
            onDismiss = { confirmDisconnect = false },
            onConfirm = {
                confirmDisconnect = false
                busy = true
                scope.launch {
                    runCatching { OrbitWeixin.disconnect(context, orbit.id) }
                        .onFailure { Toast.makeText(context, R.string.orbit_weixin_disconnect_failed, Toast.LENGTH_SHORT).show() }
                    busy = false
                }
            },
        )
    }
    if (connecting) WeixinConnectSheet(orbit, onDismiss = { connecting = false })
}

@Composable
private fun WeixinButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun WeixinStatusCard(status: OrbitWeixin.Status) {
    val context = LocalContext.current
    // Times are relative: this clock ticks while the page is open, so "1 min ago" keeps moving.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val (stateText, stateColor) = when {
        status.loginExpired -> stringResource(R.string.orbit_weixin_expired) to MaterialTheme.colorScheme.error
        // The brand green is too light for text on a light page; the theme's own colour reads well.
        status.online -> stringResource(R.string.orbit_weixin_online) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.orbit_weixin_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SettingsCard(backgroundColor = settingsCardColor(false)) {
        StatusRow(stringResource(R.string.orbit_weixin_status), stateText, stateColor)
        SettingsRowDivider(inset = false)
        StatusRow(
            stringResource(R.string.orbit_weixin_last_in),
            if (status.lastInboundAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(
                status.lastInboundAt, maxOf(now, status.lastInboundAt), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ).toString() else stringResource(R.string.orbit_weixin_never),
        )
        if (status.waitingToSend > 0) {
            SettingsRowDivider(inset = false)
            StatusRow(stringResource(R.string.orbit_weixin_waiting), stringResource(R.string.orbit_weixin_waiting_count, status.waitingToSend))
        }
    }
    val problem = when {
        status.loginExpired || status.lastError.isEmpty() -> null
        status.lastError == "too_many_side_chats" -> R.string.orbit_weixin_error_chats
        status.lastError.startsWith("network") -> R.string.orbit_weixin_error_network
        status.lastError.startsWith("send") -> R.string.orbit_weixin_error_send
        else -> R.string.orbit_weixin_error_other
    }
    if (problem != null && !(problem == R.string.orbit_weixin_error_network && status.online)) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(problem), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
    LaunchedEffect(status.orbitId) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            now = System.currentTimeMillis()
            OrbitWeixin.refreshStatus(context, status.orbitId)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End)
    }
}

private fun qrBitmap(content: String, size: Int = 720): Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * The QR scan, the number check WeChat may ask for, and the result, in one sheet. [onConnected]
 * runs once when a link made in this sheet is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeixinConnectSheet(orbit: OrbitProfile, onDismiss: () -> Unit, onConnected: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    val login = remember(attempt) { OrbitWeixin.Login(app, orbit.id) }
    LaunchedEffect(login) { login.run() }
    val step by login.step.collectAsState()
    val connected by rememberUpdatedState(onConnected)
    var told by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == OrbitWeixin.Login.Step.Done && !told) {
            told = true
            connected?.invoke()
        }
    }
    OrbitModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = step) {
                OrbitWeixin.Login.Step.Loading -> {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Text(stringResource(R.string.orbit_weixin_loading_qr), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                }
                is OrbitWeixin.Login.Step.Qr -> {
                    Text(stringResource(R.string.orbit_weixin_scan_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.orbit_weixin_scan_note), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    val bitmap by produceState<Bitmap?>(null, s.content) {
                        // A new code starts blank, so the expired one is never shown or saved meanwhile.
                        value = null
                        value = withContext(Dispatchers.Default) { runCatching { qrBitmap(s.content) }.getOrNull() }
                    }
                    val label = stringResource(R.string.orbit_weixin_qr_desc)
                    Box(
                        Modifier.size(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        bitmap?.let { Image(it.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize()) }
                            ?: CircularProgressIndicator(color = Color.Black.copy(alpha = 0.4f))
                    }
                    if (s.scanned) {
                        Text(stringResource(R.string.orbit_weixin_scanned), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                    OutlinedButton(
                        onClick = {
                            val image = bitmap ?: return@OutlinedButton
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val file = java.io.File(app.cacheDir, "weixin-login-qr.png")
                                        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                        saveToGallery(app, file).also { file.delete() }
                                    }.getOrDefault(false)
                                }
                                Toast.makeText(context, if (saved) R.string.orbit_weixin_qr_saved else R.string.orbit_weixin_qr_save_failed,
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = bitmap != null,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.orbit_weixin_save_qr)) }
                }
                is OrbitWeixin.Login.Step.Code -> {
                    var code by remember { mutableStateOf("") }
                    Text(stringResource(R.string.orbit_weixin_code_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (s.wrong) R.string.orbit_weixin_code_wrong else R.string.orbit_weixin_code_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s.wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter(Char::isDigit).take(12) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.orbit_weixin_code_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WeixinButton(stringResource(R.string.orbit_weixin_code_submit), enabled = code.isNotEmpty()) { login.submitCode(code) }
                }
                OrbitWeixin.Login.Step.Done -> {
                    Spacer(Modifier.height(8.dp))
                    WeixinMark(64.dp)
                    Text(stringResource(R.string.orbit_weixin_done), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.orbit_weixin_done_note, orbit.name), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    WeixinButton(stringResource(R.string.orbit_weixin_finish), onClick = onDismiss)
                }
                is OrbitWeixin.Login.Step.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(when (s.reason) {
                            OrbitWeixin.Login.Reason.EXPIRED -> R.string.orbit_weixin_fail_expired
                            OrbitWeixin.Login.Reason.BLOCKED -> R.string.orbit_weixin_fail_blocked
                            OrbitWeixin.Login.Reason.ALREADY_BOUND -> R.string.orbit_weixin_fail_bound
                            OrbitWeixin.Login.Reason.TIMEOUT -> R.string.orbit_weixin_fail_timeout
                            OrbitWeixin.Login.Reason.TOO_MANY_CHATS -> R.string.orbit_weixin_fail_chats
                            OrbitWeixin.Login.Reason.NETWORK -> R.string.orbit_weixin_fail_network
                        }),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    WeixinButton(stringResource(R.string.orbit_weixin_retry)) { attempt++ }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.orbit_weixin_close)) }
                }
            }
        }
    }
}
