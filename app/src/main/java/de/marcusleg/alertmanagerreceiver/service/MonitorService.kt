package de.marcusleg.alertmanagerreceiver.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.marcusleg.alertmanagerreceiver.R
import de.marcusleg.alertmanagerreceiver.container
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.MonitorState
import de.marcusleg.alertmanagerreceiver.monitor.PollTrigger
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import de.marcusleg.alertmanagerreceiver.util.TimeFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Foreground service that checks Alertmanager every poll interval. A foreground service keeps the
 * process alive between checks; [de.marcusleg.alertmanagerreceiver.monitor.PollScheduler] alarms wake
 * the device for each check.
 */
class MonitorService : LifecycleService() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = container.notifier.serviceNotification(getString(R.string.service_title_starting), null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlertNotifier.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AlertNotifier.ID_SERVICE, notification)
        }
        if (!isRunning) {
            isRunning = true
            run()
        }
        return START_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun run() {
        val c = container

        lifecycleScope.launch { pollLoop() }

        // Re-check soon after the connection settings change (debounced: text fields save on every keystroke).
        lifecycleScope.launch {
            c.settingsRepository.settingsFlow
                .map { it.copy(monitoringEnabled = true, startOnBoot = true, notifyResolved = true, retentionDays = 0) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1_500)
                .collect { c.pollTriggers.trySend(PollTrigger.NOW) }
        }

        lifecycleScope.launch {
            combine(c.settingsRepository.settingsFlow, c.settingsRepository.monitorStateFlow, c.alertRepository.observeFiringCount()) { s, m, f ->
                Triple(s, m, f)
            }.collect { (settings, state, firing) -> updateNotification(settings, state, firing) }
        }

        registerNetworkCallback()
    }

    private suspend fun pollLoop() {
        val c = container
        var force = true // check right away when the service starts
        var lastLocalAttempt = 0L
        while (true) {
            val settings = c.settingsRepository.current()
            val interval = settings.pollIntervalMillis
            val lastAttempt = maxOf(lastLocalAttempt, c.settingsRepository.currentMonitorState().lastAttemptAt ?: 0L)
            val now = System.currentTimeMillis()
            val due = lastAttempt + interval
            if (settings.isConfigured && (force || now >= due - EARLY_TOLERANCE_MS)) {
                force = false
                lastLocalAttempt = now
                withWakeLock { c.poller.poll() }
                continue
            }
            val trigger = if (settings.isConfigured) {
                c.pollScheduler.scheduleAt(due)
                withTimeoutOrNull(due - now) { c.pollTriggers.receive() }
            } else {
                c.pollScheduler.cancel()
                c.pollTriggers.receive()
            }
            force = trigger == PollTrigger.NOW
        }
    }

    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertmanagerReceiver:poll")
        lock.acquire(POLL_WAKE_LOCK_MS)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Retry as soon as we're back online instead of waiting for the next interval.
                lifecycleScope.launch {
                    if (container.settingsRepository.currentMonitorState().lastCheckFailed) {
                        container.pollTriggers.trySend(PollTrigger.NOW)
                    }
                }
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(settings: AppSettings, state: MonitorState, firing: Int) {
        val firingText = resources.getQuantityString(R.plurals.service_firing, firing, firing)
        val host = runCatching { URI(settings.alertmanagerUrl.trim()).host }.getOrNull() ?: settings.alertmanagerUrl
        val (title, text) = when {
            !settings.isConfigured -> getString(R.string.service_title_not_configured) to null
            state.authProblem -> getString(R.string.service_title_auth) to state.lastError
            state.lastCheckFailed -> getString(R.string.service_title_failing) to state.lastError
            state.lastSuccessAt != null -> getString(R.string.service_title_ok, host) to
                getString(R.string.service_text_ok, firingText, TimeFormat.clock(state.lastSuccessAt))
            else -> getString(R.string.service_title_starting) to null
        }
        val notification = container.notifier.serviceNotification(title, text)
        if (container.notifier.canPost()) NotificationManagerCompat.from(this).notify(AlertNotifier.ID_SERVICE, notification)
    }

    override fun onDestroy() {
        isRunning = false
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val EARLY_TOLERANCE_MS = 5_000L
        private const val POLL_WAKE_LOCK_MS = 2 * 60_000L
    }
}
