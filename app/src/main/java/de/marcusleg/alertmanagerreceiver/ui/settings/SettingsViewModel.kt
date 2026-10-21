package de.marcusleg.alertmanagerreceiver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertmanagerreceiver.AppContainer
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settingsRepository.settingsFlow
        .map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val updated = container.settingsRepository.update(transform)
            container.alertRepository.applyRetention(updated.retentionDays)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.notifier.cancelAllAlerts()
            container.alertRepository.deleteAll()
        }
    }
}
