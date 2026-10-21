package de.marcusleg.alertmanagerreceiver.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.Severity

@Entity(
    tableName = "alerts",
    indices = [Index("status"), Index("lastReceivedAt")],
)
data class AlertEntity(
    @PrimaryKey val fingerprint: String,
    val alertName: String,
    val status: AlertStatus,
    val severity: Severity,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val startsAt: Long?,
    val endsAt: Long?,
    val generatorUrl: String?,
    val externalUrl: String?,
    val receiver: String?,
    /** When this device first saw the alert (in its current firing episode). */
    val firstReceivedAt: Long,
    /** When this device last received a notification containing the alert. */
    val lastReceivedAt: Long,
    /** When the status last changed (fired, re-fired or resolved). */
    val statusChangedAt: Long,
    val acknowledged: Boolean = false,
    val silenceId: String? = null,
    val silencedUntil: Long? = null,
) {
    val summary: String?
        get() = annotations["summary"] ?: annotations["message"] ?: annotations["title"]

    val description: String?
        get() = annotations["description"]

    /** A short label to give context in lists, e.g. the namespace or instance. */
    val context: String?
        get() = CONTEXT_LABELS.firstNotNullOfOrNull { labels[it] }

    companion object {
        val CONTEXT_LABELS = listOf("namespace", "instance", "pod", "job", "service", "node", "cluster")
    }
}

@Entity(tableName = "deliveries", indices = [Index("receivedAt")])
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receivedAt: Long,
    val remoteAddress: String,
    val receiver: String?,
    val groupStatus: String?,
    val firing: Int,
    val resolved: Int,
    val httpStatus: Int,
    val message: String?,
)
