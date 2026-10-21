package de.marcusleg.alertmanagerreceiver.ui.receiver

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import de.marcusleg.alertmanagerreceiver.data.DeliveryEntity
import de.marcusleg.alertmanagerreceiver.server.ServerState
import de.marcusleg.alertmanagerreceiver.server.WEBHOOK_PATH
import de.marcusleg.alertmanagerreceiver.ui.alerts.rememberTicker
import de.marcusleg.alertmanagerreceiver.ui.components.CodeBlock
import de.marcusleg.alertmanagerreceiver.ui.components.CopyButton
import de.marcusleg.alertmanagerreceiver.ui.components.SectionCard
import de.marcusleg.alertmanagerreceiver.ui.components.StatusDot
import de.marcusleg.alertmanagerreceiver.ui.components.shareText
import de.marcusleg.alertmanagerreceiver.ui.theme.LocalStatusColors
import de.marcusleg.alertmanagerreceiver.ui.theme.MonoFamily
import de.marcusleg.alertmanagerreceiver.util.ConfigSnippets
import de.marcusleg.alertmanagerreceiver.util.NetworkAddresses
import de.marcusleg.alertmanagerreceiver.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(viewModel: ReceiverViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val now by rememberTicker(10_000)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Receiver") }) },
    ) { padding ->
        if (state.loading) return@Scaffold
        val settings = state.settings
        val primaryUrl = state.addresses.firstOrNull()?.let { NetworkAddresses.webhookUrl(it, settings.port) }
            ?: "http://<phone-ip>:${settings.port}$WEBHOOK_PATH"

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(settings.serverEnabled, state.serverState, now, viewModel::setEnabled)
            UrlsCard(state.addresses, settings.port)
            SecurityCard(settings, viewModel)
            ConfigCard(primaryUrl, settings)
            TestCard(enabled = state.serverState is ServerState.Running, onSend = viewModel::sendTest)
            if (settings.heartbeatEnabled) HeartbeatCard(settings, state.heartbeat, now)
            DeliveriesCard(state.deliveries, now)
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, serverState: ServerState, now: Long, onToggle: (Boolean) -> Unit) {
    val colors = LocalStatusColors.current
    val (title, subtitle, color) = when {
        !enabled -> Triple("Receiver stopped", "Turn on to accept webhooks from Alertmanager", MaterialTheme.colorScheme.outline)
        serverState is ServerState.Running -> Triple(
            "Receiving alerts",
            "Listening on port ${serverState.port} · up ${TimeFormat.duration(now - serverState.since)}",
            colors.resolved,
        )
        serverState is ServerState.Failed -> Triple("Failed to start", serverState.message, colors.critical)
        else -> Triple("Starting…", "Opening the HTTP server", colors.warning)
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.13f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color.copy(alpha = 0.2f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            !enabled -> Icons.Outlined.SensorsOff
                            serverState is ServerState.Failed -> Icons.Outlined.ErrorOutline
                            else -> Icons.Outlined.Sensors
                        },
                        contentDescription = null,
                        tint = color,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun UrlsCard(addresses: List<NetworkAddresses.Address>, port: Int) {
    var showIpv6 by rememberSaveable { mutableStateOf(false) }
    val (ipv6, ipv4) = addresses.partition { it.isIpv6 }
    SectionCard("Webhook URL", icon = Icons.Outlined.Link) {
        if (addresses.isEmpty()) {
            Text("No network connection. Connect to Wi-Fi or a VPN.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (if (showIpv6 || ipv4.isEmpty()) ipv4 + ipv6 else ipv4).forEach { address ->
            val url = NetworkAddresses.webhookUrl(address, port)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${address.kind.label} · ${address.interfaceName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(url, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CopyButton(url, "webhook URL")
            }
        }
        if (ipv6.isNotEmpty() && ipv4.isNotEmpty()) {
            TextButton(onClick = { showIpv6 = !showIpv6 }) {
                Text(if (showIpv6) "Hide IPv6 addresses" else "Show IPv6 addresses (${ipv6.size})")
            }
        }
        Text(
            "Give this phone a fixed IP (DHCP reservation) or use a VPN such as WireGuard or Tailscale so the URL stays stable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SecurityCard(settings: AppSettings, viewModel: ReceiverViewModel) {
    SectionCard("Authentication", icon = Icons.Outlined.Key) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(AuthMode.BEARER to "Bearer token", AuthMode.BASIC to "Basic auth", AuthMode.NONE to "None")
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.authMode == mode,
                    onClick = { viewModel.setAuthMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        when (settings.authMode) {
            AuthMode.BEARER -> SecretField("Token", settings.bearerToken, onRegenerate = viewModel::regenerateToken)
            AuthMode.BASIC -> {
                var username by rememberSaveable(settings.basicUsername) { mutableStateOf(settings.basicUsername) }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; viewModel.setBasicUsername(it) },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField("Password", settings.basicPassword, onRegenerate = viewModel::regeneratePassword)
            }
            AuthMode.NONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = LocalStatusColors.current.warning)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Anyone on your network can send fake alerts. Only use this on trusted networks.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        PortField(settings.port, viewModel::setPort)
    }
}

@Composable
private fun SecretField(label: String, value: String, onRegenerate: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Hide" else "Show")
                }
                CopyButton(value, label.lowercase())
                IconButton(onClick = onRegenerate) { Icon(Icons.Outlined.Refresh, contentDescription = "Regenerate") }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PortField(port: Int, onApply: (Int) -> Unit) {
    var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1024..65535
    val apply = { if (valid && parsed != port) onApply(parsed) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(5) },
        label = { Text("Port") },
        singleLine = true,
        isError = !valid,
        supportingText = { Text(if (valid) "Changing the port restarts the receiver" else "Use a port between 1024 and 65535") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { apply() }),
        trailingIcon = {
            if (valid && parsed != port) IconButton(onClick = apply) { Icon(Icons.Outlined.Done, contentDescription = "Apply port") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(url: String, settings: AppSettings) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Alertmanager", "Kubernetes", "curl")
    val snippet = when (tab) {
        0 -> ConfigSnippets.alertmanagerYaml(url, settings)
        1 -> ConfigSnippets.alertmanagerConfigCrd(url, settings)
        else -> ConfigSnippets.curlTest(url, settings)
    }
    SectionCard(
        "Alertmanager configuration",
        icon = Icons.Outlined.Code,
        action = {
            Row {
                CopyButton(snippet, "configuration")
                IconButton(onClick = { shareText(context, "Alertmanager receiver configuration", snippet) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
            }
        },
    ) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        CodeBlock(snippet)
        Text(
            when (tab) {
                0 -> "Merge into your Alertmanager configuration, or add the receiver to an existing route."
                1 -> "For prometheus-operator / kube-prometheus-stack. Make sure your Alertmanager selects this AlertmanagerConfig."
                else -> "Quick check from any machine on your network."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TestCard(enabled: Boolean, onSend: (String) -> Unit) {
    SectionCard("Test", icon = Icons.AutoMirrored.Outlined.Send) {
        Text(
            "Sends a sample notification through the HTTP endpoint, including authentication.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSend("firing") }, enabled = enabled) { Text("Send test alert") }
            OutlinedButton(onClick = { onSend("resolved") }, enabled = enabled) { Text("Resolve") }
        }
    }
}

@Composable
private fun HeartbeatCard(settings: AppSettings, heartbeat: de.marcusleg.alertmanagerreceiver.data.HeartbeatState, now: Long) {
    val colors = LocalStatusColors.current
    SectionCard("Heartbeat", icon = Icons.Outlined.MonitorHeart) {
        val (color, text) = when {
            heartbeat.missed -> colors.critical to "Missing — no ${settings.heartbeatAlertName} for over ${settings.heartbeatTimeoutMinutes} min"
            heartbeat.lastSeenAt != null -> colors.resolved to "Last ${settings.heartbeatAlertName} ${TimeFormat.relative(heartbeat.lastSeenAt, now)}"
            else -> colors.warning to "Waiting for the first ${settings.heartbeatAlertName} alert"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DeliveriesCard(deliveries: List<DeliveryEntity>, now: Long) {
    val colors = LocalStatusColors.current
    SectionCard("Recent deliveries", icon = Icons.Outlined.History) {
        if (deliveries.isEmpty()) {
            Text("Nothing received yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        deliveries.take(15).forEach { delivery ->
            val ok = delivery.httpStatus in 200..299
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) colors.resolved else colors.critical,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ok) {
                            buildList {
                                if (delivery.message == "heartbeat") add("heartbeat")
                                if (delivery.firing > 0) add("${delivery.firing} firing")
                                if (delivery.resolved > 0) add("${delivery.resolved} resolved")
                            }.ifEmpty { listOf("empty") }.joinToString(" · ")
                        } else {
                            "HTTP ${delivery.httpStatus} · ${delivery.message.orEmpty()}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(delivery.remoteAddress, delivery.receiver).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    TimeFormat.relative(delivery.receivedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
