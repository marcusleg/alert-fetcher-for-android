package de.marcusleg.alertfetcher.alertmanager

import android.os.Build
import de.marcusleg.alertfetcher.auth.OidcManager
import de.marcusleg.alertfetcher.data.AlertRepository
import de.marcusleg.alertfetcher.data.AuthMode
import de.marcusleg.alertfetcher.data.SettingsRepository
import de.marcusleg.alertfetcher.notify.AlertNotifier
import kotlinx.coroutines.flow.first

class SilenceManager(
    private val api: AlertmanagerApi,
    private val settingsRepository: SettingsRepository,
    private val alertRepository: AlertRepository,
    private val notifier: AlertNotifier,
    private val oidc: OidcManager,
    /** Called after a silence was created so the alert list catches up with Alertmanager. */
    private val onSilenced: () -> Unit,
) {
    suspend fun silence(fingerprint: String, durationMillis: Long): Result<String> = runCatching {
        val alert = alertRepository.get(fingerprint) ?: error("Alert no longer exists")
        val settings = settingsRepository.current()
        require(settings.isConfigured) { "Set up the Alertmanager connection first" }
        val now = System.currentTimeMillis()
        val until = now + durationMillis
        val user = if (settings.authMode == AuthMode.OIDC) oidc.session.first()?.identity else null
        val id = api.createSilence(
            settings = settings,
            labels = alert.labels,
            startsAt = now,
            endsAt = until,
            createdBy = user ?: settings.basicUsername.takeIf { settings.authMode == AuthMode.BASIC && it.isNotBlank() }
                ?: "alert-fetcher-android",
            comment = "Silenced from ${Build.MANUFACTURER} ${Build.MODEL} via Alert Fetcher",
        )
        alertRepository.setSilenced(fingerprint, until)
        notifier.cancel(fingerprint)
        onSilenced()
        id
    }
}
