package de.marcusleg.alertmanagerreceiver.monitor

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.MonitorState

enum class HealthEvent {
    /** No successful check for a while although the phone is online. */
    CONTACT_LOST,
    /** Credentials or the OIDC session were rejected; needs the user. */
    AUTH_FAILED,
    /** A check succeeded after [CONTACT_LOST] or [AUTH_FAILED]. */
    CONNECTION_RESTORED,
    /** Alertmanager answers, but the always-firing heartbeat alert is gone: Prometheus or its rules are broken. */
    HEARTBEAT_MISSING,
    HEARTBEAT_RESTORED,
}

data class HealthOutcome(val state: MonitorState, val events: List<HealthEvent>)

/** Decides when a broken monitoring path is worth a notification. Pure so it can be unit tested. */
object Health {
    private const val MINUTE = 60_000L

    fun onSuccess(state: MonitorState, settings: AppSettings, now: Long, heartbeatSeen: Boolean): HealthOutcome {
        val events = mutableListOf<HealthEvent>()
        if (state.contactLostNotified || state.authProblem) events += HealthEvent.CONNECTION_RESTORED

        var heartbeatLastSeenAt = state.heartbeatLastSeenAt
        var heartbeatArmedAt = state.heartbeatArmedAt
        var heartbeatMissing = state.heartbeatMissingNotified
        when {
            !settings.heartbeatEnabled -> {
                heartbeatLastSeenAt = null
                heartbeatArmedAt = null
                heartbeatMissing = false
            }
            heartbeatSeen -> {
                if (heartbeatMissing) events += HealthEvent.HEARTBEAT_RESTORED
                heartbeatLastSeenAt = now
                heartbeatArmedAt = heartbeatArmedAt ?: now
                heartbeatMissing = false
            }
            else -> {
                heartbeatArmedAt = heartbeatArmedAt ?: now
                val reference = maxOf(heartbeatLastSeenAt ?: 0L, heartbeatArmedAt, state.since ?: 0L)
                if (!heartbeatMissing && now - reference >= settings.effectiveStaleAfterMinutes * MINUTE) {
                    events += HealthEvent.HEARTBEAT_MISSING
                    heartbeatMissing = true
                }
            }
        }

        val next = state.copy(
            lastAttemptAt = now,
            lastSuccessAt = now,
            lastError = null,
            consecutiveFailures = 0,
            authProblem = false,
            contactLostNotified = false,
            heartbeatLastSeenAt = heartbeatLastSeenAt,
            heartbeatArmedAt = heartbeatArmedAt,
            heartbeatMissingNotified = heartbeatMissing,
        )
        return HealthOutcome(next, events)
    }

    fun onFailure(
        state: MonitorState,
        settings: AppSettings,
        now: Long,
        error: String,
        authProblem: Boolean,
        online: Boolean,
    ): HealthOutcome {
        val events = mutableListOf<HealthEvent>()
        val failures = state.consecutiveFailures + 1
        if (authProblem && !state.authProblem) events += HealthEvent.AUTH_FAILED

        var contactLost = state.contactLostNotified
        // Being offline is something the user already knows about; don't alarm for it.
        if (!authProblem && online && !contactLost && failures >= 2) {
            val reference = maxOf(state.lastSuccessAt ?: 0L, state.since ?: 0L)
            if (now - reference >= settings.effectiveStaleAfterMinutes * MINUTE) {
                events += HealthEvent.CONTACT_LOST
                contactLost = true
            }
        }

        val next = state.copy(
            lastAttemptAt = now,
            lastError = error,
            consecutiveFailures = failures,
            authProblem = authProblem,
            contactLostNotified = contactLost,
        )
        return HealthOutcome(next, events)
    }
}
