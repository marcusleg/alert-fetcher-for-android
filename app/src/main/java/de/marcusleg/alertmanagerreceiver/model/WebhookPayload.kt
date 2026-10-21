package de.marcusleg.alertmanagerreceiver.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON body Alertmanager POSTs to a `webhook_config` receiver (payload version 4).
 * See https://prometheus.io/docs/alerting/latest/configuration/#webhook_config
 */
@Serializable
data class WebhookPayload(
    val version: String = "4",
    val groupKey: String = "",
    val truncatedAlerts: Int = 0,
    val status: String = "firing",
    val receiver: String = "",
    val groupLabels: Map<String, String> = emptyMap(),
    val commonLabels: Map<String, String> = emptyMap(),
    val commonAnnotations: Map<String, String> = emptyMap(),
    val externalURL: String = "",
    val alerts: List<WebhookAlert> = emptyList(),
) {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun parse(body: String): WebhookPayload = json.decodeFromString(serializer(), body)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}

@Serializable
data class WebhookAlert(
    val status: String = "firing",
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val startsAt: String? = null,
    val endsAt: String? = null,
    val generatorURL: String = "",
    val fingerprint: String = "",
)
