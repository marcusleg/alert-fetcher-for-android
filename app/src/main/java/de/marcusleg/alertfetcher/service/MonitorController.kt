package de.marcusleg.alertfetcher.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import de.marcusleg.alertfetcher.data.SettingsRepository
import de.marcusleg.alertfetcher.monitor.PollScheduler
import de.marcusleg.alertfetcher.monitor.PollTrigger
import kotlinx.coroutines.channels.Channel

/** Starts and stops the foreground service and persists the user's choice. */
class MonitorController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scheduler: PollScheduler,
    private val pollTriggers: Channel<PollTrigger>,
) {
    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.update { it.copy(monitoringEnabled = enabled) }
        if (enabled) {
            val now = System.currentTimeMillis()
            // Grace periods for "can't reach" and "heartbeat missing" start over.
            settingsRepository.updateMonitorState { it.copy(since = now, contactLostNotified = false, heartbeatMissingNotified = false) }
            start()
        } else {
            stop()
        }
    }

    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }

    fun stop() {
        context.stopService(Intent(context, MonitorService::class.java))
        scheduler.cancel()
    }

    /** Checks Alertmanager now if monitoring is on; starts the service if it isn't running. */
    fun checkNow() {
        if (MonitorService.isRunning) pollTriggers.trySend(PollTrigger.NOW) else start()
    }

    /** Re-establishes the service after process death if the user left monitoring on. Returns whether it is on. */
    suspend fun ensureRunningIfEnabled(): Boolean {
        val enabled = settingsRepository.current().monitoringEnabled
        if (enabled && !MonitorService.isRunning) start()
        return enabled
    }
}
