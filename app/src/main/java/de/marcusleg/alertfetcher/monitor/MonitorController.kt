package de.marcusleg.alertfetcher.monitor

import de.marcusleg.alertfetcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the checks while monitoring is on and persists the user's choice. No service stays running:
 * an alarm wakes the app for each check, so nothing needs a persistent notification.
 */
class MonitorController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val poller: Poller,
    private val scheduler: PollScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.update { it.copy(monitoringEnabled = enabled) }
        if (enabled) {
            val now = clock()
            // Grace periods for "can't reach" and "heartbeat missing" start over.
            settingsRepository.updateMonitorState { it.copy(since = now, contactLostNotified = false, heartbeatMissingNotified = false) }
            checkNow()
        } else {
            scheduler.cancel()
            scheduler.unwatchNetwork()
        }
    }

    /** Checks Alertmanager now, in the background. While monitoring is off this checks once. */
    fun checkNow() {
        scope.launch { check(force = true) }
    }

    /** For the alarm: checks unless a check ran recently (e.g. a manual refresh), and arms the next alarm. */
    suspend fun checkIfDue() = check(force = false)

    /** Retries right away when the network comes back after a failed check, instead of waiting for the next alarm. */
    suspend fun checkAfterReconnect() {
        val state = settingsRepository.currentMonitorState()
        // A check that just failed re-registers the watch; don't let a network that connects without working loop.
        if (state.lastCheckFailed && clock() - (state.lastAttemptAt ?: 0L) >= RECONNECT_DEBOUNCE_MS) check(force = true)
    }

    /**
     * Re-arms the alarm, which a reboot, app update or force stop cancels, and checks right away if a check is
     * overdue or [force] is set. Does nothing while monitoring is off.
     */
    suspend fun resume(force: Boolean = false) {
        if (settingsRepository.current().monitoringEnabled) check(force)
    }

    /** Re-checks soon after the connection settings change (debounced: text fields save on every keystroke). */
    @OptIn(FlowPreview::class)
    suspend fun recheckOnSettingsChange() {
        settingsRepository.settingsFlow
            .map { it.copy(monitoringEnabled = true, startOnBoot = true, notifyResolved = true, retentionDays = 0) }
            .distinctUntilChanged()
            .drop(1)
            .debounce(1_500)
            .collect { if (settingsRepository.current().monitoringEnabled) check(force = true) }
    }

    private suspend fun check(force: Boolean) = mutex.withLock {
        val settings = settingsRepository.current()
        if (!settings.monitoringEnabled) {
            scheduler.cancel()
            if (force) poller.poll()
            return@withLock
        }
        if (!settings.isConfigured) {
            // Changing the settings checks again.
            scheduler.cancel()
            return@withLock
        }
        val interval = settings.pollIntervalMillis
        val now = clock()
        val lastAttempt = settingsRepository.currentMonitorState().lastAttemptAt ?: 0L
        val due = lastAttempt + interval
        if (!force && lastAttempt <= now && now < due - EARLY_TOLERANCE_MS) {
            scheduler.scheduleAt(due)
            return@withLock
        }
        // Arm the next check first, so monitoring goes on even if the system kills the process during this one.
        scheduler.scheduleAt(now + interval)
        poller.poll()
        scheduler.scheduleAt(clock() + interval)
        if (settingsRepository.currentMonitorState().lastCheckFailed && !poller.isOnline()) scheduler.watchNetwork()
    }

    private companion object {
        const val EARLY_TOLERANCE_MS = 5_000L
        const val RECONNECT_DEBOUNCE_MS = 15_000L
    }
}
