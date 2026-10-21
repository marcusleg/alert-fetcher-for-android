package de.marcusleg.alertfetcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.marcusleg.alertfetcher.container
import kotlinx.coroutines.launch

/** Restarts monitoring after a reboot or an app update if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                val settings = container.settingsRepository.current()
                val wanted = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || settings.startOnBoot
                if (settings.monitoringEnabled && wanted) container.monitorController.start()
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not start the monitor service", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
