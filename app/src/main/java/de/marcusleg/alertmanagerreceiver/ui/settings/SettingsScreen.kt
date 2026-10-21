package de.marcusleg.alertmanagerreceiver.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import de.marcusleg.alertmanagerreceiver.BuildConfig
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.model.Severity
import de.marcusleg.alertmanagerreceiver.ui.components.ChoiceRow
import de.marcusleg.alertmanagerreceiver.ui.components.SectionCard
import de.marcusleg.alertmanagerreceiver.ui.components.SwitchRow
import de.marcusleg.alertmanagerreceiver.ui.theme.LocalStatusColors
import de.marcusleg.alertmanagerreceiver.util.ConfigSnippets

const val PROJECT_URL = "https://github.com/marcusleg/alertmanager-receiver-for-android"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationsSection(s, viewModel::update)
            HeartbeatSection(s, viewModel::update)
            ReliabilitySection(s, viewModel::update)
            AlertmanagerSection(s, viewModel::update)
            DataSection(s, viewModel::update, viewModel::clearAll)
            AboutSection()
        }
    }
}

@Composable
private fun NotificationsSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    SectionCard("Notifications", icon = Icons.Outlined.Notifications) {
        ChoiceRow(
            title = "Minimum severity",
            subtitle = "Lower severities are recorded without a notification",
            options = Severity.entries.reversed(),
            selected = s.minNotifySeverity,
            label = { it.label },
            onSelect = { sev -> update { it.copy(minNotifySeverity = sev) } },
        )
        SwitchRow(
            "Notify when resolved",
            s.notifyResolved,
            { v -> update { it.copy(notifyResolved = v) } },
            subtitle = "Replace the alert with a quiet \"resolved\" notification instead of removing it",
        )
        TextSetting(
            label = "Ignored alert names",
            value = s.ignoredAlertNames,
            supporting = "Comma separated; these are dropped entirely",
            onChange = { v -> update { it.copy(ignoredAlertNames = v) } },
        )
        Text(
            "Tip: allow the Critical alerts channel to override Do Not Disturb so pages reach you at night.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }) { Text("Notification channels") }
    }
}

@Composable
private fun HeartbeatSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("Heartbeat (dead man's switch)", icon = Icons.Outlined.MonitorHeart) {
        Text(
            "Alert when an always-firing alert such as kube-prometheus-stack's Watchdog stops arriving — " +
                "that means Prometheus, Alertmanager or the network path to this phone is broken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Monitor heartbeat", s.heartbeatEnabled, { v -> update { it.copy(heartbeatEnabled = v) } })
        if (s.heartbeatEnabled) {
            TextSetting(
                label = "Heartbeat alert name",
                value = s.heartbeatAlertName,
                onChange = { v -> update { it.copy(heartbeatAlertName = v.trim()) } },
            )
            ChoiceRow(
                title = "Alarm after",
                options = listOf(5, 10, 15, 30, 60, 120),
                selected = s.heartbeatTimeoutMinutes,
                label = { "$it min" },
                onSelect = { v -> update { it.copy(heartbeatTimeoutMinutes = v) } },
            )
            Text(
                "Route the heartbeat to this receiver with repeat_interval: ${ConfigSnippets.heartbeatRepeatMinutes(s)}m " +
                    "(the generated configuration on the Receiver tab already does).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun ReliabilitySection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Re-evaluated whenever the screen resumes (e.g. after returning from the system dialog).
    val ignoringOptimizations = remember(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val colors = LocalStatusColors.current
    SectionCard("Reliability", icon = Icons.Outlined.Bolt) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Outlined.BatteryAlert,
                null,
                tint = if (ignoringOptimizations) colors.resolved else colors.warning,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Battery optimization", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (ignoringOptimizations) "Unrestricted — the receiver can run in the background"
                    else "Optimized — Android may stop the receiver while the screen is off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!ignoringOptimizations) {
            FilledTonalButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                    )
                }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Allow background use") }
        }
        SwitchRow("Start on boot", s.startOnBoot, { v -> update { it.copy(startOnBoot = v) } },
            subtitle = "Restart the receiver after the phone reboots")
        SwitchRow("Keep CPU awake", s.keepCpuAwake, { v -> update { it.copy(keepCpuAwake = v) } },
            subtitle = "Holds a wake lock. Only needed on devices that drop connections in deep sleep; uses more battery")
    }
}

@Composable
private fun AlertmanagerSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("Alertmanager API", icon = Icons.Outlined.Hub) {
        Text(
            "Used to create silences. By default the externalURL sent by Alertmanager is used; override it if that URL isn't reachable from this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextSetting(
            label = "Alertmanager URL",
            value = s.alertmanagerUrl,
            placeholder = "http://alertmanager.example.lan:9093",
            keyboardType = KeyboardType.Uri,
            onChange = { v -> update { it.copy(alertmanagerUrl = v.trim()) } },
        )
        TextSetting(
            label = "Authorization header",
            value = s.alertmanagerAuthorization,
            placeholder = "Bearer … or Basic …",
            secret = true,
            onChange = { v -> update { it.copy(alertmanagerAuthorization = v) } },
        )
    }
}

@Composable
private fun DataSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, clearAll: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard("Data", icon = Icons.Outlined.Storage) {
        ChoiceRow(
            title = "Keep resolved alerts",
            options = listOf(1, 7, 30, 90, 0),
            selected = s.retentionDays,
            label = { if (it == 0) "Forever" else if (it == 1) "1 day" else "$it days" },
            onSelect = { v -> update { it.copy(retentionDays = v) } },
        )
        OutlinedButton(onClick = { confirm = true }) { Text("Clear all alerts and history") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear everything?") },
            text = { Text("All stored alerts and the delivery history are deleted from this device.") },
            confirmButton = { TextButton(onClick = { confirm = false; clearAll() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    SectionCard("About", icon = Icons.Outlined.Info) {
        Text("Alertmanager Receiver ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Receives Prometheus Alertmanager webhooks directly on your phone — no cloud push service involved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri())) } }) {
            Text("Source code on GitHub")
        }
    }
}

/** Text field that keeps its own editing state and persists every change. */
@Composable
private fun TextSetting(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    supporting: String? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by rememberSaveable { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
