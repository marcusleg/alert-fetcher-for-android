package de.marcusleg.alertfetcher.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.marcusleg.alertfetcher.container
import kotlinx.coroutines.launch

/** Resumes monitoring after a reboot or an app update if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                // Both clear the notifications of alerts that are still firing; the next check posts them again.
                container.settingsRepository.updateMonitorState { it.copy(restoreNotifications = true) }
                val settings = container.settingsRepository.current()
                val wanted = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || settings.startOnBoot
                if (wanted) container.monitorController.resume(force = true)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not resume monitoring", e)
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
