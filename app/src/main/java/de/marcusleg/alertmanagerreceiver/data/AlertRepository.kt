package de.marcusleg.alertmanagerreceiver.data

import androidx.room.withTransaction
import de.marcusleg.alertmanagerreceiver.model.AlertIdentity
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.Rfc3339
import de.marcusleg.alertmanagerreceiver.model.Severity
import de.marcusleg.alertmanagerreceiver.model.WebhookAlert
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import kotlinx.coroutines.flow.Flow

/** How an incoming alert relates to what we already know about it. */
enum class AlertChange {
    /** Never seen before and firing. */
    NEW_FIRING,
    /** Was resolved, fires again. */
    REFIRED,
    /** Still firing; Alertmanager re-sent it (group_interval / repeat_interval). */
    STILL_FIRING,
    /** Was firing, now resolved. */
    RESOLVED,
    /** Resolved notification for an alert we never saw firing, or a repeated resolve. */
    STILL_RESOLVED;

    companion object {
        fun classify(previous: AlertStatus?, incoming: AlertStatus): AlertChange = when (incoming) {
            AlertStatus.FIRING -> when (previous) {
                null -> NEW_FIRING
                AlertStatus.RESOLVED -> REFIRED
                AlertStatus.FIRING -> STILL_FIRING
            }
            AlertStatus.RESOLVED -> when (previous) {
                AlertStatus.FIRING -> RESOLVED
                else -> STILL_RESOLVED
            }
        }
    }
}

data class AlertUpdate(val alert: AlertEntity, val change: AlertChange)

data class IngestResult(
    val updates: List<AlertUpdate>,
    val heartbeatReceived: Boolean,
    val ignored: Int,
)

class AlertRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val alerts = db.alertDao()
    private val deliveries = db.deliveryDao()

    fun observeAlerts(): Flow<List<AlertEntity>> = alerts.observeAll()
    fun observeAlert(fingerprint: String): Flow<AlertEntity?> = alerts.observe(fingerprint)
    fun observeFiringCount(): Flow<Int> = alerts.observeCount(AlertStatus.FIRING)
    fun observeDeliveries(): Flow<List<DeliveryEntity>> = deliveries.observeRecent()
    fun observeLastSuccessfulDelivery(): Flow<DeliveryEntity?> = deliveries.observeLastSuccess()

    suspend fun get(fingerprint: String): AlertEntity? = alerts.get(fingerprint)
    suspend fun firing(): List<AlertEntity> = alerts.firing()
    suspend fun setAcknowledged(fingerprint: String, acknowledged: Boolean) = alerts.setAcknowledged(fingerprint, acknowledged)
    suspend fun setSilenced(fingerprint: String, silenceId: String, until: Long) = alerts.setSilenced(fingerprint, silenceId, until)
    suspend fun delete(fingerprint: String) = alerts.delete(fingerprint)
    suspend fun deleteResolved() = alerts.deleteResolved()
    suspend fun deleteAll() {
        alerts.deleteAll()
        deliveries.deleteAll()
    }

    suspend fun applyRetention(days: Int) {
        if (days > 0) alerts.deleteResolvedBefore(clock() - days * 86_400_000L)
        deliveries.trim()
    }

    suspend fun ingest(payload: WebhookPayload, remoteAddress: String, settings: AppSettings): IngestResult {
        val now = clock()
        val updates = mutableListOf<AlertUpdate>()
        var heartbeat = false
        var ignored = 0
        val ignoredNames = settings.ignoredAlertNameSet

        db.withTransaction {
            for (incoming in payload.alerts) {
                val name = incoming.labels["alertname"].orEmpty()
                if (settings.heartbeatEnabled && name == settings.heartbeatAlertName) {
                    heartbeat = true
                    continue
                }
                if (name in ignoredNames) {
                    ignored++
                    continue
                }
                val fingerprint = AlertIdentity.fingerprint(incoming)
                val existing = alerts.get(fingerprint)
                val entity = merge(existing, incoming, fingerprint, payload, now)
                alerts.upsert(entity.first)
                updates += AlertUpdate(entity.first, entity.second)
            }
            deliveries.insert(
                DeliveryEntity(
                    receivedAt = now,
                    remoteAddress = remoteAddress,
                    receiver = payload.receiver.ifBlank { null },
                    groupStatus = payload.status,
                    firing = payload.alerts.count { AlertStatus.fromString(it.status) == AlertStatus.FIRING },
                    resolved = payload.alerts.count { AlertStatus.fromString(it.status) == AlertStatus.RESOLVED },
                    httpStatus = 200,
                    message = if (heartbeat) "heartbeat" else null,
                ),
            )
        }
        applyRetention(settings.retentionDays)
        return IngestResult(updates, heartbeat, ignored)
    }

    suspend fun recordRejected(remoteAddress: String, httpStatus: Int, message: String) {
        deliveries.insert(
            DeliveryEntity(
                receivedAt = clock(),
                remoteAddress = remoteAddress,
                receiver = null,
                groupStatus = null,
                firing = 0,
                resolved = 0,
                httpStatus = httpStatus,
                message = message,
            ),
        )
    }

    companion object {
        fun merge(
            existing: AlertEntity?,
            incoming: WebhookAlert,
            fingerprint: String,
            payload: WebhookPayload,
            now: Long,
        ): Pair<AlertEntity, AlertChange> {
            val status = AlertStatus.fromString(incoming.status)
            val change = AlertChange.classify(existing?.status, status)
            val newEpisode = change == AlertChange.NEW_FIRING || change == AlertChange.REFIRED
            val statusChanged = newEpisode || change == AlertChange.RESOLVED || existing == null
            val entity = AlertEntity(
                fingerprint = fingerprint,
                alertName = incoming.labels["alertname"]?.ifBlank { null } ?: "Unnamed alert",
                status = status,
                severity = Severity.fromLabel(incoming.labels["severity"]),
                labels = incoming.labels,
                annotations = incoming.annotations,
                startsAt = Rfc3339.parseMillis(incoming.startsAt),
                endsAt = if (status == AlertStatus.RESOLVED) Rfc3339.parseMillis(incoming.endsAt) ?: now else null,
                generatorUrl = incoming.generatorURL.ifBlank { null },
                externalUrl = payload.externalURL.ifBlank { null } ?: existing?.externalUrl,
                receiver = payload.receiver.ifBlank { null } ?: existing?.receiver,
                firstReceivedAt = if (newEpisode || existing == null) now else existing.firstReceivedAt,
                lastReceivedAt = now,
                statusChangedAt = if (statusChanged) now else existing.statusChangedAt,
                acknowledged = if (newEpisode) false else existing?.acknowledged ?: false,
                silenceId = if (newEpisode) null else existing?.silenceId,
                silencedUntil = if (newEpisode) null else existing?.silencedUntil,
            )
            return entity to change
        }
    }
}
