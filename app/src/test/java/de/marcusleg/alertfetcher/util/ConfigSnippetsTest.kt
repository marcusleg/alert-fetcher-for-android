package de.marcusleg.alertfetcher.util

import de.marcusleg.alertfetcher.data.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSnippetsTest {
    @Test
    fun `route uses the default receiver name`() {
        val yaml = ConfigSnippets.alertmanagerRoute(AppSettings())
        assertTrue(yaml.contains("  - name: phone"))
        assertTrue(yaml.contains("    - receiver: phone"))
        assertTrue(yaml.contains("continue: true"))
    }

    @Test
    fun `route and CRD follow the receiver filter`() {
        val settings = AppSettings(receiverFilter = "monitoring/pager/oncall-phone, other")
        assertTrue(ConfigSnippets.alertmanagerRoute(settings).contains("- receiver: oncall-phone"))
        val crd = ConfigSnippets.alertmanagerConfigCrd(settings)
        assertTrue(crd.contains("kind: AlertmanagerConfig"))
        assertTrue(crd.contains("    receiver: oncall-phone"))
        assertTrue(crd.contains("    - name: oncall-phone"))
    }

    @Test
    fun `keep_firing_for outlasts the check interval`() {
        assertTrue(ConfigSnippets.keepFiringRule(AppSettings(pollIntervalMinutes = 1)).contains("keep_firing_for: 10m"))
        assertTrue(ConfigSnippets.keepFiringRule(AppSettings(pollIntervalMinutes = 5)).contains("keep_firing_for: 10m"))
        assertTrue(ConfigSnippets.keepFiringRule(AppSettings(pollIntervalMinutes = 30)).contains("keep_firing_for: 60m"))
    }

    @Test
    fun `oauth2-proxy args contain issuer and client`() {
        val args = ConfigSnippets.oauth2ProxyArgs(AppSettings(oidcIssuer = " https://sso.lan/ ", oidcClientId = "af"))
        assertTrue(args.contains("--skip-jwt-bearer-tokens=true"))
        assertTrue(args.contains("--extra-jwt-issuers=https://sso.lan/=af"))
    }
}
