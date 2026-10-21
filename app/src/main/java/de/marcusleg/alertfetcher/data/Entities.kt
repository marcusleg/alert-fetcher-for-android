package de.marcusleg.alertfetcher.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.marcusleg.alertfetcher.model.AlertStatus
import de.marcusleg.alertfetcher.model.Severity

@Entity(
    tableName = "alerts",
    indices = [Index("status"), Index("lastSeenAt")],
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
    val alertmanagerUrl: String?,
    val receivers: List<String>,
    /** IDs of the Alertmanager silences currently muting the alert. */
    val silencedBy: List<String> = emptyList(),
    /** Fingerprints of the alerts currently inhibiting this one. */
    val inhibitedBy: List<String> = emptyList(),
    /** When this device first saw the alert (in its current firing episode). */
    val firstSeenAt: Long,
    /** When the alert was last returned by Alertmanager. */
    val lastSeenAt: Long,
    /** When the status last changed (fired, re-fired, suppressed or resolved). */
    val statusChangedAt: Long,
    val acknowledged: Boolean = false,
    /** End of a silence created from this device, shown until Alertmanager reports it. */
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

/** One poll of the Alertmanager API, for the "Recent checks" log. */
@Entity(tableName = "checks", indices = [Index("startedAt")])
data class CheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMillis: Long,
    val success: Boolean,
    /** Alerts returned by Alertmanager (before filtering). */
    val alertCount: Int = 0,
    /** Alerts that are firing and not suppressed, after filtering. */
    val firingCount: Int = 0,
    val message: String? = null,
)
