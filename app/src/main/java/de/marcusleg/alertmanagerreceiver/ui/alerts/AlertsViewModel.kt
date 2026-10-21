package de.marcusleg.alertmanagerreceiver.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertmanagerreceiver.AppContainer
import de.marcusleg.alertmanagerreceiver.data.AlertEntity
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.Severity
import de.marcusleg.alertmanagerreceiver.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AlertFilter(val label: String) { FIRING("Firing"), RESOLVED("Resolved"), ALL("All") }

data class AlertsUiState(
    val loading: Boolean = true,
    val alerts: List<AlertEntity> = emptyList(),
    val filter: AlertFilter = AlertFilter.FIRING,
    val firingCount: Int = 0,
    val resolvedCount: Int = 0,
    val firingBySeverity: Map<Severity, Int> = emptyMap(),
    val serverEnabled: Boolean = true,
    val serverState: ServerState = ServerState.Stopped,
)

class AlertsViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(AlertFilter.FIRING)

    val state: StateFlow<AlertsUiState> = combine(
        container.alertRepository.observeAlerts(),
        filter,
        container.settingsRepository.settingsFlow,
        container.webhookServer.state,
    ) { all, filter, settings, serverState ->
        val firing = all.filter { it.status == AlertStatus.FIRING }
        AlertsUiState(
            loading = false,
            alerts = when (filter) {
                AlertFilter.FIRING -> firing
                AlertFilter.RESOLVED -> all.filter { it.status == AlertStatus.RESOLVED }
                AlertFilter.ALL -> all
            },
            filter = filter,
            firingCount = firing.size,
            resolvedCount = all.size - firing.size,
            firingBySeverity = firing.groupingBy { it.severity }.eachCount(),
            serverEnabled = settings.serverEnabled,
            serverState = serverState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun setFilter(value: AlertFilter) {
        filter.value = value
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

    fun startReceiver() {
        viewModelScope.launch { container.receiverController.setEnabled(true) }
    }
}
