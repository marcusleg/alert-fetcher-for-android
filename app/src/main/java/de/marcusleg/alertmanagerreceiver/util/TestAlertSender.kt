package de.marcusleg.alertmanagerreceiver.util

import android.os.Build
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.model.AlertIdentity
import de.marcusleg.alertmanagerreceiver.model.Rfc3339
import de.marcusleg.alertmanagerreceiver.model.WebhookAlert
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import de.marcusleg.alertmanagerreceiver.server.AuthValidator
import de.marcusleg.alertmanagerreceiver.server.WEBHOOK_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends a sample notification through the real HTTP endpoint, exercising auth, parsing and notifications. */
object TestAlertSender {
    fun samplePayload(status: String, now: Long = System.currentTimeMillis()): WebhookPayload {
        val labels = mapOf(
            "alertname" to "TestAlert",
            "severity" to "warning",
            "instance" to "${Build.MODEL}",
            "job" to "alertmanager-receiver",
        )
        return WebhookPayload(
            status = status,
            receiver = "android",
            groupKey = "{}:{alertname=\"TestAlert\"}",
            groupLabels = mapOf("alertname" to "TestAlert"),
            commonLabels = labels,
            alerts = listOf(
                WebhookAlert(
                    status = status,
                    labels = labels,
                    annotations = mapOf(
                        "summary" to "Test alert from Alertmanager Receiver",
                        "description" to "If you can read this, notifications work. Send a resolved test to clear it.",
                    ),
                    startsAt = Rfc3339.format(now),
                    endsAt = if (status == "resolved") Rfc3339.format(now) else "0001-01-01T00:00:00Z",
                    fingerprint = AlertIdentity.fingerprintOf(labels),
                ),
            ),
        )
    }

    suspend fun send(settings: AppSettings, status: String = "firing"): Int = withContext(Dispatchers.IO) {
        val connection = (URL("http://127.0.0.1:${settings.port}$WEBHOOK_PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            AuthValidator.headerFor(settings)?.let { setRequestProperty("Authorization", it) }
        }
        try {
            connection.outputStream.use { it.write(samplePayload(status).toJson().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Receiver answered HTTP $code")
            code
        } finally {
            connection.disconnect()
        }
    }
}
