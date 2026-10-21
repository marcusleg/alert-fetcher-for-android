package de.marcusleg.alertmanagerreceiver.model

import de.marcusleg.alertmanagerreceiver.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WebhookPayloadTest {
    @Test
    fun `parses a real Alertmanager payload and ignores unknown fields`() {
        val payload = WebhookPayload.parse(TestFixtures.webhookJson())

        assertEquals("android", payload.receiver)
        assertEquals("http://alertmanager.lan:9093", payload.externalURL)
        assertEquals(2, payload.alerts.size)
        val first = payload.alerts[0]
        assertEquals("KubePodCrashLooping", first.labels["alertname"])
        assertEquals("4b0d2a5e7c9f1d3a", first.fingerprint)
        assertEquals("Pod is crash looping.", first.annotations["summary"])
    }

    @Test
    fun `tolerates a minimal payload`() {
        val payload = WebhookPayload.parse("""{"alerts":[{"labels":{"alertname":"X"}}]}""")
        assertEquals("firing", payload.alerts.single().status)
        assertEquals("4", payload.version)
    }

    @Test
    fun `round trips through JSON`() {
        val payload = WebhookPayload.parse(TestFixtures.webhookJson())
        assertEquals(payload, WebhookPayload.parse(payload.toJson()))
    }

    @Test
    fun `parses RFC3339 timestamps with nanoseconds and offsets`() {
        assertEquals(Instant.parse("2026-10-20T21:14:03.123Z").toEpochMilli(), Rfc3339.parseMillis("2026-10-20T21:14:03.123456789Z"))
        assertEquals(Instant.parse("2026-10-20T19:00:00Z").toEpochMilli(), Rfc3339.parseMillis("2026-10-20T21:00:00+02:00"))
    }

    @Test
    fun `treats the zero time and garbage as unset`() {
        assertNull(Rfc3339.parseMillis("0001-01-01T00:00:00Z"))
        assertNull(Rfc3339.parseMillis(""))
        assertNull(Rfc3339.parseMillis(null))
        assertNull(Rfc3339.parseMillis("yesterday"))
    }

    @Test
    fun `computes a stable fingerprint when Alertmanager omits one`() {
        val a = WebhookAlert(labels = mapOf("alertname" to "A", "instance" to "x"))
        val b = WebhookAlert(labels = linkedMapOf("instance" to "x", "alertname" to "A"))
        val c = WebhookAlert(labels = mapOf("alertname" to "A", "instance" to "y"))
        assertEquals(AlertIdentity.fingerprint(a), AlertIdentity.fingerprint(b))
        assertNotEquals(AlertIdentity.fingerprint(a), AlertIdentity.fingerprint(c))
        assertEquals("abc", AlertIdentity.fingerprint(a.copy(fingerprint = "abc")))
    }
}
