package de.marcusleg.alertfetcher.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.util.Log
import de.marcusleg.alertfetcher.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Wakes the app for each check with an alarm, which also fires while the phone sleeps (Doze) and after the
 * process was killed.
 */
class PollScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Exact alarms need the "Alarms & reminders" special permission on Android 12+. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleAt(at: Long) {
        val intent = alarmIntent()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            // May be delayed by a few minutes, but still fires in Doze.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun cancel() = alarmManager.cancel(alarmIntent())

    /**
     * Delivers [NetworkAvailableReceiver] once a network connects, even if the process isn't running. Android
     * delivers it once and then drops the request; it is delivered right away if a network is already connected.
     */
    fun watchNetwork() {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        // Registering the same PendingIntent again replaces the earlier request.
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(request, networkIntent()) }
            .onFailure { Log.w("PollScheduler", "Could not watch the network", it) }
    }

    fun unwatchNetwork() {
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkIntent()) }
    }

    private fun alarmIntent() = broadcast(PollAlarmReceiver::class.java)

    private fun networkIntent() = broadcast(NetworkAvailableReceiver::class.java)

    private fun broadcast(receiver: Class<out BroadcastReceiver>): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, receiver),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class PollAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runCheck(context, "alarm") { it.checkIfDue() }
}

class NetworkAvailableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runCheck(context, "network") { it.checkAfterReconnect() }
}

/**
 * Runs a check past onReceive, keeping the CPU awake until it's done. The HTTP timeouts keep it well within
 * the time Android gives a background broadcast.
 */
private fun BroadcastReceiver.runCheck(context: Context, reason: String, check: suspend (MonitorController) -> Unit) {
    val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertFetcher:$reason")
    lock.acquire(CHECK_WAKE_LOCK_MS)
    val pending = goAsync()
    val container = context.container
    container.scope.launch {
        try {
            check(container.monitorController)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PollScheduler", "Check after $reason failed", e)
        } finally {
            if (lock.isHeld) lock.release()
            pending.finish()
        }
    }
}

private const val CHECK_WAKE_LOCK_MS = 2 * 60_000L
