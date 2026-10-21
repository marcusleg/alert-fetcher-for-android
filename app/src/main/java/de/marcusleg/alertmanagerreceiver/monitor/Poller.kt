package de.marcusleg.alertmanagerreceiver.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.marcusleg.alertmanagerreceiver.alertmanager.AlertmanagerApi
import de.marcusleg.alertmanagerreceiver.alertmanager.AuthenticationException
import de.marcusleg.alertmanagerreceiver.data.AlertRepository
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Fetches the alerts from Alertmanager once and turns the differences into notifications. */
class Poller(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val api: AlertmanagerApi,
    private val alertRepository: AlertRepository,
    private val notifier: AlertNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Notifications don't survive a reboot or app update; the first check of a process restores them. */
    private var restoreNotifications = true
    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling.asStateFlow()

    /** Returns false if nothing could be checked because the connection isn't configured. */
    suspend fun poll(): Boolean = mutex.withLock {
        val settings = settingsRepository.current()
        if (!settings.isConfigured) return false
        _polling.value = true
        val startedAt = clock()
        try {
            val alerts = api.alerts(settings)
            val result = alertRepository.sync(alerts, settings, startedAt)
            notifier.handle(result.updates, settings, restoreMissing = restoreNotifications)
            restoreNotifications = false
            var outcome: HealthOutcome? = null
            settingsRepository.updateMonitorState { state ->
                Health.onSuccess(state, settings, clock(), result.heartbeatSeen).also { outcome = it }.state
            }
            outcome?.let { notifier.handleHealth(it.events, it.state, settings) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = describe(e)
            alertRepository.recordFailure(startedAt, message)
            val online = isOnline()
            var outcome: HealthOutcome? = null
            settingsRepository.updateMonitorState { state ->
                Health.onFailure(state, settings, clock(), message, e is AuthenticationException, online).also { outcome = it }.state
            }
            outcome?.let { notifier.handleHealth(it.events, it.state, settings) }
        } finally {
            _polling.value = false
        }
        true
    }

    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        /** Turns low-level exceptions into something a user can act on. */
        fun describe(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.toList()
            return when {
                e is AuthenticationException -> e.message ?: "Authentication failed"
                root.any { it is CertPathValidatorException } ->
                    "Certificate not trusted. If you use a private CA, install it on the phone (Settings › Security › Encryption & credentials)."
                root.any { it is SSLPeerUnverifiedException } || root.any { it is CertificateException } ->
                    "TLS certificate problem: ${(root.last().message ?: "invalid certificate").take(160)}"
                e is SSLHandshakeException || e is SSLException -> "TLS handshake failed: ${e.message?.take(160)}"
                e is UnknownHostException -> "Unknown host ${e.message.orEmpty()}".trim()
                e is SocketTimeoutException -> "Timed out talking to Alertmanager"
                e is ConnectException -> "Connection refused or unreachable"
                e is IllegalArgumentException -> e.message ?: "Invalid settings"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}
