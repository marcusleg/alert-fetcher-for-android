package de.marcusleg.alertfetcher.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marcusleg.alertfetcher.data.AlertEntity
import de.marcusleg.alertfetcher.model.AlertStatus
import de.marcusleg.alertfetcher.ui.alerts.rememberTicker
import de.marcusleg.alertfetcher.ui.components.BrandRule
import de.marcusleg.alertfetcher.ui.components.Pill
import de.marcusleg.alertfetcher.ui.components.SectionCard
import de.marcusleg.alertfetcher.ui.components.alertColor
import de.marcusleg.alertfetcher.ui.components.copyToClipboard
import de.marcusleg.alertfetcher.ui.theme.LocalStatusColors
import de.marcusleg.alertfetcher.ui.theme.MonoFamily
import de.marcusleg.alertfetcher.util.TimeFormat

private val SILENCE_OPTIONS = listOf(
    "1 hour" to 3_600_000L,
    "4 hours" to 4 * 3_600_000L,
    "12 hours" to 12 * 3_600_000L,
    "1 day" to 24 * 3_600_000L,
    "1 week" to 7 * 24 * 3_600_000L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(viewModel: AlertDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var silenceDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Alert details") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        if (state.alert != null) {
                            IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                        }
                    },
                )
                BrandRule()
            }
        },
    ) { padding ->
        val alert = state.alert
        if (alert == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator() else Text("This alert no longer exists.")
            }
            return@Scaffold
        }
        AlertDetailContent(
            alert = alert,
            silencing = state.silencing,
            canSilence = state.canSilence,
            onAcknowledge = viewModel::setAcknowledged,
            onSilence = { silenceDialog = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (silenceDialog) {
        SilenceDialog(
            onDismiss = { silenceDialog = false },
            onConfirm = { duration ->
                silenceDialog = false
                viewModel.silence(duration)
            },
        )
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete alert?") },
            text = { Text("It is removed from this device only. If it is still firing, it comes back with the next check.") },
            confirmButton = { TextButton(onClick = { deleteDialog = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertDetailContent(
    alert: AlertEntity,
    silencing: Boolean,
    canSilence: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onSilence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now by rememberTicker()
    val color = alertColor(alert)
    val statusColors = LocalStatusColors.current
    val firing = alert.status == AlertStatus.FIRING
    val suppressed = alert.status == AlertStatus.SUPPRESSED
    val silencedUntil = alert.silencedUntil?.takeIf { it > now && alert.status != AlertStatus.RESOLVED }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        SectionCard(title = null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (alert.status) {
                    AlertStatus.FIRING -> Pill("FIRING", color, filled = true)
                    AlertStatus.SUPPRESSED -> Pill(
                        if (alert.silencedBy.isEmpty() && alert.inhibitedBy.isNotEmpty()) "INHIBITED" else "SILENCED",
                        MaterialTheme.colorScheme.secondary,
                        filled = true,
                        icon = Icons.Outlined.NotificationsOff,
                    )
                    AlertStatus.RESOLVED -> Pill("RESOLVED", statusColors.resolved, filled = true, icon = Icons.Outlined.CheckCircle)
                }
                Pill(alert.severity.label, statusColors.forSeverity(alert.severity))
                if (silencedUntil != null) {
                    Pill("Silenced until ${TimeFormat.absolute(silencedUntil)}", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.NotificationsOff)
                } else if (alert.acknowledged && firing) {
                    Pill("Acknowledged", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.TaskAlt)
                }
            }
            Text(alert.alertName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            alert.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            alert.description?.takeIf { it != alert.summary }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (firing || suppressed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (alert.acknowledged) {
                        FilledTonalButton(onClick = { onAcknowledge(false) }) { Text("Unacknowledge") }
                    } else {
                        FilledTonalButton(onClick = { onAcknowledge(true) }) {
                            Icon(Icons.Outlined.TaskAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Acknowledge")
                        }
                    }
                    Button(onClick = onSilence, enabled = canSilence && !silencing && !suppressed) {
                        if (silencing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Silence…")
                    }
                }
            }
        }

        // Links
        val links = linksFor(alert)
        if (links.isNotEmpty()) {
            SectionCard("Links", icon = Icons.Outlined.Link) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { link ->
                        AssistChip(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) } },
                            label = { Text(link.title) },
                            leadingIcon = { Icon(link.icon, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }

        // Timing
        SectionCard("Timeline", icon = Icons.Outlined.Schedule) {
            val started = alert.startsAt ?: alert.firstSeenAt
            TimeRow("Started", started, now)
            alert.endsAt?.let { TimeRow("Resolved", it, now) }
            KeyValueRow("Duration", TimeFormat.duration((alert.endsAt ?: now) - started))
            TimeRow(if (alert.status == AlertStatus.RESOLVED) "Last seen" else "Last checked", alert.lastSeenAt, now)
            if (alert.receivers.isNotEmpty()) KeyValueRow(if (alert.receivers.size == 1) "Receiver" else "Receivers", alert.receivers.joinToString(", "))
            if (alert.silencedBy.isNotEmpty()) KeyValueRow("Silenced by", alert.silencedBy.joinToString(", "))
            if (alert.inhibitedBy.isNotEmpty()) KeyValueRow("Inhibited by", alert.inhibitedBy.joinToString(", "))
        }

        SectionCard("Labels", icon = Icons.AutoMirrored.Outlined.Label) {
            alert.labels.toSortedMap().entries.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                KeyValueRow(k, v, mono = true, onClick = { copyToClipboard(context, k, v) })
            }
        }

        val shownAnnotations = alert.annotations.filterKeys { it != "summary" && it != "description" }
        if (shownAnnotations.isNotEmpty()) {
            SectionCard("Annotations", icon = Icons.AutoMirrored.Outlined.Notes) {
                shownAnnotations.toSortedMap().entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    KeyValueRow(k, v, mono = true, onClick = { copyToClipboard(context, k, v) })
                }
            }
        }

        Text(
            "Fingerprint ${alert.fingerprint}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private data class AlertLink(val title: String, val url: String, val icon: ImageVector)

private fun linksFor(alert: AlertEntity): List<AlertLink> = buildList {
    alert.annotations["runbook_url"]?.let { add(AlertLink("Runbook", it, Icons.AutoMirrored.Outlined.MenuBook)) }
    alert.annotations["dashboard_url"]?.let { add(AlertLink("Dashboard", it, Icons.Outlined.Dashboard)) }
    alert.generatorUrl?.let { add(AlertLink("Source", it, Icons.Outlined.QueryStats)) }
    alert.alertmanagerUrl?.let { add(AlertLink("Alertmanager", it, Icons.AutoMirrored.Outlined.OpenInNew)) }
    alert.annotations
        .filterKeys { it != "runbook_url" && it != "dashboard_url" }
        .filterValues { it.startsWith("http://") || it.startsWith("https://") }
        .forEach { (k, v) -> add(AlertLink(k.removeSuffix("_url").replace('_', ' ').replaceFirstChar { it.uppercase() }, v, Icons.Outlined.Link)) }
}.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }

@Composable
private fun TimeRow(label: String, at: Long, now: Long) {
    KeyValueRow(label, "${TimeFormat.absolute(at)}  ·  ${TimeFormat.relative(at, now)}")
}

@Composable
private fun KeyValueRow(key: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall.let { if (mono) it.copy(fontFamily = MonoFamily) else it },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.let { if (mono) it.copy(fontFamily = MonoFamily) else it },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SilenceDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var selected by remember { mutableLongStateOf(SILENCE_OPTIONS.first().second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Silence alert") },
        text = {
            Column {
                Text(
                    "Creates a silence in Alertmanager matching all labels of this alert.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                SILENCE_OPTIONS.forEach { (label, duration) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = selected == duration, role = Role.RadioButton) { selected = duration }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == duration, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Silence") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
