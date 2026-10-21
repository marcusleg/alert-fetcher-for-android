package de.marcusleg.alertmanagerreceiver.alertmanager

import android.os.Build
import de.marcusleg.alertmanagerreceiver.data.AlertRepository
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier

class SilenceManager(
    private val client: AlertmanagerClient,
    private val settingsRepository: SettingsRepository,
    private val alertRepository: AlertRepository,
    private val notifier: AlertNotifier,
) {
    suspend fun baseUrlFor(fingerprint: String): String? {
        val configured = settingsRepository.current().alertmanagerUrl
        if (configured.isNotBlank()) return configured
        return alertRepository.get(fingerprint)?.externalUrl?.takeIf { it.isNotBlank() }
    }

    suspend fun silence(fingerprint: String, durationMillis: Long): Result<String> = runCatching {
        val alert = alertRepository.get(fingerprint) ?: error("Alert no longer exists")
        val settings = settingsRepository.current()
        val baseUrl = settings.alertmanagerUrl.ifBlank { alert.externalUrl.orEmpty() }
        require(baseUrl.isNotBlank()) { "No Alertmanager URL known; set one in Settings" }
        val now = System.currentTimeMillis()
        val until = now + durationMillis
        val id = client.createSilence(
            baseUrl = baseUrl,
            authorization = settings.alertmanagerAuthorization,
            labels = alert.labels,
            startsAt = now,
            endsAt = until,
            createdBy = "alertmanager-receiver-android",
            comment = "Silenced from ${Build.MANUFACTURER} ${Build.MODEL}",
        )
        alertRepository.setSilenced(fingerprint, id, until)
        notifier.cancel(fingerprint)
        id
    }
}
