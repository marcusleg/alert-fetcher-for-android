package de.marcusleg.alertmanagerreceiver.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertmanagerreceiver.AppContainer
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import de.marcusleg.alertmanagerreceiver.data.DeliveryEntity
import de.marcusleg.alertmanagerreceiver.data.HeartbeatState
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.server.ServerState
import de.marcusleg.alertmanagerreceiver.util.NetworkAddresses
import de.marcusleg.alertmanagerreceiver.util.TestAlertSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiverUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val serverState: ServerState = ServerState.Stopped,
    val addresses: List<NetworkAddresses.Address> = emptyList(),
    val deliveries: List<DeliveryEntity> = emptyList(),
    val heartbeat: HeartbeatState = HeartbeatState(),
)

class ReceiverViewModel(private val container: AppContainer) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val addresses = flow {
        while (true) {
            emit(NetworkAddresses.list())
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<ReceiverUiState> = combine(
        container.settingsRepository.settingsFlow,
        container.webhookServer.state,
        addresses,
        container.alertRepository.observeDeliveries(),
        container.settingsRepository.heartbeat,
    ) { settings, serverState, addresses, deliveries, heartbeat ->
        ReceiverUiState(false, settings, serverState, addresses, deliveries, heartbeat)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiverUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { container.receiverController.setEnabled(enabled) }
    }

    fun setAuthMode(mode: AuthMode) = update { it.copy(authMode = mode) }

    fun regenerateToken() = update { it.copy(bearerToken = SettingsRepository.generateSecret()) }

    fun regeneratePassword() = update { it.copy(basicPassword = SettingsRepository.generateSecret()) }

    fun setBasicUsername(username: String) = update { it.copy(basicUsername = username.trim()) }

    fun setPort(port: Int) = update { it.copy(port = port) }

    fun sendTest(status: String) {
        viewModelScope.launch {
            val settings = container.settingsRepository.current()
            val message = runCatching { TestAlertSender.send(settings, status) }.fold(
                onSuccess = { if (status == "firing") "Test alert delivered" else "Test alert resolved" },
                onFailure = { "Test failed: ${it.message ?: it.javaClass.simpleName}" },
            )
            _messages.send(message)
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }
}
