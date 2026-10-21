package de.marcusleg.alertmanagerreceiver.ui.settings

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import de.marcusleg.alertmanagerreceiver.ui.components.SettingTextField

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
        SettingTextField(
            label = "Ignored alert names",
            value = s.ignoredAlertNames,
            supporting = "Comma separated; these are hidden entirely",
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
            "Warn when an always-firing alert such as kube-prometheus-stack's Watchdog disappears while Alertmanager " +
                "is still reachable: then Prometheus or its alerting rules are broken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Monitor heartbeat", s.heartbeatEnabled, { v -> update { it.copy(heartbeatEnabled = v) } })
        if (s.heartbeatEnabled) {
            SettingTextField(
                label = "Heartbeat alert name",
                value = s.heartbeatAlertName,
                supporting = "Warns after ${s.effectiveStaleAfterMinutes} min without it (see Connection › Checks)",
                onChange = { v -> update { it.copy(heartbeatAlertName = v.trim()) } },
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
                    if (ignoringOptimizations) "Unrestricted — monitoring keeps running in the background"
                    else "Optimized — Android may delay or stop checks while the screen is off",
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val exact = remember(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Outlined.Alarm, null, tint = if (exact) colors.resolved else colors.warning)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Exact check times", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exact) "Allowed — checks run on time while the phone sleeps"
                        else "Not allowed — checks may run a few minutes late while the phone sleeps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!exact) {
                FilledTonalButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                    }
                }) { Text("Allow alarms & reminders") }
            }
        }
        SwitchRow("Start on boot", s.startOnBoot, { v -> update { it.copy(startOnBoot = v) } },
            subtitle = "Resume monitoring after the phone reboots")
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
            text = { Text("All stored alerts and the check history are deleted from this device. Active alerts reappear with the next check.") },
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
            "Watches your own Prometheus Alertmanager and notifies you about alerts, without any third-party push service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri())) } }) {
            Text("Source code on GitHub")
        }
    }
}
