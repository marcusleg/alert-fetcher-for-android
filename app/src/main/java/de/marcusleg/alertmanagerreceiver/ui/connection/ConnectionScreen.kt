package de.marcusleg.alertmanagerreceiver.ui.connection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marcusleg.alertmanagerreceiver.alertmanager.AlertmanagerApi
import de.marcusleg.alertmanagerreceiver.alertmanager.RequestAuthenticator
import de.marcusleg.alertmanagerreceiver.auth.OidcManager
import de.marcusleg.alertmanagerreceiver.auth.OidcSession
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import de.marcusleg.alertmanagerreceiver.data.CheckEntity
import de.marcusleg.alertmanagerreceiver.data.OidcTokenType
import de.marcusleg.alertmanagerreceiver.ui.alerts.rememberTicker
import de.marcusleg.alertmanagerreceiver.ui.components.ChoiceRow
import de.marcusleg.alertmanagerreceiver.ui.components.CodeBlock
import de.marcusleg.alertmanagerreceiver.ui.components.CopyButton
import de.marcusleg.alertmanagerreceiver.ui.components.SectionCard
import de.marcusleg.alertmanagerreceiver.ui.components.SettingTextField
import de.marcusleg.alertmanagerreceiver.ui.components.StatusDot
import de.marcusleg.alertmanagerreceiver.ui.components.shareText
import de.marcusleg.alertmanagerreceiver.ui.theme.LocalStatusColors
import de.marcusleg.alertmanagerreceiver.ui.theme.MonoFamily
import de.marcusleg.alertmanagerreceiver.util.ConfigSnippets
import de.marcusleg.alertmanagerreceiver.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(viewModel: ConnectionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val now by rememberTicker(10_000)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Connection") }) },
    ) { padding ->
        if (state.loading) return@Scaffold
        val settings = state.settings
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state, now, viewModel)
            AlertmanagerCard(settings, state.busy, viewModel)
            AuthenticationCard(settings, state.session, state.busy.signingIn, now, viewModel)
            ChecksCard(settings, viewModel)
            SetupCard(settings)
            RecentChecksCard(state.checks, now)
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionUiState, now: Long, viewModel: ConnectionViewModel) {
    val colors = LocalStatusColors.current
    val settings = state.settings
    val monitor = state.monitor
    data class Status(val title: String, val subtitle: String, val color: Color, val icon: ImageVector)
    val status = when {
        !settings.isConfigured -> Status("Not connected", "Enter your Alertmanager URL below", MaterialTheme.colorScheme.outline, Icons.Outlined.CloudOff)
        !settings.monitoringEnabled -> Status(
            "Monitoring off",
            "Turn on to be notified about new alerts",
            MaterialTheme.colorScheme.outline,
            Icons.Outlined.SyncDisabled,
        )
        monitor.authProblem -> Status("Sign-in required", monitor.lastError.orEmpty(), colors.critical, Icons.Outlined.Key)
        monitor.lastCheckFailed -> Status(
            "Can't reach Alertmanager",
            monitor.lastError.orEmpty(),
            if (monitor.contactLostNotified) colors.critical else colors.warning,
            Icons.Outlined.CloudOff,
        )
        monitor.lastSuccessAt != null -> Status(
            "Monitoring",
            "Checked ${TimeFormat.relative(monitor.lastSuccessAt, now)} · every ${settings.pollIntervalMinutes} min",
            colors.resolved,
            Icons.Outlined.CloudDone,
        )
        else -> Status("Connecting…", "Checking Alertmanager", colors.warning, Icons.Outlined.CloudSync)
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = status.color.copy(alpha = 0.13f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = status.color.copy(alpha = 0.2f), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(status.icon, contentDescription = null, tint = status.color) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(status.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        status.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = settings.monitoringEnabled, onCheckedChange = viewModel::setEnabled, enabled = settings.isConfigured)
            }
            if (settings.heartbeatEnabled && settings.monitoringEnabled && monitor.lastSuccessAt != null) {
                val (dot, text) = when {
                    monitor.heartbeatMissingNotified -> colors.critical to "${settings.heartbeatAlertName} heartbeat missing"
                    monitor.heartbeatLastSeenAt != null -> colors.resolved to "${settings.heartbeatAlertName} heartbeat OK"
                    else -> colors.warning to "Waiting for the ${settings.heartbeatAlertName} heartbeat"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot)
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (settings.isConfigured) {
                FilledTonalButton(onClick = viewModel::checkNow, enabled = !state.polling) {
                    if (state.polling) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Check now")
                }
            }
        }
    }
}

