package de.marcusleg.alertmanagerreceiver.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun `formats durations compactly`() {
        assertEquals("42s", TimeFormat.duration(42_000))
        assertEquals("5m", TimeFormat.duration(5 * 60_000L))
        assertEquals("2h 13m", TimeFormat.duration((2 * 60 + 13) * 60_000L))
        assertEquals("3h", TimeFormat.duration(3 * 3_600_000L))
        assertEquals("1d 4h", TimeFormat.duration(28 * 3_600_000L))
    }

    @Test
    fun `formats relative times`() {
        assertEquals("just now", TimeFormat.relative(1_000, now = 30_000))
        assertEquals("5m ago", TimeFormat.relative(0, now = 5 * 60_000L))
        assertEquals("in 2h", TimeFormat.relative(2 * 3_600_000L, now = 0))
    }

}
