package de.marcusleg.alertmanagerreceiver.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.marcusleg.alertmanagerreceiver.container
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import kotlinx.coroutines.launch

/**
 * Dead man's switch: Alertmanager regularly sends an always-firing alert (e.g. kube-prometheus-stack's
 * `Watchdog`). If it stops arriving, something between Prometheus and this phone is broken.
 */
class HeartbeatMonitor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val notifier: AlertNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Starts (or restarts) monitoring, measuring the timeout from now. */
    suspend fun arm() {
        val settings = settingsRepository.current()
        if (!settings.heartbeatEnabled) return disarm()
        val now = clock()
        settingsRepository.armHeartbeat(now)
        schedule(now + settings.heartbeatTimeoutMinutes * MINUTE)
    }

    fun disarm() {
        alarmManager.cancel(pendingIntent())
    }

    suspend fun onHeartbeat() {
        val settings = settingsRepository.current()
        val now = clock()
        val previous = settingsRepository.recordHeartbeat(now)
        if (previous.missed) notifier.showHeartbeatRestored(settings.heartbeatAlertName)
        schedule(now + settings.heartbeatTimeoutMinutes * MINUTE)
    }

    suspend fun check() {
        val settings = settingsRepository.current()
        if (!settings.heartbeatEnabled || !settings.serverEnabled) return
        val state = settingsRepository.currentHeartbeat()
        val reference = maxOf(state.lastSeenAt ?: 0L, state.armedAt ?: 0L)
        val deadline = reference + settings.heartbeatTimeoutMinutes * MINUTE
        if (clock() >= deadline) {
            if (!state.missed) {
                settingsRepository.setHeartbeatMissed(true)
                notifier.showHeartbeatMissing(settings.heartbeatAlertName, state.lastSeenAt, settings.heartbeatTimeoutMinutes)
            }
        } else {
            schedule(deadline)
        }
    }

    private fun schedule(at: Long) {
        // Inexact but allowed while idle; good enough for a dead man's switch and needs no special permission.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                container.heartbeatMonitor.check()
            } finally {
                pending.finish()
            }
        }
    }
}
