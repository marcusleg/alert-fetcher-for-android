package de.marcusleg.alertmanagerreceiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.marcusleg.alertmanagerreceiver.container
import kotlinx.coroutines.launch

/** Restarts the receiver after a reboot or an app update if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                val settings = container.settingsRepository.current()
                if (settings.serverEnabled && settings.startOnBoot) container.receiverController.start()
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not start receiver service", e)
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
