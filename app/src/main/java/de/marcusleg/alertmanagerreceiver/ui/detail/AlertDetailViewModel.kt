package de.marcusleg.alertmanagerreceiver.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertmanagerreceiver.AppContainer
import de.marcusleg.alertmanagerreceiver.data.AlertEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlertDetailUiState(
    val loading: Boolean = true,
    val alert: AlertEntity? = null,
    val alertmanagerUrl: String? = null,
    val silencing: Boolean = false,
)

class AlertDetailViewModel(private val container: AppContainer, private val fingerprint: String) : ViewModel() {
    private val silencing = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val state: StateFlow<AlertDetailUiState> = combine(
        container.alertRepository.observeAlert(fingerprint),
        container.settingsRepository.settingsFlow,
        silencing,
    ) { alert, settings, silencing ->
        AlertDetailUiState(
            loading = false,
            alert = alert,
            alertmanagerUrl = settings.alertmanagerUrl.ifBlank { alert?.externalUrl.orEmpty() }.ifBlank { null },
            silencing = silencing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertDetailUiState())

    fun setAcknowledged(acknowledged: Boolean) {
        viewModelScope.launch {
            container.alertRepository.setAcknowledged(fingerprint, acknowledged)
            if (acknowledged) container.notifier.cancel(fingerprint)
        }
    }

    fun silence(durationMillis: Long) {
        viewModelScope.launch {
            silencing.value = true
            val result = container.silenceManager.silence(fingerprint, durationMillis)
            silencing.value = false
            _messages.send(
                result.fold(
                    onSuccess = { "Silence created" },
                    onFailure = { "Silence failed: ${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            container.notifier.cancel(fingerprint)
            container.alertRepository.delete(fingerprint)
            onDeleted()
        }
    }
}
