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

    @Test
    fun `classifies interfaces`() {
        assertEquals(NetworkAddresses.Kind.WIFI, NetworkAddresses.kindOf("wlan0"))
        assertEquals(NetworkAddresses.Kind.VPN, NetworkAddresses.kindOf("tun0"))
        assertEquals(NetworkAddresses.Kind.MOBILE, NetworkAddresses.kindOf("rmnet_data1"))
        assertEquals(NetworkAddresses.Kind.OTHER, NetworkAddresses.kindOf("dummy0"))
    }

    @Test
    fun `builds webhook urls for v4 and v6`() {
        val v4 = NetworkAddresses.Address("192.168.1.5", "wlan0", NetworkAddresses.Kind.WIFI)
        val v6 = NetworkAddresses.Address("fd00::5", "wlan0", NetworkAddresses.Kind.WIFI)
        assertEquals("http://192.168.1.5:9095/webhook", NetworkAddresses.webhookUrl(v4, 9095))
        assertEquals("http://[fd00::5]:9095/webhook", NetworkAddresses.webhookUrl(v6, 9095))
    }
}
