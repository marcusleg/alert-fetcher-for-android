package de.marcusleg.alertfetcher.alertmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertmanagerApiTest {
    @Test
    fun `builds API urls from different base urls`() {
        assertEquals("https://am.lan/api/v2/alerts", AlertmanagerApi.apiUrl("https://am.lan", "alerts"))
        assertEquals("https://am.lan:9093/api/v2/silences", AlertmanagerApi.apiUrl("https://am.lan:9093/", "silences"))
        assertEquals("https://x.lan/alertmanager/api/v2/alerts", AlertmanagerApi.apiUrl("https://x.lan/alertmanager", "alerts"))
        assertEquals("https://am.lan/api/v2/alerts", AlertmanagerApi.apiUrl(" https://am.lan/api/v2/ ", "alerts"))
        assertEquals("HTTPS://am.lan/api/v2/alerts", AlertmanagerApi.apiUrl("HTTPS://am.lan", "alerts"))
    }

    @Test
    fun `only https is accepted`() {
        val e = assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.apiUrl("http://am.lan:9093", "alerts") }
        assertEquals("Only https:// URLs are supported", e.message)
        assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.apiUrl("am.lan:9093", "alerts") }
    }

    @Test
    fun `rejects urls that would leak or confuse`() {
        assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.validateBaseUrl("https://user:pw@am.lan") }
        assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.validateBaseUrl("https://am.lan/?x=1") }
        assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.validateBaseUrl("https://") }
        assertThrows(IllegalArgumentException::class.java) { AlertmanagerApi.validateBaseUrl("   ") }
    }

    @Test
    fun `matches every label exactly`() {
        val matchers = AlertmanagerApi.silenceMatchers(mapOf("b" to "2", "a" to "1"))
        assertEquals(listOf(Matcher("a", "1"), Matcher("b", "2")), matchers)
    }
}
