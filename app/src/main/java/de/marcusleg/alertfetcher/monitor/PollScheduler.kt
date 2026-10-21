package de.marcusleg.alertfetcher.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import de.marcusleg.alertfetcher.container
import de.marcusleg.alertfetcher.service.MonitorService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PollTrigger {
    /** The poll interval elapsed (alarm). Ignored if a check just ran. */
    SCHEDULED,
    /** User request, settings change or the network came back. Always checks. */
    NOW,
}

/**
 * Wakes the device for the next check. The service's own timer stops while the CPU sleeps (Doze),
 * so an alarm is what actually keeps the interval.
 */
class PollScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Exact alarms need the "Alarms & reminders" special permission on Android 12+. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleAt(at: Long) {
        val intent = pendingIntent()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // May be delayed by a few minutes, but still fires in Doze.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun cancel() = alarmManager.cancel(pendingIntent())

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PollAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class PollAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The alarm only keeps the CPU awake during onReceive; bridge the gap until the service takes its own lock.
        val power = context.getSystemService(PowerManager::class.java)
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertFetcher:alarm").acquire(BRIDGE_WAKE_LOCK_MS)
        val container = context.container
        if (MonitorService.isRunning) {
            container.pollTriggers.trySend(PollTrigger.SCHEDULED)
            return
        }
        // The process was killed; the service checks right away when it starts.
        val pending = goAsync()
        container.scope.launch {
            try {
                if (!container.monitorController.ensureRunningIfEnabled()) return@launch
            } catch (e: Exception) {
                // Background service starts can be refused (no exact alarm permission, battery optimized).
                // Check inline at least, within the broadcast's time budget.
                Log.w("PollAlarmReceiver", "Could not start the monitor service", e)
                withTimeoutOrNull(INLINE_POLL_TIMEOUT_MS) { container.poller.poll() }
                container.pollScheduler.scheduleAt(System.currentTimeMillis() + container.settingsRepository.current().pollIntervalMillis)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BRIDGE_WAKE_LOCK_MS = 10_000L
        const val INLINE_POLL_TIMEOUT_MS = 8_000L
    }
}
