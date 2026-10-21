package de.marcusleg.alertmanagerreceiver.alertmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertmanagerClientTest {
    @Test
    fun `builds API urls from different base urls`() {
        assertEquals("http://am:9093/api/v2/silences", AlertmanagerClient.apiUrl("http://am:9093", "silences"))
        assertEquals("http://am:9093/api/v2/silences", AlertmanagerClient.apiUrl("http://am:9093/", "silences"))
        assertEquals("https://x.lan/alertmanager/api/v2/silences", AlertmanagerClient.apiUrl("https://x.lan/alertmanager", "silences"))
        assertEquals("http://am:9093/api/v2/silences", AlertmanagerClient.apiUrl(" http://am:9093/api/v2/ ", "silences"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects urls without scheme`() {
        AlertmanagerClient.apiUrl("am:9093", "silences")
    }

    @Test
    fun `matches every label exactly`() {
        val matchers = AlertmanagerClient.silenceMatchers(mapOf("b" to "2", "a" to "1"))
        assertEquals(listOf(Matcher("a", "1"), Matcher("b", "2")), matchers)
    }
}
