package de.marcusleg.alertmanagerreceiver.service

import de.marcusleg.alertmanagerreceiver.data.AlertRepository
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import de.marcusleg.alertmanagerreceiver.heartbeat.HeartbeatMonitor
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import de.marcusleg.alertmanagerreceiver.notify.AlertNotifier
import de.marcusleg.alertmanagerreceiver.server.WebhookHandler

/** Glue between the HTTP server and storage / notifications. */
class AlertProcessor(
    private val settingsRepository: SettingsRepository,
    private val alertRepository: AlertRepository,
    private val notifier: AlertNotifier,
    private val heartbeatMonitor: HeartbeatMonitor,
) : WebhookHandler {
    override suspend fun currentSettings(): AppSettings = settingsRepository.current()

    override suspend fun onWebhook(payload: WebhookPayload, remoteAddress: String) {
        val settings = settingsRepository.current()
        val result = alertRepository.ingest(payload, remoteAddress, settings)
        notifier.handle(result.updates, settings)
        if (result.heartbeatReceived) heartbeatMonitor.onHeartbeat()
    }

    override suspend fun onRejected(remoteAddress: String, httpStatus: Int, reason: String) {
        alertRepository.recordRejected(remoteAddress, httpStatus, reason)
    }
}
