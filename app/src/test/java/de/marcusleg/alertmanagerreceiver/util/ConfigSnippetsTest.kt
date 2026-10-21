package de.marcusleg.alertmanagerreceiver.util

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSnippetsTest {
    private val url = "http://192.168.1.50:9095/webhook"

    @Test
    fun `alertmanager yaml contains url and bearer credentials`() {
        val yaml = ConfigSnippets.alertmanagerYaml(url, AppSettings(authMode = AuthMode.BEARER, bearerToken = "tok"))
        assertTrue(yaml.contains("- url: $url"))
        assertTrue(yaml.contains("send_resolved: true"))
        assertTrue(yaml.contains("type: Bearer"))
        assertTrue(yaml.contains("credentials: tok"))
        assertFalse(yaml.contains("routes:"))
    }

    @Test
    fun `alertmanager yaml with basic auth and heartbeat route`() {
        val settings = AppSettings(
            authMode = AuthMode.BASIC, basicUsername = "u", basicPassword = "p",
            heartbeatEnabled = true, heartbeatAlertName = "Watchdog", heartbeatTimeoutMinutes = 15,
        )
        val yaml = ConfigSnippets.alertmanagerYaml(url, settings)
        assertTrue(yaml.contains("basic_auth:"))
        assertTrue(yaml.contains("username: u"))
        assertTrue(yaml.contains("matchers: [alertname=\"Watchdog\"]"))
        assertTrue(yaml.contains("repeat_interval: 5m"))
    }

    @Test
    fun `no auth emits no http_config`() {
        val yaml = ConfigSnippets.alertmanagerYaml(url, AppSettings(authMode = AuthMode.NONE))
        assertFalse(yaml.contains("http_config"))
    }

    @Test
    fun `crd references a secret instead of inlining the token`() {
        val crd = ConfigSnippets.alertmanagerConfigCrd(url, AppSettings(authMode = AuthMode.BEARER, bearerToken = "tok"))
        assertTrue(crd.contains("kind: Secret"))
        assertTrue(crd.contains("token: tok"))
        assertTrue(crd.contains("kind: AlertmanagerConfig"))
        assertTrue(crd.contains("key: token"))
        assertEquals(1, Regex("tok\\b").findAll(crd).count())
    }

    @Test
    fun `heartbeat repeat interval stays well inside the timeout`() {
        assertEquals(1, ConfigSnippets.heartbeatRepeatMinutes(AppSettings(heartbeatTimeoutMinutes = 2)))
        assertEquals(10, ConfigSnippets.heartbeatRepeatMinutes(AppSettings(heartbeatTimeoutMinutes = 30)))
        assertEquals(40, ConfigSnippets.heartbeatRepeatMinutes(AppSettings(heartbeatTimeoutMinutes = 120)))
    }

    @Test
    fun `curl test uses the configured auth`() {
        assertTrue(ConfigSnippets.curlTest(url, AppSettings(authMode = AuthMode.BASIC, basicUsername = "u", basicPassword = "p")).contains("-u 'u:p'"))
    }
}
