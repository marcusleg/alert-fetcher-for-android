package de.marcusleg.alertmanagerreceiver.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository

/** Starts and stops the foreground service and persists the user's choice. */
class ReceiverController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.update { it.copy(serverEnabled = enabled) }
        if (enabled) start() else stop()
    }

    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
    }

    fun stop() {
        context.stopService(Intent(context, ReceiverService::class.java))
    }

    /** Re-establishes the service after process death if the user left it enabled. */
    suspend fun ensureRunningIfEnabled() {
        if (settingsRepository.current().serverEnabled) start()
    }
}
