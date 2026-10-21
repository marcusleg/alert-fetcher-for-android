package de.marcusleg.alertfetcher.monitor

import de.marcusleg.alertfetcher.data.AppSettings
import de.marcusleg.alertfetcher.data.MonitorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val min = 60_000L
    private val settings = AppSettings(pollIntervalMinutes = 5, staleAfterMinutes = 15)

    private fun fail(state: MonitorState, at: Long, auth: Boolean = false, online: Boolean = true) =
        Health.onFailure(state, settings, at, "boom", auth, online)

    @Test
    fun `contact lost only after the threshold and at least two failures`() {
        var state = MonitorState(since = 0, lastSuccessAt = 0)
        var outcome = fail(state, 5 * min)
        assertTrue(outcome.events.isEmpty())
        state = outcome.state
        outcome = fail(state, 10 * min)
        assertTrue(outcome.events.isEmpty())
        state = outcome.state
        outcome = fail(state, 15 * min)
        assertEquals(listOf(HealthEvent.CONTACT_LOST), outcome.events)
        assertEquals(3, outcome.state.consecutiveFailures)
        // Not repeated.
        assertTrue(fail(outcome.state, 20 * min).events.isEmpty())
    }

    @Test
    fun `a single failure after a long interval does not alarm`() {
        val hourly = settings.copy(pollIntervalMinutes = 60)
        val outcome = Health.onFailure(MonitorState(since = 0, lastSuccessAt = 0), hourly, 60 * min, "boom", authProblem = false, online = true)
        assertTrue(outcome.events.isEmpty())
        assertEquals(120, hourly.effectiveStaleAfterMinutes)
    }

    @Test
    fun `being offline is not reported`() {
        var state = MonitorState(since = 0, lastSuccessAt = 0)
        repeat(6) { i -> state = fail(state, (i + 1) * 5 * min, online = false).also { assertTrue(it.events.isEmpty()) }.state }
        assertEquals(6, state.consecutiveFailures)
    }

    @Test
    fun `auth failure is reported immediately and once`() {
        val first = fail(MonitorState(since = 0, lastSuccessAt = 0), 1 * min, auth = true)
        assertEquals(listOf(HealthEvent.AUTH_FAILED), first.events)
        assertTrue(first.state.authProblem)
        assertTrue(fail(first.state, 6 * min, auth = true).events.isEmpty())
    }

    @Test
    fun `success after trouble restores`() {
        val broken = MonitorState(since = 0, lastSuccessAt = 0, consecutiveFailures = 4, contactLostNotified = true, lastError = "x")
        val outcome = Health.onSuccess(broken, settings, 30 * min, heartbeatSeen = false)
        assertEquals(listOf(HealthEvent.CONNECTION_RESTORED), outcome.events)
        assertEquals(0, outcome.state.consecutiveFailures)
        assertFalse(outcome.state.contactLostNotified)
        assertEquals(null, outcome.state.lastError)
    }

    @Test
    fun `heartbeat missing after threshold, then restored`() {
        val hb = settings.copy(heartbeatEnabled = true)
        var state = MonitorState(since = 0)
        state = Health.onSuccess(state, hb, 0, heartbeatSeen = true).state
        var outcome = Health.onSuccess(state, hb, 10 * min, heartbeatSeen = false)
        assertTrue(outcome.events.isEmpty())
        outcome = Health.onSuccess(outcome.state, hb, 15 * min, heartbeatSeen = false)
        assertEquals(listOf(HealthEvent.HEARTBEAT_MISSING), outcome.events)
        outcome = Health.onSuccess(outcome.state, hb, 20 * min, heartbeatSeen = false)
        assertTrue(outcome.events.isEmpty())
        outcome = Health.onSuccess(outcome.state, hb, 25 * min, heartbeatSeen = true)
        assertEquals(listOf(HealthEvent.HEARTBEAT_RESTORED), outcome.events)
    }

    @Test
    fun `enabling the heartbeat late gives it a grace period`() {
        // Monitoring has been running for a day before the heartbeat is switched on.
        val hb = settings.copy(heartbeatEnabled = true)
        val state = MonitorState(since = 0, lastSuccessAt = 1440 * min)
        val outcome = Health.onSuccess(state, hb, 1445 * min, heartbeatSeen = false)
        assertTrue(outcome.events.isEmpty())
        assertEquals(1445 * min, outcome.state.heartbeatArmedAt)
        assertEquals(
            listOf(HealthEvent.HEARTBEAT_MISSING),
            Health.onSuccess(outcome.state, hb, 1460 * min, heartbeatSeen = false).events,
        )
    }
}
