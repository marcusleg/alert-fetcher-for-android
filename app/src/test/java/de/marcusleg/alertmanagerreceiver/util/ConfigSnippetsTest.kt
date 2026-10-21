package de.marcusleg.alertmanagerreceiver.util

import de.marcusleg.alertmanagerreceiver.data.AppSettings
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
    fun `oauth2-proxy args contain issuer and client`() {
        val args = ConfigSnippets.oauth2ProxyArgs(AppSettings(oidcIssuer = " https://sso.lan/ ", oidcClientId = "amr"))
        assertTrue(args.contains("--skip-jwt-bearer-tokens=true"))
        assertTrue(args.contains("--extra-jwt-issuers=https://sso.lan/=amr"))
    }
}
