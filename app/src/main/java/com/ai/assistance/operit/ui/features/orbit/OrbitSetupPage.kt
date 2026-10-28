package com.ai.assistance.operit.ui.features.orbit

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.features.orbit.OrbitSetup
import com.ai.assistance.operit.ui.features.settings.sections.getBuiltInProviderDisplayName
import kotlinx.coroutines.launch

private const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"

/**
 * The first start of Orbit: pick a provider, type an API key, pick the two models, and Orbit is
 * ready to talk. [onSkip] leaves for the plain Orbit list, for users who set models up themselves.
 */
@Composable
internal fun OrbitSetupPage(onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val providers = remember { OrbitSetup.providers() }
    var providerId by rememberSaveable { mutableStateOf(providers.first().name) }
    val provider = providers.firstOrNull { it.name == providerId } ?: providers.first()
    var endpoint by rememberSaveable(providerId) { mutableStateOf(OrbitSetup.defaultEndpoint(provider)) }
    var showEndpoint by rememberSaveable(providerId) { mutableStateOf(OrbitSetup.defaultEndpoint(provider).isBlank()) }
    // The key is never saved with the screen state; it only lives while the page is open.
    var key by remember(providerId) { mutableStateOf("") }
    var keyVisible by remember(providerId) { mutableStateOf(false) }
    var models by remember(providerId) { mutableStateOf<List<String>?>(null) }
    var main by remember(providerId) { mutableStateOf<String?>(null) }
    var assistant by remember(providerId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The provider's own words behind [error], shown only when the user asks for them.
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    // When the provider cannot list its models, the user types the model names instead.
    var manual by remember(providerId) { mutableStateOf(false) }
    var manualMain by remember(providerId) { mutableStateOf("") }
    var manualAssistant by remember(providerId) { mutableStateOf("") }
    // Models the main app already uses, offered so a user who set them up need not type a key again.
    var existing by remember { mutableStateOf<OrbitSetup.Existing?>(null) }
    LaunchedEffect(Unit) { existing = OrbitSetup.existing(context) }
    var pickProvider by remember { mutableStateOf(false) }
    val needsKey = OrbitSetup.requiresKey(provider, endpoint)
    val endpointOk = endpoint.trim().matches(Regex("https?://\\S{3,300}"))
    // Account services (ChatGPT, Claude, Grok…) sign in here instead of taking a key.
    val signIn = OrbitSetup.signsIn(provider)
    var signedIn by remember(providerId) { mutableStateOf(false) }
    var accountTick by remember { mutableStateOf(0) }
    LaunchedEffect(providerId, accountTick) {
        signedIn = signIn && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            OrbitSetup.signedIn(context.applicationContext, provider)
        }
    }
    // 1: ChatGPT browser login, 2: ChatGPT device code, 3: other account sign-in sheet.
    var login by remember { mutableStateOf(0) }
    SecureWindow()

    fun resetModels() { models = null; main = null; assistant = null; manual = false }

    /** After a sign-in or sign-out: read the account again; the model list belongs to the old account. */
    fun accountChanged(close: Boolean) {
        if (close) login = 0
        accountTick++
        resetModels()
        error = null
        scope.launch {
            runCatching { com.ai.assistance.operit.api.chat.EnhancedAIService.refreshAllServices(context.applicationContext) }
        }
    }

    fun showError(message: String, detail: String? = null) {
        error = message
        errorDetail = detail?.takeIf { it.isNotBlank() }
        showDetail = false
    }

    fun problemText(problem: OrbitSetup.Problem): String = OrbitSetup.describe(context, problem)

    fun fetch() {
        busy = true
        error = null
        scope.launch {
            val result = runCatching { OrbitSetup.fetchModels(context, provider, endpoint.trim(), key) }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            busy = false
            result.onSuccess { list ->
                if (list.isEmpty()) {
                    manual = true
                    showError(context.getString(R.string.orbit_setup_no_models))
                } else {
                    manual = false
                    models = list
                    main = OrbitSetup.suggestMain(list)
                    assistant = OrbitSetup.suggestAssistant(list, main)
                }
            }.onFailure { e ->
                val raw = e.message.orEmpty().let { if (key.length >= 6) it.replace(key, "***") else it }.take(300)
                // Some providers refuse to list models but still chat, so the names can always be typed;
                // the chat test then shows whether the key works.
                manual = true
                when (OrbitSetup.problemOf(raw)) {
                    OrbitSetup.Problem.KEY -> showError(context.getString(R.string.orbit_setup_problem_key), raw)
                    OrbitSetup.Problem.NETWORK -> showError(context.getString(R.string.orbit_setup_problem_network), raw)
                    else -> showError(context.getString(R.string.orbit_setup_fetch_failed), raw)
                }
            }
        }
    }

    fun start() {
        val m = (if (manual) manualMain.trim() else main)?.takeIf { it.isNotEmpty() } ?: return
        val a = (if (manual) manualAssistant.trim() else assistant)?.takeIf { it.isNotEmpty() } ?: m
        busy = true
        testing = true
        error = null
        scope.launch {
            val result = runCatching {
                OrbitSetup.finish(context, getBuiltInProviderDisplayName(provider, context), provider, endpoint.trim(), key, m, a)
            }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            busy = false
            testing = false
            result.onSuccess { key = "" }.onFailure { e ->
                val failure = e as? OrbitSetup.SetupException
                val reason = problemText(failure?.problem ?: OrbitSetup.Problem.OTHER)
                showError(
                    failure?.model?.let { context.getString(R.string.orbit_setup_helper_failed, it, reason) } ?: reason,
                    failure?.detail ?: e.message,
                )
            }
        }
    }

    fun useExisting() {
        busy = true
        error = null
        scope.launch {
            val result = runCatching { OrbitSetup.startWithExisting(context) }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            busy = false
            result.onFailure { e ->
                showError(context.getString(R.string.orbit_setup_problem_other), (e as? OrbitSetup.SetupException)?.detail ?: e.message)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(76.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(orbitIcon("orbit"), null, Modifier.size(38.dp), tint = colors.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.orbit_setup_title), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.orbit_setup_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            existing?.let { found ->
                Surface(shape = RoundedCornerShape(22.dp), color = colors.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.orbit_setup_existing_title), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.orbit_setup_existing_main, found.main), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        Text(stringResource(R.string.orbit_setup_existing_assistant, found.assistant),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = ::useExisting, enabled = !busy, shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                            Text(stringResource(R.string.orbit_setup_existing_use))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.orbit_setup_existing_or), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
            }

            SetupLabel(stringResource(R.string.orbit_setup_provider))
            Box {
                SetupField(onClick = { if (!busy) pickProvider = true }) {
                    Text(getBuiltInProviderDisplayName(provider, context), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (provider == ApiProviderType.DEEPSEEK) {
                        Text(stringResource(R.string.orbit_setup_recommended), style = MaterialTheme.typography.labelMedium,
                            color = colors.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(orbitIcon("chevron-down"), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                OrbitDropdownMenu(expanded = pickProvider, onDismissRequest = { pickProvider = false },
                    modifier = Modifier.heightIn(max = 360.dp)) {
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(getBuiltInProviderDisplayName(p, context)) },
                            onClick = {
                                pickProvider = false
                                if (p.name != providerId) { providerId = p.name; error = null }
                            },
                        )
                    }
                }
            }
            if (showEndpoint) {
                Spacer(Modifier.height(14.dp))
                SetupLabel(stringResource(R.string.orbit_setup_endpoint))
                SetupTextField(endpoint, { endpoint = it.take(300); resetModels() }, enabled = !busy,
                    placeholder = "https://", keyboardType = KeyboardType.Uri)
            } else {
                TextButton(onClick = { showEndpoint = true }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.orbit_setup_change_endpoint), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (signIn) {
                Spacer(Modifier.height(if (showEndpoint) 14.dp else 0.dp))
                SetupLabel(stringResource(R.string.orbit_setup_account))
                SetupField(onClick = {
                    if (!busy) login = if (provider == ApiProviderType.OPENAI_CODEX) 1 else 3
                }) {
                    Icon(orbitIcon(if (signedIn) "circle-check" else "log-in"), null, Modifier.size(18.dp),
                        tint = if (signedIn) colors.primary else colors.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (signedIn) R.string.orbit_setup_account_done else R.string.orbit_setup_account_none),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(when {
                        !signedIn -> R.string.orbit_chatgpt_card_login
                        // ChatGPT has no account page here; tapping signs in again.
                        provider == ApiProviderType.OPENAI_CODEX -> R.string.orbit_login_card_relogin
                        else -> R.string.orbit_setup_account_manage
                    }),
                        style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                if (provider == ApiProviderType.OPENAI_CODEX && !signedIn) {
                    TextButton(onClick = { login = 2 }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.orbit_chatgpt_card_device), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (needsKey) {
                Spacer(Modifier.height(if (showEndpoint) 14.dp else 0.dp))
                SetupLabel(stringResource(R.string.orbit_vault_api_key))
                SetupTextField(key, { if (it.length <= 4000) { key = it; resetModels() } }, enabled = !busy,
                    placeholder = "sk-…", keyboardType = KeyboardType.Password,
                    visual = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        androidx.compose.material3.IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(orbitIcon(if (keyVisible) "eye-off" else "eye"),
                                stringResource(if (keyVisible) R.string.orbit_vault_hide else R.string.orbit_vault_show))
                        }
                    })
                if (provider == ApiProviderType.DEEPSEEK) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEEPSEEK_KEYS_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.orbit_setup_get_key), style = MaterialTheme.typography.labelLarge) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(orbitIcon("shield-check"), null, Modifier.size(16.dp).padding(top = 2.dp), tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.orbit_setup_key_promise), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }

            val list = models
            if (list != null) {
                Spacer(Modifier.height(20.dp))
                SetupLabel(stringResource(R.string.orbit_setup_main))
                ModelDropdown(list, main, enabled = !busy) { main = it }
                Text(stringResource(R.string.orbit_setup_main_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp))
                Spacer(Modifier.height(14.dp))
                SetupLabel(stringResource(R.string.orbit_setup_assistant))
                ModelDropdown(list, assistant, enabled = !busy) { assistant = it }
                Text(stringResource(R.string.orbit_setup_assistant_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp))
            } else if (manual) {
                Spacer(Modifier.height(20.dp))
                SetupLabel(stringResource(R.string.orbit_setup_main))
                SetupTextField(manualMain, { manualMain = it.take(200) }, enabled = !busy,
                    placeholder = stringResource(R.string.orbit_setup_model_name_hint), keyboardType = KeyboardType.Uri)
                Text(stringResource(R.string.orbit_setup_manual_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp))
                Spacer(Modifier.height(14.dp))
                SetupLabel(stringResource(R.string.orbit_setup_assistant))
                SetupTextField(manualAssistant, { manualAssistant = it.take(200) }, enabled = !busy,
                    placeholder = stringResource(R.string.orbit_setup_same_as_main), keyboardType = KeyboardType.Uri)
                Text(stringResource(R.string.orbit_setup_assistant_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp))
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                errorDetail?.let { detail ->
                    TextButton(onClick = { showDetail = !showDetail }) {
                        Text(stringResource(if (showDetail) R.string.orbit_setup_hide_detail else R.string.orbit_setup_show_detail),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    if (showDetail) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            val listing = list == null && !manual
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (listing) fetch() else start() },
                enabled = !busy && endpointOk && (!needsKey || key.isNotBlank()) && (!signIn || signedIn) &&
                    (listing || (if (manual && list == null) manualMain.isNotBlank() else main != null)),
                shape = RoundedCornerShape(50),
                // While working the button stays in its normal colors so the spinner is easy to see.
                colors = if (busy) androidx.compose.material3.ButtonDefaults.buttonColors(
                    disabledContainerColor = colors.primary, disabledContentColor = colors.onPrimary)
                else androidx.compose.material3.ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(when {
                        testing -> R.string.orbit_setup_testing
                        listing -> R.string.orbit_setup_check
                        else -> R.string.orbit_setup_start
                    }),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip, enabled = !busy) {
                Text(stringResource(R.string.orbit_setup_skip), color = colors.onSurfaceVariant)
            }
        }
        when (login) {
            1 -> com.ai.assistance.operit.ui.features.codex.CodexLoginDialog(onDismissRequest = { login = 0 },
                onLoginSuccess = { accountChanged(close = true) }, presenter = { OrbitChatGptLoginSheet(it) })
            2 -> com.ai.assistance.operit.ui.features.codex.CodexDeviceLoginDialog(onDismissRequest = { login = 0 },
                onLoginSuccess = { accountChanged(close = true) }, presenter = { OrbitChatGptLoginSheet(it) })
            3 -> com.ai.assistance.operit.data.api.AccountProvider.from(provider)?.let { account ->
                SetupAccountSheet(account, onDismiss = { login = 0 }, onChanged = ::accountChanged)
            }
        }
    }
}

/** Signs in to an account service right on the setup page; [onChanged] gets whether to close (signed in now). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SetupAccountSheet(
    account: com.ai.assistance.operit.data.api.AccountProvider,
    onDismiss: () -> Unit,
    onChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // The account store is encrypted; opening it is kept off the main thread.
    val manager by androidx.compose.runtime.produceState<com.ai.assistance.operit.data.api.ProviderAccountManager?>(null, account) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ai.assistance.operit.data.api.ProviderAccountManager.get(context.applicationContext, account)
        }
    }
    OrbitModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.orbit_account_card_title,
                com.ai.assistance.operit.features.orbit.OrbitChatCards.accountLabel(account)),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val current = manager
            if (current == null) {
                // Shown only after the store is open, so the sheet never opens it on the main thread.
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                com.ai.assistance.operit.ui.features.settings.components.SettingsDialogs(
                    com.ai.assistance.operit.ui.features.settings.components.SettingsDialogStyle.ORBIT,
                ) {
                    com.ai.assistance.operit.ui.features.codex.ProviderAccountSettings(account) {
                        onChanged(current.account.value != null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 6.dp))
}

@Composable
private fun SetupField(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp), content = content)
    }
}

@Composable
private fun SetupTextField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    keyboardType: KeyboardType,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        TextField(
            value, onChange, singleLine = true, enabled = enabled,
            placeholder = { Text(placeholder) },
            visualTransformation = visual,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, autoCorrect = false),
            trailingIcon = trailing,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ModelDropdown(models: List<String>, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Long provider lists get a search box, and only the first matches are drawn.
    val searchable = models.size > SEARCH_FROM
    val matches = remember(models, query) {
        val q = query.trim()
        if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) }
    }
    Box {
        SetupField(onClick = { if (enabled) open = true }) {
            Text(selected ?: stringResource(R.string.orbit_setup_pick_model), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(orbitIcon("chevron-down"), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OrbitDropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }, modifier = Modifier.heightIn(max = 360.dp)) {
            if (searchable) {
                TextField(
                    query, { query = it.take(80) }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.orbit_model_pick_search)) },
                    leadingIcon = { Icon(orbitIcon("search"), null, Modifier.size(18.dp)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            matches.take(MAX_SHOWN).forEach { m ->
                DropdownMenuItem(text = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false; query = ""; onSelect(m) })
            }
            if (matches.size > MAX_SHOWN) {
                Text(stringResource(R.string.orbit_model_pick_more, matches.size - MAX_SHOWN),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            } else if (matches.isEmpty()) {
                Text(stringResource(R.string.orbit_setup_no_match),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

private const val SEARCH_FROM = 12
private const val MAX_SHOWN = 60
