package de.marcusleg.alertfetcher.ui.connection

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.marcusleg.alertfetcher.AppContainer
import de.marcusleg.alertfetcher.auth.OidcSession
import de.marcusleg.alertfetcher.data.AppSettings
import de.marcusleg.alertfetcher.data.CheckEntity
import de.marcusleg.alertfetcher.data.MonitorState
import de.marcusleg.alertfetcher.monitor.Poller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TestResult {
    data class Success(val version: String, val cluster: String, val peers: Int, val alerts: Int) : TestResult
    data class Failure(val message: String) : TestResult
}

data class ConnectionUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val monitor: MonitorState = MonitorState(),
    val checks: List<CheckEntity> = emptyList(),
    val session: OidcSession? = null,
    val polling: Boolean = false,
    val busy: Busy = Busy(),
)

data class Busy(val testing: Boolean = false, val signingIn: Boolean = false, val testResult: TestResult? = null)

class ConnectionViewModel(private val container: AppContainer) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val busy = MutableStateFlow(Busy())

    val redirectUri: String = container.oidc.redirectUri.toString()

    val state: StateFlow<ConnectionUiState> = combine(
        combine(container.settingsRepository.settingsFlow, container.settingsRepository.monitorStateFlow, ::Pair),
        container.alertRepository.observeChecks(),
        container.oidc.session,
        container.poller.polling,
        busy,
    ) { (settings, monitor), checks, session, polling, busy ->
        ConnectionUiState(false, settings, monitor, checks, session, polling, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { container.monitorController.setEnabled(enabled) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
        busy.value = busy.value.copy(testResult = null)
    }

    fun checkNow() {
        viewModelScope.launch {
            if (container.settingsRepository.current().monitoringEnabled) container.monitorController.checkNow()
            else container.poller.poll()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            busy.value = busy.value.copy(testing = true, testResult = null)
            val settings = container.settingsRepository.current()
            val result = try {
                val status = container.api.status(settings)
                val alerts = container.api.alerts(settings)
                TestResult.Success(
                    version = status.versionInfo.version.ifBlank { "unknown version" },
                    cluster = status.cluster.status.ifBlank { "unknown" },
                    peers = status.cluster.peers.size,
                    alerts = alerts.size,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestResult.Failure(Poller.describe(e))
            }
            busy.value = busy.value.copy(testing = false, testResult = result)
        }
    }

    /** Discovers the identity provider and hands the browser intent to the UI. */
    fun startSignIn(launch: (Intent) -> Unit) {
        viewModelScope.launch {
            busy.value = busy.value.copy(signingIn = true)
            try {
                launch(container.oidc.authorizationIntent(container.settingsRepository.current()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = busy.value.copy(signingIn = false)
                _messages.send(Poller.describe(e))
            }
        }
    }

    fun completeSignIn(data: Intent?) {
        viewModelScope.launch {
            try {
                container.oidc.completeAuthorization(data, container.settingsRepository.current())
                _messages.send("Signed in")
                container.monitorController.checkNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(Poller.describe(e))
            } finally {
                busy.value = busy.value.copy(signingIn = false)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { container.oidc.signOut() }
    }
}
