package de.marcusleg.alertmanagerreceiver.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An alert as returned by Alertmanager's `GET /api/v2/alerts` (the `gettableAlert` schema).
 * See https://github.com/prometheus/alertmanager/blob/main/api/v2/openapi.yaml
 */
@Serializable
data class ApiAlert(
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val startsAt: String? = null,
    val endsAt: String? = null,
    val updatedAt: String? = null,
    val generatorURL: String = "",
    val fingerprint: String = "",
    val receivers: List<ApiReceiver> = emptyList(),
    val status: ApiAlertStatus = ApiAlertStatus(),
) {
    val alertName: String get() = labels["alertname"].orEmpty()

    /** Silenced or inhibited in Alertmanager; such alerts would not be notified by Alertmanager either. */
    val isSuppressed: Boolean get() = status.state == "suppressed"

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun parseList(body: String): List<ApiAlert> = json.decodeFromString(body)
    }
}

@Serializable
data class ApiReceiver(val name: String = "")

@Serializable
data class ApiAlertStatus(
    /** `active`, `suppressed` or `unprocessed`. */
    val state: String = "active",
    val silencedBy: List<String> = emptyList(),
    val inhibitedBy: List<String> = emptyList(),
)

/** The subset of `GET /api/v2/status` shown when testing the connection. */
@Serializable
data class ApiStatus(
    val versionInfo: VersionInfo = VersionInfo(),
    val cluster: Cluster = Cluster(),
    val uptime: String? = null,
) {
    @Serializable
    data class VersionInfo(val version: String = "")

    @Serializable
    data class Cluster(val status: String = "", val peers: List<Peer> = emptyList())

    @Serializable
    data class Peer(val name: String = "")
}
