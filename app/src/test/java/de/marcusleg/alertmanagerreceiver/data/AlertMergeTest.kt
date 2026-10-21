package de.marcusleg.alertmanagerreceiver.data

import de.marcusleg.alertmanagerreceiver.TestFixtures
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import de.marcusleg.alertmanagerreceiver.model.Severity
import de.marcusleg.alertmanagerreceiver.model.WebhookAlert
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMergeTest {
    private val payload = WebhookPayload.parse(TestFixtures.webhookJson())
    private val firing = payload.alerts[0]
    private val fp = firing.fingerprint

    @Test
    fun `classifies status transitions`() {
        assertEquals(AlertChange.NEW_FIRING, AlertChange.classify(null, AlertStatus.FIRING))
        assertEquals(AlertChange.STILL_FIRING, AlertChange.classify(AlertStatus.FIRING, AlertStatus.FIRING))
        assertEquals(AlertChange.REFIRED, AlertChange.classify(AlertStatus.RESOLVED, AlertStatus.FIRING))
        assertEquals(AlertChange.RESOLVED, AlertChange.classify(AlertStatus.FIRING, AlertStatus.RESOLVED))
        assertEquals(AlertChange.STILL_RESOLVED, AlertChange.classify(AlertStatus.RESOLVED, AlertStatus.RESOLVED))
        assertEquals(AlertChange.STILL_RESOLVED, AlertChange.classify(null, AlertStatus.RESOLVED))
    }

    @Test
    fun `new alert takes fields from the payload`() {
        val (alert, change) = AlertRepository.merge(null, firing, fp, payload, now = 1_000)
        assertEquals(AlertChange.NEW_FIRING, change)
        assertEquals("KubePodCrashLooping", alert.alertName)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals("Pod is crash looping.", alert.summary)
        assertEquals("media", alert.context)
        assertEquals("http://alertmanager.lan:9093", alert.externalUrl)
        assertNull(alert.endsAt)
        assertEquals(1_000, alert.firstReceivedAt)
        assertFalse(alert.acknowledged)
    }

    @Test
    fun `repeat notification keeps acknowledgement and first-seen time`() {
        val (first, _) = AlertRepository.merge(null, firing, fp, payload, now = 1_000)
        val acked = first.copy(acknowledged = true)
        val (second, change) = AlertRepository.merge(acked, firing, fp, payload, now = 5_000)
        assertEquals(AlertChange.STILL_FIRING, change)
        assertTrue(second.acknowledged)
        assertEquals(1_000, second.firstReceivedAt)
        assertEquals(1_000, second.statusChangedAt)
        assertEquals(5_000, second.lastReceivedAt)
    }

    @Test
    fun `resolving sets endsAt and re-firing starts a new episode`() {
        val (first, _) = AlertRepository.merge(null, firing, fp, payload, now = 1_000)
        val resolvedIncoming = firing.copy(status = "resolved", endsAt = "2026-10-20T22:00:00Z")
        val (resolved, change) = AlertRepository.merge(first.copy(acknowledged = true, silenceId = "s"), resolvedIncoming, fp, payload, now = 2_000)
        assertEquals(AlertChange.RESOLVED, change)
        assertEquals(AlertStatus.RESOLVED, resolved.status)
        assertEquals(java.time.Instant.parse("2026-10-20T22:00:00Z").toEpochMilli(), resolved.endsAt)
        assertEquals(2_000, resolved.statusChangedAt)

        val (refired, change2) = AlertRepository.merge(resolved, firing, fp, payload, now = 3_000)
        assertEquals(AlertChange.REFIRED, change2)
        assertFalse(refired.acknowledged)
        assertNull(refired.silenceId)
        assertNull(refired.endsAt)
        assertEquals(3_000, refired.firstReceivedAt)
    }

    @Test
    fun `resolved alert without endsAt uses receive time`() {
        val incoming = WebhookAlert(status = "resolved", labels = mapOf("alertname" to "X"), endsAt = "0001-01-01T00:00:00Z")
        val (alert, _) = AlertRepository.merge(null, incoming, "x", WebhookPayload(), now = 42)
        assertEquals(42L, alert.endsAt)
    }

    @Test
    fun `missing alertname gets a placeholder`() {
        val (alert, _) = AlertRepository.merge(null, WebhookAlert(), "x", WebhookPayload(), now = 1)
        assertEquals("Unnamed alert", alert.alertName)
    }

    @Test
    fun `ignored alert names are parsed from the comma separated setting`() {
        val settings = AppSettings(ignoredAlertNames = " InfoInhibitor, Watchdog ,,")
        assertEquals(setOf("InfoInhibitor", "Watchdog"), settings.ignoredAlertNameSet)
    }
}
