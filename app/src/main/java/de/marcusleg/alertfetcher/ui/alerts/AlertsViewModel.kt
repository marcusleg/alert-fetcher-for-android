package de.marcusleg.alertfetcher.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertfetcher.AppContainer
import de.marcusleg.alertfetcher.data.AlertEntity
import de.marcusleg.alertfetcher.data.AppSettings
import de.marcusleg.alertfetcher.data.MonitorState
import de.marcusleg.alertfetcher.model.AlertStatus
import de.marcusleg.alertfetcher.model.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AlertFilter(val label: String, val status: AlertStatus) {
    FIRING("Firing", AlertStatus.FIRING),
    SILENCED("Silenced", AlertStatus.SUPPRESSED),
    RESOLVED("Resolved", AlertStatus.RESOLVED),
}

data class AlertsUiState(
    val loading: Boolean = true,
    val alerts: List<AlertEntity> = emptyList(),
    val filter: AlertFilter = AlertFilter.FIRING,
    val counts: Map<AlertStatus, Int> = emptyMap(),
    val firingBySeverity: Map<Severity, Int> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val monitor: MonitorState = MonitorState(),
    val refreshing: Boolean = false,
)

class AlertsViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(AlertFilter.FIRING)

    val state: StateFlow<AlertsUiState> = combine(
        container.alertRepository.observeAlerts(),
        filter,
        container.settingsRepository.settingsFlow,
        container.settingsRepository.monitorStateFlow,
        container.poller.polling,
    ) { all, filter, settings, monitor, polling ->
        val byStatus = all.groupBy { it.status }
        AlertsUiState(
            loading = false,
            alerts = byStatus[filter.status].orEmpty(),
            filter = filter,
            counts = byStatus.mapValues { it.value.size },
            firingBySeverity = byStatus[AlertStatus.FIRING].orEmpty().groupingBy { it.severity }.eachCount(),
            settings = settings,
            monitor = monitor,
            refreshing = polling,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun setFilter(value: AlertFilter) {
        filter.value = value
    }

    fun refresh() {
        // While monitoring is off a manual refresh still checks once.
        container.monitorController.checkNow()
    }

    fun clearResolved() {
        viewModelScope.launch { container.alertRepository.deleteResolved() }
    }

    fun acknowledgeAllFiring() {
        viewModelScope.launch {
            container.alertRepository.firing().forEach {
                container.alertRepository.setAcknowledged(it.fingerprint, true)
            }
            container.notifier.cancelAllAlerts()
        }
    }

    fun startMonitoring() {
        viewModelScope.launch { container.monitorController.setEnabled(true) }
    }
}
