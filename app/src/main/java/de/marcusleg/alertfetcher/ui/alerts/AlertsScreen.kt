package de.marcusleg.alertfetcher.ui.alerts

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marcusleg.alertfetcher.data.AlertEntity
import de.marcusleg.alertfetcher.model.AlertStatus
import de.marcusleg.alertfetcher.model.Severity
import de.marcusleg.alertfetcher.ui.components.LabelChip
import de.marcusleg.alertfetcher.ui.components.Pill
import de.marcusleg.alertfetcher.ui.components.alertColor
import de.marcusleg.alertfetcher.ui.theme.LocalStatusColors
import de.marcusleg.alertfetcher.util.TimeFormat
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    onOpenAlert: (String) -> Unit,
    onOpenConnection: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberTicker()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Acknowledge all firing") },
                            leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                            onClick = { viewModel.acknowledgeAllFiring(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear resolved") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                            onClick = { viewModel.clearResolved(); menuOpen = false },
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!state.loading) {
                    statusBanner(state, viewModel::startMonitoring, onOpenConnection)?.let { banner -> item(key = "banner") { banner() } }
                }
                item(key = "stats") { SeverityStats(state) }
                item(key = "filter") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        AlertFilter.entries.forEachIndexed { index, filter ->
                            val count = state.counts[filter.status] ?: 0
                            SegmentedButton(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                shape = SegmentedButtonDefaults.itemShape(index, AlertFilter.entries.size),
                            ) { Text("${filter.label} ($count)") }
                        }
                    }
                }
                if (state.alerts.isEmpty() && !state.loading) {
                    item(key = "empty") { EmptyState(state.filter) }
                }
                items(state.alerts, key = { it.fingerprint }) { alert ->
                    AlertCard(alert, now, onClick = { onOpenAlert(alert.fingerprint) }, modifier = Modifier.animateItem())
                }
                if (!state.loading && state.settings.isConfigured) {
                    item(key = "checked") { LastChecked(state, now) }
                }
            }
        }
    }
}

@Composable
private fun LastChecked(state: AlertsUiState, now: Long) {
    val last = state.monitor.lastSuccessAt
    Text(
        buildString {
            append(if (last != null) "Checked ${TimeFormat.relative(last, now)}" else "Not checked yet")
            if (state.settings.monitoringEnabled) append(" · every ${state.settings.pollIntervalMinutes} min")
            append(" · pull down to refresh")
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun statusBanner(
    state: AlertsUiState,
    onStart: () -> Unit,
    onOpenConnection: () -> Unit,
): (@Composable () -> Unit)? {
    val monitor = state.monitor
    return when {
        !state.settings.isConfigured -> {
            { Banner(Icons.Outlined.Hub, "Not connected", "Connect to your Alertmanager to see its alerts.", "Set up", onOpenConnection) }
        }
        !state.settings.monitoringEnabled -> {
            { Banner(Icons.Outlined.SyncDisabled, "Monitoring is off", "You won't be notified about new alerts.", "Start", onStart) }
        }
        monitor.authProblem -> {
            { Banner(Icons.Outlined.Key, "Sign-in required", monitor.lastError.orEmpty(), "Fix", onOpenConnection) }
        }
        monitor.lastCheckFailed -> {
            { Banner(Icons.Outlined.CloudOff, "Can't reach Alertmanager", monitor.lastError.orEmpty(), "Details", onOpenConnection) }
        }
        else -> null
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SeverityStats(state: AlertsUiState) {
    val colors = LocalStatusColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Severity.entries.forEach { severity ->
            StatTile(
                label = severity.label,
                value = state.firingBySeverity[severity] ?: 0,
                color = colors.forSeverity(severity),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    val active = value > 0
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertCard(alert: AlertEntity, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = alertColor(alert)
    val resolved = alert.status == AlertStatus.RESOLVED
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).clickable(onClick = onClick)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        alert.alertName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (resolved) "resolved ${TimeFormat.relative(alert.endsAt ?: alert.statusChangedAt, now)}"
                        else TimeFormat.duration(now - (alert.startsAt ?: alert.firstSeenAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                alert.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (resolved) {
                        Pill("Resolved", color, icon = Icons.Outlined.CheckCircle)
                    } else {
                        Pill(alert.severity.label, color)
                    }
                    if (alert.status == AlertStatus.SUPPRESSED) {
                        Pill(
                            if (alert.silencedBy.isEmpty() && alert.inhibitedBy.isNotEmpty()) "Inhibited" else "Silenced",
                            MaterialTheme.colorScheme.secondary,
                            icon = Icons.Outlined.NotificationsOff,
                        )
                    } else if (alert.silencedUntil != null && alert.silencedUntil > now && !resolved) {
                        Pill("Silenced", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.NotificationsOff)
                    } else if (alert.acknowledged && !resolved) {
                        Pill("Acknowledged", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.TaskAlt)
                    }
                    AlertEntity.CONTEXT_LABELS.mapNotNull { key -> alert.labels[key]?.let { key to it } }.take(2).forEach { (k, v) ->
                        LabelChip(k, v)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filter: AlertFilter) {
    val colors = LocalStatusColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = colors.resolved,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when (filter) {
                AlertFilter.FIRING -> "All quiet"
                AlertFilter.SILENCED -> "Nothing silenced"
                AlertFilter.RESOLVED -> "No resolved alerts"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (filter) {
                AlertFilter.FIRING -> "Nothing is firing right now."
                AlertFilter.SILENCED -> "Alerts silenced or inhibited in Alertmanager show up here."
                AlertFilter.RESOLVED -> "Alerts that stopped firing show up here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Current time, refreshed every 30 s so relative timestamps stay fresh. */
@Composable
fun rememberTicker(periodMillis: Long = 30_000): androidx.compose.runtime.State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(periodMillis)
        }
    }
    return state
}
