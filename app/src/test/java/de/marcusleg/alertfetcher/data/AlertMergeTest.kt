package de.marcusleg.alertfetcher.data

import de.marcusleg.alertfetcher.TestFixtures
import de.marcusleg.alertfetcher.model.AlertStatus
import de.marcusleg.alertfetcher.model.ApiAlertStatus
import de.marcusleg.alertfetcher.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMergeTest {
    private val alerts = TestFixtures.alerts()
    private val firing = alerts[0]
    private val silenced = firing.copy(status = ApiAlertStatus(state = "suppressed", silencedBy = listOf("s1")))
    private val fp = firing.fingerprint
    private val url = "https://am.example.com"

    @Test
    fun `classifies status transitions`() {
        assertEquals(AlertChange.NEW_FIRING, AlertChange.classify(null, AlertStatus.FIRING))
        assertEquals(AlertChange.STILL_FIRING, AlertChange.classify(AlertStatus.FIRING, AlertStatus.FIRING))
        assertEquals(AlertChange.REFIRED, AlertChange.classify(AlertStatus.RESOLVED, AlertStatus.FIRING))
        assertEquals(AlertChange.UNSUPPRESSED, AlertChange.classify(AlertStatus.SUPPRESSED, AlertStatus.FIRING))
        assertEquals(AlertChange.SUPPRESSED, AlertChange.classify(AlertStatus.FIRING, AlertStatus.SUPPRESSED))
        assertEquals(AlertChange.SUPPRESSED, AlertChange.classify(null, AlertStatus.SUPPRESSED))
        assertEquals(AlertChange.STILL_SUPPRESSED, AlertChange.classify(AlertStatus.SUPPRESSED, AlertStatus.SUPPRESSED))
        assertEquals(AlertChange.RESOLVED, AlertChange.classify(AlertStatus.FIRING, AlertStatus.RESOLVED))
        assertEquals(AlertChange.RESOLVED_QUIETLY, AlertChange.classify(AlertStatus.SUPPRESSED, AlertStatus.RESOLVED))
    }

    @Test
    fun `only newly firing changes notify`() {
        val notifying = AlertChange.entries.filter { it.isNewlyFiring }.toSet()
        assertEquals(setOf(AlertChange.NEW_FIRING, AlertChange.REFIRED, AlertChange.UNSUPPRESSED), notifying)
    }

    @Test
    fun `new alert takes fields from the API`() {
        val (alert, change) = AlertRepository.merge(null, firing, fp, url, now = 1_000)
        assertEquals(AlertChange.NEW_FIRING, change)
        assertEquals("KubePodCrashLooping", alert.alertName)
        assertEquals(AlertStatus.FIRING, alert.status)
        assertEquals(Severity.WARNING, alert.severity)
        assertEquals("Pod is crash looping.", alert.summary)
        assertEquals("media", alert.context)
        assertEquals(url, alert.alertmanagerUrl)
        assertEquals(listOf("phone", "default"), alert.receivers)
        assertEquals(java.time.Instant.parse("2026-10-21T09:58:30Z").toEpochMilli(), alert.startsAt)
        assertNull("endsAt of an active alert is only an estimate", alert.endsAt)
        assertEquals(1_000, alert.firstSeenAt)
        assertFalse(alert.acknowledged)
    }

    @Test
    fun `unchanged alert keeps acknowledgement and first-seen time`() {
        val (first, _) = AlertRepository.merge(null, firing, fp, url, now = 1_000)
        val (second, change) = AlertRepository.merge(first.copy(acknowledged = true), firing, fp, url, now = 5_000)
        assertEquals(AlertChange.STILL_FIRING, change)
        assertTrue(second.acknowledged)
        assertEquals(1_000, second.firstSeenAt)
        assertEquals(1_000, second.statusChangedAt)
        assertEquals(5_000, second.lastSeenAt)
    }

    @Test
    fun `silence and its expiry`() {
        val (first, _) = AlertRepository.merge(null, firing, fp, url, now = 1_000)
        val (muted, change) = AlertRepository.merge(first.copy(acknowledged = true, silencedUntil = 9_000), silenced, fp, url, now = 2_000)
        assertEquals(AlertChange.SUPPRESSED, change)
        assertEquals(AlertStatus.SUPPRESSED, muted.status)
        assertEquals(listOf("s1"), muted.silencedBy)
        assertEquals(2_000, muted.statusChangedAt)
        assertTrue(muted.acknowledged)

        val (back, change2) = AlertRepository.merge(muted, firing, fp, url, now = 3_000)
        assertEquals(AlertChange.UNSUPPRESSED, change2)
        assertFalse("an expired silence should notify again", back.acknowledged)
        assertNull(back.silencedUntil)
        assertEquals(1_000, back.firstSeenAt)
    }

    @Test
    fun `disappearing alert resolves and re-firing starts a new episode`() {
        val (first, _) = AlertRepository.merge(null, firing, fp, url, now = 1_000)
        val (resolved, change) = AlertRepository.resolve(first.copy(acknowledged = true), now = 2_000)
        assertEquals(AlertChange.RESOLVED, change)
        assertEquals(AlertStatus.RESOLVED, resolved.status)
        assertEquals(2_000L, resolved.endsAt)
        assertEquals(2_000, resolved.statusChangedAt)

        val (refired, change2) = AlertRepository.merge(resolved, firing, fp, url, now = 3_000)
        assertEquals(AlertChange.REFIRED, change2)
        assertFalse(refired.acknowledged)
        assertNull(refired.endsAt)
        assertEquals(3_000, refired.firstSeenAt)
    }

    @Test
    fun `suppressed alert resolves quietly`() {
        val (muted, _) = AlertRepository.merge(null, silenced, fp, url, now = 1_000)
        val (_, change) = AlertRepository.resolve(muted, now = 2_000)
        assertEquals(AlertChange.RESOLVED_QUIETLY, change)
    }

    @Test
    fun `filter drops ignored names and keeps everything else by default`() {
        val kept = AlertRepository.filter(alerts, AppSettings()).map { it.alertName }
        assertEquals(listOf("KubePodCrashLooping", "TargetDown"), kept)
    }

    @Test
    fun `filter by receiver`() {
        val kept = AlertRepository.filter(alerts, AppSettings(receiverFilter = "phone, pager")).map { it.alertName }
        assertEquals(listOf("KubePodCrashLooping"), kept)
    }

    @Test
    fun `heartbeat alert is never shown as an alert`() {
        val settings = AppSettings(ignoredAlertNames = "", heartbeatEnabled = true, heartbeatAlertName = "Watchdog")
        val kept = AlertRepository.filter(alerts, settings).map { it.alertName }
        assertEquals(listOf("KubePodCrashLooping", "TargetDown", "InfoInhibitor"), kept)
    }
}
