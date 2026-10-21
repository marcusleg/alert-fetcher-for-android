package de.marcusleg.alertfetcher.model

import de.marcusleg.alertfetcher.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAlertTest {
    @Test
    fun `parses the alerts endpoint and ignores unknown fields`() {
        val alerts = TestFixtures.alerts()
        assertEquals(4, alerts.size)
        val crash = alerts[0]
        assertEquals("KubePodCrashLooping", crash.alertName)
        assertEquals("5a0b1c2d3e4f5061", crash.fingerprint)
        assertEquals(listOf("phone", "default"), crash.receivers.map { it.name })
        assertFalse(crash.isSuppressed)
        assertEquals("Pod is crash looping.", crash.annotations["summary"])
    }

    @Test
    fun `recognises suppressed alerts`() {
        val silenced = TestFixtures.alerts()[1]
        assertTrue(silenced.isSuppressed)
        assertEquals(listOf("2a8f2f3e-6f6b-4b4e-9f3c-1d2e3f4a5b6c"), silenced.status.silencedBy)
    }

    @Test
    fun `tolerates missing and null fields`() {
        val alerts = ApiAlert.parseList("""[{"labels":{"alertname":"X"},"status":null,"receivers":null}]""")
        assertEquals("X", alerts.single().alertName)
        assertEquals("active", alerts.single().status.state)
        assertTrue(alerts.single().receivers.isEmpty())
    }

    @Test
    fun `parses the status endpoint`() {
        val status = ApiAlert.json.decodeFromString(
            ApiStatus.serializer(),
            """{"cluster":{"name":"01J","peers":[{"address":"10.0.0.1:9094","name":"a"},{"address":"10.0.0.2:9094","name":"b"}],"status":"ready"},
               "config":{"original":"global: {}"},"uptime":"2026-10-21T08:00:00Z","versionInfo":{"version":"0.29.0","revision":"abc"}}""",
        )
        assertEquals("0.29.0", status.versionInfo.version)
        assertEquals("ready", status.cluster.status)
        assertEquals(2, status.cluster.peers.size)
    }
}
