package de.marcusleg.alertmanagerreceiver.data

import androidx.room.withTransaction
import de.marcusleg.alertmanagerreceiver.model.AlertIdentity
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.ApiAlert
import de.marcusleg.alertmanagerreceiver.model.Rfc3339
import de.marcusleg.alertmanagerreceiver.model.Severity
import kotlinx.coroutines.flow.Flow

/** How an alert's state changed between two checks. */
enum class AlertChange {
    /** Never seen before and firing. */
    NEW_FIRING,
    /** Was resolved, fires again. */
    REFIRED,
    /** Was silenced or inhibited in Alertmanager and no longer is. */
    UNSUPPRESSED,
    STILL_FIRING,
    /** Now silenced or inhibited in Alertmanager. */
    SUPPRESSED,
    STILL_SUPPRESSED,
    /** Was firing, no longer returned by Alertmanager. */
    RESOLVED,
    /** Was suppressed when it went away, so the user never got a firing notification. */
    RESOLVED_QUIETLY;

    /** Whether this change should raise a (sounding) notification. */
    val isNewlyFiring: Boolean get() = this == NEW_FIRING || this == REFIRED || this == UNSUPPRESSED

    companion object {
        fun classify(previous: AlertStatus?, incoming: AlertStatus): AlertChange = when (incoming) {
            AlertStatus.FIRING -> when (previous) {
                null -> NEW_FIRING
                AlertStatus.RESOLVED -> REFIRED
                AlertStatus.SUPPRESSED -> UNSUPPRESSED
                AlertStatus.FIRING -> STILL_FIRING
            }
            AlertStatus.SUPPRESSED -> if (previous == AlertStatus.SUPPRESSED) STILL_SUPPRESSED else SUPPRESSED
            AlertStatus.RESOLVED -> if (previous == AlertStatus.FIRING) RESOLVED else RESOLVED_QUIETLY
        }
    }
}

data class AlertUpdate(val alert: AlertEntity, val change: AlertChange)

data class SyncResult(
    val updates: List<AlertUpdate>,
    /** The heartbeat alert (e.g. Watchdog) was among the alerts Alertmanager returned. */
    val heartbeatSeen: Boolean,
    val fetched: Int,
    val firing: Int,
)

class AlertRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val alerts = db.alertDao()
    private val checks = db.checkDao()

    fun observeAlerts(): Flow<List<AlertEntity>> = alerts.observeAll()
    fun observeAlert(fingerprint: String): Flow<AlertEntity?> = alerts.observe(fingerprint)
    fun observeFiringCount(): Flow<Int> = alerts.observeCount(AlertStatus.FIRING)
    fun observeChecks(): Flow<List<CheckEntity>> = checks.observeRecent()

    suspend fun get(fingerprint: String): AlertEntity? = alerts.get(fingerprint)
    suspend fun firing(): List<AlertEntity> = alerts.firing()
    suspend fun setAcknowledged(fingerprint: String, acknowledged: Boolean) = alerts.setAcknowledged(fingerprint, acknowledged)
    suspend fun setSilenced(fingerprint: String, until: Long) = alerts.setSilenced(fingerprint, until)
    suspend fun delete(fingerprint: String) = alerts.delete(fingerprint)
    suspend fun deleteResolved() = alerts.deleteResolved()
    suspend fun deleteAll() {
        alerts.deleteAll()
        checks.deleteAll()
    }

    suspend fun applyRetention(days: Int) {
        if (days > 0) alerts.deleteResolvedBefore(clock() - days * 86_400_000L)
        checks.trim()
    }

    /**
     * Reconciles the stored alerts with the complete list Alertmanager just returned: alerts that are
     * no longer returned have resolved.
     */
    suspend fun sync(fetched: List<ApiAlert>, settings: AppSettings, startedAt: Long): SyncResult {
        val now = clock()
        val updates = mutableListOf<AlertUpdate>()
        val heartbeatSeen = settings.heartbeatEnabled && fetched.any { it.alertName == settings.heartbeatAlertName }
        val relevant = filter(fetched, settings)

        db.withTransaction {
            val previouslyActive = alerts.active().associateBy { it.fingerprint }
            val seen = HashSet<String>()
            for (incoming in relevant) {
                val fingerprint = AlertIdentity.fingerprint(incoming)
                if (!seen.add(fingerprint)) continue
                val existing = previouslyActive[fingerprint] ?: alerts.get(fingerprint)
                val (entity, change) = merge(existing, incoming, fingerprint, settings.alertmanagerUrl, now)
                alerts.upsert(entity)
                updates += AlertUpdate(entity, change)
            }
            for ((fingerprint, existing) in previouslyActive) {
                if (fingerprint in seen) continue
                val (entity, change) = resolve(existing, now)
                alerts.upsert(entity)
                updates += AlertUpdate(entity, change)
            }
            checks.insert(
                CheckEntity(
                    startedAt = startedAt,
                    durationMillis = now - startedAt,
                    success = true,
                    alertCount = fetched.size,
                    firingCount = updates.count { it.alert.status == AlertStatus.FIRING },
                ),
            )
        }
        applyRetention(settings.retentionDays)
        return SyncResult(
            updates = updates,
            heartbeatSeen = heartbeatSeen,
            fetched = fetched.size,
            firing = updates.count { it.alert.status == AlertStatus.FIRING },
        )
    }

    suspend fun recordFailure(startedAt: Long, message: String) {
        checks.insert(CheckEntity(startedAt = startedAt, durationMillis = clock() - startedAt, success = false, message = message))
        checks.trim()
    }

    companion object {
        /** Drops the heartbeat, ignored alert names and alerts not routed to the configured receivers. */
        fun filter(fetched: List<ApiAlert>, settings: AppSettings): List<ApiAlert> {
            val ignored = settings.ignoredAlertNameSet
            val receivers = settings.receiverFilterSet
            return fetched.filter { alert ->
                val name = alert.alertName
                when {
                    settings.heartbeatEnabled && name == settings.heartbeatAlertName -> false
                    name in ignored -> false
                    receivers.isNotEmpty() -> alert.receivers.any { it.name in receivers }
                    else -> true
                }
            }
        }

        fun merge(
            existing: AlertEntity?,
            incoming: ApiAlert,
            fingerprint: String,
            alertmanagerUrl: String,
            now: Long,
        ): Pair<AlertEntity, AlertChange> {
            val status = if (incoming.isSuppressed) AlertStatus.SUPPRESSED else AlertStatus.FIRING
            val change = AlertChange.classify(existing?.status, status)
            val newEpisode = existing == null || existing.status == AlertStatus.RESOLVED
            // A silence that ran out (or an inhibition that ended) deserves a fresh notification.
            val resetUserState = newEpisode || change == AlertChange.UNSUPPRESSED
            val entity = AlertEntity(
                fingerprint = fingerprint,
                alertName = incoming.alertName.ifBlank { "Unnamed alert" },
                status = status,
                severity = Severity.fromLabel(incoming.labels["severity"]),
                labels = incoming.labels,
                annotations = incoming.annotations,
                startsAt = Rfc3339.parseMillis(incoming.startsAt),
                // For active alerts Alertmanager's endsAt is only an expiry estimate.
                endsAt = null,
                generatorUrl = incoming.generatorURL.ifBlank { null },
                alertmanagerUrl = alertmanagerUrl.ifBlank { null },
                receivers = incoming.receivers.map { it.name }.filter { it.isNotEmpty() },
                silencedBy = incoming.status.silencedBy,
                inhibitedBy = incoming.status.inhibitedBy,
                firstSeenAt = if (newEpisode) now else existing.firstSeenAt,
                lastSeenAt = now,
                statusChangedAt = if (existing == null || existing.status != status) now else existing.statusChangedAt,
                acknowledged = if (resetUserState) false else existing.acknowledged,
                silencedUntil = if (resetUserState) null else existing.silencedUntil,
            )
            return entity to change
        }

        fun resolve(existing: AlertEntity, now: Long): Pair<AlertEntity, AlertChange> {
            val change = AlertChange.classify(existing.status, AlertStatus.RESOLVED)
            val entity = existing.copy(
                status = AlertStatus.RESOLVED,
                endsAt = now,
                statusChangedAt = now,
                silencedBy = emptyList(),
                inhibitedBy = emptyList(),
            )
            return entity to change
        }
    }
}