@Composable
private fun AlertmanagerCard(settings: AppSettings, busy: Busy, viewModel: ConnectionViewModel) {
    val colors = LocalStatusColors.current
    SectionCard("Alertmanager", icon = Icons.Outlined.Hub) {
        SettingTextField(
            label = "URL",
            value = settings.alertmanagerUrl,
            placeholder = "https://alertmanager.example.com",
            keyboardType = KeyboardType.Uri,
            supporting = "The Alertmanager web UI address, e.g. your Ingress or Gateway hostname",
            validate = { text -> if (text.isBlank()) null else runCatching { AlertmanagerApi.validateBaseUrl(text) }.exceptionOrNull()?.message },
            onChange = { v -> viewModel.update { it.copy(alertmanagerUrl = v.trim()) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = viewModel::testConnection, enabled = settings.isConfigured && !busy.testing) {
                if (busy.testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Test connection")
            }
        }
        when (val result = busy.testResult) {
            is TestResult.Success -> ResultRow(
                Icons.Outlined.CheckCircle,
                colors.resolved,
                "Alertmanager ${result.version} · cluster ${result.cluster}" +
                    (if (result.peers > 1) " (${result.peers} peers)" else "") + " · ${result.alerts} alerts",
            )
            is TestResult.Failure -> ResultRow(Icons.Outlined.ErrorOutline, colors.critical, result.message)
            null -> Unit
        }
    }
}

@Composable
private fun ResultRow(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AuthenticationCard(settings: AppSettings, session: OidcSession?, signingIn: Boolean, now: Long, viewModel: ConnectionViewModel) {
    SectionCard("Authentication", icon = Icons.Outlined.Key) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(AuthMode.OIDC to "OIDC", AuthMode.BASIC to "Basic", AuthMode.BEARER to "Token", AuthMode.NONE to "None")
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.authMode == mode,
                    onClick = { viewModel.update { it.copy(authMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        when (settings.authMode) {
            AuthMode.OIDC -> OidcFields(settings, session, signingIn, now, viewModel)
            AuthMode.BASIC -> {
                SettingTextField("Username", settings.basicUsername, { v -> viewModel.update { it.copy(basicUsername = v.trim()) } })
                SettingTextField("Password", settings.basicPassword, { v -> viewModel.update { it.copy(basicPassword = v) } }, secret = true)
            }
            AuthMode.BEARER -> SettingTextField(
                "Bearer token",
                settings.bearerToken,
                { v -> viewModel.update { it.copy(bearerToken = v.trim()) } },
                secret = true,
                supporting = "Sent as \"Authorization: Bearer …\", e.g. a Kubernetes service account token checked by the proxy",
            )
            AuthMode.NONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = LocalStatusColors.current.warning)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Only if Alertmanager is protected by other means, e.g. reachable through your VPN only.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        var showHeaders by rememberSaveable { mutableStateOf(settings.extraHeaders.isNotBlank()) }
        if (showHeaders) {
            SettingTextField(
                label = "Additional headers",
                value = settings.extraHeaders,
                onChange = { v -> viewModel.update { it.copy(extraHeaders = v) } },
                singleLine = false,
                mono = true,
                placeholder = "CF-Access-Client-Id: …",
                supporting = "One \"Name: value\" per line, sent with every request (e.g. Cloudflare Access service tokens)",
                validate = { RequestAuthenticator.parseExtraHeaders(it).error },
            )
        } else {
            TextButton(onClick = { showHeaders = true }) { Text("Add request headers") }
        }
    }
}

@Composable
private fun OidcFields(settings: AppSettings, session: OidcSession?, signingIn: Boolean, now: Long, viewModel: ConnectionViewModel) {
    val colors = LocalStatusColors.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.completeSignIn(result.data)
    }
    SettingTextField(
        label = "Issuer URL",
        value = settings.oidcIssuer,
        placeholder = "https://sso.example.com/realms/homelab",
        keyboardType = KeyboardType.Uri,
        validate = { text -> if (text.isBlank()) null else runCatching { OidcManager.validateIssuer(text) }.exceptionOrNull()?.message },
        onChange = { v -> viewModel.update { it.copy(oidcIssuer = v.trim()) } },
    )
    SettingTextField("Client ID", settings.oidcClientId, { v -> viewModel.update { it.copy(oidcClientId = v.trim()) } })
    SettingTextField(
        "Client secret (optional)",
        settings.oidcClientSecret,
        { v -> viewModel.update { it.copy(oidcClientSecret = v.trim()) } },
        secret = true,
        supporting = "Leave empty for a public client (recommended; PKCE protects the sign-in)",
    )
    SettingTextField(
        "Scopes",
        settings.oidcScopes,
        { v -> viewModel.update { it.copy(oidcScopes = v) } },
        supporting = "offline_access is needed for a refresh token on most providers",
    )
    ChoiceRow(
        title = "Token sent to Alertmanager",
        subtitle = "oauth2-proxy usually needs the ID token; most gateways validate the access token",
        options = OidcTokenType.entries,
        selected = settings.oidcTokenType,
        label = { if (it == OidcTokenType.ACCESS_TOKEN) "Access token" else "ID token" },
        onSelect = { v -> viewModel.update { it.copy(oidcTokenType = v) } },
    )
    OutlinedTextField(
        value = viewModel.redirectUri,
        onValueChange = {},
        readOnly = true,
        label = { Text("Redirect URI (register at your provider)") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
        trailingIcon = { CopyButton(viewModel.redirectUri, "redirect URI") },
        keyboardOptions = KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
    val matching = session?.takeIf { it.issuer == settings.oidcIssuer.trim() && it.clientId == settings.oidcClientId.trim() }
    val sessionMatches = matching != null
    if (matching != null) {
        val session = matching
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(colors.resolved)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Signed in" + (session.identity?.let { " as $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (session.canRefresh) "Tokens are refreshed automatically"
                    else "No refresh token: sign in again when the token expires" +
                        (session.accessTokenExpiresAt?.let { " (${TimeFormat.relative(it, now)})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.canRefresh) MaterialTheme.colorScheme.onSurfaceVariant else colors.warning,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ready = settings.oidcIssuer.isNotBlank() && settings.oidcClientId.isNotBlank()
        FilledTonalButton(onClick = { viewModel.startSignIn(launcher::launch) }, enabled = ready && !signingIn) {
            if (signingIn) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (sessionMatches) "Sign in again" else "Sign in")
        }
        if (session != null) {
            OutlinedButton(onClick = viewModel::signOut) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun ChecksCard(settings: AppSettings, viewModel: ConnectionViewModel) {
    SectionCard("Checks", icon = Icons.Outlined.Schedule) {
        ChoiceRow(
            title = "Check every",
            subtitle = "Shorter intervals notify sooner but use more battery and data",
            options = AppSettings.POLL_INTERVAL_OPTIONS,
            selected = settings.pollIntervalMinutes,
            label = { if (it == 60) "1 hour" else "$it min" },
            onSelect = { v -> viewModel.update { it.copy(pollIntervalMinutes = v) } },
        )
        ChoiceRow(
            title = "Warn when unreachable for",
            subtitle = if (settings.effectiveStaleAfterMinutes != settings.staleAfterMinutes) {
                "Effectively ${settings.effectiveStaleAfterMinutes} min: at least two missed checks"
            } else {
                "Also applies to a missing heartbeat"
            },
            options = listOf(10, 15, 30, 60, 120),
            selected = settings.staleAfterMinutes,
            label = { if (it >= 60) "${it / 60} h" else "$it min" },
            onSelect = { v -> viewModel.update { it.copy(staleAfterMinutes = v) } },
        )
        SettingTextField(
            label = "Only receivers (optional)",
            value = settings.receiverFilter,
            placeholder = ConfigSnippets.DEFAULT_RECEIVER,
            supporting = "Comma separated. When set, only alerts routed to these receivers are shown; see the setup below",
            onChange = { v -> viewModel.update { it.copy(receiverFilter = v) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupCard(settings: AppSettings) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = buildList {
        add("Route")
        add("Kubernetes")
        if (settings.authMode == AuthMode.OIDC) add("oauth2-proxy")
    }
    val selected = tab.coerceAtMost(tabs.lastIndex)
    val snippet = when (tabs[selected]) {
        "Route" -> ConfigSnippets.alertmanagerRoute(settings)
        "Kubernetes" -> ConfigSnippets.alertmanagerConfigCrd(settings)
        else -> ConfigSnippets.oauth2ProxyArgs(settings)
    }
    SectionCard(
        "Server-side setup",
        icon = Icons.Outlined.Code,
        action = {
            Row {
                CopyButton(snippet, "configuration")
                IconButton(onClick = { shareText(context, "Alertmanager Receiver setup", snippet) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
            }
        },
    ) {
        PrimaryScrollableTabRow(selectedTabIndex = selected, containerColor = Color.Transparent, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selected == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        CodeBlock(snippet)
        Text(
            when (tabs[selected]) {
                "Route" -> "Optional. Without a receiver filter the app shows every alert. With this route in alertmanager.yml, " +
                    "set \"Only receivers\" to the receiver's name to choose what reaches the phone."
                "Kubernetes" -> "Optional, for prometheus-operator. The operator prefixes the receiver name with namespace and " +
                    "resource name, so filter on \"monitoring/<name>/<name>\". It also only matches alerts from that " +
                    "namespace unless alertmanagerConfigMatcherStrategy is None."
                else -> "Lets oauth2-proxy accept the app's tokens as bearer tokens. Select \"ID token\" above unless your " +
                    "provider puts the client ID into the access token's audience."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentChecksCard(checks: List<CheckEntity>, now: Long) {
    val colors = LocalStatusColors.current
    SectionCard("Recent checks", icon = Icons.Outlined.History) {
        if (checks.isEmpty()) {
            Text("No checks yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        checks.take(15).forEach { check ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    if (check.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (check.success) colors.resolved else colors.critical,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (check.success) {
                            "${check.firingCount} firing · ${check.alertCount} in Alertmanager"
                        } else {
                            check.message.orEmpty()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${check.durationMillis} ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    TimeFormat.relative(check.startedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
