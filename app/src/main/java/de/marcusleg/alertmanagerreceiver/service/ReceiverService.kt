package de.marcusleg.alertmanagerreceiver.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.marcusleg.alertmanagerreceiver.R
import de.marcusleg.alertmanagerreceiver.container
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import de.marcusleg.alertmanagerreceiver.server.ServerState
import de.marcusleg.alertmanagerreceiver.util.NetworkAddresses
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the webhook HTTP server alive. */
class ReceiverService : LifecycleService() {
    private var running = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = container.notifier.serviceNotification(
            getString(R.string.service_title_starting),
            getString(R.string.service_text_starting),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlertNotifier.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AlertNotifier.ID_SERVICE, notification)
        }
        if (!running) {
            running = true
            run()
        }
        return START_STICKY
    }

    private fun run() {
        val c = container
        acquireWifiLock()

        lifecycleScope.launch {
            c.settingsRepository.settingsFlow.map { it.port }.distinctUntilChanged().collectLatest { port ->
                c.webhookServer.start(port, c.alertProcessor)
            }
        }

        lifecycleScope.launch {
            combine(c.webhookServer.state, c.alertRepository.observeFiringCount()) { state, firing -> state to firing }
                .collect { (state, firing) -> updateNotification(state, firing) }
        }

        lifecycleScope.launch {
            c.settingsRepository.settingsFlow.map { it.keepCpuAwake }.distinctUntilChanged().collect { keepAwake ->
                if (keepAwake) acquireWakeLock() else releaseWakeLock()
            }
        }

        lifecycleScope.launch {
            c.settingsRepository.settingsFlow
                .map { Triple(it.heartbeatEnabled, it.heartbeatAlertName, it.heartbeatTimeoutMinutes) }
                .distinctUntilChanged()
                .collect { c.heartbeatMonitor.arm() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: ServerState, firing: Int) {
        val firingText = resources.getQuantityString(R.plurals.service_firing, firing, firing)
        val (title, text) = when (state) {
            is ServerState.Running -> {
                val address = NetworkAddresses.list().firstOrNull()?.let { NetworkAddresses.hostPort(it, state.port) }
                    ?: getString(R.string.service_port, state.port)
                getString(R.string.service_title_running, address) to firingText
            }
            is ServerState.Failed -> getString(R.string.service_title_failed) to state.message
            is ServerState.Starting -> getString(R.string.service_title_starting) to getString(R.string.service_text_starting)
            ServerState.Stopped -> getString(R.string.service_title_stopped) to firingText
        }
        val notification = container.notifier.serviceNotification(title, text)
        if (container.notifier.canPost()) {
            androidx.core.app.NotificationManagerCompat.from(this).notify(AlertNotifier.ID_SERVICE, notification)
        }
    }

    override fun onDestroy() {
        running = false
        val c = container
        c.heartbeatMonitor.disarm()
        c.scope.launch { c.webhookServer.stop() }
        releaseWakeLock()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        // Keeps the Wi-Fi radio from dozing off so inbound connections from Alertmanager get through.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AlertmanagerReceiver:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // Deliberately held for as long as the user enables "Keep CPU awake" and the service runs.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertmanagerReceiver:server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
