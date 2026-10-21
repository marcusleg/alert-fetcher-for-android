package de.marcusleg.alertmanagerreceiver.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeverityTest {
    @Test
    fun `maps common severity labels`() {
        assertEquals(Severity.CRITICAL, Severity.fromLabel("critical"))
        assertEquals(Severity.CRITICAL, Severity.fromLabel(" Error "))
        assertEquals(Severity.CRITICAL, Severity.fromLabel("page"))
        assertEquals(Severity.WARNING, Severity.fromLabel("warning"))
        assertEquals(Severity.WARNING, Severity.fromLabel("WARN"))
        assertEquals(Severity.INFO, Severity.fromLabel("info"))
        assertEquals(Severity.INFO, Severity.fromLabel("none"))
    }

    @Test
    fun `unknown and missing severities are warnings`() {
        assertEquals(Severity.WARNING, Severity.fromLabel(null))
        assertEquals(Severity.WARNING, Severity.fromLabel("spicy"))
    }

    @Test
    fun `orders severities`() {
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.WARNING))
        assertTrue(Severity.WARNING.isAtLeast(Severity.WARNING))
        assertFalse(Severity.INFO.isAtLeast(Severity.WARNING))
    }

    @Test
    fun `parses status`() {
        assertEquals(AlertStatus.RESOLVED, AlertStatus.fromString("resolved"))
        assertEquals(AlertStatus.FIRING, AlertStatus.fromString("firing"))
        assertEquals(AlertStatus.FIRING, AlertStatus.fromString(null))
    }
}
